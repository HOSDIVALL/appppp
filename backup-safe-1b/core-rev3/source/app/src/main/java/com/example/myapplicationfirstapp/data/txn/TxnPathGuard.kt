package com.example.myapplicationfirstapp.data.txn

import java.io.File
import java.io.IOException

/** 布局自检结论。`Unsafe` 一律表示「不许读、不许写、不许删」。 */
internal sealed interface LayoutVerdict {
    data object Ok : LayoutVerdict
    data class Unsafe(val reason: String) : LayoutVerdict
}

/**
 * 根身份与路径边界守卫（R3 / core-rev3）。
 *
 * 三条规则，缺一不可：
 *
 * 1. **根身份**：首次自检时记下根的规范路径，之后每次都要相同。根被换成指向别处的链接、
 *    或整目录被替换，都在这里被拒。
 * 2. **形状即边界**：只认 `.import/` 下**已知的三层**——全局文件、`txn-<id>/`、材料目录里的
 *    平铺文件。多出来的东西一律 `Unknown`，不是「没看见」。这样「检查通过的范围」与
 *    「允许删除的范围」是同一个集合，不存在「有限检查、无限删除」。
 * 3. **检查不出来就停**：枚举失败（`IOException`）、`lstat` 报的不是 `ENOENT`/`ENOTDIR`
 *    （`Unknown`）、列完之后条目又不见了（`Absent`），都不是「空目录」也不是「不是链接」，
 *    而是 `Unsafe`。`TxnSafeDelete` 只删已经过这一关的路径。
 *
 * 明确不做的事：**不防御并发换链**（检查与使用之间被换掉）。这一条留到接入批，
 * 本批只保证「已经摆在盘上的东西」不会被误判成可删。
 */
internal class TxnPathGuard(private val root: File, private val ops: TxnFileOps) {

    /** 首次成功自检时捕获的规范路径；之后不一致即视为根被换掉 */
    private var identity: File? = null
    private var rootCanonical: File? = null

    fun verify(): LayoutVerdict = try {
        walk()
        LayoutVerdict.Ok
    } catch (signal: UnsafeSignal) {
        signal.verdict
    }

    // ---------------- 根与顶层 ----------------

    private fun walk() {
        when (val kind = ops.entryKind(root)) {
            EntryKind.Absent -> return
            EntryKind.Directory -> Unit
            EntryKind.Symlink -> fail("root-is-symlink")
            is EntryKind.Unknown -> fail("root-undecidable:${kind.reason}")
            EntryKind.Regular -> fail("root-not-directory")
        }

        val canonical = canonicalOf(root) ?: fail("root-unresolvable")
        val known = identity ?: canonical.also { identity = it }
        if (canonical != known) fail("root-identity-changed")
        rootCanonical = canonical

        // notes.json 的具体类型不在这里定：目录占位由 TxnDisk 判成 `Unreadable`，
        // 界面上是「读不出来」，不是「布局越界」。这里只管它不是链接、不是判不出来的东西。
        checkTopLevel(TxnLayout.NOTES, expected = null)
        checkTopLevel(TxnLayout.ATTACHMENTS, expected = EntryKind.Directory)
        checkTopLevel(TxnLayout.IMPORT_DIR, expected = EntryKind.Directory)
        if (ops.entryKind(File(root, TxnLayout.IMPORT_DIR)) != EntryKind.Absent) validateImport()
    }

    /**
     * 顶层三个已知名字。**根下的其他东西不查**：生产里 `filesDir` 还有录音等目录，
     * 它们不在事务布局内，也永远不会被这条路径删到。
     *
     * `expected == null` 表示「类型不限，只要不是链接且判得出来」。
     */
    private fun checkTopLevel(name: String, expected: EntryKind?) {
        val file = File(root, name)
        when (val kind = ops.entryKind(file)) {
            EntryKind.Absent -> return
            EntryKind.Symlink -> fail("symlink:$name")
            is EntryKind.Unknown -> fail("undecidable:$name:${kind.reason}")
            else -> if (expected != null && kind != expected) fail("shape:$name")
        }
        val canonical = canonicalOf(file) ?: fail("unresolvable:$name")
        if (canonical.parentFile != rootCanonical) fail("outside-root:$name")
    }

    // ---------------- `.import/` 一层 ----------------

    private fun validateImport() {
        val importDir = File(root, TxnLayout.IMPORT_DIR)
        val importCanonical = canonicalOf(importDir) ?: fail("unresolvable:${TxnLayout.IMPORT_DIR}")
        if (importCanonical.parentFile != rootCanonical) fail("outside-root:${TxnLayout.IMPORT_DIR}")

        for (child in enumerate(importDir, TxnLayout.IMPORT_DIR)) {
            val name = child.name
            val relative = "${TxnLayout.IMPORT_DIR}/$name"
            val kind = stableKind(child, relative)
            when {
                name in GLOBAL_FILES -> if (kind != EntryKind.Regular) fail("shape:$relative")

                name.startsWith(TxnLayout.TXN_PREFIX) -> {
                    if (kind != EntryKind.Directory) fail("shape:$relative")
                    if (!TxnLayout.isSafeName(name.removePrefix(TxnLayout.TXN_PREFIX))) {
                        fail("unsafe-name:$relative")
                    }
                    validateTxnDir(child, relative, importCanonical)
                }

                isTempLeftover(name) -> if (kind != EntryKind.Regular) fail("shape:$relative")

                // 布局里没有的东西：不猜、不跳过、更不交给删除
                else -> fail("unknown-entry:$relative")
            }
        }
    }

    private fun validateTxnDir(dir: File, relative: String, importCanonical: File) {
        val canonical = canonicalOf(dir) ?: fail("unresolvable:$relative")
        if (canonical.parentFile != importCanonical) fail("outside-root:$relative")

        for (child in enumerate(dir, relative)) {
            val name = child.name
            val childRelative = "$relative/$name"
            val kind = stableKind(child, childRelative)
            when {
                name in TXN_FILES -> if (kind != EntryKind.Regular) fail("shape:$childRelative")

                name == TxnLayout.OLD_ATTACHMENTS || name == TxnLayout.NEW_ATTACHMENTS -> {
                    if (kind != EntryKind.Directory) fail("shape:$childRelative")
                    validateMaterials(child, childRelative, canonical)
                }

                isTempLeftover(name) -> if (kind != EntryKind.Regular) fail("shape:$childRelative")

                else -> fail("unknown-entry:$childRelative")
            }
        }
    }

    /**
     * 材料目录只允许平铺的普通文件，**不允许再套目录**。
     *
     * 这是「已知结构的最后一层」：再往下的东西不在删除范围内，所以一旦出现目录就整片拒掉，
     * 而不是先删了再说。`writeAtomic` 的残留临时文件形状固定、是单文件删除，予以放行。
     */
    private fun validateMaterials(dir: File, relative: String, parentCanonical: File) {
        val canonical = canonicalOf(dir) ?: fail("unresolvable:$relative")
        if (canonical.parentFile != parentCanonical) fail("outside-root:$relative")

        for (child in enumerate(dir, relative)) {
            val childRelative = "$relative/${child.name}"
            val kind = stableKind(child, childRelative)
            val temp = isTempLeftover(child.name)
            if (kind != EntryKind.Regular) fail("unknown-entry:$childRelative")
            if (!temp && !TxnLayout.isSafeName(child.name)) fail("unsafe-name:$childRelative")
        }
    }

    // ---------------- 基础动作 ----------------

    private fun enumerate(dir: File, relative: String): List<File> = try {
        ops.listStrict(dir)
    } catch (failure: IOException) {
        fail("unlistable:$relative")
    }

    /** 列出来之后又变了（没了 / 变链接 / 判不出来），同样不许继续 */
    private fun stableKind(file: File, relative: String): EntryKind =
        when (val kind = ops.entryKind(file)) {
            EntryKind.Absent -> fail("vanished:$relative")
            EntryKind.Symlink -> fail("symlink:$relative")
            is EntryKind.Unknown -> fail("undecidable:$relative:${kind.reason}")
            else -> kind
        }

    private fun canonicalOf(file: File): File? =
        runCatching { file.canonicalFile }.getOrNull()

    private fun fail(reason: String): Nothing = throw UnsafeSignal(LayoutVerdict.Unsafe(reason))

    private class UnsafeSignal(val verdict: LayoutVerdict.Unsafe) : Exception(verdict.reason)

    private companion object {
        val GLOBAL_FILES = setOf(TxnLayout.JOURNAL, TxnLayout.CREDENTIAL, TxnLayout.RESULTS)

        val TXN_FILES = setOf(TxnLayout.COMMIT_JSON, TxnLayout.OLD_NOTES, TxnLayout.OLD_MANIFEST)

        /** `writeAtomic` 的临时文件：`.<名字>-<随机>.tmp`，单文件、不递归 */
        fun isTempLeftover(name: String): Boolean = name.startsWith(".") && name.endsWith(".tmp")
    }
}
