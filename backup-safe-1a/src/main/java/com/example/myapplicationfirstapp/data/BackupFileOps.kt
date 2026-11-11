package com.example.myapplicationfirstapp.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 导入准备阶段的文件接缝。
 *
 * 生产实现 [RealBackupFileOps] 就是真实 IO；测试可以注入「第 N 次调用抛异常」「阻塞到取消」
 * 之类的实现，用来验证失败与取消路径。生产路径没有任何「测试模式跳过 IO」的分支。
 *
 * 这里只做**准备阶段**需要的动作：读源、写暂存、fsync、改名、删除、摘要。
 * 提交阶段的日志/回滚动作属于 BACKUP-SAFE-1B，本文件不涉及。
 */
internal interface BackupFileOps {

    /** 打开用户选中的 URI 的输入流；打不开返回 null（权限失效、提供方错误等） */
    fun openSource(uri: Uri): InputStream?

    fun openOutput(file: File): OutputStream

    /** 把已写入的内容落到存储（真实实现是 `fd.sync()`） */
    fun sync(output: OutputStream)

    fun rename(from: File, to: File): Boolean

    fun delete(file: File): Boolean

    /** 递归删除一个目录；返回是否确实不存在了 */
    fun deleteTree(dir: File): Boolean

    /** 文件的 SHA-256（小写十六进制） */
    fun digest(file: File): String

    /**
     * 每复制完一块调用一次（生产实现是空操作）。
     *
     * 只给测试用：注入实现可以在这里阻塞，让测试有机会在复制途中的确定性位置取消协程。
     */
    fun onChunk(bytesCopied: Long) {}
}

internal class RealBackupFileOps(private val context: Context) : BackupFileOps {

    override fun openSource(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    override fun openOutput(file: File): OutputStream = FileOutputStream(file)

    override fun sync(output: OutputStream) {
        // 只有 FileOutputStream 能 fsync；其他实现没有 fd，跳过即可
        if (output is FileOutputStream) output.fd.sync()
    }

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    override fun delete(file: File): Boolean = file.delete()

    override fun deleteTree(dir: File): Boolean {
        dir.deleteRecursively()
        return !dir.exists()
    }

    override fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
