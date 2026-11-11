package com.example.myapplicationfirstapp.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 负责「读写文件」这一件事。
 *
 * 三种数据：
 * 1. 笔记本身 —— notes.json，一个小文本文件
 * 2. 图片附件 —— attachments/ 目录，一张图一个文件
 * 3. 备份 —— 把 1 + 2 打进一个 zip，交给系统文件选择器保存
 *
 * 目录都在 /data/data/<包名>/files/ 下，只有本 App 能访问，卸载时系统自动清理。
 */
class NotesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, NOTES_FILE_NAME)
    private val attachmentsDir: File get() = File(appContext.filesDir, ATTACHMENTS_DIR)

    // ============ 笔记 ============

    /** 读取全部笔记；文件不存在或内容损坏时返回空列表 */
    suspend fun load(): List<Note> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { notesFromJson(file.readText()) }.getOrDefault(emptyList())
    }

    /** Write a unique temporary file; propagate failures without truncating the old file. */
    suspend fun save(notes: List<Note>) = withContext(Dispatchers.IO) {
        val json = notesToJson(notes).toByteArray(Charsets.UTF_8)
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create notes directory" }
        val temp = File.createTempFile("notes-", ".tmp", parent)
        try {
            temp.outputStream().use { output ->
                output.write(json)
                output.fd.sync()
            }
            if (!temp.renameTo(file)) {
                throw java.io.IOException("Cannot replace notes file")
            }
        } finally {
            temp.delete()
        }
        Unit
    }

    // ============ 图片附件 ============

    fun attachmentFile(name: String): File = File(attachmentsDir, name)

    /**
     * 把相册选中的图片复制进 App 私有目录，并压缩。
     *
     * 为什么要复制？因为相册给的 Uri 只是「临时通行证」，
     * 用户删掉原图或重启后可能就失效了，笔记里的图会变成空白。
     */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            attachmentsDir.mkdirs()
            val name = "img_${System.currentTimeMillis()}.jpg"
            val target = File(attachmentsDir, name)

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null

            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }

            ImageCompressor.compressInPlace(target)
            name
        }.getOrNull()
    }

    /** 为拍照准备一个空文件，并返回可交给相机 App 的 Uri */
    fun createCameraTarget(): Pair<String, Uri>? = runCatching {
        attachmentsDir.mkdirs()
        val name = "cam_${System.currentTimeMillis()}.jpg"
        val target = File(attachmentsDir, name)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            target
        )
        name to uri
    }.getOrNull()

    /** 相机拍完回来再压缩 —— 相机 App 写的永远是原图 */
    suspend fun compressAttachment(name: String) = withContext(Dispatchers.IO) {
        val target = File(attachmentsDir, name)
        if (target.exists()) ImageCompressor.compressInPlace(target)
        Unit
    }

    /** 删除一张图片（笔记彻底删除、或用户移除图片时调用） */
    fun deleteAttachment(name: String) {
        runCatching { File(attachmentsDir, name).delete() }
    }

    fun deleteAttachments(names: List<String>) {
        names.forEach(::deleteAttachment)
    }

    /** 删掉不再被任何笔记引用的图片（导入备份后清理旧文件） */
    fun pruneAttachments(keep: Set<String>) {
        runCatching {
            attachmentsDir.listFiles()?.forEach { file ->
                if (file.name !in keep) file.delete()
            }
        }
    }

    // ============ 备份 ============

    /**
     * 导出 zip：`notes.json` + `attachments/xxx.jpg`。
     *
     * 不用申请存储权限：uri 由系统文件选择器（SAF）给出，
     * 我们只拿到这一次写入的许可，和之前选照片是同一套机制。
     */
    suspend fun exportBackup(uri: Uri, notes: List<Note>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    zip.putNextEntry(ZipEntry(NOTES_FILE_NAME))
                    zip.write(notesToJson(notes).toByteArray())
                    zip.closeEntry()

                    notes.flatMap { it.images }.distinct().forEach { name ->
                        val image = File(attachmentsDir, name)
                        if (image.exists() && image.length() > 0) {
                            zip.putNextEntry(ZipEntry("$ATTACHMENTS_DIR/$name"))
                            image.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } != null
        }.getOrDefault(false)
    }

    /**
     * 读取备份 zip，把图片还原到 attachments/，返回解析出来的笔记。
     * 读不懂的文件（不是 zip、缺 notes.json）返回 null。
     */
    suspend fun importBackup(uri: Uri): List<Note>? = withContext(Dispatchers.IO) {
        runCatching {
            var notes: List<Note>? = null

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    attachmentsDir.mkdirs()

                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == NOTES_FILE_NAME -> {
                                notes = notesFromJson(zip.readBytes().toString(Charsets.UTF_8))
                            }

                            entry.name.startsWith("$ATTACHMENTS_DIR/") -> {
                                val name = entry.name.removePrefix("$ATTACHMENTS_DIR/")
                                // 只接受纯文件名，防止压缩包里带 ../ 跑到别的目录去
                                if (name.isNotBlank() && '/' !in name && '\\' !in name) {
                                    val target = File(attachmentsDir, name)
                                    target.outputStream().use { zip.copyTo(it) }
                                    if (target.length() == 0L) target.delete()
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            notes
        }.getOrNull()
    }

    private companion object {
        const val NOTES_FILE_NAME = "notes.json"
        const val ATTACHMENTS_DIR = "attachments"
    }
}
