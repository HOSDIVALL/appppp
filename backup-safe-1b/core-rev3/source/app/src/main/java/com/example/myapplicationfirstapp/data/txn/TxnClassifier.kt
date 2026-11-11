package com.example.myapplicationfirstapp.data.txn

/**
 * 磁盘分类器。唯一职责：把「磁盘上现在是什么」翻成 [DiskState]，**不写任何文件**。
 *
 * 三条硬规则：
 *
 * 1. 布局自检不过（根被换、布局里有符号链接）一律 `Corrupt`：既不读也不写。
 * 2. 凭据不可读时**不能**当成「没有凭据」——那会把「可能已提交」误判成「可以回滚」。
 * 3. 判定用的摘要保留 `Absent / Unreadable / Present` 三态；读不出来是 `Corrupt`，
 *    不是「内容变了」。
 * 4. `APPLYING` 只有在「凭据完整匹配 + 摘要一致」时才算已提交，其余一律 `Interrupted`；
 *    不看 `notes.json` 是否已经等于新载荷。
 */
internal class TxnClassifier(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val disk: TxnDisk
) {

    fun classify(): DiskState {
        val verdict = layout.verify()
        if (verdict is LayoutVerdict.Unsafe) return DiskState.Corrupt("unsafe-layout:${verdict.reason}")
        return when (val journal = disk.journal()) {
            JournalRead.Unreadable -> rescueByCredential("journal-unreadable")
            JournalRead.Absent -> withoutJournal()
            is JournalRead.Present -> judge(journal.value, disk.credential())
        }
    }

    /** journal 读不出来时只剩凭据能救，而且必须与正式数据对得上 */
    private fun rescueByCredential(reason: String): DiskState =
        when (val credential = disk.credential()) {
            CredentialRead.Unreadable -> DiskState.Corrupt("journal-and-credential-unreadable")
            is CredentialRead.Present -> when (val notes = disk.notesDigest()) {
                DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                is DigestRead.Present ->
                    if (notes.digest == credential.value.commitDigest) {
                        DiskState.Committed(credential.value.txnId)
                    } else {
                        DiskState.Corrupt(reason)
                    }

                DigestRead.Absent -> DiskState.Corrupt(reason)
            }

            CredentialRead.Absent -> DiskState.Corrupt(reason)
        }

    private fun withoutJournal(): DiskState =
        when (val credential = disk.credential()) {
            CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
            is CredentialRead.Present -> when (val notes = disk.notesDigest()) {
                DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                is DigestRead.Present ->
                    if (notes.digest == credential.value.commitDigest) {
                        DiskState.Committed(credential.value.txnId)
                    } else {
                        DiskState.Corrupt("credential-digest-mismatch")
                    }

                DigestRead.Absent -> DiskState.Corrupt("credential-digest-mismatch")
            }

            // 没有 journal 也没有凭据：还剩事务材料就无法确定生命周期。
            // 不按 notes 摘要推断「可以删」——摘要相同不代表附件没被动过。
            CredentialRead.Absent -> {
                val dirs = layout.txnDirs(ops)
                if (dirs.isEmpty()) DiskState.Idle else DiskState.Quarantined(dirs.map { it.name })
            }
        }

    private fun judge(journal: Journal, credential: CredentialRead): DiskState {
        val notes = disk.notesDigest()
        return when (journal.state) {
            JournalState.COMMITTED -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present &&
                    (credential.value.txnId != journal.txnId ||
                        credential.value.commitDigest != journal.commitDigest) ->
                    DiskState.Corrupt("credential-journal-conflict")

                notes is DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                notes.digestOrNull != journal.commitDigest -> DiskState.Corrupt("notes-digest-mismatch")
                else -> DiskState.Committed(journal.txnId)
            }

            JournalState.APPLYING -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")

                credential is CredentialRead.Present &&
                    credential.value.txnId == journal.txnId &&
                    credential.value.commitDigest == journal.commitDigest -> when (notes) {
                    DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                    is DigestRead.Present ->
                        if (notes.digest == journal.commitDigest) {
                            DiskState.Committed(journal.txnId)
                        } else {
                            DiskState.Corrupt("credential-inconsistent")
                        }

                    DigestRead.Absent -> DiskState.Corrupt("credential-inconsistent")
                }

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
                notes is DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                notes.digestOrNull == journal.oldNotesDigest -> DiskState.Aborted(journal.txnId)
                else -> DiskState.Corrupt("aborted-but-notes-changed")
            }

            JournalState.PREPARED -> when {
                credential is CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-prepared")
                notes is DigestRead.Unreadable -> DiskState.Corrupt("notes-unreadable")
                notes.digestOrNull != journal.oldNotesDigest ->
                    DiskState.Corrupt("prepared-but-notes-changed")

                !disk.commitMaterialExists(journal.txnId) -> DiskState.StagingFailed(journal.txnId)
                else -> DiskState.Prepared(journal.txnId)
            }
        }
    }
}
