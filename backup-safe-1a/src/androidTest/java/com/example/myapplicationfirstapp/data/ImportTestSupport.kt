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
