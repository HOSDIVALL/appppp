package com.example.myapplicationfirstapp.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 拒绝路径矩阵：结构、路径、重复、字段类型、引用安全、空备份夹带附件。
 *
 * 每个用例都断言两件事：返回的原因正确；暂存目录已被清掉（失败不留垃圾）。
 */
@RunWith(AndroidJUnit4::class)
class BackupImporterValidationTest {

    private lateinit var sandbox: IsolatedAppContext
    private lateinit var before: RealDataFingerprint
    private val violations = mutableListOf<String>()

    @Before
    fun setUp() {
        sandbox = isolatedStorage("backup-1a-validation")
        before = realDataFingerprint()
    }

    @After
    fun tearDown() {
        violations += finishImportTest(sandbox, before)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    // ---------- 结构 ----------

    @Test
    fun randomBytesAreNotAZip() {
        val file = zipFile()
        file.writeBytes(ByteArray(512) { (it * 7).toByte() })
        assertInvalid(runBlocking { BackupImporter(sandbox).prepare(Uri.fromFile(file)) }, InvalidReason.NotZip)
    }

    @Test
    fun emptyZipHasNoEntries() {
        assertInvalid(prepare(), InvalidReason.EmptyArchive)
    }

    @Test
    fun missingNotesJson() {
        assertInvalid(
            prepare(ZipPiece("attachments/a.jpg", ByteArray(8) { 1 })),
            InvalidReason.MissingNotes
        )
    }

    @Test
    fun corruptNotesPayloads() {
        listOf("[", "[] trailing", "{}", "[null]", "[7]").forEach { text ->
            assertInvalid(
                prepare(ZipPiece("notes.json", text.toByteArray())),
                InvalidReason.CorruptNotes
            )
        }
    }

    @Test
    fun corruptedEntryPayloadIsRejected() {
        val fileName = "attachments/a.jpg"
        val payload = ByteArray(64) { 1 }
        val zip = buildStoredZip(zipFile(), fileName, payload)
        val bytes = zip.readBytes()
        val payloadOffset = 30 + fileName.toByteArray(Charsets.UTF_8).size
        assertTrue("payload offset 越界", bytes.size > payloadOffset)
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0xFF).toByte()
        zip.writeBytes(bytes)

        val result = runBlocking { BackupImporter(sandbox).prepare(Uri.fromFile(zip)) }
        assertInvalid(result, InvalidReason.CorruptEntry)
    }

    // ---------- 重复 ----------

    @Test
    fun duplicateAttachmentEntryIsRejected() {
        val zip = buildDuplicateEntryZip(zipFile(), "attachments/a.jpg", ByteArray(4) { 1 }, ByteArray(4) { 2 })
        assertInvalid(
            runBlocking {
                BackupImporter(sandbox).prepare(Uri.fromFile(zip))
            }.also { _ -> assertNoStagingLeft() },
            InvalidReason.DuplicateEntry
        )
    }

    @Test
    fun duplicateNotesEntryIsRejected() {
        val zip = buildDuplicateEntryZip(zipFile(), "notes.json", "[]".toByteArray(), "[]".toByteArray())
        assertInvalid(
            runBlocking { BackupImporter(sandbox).prepare(Uri.fromFile(zip)) },
            InvalidReason.DuplicateEntry
        )
    }

    @Test
    fun duplicateNoteIdIsRejected() {
        val first = Note(id = "same", title = "a")
        val second = Note(id = "same", title = "b")
        assertInvalid(
            prepare(ZipPiece("notes.json", notesToJson(listOf(first, second)).toByteArray())),
            InvalidReason.DuplicateNoteId
        )
    }

    @Test
    fun duplicateTodoIdIsRejected() {
        val note = Note(
            id = "n",
            title = "t",
            checklist = true,
            todos = listOf(TodoItem(id = "dup", text = "a"), TodoItem(id = "dup", text = "b"))
        )
        assertInvalid(
            prepare(ZipPiece("notes.json", notesToJson(listOf(note)).toByteArray())),
            InvalidReason.DuplicateTodoId
        )
    }

    // ---------- 危险路径 ----------

    @Test
    fun dangerousAttachmentPathsAreRejected() {
        listOf(
            "attachments/../escape.jpg",
            "attachments/..",
            "attachments/.",
            "attachments/.hidden.jpg",
            "attachments/a/b.jpg",
            "attachments/sub\\x.jpg",
            "attachments//abs.jpg"
        ).forEach { name ->
            val result = prepare(
                ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()),
                ZipPiece(name, ByteArray(4) { 3 })
            )
            val detail = (result as? PrepareResult.Invalid)?.let { "${it.reason}/${it.detail}" } ?: result.toString()
            assertEquals("entry=$name -> $detail", InvalidReason.DangerousPath, (result as PrepareResult.Invalid).reason)
            assertNoStagingLeft()
        }
    }

    @Test
    fun nestedDirectoryEntryIsRejected() {
        assertInvalid(
            prepare(
                ZipPiece("notes.json", "[]".toByteArray()),
                ZipPiece("attachments/sub/", ByteArray(0))
            ),
            InvalidReason.DangerousPath
        )
    }

    @Test
    fun unsafeImageReferencesAreRejected() {
        listOf(
            """[{"id":"n","images":["../escape.jpg"]}]""",
            """[{"id":"n","images":["a/b.jpg"]}]""",
            """[{"id":"n","images":[".hidden.jpg"]}]""",
            """[{"id":"n","content":"![](../x.png)"}]"""
        ).forEach { json ->
            assertInvalid(
                prepare(ZipPiece("notes.json", json.toByteArray())),
                InvalidReason.UnsafeImageReference
            )
        }
    }

    // ---------- 字段类型 ----------

    @Test
    fun obviouslyWrongFieldTypesAreRejected() {
        listOf(
            """[{"id":"a","title":1}]""",
            """[{"id":"a","content":[]}]""",
            """[{"id":"a","images":{}}]""",
            """[{"id":"a","tags":[1]}]""",
            """[{"id":"a","audio":"REC.m4a"}]""",
            """[{"id":"a","todos":[7]}]""",
            """[{"id":"a","todos":[{"id":"t","done":"yes"}]}]""",
            """[{"id":"a","remindAt":"soon"}]""",
            """[{"id":"a","createdAt":"yesterday"}]""",
            """[{"id":"a","pinned":"true"}]"""
        ).forEach { json ->
            assertInvalid(
                prepare(ZipPiece("notes.json", json.toByteArray())),
                InvalidReason.WrongFieldType
            )
        }
    }

    @Test
    fun wrongRootElementTypeIsCorruptNotWrongField() {
        assertInvalid(
            prepare(ZipPiece("notes.json", """{"notes":[]}""".toByteArray())),
            InvalidReason.CorruptNotes
        )
    }

    // ---------- 空备份夹带附件 ----------

    @Test
    fun emptyNotesWithRealAttachmentIsRejected() {
        assertInvalid(
            prepare(
                ZipPiece("notes.json", "[]".toByteArray()),
                ZipPiece("attachments/a.jpg", ByteArray(16) { 1 })
            ),
            InvalidReason.EmptyNotesWithAttachments
        )
    }

    @Test
    fun emptyNotesWithZeroByteAttachmentIsStillEmpty() {
        val result = prepare(
            ZipPiece("notes.json", "[]".toByteArray()),
            ZipPiece("attachments/empty.jpg", ByteArray(0))
        )
        assertTrue("零字节附件不算实际附件，应视为合法空备份", result is PrepareResult.Empty)
    }

    // ---------- 源不可读 ----------

    @Test
    fun unreadableSourceIsReported() {
        val zip = buildBackupZip(zipFile(), ZipPiece("notes.json", "[]".toByteArray()))
        val importer = BackupImporter(sandbox, UnreadableFileOps(RealBackupFileOps(sandbox)))
        val result = runBlocking { importer.prepare(Uri.fromFile(zip)) }
        assertInvalid(result, InvalidReason.SourceUnreadable)
    }

    // ---------- 工具 ----------

    private fun zipFile(): File =
        File(sandbox.filesDir, "input/backup-${System.nanoTime()}.zip").also { it.parentFile?.mkdirs() }

    private fun prepare(vararg pieces: ZipPiece): PrepareResult =
        runBlocking { BackupImporter(sandbox).prepare(Uri.fromFile(buildBackupZip(zipFile(), *pieces))) }

    private fun assertInvalid(result: PrepareResult, reason: InvalidReason) {
        assertTrue("expected Invalid($reason), got $result", result is PrepareResult.Invalid)
        assertEquals(reason, (result as PrepareResult.Invalid).reason)
        assertNoStagingLeft()
    }

    private fun assertNoStagingLeft() {
        val root = File(sandbox.filesDir, ".import")
        val leftovers = root.listFiles()?.map { it.name } ?: emptyList()
        assertTrue("失败路径必须清掉暂存：$leftovers", leftovers.isEmpty())
        assertFalse(root.exists() && root.listFiles()?.isNotEmpty() == true)
    }
}
