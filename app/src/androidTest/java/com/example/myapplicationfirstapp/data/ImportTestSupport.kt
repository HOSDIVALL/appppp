package com.example.myapplicationfirstapp.data

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BACKUP-SAFE-1A 测试支撑。
 *
 * 与 SAVE-ORDER-2 一样，所有写入都落在 `isolatedStorage()` 给出的 UUID 沙箱里；
 * 真实 `files/notes.json`、`files/attachments/`、`files/recordings/` 在测试前后各取一次指纹比对。
 */

internal data class RealDataFingerprint(
    val notes: String,
    val attachments: String,
    val recordings: String
)

internal fun realDataFingerprint(): RealDataFingerprint {
    val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    return RealDataFingerprint(
        notes = realNotesFingerprint(),
        attachments = directoryFingerprint(File(files, "attachments")),
        recordings = directoryFingerprint(File(files, "recordings"))
    )
}

/**
 * 目录指纹用「文件名 + 长度 + mtime」，不做内容哈希：
 * 真机上录音目录有几十个文件、两百多 MB，逐字节哈希会让每个用例多花十几秒。
 */
private fun directoryFingerprint(dir: File): String {
    if (!dir.isDirectory) return "<absent>"
    val entries = dir.listFiles()?.sortedBy { it.name } ?: return "<unreadable>"
    return entries.joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }
}

internal fun finishImportTest(sandbox: IsolatedAppContext, before: RealDataFingerprint): List<String> {
    val violations = mutableListOf<String>()

    val after = try {
        realDataFingerprint()
    } catch (failure: Throwable) {
        violations += "读取真实数据指纹失败：$failure"
        before
    }
    if (after.notes != before.notes) {
        violations += "真实 notes.json 被改动：before=${before.notes} after=${after.notes}"
    }
    if (after.attachments != before.attachments) {
        violations += "真实 attachments 被改动：before=${before.attachments} after=${after.attachments}"
    }
    if (after.recordings != before.recordings) {
        violations += "真实 recordings 被改动：before=${before.recordings} after=${after.recordings}"
    }

    violations += finishIsolatedTest(sandbox, before.notes)
    return violations
}

// ================= 造 zip =================

internal data class ZipPiece(val name: String, val bytes: ByteArray = ByteArray(0))

internal fun buildBackupZip(target: File, vararg pieces: ZipPiece): File =
    buildBackupZip(target, pieces.toList())

internal fun buildBackupZip(target: File, pieces: List<ZipPiece>): File {    target.parentFile?.mkdirs()
    ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
        pieces.forEach { piece ->
            zip.putNextEntry(ZipEntry(piece.name))
            if (piece.bytes.isNotEmpty()) zip.write(piece.bytes)
            zip.closeEntry()
        }
    }
    return target
}

/**
 * 单条目 stored zip（不压缩）。
 *
 * stored 条目在读的时候按声明长度读满再由 `closeEntry()` 校验 CRC，
 * 所以翻转载荷里任意一个字节就能造出「CRC 不符」的损坏条目。
 */
internal fun buildStoredZip(target: File, name: String, payload: ByteArray): File {
    target.parentFile?.mkdirs()
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = payload.size.toLong()
        compressedSize = payload.size.toLong()
        crc = CRC32().apply { update(payload) }.value
    }
    ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
        zip.putNextEntry(entry)
        zip.write(payload)
        zip.closeEntry()
    }
    return target
}

/**
 * 手写一个允许同名条目的 zip。
 *
 * `ZipOutputStream` 自己会拒绝 duplicate entry，而「重名条目必须整包拒绝」正是要测的路径，
 * 所以这里按 ZIP 结构直接拼本地文件头 + 中央目录（stored，不压缩）。
 */
internal fun buildDuplicateEntryZip(target: File, name: String, first: ByteArray, second: ByteArray): File {
    target.parentFile?.mkdirs()
    val entries = listOf(name to first, name to second)
    val body = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    val offsets = mutableListOf<Int>()

    entries.forEach { (entryName, bytes) ->
        offsets += body.size()
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }.value

        body.writeLe32(0x04034b50)
        body.writeLe16(20)
        body.writeLe16(0)
        body.writeLe16(0) // stored
        body.writeLe16(0)
        body.writeLe16(0)
        body.writeLe32(crc)
        body.writeLe32(bytes.size.toLong())
        body.writeLe32(bytes.size.toLong())
        body.writeLe16(nameBytes.size)
        body.writeLe16(0)
        body.write(nameBytes)
        body.write(bytes)
    }

    entries.forEachIndexed { index, (entryName, bytes) ->
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }.value
        central.writeLe32(0x02014b50)
        central.writeLe16(20)
        central.writeLe16(20)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe32(crc)
        central.writeLe32(bytes.size.toLong())
        central.writeLe32(bytes.size.toLong())
        central.writeLe16(nameBytes.size)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe16(0)
        central.writeLe32(0)
        central.writeLe32(offsets[index].toLong())
        central.write(nameBytes)
    }

    val all = ByteArrayOutputStream()
    all.write(body.toByteArray())
    val cdOffset = all.size()
    all.write(central.toByteArray())
    val cdSize = central.size()

    all.writeLe32(0x06054b50)
    all.writeLe16(0)
    all.writeLe16(0)
    all.writeLe16(entries.size)
    all.writeLe16(entries.size)
    all.writeLe32(cdSize.toLong())
    all.writeLe32(cdOffset.toLong())
    all.writeLe16(0)

    target.writeBytes(all.toByteArray())
    return target
}

private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeLe32(value: Long) {
    write((value and 0xFF).toInt())
    write(((value shr 8) and 0xFF).toInt())
    write(((value shr 16) and 0xFF).toInt())
    write(((value shr 24) and 0xFF).toInt())
}

/** 统计「打开源」次数，用来证明 URI 只被读一次 */
internal class CountingFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    var openCount = 0
        private set

    override fun openSource(uri: Uri): InputStream? {
        openCount++
        return base.openSource(uri)
    }
}

/** 在第一次复制回调处阻塞，给测试一个确定性的取消时机 */
internal class BlockingFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun onChunk(bytesCopied: Long) {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
    }
}

/** 打开源直接返回 null，模拟权限失效 */
internal class UnreadableFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    override fun openSource(uri: Uri): InputStream? = null
}

/** 清理失败：`deleteTree` 报告「没删掉」，但不抛异常 */
internal class RefusingDeleteFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    var deleteCalls = 0
        private set

    override fun deleteTree(dir: File): Boolean {
        deleteCalls++
        return false
    }
}

/** 清理失败：`deleteTree` 直接抛异常 */
internal class ThrowingDeleteFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    @Volatile
    var deleteCalls = 0
        private set

    override fun deleteTree(dir: File): Boolean {
        deleteCalls++
        throw java.io.IOException("injected delete failure")
    }
}

/**
 * 在「结果已构造、尚未从 withContext 交付」处阻塞。
 *
 * 这一段没有别的可阻塞点，用它才能把取消确定性地放进交付窗口。
 */
internal class BlockingResultFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun onResultBuilt(token: String) {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
    }
}

// ================= 正式数据哨兵 =================

/**
 * 沙箱里的「已有正式数据」快照。
 *
 * 准备层不该碰它们：不是「不存在」，而是「原样还在」。用内容比对而非存在性判断，
 * 才能发现「覆盖成同样长度的别的内容」这种破坏。
 */
internal data class LiveSentinel(
    val notes: ByteArray,
    val attachment: ByteArray,
    val recording: ByteArray
)

internal fun plantLiveSentinels(filesDir: File): LiveSentinel {
    File(filesDir, "attachments").mkdirs()
    File(filesDir, "recordings").mkdirs()

    val notes = File(filesDir, "notes.json").apply {
        writeBytes("""[{"id":"live-note","title":"现有笔记","content":"正文"}]""".toByteArray())
    }
    val attachment = File(filesDir, "attachments/live.jpg").apply { writeBytes(ByteArray(32) { 9 }) }
    val recording = File(filesDir, "recordings/REC_live.m4a").apply { writeBytes(ByteArray(48) { 7 }) }

    return LiveSentinel(notes.readBytes(), attachment.readBytes(), recording.readBytes())
}

internal fun assertLiveSentinelsIntact(filesDir: File, sentinel: LiveSentinel): List<String> {
    val problems = mutableListOf<String>()

    fun check(relative: String, expected: ByteArray) {
        val file = File(filesDir, relative)
        when {
            !file.isFile -> problems += "$relative 不见了"
            !file.readBytes().contentEquals(expected) -> problems += "$relative 内容被改动"
        }
    }

    check("notes.json", sentinel.notes)
    check("attachments/live.jpg", sentinel.attachment)
    check("recordings/REC_live.m4a", sentinel.recording)

    // 目录里也不能多出文件（例如把暂存附件写进正式附件目录）
    val attachments = File(filesDir, "attachments").listFiles()?.map { it.name }?.sorted()
    if (attachments != listOf("live.jpg")) problems += "attachments 目录变了：$attachments"

    val recordings = File(filesDir, "recordings").listFiles()?.map { it.name }?.sorted()
    if (recordings != listOf("REC_live.m4a")) problems += "recordings 目录变了：$recordings"

    return problems
}

/**
 * 可暂停的调度器：`dispatch` 只入队，由测试决定什么时候执行。
 *
 * 用来把「调用方线程」与块所在的 IO 线程分开，并把 **withContext 的交付续体**停在队列里：
 * 结果已经构造完、调用方还没拿到值——取消正好落在这个窗口里，而且不靠 sleep。
 */
internal class PausableDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    private val dispatchCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val resumeQueued = CountDownLatch(1)

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        queue.add(block)
        // 第 1 次是启动协程，第 2 次就是交付续体入队
        if (dispatchCount.incrementAndGet() >= 2) resumeQueued.countDown()
    }

    /** 等到「交付续体」已入队：此刻块已执行完、调用方尚未恢复 */
    fun awaitResumeQueued(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean =
        resumeQueued.await(timeout, unit)

    /** 只执行队列里的下一个任务，绝不多跑——多跑一步就把取消窗口放过去了 */
    fun runNext(timeoutMs: Long = 10_000): Boolean {
        val task = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
        task.run()
        return true
    }
}

/** 记录 `onResultBuilt` 与 `deleteTree` 发生在哪个线程上 */
internal class ThreadRecordingFileOps(private val base: BackupFileOps) : BackupFileOps by base {
    @Volatile
    var resultBuiltThread: Thread? = null

    @Volatile
    var deleteThread: Thread? = null

    override fun onResultBuilt(token: String) {
        resultBuiltThread = Thread.currentThread()
    }

    override fun deleteTree(dir: File): Boolean {
        deleteThread = Thread.currentThread()
        return base.deleteTree(dir)
    }
}

/** 建符号链接；失败就让用例明确失败，不静默跳过 */
internal fun createSymlink(link: File, target: File) {
    android.system.Os.symlink(target.absolutePath, link.absolutePath)
}
