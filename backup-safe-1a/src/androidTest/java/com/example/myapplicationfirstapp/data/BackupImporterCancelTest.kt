package com.example.myapplicationfirstapp.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun zipFile(): File =
        File(sandbox.filesDir, "input/backup-${System.nanoTime()}.zip").also { it.parentFile?.mkdirs() }
}
