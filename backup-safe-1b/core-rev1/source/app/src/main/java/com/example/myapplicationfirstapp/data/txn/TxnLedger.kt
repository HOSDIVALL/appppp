package com.example.myapplicationfirstapp.data.txn

/**
 * 终态结果留存。按 `txnId` 覆盖，最多 [MAX] 条，最新在前。
 *
 * 它**不是证据**：可以删、可以坏、不影响任何判定；写失败也不算事务失败。
 * 唯一的作用是清理之后仍能回答「这次导入最后怎么样」。
 */
internal class TxnLedger(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val clock: () -> Long
) {

    fun all(): List<ResultRecord> {
        if (!ops.exists(layout.resultsFile)) return emptyList()
        val bytes = try {
            ops.readBytes(layout.resultsFile)
        } catch (failure: java.io.IOException) {
            return emptyList()
        }
        return TxnCodec.decodeResults(String(bytes, Charsets.UTF_8)) ?: emptyList()
    }

    fun find(txnId: String): ResultRecord? = all().firstOrNull { it.txnId == txnId }

    fun append(record: ResultRecord): Boolean {
        val merged = (listOf(record) + all().filter { it.txnId != record.txnId }).take(MAX)
        return try {
            ops.checkpoint(TxnStep.RESULTS)
            ops.writeAtomic(layout.resultsFile, TxnCodec.encodeResults(merged).toByteArray())
            true
        } catch (failure: java.io.IOException) {
            false
        }
    }

    /** 已经有记录就不覆盖；返回 false 只表示**写失败**，不表示「本来就有」 */
    fun appendIfMissing(txnId: String, outcome: ResultOutcome, count: Int = 0, reason: String? = null): Boolean {
        if (find(txnId) != null) return true
        return append(ResultRecord(txnId, outcome, count, clock(), reason))
    }

    fun markConsumed(txnId: String): Boolean {
        val records = all()
        if (records.none { it.txnId == txnId }) return false
        val updated = records.map { if (it.txnId == txnId) it.copy(consumed = true) else it }
        return try {
            ops.writeAtomic(layout.resultsFile, TxnCodec.encodeResults(updated).toByteArray())
            true
        } catch (failure: java.io.IOException) {
            false
        }
    }

    private companion object {
        /** 留存上限；超出按「最旧」淘汰 */
        const val MAX = 8
    }
}
