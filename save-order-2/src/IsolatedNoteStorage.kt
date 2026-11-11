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
 * `filesDir` 就落在沙箱里。
 *
 * 这带来的是**路径隔离**：真实 notes.json 与沙箱路径不同，测试前后的指纹比对应当一致。
 * 它不构成「进程内任何时刻都绝对没有触碰真实路径」的证明，只是本仓库能达到的最强检查。
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

    /**
     * 只删本测试自己的 UUID 沙箱，公共父目录 `save-order-2/` 保留。
     *
     * 返回是否真的删干净了 —— 调用方必须检查，不允许静默忽略清理失败。
     */
    fun dispose(): Boolean {
        rootDir.deleteRecursively()
        return !rootDir.exists()
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
 * 测试前后各取一次并比对，用来证明沙箱测试没有落到应用真实路径上。
 */
internal fun realNotesFingerprint(): String {
    val real = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "notes.json")
    return if (real.isFile) real.sha256Hex() else "<absent>"
}

/**
 * 统一收尾：先比对真实 notes.json 指纹，再删除本测试自己的 UUID 沙箱。
 *
 * 两步都必须在**测试失败时也执行**，所以这里不抛异常，而是把问题收集成清单返回，
 * 由 `@After` 统一断言。清理失败不会被静默忽略。
 */
internal fun finishIsolatedTest(sandbox: IsolatedAppContext, realBefore: String): List<String> {
    val violations = mutableListOf<String>()

    try {
        val after = realNotesFingerprint()
        if (after != realBefore) {
            violations += "路径隔离失效：真实 notes.json 指纹前后不一致（before=$realBefore after=$after）"
        }
    } catch (t: Throwable) {
        violations += "读取真实 notes.json 指纹失败：$t"
    }

    var cleared = false
    try {
        cleared = sandbox.dispose()
    } catch (t: Throwable) {
        violations += "清理沙箱时抛异常：$t"
    }
    if (!cleared) {
        violations += "本测试的 UUID 沙箱未被删除：${sandbox.rootDir}"
    }

    val parent = sandbox.rootDir.parentFile
    if (parent == null || !parent.isDirectory) {
        violations += "公共父目录 save-order-2/ 不得被删除：$parent"
    }

    return violations
}
