package com.example.myapplicationfirstapp.data.txn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * BACKUP-SAFE-1B-CORE：隔离事务内核（core-rev1）。
 *
 * 只接受两样东西：**调用方指定的存储根**与**已经物化好的材料**（`txn-<txnId>/commit.json`
 * 与 `new-attachments/`）。它不读 Context、不解析 Uri、不补 ID/时间戳、不碰 UI，
 * 因此在测试里可以把根指到一个 UUID 沙箱，跑完整的提交/回滚/清理。
 *
 * ## 职责分层
 *
 * 本类只做三件事：**串行化**（[mutex]）、**闸门**（[WriteGate]）、**编排**（`recover` 的分支）。
 * 具体机制按职责拆在别的文件里，便于单独阅读：
 *
 * | 文件 | 职责 |
 * |---|---|
 * | `TxnLayout` | 固定布局、路径拼装、`isSafeName` 安全边界 |
 * | `TxnPathGuard` | 根身份 + **形状即边界**：`.import/` 只认已知三层，其余一律不安全，检查不出来也停 |
 * | `TxnSafeDelete` | 有边界的删除：逐层看实际类型，链接与判不出来的整片放弃 |
 * | `TxnDisk` | 只读磁盘访问；`Absent` 与 `Unreadable` 严格分开 |
 * | `TxnClassifier` | 磁盘 → `DiskState`，不写任何文件 |
 * | `TxnLedger` | 终态结果留存（不是证据、不参与判定） |
 * | `ImportTxnCommitPath` | 物化、备份、替换、提交点、提交后清理 |
 * | `ImportTxnRollbackPath` | 分类授权的回滚状态机（逐文件收敛 + manifest 校验） |
 * | `ImportTxnCleanupPath` | 清理与隔离处置（先核对 `txnId`） |
 * | `TxnJson` | journal / 凭据 / 结果 / manifest 的严格编解码 |
 *
 * ## 并发契约
 *
 * 同一个内核实例（同一个存储根）的所有操作都由 [mutex] 串行：`commit` / `recover` /
 * `cleanup` / `rollback` / `discardQuarantined` / `materialize`。`inspect` 也拿同一把锁，
 * 所以它不会读到「清理删到一半」的中间态。跨实例共享同一个根**不受保护**——
 * 生产上只应该有一个实例（后批的 `NotesStore`），测试里每个沙箱一个实例。
 *
 * ## 闸门与放行
 *
 * 提交走 [WriteGate.beginExclusive]（非 OPEN 直接拒），恢复/回滚/清理走
 * [WriteGate.beginRecovery]（等正在跑的写入退出，不看当前状态）。两者共用同一把写锁。
 * 结束时交还锁，但**是否回到 OPEN 由磁盘决定**：只有分类结果是 `Idle` 才放行普通保存。
 *
 * ## 本批不做什么
 *
 * - 不接线 `NotesRepository` / `NotesState` / `MainActivity` / `ReminderReceiver`；
 * - 不 prune 未被引用的旧附件（删除用户文件需要接线批的确认流程）；
 * - 不做 prepare 的 ID/时间戳物化，只把调用方给的字节落成事务材料。
 */
internal class ImportTxnKernel(
    val root: File,
    private val ops: TxnFileOps = RealTxnFileOps(),
    private val gate: WriteGate = WriteGate(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val mutex = Mutex()

    private val layout = TxnLayout(root, ops)
    private val disk = TxnDisk(layout, ops)
    private val classifier = TxnClassifier(layout, ops, disk)
    private val ledger = TxnLedger(layout, ops, clock)
    private val safeDelete = TxnSafeDelete(ops)
    private val rollbackPath = ImportTxnRollbackPath(layout, ops, disk, classifier, ledger, clock)
    private val cleanupPath = ImportTxnCleanupPath(layout, ops, classifier, ledger, safeDelete)
    private val commitPath = ImportTxnCommitPath(
        layout, ops, disk, classifier, rollbackPath, cleanupPath, ledger, safeDelete, clock
    )

    // ================= 只读 =================

    suspend fun inspect(): DiskState = mutex.withLock {
        withContext(io) { classifier.classify() }
    }

    suspend fun currentResult(txnId: String): ResultRecord? = mutex.withLock {
        withContext(io) { ledger.find(txnId) }
    }

    suspend fun results(): List<ResultRecord> = mutex.withLock {
        withContext(io) { ledger.all() }
    }

    suspend fun markConsumed(txnId: String): Boolean = mutex.withLock {
        withContext(io) { ledger.markConsumed(txnId) }
    }

    // ================= 材料落地 =================

    suspend fun materialize(
        txnId: String,
        commitBytes: ByteArray,
        attachments: Map<String, ByteArray>
    ): MaterializeOutcome = mutex.withLock {
        withContext(io) { commitPath.materialize(txnId, commitBytes, attachments) }
    }

    // ================= 提交 =================

    suspend fun commit(ref: MaterialRef): CommitOutcome = mutex.withLock {
        withContext(io) {
            val admission = gate.beginExclusive()
            if (admission is WriteGate.Admission.Refused) {
                return@withContext CommitOutcome.Refused(admission.refusal)
            }
            val ticket = (admission as WriteGate.Admission.Granted).ticket
            try {
                commitPath.commitLocked(ref)
            } finally {
                gate.releaseExclusive(ticket, nextModeLocked())
            }
        }
    }

    // ================= 回滚 / 清理 / 隔离处置 =================

    suspend fun rollback(txnId: String): RollbackOutcome = withRecoveryGate {
        rollbackPath.rollbackLocked(txnId)
    }

    suspend fun cleanup(txnId: String): CleanupOutcome = withRecoveryGate {
        cleanupPath.cleanupLocked(txnId)
    }

    suspend fun discardQuarantined(txnId: String): CleanupOutcome = withRecoveryGate {
        cleanupPath.discardLocked(txnId)
    }

    // ================= 恢复 =================

    /**
     * 启动恢复。按 `classify()` 的结论收尾：
     *
     * - `Interrupted` / `RollingBack` / `RollbackFailed` → 回滚 → 清理；
     * - `Aborted` / `StagingFailed` → 清理；
     * - `Committed` → 补结果与终态 → 清理；
     * - `Prepared` / `Quarantined` / `Corrupt` → 中止并保留现场（并继续停写）。
     */
    suspend fun recover(): RecoverOutcome = withRecoveryGate { recoverLocked() }

    private fun recoverLocked(): RecoverOutcome = when (val state = classifier.classify()) {
        DiskState.Idle -> RecoverOutcome.Settled(DiskState.Idle, null)
        is DiskState.Prepared -> RecoverOutcome.Stopped(state, "awaiting-confirmation")
        is DiskState.Quarantined -> RecoverOutcome.Stopped(state, "lifecycle-unknown")
        is DiskState.Corrupt -> RecoverOutcome.Stopped(state, state.reason)

        // 回滚失败不是终态：每次启动都重跑同一状态机，直到收敛或再次失败
        is DiskState.RollbackFailed -> settleRollback(state.txnId)
        is DiskState.Interrupted -> settleRollback(state.txnId)
        is DiskState.RollingBack -> settleRollback(state.txnId)

        is DiskState.StagingFailed -> {
            ledger.appendIfMissing(state.txnId, ResultOutcome.NOT_STARTED)
            val cleanup = cleanupPath.cleanupLocked(state.txnId)
            RecoverOutcome.Settled(classifier.classify(), cleanup)
        }

        is DiskState.Aborted -> {
            val cleanup = cleanupPath.cleanupLocked(state.txnId)
            RecoverOutcome.Settled(classifier.classify(), cleanup)
        }

        is DiskState.Committed -> {
            ledger.appendIfMissing(state.txnId, ResultOutcome.COMMITTED)
            rewriteCommittedJournal(state.txnId)
            val cleanup = cleanupPath.cleanupLocked(state.txnId)
            RecoverOutcome.Settled(classifier.classify(), cleanup)
        }
    }

    private fun settleRollback(txnId: String): RecoverOutcome =
        when (val outcome = rollbackPath.rollbackLocked(txnId)) {
            RollbackOutcome.Restored -> {
                val cleanup = cleanupPath.cleanupLocked(txnId)
                RecoverOutcome.Settled(classifier.classify(), cleanup)
            }

            is RollbackOutcome.Failed -> RecoverOutcome.Stopped(classifier.classify(), outcome.detail)
        }

    private fun rewriteCommittedJournal(txnId: String) {
        val journal = (disk.journal() as? JournalRead.Present)?.value ?: return
        if (journal.state == JournalState.COMMITTED) return
        runCatching {
            ops.checkpoint(TxnStep.JOURNAL)
            ops.writeAtomic(
                layout.journalFile,
                TxnCodec.encodeJournal(journal.copy(state = JournalState.COMMITTED, failedAt = null))
                    .toByteArray()
            )
        }
    }

    // ================= 闸门 =================

    /** 恢复类操作：等正在跑的写入退出，不看当前状态，因此 `BLOCKED` 之后仍可重试 */
    private suspend fun <T> withRecoveryGate(block: () -> T): T = mutex.withLock {
        withContext(io) {
            val ticket = gate.beginRecovery()
            try {
                block()
            } finally {
                gate.releaseExclusive(ticket, nextModeLocked())
            }
        }
    }

    /**
     * 交还写锁时该回到哪个状态。
     *
     * 只有磁盘真的收敛到 `Idle` 才放行普通保存。`Committed`（清理没做完）、`Quarantined`、
     * `Corrupt`、`RollbackFailed`、`Prepared` 一律继续停写——写锁交还了，通道没开。
     */
    private fun nextModeLocked(): WriteGate.Mode =
        if (classifier.classify() is DiskState.Idle) WriteGate.Mode.OPEN else WriteGate.Mode.BLOCKED
}
