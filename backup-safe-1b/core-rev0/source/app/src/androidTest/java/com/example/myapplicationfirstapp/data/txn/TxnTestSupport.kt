package com.example.myapplicationfirstapp.data.txn

import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplicationfirstapp.data.realDataFingerprint
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BACKUP-SAFE-1B-CORE 测试支撑。
 *
 * 每个用例一个 UUID 沙箱，内核只拿到这个根；测试**不**通过 Context 取 `filesDir`，
 * 也不构造会回落到真实目录的包装器。真实数据仍按 1A 的方式取指纹，前后比对。
 *
 * 注入的异常一律叫 `injected-io-failure`：这是代码级注入，不是「真实磁盘故障」的证明。
 */

internal class TxnSandbox(val tag: String) {

    private val base = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    val root: File = File(base, "backup-1b-core/$tag-${UUID.randomUUID()}").apply {
        check(mkdirs() || isDirectory) { "cannot create sandbox: $path" }
    }

    private var disposed = false

    fun kernel(ops: TxnFileOps = RealTxnFileOps(), gate: WriteGate = WriteGate()): ImportTxnKernel =
        ImportTxnKernel(root, ops, gate)

    fun dispose(): Boolean {
        disposed = true
        root.deleteRecursively()
        val parent = root.parentFile
        return !root.exists() && parent != null && parent.isDirectory
    }

    fun wasDisposed(): Boolean = disposed
}

internal fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun File.sha256(): String = readBytes().sha256()

internal fun realFingerprint(): String {
    val fingerprint = realDataFingerprint()
    return "${fingerprint.notes}|${fingerprint.attachments}|${fingerprint.recordings}"
}

// ================= 文件布局 =================

internal fun TxnSandbox.txnDir(txnId: String): File = File(root, ".import/txn-$txnId")
internal fun TxnSandbox.journalFile(): File = File(root, ".import/journal.json")
internal fun TxnSandbox.credentialFile(): File = File(root, ".import/committed.json")
internal fun TxnSandbox.resultsFile(): File = File(root, ".import/results.json")
internal fun TxnSandbox.notesFile(): File = File(root, "notes.json")
internal fun TxnSandbox.attachmentsDir(): File = File(root, "attachments")

internal fun TxnSandbox.attachment(name: String): File = File(attachmentsDir(), name)

internal fun TxnSandbox.tempFiles(): List<String> = root.walkTopDown()
    .filter { it.isFile && it.name.endsWith(".tmp") }
    .map { it.relativeTo(root).path }
    .sorted()
    .toList()

internal fun TxnSandbox.writeNotes(text: String) {
    notesFile().parentFile?.mkdirs()
    notesFile().writeText(text)
}

internal fun TxnSandbox.writeAttachment(name: String, bytes: ByteArray) {
    attachmentsDir().mkdirs()
    attachment(name).writeBytes(bytes)
}

internal fun TxnSandbox.notesText(): String? = if (notesFile().isFile) notesFile().readText() else null

internal fun TxnSandbox.attachmentNames(): List<String> =
    attachmentsDir().listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

// ================= 故障注入 =================

/**
 * 按步骤名注入失败或阻塞。
 *
 * - [failOnce]：命中一次后自动移除，命中时抛 IOException（不是 Error，不冒充崩溃）。
 * - [alwaysFail]：每次都抛。
 * - [blockAt]：在该步骤阻塞，测试用来把取消固定在确定性窗口。
 */
internal class FaultyOps(private val base: TxnFileOps = RealTxnFileOps()) : TxnFileOps by base {

    val failOnce = mutableSetOf<String>()
    val alwaysFail = mutableSetOf<String>()

    @Volatile
    var blockAt: String? = null

    val blocked = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile
    var checkpoints: MutableList<String> = mutableListOf()

    @Volatile
    var forcedUsableSpace: Long? = null

    override fun checkpoint(name: String) {
        synchronized(this) { checkpoints += name }
        if (name == blockAt) {
            blocked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val fail = synchronized(this) { alwaysFail.contains(name) || failOnce.remove(name) }
        if (fail) throw IOException("injected-io-failure:$name")
    }

    override fun usableSpace(dir: File): Long = forcedUsableSpace ?: base.usableSpace(dir)

    fun sawCheckpoint(name: String): Boolean = synchronized(this) { checkpoints.contains(name) }
}

// ================= 直接摆盘（不经 commit） =================

/**
 * 一条「已经动过正式数据」的事务现场。
 *
 * 用直接写文件的方式构造，而不是靠让 `commit` 崩溃：前者只依赖磁盘布局，
 * 不依赖代码里的异常路径，读起来也更接近真正的重启现场。
 */
internal data class PlantedTxn(
    val txnId: String,
    val oldNotes: String?,
    val newNotes: String,
    val oldAttachments: Map<String, ByteArray>,
    val newAttachments: Map<String, ByteArray>,
    /** 正式目录里当前的附件内容；默认是「全部新」，可只给一部分来模拟部分应用 */
    val appliedAttachments: Map<String, ByteArray> = newAttachments
)

internal fun TxnSandbox.plant(
    spec: PlantedTxn,
    notesState: NotesState = NotesState.NEW,
    writeJournal: Boolean = true,
    journalState: JournalState = JournalState.APPLYING,
    writeCredential: Boolean = false,
    writeOldCopies: Boolean = true,
    writeNewMaterial: Boolean = true
) {
    root.mkdirs()
    notesFile().delete()
    when (notesState) {
        NotesState.OLD -> spec.oldNotes?.let { writeNotes(it) }
        NotesState.NEW -> writeNotes(spec.newNotes)
    }

    attachmentsDir().deleteRecursively()
    attachmentsDir().mkdirs()
    spec.appliedAttachments.forEach { (name, bytes) -> writeAttachment(name, bytes) }

    val dir = txnDir(spec.txnId)
    dir.mkdirs()
    if (writeNewMaterial) {
        File(dir, "commit.json").writeText(spec.newNotes)
        File(dir, "new-attachments").mkdirs()
        spec.newAttachments.forEach { (name, bytes) ->
            File(dir, "new-attachments/$name").writeBytes(bytes)
        }
    }
    if (writeOldCopies) {
        spec.oldNotes?.let { File(dir, "old-notes.json").writeText(it) }
        File(dir, "old-attachments").mkdirs()
        spec.oldAttachments.forEach { (name, bytes) ->
            File(dir, "old-attachments/$name").writeBytes(bytes)
        }
    }

    val entries = (spec.oldAttachments.keys + spec.newAttachments.keys).map { name ->
        JournalAttachment(
            name = name,
            old = spec.oldAttachments[name]?.sha256(),
            new = (spec.newAttachments[name] ?: spec.oldAttachments.getValue(name)).sha256()
        )
    }
    val journal = Journal(
        state = journalState,
        txnId = spec.txnId,
        commitDigest = spec.newNotes.toByteArray().sha256(),
        oldNotesDigest = spec.oldNotes?.let { it.toByteArray().sha256() },
        attachments = entries
    )
    if (writeJournal) {
        journalFile().parentFile?.mkdirs()
        writeJournalRaw(TxnCodec.encodeJournal(journal))
    }
    if (writeCredential) {
        credentialFile().parentFile?.mkdirs()
        credentialFile().writeText(
            TxnCodec.encodeCredential(
                CommitCredential(spec.txnId, journal.commitDigest, 1_700_000_000_000)
            )
        )
    }
}

internal enum class NotesState { OLD, NEW }

internal fun TxnSandbox.writeJournalRaw(text: String) {
    journalFile().parentFile?.mkdirs()
    journalFile().writeText(text)
}

internal fun TxnSandbox.journal(): Journal? {
    if (!journalFile().isFile) return null
    return TxnCodec.decodeJournal(journalFile().readText())
}

internal fun TxnSandbox.credential(): CommitCredential? {
    if (!credentialFile().isFile) return null
    return TxnCodec.decodeCredential(credentialFile().readText())
}

internal fun TxnSandbox.results(): List<ResultRecord> {
    if (!resultsFile().isFile) return emptyList()
    return TxnCodec.decodeResults(resultsFile().readText()) ?: emptyList()
}

// ================= 断言辅助 =================

internal fun expectTrue(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

internal fun expectEquals(expected: Any?, actual: Any?, message: String) {
    if (expected != actual) throw AssertionError("$message：expected=$expected actual=$actual")
}

internal fun TxnSandbox.expectNotes(expected: String?, message: String) {
    expectEquals(expected, notesText(), message)
}

internal fun TxnSandbox.expectAttachments(expected: Map<String, ByteArray>, message: String) {
    expectEquals(expected.keys.sorted(), attachmentNames(), "$message（文件名）")
    expected.forEach { (name, bytes) ->
        expectTrue(attachment(name).readBytes().contentEquals(bytes), "$message：$name 内容不符")
    }
}

/**
 * 每个用例的固定收尾：
 * 1. 真实 `files/notes.json`、`attachments/`、`recordings/` 的指纹必须与开跑前一致；
 * 2. 本用例的 UUID 沙箱必须被删掉，公共父目录保留。
 *
 * 两条都在失败时也执行，问题收集成清单一次性抛出，不静默。
 */
internal abstract class TxnKernelTestBase {

    protected lateinit var sandbox: TxnSandbox
    private var realBefore: String = ""

    @org.junit.Before
    fun createSandbox() {
        realBefore = realFingerprint()
        sandbox = TxnSandbox(javaClass.simpleName)
    }

    @org.junit.After
    fun disposeSandbox() {
        val problems = mutableListOf<String>()
        try {
            if (realFingerprint() != realBefore) problems += "真实数据指纹被改动"
        } catch (failure: Throwable) {
            problems += "读取真实数据指纹失败：$failure"
        }
        try {
            if (!sandbox.dispose()) problems += "沙箱未删除：${sandbox.root}"
        } catch (failure: Throwable) {
            problems += "删除沙箱抛异常：$failure"
        }
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("; "))
    }
}
