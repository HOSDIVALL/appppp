package com.example.myapplicationfirstapp.data.txn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** 布局自检结论。`Unsafe` 一律表示「不许读、不许写、不许删」。 */
internal sealed interface LayoutVerdict {
    data object Ok : LayoutVerdict
    data class Unsafe(val reason: String) : LayoutVerdict
}

/**
 * 根身份与路径边界守卫（R3）。
 *
 * 两件事：
 *
 * 1. **根身份**：构造时记下根的规范路径，之后每次都要求仍然相同。根被换成指向别处的链接、
 *    或整目录被替换，都会在这里被拒。
 * 2. **布局内不得有预存链接**：从根往下逐层 `lstat`，任何一层是符号链接就整片判不可用。
 *    这一条挡住的是「删改跟着链接跑到根外」——`deleteRecursively()` 与 `listFiles()`
 *    都会顺着链接走。
 *
 * 明确不做的事：不防御并发换链（检查与使用之间被换掉），那不是这批的范围；也不试图
 * 解析挂载点或 `..` 逃逸——路径全部由 [TxnLayout] 拼装，外部字符串只经 `isSafeName`。
 *
 * 只用 `Os.lstat` 判断链接本身（API 21 起可用），不用 `File.exists()`：后者会跟随链接，
 * 断链会显示成「不存在」，正好把危险情况藏起来。
 */
internal class TxnPathGuard(private val root: File) {

    /** 首次成功自检时捕获；之后不一致即视为根被换掉 */
    private var identity: String? = null

    fun verify(): LayoutVerdict {
        if (!root.exists() && !isSymlink(root)) return LayoutVerdict.Ok
        if (isSymlink(root)) return LayoutVerdict.Unsafe("root-is-symlink")
        if (!root.isDirectory) return LayoutVerdict.Unsafe("root-not-directory")

        val canonical = canonicalOf(root)?.path ?: return LayoutVerdict.Unsafe("root-unresolvable")
        val known = identity ?: canonical.also { identity = it }
        if (canonical != known) return LayoutVerdict.Unsafe("root-identity-changed")

        checkPath(TxnLayout.NOTES)?.let { return it }
        checkPath(TxnLayout.ATTACHMENTS)?.let { return it }
        checkPath(TxnLayout.IMPORT_DIR)?.let { return it }
        scanImport()?.let { return it }
        return LayoutVerdict.Ok
    }

    /** `.import/` 及其下的两层：journal、凭据、结果、txn 目录与材料 */
    private fun scanImport(): LayoutVerdict.Unsafe? {
        val importDir = File(root, TxnLayout.IMPORT_DIR)
        if (!importDir.isDirectory) return null
        return scanChildren(importDir, TxnLayout.IMPORT_DIR, depth = 0)
    }

    private fun scanChildren(dir: File, prefix: String, depth: Int): LayoutVerdict.Unsafe? {
        if (depth > MAX_DEPTH) return null
        val children = dir.listFiles() ?: return null
        for (child in children) {
            val relative = "$prefix/${child.name}"
            checkPath(relative)?.let { return it }
            if (child.isDirectory && !isSymlink(child)) {
                scanChildren(child, relative, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 逐层核对：每一层都必须真的存在、不是链接、且规范父目录等于上一层。
     *
     * 第三种情况能抓住「中间层被换成指向别处的目录」——这时 `isSymlink` 只看最后一层不够。
     * 某一层不存在就说明后面的都不用看（本次是新建）。
     */
    private fun checkPath(relative: String): LayoutVerdict.Unsafe? {
        var current = root
        var expected = canonicalOf(root) ?: return LayoutVerdict.Unsafe("root-unresolvable")
        for (part in relative.split('/')) {
            if (part.isEmpty()) continue
            current = File(current, part)
            val linked = isSymlink(current)
            if (!linked && !current.exists()) return null
            if (linked) return LayoutVerdict.Unsafe("symlink:$relative")
            val canonical = canonicalOf(current)
                ?: return LayoutVerdict.Unsafe("unresolvable:$relative")
            if (canonical.parentFile != expected) {
                return LayoutVerdict.Unsafe("outside-root:$relative")
            }
            expected = canonical
        }
        return null
    }

    private fun canonicalOf(file: File): File? =
        runCatching { file.canonicalFile }.getOrNull()

    private fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (failure: ErrnoException) {
        false
    }

    private companion object {
        /** `txn-<id>/old-attachments/<name>` 已经在第 4 层，再多就是不属于布局的东西 */
        const val MAX_DEPTH = 4
    }
}
