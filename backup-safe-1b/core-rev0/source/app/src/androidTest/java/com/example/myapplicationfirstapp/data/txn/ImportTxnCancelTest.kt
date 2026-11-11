package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 取消与 IO 异常必须分开处理：
 *
 * - 可取消段内取消 → 回滚后重抛**同一个** `CancellationException`；
 * - 不可取消段内取消 → 延迟投递，事务按已提交处理，不回滚；
 * - rename/凭据的 IO 异常 → 回滚，且异常不得被包装成取消。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnCancelTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2)
    private val newB = byteArrayOf(3, 3)

    private fun seed() {
        sandbox.writeNotes(oldNotes)
        sandbox.writeAttachment("a.jpg", oldA)
    }

    private suspend fun materialize(kernel: ImportTxnKernel): String {
        val result = kernel.materialize(
            "t1",
            newNotes.toByteArray(),
            mapOf("a.jpg" to newA, "b.jpg" to newB)
        )
        assertTrue("材料落地失败：$result", result is MaterializeOutcome.Ready)
        return (result as MaterializeOutcome.Ready).commitDigest
    }

    @Test
    fun cancelDuringReplacementRollsBackAndRethrowsSameException() = runBlocking {
        seed()
        val ops = FaultyOps()
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)
        // 必须在物化之后设置：materialize 也走同一个注入点名字
        ops.blockAt = "new-attachment:b.jpg"

        var caught: Throwable? = null
        var jobCause: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                kernel.commit(MaterialRef("t1", digest))
            } catch (failure: Throwable) {
                caught = failure
                throw failure
            }
        }
        job.invokeOnCompletion { jobCause = it }

        assertTrue("未到达取消窗口", ops.blocked.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        val thrown = caught
        assertTrue("必须是取消，实际=$thrown", thrown is CancellationException)
        assertSame("必须重抛同一个取消实例", jobCause, thrown)

        sandbox.expectNotes(oldNotes, "取消后必须回到旧笔记")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "取消后必须回到旧附件")
        assertNotNull(
            "回滚要留下记录：results=${sandbox.results()} import=${File(sandbox.root, ".import").list()?.toList()}",
            sandbox.results().firstOrNull { it.txnId == "t1" }
        )
        assertEquals(DiskState.Aborted("t1"), kernel.inspect())

        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun cancelInsideNonCancellableWindowCommitsAndDoesNotRollBack() = runBlocking {
        seed()
        val ops = FaultyOps()
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)
        ops.blockAt = TxnStep.NOTES

        var outcome: CommitOutcome? = null
        var failure: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                outcome = kernel.commit(MaterialRef("t1", digest))
            } catch (thrown: Throwable) {
                failure = thrown
                throw thrown
            }
        }

        assertTrue("未到达不可取消段", ops.blocked.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        // 取消是在提交点之后才投递的：返回值可能拿不到，但磁盘必须已经是已提交状态
        assertTrue("必须以取消结束，实际=$outcome", job.isCancelled)
        assertTrue("收到的应是取消，实际=$failure", failure is CancellationException)
        sandbox.expectNotes(newNotes, "不可取消段一旦开始就必须走完")
        sandbox.expectAttachments(mapOf("a.jpg" to newA, "b.jpg" to newB), "附件应为新内容")
        assertEquals(DiskState.Idle, kernel.inspect())
        assertEquals(ResultOutcome.COMMITTED, sandbox.results().single().outcome)
    }

    @Test
    fun ioFailureAtNotesIsTreatedAsRollbackNotCancellation() = runBlocking {
        seed()
        val ops = FaultyOps().apply { failOnce += TxnStep.NOTES }
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)

        val outcome = kernel.commit(MaterialRef("t1", digest))
        assertTrue("应回滚，实际=$outcome", outcome is CommitOutcome.Failed)

        sandbox.expectNotes(oldNotes, "notes 写失败必须回滚到旧内容")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "附件必须回滚")
        assertEquals(DiskState.Idle, kernel.inspect())
        assertEquals(ResultOutcome.ABORTED, sandbox.results().single().outcome)
    }

    @Test
    fun ioFailureAtCredentialLeavesNoCredentialAndRollsBack() = runBlocking {
        seed()
        val ops = FaultyOps().apply { failOnce += TxnStep.CREDENTIAL }
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)

        val outcome = kernel.commit(MaterialRef("t1", digest))
        assertTrue("应回滚，实际=$outcome", outcome is CommitOutcome.Failed)

        assertFalse("凭据不得存在", sandbox.credentialFile().exists())
        sandbox.expectNotes(oldNotes, "凭据写失败必须回滚")
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun cancelDuringBackupAlsoRollsBack() = runBlocking {
        seed()
        val ops = FaultyOps()
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)
        ops.blockAt = TxnStep.OLD_NOTES

        var caught: Throwable? = null
        var jobCause: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                kernel.commit(MaterialRef("t1", digest))
            } catch (failure: Throwable) {
                caught = failure
                throw failure
            }
        }
        job.invokeOnCompletion { jobCause = it }

        assertTrue(ops.blocked.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertTrue("必须是取消，实际=$caught", caught is CancellationException)
        assertSame("备份阶段取消也必须重抛同一实例", jobCause, caught)
        sandbox.expectNotes(oldNotes, "备份阶段取消时正式数据未动")
        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
    }
}
