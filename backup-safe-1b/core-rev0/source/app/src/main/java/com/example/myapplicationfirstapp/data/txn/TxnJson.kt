package com.example.myapplicationfirstapp.data.txn

import org.json.JSONArray
import org.json.JSONObject

/**
 * journal / 凭据 / 结果留存的编码。
 *
 * 解码一律**严格**：任何必填字段缺失、类型不对、枚举不认识，整份返回 null（视为不可读）。
 * 不做「尽量读出一部分」——半读出来的 journal 会让回滚目标变成猜出来的值。
 */
internal object TxnCodec {

    private const val VERSION = 1

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

    fun decodeJournal(text: String): Journal? {
        val root = parse(text) ?: return null
        if (long(root, "version") != VERSION.toLong()) return null
        val state = str(root, "state")?.let { name ->
            JournalState.entries.firstOrNull { it.name == name }
        } ?: return null
        val txnId = str(root, "txnId")?.takeIf { it.isNotBlank() } ?: return null
        val commitDigest = str(root, "commitDigest")?.takeIf { it.isNotBlank() } ?: return null
        val oldNotesDigest = optionalString(root, "oldNotesDigest").getOrElse { return null }
        val attachmentsArray = root.opt("attachments") as? JSONArray ?: return null
        val attachments = ArrayList<JournalAttachment>(attachmentsArray.length())
        for (index in 0 until attachmentsArray.length()) {
            val item = attachmentsArray.opt(index) as? JSONObject ?: return null
            val name = str(item, "name")?.takeIf { it.isNotBlank() } ?: return null
            val old = optionalString(item, "old").getOrElse { return null }
            val new = str(item, "new")?.takeIf { it.isNotBlank() } ?: return null
            attachments += JournalAttachment(name, old, new)
        }
        val failedAt = optionalString(root, "failedAt").getOrElse { return null }
        if (attachments.map { it.name }.toSet().size != attachments.size) return null
        return Journal(state, txnId, commitDigest, oldNotesDigest, attachments, failedAt)
    }

    fun encodeCredential(credential: CommitCredential): String = JSONObject()
        .put("version", VERSION)
        .put("txnId", credential.txnId)
        .put("commitDigest", credential.commitDigest)
        .put("committedAt", credential.committedAt)
        .toString()

    fun decodeCredential(text: String): CommitCredential? {
        val root = parse(text) ?: return null
        if (long(root, "version") != VERSION.toLong()) return null
        val txnId = str(root, "txnId")?.takeIf { it.isNotBlank() } ?: return null
        val commitDigest = str(root, "commitDigest")?.takeIf { it.isNotBlank() } ?: return null
        val committedAt = long(root, "committedAt") ?: return null
        return CommitCredential(txnId, commitDigest, committedAt)
    }

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

    fun decodeResults(text: String): List<ResultRecord>? {
        val root = parse(text) ?: return null
        if (long(root, "version") != VERSION.toLong()) return null
        val array = root.opt("records") as? JSONArray ?: return null
        val records = ArrayList<ResultRecord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: return null
            val txnId = str(item, "txnId")?.takeIf { it.isNotBlank() } ?: return null
            val outcome = str(item, "outcome")?.let { name ->
                ResultOutcome.entries.firstOrNull { it.name == name }
            } ?: return null
            val count = long(item, "count")?.toInt() ?: return null
            val at = long(item, "at") ?: return null
            val reason = optionalString(item, "reason").getOrElse { return null }
            val consumed = bool(item, "consumed") ?: return null
            records += ResultRecord(txnId, outcome, count, at, reason, consumed)
        }
        return records
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (failure: Exception) {
        null
    }

    private fun str(root: JSONObject, key: String): String? = root.opt(key) as? String

    /** 缺省与显式 null 都算成功（值为 null）；类型不对算失败，由调用方整份判不可读 */
    private fun optionalString(root: JSONObject, key: String): Result<String?> {
        val value = root.opt(key)
        return when {
            value == null || value == JSONObject.NULL -> Result.success(null)
            value is String -> Result.success(value)
            else -> Result.failure(IllegalArgumentException(key))
        }
    }

    private fun long(root: JSONObject, key: String): Long? = when (val value = root.opt(key)) {
        is Number -> value.toLong()
        else -> null
    }

    private fun bool(root: JSONObject, key: String): Boolean? = root.opt(key) as? Boolean
}
