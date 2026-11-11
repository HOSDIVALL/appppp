package com.example.myapplicationfirstapp.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 资源限制：全部按**实际读取/解压的字节**判定，不看 ZIP 声明的长度，也不算压缩比。
 *
 * 用很小的上限跑，保证用例快且能精确命中每一条拒绝分支；
 * 另外有一条用例断言生产默认值没被这些测试改动。
 */
@RunWith(AndroidJUnit4::class)
class BackupImporterLimitsTest {

    private lateinit var sandbox: IsolatedAppContext
    private lateinit var before: RealDataFingerprint
    private val violations = mutableListOf<String>()

    @Before
    fun setUp() {
        sandbox = isolatedStorage("backup-1a-limits")
        before = realDataFingerprint()
    }

    @After
    fun tearDown() {
        violations += finishImportTest(sandbox, before)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun defaultsAreTheDocumentedOnes() {
        val limits = BackupLimits.DEFAULT
        assertEquals(128L * 1024 * 1024, limits.maxZipBytes)
        assertEquals(32L * 1024 * 1024, limits.maxNotesBytes)
        assertEquals(64L * 1024 * 1024, limits.maxEntryBytes)
        assertEquals(256L * 1024 * 1024, limits.maxTotalBytes)
        assertEquals(2000, limits.maxEntries)
        assertEquals(128, limits.maxNameLength)
    }

    @Test
    fun tooManyEntriesIsRejected() {
        // 1 个 notes.json + 3 个附件 = 4 条，超过上限 3
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxEntries = 3),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(4) { 1 }),
            ZipPiece("attachments/b.jpg", ByteArray(4) { 2 }),
            ZipPiece("attachments/c.jpg", ByteArray(4) { 3 })
        )
        assertInvalid(result, InvalidReason.TooManyEntries)
    }

    @Test
    fun singleEntryOverRealByteLimitIsRejected() {
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxEntryBytes = 100),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/big.jpg", ByteArray(200) { 1 })
        )
        assertInvalid(result, InvalidReason.EntryTooLarge)
    }

    @Test
    fun totalBytesOverLimitIsRejected() {
        val notes = notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxTotalBytes = notes.size + 50L),
            ZipPiece("notes.json", notes),
            ZipPiece("attachments/a.jpg", ByteArray(120) { 1 })
        )
        assertInvalid(result, InvalidReason.TooLarge)
    }

    @Test
    fun notesOverLimitIsRejected() {
        val notes = notesToJson(listOf(Note(id = "n", title = "标题".repeat(20)))).toByteArray()
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxNotesBytes = notes.size - 1L),
            ZipPiece("notes.json", notes)
        )
        assertInvalid(result, InvalidReason.NotesTooLarge)
    }

    @Test
    fun zipCopyOverLimitIsRejected() {
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxZipBytes = 40),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray())
        )
        assertInvalid(result, InvalidReason.ZipTooLarge)
    }

    @Test
    fun overlongAttachmentNameIsRejected() {
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxNameLength = 8),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/long-name.jpg", ByteArray(4) { 1 })
        )
        assertInvalid(result, InvalidReason.DangerousPath)
    }

    @Test
    fun unknownEntriesAlsoConsumeTheBudget() {
        val notes = notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()
        val result = prepare(
            BackupLimits.DEFAULT.copy(maxTotalBytes = notes.size + 40L),
            ZipPiece("notes.json", notes),
            ZipPiece("misc/blob.bin", ByteArray(512) { 9 })
        )
        assertInvalid(result, InvalidReason.TooLarge)
    }

    @Test
    fun declaredEntrySizeIsNotTrusted() {
        // ZipOutputStream 在写入时把本地文件头里的 uncompressed size 写成 0（真正的长度放在
        // data descriptor 里）。这里先证明「声明值确实是 0」，再证明限制按实际 200 字节触发。
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("attachments/liar.jpg", ByteArray(200) { 7 })
        )
        val header = zip.readBytes()
        assertTrue("本地文件头长度不足", header.size > 26)
        assertEquals("声明长度应为 0", 0, header[22].toInt())
        assertEquals(0, header[23].toInt())
        assertEquals(0, header[24].toInt())
        assertEquals(0, header[25].toInt())

        val result = runBlocking {
            BackupImporter(sandbox, RealBackupFileOps(sandbox), BackupLimits.DEFAULT.copy(maxEntryBytes = 100))
                .prepare(Uri.fromFile(zip))
        }
        assertInvalid(result, InvalidReason.EntryTooLarge)
    }

    // ---------- 工具 ----------

    private fun zipFile(): File =
        File(sandbox.filesDir, "input/backup-${System.nanoTime()}.zip").also { it.parentFile?.mkdirs() }

    private fun prepare(limits: BackupLimits, vararg pieces: ZipPiece): PrepareResult =
        runBlocking {
            BackupImporter(sandbox, RealBackupFileOps(sandbox), limits)
                .prepare(Uri.fromFile(buildBackupZip(zipFile(), *pieces)))
        }

    private fun assertInvalid(result: PrepareResult, reason: InvalidReason) {
        assertTrue("expected Invalid($reason), got $result", result is PrepareResult.Invalid)
        assertEquals(reason, (result as PrepareResult.Invalid).reason)
        val leftovers = File(sandbox.filesDir, ".import").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("失败路径必须清掉暂存：$leftovers", leftovers.isEmpty())
    }
}
