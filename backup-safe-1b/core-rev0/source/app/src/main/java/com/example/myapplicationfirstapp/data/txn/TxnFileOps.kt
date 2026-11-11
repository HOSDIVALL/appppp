package com.example.myapplicationfirstapp.data.txn

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

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

    fun deleteTree(dir: File): Boolean

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

    override fun deleteTree(dir: File): Boolean {
        dir.deleteRecursively()
        return !dir.exists()
    }

    override fun mkdirs(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    override fun digest(file: File): String = digestBytes(file.readBytes())

    override fun digestBytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    override fun length(file: File): Long = file.length()

    override fun usableSpace(dir: File): Long = dir.usableSpace
}
