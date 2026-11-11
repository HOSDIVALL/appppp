package com.example.myapplicationfirstapp.data

import java.io.File
import java.io.FileOutputStream

/**
 * notes.json 原子写的五个真实 IO 步骤。
 *
 * [RealNotesFileOps] 就是 `save()` 原先直接调用的那几行，行为完全等价；
 * 把步骤拆出来只为让测试能在**指定的一步**注入失败，生产默认路径不做任何模拟。
 *
 * 只在本模块与 androidTest 之间可见，不对外暴露。
 */
internal interface NotesFileOps {
    /** 在 [parent] 下建一个唯一的临时文件 */
    fun createTemp(parent: File): File

    /** 打开临时文件的输出流（会截断已有内容） */
    fun openStream(temp: File): FileOutputStream

    /** 写入完整字节数组 */
    fun write(out: FileOutputStream, bytes: ByteArray)

    /** 把已写入的内容刷到存储介质 */
    fun sync(out: FileOutputStream)

    /** 原子替换：把临时文件移到正式路径，成功返回 true */
    fun rename(from: File, to: File): Boolean
}

/** 生产实现：真实建文件、真实写、真实 fsync、真实 rename。 */
internal object RealNotesFileOps : NotesFileOps {
    override fun createTemp(parent: File): File = File.createTempFile("notes-", ".tmp", parent)

    override fun openStream(temp: File): FileOutputStream = FileOutputStream(temp)

    override fun write(out: FileOutputStream, bytes: ByteArray) = out.write(bytes)

    override fun sync(out: FileOutputStream) = out.fd.sync()

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)
}
