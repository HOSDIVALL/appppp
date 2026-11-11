package com.example.myapplicationfirstapp.data.txn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * 提交路径：材料落地 → 备份 → 替换 → 提交点 → 结果 → 终态 → 清理。
 *
 * 三条约束：
 *
 * - **备份先于替换**：`old-notes.json`、`old-attachments/`、`old-manifest.json` 全部完成后，
 *   才允许写第一个正式文件；副本是复制不是搬移。
 * - **取消由当前调用收尾（B5）**：备份段与替换段内取消，都先回滚再重抛同一个取消实例；
 *   不可取消段内取消是延迟投递，事务按已提交处理，不回滚。
 * - **失败语义**：`NotStarted` 没动正式数据、`Refused` 写前被拒、`Failed` 动过但已回滚或停在可判定状态。
 */
internal class ImportTxnCommitPath(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val disk: TxnDisk,
    private val classifier: TxnClassifier,
    private val rollback: ImportTxnRollbackPath,
    private val cleanup: ImportTxnCleanupPath,
    private val ledger: TxnLedger,
    private val safeDelete: TxnSafeDelete,
    private val clock: () -> Long
) {

    // ---------------- 材料落地 ----------------

    suspend fun materialize(
        txnId: String,
        commitBytes: ByteArray,
        attachments: Map<String, ByteArray>
    ): MaterializeOutcome {
        layout.verify().let { verdict ->
            if (verdict is LayoutVerdict.Unsafe) {
                return MaterializeOutcome.Rejected("unsafe-layout:${verdict.reason}")
            }
        }
        if (!TxnLayout.isSafeName(txnId)) return MaterializeOutcome.Rejected("unsafe-txn-id")
        val dir = layout.txnDir(txnId)
        if (ops.exists(dir)) return MaterializeOutcome.Rejected("txn-exists")
        if (!ops.mkdirs(dir)) return MaterializeOutcome.Rejected("mkdir-failed")
        attachments.keys.forEach { name ->
            if (!TxnLayout.isSafeName(name)) {
                safeDelete.deleteVerified(dir, maxDepth = 2)
                return MaterializeOutcome.Rejected("unsafe-name:$name")
            }
        }
        return try {
            ops.writeAtomic(File(dir, TxnLayout.COMMIT_JSON), commitBytes)
            val newDir = File(dir, TxnLayout.NEW_ATTACHMENTS)
            if (!ops.mkdirs(newDir)) throw IOException("cannot create new-attachments")
            attachments.forEach { (name, bytes) ->
                ops.checkpoint(TxnStep.newAttachment(name))
                ops.writeAtomic(File(newDir, name), bytes)
            }
            MaterializeOutcome.Ready(txnId, ops.digestBytes(commitBytes))
        } catch (failure: IOException) {
            safeDelete.deleteVerified(dir, maxDepth = 2)
            MaterializeOutcome.Rejected("materialize-failed")
        }
    }

    // ---------------- 提交 ----------------

    suspend fun commitLocked(ref: MaterialRef): CommitOutcome {
        val txnId = ref.txnId
        layout.verify().let { verdict ->
            if (verdict is LayoutVerdict.Unsafe) {
                return CommitOutcome.NotStarted("unsafe-layout:${verdict.reason}")
            }
        }
        if (!TxnLayout.isSafeName(txnId)) return CommitOutcome.NotStarted("unsafe-txn-id")

        // 起始状态必须干净：journal、凭据、别的事务材料，任何一样都在，就不许越过。
        // 例外只有「本次要提交的材料目录」——它本来就该在，而且还没有 journal。
        when (val journalRead = disk.journal()) {
            JournalRead.Absent -> Unit
            JournalRead.Unreadable -> return CommitOutcome.NotStarted("journal-unreadable")
            is JournalRead.Present ->
                return CommitOutcome.NotStarted("journal-present:${journalRead.value.state}")
        }
        when (disk.credential()) {
            CredentialRead.Absent -> Unit
            CredentialRead.Unreadable -> return CommitOutcome.NotStarted("credential-unreadable")
            is CredentialRead.Present -> return CommitOutcome.NotStarted("credential-present")
        }
        val others = layout.txnDirs(ops).filter { it.name != layout.txnDirName(txnId) }
        if (others.isNotEmpty()) {
            return CommitOutcome.NotStarted("quarantined:${others.joinToString(",") { it.name }}")
        }

        val dir = layout.txnDir(txnId)
        if (!ops.isDirectory(dir)) return CommitOutcome.NotStarted("material-missing")
        val commitRead = disk.readFile(File(dir, TxnLayout.COMMIT_JSON))
        val commitBytes = when (commitRead) {
            FileRead.Absent -> return CommitOutcome.NotStarted("material-missing")
            FileRead.Unreadable -> return CommitOutcome.NotStarted("material-unreadable")
            is FileRead.Present -> commitRead.bytes
        }
        val commitDigest = ops.digestBytes(commitBytes)
        if (ref.expectedCommitDigest != null && ref.expectedCommitDigest != commitDigest) {
            return CommitOutcome.NotStarted("material-changed")
        }

        val oldNotesBytes = when (val notesRead = disk.notesRead()) {
            FileRead.Absent -> null
            FileRead.Unreadable -> return CommitOutcome.NotStarted("notes-unreadable")
            is FileRead.Present -> notesRead.bytes
        }
        val oldNotesDigest = oldNotesBytes?.let { ops.digestBytes(it) }

        val newFiles = try {
            ops.listStrict(File(dir, TxnLayout.NEW_ATTACHMENTS))
                .filter { ops.entryKind(it) == EntryKind.Regular }
        } catch (failure: IOException) {
            // 列不出来就不知道这次导入该带哪些附件：不许当成「没有附件」继续提交
            return CommitOutcome.NotStarted("material-unlistable")
        }
        newFiles.forEach {
            if (!TxnLayout.isSafeName(it.name)) {
                return CommitOutcome.NotStarted("unsafe-material-name:${it.name}")
            }
        }
        val entries = ArrayList<JournalAttachment>(newFiles.size)
        var requiredBytes = commitBytes.size.toLong() + (oldNotesBytes?.size?.toLong() ?: 0L)
        for (file in newFiles) {
            val newDigest = ops.digest(file)
            val target = layout.attachmentFile(file.name)
            val oldDigest = if (ops.exists(target)) ops.digest(target) else null
            entries += JournalAttachment(file.name, oldDigest, newDigest)
            requiredBytes += ops.length(file) + (if (oldDigest != null) ops.length(target) else 0L)
        }
        if (ops.usableSpace(layout.root) < requiredBytes) return CommitOutcome.NotStarted("insufficient-space")

        val journal = Journal(
            state = JournalState.APPLYING,
            txnId = txnId,
            commitDigest = commitDigest,
            oldNotesDigest = oldNotesDigest,
            attachments = entries
        )
        try {
            writeJournal(journal)
        } catch (failure: IOException) {
            return CommitOutcome.NotStarted("journal-write-failed")
        }

        // 备份：副本先落地，才允许改第一个正式文件
        try {
            if (oldNotesBytes != null) {
                ops.checkpoint(TxnStep.OLD_NOTES)
                ops.writeAtomic(layout.oldNotesFile(txnId), oldNotesBytes)
            }
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val old = entry.old ?: continue
                ops.checkpoint(TxnStep.oldAttachment(entry.name))
                ops.writeAtomic(
                    layout.oldAttachmentFile(txnId, entry.name),
                    ops.readBytes(layout.attachmentFile(entry.name))
                )
                if (ops.digestBytes(ops.readBytes(layout.oldAttachmentFile(txnId, entry.name))) != old) {
                    throw IOException("old-copy-mismatch:${entry.name}")
                }
            }
            ops.checkpoint(TxnStep.OLD_MANIFEST)
            ops.writeAtomic(
                layout.oldManifestFile(txnId),
                TxnCodec.encodeManifest(manifestOf(oldNotesDigest, entries)).toByteArray()
            )
        } catch (cancellation: CancellationException) {
            // B5：备份阶段取消同样由这次 commit 收尾，不留「等下次启动再回滚」
            withContext(NonCancellable) { rollback.rollbackLocked(txnId) }
            throw cancellation
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        // 可取消段：附件替换
        try {
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val bytes = ops.readBytes(layout.newAttachmentFile(txnId, entry.name))
                ops.checkpoint(TxnStep.newAttachment(entry.name))
                ops.writeAtomic(layout.attachmentFile(entry.name), bytes)
            }
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { rollback.rollbackLocked(txnId) }
            throw cancellation
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        // 提交点：一旦开始必须走完，否则会停在「数据换了但没凭据」的不可判状态。
        // `withContext(NonCancellable)` 退出时仍可能收到**延迟**的取消：那时凭据已经写了，
        // 事务已提交，绝不能回滚。
        try {
            withContext(NonCancellable) {
                ops.checkpoint(TxnStep.NOTES)
                ops.writeAtomic(layout.notesFile, commitBytes)
                ops.checkpoint(TxnStep.CREDENTIAL)
                ops.writeAtomic(
                    layout.credentialFile,
                    TxnCodec.encodeCredential(CommitCredential(txnId, commitDigest, clock())).toByteArray()
                )
            }
        } catch (cancellation: CancellationException) {
            // 提交点之后才到的取消：磁盘已经提交，不回滚。取消语义由 Job 保留，
            // 调用方收到后必须查磁盘（currentResult / inspect），不能按失败处理。
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        val count = countNotes(commitBytes)
        ledger.append(ResultRecord(txnId, ResultOutcome.COMMITTED, count, clock()))
        runCatching { writeJournal(journal.copy(state = JournalState.COMMITTED)) }
        val cleanupOutcome = cleanup.cleanupLocked(txnId)
        return CommitOutcome.Committed(count, cleanupOutcome)
    }

    /** 提交中途失败：回滚到旧状态并清理；回滚失败或不被授权则停在可判定状态并保留材料 */
    private suspend fun settleAfterFailure(txnId: String, failure: IOException): CommitOutcome {
        val rollbackOutcome = withContext(NonCancellable) { rollback.rollbackLocked(txnId) }
        val detail = failure.message ?: "apply-failed"
        return when (rollbackOutcome) {
            RollbackOutcome.Restored -> {
                cleanup.cleanupLocked(txnId)
                CommitOutcome.Failed(classifier.classify(), detail)
            }

            is RollbackOutcome.Failed ->
                CommitOutcome.Failed(classifier.classify(), "$detail|${rollbackOutcome.detail}")
        }
    }

    // ---------------- 辅助 ----------------

    private fun manifestOf(oldNotesDigest: String?, entries: List<JournalAttachment>): OldManifest =
        OldManifest(
            notesPresent = oldNotesDigest != null,
            notesDigest = oldNotesDigest,
            attachments = entries.map { ManifestEntry(it.name, it.old) }
        )

    private fun writeJournal(journal: Journal) {
        ops.checkpoint(TxnStep.JOURNAL)
        ops.writeAtomic(layout.journalFile, TxnCodec.encodeJournal(journal).toByteArray())
    }

    private fun countNotes(commitBytes: ByteArray): Int = runCatching {
        JSONArray(String(commitBytes, Charsets.UTF_8)).length()
    }.getOrDefault(0)
}
