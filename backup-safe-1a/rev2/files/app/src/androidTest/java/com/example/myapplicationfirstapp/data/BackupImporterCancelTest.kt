package com.example.myapplicationfirstapp.data

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 准备阶段取消：协程取消后必须**删掉本次暂存**，且不产生任何 live 写入。
 *
 * 用一个会在第一次复制回调处阻塞的接缝把取消时机固定下来，
 * 不依赖 sleep 或大文件大小。
 */
@RunWith(AndroidJUnit4::class)
class BackupImporterCancelTest {

    private lateinit var sandbox: IsolatedAppContext
    private lateinit var before: RealDataFingerprint
    private val violations = mutableListOf<String>()

    @Before
    fun setUp() {
        sandbox = isolatedStorage("backup-1a-cancel")
        before = realDataFingerprint()
    }

    @After
    fun tearDown() {
        violations += finishImportTest(sandbox, before)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun cancellingDuringCopyCleansStagingAndKeepsLiveData() = runBlocking {
        val ops = BlockingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(64) { 1 })
        )

        var cancelled: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                val result = importer.prepare(Uri.fromFile(zip))
                // 取消发生在复制途中，绝不该走到返回结果
                throw AssertionError("prepare 不应正常返回：$result")
            } catch (cancellation: CancellationException) {
                cancelled = cancellation
                throw cancellation
            }
        }

        assertTrue("复制未开始，无法确定取消时机", ops.started.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertTrue("job 应处于取消状态", job.isCancelled)
        assertTrue("应抛出 CancellationException", cancelled is CancellationException)

        val leftovers = File(sandbox.filesDir, ".import").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("取消后暂存必须清空：$leftovers", leftovers.isEmpty())
        assertFalse("取消不得写 live notes.json", File(sandbox.filesDir, "notes.json").exists())
        assertFalse("取消不得写 live attachments", File(sandbox.filesDir, "attachments").exists())
    }

    @Test
    fun cancelledPreparationLeavesNoResultToCommit() = runBlocking {
        val ops = BlockingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", "[]".toByteArray())
        )

        var prepared: PreparedBackup? = null
        val job = launch(Dispatchers.IO) {
            val result = importer.prepare(Uri.fromFile(zip))
            prepared = (result as? PrepareResult.Empty)?.prepared
        }

        assertTrue(ops.started.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertNull("取消后不应产生可提交的 PreparedBackup", prepared)
        assertTrue(job.isCancelled)
    }

    /**
     * 交付窗口取消：结果已经构造完、`withContext` 还没把它交给调用方时取消。
     *
     * 这一段的暂存目录不属于任何返回对象——如果只在块内清理，它就会永远留在 `.import` 下。
     */
    @Test
    fun cancellingAfterResultIsBuiltStillCleansStaging() = runBlocking {
        val ops = BlockingResultFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(32) { 1 })
        )

        var delivered: PrepareResult? = null
        var cancelled: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                delivered = importer.prepare(Uri.fromFile(zip))
            } catch (cancellation: CancellationException) {
                cancelled = cancellation
                throw cancellation
            }
        }

        assertTrue("结果未构造完成，无法确定取消时机", ops.started.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("必须原样抛出 CancellationException", cancelled is CancellationException)
        assertNull("交付窗口被取消时不能把结果交出去", delivered)

        val leftovers = File(sandbox.filesDir, ".import").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("交付窗口取消后暂存必须清空：$leftovers", leftovers.isEmpty())
        assertFalse(File(sandbox.filesDir, "notes.json").exists())
        assertFalse(File(sandbox.filesDir, "attachments").exists())
    }

    /**
     * 真正跨调度器的交付窗口：调用方在自建调度器上，块在 IO 上，取消从第三个线程（测试线程）发出。
     *
     * 关闭顺序：启动协程 → 块执行完、交付续体被排进队列（此刻结果已构造、调用方还没拿到值）
     * → 取消 → 才执行交付续体。只有这一步才真正踩到 `withContext` 的 resume 检查；
     * 原来的用例是在块**内部**取消，验的是另一条路径，保留不动。
     */
    @Test
    fun deliveryWindowCancellationAcrossDispatchers() = runBlocking {
        val dispatcher = PausableDispatcher()
        val ops = ThreadRecordingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(32) { 1 })
        )

        var delivered: PrepareResult? = null
        var cancelled: Throwable? = null
        var jobCause: Throwable? = null
        var callerThread: Thread? = null

        val job = CoroutineScope(dispatcher).launch {
            callerThread = Thread.currentThread()
            try {
                delivered = importer.prepare(Uri.fromFile(zip))
            } catch (cancellation: CancellationException) {
                cancelled = cancellation
                throw cancellation
            }
        }
        job.invokeOnCompletion { jobCause = it }

        assertTrue("启动任务应当已入队", dispatcher.runNext())
        assertTrue("交付续体未入队：块可能还没跑完", dispatcher.awaitResumeQueued())
        assertNotNull("块必须在自己的线程上留下记录", ops.resultBuiltThread)
        assertNotSame("块与调用方必须不在同一线程", callerThread, ops.resultBuiltThread)

        // 到这里：暂存已建好、结果已构造、调用方还没拿到值
        job.cancel()

        // 取消之后可能排进不止一个续体（交付 + 取消收尾），必须泵到队列空；
        // 只泵一次会让 join() 永远不返回——这正是本用例第一版踩到的坑。
        var pumped = 0
        while (!job.isCompleted && pumped < 20) {
            if (!dispatcher.runNext(timeoutMs = 2_000)) break
            pumped++
        }
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertNull("交付窗口被取消时不能交出结果", delivered)
        assertSame("抛出的必须是同一个取消实例", jobCause, cancelled)

        val leftovers = File(sandbox.filesDir, ".import").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("交付窗口取消后暂存必须清空：$leftovers", leftovers.isEmpty())
        assertNotSame("清理不能在主线程做 IO", Looper.getMainLooper().thread, ops.deleteThread)
        assertFalse(File(sandbox.filesDir, "notes.json").exists())
    }

    /** 清理本身失败（抛异常）时，取消异常仍然是原来那一个，不被替换 */
    @Test
    fun cleanupFailureDoesNotReplaceCancellation() = runBlocking {
        val blocking = BlockingFileOps(RealBackupFileOps(sandbox))
        val ops = ThrowingDeleteFileOps(blocking)
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray())
        )

        var cancelled: Throwable? = null
        var jobCause: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                importer.prepare(Uri.fromFile(zip))
            } catch (cancellation: CancellationException) {
                cancelled = cancellation
                throw cancellation
            }
        }
        job.invokeOnCompletion { jobCause = it }

        assertTrue(blocking.started.await(10, TimeUnit.SECONDS))
        job.cancel()
        blocking.release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertSame("清理抛异常不能顶掉取消：$cancelled", jobCause, cancelled)
        // 清理确实失败了，暂存只能留在原地（由下一次启动或用户清理），但不得影响取消语义
        assertTrue(File(sandbox.filesDir, ".import").isDirectory)
        // 清理确实被调用过，而且没有被取消打断
        assertTrue("清理必须被调用", ops.deleteCalls > 0)
    }

    @Test
    fun liveSentinelsSurviveCancellation() = runBlocking {
        val sentinel = plantLiveSentinels(sandbox.filesDir)
        val ops = BlockingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray())
        )

        val job = launch(Dispatchers.IO) {
            runCatching { importer.prepare(Uri.fromFile(zip)) }
        }

        assertTrue(ops.started.await(10, TimeUnit.SECONDS))
        job.cancel()
        ops.release.countDown()
        job.join()

        val problems = assertLiveSentinelsIntact(sandbox.filesDir, sentinel)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun zipFile(): File =
        File(sandbox.filesDir, "input/backup-${System.nanoTime()}.zip").also { it.parentFile?.mkdirs() }
}
