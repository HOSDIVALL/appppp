package com.example.myapplicationfirstapp.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 测试专用：把 filesDir / cacheDir 重定向到 cacheDir 下的临时沙箱。
 *
 * 生产代码里 [NotesRepository] 读的是 `context.applicationContext.filesDir`，
 * 所以只 override `getFilesDir()` 还不够 —— 默认实现会转发给真实 Application，
 * 仍然指向真机上的笔记目录。这里让 wrapper 自己充当 applicationContext，
 * `filesDir` 就落在沙箱里，真机 notes.json 一个字节都不会被碰到。
 *
 * 只在 androidTest 里使用，不进入生产代码。
 */
internal class IsolatedAppContext(base: Context, internal val rootDir: File) : ContextWrapper(base) {
    private val isolatedFiles = File(rootDir, "files")
    private val isolatedCache = File(rootDir, "cache")

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File {
        isolatedFiles.mkdirs()
        return isolatedFiles
    }

    override fun getCacheDir(): File {
        isolatedCache.mkdirs()
        return isolatedCache
    }

    /** 只删本测试自己的 UUID 沙箱，父目录 `save-order-2/` 保留 */
    fun dispose() {
        rootDir.deleteRecursively()
    }
}

/** 每次调用都返回一个全新空沙箱，测试之间互不干扰 */
internal fun isolatedStorage(tag: String): IsolatedAppContext {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(base.cacheDir, "save-order-2/$tag-${UUID.randomUUID()}")
    check(root.mkdirs() || root.isDirectory) { "Cannot create isolated root: $root" }
    return IsolatedAppContext(base, root)
}

internal fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(readBytes()).joinToString("") { "%02x".format(it) }
}

/** 沙箱内残留的原子写临时文件（正常路径结束后必须为空） */
internal fun File.listTempNotesFiles(): List<String> =
    listFiles { f -> f.isFile && f.name.startsWith("notes-") && f.name.endsWith(".tmp") }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()

/**
 * 真机/模拟器上「真实」笔记文件的指纹（不存在时返回 "<absent>"）。
 * 测试前后各取一次并比对，用来证明沙箱测试没有碰到应用真实数据。
 */
internal fun realNotesFingerprint(): String {
    val real = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "notes.json")
    return if (real.isFile) real.sha256Hex() else "<absent>"
}
