package com.example.myapplicationfirstapp.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 准备层的正常路径、空备份、告警与暂存生命周期。
 *
 * 每个用例都在 UUID 沙箱里跑；`@After` 比对真实 notes.json / attachments / recordings 指纹，
 * 并删除本次沙箱（失败时也执行）。
 */
@RunWith(AndroidJUnit4::class)
class BackupImporterPrepareTest {

    private lateinit var sandbox: IsolatedAppContext
    private lateinit var before: RealDataFingerprint
    private val violations = mutableListOf<String>()

    @Before
    fun setUp() {
        sandbox = isolatedStorage("backup-1a-prepare")
        before = realDataFingerprint()
    }

    @After
    fun tearDown() {
        violations += finishImportTest(sandbox, before)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    // ---------- 正常备份 ----------

    @Test
    fun readyReturnsNotesAttachmentsAndDigest() {
        val note = Note(
            id = "note-1",
            title = "标题",
            content = "正文\n![图片](a.jpg)",
            createdAt = 100L,
            updatedAt = 200L,
            checklist = true,
            todos = listOf(TodoItem(id = "todo-1", text = "任务", done = true)),
            images = listOf("a.jpg"),
            remindAt = 9_999_999_999L
        )
        val notesJson = notesToJson(listOf(note))

        val result = prepare(
            ZipPiece("notes.json", notesJson.toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(64) { 1 }),
            ZipPiece("attachments/b.jpg", ByteArray(32) { 2 }),
            ZipPiece("readme.txt", ByteArray(7)) // 未知条目：忽略但计入限额
        )

        val prepared = ready(result)
        assertEquals(1, prepared.notes.size)
        val restored = prepared.notes.single()
        assertEquals(note.id, restored.id)
        assertEquals(note.title, restored.title)
        assertEquals(note.content, restored.content)
        assertEquals(note.todos, restored.todos)
        assertEquals(note.images, restored.images)
        assertEquals(note.remindAt, restored.remindAt)

        assertEquals(listOf("a.jpg", "b.jpg"), prepared.attachmentNames)
        assertEquals(4, prepared.entryCount)
        assertEquals(sha256(notesJson.toByteArray()), prepared.notesDigest)
        assertTrue(prepared.warnings.none { it.kind == WarningKind.MissingImages })
        assertTrue(prepared.warnings.none { it.kind == WarningKind.RecordingsNotInBackup })

        // 暂存是真的落盘了，而且落在 files/ 下（不是 cache）
        assertTrue(prepared.backupFile.isFile)
        assertTrue(File(prepared.stagingDir, "attachments/a.jpg").isFile)
        assertEquals(64L, File(prepared.stagingDir, "attachments/a.jpg").length())
        assertTrue(prepared.stagingDir.absolutePath.startsWith(sandbox.filesDir.absolutePath))
        assertEquals(64L + 32L, prepared.attachmentBytes)
    }

    @Test
    fun sourceUriIsOpenedExactlyOnce() {
        val ops = CountingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)
        val zip = buildBackupZip(
            zipFile(),
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(16) { 3 })
        )

        val result = runBlocking { importer.prepare(Uri.fromFile(zip)) }
        ready(result)
        assertEquals("URI 只能被打开一次", 1, ops.openCount)
    }

    @Test
    fun legacyBackupWithoutOptionalFieldsKeepsDefaults() {
        val result = prepare(ZipPiece("notes.json", """[{"id":"legacy","title":"Old"}]""".toByteArray()))
        val prepared = ready(result)
        val note = prepared.notes.single()
        assertEquals("legacy", note.id)
        assertEquals("Old", note.title)
        assertTrue(note.audio.isEmpty())
        assertTrue(note.images.isEmpty())
        assertTrue(note.todos.isEmpty())
        assertFalse(note.checklist)
        assertEquals(null, note.remindAt)
        assertTrue(prepared.warnings.isEmpty())
    }

    @Test
    fun nullOptionalTimestampsAreAccepted() {
        val result = prepare(
            ZipPiece(
                "notes.json",
                """[{"id":"n","title":"t","deletedAt":null,"remindAt":null}]""".toByteArray()
            )
        )
        val note = ready(result).notes.single()
        assertEquals(null, note.deletedAt)
        assertEquals(null, note.remindAt)
    }

    // ---------- 合法空备份 ----------

    @Test
    fun emptyArrayIsReportedAsEmptyNotInvalid() {
        val result = prepare(ZipPiece("notes.json", "[]".toByteArray()))
        assertTrue("空备份必须走 Empty 而不是 Invalid", result is PrepareResult.Empty)
        val prepared = (result as PrepareResult.Empty).prepared
        assertTrue(prepared.isEmpty)
        assertTrue(prepared.notes.isEmpty())
        assertEquals(sha256("[]".toByteArray()), prepared.notesDigest)
    }

    @Test
    fun directoryPlaceholderDoesNotCountAsAttachment() {
        val result = prepare(
            ZipPiece("notes.json", "[]".toByteArray()),
            ZipPiece("attachments/", ByteArray(0))
        )
        assertTrue(result is PrepareResult.Empty)
    }

    // ---------- 告警 ----------

    @Test
    fun missingImagesWarnButKeepReferences() {
        val note = Note(
            id = "n1",
            title = "t",
            content = "![图片](c.jpg)",
            images = listOf("a.jpg", "b.jpg")
        )
        val result = prepare(
            ZipPiece("notes.json", notesToJson(listOf(note)).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(8) { 4 })
        )

        val prepared = ready(result)
        assertEquals("引用必须原样保留", listOf("a.jpg", "b.jpg"), prepared.notes.single().images)
        val warning = prepared.warnings.single { it.kind == WarningKind.MissingImages }
        assertEquals(2, warning.total)
        assertEquals(listOf("b.jpg", "c.jpg"), warning.names.sorted())
    }

    @Test
    fun recordingReferencesWarnWithoutTouchingRecordings() {
        val note = Note(
            id = "n1",
            title = "t",
            content = "正文\n!audio(REC_inline.m4a)",
            audio = listOf("REC_attached.m4a")
        )
        val result = prepare(ZipPiece("notes.json", notesToJson(listOf(note)).toByteArray()))

        val prepared = ready(result)
        assertEquals(listOf("REC_attached.m4a"), prepared.notes.single().audio)
        val warning = prepared.warnings.single { it.kind == WarningKind.RecordingsNotInBackup }
        assertEquals(2, warning.total)
        assertTrue(warning.names.containsAll(listOf("REC_attached.m4a", "REC_inline.m4a")))
        // 沙箱里没有 recordings 目录被创建
        assertFalse(File(sandbox.filesDir, "recordings").exists())
    }

    @Test
    fun warningDisplaySanitizesControlCharactersAndTruncates() {
        val longName = "图".repeat(120) + ".jpg"
        val warning = ImportWarning(WarningKind.MissingImages, listOf(longName, "a\nb.jpg", "c\u0000d.jpg"), 9)

        val text = warning.displaySummary(maxNames = 3, maxNameLength = 20)
        assertFalse("不能带换行", text.contains('\n'))
        assertFalse("不能带 NUL", text.contains('\u0000'))
        assertTrue(text.contains("…"))
        assertTrue(text.contains("等 9 个"))
        assertTrue(text.length < 200)
    }

    @Test
    fun sanitizeHandlesTabAndTrailingControlNoise() {
        assertEquals("a  b", sanitizeForDisplay("a\t\nb"))
        assertEquals("?", sanitizeForDisplay("\u0001"))
        val truncated = sanitizeForDisplay("x".repeat(50), maxLength = 10)
        assertEquals(10, truncated.length)
        assertTrue(truncated.endsWith("…"))
    }

    // ---------- 暂存生命周期 ----------

    @Test
    fun stagingLivesUnderFilesAndDiscardRemovesIt() {
        val result = prepare(
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray())
        )
        val prepared = ready(result)
        assertTrue(prepared.stagingDir.absolutePath.startsWith(sandbox.filesDir.absolutePath))
        assertFalse(prepared.stagingDir.absolutePath.startsWith(sandbox.cacheDir.absolutePath))

        assertTrue(BackupImporter(sandbox).discard(prepared))
        assertFalse(prepared.stagingDir.exists())
    }

    @Test
    fun prepareDoesNotTouchLiveNotesOrAttachments() {
        val result = prepare(
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(8) { 5 })
        )
        ready(result)
        assertFalse("准备层不得写 live notes.json", File(sandbox.filesDir, "notes.json").exists())
        assertFalse("准备层不得写 live attachments", File(sandbox.filesDir, "attachments").exists())
    }

    @Test
    fun unknownEntriesDoNotLandOnDisk() {
        val result = prepare(
            ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
            ZipPiece("misc/payload.bin", ByteArray(128) { 6 })
        )
        val prepared = ready(result)
        assertFalse(File(prepared.stagingDir, "misc").exists())
        assertEquals(2, prepared.entryCount)
    }

    // ---------- 工具 ----------

    private fun zipFile(): File =
        File(sandbox.filesDir, "input/backup-${System.nanoTime()}.zip").also { it.parentFile?.mkdirs() }

    private fun prepare(vararg pieces: ZipPiece): PrepareResult =
        runBlocking { BackupImporter(sandbox).prepare(Uri.fromFile(buildBackupZip(zipFile(), *pieces))) }

    private fun ready(result: PrepareResult): PreparedBackup {
        assertNotNull("expected Ready, got $result", result as? PrepareResult.Ready)
        return (result as PrepareResult.Ready).prepared
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
