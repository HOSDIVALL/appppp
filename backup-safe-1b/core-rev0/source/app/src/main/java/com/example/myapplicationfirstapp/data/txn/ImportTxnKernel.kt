package com.example.myapplicationfirstapp.data.txn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * BACKUP-SAFE-1B-CORE：隔离事务内核。
 *
 * 只接受两样东西：**调用方指定的存储根**与**已经物化好的材料**（`txn-<txnId>/commit.json`
 * 与 `new-attachments/`）。它不读 Context、不解析 Uri、不补 ID/时间戳、不碰 UI，
 * 因此在测试里可以把根指到一个 UUID 沙箱，跑完整的提交/回滚/清理。
 *
 * ## 并发契约
 *
 * 同一个内核实例（同一个存储根）的所有写操作都由 [mutex] 串行：`commit` / `recover` /
 * `cleanup` / `rollback` / `discardQuarantined` / `materialize`。`inspect` 也拿同一把锁，
 * 所以它不会读到「清理删到一半」的中间态。跨实例共享同一个根**不受保护**——
 * 生产上只应该有一个实例（后批的 `NotesStore`），测试里每个沙箱一个实例。
 *
 * `WriteGate` 是第二层：写权限（epoch/sequence）与「同根串行」是两件事，
 * 前者决定「这次能不能写」，后者决定「两次写不会交错」。
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

    private val notesFile = File(root, NOTES)
    private val attachmentsDir = File(root, ATTACHMENTS)
    private val importDir = File(root, IMPORT_DIR)
    private val journalFile = File(importDir, JOURNAL)
    private val credentialFile = File(importDir, CREDENTIAL)
    private val resultsFile = File(importDir, RESULTS)

    // ================= 只读判定 =================

    suspend fun inspect(): DiskState = mutex.withLock {
        withContext(io) { classifyLocked() }
    }

    /**
     * 从磁盘重建状态。只看磁盘，不看内存、不看上一次返回值。
     *
     * 判定顺序：先判提交事实（证据 + 摘要），再判事务阶段，最后判材料是否足以支撑动作。
     * 材料够不够由 `rollback` 在推进每个文件时判定，这里不预判。
     */
    private fun classifyLocked(): DiskState {
        val txnDirs = txnDirs()
        return when (val journal = readJournal()) {
            JournalRead.Unreadable -> {
                // journal 存在但读不出来：只有凭据能救，且必须与正式数据对得上
                val credential = readCredential()
                if (credential is CredentialRead.Present && notesDigest() == credential.value.commitDigest) {
                    DiskState.Committed(credential.value.txnId)
                } else {
                    DiskState.Corrupt("journal-unreadable")
                }
            }

            JournalRead.Absent -> when (val credential = readCredential()) {
                CredentialRead.Unreadable -> DiskState.Corrupt("credential-unreadable")
                is CredentialRead.Present ->
                    if (notesDigest() == credential.value.commitDigest) {
                        DiskState.Committed(credential.value.txnId)
                    } else {
                        DiskState.Corrupt("credential-digest-mismatch")
                    }

                // 没有 journal 也没有凭据：只要还剩事务材料，生命周期就无法确定。
                // 不按 notes 摘要推断「可以删」——摘要相同不代表附件没被动过。
                CredentialRead.Absent ->
                    if (txnDirs.isEmpty()) DiskState.Idle else DiskState.Quarantined(txnDirs.map { it.name })
            }

            is JournalRead.Present -> judge(journal.value, readCredential())
        }
    }

    private fun judge(journal: Journal, credential: CredentialRead): DiskState {
        val digest = notesDigest()
        return when (journal.state) {
            JournalState.COMMITTED -> when {
                credential is CredentialRead.Present &&
                    (credential.value.txnId != journal.txnId || credential.value.commitDigest != journal.commitDigest) ->
                    DiskState.Corrupt("credential-journal-conflict")

                digest != journal.commitDigest -> DiskState.Corrupt("notes-digest-mismatch")
                else -> DiskState.Committed(journal.txnId)
            }

            JournalState.APPLYING -> when {
                // 终态证据成立 → 已提交
                credential is CredentialRead.Present && credential.value.txnId == journal.txnId &&
                    credential.value.commitDigest == journal.commitDigest && digest == journal.commitDigest ->
                    DiskState.Committed(journal.txnId)

                credential is CredentialRead.Present -> DiskState.Corrupt("credential-inconsistent")
                // 没有终态证据：无论 notes 是否已经等于新载荷，都只能回滚
                else -> DiskState.Interrupted(journal.txnId)
            }

            JournalState.ROLLING_BACK -> when {
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-rolling-back")
                // 上一次回滚失败过：仍然可以重跑同一套状态机，只是先如实报告失败位置
                journal.failedAt != null -> DiskState.RollbackFailed(journal.txnId, journal.failedAt)
                else -> DiskState.RollingBack(journal.txnId)
            }

            JournalState.ABORTED -> when {
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-aborted")
                digest == journal.oldNotesDigest -> DiskState.Aborted(journal.txnId)
                else -> DiskState.Corrupt("aborted-but-notes-changed")
            }

            JournalState.PREPARED -> when {
                credential is CredentialRead.Present -> DiskState.Corrupt("credential-on-prepared")
                digest != journal.oldNotesDigest -> DiskState.Corrupt("prepared-but-notes-changed")
                !commitMaterialExists(journal.txnId) -> DiskState.StagingFailed(journal.txnId)
                else -> DiskState.Prepared(journal.txnId)
            }
        }
    }

    // ================= 材料落地 =================

    /**
     * 把调用方已经算好的载荷与附件字节落成事务材料。
     *
     * 只做「写文件 + 算摘要」，不补默认值、不解析笔记；返回的 `commitDigest` 必须被调用方
     * 原样带走，提交时再用 [MaterialRef.expectedCommitDigest] 校验，防止材料被改。
     */
    suspend fun materialize(
        txnId: String,
        commitBytes: ByteArray,
        attachments: Map<String, ByteArray>
    ): MaterializeOutcome = mutex.withLock {
        withContext(io) {
            if (!isSafeName(txnId)) return@withContext MaterializeOutcome.Rejected("unsafe-txn-id")
            val dir = txnDir(txnId)
            if (ops.exists(dir)) return@withContext MaterializeOutcome.Rejected("txn-exists")
            if (!ops.mkdirs(dir)) return@withContext MaterializeOutcome.Rejected("mkdir-failed")
            attachments.keys.forEach { name ->
                if (!isSafeName(name)) {
                    ops.deleteTree(dir)
                    return@withContext MaterializeOutcome.Rejected("unsafe-name:$name")
                }
            }
            return@withContext try {
                ops.writeAtomic(File(dir, COMMIT_JSON), commitBytes)
                val newDir = File(dir, NEW_ATTACHMENTS)
                if (!ops.mkdirs(newDir)) throw IOException("cannot create new-attachments")
                attachments.forEach { (name, bytes) ->
                    ops.checkpoint(TxnStep.newAttachment(name))
                    ops.writeAtomic(File(newDir, name), bytes)
                }
                MaterializeOutcome.Ready(txnId, ops.digestBytes(commitBytes))
            } catch (failure: IOException) {
                ops.deleteTree(dir)
                MaterializeOutcome.Rejected("materialize-failed")
            }
        }
    }

    // ================= 提交 =================

    /**
     * 提交一份已物化材料。
     *
     * 失败语义：
     * - [CommitOutcome.NotStarted]：没动正式数据（材料缺失、材料变了、空间不足、已有活跃事务）。
     * - [CommitOutcome.Refused]：写前被拒，同样没有 IO。
     * - [CommitOutcome.Failed]：动过但已回滚（或停在可判定的失败态），[detail] 只用于日志。
     *
     * 取消：可取消段内取消 → 回滚后**重抛同一个** `CancellationException`；
     * 不可取消段（notes rename + 凭据）内取消被延迟，事务按已提交处理，不抛取消。
     */
    suspend fun commit(ref: MaterialRef): CommitOutcome = mutex.withLock {
        withContext(io) {
            val admission = gate.beginExclusive()
            if (admission is WriteGate.Admission.Refused) {
                return@withContext CommitOutcome.Refused(admission.refusal)
            }
            val ticket = (admission as WriteGate.Admission.Granted).ticket
            try {
                commitUnderLock(ref)
            } finally {
                gate.endExclusive(ticket)
            }
        }
    }

    private suspend fun commitUnderLock(ref: MaterialRef): CommitOutcome {
        val txnId = ref.txnId
        if (!isSafeName(txnId)) return CommitOutcome.NotStarted("unsafe-txn-id")

        when (val journal = readJournal()) {
            is JournalRead.Present -> return CommitOutcome.NotStarted("journal-present:${journal.value.state}")
            JournalRead.Unreadable -> return CommitOutcome.NotStarted("journal-unreadable")
            JournalRead.Absent -> Unit
        }
        when (readCredential()) {
            CredentialRead.Absent -> Unit
            else -> return CommitOutcome.NotStarted("credential-present")
        }
        val others = txnDirs().filter { it.name != txnDirName(txnId) }
        if (others.isNotEmpty()) {
            // 生命周期不明的事务必须先把处置掉，不能越过它开始新事务
            return CommitOutcome.NotStarted("quarantined:${others.joinToString(",") { it.name }}")
        }

        val dir = txnDir(txnId)
        if (!ops.isDirectory(dir)) return CommitOutcome.NotStarted("material-missing")
        val commitBytes = readOrNull(File(dir, COMMIT_JSON)) ?: return CommitOutcome.NotStarted("material-missing")
        val commitDigest = ops.digestBytes(commitBytes)
        if (ref.expectedCommitDigest != null && ref.expectedCommitDigest != commitDigest) {
            return CommitOutcome.NotStarted("material-changed")
        }

        val notesRead = readNotes()
        if (notesRead is NotesRead.Unreadable) return CommitOutcome.NotStarted("notes-unreadable")
        val oldNotesBytes = (notesRead as? NotesRead.Present)?.bytes
        val oldNotesDigest = oldNotesBytes?.let { ops.digestBytes(it) }

        val newFiles = ops.list(File(dir, NEW_ATTACHMENTS)).filter { !it.isDirectory }
        newFiles.forEach {
            if (!isSafeName(it.name)) return CommitOutcome.NotStarted("unsafe-material-name:${it.name}")
        }
        val entries = ArrayList<JournalAttachment>(newFiles.size)
        var requiredBytes = commitBytes.size.toLong() + (oldNotesBytes?.size?.toLong() ?: 0L)
        for (file in newFiles) {
            val newDigest = ops.digest(file)
            val target = File(attachmentsDir, file.name)
            val oldDigest = if (ops.exists(target)) ops.digest(target) else null
            entries += JournalAttachment(file.name, oldDigest, newDigest)
            requiredBytes += ops.length(file) + (if (oldDigest != null) ops.length(target) else 0L)
        }
        if (ops.usableSpace(root) < requiredBytes) return CommitOutcome.NotStarted("insufficient-space")

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
                ops.writeAtomic(File(dir, OLD_NOTES), oldNotesBytes)
            }
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (entry.old == null) continue
                ops.checkpoint(TxnStep.oldAttachment(entry.name))
                val source = File(attachmentsDir, entry.name)
                ops.writeAtomic(File(dir, "$OLD_ATTACHMENTS/${entry.name}"), ops.readBytes(source))
            }
            ops.checkpoint(TxnStep.OLD_MANIFEST)
            ops.writeAtomic(File(dir, OLD_MANIFEST), oldManifest(oldNotesDigest, entries).toByteArray())
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        // 可取消段：附件替换。取消到这里为止都必须回滚。
        try {
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val bytes = ops.readBytes(File(dir, "$NEW_ATTACHMENTS/${entry.name}"))
                ops.checkpoint(TxnStep.newAttachment(entry.name))
                ops.writeAtomic(File(attachmentsDir, entry.name), bytes)
            }
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { rollbackLocked(txnId) }
            throw cancellation
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        // 提交点：这两步一旦开始就必须走完，否则会停在「数据换了但没凭据」的不可判状态。
        // 注意 `withContext(NonCancellable)` 退出时仍可能收到**延迟**的取消：那时凭据已经写了，
        // 事务是已提交，绝不能回滚，只能把取消标记下来继续收尾。
        try {
            withContext(NonCancellable) {
                ops.checkpoint(TxnStep.NOTES)
                ops.writeAtomic(notesFile, commitBytes)
                ops.checkpoint(TxnStep.CREDENTIAL)
                ops.writeAtomic(
                    credentialFile,
                    TxnCodec.encodeCredential(CommitCredential(txnId, commitDigest, clock())).toByteArray()
                )
            }
        } catch (cancellation: CancellationException) {
            // 提交点之后才到的取消：磁盘已经提交，**不能回滚**。
            // 这里不把它转成返回值——取消语义由 Job 保留，调用方会在下一个挂起点收到；
            // 收到之后必须查磁盘（currentResult/inspect）而不是按失败处理。
        } catch (failure: IOException) {
            return settleAfterFailure(txnId, failure)
        }

        val count = countNotes(commitBytes)
        appendResult(ResultRecord(txnId, ResultOutcome.COMMITTED, count, clock()))
        runCatching {
            writeJournal(journal.copy(state = JournalState.COMMITTED))
        }
        val cleanup = cleanupLocked(txnId)
        return CommitOutcome.Committed(count, cleanup)
    }

    /** 提交中途失败：回滚到旧状态并清理；回滚失败则停在 `RollbackFailed` 并保留材料 */
    private suspend fun settleAfterFailure(txnId: String, failure: IOException): CommitOutcome {
        val rollback = withContext(NonCancellable) { rollbackLocked(txnId) }
        return when (rollback) {
            RollbackOutcome.Restored -> {
                cleanupLocked(txnId)
                CommitOutcome.Failed(classifyLocked(), failure.message ?: "apply-failed")
            }

            is RollbackOutcome.Failed -> CommitOutcome.Failed(
                DiskState.RollbackFailed(txnId, rollback.detail),
                failure.message ?: "apply-failed"
            )
        }
    }

    // ================= 回滚 =================

    /**
     * 显式回滚。只对 `APPLYING` / `ROLLING_BACK` 有效，已提交的事务一律拒绝。
     *
     * 每个文件单独比较「当前状态」与「目标状态」：摘要相同就跳过，不同才从副本恢复，
     * 恢复后再校验一次。因此重复中断任意次都能收敛。
     */
    suspend fun rollback(txnId: String): RollbackOutcome = mutex.withLock {
        withContext(io) { rollbackLocked(txnId) }
    }

    private fun rollbackLocked(txnId: String): RollbackOutcome {
        val journal = (readJournal() as? JournalRead.Present)?.value
            ?: return RollbackOutcome.Failed("journal-unreadable")
        if (journal.txnId != txnId) return RollbackOutcome.Failed("txn-mismatch")
        if (journal.state == JournalState.COMMITTED) return RollbackOutcome.Failed("refuse-rollback-committed")

        var current = journal.copy(state = JournalState.ROLLING_BACK)
        runCatching { writeJournal(current) }

        val nowDigest = notesDigest()
        if (current.oldNotesDigest != null) {
            // 目标：notes.json 的字节 == Dold。当前已经等于 Dold 时不需要副本。
            if (nowDigest != current.oldNotesDigest) {
                val copy = readOrNull(File(txnDir(txnId), OLD_NOTES))
                    ?: return failRollback(current, "old-notes-missing")
                if (ops.digestBytes(copy) != current.oldNotesDigest) {
                    return failRollback(current, "old-notes-corrupt")
                }
                if (!attempt {
                        ops.checkpoint(TxnStep.NOTES)
                        ops.writeAtomic(notesFile, copy)
                    }
                ) {
                    return failRollback(current, "notes-restore-io")
                }
                if (notesDigest() != current.oldNotesDigest) {
                    return failRollback(current, "notes-restore-verify")
                }
            }
        } else if (nowDigest != null) {
            // 目标：文件不存在。只删「自己写的」那一份。
            if (nowDigest != current.commitDigest) return failRollback(current, "notes-not-from-txn")
            if (!attempt {
                    ops.checkpoint(TxnStep.NOTES)
                    ops.delete(notesFile)
                }
            ) return failRollback(current, "notes-delete-io")
            if (notesDigest() != null) return failRollback(current, "notes-delete-verify")
        }

        for (entry in current.attachments) {
            val target = File(attachmentsDir, entry.name)
            val onDisk = readOrNull(target)?.let { ops.digestBytes(it) }
            if (entry.old == null) {
                if (onDisk == null) continue
                if (onDisk != entry.new) return failRollback(current, "unexpected-attachment:${entry.name}")
                if (!attempt {
                        ops.checkpoint(TxnStep.deleteMaterial("$ATTACHMENTS/${entry.name}"))
                        ops.delete(target)
                    }
                ) {
                    return failRollback(current, "attachment-delete-io")
                }
                if (ops.exists(target)) return failRollback(current, "attachment-delete-verify")
            } else {
                if (onDisk == entry.old) continue
                val copy = readOrNull(File(txnDir(txnId), "$OLD_ATTACHMENTS/${entry.name}"))
                    ?: return failRollback(current, "old-attachment-missing:${entry.name}")
                if (ops.digestBytes(copy) != entry.old) {
                    return failRollback(current, "old-attachment-corrupt:${entry.name}")
                }
                if (!attempt {
                        ops.checkpoint(TxnStep.oldAttachment(entry.name))
                        ops.writeAtomic(target, copy)
                    }
                ) {
                    return failRollback(current, "attachment-restore-io")
                }
                if (readOrNull(target)?.let { ops.digestBytes(it) } != entry.old) {
                    return failRollback(current, "attachment-restore-verify")
                }
            }
        }

        current = current.copy(state = JournalState.ABORTED, failedAt = null)
        runCatching { writeJournal(current) }
        appendResult(ResultRecord(txnId, ResultOutcome.ABORTED, 0, clock(), reason = "rolled-back"))
        return RollbackOutcome.Restored
    }

    private fun failRollback(journal: Journal, detail: String): RollbackOutcome {
        runCatching {
            writeJournal(journal.copy(state = JournalState.ROLLING_BACK, failedAt = detail))
        }
        return RollbackOutcome.Failed(detail)
    }

    // ================= 清理 =================

    /**
     * 清理事务件。只删 `.import/` 下的东西，绝不动正式数据。
     *
     * 顺序固定：结果留存 → 材料 → 目录 → journal → 凭据。
     * **材料没删干净就不删证据**：否则会退化成「有材料、没凭据」的隔离态。
     */
    suspend fun cleanup(txnId: String): CleanupOutcome = mutex.withLock {
        withContext(io) { cleanupLocked(txnId) }
    }

    private fun cleanupLocked(txnId: String): CleanupOutcome {
        val state = classifyLocked()
        val outcome = when (state) {
            is DiskState.Committed -> ResultOutcome.COMMITTED
            is DiskState.Aborted -> ResultOutcome.ABORTED
            is DiskState.StagingFailed -> ResultOutcome.NOT_STARTED
            // 其他状态没有「已终结」的事实可清理：停手，保留现场
            else -> return CleanupOutcome(false, listOf("state:${state::class.simpleName}"), stoppedAt = "state")
        }

        if (currentResultLocked(txnId) == null && !appendResult(ResultRecord(txnId, outcome, 0, clock()))) {
            return CleanupOutcome(false, listOf(RESULTS), stoppedAt = "results")
        }

        val leftovers = mutableListOf<String>()
        val dir = txnDir(txnId)
        if (ops.isDirectory(dir)) {
            for (child in listOf(OLD_ATTACHMENTS, NEW_ATTACHMENTS)) {
                val childDir = File(dir, child)
                for (file in ops.list(childDir).filter { !it.isDirectory }) {
                    if (!attempt {
                            ops.checkpoint(TxnStep.deleteMaterial("$child/${file.name}"))
                            ops.delete(file)
                        }
                    ) {
                        leftovers += "$child/${file.name}"
                        return CleanupOutcome(false, leftovers, stoppedAt = "materials")
                    }
                }
                if (ops.exists(childDir)) {
                    if (!attempt { ops.deleteTree(childDir) }) {
                        leftovers += child
                        return CleanupOutcome(false, leftovers, stoppedAt = "materials")
                    }
                }
            }
            for (name in listOf(COMMIT_JSON, OLD_NOTES, OLD_MANIFEST)) {
                val file = File(dir, name)
                if (!ops.exists(file)) continue
                if (!attempt {
                        ops.checkpoint(TxnStep.deleteMaterial(name))
                        ops.delete(file)
                    }
                ) {
                    leftovers += name
                    return CleanupOutcome(false, leftovers, stoppedAt = "materials")
                }
            }
            if (!attempt { ops.deleteTree(dir) }) {
                leftovers += dir.name
                return CleanupOutcome(false, leftovers, stoppedAt = "materials")
            }
        }

        // 门禁：材料目录必须已经不在了，才允许删证据
        if (ops.exists(dir)) return CleanupOutcome(false, leftovers + dir.name, stoppedAt = "gate")

        if (ops.exists(journalFile)) {
            if (!attempt {
                    ops.checkpoint(TxnStep.JOURNAL)
                    ops.delete(journalFile)
                }
            ) {
                return CleanupOutcome(false, leftovers + JOURNAL, stoppedAt = "journal")
            }
        }
        if (ops.exists(credentialFile)) {
            if (!attempt {
                    ops.checkpoint(TxnStep.CREDENTIAL)
                    ops.delete(credentialFile)
                }
            ) {
                return CleanupOutcome(false, leftovers + CREDENTIAL, stoppedAt = "credential")
            }
        }
        return CleanupOutcome(true, leftovers)
    }

    /**
     * 处置一个生命周期不明的 `txn-*`。**只能显式调用**，`recover()` 永不自动删。
     */
    suspend fun discardQuarantined(txnId: String): CleanupOutcome = mutex.withLock {
        withContext(io) {
            val state = classifyLocked()
            if (state !is DiskState.Quarantined || state.txnDirs.none { it == txnDirName(txnId) }) {
                return@withContext CleanupOutcome(false, listOf("not-quarantined"), stoppedAt = "state")
            }
            val dir = txnDir(txnId)
            ops.checkpoint(TxnStep.deleteMaterial(dir.name))
            val cleared = attempt { ops.deleteTree(dir) } && !ops.exists(dir)
            CleanupOutcome(cleared, if (cleared) emptyList() else listOf(dir.name), if (cleared) null else "quarantine")
        }
    }

    // ================= 恢复 =================

    /**
     * 启动恢复。按 `classify()` 的结论收尾：
     *
     * - `Interrupted` / `RollingBack` → 回滚 → 清理；
     * - `Aborted` / `StagingFailed` → 清理；
     * - `Committed` → 补结果与终态 → 清理；
     * - `Quarantined` / `Corrupt` / `RollbackFailed` / `Prepared` → 中止并保留现场。
     */
    suspend fun recover(): RecoverOutcome = mutex.withLock {
        withContext(io) {
            gate.enter(WriteGate.Mode.RECOVERING)
            try {
                when (val state = classifyLocked()) {
                    DiskState.Idle -> RecoverOutcome.Settled(DiskState.Idle, null)
                    is DiskState.Prepared -> RecoverOutcome.Stopped(state, "awaiting-confirmation")
                    is DiskState.Quarantined -> RecoverOutcome.Stopped(state, "lifecycle-unknown")
                    is DiskState.Corrupt -> RecoverOutcome.Stopped(state, state.reason)
                    // 回滚失败不是终态：每次启动都重跑同一状态机，直到收敛或再次失败
                    is DiskState.RollbackFailed -> settleRollback(state.txnId)

                    is DiskState.StagingFailed -> {
                        appendResult(ResultRecord(state.txnId, ResultOutcome.NOT_STARTED, 0, clock()))
                        val cleanup = cleanupLocked(state.txnId)
                        RecoverOutcome.Settled(classifyLocked(), cleanup)
                    }

                    is DiskState.Interrupted -> settleRollback(state.txnId)
                    is DiskState.RollingBack -> settleRollback(state.txnId)

                    is DiskState.Aborted -> {
                        val cleanup = cleanupLocked(state.txnId)
                        RecoverOutcome.Settled(classifyLocked(), cleanup)
                    }

                    is DiskState.Committed -> {
                        appendResultIfMissing(state.txnId, ResultOutcome.COMMITTED)
                        rewriteCommittedJournal(state.txnId)
                        val cleanup = cleanupLocked(state.txnId)
                        RecoverOutcome.Settled(classifyLocked(), cleanup)
                    }
                }
            } finally {
                gate.enter(WriteGate.Mode.OPEN)
            }
        }
    }

    private fun settleRollback(txnId: String): RecoverOutcome = when (val rollback = rollbackLocked(txnId)) {
        RollbackOutcome.Restored -> {
            val cleanup = cleanupLocked(txnId)
            RecoverOutcome.Settled(classifyLocked(), cleanup)
        }

        is RollbackOutcome.Failed -> RecoverOutcome.Stopped(classifyLocked(), rollback.detail)
    }

    // ================= 结果留存 =================

    suspend fun currentResult(txnId: String): ResultRecord? = mutex.withLock {
        withContext(io) { currentResultLocked(txnId) }
    }

    suspend fun results(): List<ResultRecord> = mutex.withLock {
        withContext(io) { readResultsLocked() }
    }

    suspend fun markConsumed(txnId: String): Boolean = mutex.withLock {
        withContext(io) {
            val records = readResultsLocked()
            if (records.none { it.txnId == txnId }) return@withContext false
            val updated = records.map { if (it.txnId == txnId) it.copy(consumed = true) else it }
            attempt { ops.writeAtomic(resultsFile, TxnCodec.encodeResults(updated).toByteArray()) }
        }
    }

    private fun currentResultLocked(txnId: String): ResultRecord? =
        readResultsLocked().firstOrNull { it.txnId == txnId }

    private fun readResultsLocked(): List<ResultRecord> {
        if (!ops.exists(resultsFile)) return emptyList()
        val text = readOrNull(resultsFile) ?: return emptyList()
        return TxnCodec.decodeResults(String(text, Charsets.UTF_8)) ?: emptyList()
    }

    /**
     * 追加一条终态记录。同一 `txnId` 覆盖，最多保留 [MAX_RESULTS] 条。
     *
     * 写失败不算导入失败：结果留存是「方便查询」，事实仍在磁盘上。
     */
    private fun appendResult(record: ResultRecord): Boolean {
        val existing = readResultsLocked().filter { it.txnId != record.txnId }
        val merged = (listOf(record) + existing).take(MAX_RESULTS)
        return attempt {
            ops.checkpoint(TxnStep.RESULTS)
            ops.writeAtomic(resultsFile, TxnCodec.encodeResults(merged).toByteArray())
        }
    }

    private fun appendResultIfMissing(txnId: String, outcome: ResultOutcome) {
        if (currentResultLocked(txnId) == null) {
            appendResult(ResultRecord(txnId, outcome, 0, clock()))
        }
    }

    private fun rewriteCommittedJournal(txnId: String) {
        val journal = (readJournal() as? JournalRead.Present)?.value ?: return
        if (journal.state == JournalState.COMMITTED) return
        runCatching { writeJournal(journal.copy(state = JournalState.COMMITTED, failedAt = null)) }
    }

    // ================= 磁盘读取 =================

    private sealed interface JournalRead {
        data object Absent : JournalRead
        data object Unreadable : JournalRead
        data class Present(val value: Journal) : JournalRead
    }

    private sealed interface CredentialRead {
        data object Absent : CredentialRead
        data object Unreadable : CredentialRead
        data class Present(val value: CommitCredential) : CredentialRead
    }

    private sealed interface NotesRead {
        data object Absent : NotesRead
        data object Unreadable : NotesRead
        data class Present(val bytes: ByteArray) : NotesRead
    }

    private fun readJournal(): JournalRead {
        if (!ops.exists(journalFile)) return JournalRead.Absent
        val bytes = readOrNull(journalFile) ?: return JournalRead.Unreadable
        val journal = TxnCodec.decodeJournal(String(bytes, Charsets.UTF_8)) ?: return JournalRead.Unreadable
        return JournalRead.Present(journal)
    }

    private fun readCredential(): CredentialRead {
        if (!ops.exists(credentialFile)) return CredentialRead.Absent
        val bytes = readOrNull(credentialFile) ?: return CredentialRead.Unreadable
        val credential = TxnCodec.decodeCredential(String(bytes, Charsets.UTF_8)) ?: return CredentialRead.Unreadable
        return CredentialRead.Present(credential)
    }

    private fun readNotes(): NotesRead {
        if (!ops.exists(notesFile)) return NotesRead.Absent
        val bytes = readOrNull(notesFile) ?: return NotesRead.Unreadable
        return NotesRead.Present(bytes)
    }

    private fun notesDigest(): String? = readOrNull(notesFile)?.let { ops.digestBytes(it) }

    private fun readOrNull(file: File): ByteArray? = try {
        if (ops.exists(file) && !ops.isDirectory(file)) ops.readBytes(file) else null
    } catch (failure: IOException) {
        null
    }

    private fun writeJournal(journal: Journal) {
        ops.checkpoint(TxnStep.JOURNAL)
        ops.writeAtomic(journalFile, TxnCodec.encodeJournal(journal).toByteArray())
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (failure: IOException) {
        false
    }

    private fun txnDirs(): List<File> = ops.list(importDir).filter { it.isDirectory && it.name.startsWith(TXN_PREFIX) }

    private fun txnDir(txnId: String): File = File(importDir, txnDirName(txnId))

    private fun txnDirName(txnId: String): String = "$TXN_PREFIX$txnId"

    private fun commitMaterialExists(txnId: String): Boolean =
        ops.exists(File(txnDir(txnId), COMMIT_JSON))

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name == File(name).name && '/' !in name && '\\' !in name && name != "." && name != ".."

    private fun countNotes(commitBytes: ByteArray): Int = runCatching {
        JSONArray(String(commitBytes, Charsets.UTF_8)).length()
    }.getOrDefault(0)

    private fun oldManifest(oldNotesDigest: String?, entries: List<JournalAttachment>): String {
        val attachments = JSONArray()
        entries.forEach { entry ->
            attachments.put(JSONObject().put("name", entry.name).put("old", entry.old ?: JSONObject.NULL))
        }
        val notes = JSONObject().put("present", oldNotesDigest != null)
        if (oldNotesDigest != null) notes.put("digest", oldNotesDigest)
        return JSONObject()
            .put("version", 1)
            .put("notes", notes)
            .put("attachments", attachments)
            .toString()
    }

    private companion object {
        const val NOTES = "notes.json"
        const val ATTACHMENTS = "attachments"
        const val IMPORT_DIR = ".import"
        const val JOURNAL = "journal.json"
        const val CREDENTIAL = "committed.json"
        const val RESULTS = "results.json"
        const val TXN_PREFIX = "txn-"
        const val COMMIT_JSON = "commit.json"
        const val OLD_NOTES = "old-notes.json"
        const val OLD_MANIFEST = "old-manifest.json"
        const val OLD_ATTACHMENTS = "old-attachments"
        const val NEW_ATTACHMENTS = "new-attachments"

        /** 结果留存上限；超出按「最旧且已消费」优先淘汰 */
        const val MAX_RESULTS = 8
    }
}
