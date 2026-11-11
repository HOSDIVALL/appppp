package com.example.myapplicationfirstapp.data.txn

/**
 * 磁盘分类器。唯一职责：把「磁盘上现在是什么」翻成 [DiskState]，**不写任何文件**。
 *
 * 两条硬规则（rev1 已落进判定表）：
 *
 * 1. 凭据不可读时**不能**当成「没有凭据」——那会把「可能已提交」误判成「可以回滚」。
 * 2. `APPLYING` 只有在「凭据完整匹配 + 摘要一致」时才算已提交，其余一律 `Interrupted`；
 *    不看 `notes.json` 是否已经等于新载荷。
 */
internal class TxnClassifier(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val disk: TxnDisk
) {

    fun classify(): DiskState = when (val journal = disk.journal()) {
        JournalRead.Unreadable -> when (val credential = disk.credential()) {
            CredentialRead.Unreadable -> DiskState.Corrupt("journal-and-credential-unreadable")
            // 只有凭据能救，且必须与正式数据对得上
            is CredentialRead.Present ->
                if (disk.notesDigest() == credential.value.commitDigest) {
                    DiskState.Committed(credential.value.txnId)
                } else {
                    DiskState.Corrupt("journal-unreadable")
                }

            CredentialRead.Absent -> DiskState.Corrupt("journal-unreadable")
        }

        JournalRead.Absent -> when (val credential = disk.credential()) {
            CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
            is CredentialRead.Present ->
                if (disk.notesDigest() == credential.value.commitDigest) {
                    DiskState.Committed(credential.value.txnId)
                } else {
                    DiskState.Corrupt("credential-digest-mismatch")
                }

            // 没有 journal 也没有凭据：还剩事务材料就无法确定生命周期。
            // 不按 notes 摘要推断「可以删」——摘要相同不代表附件没被动过。
            CredentialRead.Absent -> {
                val dirs = layout.txnDirs(ops)
                if (dirs.isEmpty()) DiskState.Idle else DiskState.Quarantined(dirs.map { it.name })
            }
        }

        is JournalRead.Present -> judge(journal.value, disk.credential())
    }

    private fun judge(journal: Journal, credential: CredentialRead): DiskState {
        val digest = disk.notesDigest()
        return when (journal.state) {
            JournalState.COMMITTED -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present &&
                    (credential.value.txnId != journal.txnId ||
                        credential.value.commitDigest != journal.commitDigest) ->
                    DiskState.Corrupt("credential-journal-conflict")

                digest != journal.commitDigest -> DiskState.Corrupt("notes-digest-mismatch")
                else -> DiskState.Committed(journal.txnId)
            }

            JournalState.APPLYING -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")

                credential is CredentialRead.Present &&
                    credential.value.txnId == journal.txnId &&
                    credential.value.commitDigest == journal.commitDigest &&
                    digest == journal.commitDigest ->
                    DiskState.Committed(journal.txnId)

                credential is CredentialRead.Present -> DiskState.Corrupt("credential-inconsistent")
                // 没有终态证据：无论 notes 是否已经等于新载荷，都只能回滚
                else -> DiskState.Interrupted(journal.txnId)
            }

            JournalState.ROLLING_BACK -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-rolling-back")
                // 上一次回滚失败过：仍可重跑同一套状态机，只是先如实报告失败位置
                journal.failedAt != null -> DiskState.RollbackFailed(journal.txnId, journal.failedAt!!)
                else -> DiskState.RollingBack(journal.txnId)
            }

            JournalState.ABORTED -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-aborted")
                digest == journal.oldNotesDigest -> DiskState.Aborted(journal.txnId)
                else -> DiskState.Corrupt("aborted-but-notes-changed")
            }

            JournalState.PREPARED -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-prepared")
                digest != journal.oldNotesDigest -> DiskState.Corrupt("prepared-but-notes-changed")
                !disk.commitMaterialExists(journal.txnId) -> DiskState.StagingFailed(journal.txnId)
                else -> DiskState.Prepared(journal.txnId)
            }
        }
    }
}
