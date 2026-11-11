package com.example.myapplicationfirstapp.data.txn

import java.io.File
import java.io.IOException

/**
 * 有边界的删除（core-rev3）。
 *
 * 存在的唯一理由：`File.deleteRecursively()` 会顺着符号链接把根外的目录一起清空，
 * 而且它不看「这棵子树是不是布局里认得的结构」。这里改成逐层走：
 *
 * - 每一层都先用 [TxnFileOps.entryKind] 看**实际类型**，是链接就整片放弃；
 * - 枚举不出来（`IOException`）或类型判不出来（`Unknown`）同样放弃，不当空目录；
 * - 深度上限之外的东西**不删**：走到上限还没删完就返回 `false`，把「没验证过的子树」
 *   留在原地等人看，而不是猜着删。
 *
 * 调用方必须先让 [TxnPathGuard] 通过；本类只负责「按已验证的形状删」，不负责判断形状。
 * 失败时可能已经删掉了前面几个文件——所以调用方一律把 `false` 当成「没删干净」处理，
 * 不当作「什么都没发生」。
 */
internal class TxnSafeDelete(private val ops: TxnFileOps) {

    /**
     * @param maxDepth 允许往下走的层数：文件为 0，目录每进一层减 1。传 1 表示「目录 + 里面的
     *   普通文件」，传 2 表示「目录 + 子目录 + 文件」。
     */
    fun deleteVerified(target: File, maxDepth: Int): Boolean =
        when (val kind = ops.entryKind(target)) {
            EntryKind.Absent -> true
            EntryKind.Symlink -> false
            is EntryKind.Unknown -> false
            EntryKind.Regular -> ops.delete(target) && isGone(target)
            EntryKind.Directory -> deleteDirectory(target, maxDepth)
        }

    private fun deleteDirectory(dir: File, maxDepth: Int): Boolean {
        if (maxDepth <= 0) return false
        val children = try {
            ops.listStrict(dir)
        } catch (failure: IOException) {
            return false
        }
        for (child in children) {
            if (!deleteVerified(child, maxDepth - 1)) return false
        }
        // 只删空目录：不是 `rm -r`，删不掉说明里面还有没处理的东西
        return ops.delete(dir) && isGone(dir)
    }

    private fun isGone(file: File): Boolean = ops.entryKind(file) == EntryKind.Absent
}
