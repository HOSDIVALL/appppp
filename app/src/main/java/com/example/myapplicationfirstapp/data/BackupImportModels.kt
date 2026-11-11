package com.example.myapplicationfirstapp.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 准备阶段的资源上限。
 *
 * 判定全部基于**实际读到的字节**：不读 `ZipEntry.size`（ZIP 可以谎报），
 * 也不算压缩比（比值由声明长度推出，同样不可信）。每个条目边解压边计数，超了立刻停。
 *
 * 作为数据类而不是 object，是为了让测试能用很小的上限去覆盖每条拒绝路径；
 * [DEFAULT] 是生产值，测试里有一条用例专门断言它没被改动。
 */
internal data class BackupLimits(
    /** 选中 URI 拷进暂存的 zip 本体上限 */
    val maxZipBytes: Long = 128L * 1024 * 1024,
    /** notes.json 解压后上限 */
    val maxNotesBytes: Long = 32L * 1024 * 1024,
    /** 单个条目解压后上限 */
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    /** 所有条目解压后累计上限（未知条目也计入） */
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    /** 条目总数上限（未知条目也计入） */
    val maxEntries: Int = 2000,
    /** 单个文件名长度上限 */
    val maxNameLength: Int = 128

) {
    companion object {
        val DEFAULT = BackupLimits()
    }
}

/** 准备阶段的三种结果：正常、合法空备份、拒绝 */
internal sealed interface PrepareResult {
    data class Ready(val prepared: PreparedBackup) : PrepareResult
    data class Empty(val prepared: PreparedBackup) : PrepareResult

    /**
     * 拒绝。
     *
     * [residue] 是「清理没成功」的暂存路径。它**永远不参与**判定成败：
     * [reason] / [detail] 保持触发拒绝的原始原因，残留只是补充信息，
     * 免得一次删不掉目录就把真实原因改写成 StagingFailed。
     */
    data class Invalid(
        val reason: InvalidReason,
        val detail: String? = null,
        val residue: List<String> = emptyList()
    ) : PrepareResult
}

/**
 * 拒绝原因。区分得细是为了让 1B 的提示文案能落到具体一句话上，
 * 而不是笼统的「文件无效」。
 */
internal enum class InvalidReason {
    /** 选中 URI 打不开（权限失效、提供方出错） */
    SourceUnreadable,

    /** 不是 zip（签名/结构不对） */
    NotZip,

    /** zip 里一个条目都没有 */
    EmptyArchive,

    /** 没有 notes.json */
    MissingNotes,

    /** notes.json 不是 JSON 数组，或尾部有垃圾，或元素不是对象 */
    CorruptNotes,

    /**
     * notes.json 不是合法 UTF-8。
     *
     * 单独分出来是因为「解码」和「解析」是两件事：坏字节一旦被宽松解码成 U+FFFD，
     * 一份损坏的备份就会变成一份看起来正常、实际内容被改过的备份。
     */
    NotUtf8,

    /** 已存在字段的类型明显不对（缺字段不算，保留旧备份兼容） */
    WrongFieldType,

    /** zip 内同名条目出现两次 */
    DuplicateEntry,

    /** 两条笔记 id 相同 */
    DuplicateNoteId,

    /** 同一条笔记里待办 id 重复 */
    DuplicateTodoId,

    /** 条目名是危险路径（`..`、`.`、含分隔符、绝对路径、控制字符） */
    DangerousPath,

    /**
     * 目录条目带了载荷。
     *
     * zip 里可以声明一个以 `/` 结尾的条目同时又塞进内容，各家解压器对它的处理不一致；
     * 本层只接受**无载荷**的 `attachments/` 占位，其余一律整包拒绝。
     */
    DirectoryWithPayload,

    /** 笔记引用了不安全的图片文件名 */
    UnsafeImageReference,

    /** 笔记为空但备份里带了实际附件文件 */
    EmptyNotesWithAttachments,

    /** 某个条目解压/校验失败（CRC 不符、压缩流损坏） */
    CorruptEntry,

    /** 条目数超过上限 */
    TooManyEntries,

    /** 单个条目解压后超过上限 */
    EntryTooLarge,

    /** 所有条目累计解压后超过上限 */
    TooLarge,

    /** notes.json 解压后超过上限 */
    NotesTooLarge,

    /** URI 副本本体超过上限 */
    ZipTooLarge,

    /** 暂存目录建不出来 / 写暂存失败 */
    StagingFailed
}

/** 告警类别：只提示，不阻止导入 */
internal enum class WarningKind {
    /** 笔记引用的图片在备份里不存在 */
    MissingImages,

    /** 笔记引用了录音，但备份从不包含录音文件 */
    RecordingsNotInBackup,

    /** 备份里的附件是 0 字节：文件在、内容空，导入后图片显示不出来 */
    EmptyAttachments
}

/**
 * 一条告警。
 *
 * [names] 是给提示用的短列表（最多保留前若干个），[total] 是真实总数，
 * 展示时不能只用 `names.size` 冒充总数。
 */
internal data class ImportWarning(
    val kind: WarningKind,
    val names: List<String>,
    val total: Int
) {
    /**
     * 给 Snackbar / 对话框用的单行摘要。
     *
     * 文件名来自外部备份，可能带换行、控制字符或超长，直接拼进界面会撕裂布局，
     * 所以统一走 [sanitizeForDisplay]。
     */
    fun displaySummary(maxNames: Int = 5, maxNameLength: Int = 40): String {
        val shown = names.take(maxNames).joinToString("、") { sanitizeForDisplay(it, maxNameLength) }
        val suffix = if (total > maxNames) " 等 $total 个" else ""
        return when (kind) {
            WarningKind.MissingImages -> "有 $total 张图片引用不在备份里：$shown$suffix"
            WarningKind.RecordingsNotInBackup -> "备份不含录音文件，$total 段录音引用导入后可能无法播放：$shown$suffix"
            WarningKind.EmptyAttachments -> "有 $total 个附件是 0 字节，导入后无法显示：$shown$suffix"
        }
    }
}

/**
 * 准备完成、等待提交的备份。
 *
 * 所有内容都落在这个对象引用的暂存目录里；1B 提交时必须按 [token] 重新从磁盘读取，
 * 不信任内存里的副本。
 *
 * ## 提交契约
 *
 * 这里**没有**「原始文本直接写盘」这条路。四个和内容有关的字段职责不同，不能互相顶替：
 *
 * - [notesJson]：`notes.json` 的原始文本（严格 UTF-8 解码后的结果）。
 *   **只用于诊断与差异对比，禁止写入正式存储。**
 *   原因是它可能带显式 `null`（`"deletedAt": null`），而现有读取路径 `noteFromJson`
 *   把「字段存在且为 null」读成 `0`——写回去就是把笔记标成「1970 年删除」。
 * - [notes]：归一视图，只服务于告警文案与界面预览。
 *   [Note.toJson] 会丢掉未知字段、也会省掉显式 null，所以**不能**拿它序列化回去当载荷。
 * - [commitJson]：**1B 唯一允许写入 `notes.json` 的内容**。由每个原始元素与归一结果合并：
 *   未知字段原样保留，已知字段取归一值，`deletedAt` / `remindAt` 为 null 时**删除该键**
 *   （而不是写 null），键按字典序排列——因此同一份输入产出逐字节相同的载荷。
 *   前提是输入自带 `createdAt` / `updatedAt`：两者缺失时 `noteFromJson` 会按读取时刻补齐，
 *   这是既有读取语义，本层不另造一套，所以那种旧备份的载荷不承诺跨次可复现。
 * - [notesDigest] / [commitDigest]：分别是**原始字节**与 [commitJson] 的 SHA-256。
 *   前者只用于确认「暂存文件还是同一份材料」，后者供 1B 重建载荷后复核；两者都不参与准入判定。
 */
internal data class PreparedBackup(
    val token: String,
    val stagingDir: File,
    val backupFile: File,
    val notesJson: String,
    val notes: List<Note>,
    val attachmentNames: List<String>,
    val notesDigest: String,
    val commitJson: String,
    val commitDigest: String,
    val warnings: List<ImportWarning>,
    val zipBytes: Long,
    val attachmentBytes: Long,
    val entryCount: Int
) {
    val isEmpty: Boolean get() = notes.isEmpty()

    /** 提交载荷的字节（UTF-8），1B 写盘用这个而不是 [notesJson] */
    fun commitBytes(): ByteArray = commitJson.toByteArray(Charsets.UTF_8)
}

/**
 * 确定性 JSON 序列化：对象键按字典序输出，数组保持原顺序。
 *
 * 用它而不是 `JSONObject.toString()`：后者的键序取决于内部 `HashMap` 的迭代顺序，
 * 换一个 JVM 或换一批键就可能变；提交载荷要能按摘要复核，就必须逐字节稳定。
 */
internal object CanonicalJson {

    fun write(value: Any?): String = StringBuilder().also { append(value, it) }.toString()

    private fun append(value: Any?, out: StringBuilder) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> appendObject(value, out)
            is JSONArray -> appendArray(value, out)
            is String -> out.append(JSONObject.quote(value))
            is Boolean -> out.append(if (value) "true" else "false")
            is Number -> out.append(numberToString(value))
            else -> out.append(JSONObject.quote(value.toString()))
        }
    }

    private fun appendObject(value: JSONObject, out: StringBuilder) {
        out.append('{')
        val keys = value.keys().asSequence().sorted().toList()
        keys.forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            out.append(JSONObject.quote(key)).append(':')
            append(value.opt(key), out)
        }
        out.append('}')
    }

    private fun appendArray(value: JSONArray, out: StringBuilder) {
        out.append('[')
        for (index in 0 until value.length()) {
            if (index > 0) out.append(',')
            append(value.opt(index), out)
        }
        out.append(']')
    }

    private fun numberToString(value: Number): String = try {
        JSONObject.numberToString(value)
    } catch (failure: Exception) {
        value.toString()
    }
}

/**
 * 把外部文件名变成可以安全拼进提示文字的形式：
 * 控制字符替换为 `?`，超长截断并加省略号。不做任何路径处理（那是校验层的事）。
 */
internal fun sanitizeForDisplay(raw: String, maxLength: Int = 40): String {
    val cleaned = buildString {
        raw.forEach { ch ->
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                ch.isISOControl() -> append('?')
                else -> append(ch)
            }
        }
    }.trim()
    if (cleaned.length <= maxLength) return cleaned
    val keep = (maxLength - 1).coerceAtLeast(1)
    return cleaned.take(keep) + "…"
}
