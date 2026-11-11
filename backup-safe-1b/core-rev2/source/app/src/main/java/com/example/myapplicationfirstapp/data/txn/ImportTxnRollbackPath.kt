package com.example.myapplicationfirstapp.data.txn

import java.io.IOException

/**
 * 回滚状态机（B1 + B6）。
 *
 * ## 入口由磁盘分类授权（B1）
 *
 * 只有 `Interrupted` / `RollingBack` / `RollbackFailed` 三种状态能进回滚，而且状态里的 `txnId`
 * 必须等于参数。**不看 `journal.state` 的字样**：`APPLYING` 与凭据同时成立时事务事实上已提交，
 * 这时回滚必须被拒——否则一次字段误读就能把已提交的数据擦回旧版本。
 *
 * ## 每个文件单独判定（B6）
 *
 * 回滚的完成条件是「当前摘要 == 目标摘要」，不是「动作执行过」。每个文件只允许两种身份：
 * 旧内容或新内容。遇到既不是 `old` 也不是 `new` 的东西（外部改动、被删除、来路不明），
 * 一律停写并保留材料，绝不覆盖。需要动用副本时，先核对 `old-manifest.json` 与 `journal` 一致。
 *
 * 判定用的摘要保留 `Absent / Unreadable / Present` 三态（R2）：读不出来既不是「旧内容」，
 * 也不是「新内容」，更不是「本来就没有」。尤其是 `old == null` 的分支，若把读失败折叠成
 * 「不存在」，回滚会直接宣称完成、写 `ABORTED` 并把材料删掉——这正是 rev1 的问题。
 */
internal class ImportTxnRollbackPath(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val disk: TxnDisk,
    private val classifier: TxnClassifier,
    private val ledger: TxnLedger,
    private val clock: () -> Long
) {

    private enum class Action { SKIP, RESTORE, REMOVE }

    /** 计划阶段就发现不能回滚，用异常短路到统一的失败出口 */
    private class StopRollback(val detail: String) : Exception()

    fun rollbackLocked(txnId: String): RollbackOutcome {
        if (!TxnLayout.isSafeName(txnId)) return RollbackOutcome.Failed("unsafe-txn-id")

        val authorized = when (val state = classifier.classify()) {
            is DiskState.Interrupted -> state.txnId
            is DiskState.RollingBack -> state.txnId
            is DiskState.RollbackFailed -> state.txnId
            is DiskState.Committed -> return RollbackOutcome.Failed("refuse-rollback-committed")
            is DiskState.Aborted -> return RollbackOutcome.Failed("refuse-rollback-aborted")
            is DiskState.Prepared -> return RollbackOutcome.Failed("refuse-rollback-prepared")
            is DiskState.StagingFailed -> return RollbackOutcome.Failed("refuse-rollback-staging")
            is DiskState.Quarantined -> return RollbackOutcome.Failed("refuse-rollback-quarantined")
            is DiskState.Corrupt -> return RollbackOutcome.Failed("refuse-rollback-corrupt:${state.reason}")
            DiskState.Idle -> return RollbackOutcome.Failed("refuse-rollback-idle")
        }
        if (authorized != txnId) return RollbackOutcome.Failed("txn-mismatch")
        return execute(txnId)
    }

    private fun execute(txnId: String): RollbackOutcome {
        val journal = (disk.journal() as? JournalRead.Present)?.value
            ?: return RollbackOutcome.Failed("journal-unreadable")
        if (journal.txnId != txnId) return RollbackOutcome.Failed("txn-mismatch")
        if (journal.state == JournalState.COMMITTED) {
            return RollbackOutcome.Failed("refuse-rollback-committed")
        }

        var current = journal.copy(state = JournalState.ROLLING_BACK, failedAt = null)
        runCatching { writeJournal(current) }

        val notesAction: Action
        val attachmentActions: List<Pair<JournalAttachment, Action>>
        try {
            notesAction = planNotes(current)
            attachmentActions = current.attachments.map { it to planAttachment(it) }
            if (notesAction == Action.RESTORE || attachmentActions.any { it.second == Action.RESTORE }) {
                verifyManifest(current)
            }
        } catch (stop: StopRollback) {
            return failRollback(current, stop.detail)
        }

        if (notesAction != Action.SKIP) {
            val detail = when (notesAction) {
                Action.RESTORE -> restoreNotes(current)
                else -> removeNotes(current)
            }
            if (detail != null) return failRollback(current, detail)
        }

        for ((entry, action) in attachmentActions) {
            val detail = when (action) {
                Action.SKIP -> null
                Action.RESTORE -> restoreAttachment(current, entry)
                Action.REMOVE -> removeAttachment(entry)
            }
            if (detail != null) return failRollback(current, detail)
        }

        current = current.copy(state = JournalState.ABORTED, failedAt = null)
        runCatching { writeJournal(current) }
        ledger.append(ResultRecord(txnId, ResultOutcome.ABORTED, 0, clock(), reason = "rolled-back"))
        return RollbackOutcome.Restored
    }

    // ---------------- 计划 ----------------

    private fun planNotes(journal: Journal): Action {
        val now = disk.notesDigest()
        val old = journal.oldNotesDigest
        // 读不出来既不是「旧内容」也不是「新内容」，更不是「没有」：停手，别宣称收敛
        if (now is DigestRead.Unreadable) throw StopRollback("notes-unreadable")
        val current = now.digestOrNull

        return if (old != null) {
            when {
                current == old -> Action.SKIP
                current == journal.commitDigest -> Action.RESTORE
                current == null -> throw StopRollback("notes-missing")
                else -> throw StopRollback("notes-not-from-txn")
            }
        } else {
            when {
                now is DigestRead.Absent -> Action.SKIP
                current == journal.commitDigest -> Action.REMOVE
                else -> throw StopRollback("notes-not-from-txn")
            }
        }
    }

    private fun planAttachment(entry: JournalAttachment): Action {
        val onDisk = disk.digestOf(layout.attachmentFile(entry.name))
        if (onDisk is DigestRead.Unreadable) {
            throw StopRollback("attachment-unreadable:${entry.name}")
        }
        val current = onDisk.digestOrNull
        return if (entry.old != null) {
            when {
                current == entry.old -> Action.SKIP
                current == entry.new -> Action.RESTORE
                current == null -> throw StopRollback("attachment-missing:${entry.name}")
                else -> throw StopRollback("attachment-not-from-txn:${entry.name}")
            }
        } else {
            when {
                onDisk is DigestRead.Absent -> Action.SKIP
                current == entry.new -> Action.REMOVE
                else -> throw StopRollback("unexpected-attachment:${entry.name}")
            }
        }
    }

    /**
     * 副本能不能用，先看 manifest 与 journal 对不对得上。
     *
     * 只在**确实要用副本**时才要求它存在：全部文件都已经是目标状态时（重复中断的最后一跳），
     * 不应该被一份已经清理掉的 manifest 卡住。
     */
    private fun verifyManifest(journal: Journal) {
        val file = layout.oldManifestFile(journal.txnId)
        val text = when (val read = disk.readFile(file)) {
            FileRead.Absent -> throw StopRollback("old-manifest-missing")
            FileRead.Unreadable -> throw StopRollback("old-manifest-unreadable")
            is FileRead.Present -> String(read.bytes, Charsets.UTF_8)
        }
        val manifest = TxnCodec.decodeManifest(text) ?: throw StopRollback("old-manifest-unreadable")

        if (manifest.notesPresent != (journal.oldNotesDigest != null)) {
            throw StopRollback("old-manifest-mismatch")
        }
        if (manifest.notesDigest != journal.oldNotesDigest) {
            throw StopRollback("old-manifest-mismatch")
        }
        val expected = journal.attachments.map { it.name to it.old }.toSet()
        val actual = manifest.attachments.map { it.name to it.old }.toSet()
        if (expected != actual) throw StopRollback("old-manifest-mismatch")
    }

    // ---------------- 执行 ----------------

    private fun restoreNotes(journal: Journal): String? {
        val copy = when (val read = disk.readFile(layout.oldNotesFile(journal.txnId))) {
            FileRead.Absent -> return "old-notes-missing"
            FileRead.Unreadable -> return "old-notes-unreadable"
            is FileRead.Present -> read.bytes
        }
        if (ops.digestBytes(copy) != journal.oldNotesDigest) return "old-notes-corrupt"
        if (!attempt {
                ops.checkpoint(TxnStep.NOTES)
                ops.writeAtomic(layout.notesFile, copy)
            }
        ) {
            return "notes-restore-io"
        }
        return when (val after = disk.notesDigest()) {
            DigestRead.Absent -> "notes-restore-verify"
            DigestRead.Unreadable -> "notes-restore-verify-unreadable"
            is DigestRead.Present ->
                if (after.digest == journal.oldNotesDigest) null else "notes-restore-verify"
        }
    }

    private fun removeNotes(journal: Journal): String? {
        when (val before = disk.notesDigest()) {
            DigestRead.Unreadable -> return "notes-unreadable"
            DigestRead.Absent -> return "notes-not-from-txn"
            is DigestRead.Present ->
                if (before.digest != journal.commitDigest) return "notes-not-from-txn"
        }
        if (!attempt {
                ops.checkpoint(TxnStep.NOTES)
                ops.delete(layout.notesFile)
            }
        ) {
            return "notes-delete-io"
        }
        return when (disk.notesDigest()) {
            DigestRead.Absent -> null
            DigestRead.Unreadable -> "notes-delete-verify-unreadable"
            is DigestRead.Present -> "notes-delete-verify"
        }
    }

    private fun restoreAttachment(journal: Journal, entry: JournalAttachment): String? {
        val target = layout.attachmentFile(entry.name)
        val copy = when (val read = disk.readFile(layout.oldAttachmentFile(journal.txnId, entry.name))) {
            FileRead.Absent -> return "old-attachment-missing:${entry.name}"
            FileRead.Unreadable -> return "old-attachment-unreadable:${entry.name}"
            is FileRead.Present -> read.bytes
        }
        if (ops.digestBytes(copy) != entry.old) return "old-attachment-corrupt:${entry.name}"
        if (!attempt {
                ops.checkpoint(TxnStep.oldAttachment(entry.name))
                ops.writeAtomic(target, copy)
            }
        ) {
            return "attachment-restore-io"
        }
        return when (val after = disk.digestOf(target)) {
            is DigestRead.Present ->
                if (after.digest == entry.old) null else "attachment-restore-verify"

            DigestRead.Unreadable -> "attachment-restore-verify-unreadable"
            DigestRead.Absent -> "attachment-restore-verify"
        }
    }

    private fun removeAttachment(entry: JournalAttachment): String? {
        val target = layout.attachmentFile(entry.name)
        if (!attempt {
                ops.checkpoint(TxnStep.deleteMaterial("${TxnLayout.ATTACHMENTS}/${entry.name}"))
                ops.delete(target)
            }
        ) {
            return "attachment-delete-io"
        }
        if (ops.exists(target)) return "attachment-delete-verify"
        return null
    }

    // ---------------- 落盘 ----------------

    private fun failRollback(journal: Journal, detail: String): RollbackOutcome {
        runCatching {
            writeJournal(journal.copy(state = JournalState.ROLLING_BACK, failedAt = detail))
        }
        return RollbackOutcome.Failed(detail)
    }

    private fun writeJournal(journal: Journal) {
        ops.checkpoint(TxnStep.JOURNAL)
        ops.writeAtomic(layout.journalFile, TxnCodec.encodeJournal(journal).toByteArray())
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (failure: IOException) {
        false
    }
}
