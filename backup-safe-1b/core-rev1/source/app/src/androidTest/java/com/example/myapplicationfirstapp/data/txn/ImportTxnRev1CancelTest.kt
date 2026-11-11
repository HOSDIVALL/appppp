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
import java.util.concurrent.TimeUnit

/**
 * core-rev1 回归（B5）：备份阶段被取消时，**由当前这次 commit 自己收尾**。
 *
 * 断言顺序很关键：先看取消结束之后的磁盘状态，不先 `recover()`——
 * 否则无法区分「这次调用完成了回滚」与「下一次启动补上了回滚」。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev1CancelTest : TxnKernelTestBase() {

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
        val result = kernel.materialize("t1", newNotes.toByteArray(), mapOf("a.jpg" to newA, "b.jpg" to newB))
        assertTrue("材料落地失败：$result", result is MaterializeOutcome.Ready)
        return (result as MaterializeOutcome.Ready).commitDigest
    }

    @Test
    fun cancelWhileCopyingOldNotesIsSettledByTheSameCommit() = runBlocking {
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

        assertTrue("未到达备份窗口", ops.blocked.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertTrue("必须是取消，实际=$caught", caught is CancellationException)
        assertSame("必须重抛同一个取消实例", jobCause, caught)

        // 不调用 recover：下面每一条都必须是这次 commit 自己做到的
        sandbox.expectNotes(oldNotes, "备份阶段取消后正式数据必须仍是旧内容")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "备份阶段取消不得改动附件")
        assertFalse("取消后不得留下凭据", sandbox.credentialFile().exists())
        assertNotNull(
            "取消必须由当前 commit 收尾并留下回滚记录，实际=${sandbox.results()}",
            sandbox.results().firstOrNull { it.txnId == "t1" }
        )
        assertEquals(ResultOutcome.ABORTED, sandbox.results().first { it.txnId == "t1" }.outcome)
        assertEquals("应停在已回滚，而不是「需要下次启动再回滚」", DiskState.Aborted("t1"), kernel.inspect())
    }

    @Test
    fun cancelWhileCopyingOldAttachmentIsSettledByTheSameCommit() = runBlocking {
        seed()
        val ops = FaultyOps()
        val kernel = sandbox.kernel(ops)
        val digest = materialize(kernel)
        ops.blockAt = TxnStep.oldAttachment("a.jpg")

        var caught: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                kernel.commit(MaterialRef("t1", digest))
            } catch (failure: Throwable) {
                caught = failure
                throw failure
            }
        }

        assertTrue("未到达备份窗口", ops.blocked.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertTrue("必须是取消，实际=$caught", caught is CancellationException)
        sandbox.expectNotes(oldNotes, "取消后不得改动正式笔记")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "取消后不得改动正式附件")
        assertFalse(sandbox.credentialFile().exists())
        assertNotNull(
            "取消必须留下回滚记录，实际=${sandbox.results()}",
            sandbox.results().firstOrNull { it.txnId == "t1" }
        )
    }
}
