package com.example.myapplicationfirstapp.data

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
    fun stagingLivesUnderFilesAndDiscardRemovesIt() = runBlocking {
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

    // ---------- 零字节附件 ----------

    @Test
    fun zeroByteAttachmentWarnsInsteadOfBlocking() {
        val note = Note(id = "n", title = "t", images = listOf("empty.jpg"))
        val result = prepare(
            ZipPiece("notes.json", notesToJson(listOf(note)).toByteArray()),
            ZipPiece("attachments/empty.jpg", ByteArray(0))
        )

        val prepared = ready(result)
        // 名字要保留：1B 需要按名字对齐，否则这条引用会被当成「缺失」而不是「空的」
        assertEquals(listOf("empty.jpg"), prepared.attachmentNames)
        assertEquals(0L, prepared.attachmentBytes)
        assertEquals(0L, File(prepared.stagingDir, "attachments/empty.jpg").length())

        val warning = prepared.warnings.single { it.kind == WarningKind.EmptyAttachments }
        assertEquals(1, warning.total)
        assertEquals(listOf("empty.jpg"), warning.names)
        assertTrue(
            "空附件不该再报成缺失",
            prepared.warnings.none { it.kind == WarningKind.MissingImages }
        )
        assertTrue(warning.displaySummary().contains("0 字节"))
    }

    // ---------- 原始 JSON 与归一数据 ----------

    /**
     * 归一化后的 [Note] 再序列化、再读回来，必须与原值逐字段相等。
     *
     * 这条用例只盯「归一」本身没有丢信息。注意 [PreparedBackup.notesJson] 在 rev2 之后
     * **不用于写盘**（显式 null 会被现有读取路径读成 0），写盘走 [PreparedBackup.commitJson]；
     * [PreparedBackup.notes] 仍然只服务告警与预览。
     */
    @Test
    fun normalizedNotesSurviveSerializationRoundTrip() {
        val source = """
            [{"id":"a","title":"标题","content":"正文","createdAt":1,"updatedAt":2,"colorIndex":3,
            "pinned":true,"tags":["x"],"images":["a.jpg"],"audio":["REC_a.m4a"],
            "deletedAt":null,"remindAt":null,"checklist":true,
            "todos":[{"id":"t1","text":"项","done":false}]}]
        """.trimIndent().replace("\n", "")

        val prepared = ready(
            prepare(
                ZipPiece("notes.json", source.toByteArray()),
                ZipPiece("attachments/a.jpg", ByteArray(8) { 1 })
            )
        )

        val reserialized = notesToJson(prepared.notes)
        assertEquals(prepared.notes, parseBackupNotesOrThrow(reserialized))

        // 原始文本按原样保留（含显式 null），归一化再序列化会省掉它们——这正是不能互相顶替的原因
        assertEquals(source, prepared.notesJson)
        assertNotEquals(source, reserialized)
    }

    // ---------- 提交载荷契约 ----------

    private val contractSource = """
        [{"id":"a","title":"标题","content":"正文","createdAt":1,"updatedAt":2,"colorIndex":3,
        "pinned":true,"tags":["x"],"images":[],"audio":[],"deletedAt":null,"remindAt":null,
        "checklist":false,"todos":[],
        "futureField":{"b":2,"a":[1,"two",true,null]},"schemaVersion":7},
        {"id":"b","title":"回收站里","createdAt":10,"updatedAt":20,
        "deletedAt":12345,"remindAt":67890,"futureField":{"nested":{"z":1}}}]
    """.trimIndent().replace("\n", "")

    /**
     * 提交载荷必须同时做到两件事：**保留未知字段**，**修正 null 时间戳语义**。
     *
     * 修正方式是「删键」而不是「写 null」：现有读取路径 `noteFromJson` 对
     * `{"deletedAt":null}` 给出 0，写 null 等于把笔记标成 1970 年删除。
     */
    @Test
    fun commitPayloadKeepsUnknownFieldsAndDropsNullTimestamps() {
        val prepared = ready(prepare(ZipPiece("notes.json", contractSource.toByteArray())))

        // 归一视图：显式 null 仍然是「没有值」
        assertNull(prepared.notes[0].deletedAt)
        assertNull(prepared.notes[0].remindAt)
        // 非 null 的时间戳原样保留
        assertEquals(12345L, prepared.notes[1].deletedAt)
        assertEquals(67890L, prepared.notes[1].remindAt)

        // 未知字段一路带到提交载荷
        assertTrue("笔记级未知字段必须保留", prepared.commitJson.contains("\"futureField\""))
        assertTrue("未知标量字段必须保留", prepared.commitJson.contains("\"schemaVersion\":7"))
        assertTrue("第二条的嵌套未知字段也必须保留", prepared.commitJson.contains("\"nested\""))

        // null 时间戳不落成 null 键，也不落成 0
        val first = prepared.commitJson.substringBefore("},{")
        assertFalse("不能写成 \"deletedAt\":null：$first", first.contains("\"deletedAt\""))
        assertFalse("不能写成 \"remindAt\":null：$first", first.contains("\"remindAt\""))
        assertFalse("更不能写成 0：$first", first.contains("\"deletedAt\":0"))

        // 用生产归一函数把载荷读回来，必须与归一视图逐字段相等
        val reparsed = parseBackupArrayOrThrow(prepared.commitJson)
        val reread = (0 until reparsed.length()).map { normalizeImportedNote(reparsed.getJSONObject(it)) }
        assertEquals(prepared.notes, reread)

        // 摘要对应的是载荷本身
        assertEquals(sha256(prepared.commitBytes()), prepared.commitDigest)
    }

    /**
     * 同一份输入两次准备，提交载荷必须逐字节相同（键序稳定，不随 HashMap 迭代顺序漂移）。
     *
     * 边界：输入必须自带 `createdAt` / `updatedAt`。两者缺失时 `noteFromJson` 按读取时刻补齐，
     * 那是既有读取语义，本层不另造一套——这类旧备份的载荷不承诺跨次可复现。
     */
    @Test
    fun commitPayloadIsByteStableAcrossRuns() {
        val first = ready(prepare(ZipPiece("notes.json", contractSource.toByteArray())))
        val second = ready(prepare(ZipPiece("notes.json", contractSource.toByteArray())))

        assertEquals(first.commitJson, second.commitJson)
        assertEquals(first.commitDigest, second.commitDigest)
        // 键按字典序：content 在 createdAt 之前
        assertTrue(
            "键序应为字典序：${first.commitJson.take(80)}",
            first.commitJson.indexOf("\"content\"") < first.commitJson.indexOf("\"createdAt\"")
        )
    }

    // ---------- 丢弃的归属校验 ----------

    @Test
    fun discardRefusesPathsOutsideItsOwnStagingRoot() = runBlocking {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val importer = BackupImporter(sandbox)

        // 指向 filesDir：这不是暂存目录，绝不能删
        assertFalse(importer.discard(prepared.copy(stagingDir = sandbox.filesDir)))
        assertTrue("filesDir 必须还在", sandbox.filesDir.isDirectory)

        // token 不是 UUID 形式：直接拒绝，不做任何路径拼接
        assertFalse(importer.discard(prepared.copy(token = "../../notes.json")))

        // 指向暂存根目录本身：不是本次 token 的目录
        assertFalse(importer.discard(prepared.copy(stagingDir = File(sandbox.filesDir, ".import"))))

        // 以上都不许动到真正的暂存
        assertTrue("真正的暂存不能被误删", prepared.stagingDir.isDirectory)
        assertTrue("合法路径仍然可以丢弃", importer.discard(prepared))
        assertFalse(prepared.stagingDir.exists())
    }

    @Test
    fun discardIsIdempotent() = runBlocking {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val importer = BackupImporter(sandbox)

        assertTrue(importer.discard(prepared))
        assertFalse(prepared.stagingDir.exists())
        // 目录已经不在了：再丢一次仍然返回「没有残留」，而不是抛异常或报失败
        assertTrue(importer.discard(prepared))
    }

    /**
     * token 是**格式合法**的 UUID，但路径指向别处：不能因为「token 长得对」就当成自己的目录。
     */
    @Test
    fun discardRejectsWellFormedTokenPointingElsewhere() = runBlocking {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val importer = BackupImporter(sandbox)
        val elsewhere = File(sandbox.filesDir, "attachments").apply { mkdirs() }
        File(elsewhere, "live.jpg").writeBytes(ByteArray(8) { 3 })

        val forged = prepared.copy(
            token = java.util.UUID.randomUUID().toString(),
            stagingDir = elsewhere
        )
        assertFalse("token 格式不是所有权证明", importer.discard(forged))
        assertTrue("别的目录不能被删", File(elsewhere, "live.jpg").isFile)
        assertTrue("真正的暂存必须还在", prepared.stagingDir.isDirectory)
    }

    /**
     * 路径替换：暂存根里放一个指向根外的符号链接，名字与 token 完全对得上。
     *
     * 只比字符串或只比 token 的实现在这里会把根外目录删掉；按规范路径核对父目录才拦得住。
     */
    @Test
    fun discardRejectsSymlinkedStagingDirectory() = runBlocking {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val importer = BackupImporter(sandbox)
        val root = File(sandbox.filesDir, ".import").apply { mkdirs() }
        val outside = File(sandbox.filesDir, "outside-target").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("must survive") }

        val token = java.util.UUID.randomUUID().toString()
        createSymlink(File(root, "staging-$token"), outside)

        assertFalse(
            "暂存名指向根外的链接必须拒绝",
            importer.discard(prepared.copy(token = token, stagingDir = File(root, "staging-$token")))
        )
        assertTrue("根外目录不能被删", keep.isFile)
        assertTrue("真正的暂存必须还在", prepared.stagingDir.isDirectory)
    }

    /** 跨存储根：另一个沙箱（不同 filesDir）的实例，不能用同一个 token 删掉这边的目录 */
    @Test
    fun discardRefusesStagingFromAnotherStorageRoot() = runBlocking {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val other = isolatedStorage("backup-1a-discard-other")
        try {
            val foreign = BackupImporter(other)
            assertFalse("跨存储根必须拒绝", foreign.discard(prepared))
            assertTrue("这边的暂存不能被跨根删除", prepared.stagingDir.isDirectory)
            assertTrue("另一个根下不应出现同名暂存", !File(other.filesDir, ".import").exists())
        } finally {
            assertTrue("另一个沙箱必须清理干净", other.dispose())
        }
    }

    /** 主动 discard 的删除必须切到 IO 线程：调用方在主线程时不能做磁盘 IO */
    @Test
    fun discardDeletesOnIoThreadNotOnCaller() {
        val prepared = ready(
            prepare(ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t"))).toByteArray()))
        )
        val ops = ThreadRecordingFileOps(RealBackupFileOps(sandbox))
        val importer = BackupImporter(sandbox, ops)

        val callerWasMain = runBlocking(Dispatchers.Main) {
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            assertTrue("用例前提：discard 必须在主线程上调用", importer.discard(prepared))
            onMain
        }

        assertTrue("用例前提：调用方确实在主线程", callerWasMain)
        assertNotSame("删除不能留在主线程", Looper.getMainLooper().thread, ops.deleteThread)
    }

    // ---------- 清理失败不覆盖原始原因 ----------

    @Test
    fun cleanupReportingFailureKeepsOriginalReason() {
        val ops = RefusingDeleteFileOps(RealBackupFileOps(sandbox))
        val zip = buildDuplicateEntryZip(
            zipFile(), "attachments/a.jpg", ByteArray(4) { 1 }, ByteArray(4) { 2 }
        )

        val result = runBlocking { BackupImporter(sandbox, ops).prepare(Uri.fromFile(zip)) }

        val invalid = result as PrepareResult.Invalid
        assertEquals("删不掉目录不能把原因改写成 StagingFailed", InvalidReason.DuplicateEntry, invalid.reason)
        assertTrue("detail 仍应是重复条目", invalid.detail!!.contains("attachments/a.jpg"))
        assertEquals(1, invalid.residue.size)
        assertTrue("残留路径必须真实存在", File(invalid.residue.single()).isDirectory)
    }

    @Test
    fun cleanupThrowingDoesNotMaskOriginalReason() {
        val ops = ThrowingDeleteFileOps(RealBackupFileOps(sandbox))
        val zip = buildDuplicateEntryZip(
            zipFile(), "attachments/a.jpg", ByteArray(4) { 1 }, ByteArray(4) { 2 }
        )

        val result = runBlocking { BackupImporter(sandbox, ops).prepare(Uri.fromFile(zip)) }

        val invalid = result as PrepareResult.Invalid
        assertEquals(InvalidReason.DuplicateEntry, invalid.reason)
        assertEquals(1, invalid.residue.size)
        assertTrue(
            "残留要带上清理失败的类型：${invalid.residue}",
            invalid.residue.single().contains("IOException")
        )
    }

    // ---------- 正式数据哨兵 ----------

    @Test
    fun liveSentinelsSurviveSuccessfulPreparation() {
        val sentinel = plantLiveSentinels(sandbox.filesDir)
        ready(
            prepare(
                ZipPiece("notes.json", notesToJson(listOf(Note(id = "n", title = "t", images = listOf("a.jpg")))).toByteArray()),
                ZipPiece("attachments/a.jpg", ByteArray(8) { 2 })
            )
        )
        val problems = assertLiveSentinelsIntact(sandbox.filesDir, sentinel)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun liveSentinelsSurviveRejectedPreparation() {
        val sentinel = plantLiveSentinels(sandbox.filesDir)
        val result = prepare(
            ZipPiece("notes.json", "[]".toByteArray()),
            ZipPiece("attachments/a.jpg", ByteArray(8) { 2 })
        )
        assertTrue(result is PrepareResult.Invalid)
        val problems = assertLiveSentinelsIntact(sandbox.filesDir, sentinel)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
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
