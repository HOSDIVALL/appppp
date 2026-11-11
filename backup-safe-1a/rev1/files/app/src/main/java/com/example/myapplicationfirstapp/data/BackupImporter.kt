package com.example.myapplicationfirstapp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份导入的**准备与验证层**（BACKUP-SAFE-1A）。
 *
 * 只做三件事：
 * 1. 把选中的 URI **读一次**，落地成 `files/.import/staging-<token>/backup.zip`；
 * 2. 在这份副本上做结构与内容校验，附件解压到同一个暂存目录；
 * 3. 返回 [PrepareResult.Ready] / [PrepareResult.Empty] / [PrepareResult.Invalid]。
 *
 * 明确**不做**（属于 1B）：不写 `notes.json`、不动 `attachments/`、不 prune、不碰内存状态。
 * 因此本类的任何返回路径都不会改变正式数据；暂存目录删掉就等于没发生过。
 *
 * 资源限制全部按实际读到的字节计算，不读 `ZipEntry.size`，也不算压缩比。
 *
 * 失败与取消的约定：暂存目录只在 [prepare] 这一层被创建和销毁，
 * 清理**永不覆盖**原始原因（见 [cleanup]）。
 */
internal class BackupImporter(
    context: Context,
    private val fileOps: BackupFileOps = RealBackupFileOps(context.applicationContext),
    private val limits: BackupLimits = BackupLimits.DEFAULT
) {
    private val appContext = context.applicationContext

    /** `files/.import` —— 暂存放这里而不是 cacheDir，进程被杀后材料还在 */
    private val stagingRoot: File get() = File(appContext.filesDir, STAGING_ROOT)

    /**
     * 读一次 URI 并完成全部校验。
     *
     * 取消时抛 [CancellationException]（原样抛出，不包新异常），并在抛出前尽力删掉本次暂存；
     * 其他失败都收敛成 [PrepareResult.Invalid]，同样已尽力清理暂存。
     */
    suspend fun prepare(uri: Uri): PrepareResult {
        val token = UUID.randomUUID().toString()
        val dir = File(stagingRoot, STAGING_PREFIX + token)

        // 所有权边界就在这一层。目录在这里创建，结果也在这里交付：
        // 如果只把清理写在 withContext 的块里，块正常返回、但**结果交付**时协程已被取消
        // （withContext 在 resume 前检查 job 状态，此时丢弃返回值并抛 CancellationException），
        // 暂存目录就没有任何人负责，会一直留在 files/.import 下。
        // 所以块内只产出结果，清理与所有权判定统一放在块外。
        val outcome: PrepareResult = try {
            withContext(Dispatchers.IO) { prepareStaged(uri, token, dir) }
        } catch (cancelled: CancellationException) {
            val residue = cleanup(token, dir)
            if (residue.isNotEmpty()) {
                // 取消路径没有返回值可以携带残留信息，只能记日志；原始取消异常照旧抛出
                Log.w(TAG, "cancelled while staging, leftover: $residue")
            }
            throw cancelled
        } catch (rejection: Rejection) {
            return PrepareResult.Invalid(rejection.reason, rejection.detail, cleanup(token, dir))
        } catch (failure: Exception) {
            return PrepareResult.Invalid(
                InvalidReason.StagingFailed,
                failure.message ?: failure.javaClass.simpleName,
                cleanup(token, dir)
            )
        }
        return outcome
    }

    /**
     * 主动丢弃一次准备结果（用户取消确认、或 1B 提交完成后调用）。
     *
     * 不直接信任 [PreparedBackup.stagingDir]：只按 [PreparedBackup.token] 重新算出预期目录，
     * 要求它与传入路径指向同一个文件，并且确实落在本实例的暂存根之下，才允许删除。
     * 越界 / token 不合法时返回 `false` 且**什么都不删**；目录已经不在了返回 `true`（幂等）。
     */
    fun discard(prepared: PreparedBackup): Boolean =
        discardStaging(prepared.token, prepared.stagingDir)

    private fun discardStaging(token: String, claimed: File): Boolean {
        if (!TOKEN_PATTERN.matches(token)) return false
        val expected = File(stagingRoot, STAGING_PREFIX + token)
        if (!sameFile(expected, claimed)) return false
        return fileOps.deleteTree(expected)
    }

    /**
     * 尽力清掉本次暂存，**永不抛出**。
     *
     * 返回没能清掉的路径（空列表表示清干净了）。删除返回 false 或抛异常都只记录残留，
     * 不改变调用方正在上报的原因：拒绝仍是原来的 reason/detail，取消仍是原来的
     * CancellationException。
     */
    private fun cleanup(token: String, dir: File): List<String> = try {
        if (discardStaging(token, dir)) emptyList() else listOf(dir.absolutePath)
    } catch (failure: Exception) {
        listOf("${dir.absolutePath} (${failure.javaClass.simpleName})")
    }

    // ================= 落地与扫描 =================

    private suspend fun prepareStaged(uri: Uri, token: String, dir: File): PrepareResult {
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw Rejection(InvalidReason.StagingFailed, "cannot create staging directory")
        }

        val backupFile = File(dir, BACKUP_FILE)
        copySource(uri, backupFile)

        if (!looksLikeZip(backupFile)) {
            throw Rejection(InvalidReason.NotZip, "missing zip signature")
        }

        val attachmentsDir = File(dir, ATTACHMENTS_DIR)
        if (!attachmentsDir.mkdirs() && !attachmentsDir.isDirectory) {
            throw Rejection(InvalidReason.StagingFailed, "cannot create attachments staging")
        }

        val scan = scanArchive(backupFile, attachmentsDir)
        val result = buildResult(token, dir, backupFile, scan)
        // 结果已构造、尚未交付：测试在这里把取消落在交付窗口里
        fileOps.onResultBuilt(token)
        return result
    }

    /** 有上限地把 URI 内容拷到暂存；只在这里碰 URI，后续全部读副本 */
    private suspend fun copySource(uri: Uri, target: File) {
        val input: InputStream = fileOps.openSource(uri)
            ?: throw Rejection(InvalidReason.SourceUnreadable, "openInputStream returned null")

        input.use { source ->
            val output: OutputStream = fileOps.openOutput(target)
            try {
                val buffer = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limits.maxZipBytes) {
                        throw Rejection(
                            InvalidReason.ZipTooLarge,
                            "$total > ${limits.maxZipBytes}"
                        )
                    }
                    output.write(buffer, 0, read)
                    fileOps.onChunk(total)
                }
                output.flush()
                fileOps.sync(output)
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /**
     * 扫 zip：校验条目名、**统一查重**、按实际字节解压。
     *
     * `notes.json` 读进内存（上限 maxNotesBytes），`attachments/<name>` 落到暂存目录，
     * 其他条目**不解压到磁盘**，但仍然按字节计数、按条目计数——不能因为「不认识」就放行。
     *
     * 查重发生在分支之前：未知文件、目录占位、`notes.json` 和附件走同一张表，
     * 否则「同一个名字既当未知文件又当附件」这类夹带就能绕过去。
     */
    private suspend fun scanArchive(backupFile: File, attachmentsDir: File): Scan {
        var notesBytes: ByteArray? = null
        val attachmentNames = mutableListOf<String>()
        val emptyAttachmentNames = mutableListOf<String>()
        val seenNames = HashSet<String>()
        var realAttachmentCount = 0
        var attachmentBytes = 0L
        var entryCount = 0
        val total = longArrayOf(0L)

        ZipInputStream(BufferedInputStream(FileInputStream(backupFile))).use { zip ->
            var entry: ZipEntry? = nextEntryOrNull(zip)
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw Rejection(
                        InvalidReason.TooManyEntries,
                        "$entryCount > ${limits.maxEntries}"
                    )
                }

                try {
                    val rawName = entry.name ?: throw Rejection(InvalidReason.DangerousPath, "null entry name")

                    // 所有条目先统一查重，再决定怎么处理
                    if (!seenNames.add(rawName)) {
                        throw Rejection(InvalidReason.DuplicateEntry, rawName)
                    }

                    if (entry.isDirectory) {
                        // 只允许 `attachments/` 这一个目录占位，而且必须没有载荷
                        if (rawName != "$ATTACHMENTS_DIR/") {
                            throw Rejection(InvalidReason.DangerousPath, rawName)
                        }
                        copyEntry(zip, null, 0L, InvalidReason.DirectoryWithPayload, total)
                    } else if (rawName == NOTES_FILE) {
                        val sink = ByteArrayOutputStream()
                        copyEntry(zip, sink, limits.maxNotesBytes, InvalidReason.NotesTooLarge, total)
                        notesBytes = sink.toByteArray()
                    } else if (rawName.startsWith("$ATTACHMENTS_DIR/")) {
                        val name = rawName.removePrefix("$ATTACHMENTS_DIR/")
                        requireSafeAttachmentName(name)
                        val target = File(attachmentsDir, name)
                        var written = 0L
                        target.outputStream().use { sink ->
                            written = copyEntry(zip, sink, limits.maxEntryBytes, InvalidReason.EntryTooLarge, total)
                        }
                        // 零字节附件：名字与 0 字节文件都保留（1B 要按名字对齐），
                        // 但不算「实际附件」——它撑不起一张图片，也让空备份夹带它的判定有据可依
                        attachmentNames += name
                        if (written == 0L) {
                            emptyAttachmentNames += name
                        } else {
                            attachmentBytes += written
                            realAttachmentCount++
                        }
                    } else {
                        // 未知条目：丢弃内容，但字节与条目都计入限额
                        copyEntry(zip, null, limits.maxEntryBytes, InvalidReason.EntryTooLarge, total)
                    }

                    // CRC 不符 / 压缩流损坏都在 closeEntry 里暴露，不能吞掉
                    zip.closeEntry()
                } catch (broken: java.util.zip.ZipException) {
                    throw Rejection(InvalidReason.CorruptEntry, broken.message ?: "corrupt entry")
                }
                entry = nextEntryOrNull(zip)
            }
        }

        if (entryCount == 0) throw Rejection(InvalidReason.EmptyArchive, "no entries")
        val notes = notesBytes ?: throw Rejection(InvalidReason.MissingNotes, "no notes.json")

        return Scan(
            notesBytes = notes,
            attachmentNames = attachmentNames,
            emptyAttachmentNames = emptyAttachmentNames,
            realAttachmentCount = realAttachmentCount,
            attachmentBytes = attachmentBytes,
            entryCount = entryCount,
            totalBytes = total[0]
        )
    }

    private fun nextEntryOrNull(zip: ZipInputStream): ZipEntry? = try {
        zip.nextEntry
    } catch (broken: java.util.zip.ZipException) {
        // Android 的 ZipInputStream 会自己拒绝 `../` 之类的条目路径，
        // 这时异常消息里带 "Invalid zip entry path"，本质是危险路径而不是「不是 zip」。
        val message = broken.message ?: "broken zip header"
        val reason = if (message.contains("entry path", ignoreCase = true)) {
            InvalidReason.DangerousPath
        } else {
            InvalidReason.NotZip
        }
        throw Rejection(reason, message)
    }

    /** 读一个条目；[sink] 为 null 表示只计数不落盘。返回该条目实际字节数 */
    private suspend fun copyEntry(
        input: InputStream,
        sink: OutputStream?,
        perEntryLimit: Long,
        limitReason: InvalidReason,
        total: LongArray
    ): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var entryBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            entryBytes += read
            if (entryBytes > perEntryLimit) {
                throw Rejection(limitReason, "$entryBytes > $perEntryLimit")
            }
            total[0] += read
            if (total[0] > limits.maxTotalBytes) {
                throw Rejection(InvalidReason.TooLarge, "${total[0]} > ${limits.maxTotalBytes}")
            }
            sink?.write(buffer, 0, read)
        }
        sink?.flush()
        return entryBytes
    }

    // ================= 校验 =================

    private fun requireSafeAttachmentName(name: String) {
        val problem = unsafeNameReason(name)
        if (problem != null) throw Rejection(InvalidReason.DangerousPath, "$problem: ${name.take(64)}")
    }

    /**
     * 文件名安全判定，附件条目名与笔记里的图片引用共用一套规则。
     *
     * 返回 null 表示安全；否则返回人可读的原因（写进 `detail`，会被限制长度）。
     */
    private fun unsafeNameReason(name: String): String? {
        if (name.isEmpty()) return "empty name"
        if (name.length > limits.maxNameLength) return "name too long"
        if (name == "." || name == "..") return "relative path"
        if (name.startsWith(".")) return "hidden name"
        if (name.contains('/')) return "contains separator"
        if (name.contains('\\')) return "contains backslash"
        if (name.contains('\u0000')) return "contains NUL"
        if (name.any { it.isISOControl() }) return "contains control character"
        return null
    }

    /**
     * 严格 UTF-8 解码。
     *
     * 不用 `String(bytes, Charsets.UTF_8)`：那条路会把坏字节替换成 U+FFFD，
     * 于是「文件损坏」被悄悄变成「内容里多了几个问号」，1B 还会照单写入正式数据。
     */
    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (invalid: CharacterCodingException) {
            throw Rejection(InvalidReason.NotUtf8, invalid.message ?: "invalid UTF-8")
        }
    }

    private suspend fun buildResult(
        token: String,
        dir: File,
        backupFile: File,
        scan: Scan
    ): PrepareResult {
        val notesText = decodeUtf8Strict(scan.notesBytes)
        val array: JSONArray = try {
            parseBackupArrayOrThrow(notesText)
        } catch (corrupt: JSONException) {
            throw Rejection(InvalidReason.CorruptNotes, corrupt.message)
        } catch (corrupt: Exception) {
            throw Rejection(InvalidReason.CorruptNotes, corrupt.message)
        }

        val notes = ArrayList<Note>(array.length())
        val noteIds = HashSet<String>()
        for (index in 0 until array.length()) {
            // 条目数上限只约束 zip 条目；单条 notes.json 里可以有上万条笔记，
            // 逐条校验是个长循环，这里必须能被取消打断
            currentCoroutineContext().ensureActive()

            val element = try {
                array.getJSONObject(index)
            } catch (wrong: JSONException) {
                throw Rejection(InvalidReason.CorruptNotes, "notes[$index] is not an object")
            }

            validateNoteFields(element, index)?.let { throw Rejection(InvalidReason.WrongFieldType, it) }

            val note = normalizedNote(element)
            if (!noteIds.add(note.id)) {
                throw Rejection(InvalidReason.DuplicateNoteId, note.id)
            }
            val todoIds = note.todos.map { it.id }.filter { it.isNotBlank() }
            if (todoIds.size != todoIds.toSet().size) {
                throw Rejection(InvalidReason.DuplicateTodoId, "note ${note.id}")
            }
            val unsafeImage = (note.images + note.inlineImages).firstOrNull { unsafeNameReason(it) != null }
            if (unsafeImage != null) {
                throw Rejection(
                    InvalidReason.UnsafeImageReference,
                    unsafeNameReason(unsafeImage) + ": " + unsafeImage.take(64)
                )
            }
            notes += note
        }

        // `[]` 夹带附件一律拒绝：有内容的附件、0 字节的附件都算。
        // 目录占位不算附件（它在扫描阶段就被要求无载荷了）。
        if (notes.isEmpty() && (scan.realAttachmentCount > 0 || scan.emptyAttachmentNames.isNotEmpty())) {
            throw Rejection(
                InvalidReason.EmptyNotesWithAttachments,
                "non-empty=${scan.realAttachmentCount} empty=${scan.emptyAttachmentNames.size}"
            )
        }

        val warnings = buildWarnings(notes, scan.attachmentNames, scan.emptyAttachmentNames)
        val prepared = PreparedBackup(
            token = token,
            stagingDir = dir,
            backupFile = backupFile,
            notesJson = notesText,
            notes = notes,
            attachmentNames = scan.attachmentNames,
            notesDigest = sha256Hex(scan.notesBytes),
            warnings = warnings,
            zipBytes = backupFile.length(),
            attachmentBytes = scan.attachmentBytes,
            entryCount = scan.entryCount
        )

        return if (notes.isEmpty()) PrepareResult.Empty(prepared) else PrepareResult.Ready(prepared)
    }

    /**
     * 把 JSON 转成 [Note]，并修正 `null` 时间戳的语义。
     *
     * `noteFromJson` 用的是 `optLong`：字段存在但值是 JSON `null` 时会得到 0，
     * 而 `deletedAt = 0` 意味着「1970 年删的」，会被当成回收站里的过期笔记清掉。
     * 导出代码永远只写非 null 值，所以这里把显式 `null` 还原成「没有该值」，
     * 不修改 `noteFromJson` 本身的兼容行为（普通读取路径仍按原样处理）。
     */
    private fun normalizedNote(json: JSONObject): Note {
        var note = noteFromJson(json)
        if (json.has("deletedAt") && json.opt("deletedAt") === JSONObject.NULL) {
            note = note.copy(deletedAt = null)
        }
        if (json.has("remindAt") && json.opt("remindAt") === JSONObject.NULL) {
            note = note.copy(remindAt = null)
        }
        return note
    }

    /**
     * 字段级类型检查，只看「已存在的字段」。
     *
     * 缺字段一律放行——旧备份没有 `audio` / `todos` / `remindAt`，靠 `noteFromJson` 的默认值补齐，
     * 这条兼容行为不能被导入校验破坏。
     */
    private fun validateNoteFields(note: JSONObject, index: Int): String? {
        val prefix = "notes[$index]"

        for (name in STRING_FIELDS) {
            if (note.has(name) && note.opt(name) !is String) {
                return "$prefix.$name must be string"
            }
        }
        for (name in NUMBER_FIELDS) {
            if (note.has(name) && note.opt(name) !is Number) {
                return "$prefix.$name must be number"
            }
        }
        for (name in BOOLEAN_FIELDS) {
            if (note.has(name) && note.opt(name) !is Boolean) {
                return "$prefix.$name must be boolean"
            }
        }
        for (name in NULLABLE_NUMBER_FIELDS) {
            if (note.has(name)) {
                val value = note.opt(name)
                if (value !== JSONObject.NULL && value !is Number) {
                    return "$prefix.$name must be number or null"
                }
            }
        }
        for (name in STRING_ARRAY_FIELDS) {
            if (!note.has(name)) continue
            val value = note.opt(name)
            if (value !is JSONArray) return "$prefix.$name must be array"
            for (position in 0 until value.length()) {
                if (value.opt(position) !is String) {
                    return "$prefix.$name[$position] must be string"
                }
            }
        }
        if (note.has("todos")) {
            val todos = note.opt("todos")
            if (todos !is JSONArray) return "$prefix.todos must be array"
            for (position in 0 until todos.length()) {
                val item = todos.opt(position)
                if (item !is JSONObject) return "$prefix.todos[$position] must be object"
                if (item.has("id") && item.opt("id") !is String) {
                    return "$prefix.todos[$position].id must be string"
                }
                if (item.has("text") && item.opt("text") !is String) {
                    return "$prefix.todos[$position].text must be string"
                }
                if (item.has("done") && item.opt("done") !is Boolean) {
                    return "$prefix.todos[$position].done must be boolean"
                }
            }
        }
        return null
    }

    private fun buildWarnings(
        notes: List<Note>,
        attachmentNames: List<String>,
        emptyAttachmentNames: List<String>
    ): List<ImportWarning> {
        val warnings = mutableListOf<ImportWarning>()
        val present = attachmentNames.toSet()

        val referencedImages = notes
            .flatMap { it.images + it.inlineImages }
            .distinct()
        val missingImages = referencedImages.filterNot { it in present }
        if (missingImages.isNotEmpty()) {
            warnings += ImportWarning(
                kind = WarningKind.MissingImages,
                names = missingImages,
                total = missingImages.size
            )
        }

        // 附件在、但它是空的：与「缺失」分开报，1B 才能给出不同的话术
        if (emptyAttachmentNames.isNotEmpty()) {
            warnings += ImportWarning(
                kind = WarningKind.EmptyAttachments,
                names = emptyAttachmentNames,
                total = emptyAttachmentNames.size
            )
        }

        // 备份格式从来不写录音文件，这里只能提示引用会失去播放能力
        val referencedAudio = notes
            .flatMap { it.audio + it.inlineAudio }
            .distinct()
        if (referencedAudio.isNotEmpty()) {
            warnings += ImportWarning(
                kind = WarningKind.RecordingsNotInBackup,
                names = referencedAudio,
                total = referencedAudio.size
            )
        }

        return warnings
    }

    // ================= 小工具 =================

    private fun sameFile(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (failure: Exception) {
        false
    }

    private fun looksLikeZip(file: File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(4)
        var read = 0
        file.inputStream().use { stream ->
            while (read < 4) {
                val count = stream.read(header, read, 4 - read)
                if (count < 0) break
                read += count
            }
        }
        if (read < 4) return false
        if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) return false
        // 本地文件头 PK\003\004 / 空归档 EOCD PK\005\006 / 分卷 PK\007\008
        val third = header[2].toInt()
        val fourth = header[3].toInt()
        return (third == 3 && fourth == 4) ||
            (third == 5 && fourth == 6) ||
            (third == 7 && fourth == 8)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class Scan(
        val notesBytes: ByteArray,
        /** 所有附件条目名，包含 0 字节的那些 */
        val attachmentNames: List<String>,
        /** 其中长度为 0 的附件名 */
        val emptyAttachmentNames: List<String>,
        /** 有内容的附件个数（0 字节附件不算） */
        val realAttachmentCount: Int,
        val attachmentBytes: Long,
        val entryCount: Int,
        val totalBytes: Long
    )

    /** 内部早退信号：携带拒绝原因，由 [prepare] 统一转成 Invalid 并清理暂存 */
    private class Rejection(val reason: InvalidReason, val detail: String? = null) : Exception(detail)

    private companion object {
        const val TAG = "BackupImporter"
        const val STAGING_ROOT = ".import"
        const val STAGING_PREFIX = "staging-"
        const val BACKUP_FILE = "backup.zip"
        const val NOTES_FILE = "notes.json"
        const val ATTACHMENTS_DIR = "attachments"
        const val COPY_BUFFER = 64 * 1024

        /** 只接受本类自己生成的 UUID 形式 token，防止拿 `../` 之类的东西拼路径 */
        val TOKEN_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        val STRING_FIELDS = listOf("id", "title", "content")
        val NUMBER_FIELDS = listOf("createdAt", "updatedAt", "colorIndex")
        val BOOLEAN_FIELDS = listOf("pinned", "checklist")
        val NULLABLE_NUMBER_FIELDS = listOf("deletedAt", "remindAt")
        val STRING_ARRAY_FIELDS = listOf("tags", "images", "audio")
    }
}
