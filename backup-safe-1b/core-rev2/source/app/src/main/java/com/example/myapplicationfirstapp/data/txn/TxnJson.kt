package com.example.myapplicationfirstapp.data.txn

import org.json.JSONArray
import org.json.JSONObject

/**
 * journal / 凭据 / 结果 / 备份清单的编解码。
 *
 * 解码一律**严格**，任何一项不合格都整份返回 `null`（视为不可读）：
 *
 * - 版本不符、枚举不认识、类型不对；
 * - **必填字段缺失**——缺字段与显式 `null` 是两件事，`oldNotesDigest` 丢了不能被当成
 *   「旧文件不存在」，附件的 `old` 丢了不能被当成「本次新增」；
 * - `txnId` / 附件名不是安全单层文件名（`..`、路径分隔符、控制字符）；
 * - 摘要不是 64 位小写十六进制。
 *
 * 不做「尽量读出一部分」：半读出来的 journal 会让回滚目标变成猜出来的值。
 */
internal object TxnCodec {

    private const val VERSION = 1

    private val DIGEST = Regex("^[0-9a-f]{64}$")

    // ---------------- journal ----------------

    fun encodeJournal(journal: Journal): String {
        val attachments = JSONArray()
        journal.attachments.forEach { entry ->
            attachments.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("old", entry.old ?: JSONObject.NULL)
                    .put("new", entry.new)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("state", journal.state.name)
            .put("txnId", journal.txnId)
            .put("commitDigest", journal.commitDigest)
            .put("oldNotesDigest", journal.oldNotesDigest ?: JSONObject.NULL)
            .put("attachments", attachments)
            .put("failedAt", journal.failedAt ?: JSONObject.NULL)
            .toString()
    }

    fun decodeJournal(text: String): Journal? = guarded {
        val root = JSONObject(text)
        version(root)
        val state = JournalState.entries.firstOrNull { it.name == string(root, "state") }
            ?: fail()
        val txnId = safeName(string(root, "txnId"))
        val commitDigest = digest(string(root, "commitDigest"))
        val oldNotesDigest = optionalDigest(root, "oldNotesDigest")
        val rawAttachments = array(root, "attachments")
        val attachments = ArrayList<JournalAttachment>(rawAttachments.length())
        for (index in 0 until rawAttachments.length()) {
            val item = rawAttachments.opt(index) as? JSONObject ?: fail()
            attachments += JournalAttachment(
                name = safeName(string(item, "name")),
                old = optionalDigest(item, "old"),
                new = digest(string(item, "new"))
            )
        }
        if (attachments.map { it.name }.toSet().size != attachments.size) fail()
        Journal(
            state = state,
            txnId = txnId,
            commitDigest = commitDigest,
            oldNotesDigest = oldNotesDigest,
            attachments = attachments,
            failedAt = optionalString(root, "failedAt")
        )
    }

    // ---------------- 凭据 ----------------

    fun encodeCredential(credential: CommitCredential): String = JSONObject()
        .put("version", VERSION)
        .put("txnId", credential.txnId)
        .put("commitDigest", credential.commitDigest)
        .put("committedAt", credential.committedAt)
        .toString()

    fun decodeCredential(text: String): CommitCredential? = guarded {
        val root = JSONObject(text)
        version(root)
        CommitCredential(
            txnId = safeName(string(root, "txnId")),
            commitDigest = digest(string(root, "commitDigest")),
            committedAt = number(root, "committedAt")
        )
    }

    // ---------------- 备份清单 ----------------

    fun encodeManifest(manifest: OldManifest): String {
        val notes = JSONObject().put("present", manifest.notesPresent)
        if (manifest.notesDigest != null) notes.put("digest", manifest.notesDigest)
        val attachments = JSONArray()
        manifest.attachments.forEach { entry ->
            attachments.put(
                JSONObject().put("name", entry.name).put("old", entry.old ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("notes", notes)
            .put("attachments", attachments)
            .toString()
    }

    fun decodeManifest(text: String): OldManifest? = guarded {
        val root = JSONObject(text)
        version(root)
        val notes = root.opt("notes") as? JSONObject ?: fail()
        val present = notes.opt("present") as? Boolean ?: fail()
        val notesDigest = if (present) digest(notes.opt("digest") as? String ?: fail()) else null
        if (!present && notes.has("digest") && notes.opt("digest") != JSONObject.NULL) fail()

        val rawAttachments = array(root, "attachments")
        val attachments = ArrayList<ManifestEntry>(rawAttachments.length())
        for (index in 0 until rawAttachments.length()) {
            val item = rawAttachments.opt(index) as? JSONObject ?: fail()
            attachments += ManifestEntry(
                name = safeName(string(item, "name")),
                old = optionalDigest(item, "old")
            )
        }
        if (attachments.map { it.name }.toSet().size != attachments.size) fail()
        OldManifest(present, notesDigest, attachments)
    }

    // ---------------- 结果留存 ----------------

    fun encodeResults(records: List<ResultRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("txnId", record.txnId)
                    .put("outcome", record.outcome.name)
                    .put("count", record.count)
                    .put("at", record.at)
                    .put("reason", record.reason ?: JSONObject.NULL)
                    .put("consumed", record.consumed)
            )
        }
        return JSONObject().put("version", VERSION).put("records", array).toString()
    }

    fun decodeResults(text: String): List<ResultRecord>? = guarded {
        val root = JSONObject(text)
        version(root)
        val array = array(root, "records")
        val records = ArrayList<ResultRecord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: fail()
            records += ResultRecord(
                txnId = string(item, "txnId"),
                outcome = ResultOutcome.entries.firstOrNull { it.name == string(item, "outcome") } ?: fail(),
                count = number(item, "count").toInt(),
                at = number(item, "at"),
                reason = optionalString(item, "reason"),
                consumed = item.opt("consumed") as? Boolean ?: fail()
            )
        }
        records
    }

    // ---------------- 严格读取 ----------------

    /** 解析失败用私有异常短路，出口统一转成 `null`；不把异常抛给调用方 */
    private class Unreadable : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (failure: Unreadable) {
        null
    } catch (failure: Exception) {
        null
    }

    private fun fail(): Nothing = throw Unreadable()

    private fun version(root: JSONObject) {
        if (number(root, "version") != VERSION.toLong()) fail()
    }

    private fun string(root: JSONObject, key: String): String =
        if (!root.has(key)) fail() else (root.opt(key) as? String) ?: fail()

    /** 缺字段直接判不可读；显式 `null` 才算「没有」 */
    private fun optionalString(root: JSONObject, key: String): String? {
        if (!root.has(key)) fail()
        return when (val value = root.opt(key)) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> fail()
        }
    }

    private fun optionalDigest(root: JSONObject, key: String): String? =
        optionalString(root, key)?.let { digest(it) }

    private fun digest(value: String): String = if (DIGEST.matches(value)) value else fail()

    private fun safeName(value: String): String =
        if (TxnLayout.isSafeName(value)) value else fail()

    private fun array(root: JSONObject, key: String): JSONArray =
        if (!root.has(key)) fail() else (root.opt(key) as? JSONArray) ?: fail()

    private fun number(root: JSONObject, key: String): Long =
        if (!root.has(key)) fail() else (root.opt(key) as? Number)?.toLong() ?: fail()
}
