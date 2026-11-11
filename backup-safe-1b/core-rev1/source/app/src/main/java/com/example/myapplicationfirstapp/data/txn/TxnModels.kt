package com.example.myapplicationfirstapp.data.txn

/**
 * 事务内核的数据模型。
 *
 * 这里刻意不引用 `Note` / `PreparedBackup`：内核只处理**已物化的字节**
 * （`commit.json`、附件内容、旧数据），物化（补 ID/时间戳、生成 `plan.json`）
 * 属于 prepare 接线，是后批的事。这样内核可以在任意隔离根上被测试，
 * 也不会因为改 `Note` 而连带改事务。
 */

internal enum class JournalState {
    PREPARED,
    APPLYING,
    ROLLING_BACK,
    ABORTED,
    COMMITTED
}

/**
 * 一个附件在事务前后的摘要。
 *
 * [old] 为 null 表示「提交前没有这个文件」（本次新增）。回滚遇到 `new` 且 `old == null`
 * 就是删掉它，而不是从副本恢复。
 */
internal data class JournalAttachment(
    val name: String,
    val old: String?,
    val new: String
)

/** `old-manifest.json` 的一条：只描述**导入前**的样子，所以没有 `new` */
internal data class ManifestEntry(val name: String, val old: String?)

/**
 * 备份清单。
 *
 * 回滚在动用任何副本之前，必须核对它与 `journal` 完全一致：同一条附件、同一个旧摘要、
 * 同样的「旧 notes 是否存在」。否则宁可停写，也不能用一份陈旧的副本覆盖正式数据。
 */
internal data class OldManifest(
    val notesPresent: Boolean,
    val notesDigest: String?,
    val attachments: List<ManifestEntry>
)

/**
 * 事务日志。
 *
 * 它是**状态机的唯一输入**：回滚目标、逐文件摘要、失败位置都在这里。
 * 字段类型都是严格解析的，任何一项类型不对都按「不可读」处理（[JournalCodec.decode] 返回 null），
 * 不做「尽量读出来」的容错——半读出来的 journal 比读不出来更危险。
 */
internal data class Journal(
    val state: JournalState,
    val txnId: String,
    val commitDigest: String,
    val oldNotesDigest: String?,
    val attachments: List<JournalAttachment>,
    val failedAt: String? = null
)

internal data class CommitCredential(
    val txnId: String,
    val commitDigest: String,
    val committedAt: Long
)

internal enum class ResultOutcome { COMMITTED, ABORTED, NOT_STARTED }

/**
 * 终态结果记录。按 `txnId` 留存，**不是凭据**，不含摘要，不参与 `classify()`。
 *
 * 它只回答「这次导入最后怎么样」，所以清理之后仍可查；也因为它不带摘要，
 * 后续正常保存把 `notes.json` 改掉，不会被误判成损坏。
 */
internal data class ResultRecord(
    val txnId: String,
    val outcome: ResultOutcome,
    val count: Int,
    val at: Long,
    val reason: String? = null,
    val consumed: Boolean = false
)

/**
 * 磁盘判定结论。`classify()` 只输出这些值，不做任何写动作。
 */
internal sealed interface DiskState {

    data object Idle : DiskState

    /** 材料齐、未动正式数据，等待确认 */
    data class Prepared(val txnId: String) : DiskState

    /** journal 是 APPLYING 但没有终态证据：必须回滚（不看 notes 是否已等于新载荷） */
    data class Interrupted(val txnId: String) : DiskState

    data class RollingBack(val txnId: String) : DiskState

    /** 回滚已完成，等待清理 */
    data class Aborted(val txnId: String) : DiskState

    data class Committed(val txnId: String) : DiskState

    /** PREPARED 但材料不完整：可安全放弃 */
    data class StagingFailed(val txnId: String) : DiskState

    /**
     * 有事务材料、却没有 journal 也没有凭据：生命周期无法确定。
     *
     * 不删、不回滚，等显式处置。**不**用 `notes.json` 摘要来推断「可以删」。
     */
    data class Quarantined(val txnDirs: List<String>) : DiskState

    data class Corrupt(val reason: String) : DiskState

    data class RollbackFailed(val txnId: String, val detail: String) : DiskState
}

/** 调用方给出的「已物化材料」引用；内核只按这个去磁盘上读，不接受内存里的载荷 */
internal data class MaterialRef(
    val txnId: String,
    /** 调用方期望的载荷摘要；非 null 时与磁盘重算值不符即 `MaterialChanged` */
    val expectedCommitDigest: String? = null
)

internal sealed interface MaterializeOutcome {
    data class Ready(val txnId: String, val commitDigest: String) : MaterializeOutcome
    data class Rejected(val reason: String) : MaterializeOutcome
}

internal sealed interface CommitOutcome {
    /** 已提交；[cleanup] 可能只完成一部分，不影响提交事实 */
    data class Committed(val count: Int, val cleanup: CleanupOutcome) : CommitOutcome

    /** 尚未动正式数据就退出 */
    data class NotStarted(val reason: String) : CommitOutcome

    /** 写前被拒（非 OPEN / 已有写入），没有任何 IO 发生 */
    data class Refused(val refusal: WriteGate.Refusal) : CommitOutcome

    /** 已回滚或停在可判定状态；[detail] 是原始异常信息，用于日志，不用于判定 */
    data class Failed(val state: DiskState, val detail: String) : CommitOutcome
}

internal data class CleanupOutcome(
    val cleaned: Boolean,
    val leftovers: List<String>,
    /** 非 null 表示在哪个步骤停下（关键步骤失败或门禁未通过） */
    val stoppedAt: String? = null
)

internal sealed interface RollbackOutcome {
    data object Restored : RollbackOutcome
    data class Failed(val detail: String) : RollbackOutcome
}

internal sealed interface RecoverOutcome {
    /** 收敛到终态（多为 [DiskState.Idle]）；[cleanup] 为本次清理结果 */
    data class Settled(val state: DiskState, val cleanup: CleanupOutcome?) : RecoverOutcome

    /** 保持现状并停写，交给上层展示或人工处理 */
    data class Stopped(val state: DiskState, val reason: String) : RecoverOutcome
}
