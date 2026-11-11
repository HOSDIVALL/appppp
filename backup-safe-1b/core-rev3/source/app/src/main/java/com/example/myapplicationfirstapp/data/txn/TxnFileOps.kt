package com.example.myapplicationfirstapp.data.txn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 一个条目**实际是什么**。
 *
 * 五态而不是布尔，是因为「不知道」必须能和「不是链接」「不存在」分开：把 `lstat` 的
 * 权限错误当成「不是链接」，就等于把没检查过的目录当成可以递归删除的目标。
 * `Absent` 只留给 `ENOENT` / `ENOTDIR`——内核明确回答了「这里没有东西」。
 */
internal sealed interface EntryKind {
    data object Regular : EntryKind
    data object Directory : EntryKind
    data object Symlink : EntryKind
    data object Absent : EntryKind
    data class Unknown(val reason: String) : EntryKind
}

/**
 * 事务内核的文件接缝。
 *
 * 内核只认这一层，不直接 `File.writeBytes()`，原因有两个：
 *
 * 1. **可注入**：测试要在指定步骤注入 IO 异常或阻塞，才能把「第 3 个附件写完就崩」「凭据写一半」
 *    这类位置固定下来。生产实现 [RealTxnFileOps] 没有任何「测试模式」分支。
 * 2. **原子写只有一种写法**：`temp → write → fsync → rename`。把它收敛到一个方法里，
 *    内核里就不会出现「某处忘了 fsync」这种逐步骤漂移。
 *
 * 所有路径都相对于调用方给定的**存储根**（生产是 `filesDir`，测试是隔离沙箱），
 * 本接口不持有 Context，也不解析任何全局路径。
 */
internal interface TxnFileOps {

    fun exists(file: File): Boolean

    fun isDirectory(file: File): Boolean

    /** 目录内条目，按名字排序；目录不存在返回空列表 */
    fun list(dir: File): List<File>

    /**
     * 目录内条目，**枚举不出来就抛 IOException**。
     *
     * 与 [list] 分开，是因为「空目录」和「列不出来」在判定上是两件事：
     * 前者可以删，后者连能不能删都不知道。清理与布局自检只许用这个。
     */
    fun listStrict(dir: File): List<File>

    /** 单个条目的实际类型，**不跟随符号链接**；判不出来返回 [EntryKind.Unknown] */
    fun entryKind(file: File): EntryKind

    /** 读取完整字节；不存在或读失败抛 IOException */
    fun readBytes(file: File): ByteArray

    /**
     * 原子写：同目录 `temp` → 写入 → `fsync` → `rename`。
     *
     * 失败时抛 IOException，并尽力删除临时文件；不得留下半份正式文件。
     */
    fun writeAtomic(file: File, bytes: ByteArray)

    fun rename(from: File, to: File): Boolean

    fun delete(file: File): Boolean

    // 这里**故意没有** deleteTree：无边界的递归删除会顺着符号链接清空根外目录。
    // 需要删目录时用 TxnSafeDelete，它按 [entryKind] 逐层走，删不掉就停。

    fun mkdirs(dir: File): Boolean

    fun digest(file: File): String

    fun digestBytes(bytes: ByteArray): String

    fun length(file: File): Long

    /** 该目录所在存储的可用字节；测试可注入一个很小的值覆盖空间预检 */
    fun usableSpace(dir: File): Long

    /**
     * 确定性注入点。内核在**每个会改变磁盘的动作之前**调用一次，名字稳定（见 [TxnStep]）。
     *
     * 生产实现是空操作；测试实现可以在这里抛 IOException（模拟注入的 IO 异常）
     * 或阻塞等待（把取消固定在某个窗口）。这里不做任何「吞异常」处理。
     */
    fun checkpoint(name: String) {}
}

/** 稳定的步骤名。测试用它们定位注入点，不要改成动态字符串。 */
internal object TxnStep {
    const val JOURNAL = "journal"
    const val CREDENTIAL = "credential"
    const val RESULTS = "results"
    const val OLD_NOTES = "old-notes"
    const val OLD_MANIFEST = "old-manifest"
    const val NOTES = "notes"

    fun oldAttachment(name: String): String = "old-attachment:$name"
    fun newAttachment(name: String): String = "new-attachment:$name"
    fun deleteMaterial(relative: String): String = "delete-material:$relative"
}

internal class RealTxnFileOps : TxnFileOps {

    override fun exists(file: File): Boolean = file.exists()

    override fun isDirectory(file: File): Boolean = file.isDirectory

    override fun list(dir: File): List<File> =
        dir.listFiles()?.sortedBy { it.name } ?: emptyList()

    override fun listStrict(dir: File): List<File> =
        dir.listFiles()?.sortedBy { it.name }
            ?: throw IOException("cannot enumerate ${dir.name}")

    override fun entryKind(file: File): EntryKind = try {
        val mode = Os.lstat(file.absolutePath).st_mode
        when {
            OsConstants.S_ISLNK(mode) -> EntryKind.Symlink
            OsConstants.S_ISDIR(mode) -> EntryKind.Directory
            OsConstants.S_ISREG(mode) -> EntryKind.Regular
            else -> EntryKind.Unknown("special")
        }
    } catch (failure: ErrnoException) {
        when (failure.errno) {
            // 只有内核明确说「这里没有」才算不存在；其余（EACCES、ELOOP、EIO…）都是「不知道」
            OsConstants.ENOENT, OsConstants.ENOTDIR -> EntryKind.Absent
            else -> EntryKind.Unknown("errno:${failure.errno}")
        }
    }

    override fun readBytes(file: File): ByteArray = file.readBytes()

    override fun writeAtomic(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("no parent for ${file.name}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("cannot create directory ${parent.name}")
        }
        val temp = File.createTempFile(".${file.name}-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("cannot replace ${file.name}")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    override fun delete(file: File): Boolean = file.delete()

    override fun mkdirs(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    override fun digest(file: File): String = digestBytes(file.readBytes())

    override fun digestBytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    override fun length(file: File): Long = file.length()

    override fun usableSpace(dir: File): Long = dir.usableSpace
}
