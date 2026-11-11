package com.example.myapplicationfirstapp.data.txn

import java.io.File
import java.io.IOException

/**
 * 清理路径（B2 + R3）。只删 `.import/` 下的东西，绝不动正式数据。
 *
 * 顺序固定：**布局自检 → 核对 txnId → 结果留存 → 材料 → 目录 → journal → 凭据**。
 *
 * - 布局自检不过（根被换、布局里有符号链接）时一个字节都不动：`deleteRecursively()`
 *   会跟着链接把根外的目录删空。
 * - 参数与终态 `txnId` 对不上时，一个字节都不动。传错 ID 不该写结果、删材料，
 *   更不该删全局证据——那会把另一个事务的提交事实抹掉。
 * - 材料没删干净就不删证据：否则会退化成「有材料、没凭据」的隔离态。
 */
internal class ImportTxnCleanupPath(
    private val layout: TxnLayout,
    private val ops: TxnFileOps,
    private val classifier: TxnClassifier,
    private val ledger: TxnLedger
) {

    fun cleanupLocked(txnId: String): CleanupOutcome {
        layout.verify().let { verdict ->
            if (verdict is LayoutVerdict.Unsafe) {
                return CleanupOutcome(false, listOf("unsafe-layout:${verdict.reason}"), stoppedAt = "layout")
            }
        }
        if (!TxnLayout.isSafeName(txnId)) {
            return CleanupOutcome(false, listOf("unsafe-txn-id"), stoppedAt = "txn-id")
        }

        val state = classifier.classify()
        val (stateTxnId, result) = when (state) {
            is DiskState.Committed -> state.txnId to ResultOutcome.COMMITTED
            is DiskState.Aborted -> state.txnId to ResultOutcome.ABORTED
            is DiskState.StagingFailed -> state.txnId to ResultOutcome.NOT_STARTED
            // 其他状态没有「已终结」的事实可清理：停手，保留现场
            else -> return CleanupOutcome(
                false,
                listOf("state:${state::class.simpleName}"),
                stoppedAt = "state"
            )
        }
        if (stateTxnId != txnId) {
            return CleanupOutcome(false, listOf("state-txn:$stateTxnId"), stoppedAt = "txn-id")
        }

        if (!ledger.appendIfMissing(txnId, result)) {
            return CleanupOutcome(false, listOf(TxnLayout.RESULTS), stoppedAt = "results")
        }

        val leftovers = mutableListOf<String>()
        val dir = layout.txnDir(txnId)
        if (ops.isDirectory(dir)) {
            for (child in listOf(TxnLayout.OLD_ATTACHMENTS, TxnLayout.NEW_ATTACHMENTS)) {
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
                if (ops.exists(childDir) && !attempt { ops.deleteTree(childDir) }) {
                    leftovers += child
                    return CleanupOutcome(false, leftovers, stoppedAt = "materials")
                }
            }
            for (name in listOf(TxnLayout.COMMIT_JSON, TxnLayout.OLD_NOTES, TxnLayout.OLD_MANIFEST)) {
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

        if (ops.exists(layout.journalFile)) {
            if (!attempt {
                    ops.checkpoint(TxnStep.JOURNAL)
                    ops.delete(layout.journalFile)
                }
            ) {
                return CleanupOutcome(false, leftovers + TxnLayout.JOURNAL, stoppedAt = "journal")
            }
        }
        if (ops.exists(layout.credentialFile)) {
            if (!attempt {
                    ops.checkpoint(TxnStep.CREDENTIAL)
                    ops.delete(layout.credentialFile)
                }
            ) {
                return CleanupOutcome(false, leftovers + TxnLayout.CREDENTIAL, stoppedAt = "credential")
            }
        }
        return CleanupOutcome(true, leftovers)
    }

    /** 处置一个生命周期不明的 `txn-*`。**只能显式调用**，恢复流程永不自动删。 */
    fun discardLocked(txnId: String): CleanupOutcome {
        layout.verify().let { verdict ->
            if (verdict is LayoutVerdict.Unsafe) {
                return CleanupOutcome(false, listOf("unsafe-layout:${verdict.reason}"), stoppedAt = "layout")
            }
        }
        if (!TxnLayout.isSafeName(txnId)) {
            return CleanupOutcome(false, listOf("unsafe-txn-id"), stoppedAt = "txn-id")
        }
        val state = classifier.classify()
        val dirName = layout.txnDirName(txnId)
        if (state !is DiskState.Quarantined || state.txnDirs.none { it == dirName }) {
            return CleanupOutcome(false, listOf("not-quarantined"), stoppedAt = "state")
        }
        val dir = layout.txnDir(txnId)
        ops.checkpoint(TxnStep.deleteMaterial(dir.name))
        val cleared = attempt { ops.deleteTree(dir) } && !ops.exists(dir)
        return CleanupOutcome(
            cleared,
            if (cleared) emptyList() else listOf(dir.name),
            if (cleared) null else "quarantine"
        )
    }

    private fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (failure: IOException) {
        false
    }
}
