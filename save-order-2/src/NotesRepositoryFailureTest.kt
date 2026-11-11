package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * SAVE-ORDER-2 / 故障注入：原子写失败时必须保住旧文件。
 *
 * 诚实边界（不得表述成真实故障）：
 * - 这里注入的是**我们自己构造的 IOException** 或 `rename` 返回 false，
 *   不是内核返回的 ENOSPC / EIO，也不是真实掉电；
 * - F2 的 `fd.sync()` 实际**没有**失败（真实实现被跳过），验证的是
 *   "内容已写满、还没 rename" 这个中间态下的保护；
 * - 临时文件、字节写入、fsync（F3）、删除都是真实文件操作。
 */
@RunWith(AndroidJUnit4::class)
class NotesRepositoryFailureTest {

    private lateinit var sandbox: IsolatedAppContext
    private var realBefore: String = "<unset>"

    private val filesDir: File get() = sandbox.getFilesDir()
    private val target: File get() = File(filesDir, "notes.json")

    @Before
    fun setUp() {
        // 指纹在测试体之前取，失败时也在 @After 里再取一次做比对
        realBefore = realNotesFingerprint()
        sandbox = isolatedStorage("failure")
    }

    @After
    fun tearDown() {
        try {
            if (::sandbox.isInitialized) {
                assertEquals(
                    "路径隔离：真实 notes.json 指纹测试前后一致",
                    realBefore,
                    realNotesFingerprint()
                )
            }
        } finally {
            if (::sandbox.isInitialized) sandbox.dispose()
        }
    }

    // ================= F1 =================

    @Test
    fun tempWriteFailureKeepsOldFile() = runBlocking {
        val oldBytes = seedOldContent()

        val ops = InjectingFileOps(failAt = InjectingFileOps.Step.WRITE)
        val repository = NotesRepository(sandbox, ops)

        val error = runCatching { repository.save(listOf(newNote())) }.exceptionOrNull()

        assertTrue("调用方必须收到 IOException，实际是 $error", error is IOException)
        assertTrue(
            "必须是写盘失败而不是别的路径，实际消息：${(error as IOException).message}",
            error.message.orEmpty().contains("injected: write")
        )
        assertOldFileIntact(oldBytes)

        // 轨迹：写完一半就炸，sync / rename 一步都没走到
        val trace = ops.trace
        assertTrue("应当已真实创建临时文件：$trace", trace.contains("createTemp"))
        assertTrue("应当已真实打开输出流：$trace", trace.contains("openStream"))
        assertTrue("应当已真实写入一半字节：$trace", trace.any { it.startsWith("write:partial") })
        assertFalse("sync 不应被调用：$trace", trace.any { it.startsWith("sync") })
        assertFalse("rename 不应被调用：$trace", trace.any { it.startsWith("rename") })

        // 同一个 Repository 实例、同一路径恢复正常
        repository.save(listOf(newNote()))
        assertSavedNewContent(oldBytes)
    }

    // ================= F2 =================

    @Test
    fun syncFailureKeepsOldFile() = runBlocking {
        val oldBytes = seedOldContent()

        val ops = InjectingFileOps(failAt = InjectingFileOps.Step.SYNC)
        val repository = NotesRepository(sandbox, ops)

        val error = runCatching { repository.save(listOf(newNote())) }.exceptionOrNull()

        assertTrue("调用方必须收到 IOException，实际是 $error", error is IOException)
        assertTrue(
            "必须停在 sync 步骤，实际消息：${(error as IOException).message}",
            error.message.orEmpty().contains("injected: fsync")
        )
        assertOldFileIntact(oldBytes)

        val trace = ops.trace
        assertTrue(
            "完整内容应已真实写入临时文件：$trace",
            trace.any { it.startsWith("write(") && !it.contains("partial") }
        )
        assertTrue("sync 应被调用并在此注入失败：$trace", trace.any { it.startsWith("sync") })
        assertFalse("rename 不应被调用：$trace", trace.any { it.startsWith("rename") })

        repository.save(listOf(newNote()))
        assertSavedNewContent(oldBytes)
    }

    // ================= F3 =================

    @Test
    fun renameFailureKeepsOldFile() = runBlocking {
        val oldBytes = seedOldContent()

        val ops = InjectingFileOps(failAt = InjectingFileOps.Step.RENAME)
        val repository = NotesRepository(sandbox, ops)

        val error = runCatching { repository.save(listOf(newNote())) }.exceptionOrNull()

        assertTrue("调用方必须收到 IOException，实际是 $error", error is IOException)
        assertEquals("Cannot replace notes file", (error as IOException).message)
        assertOldFileIntact(oldBytes)

        val trace = ops.trace
        assertTrue(
            "完整内容应已真实写入：$trace",
            trace.any { it.startsWith("write(") && !it.contains("partial") }
        )
        assertTrue("真实 fd.sync 应已执行：$trace", trace.contains("sync"))
        assertTrue("rename 应被调用并返回 false：$trace", trace.contains("rename-injected-false"))

        repository.save(listOf(newNote()))
        assertSavedNewContent(oldBytes)
    }

    // ================= F4 =================

    @Test
    fun retryAfterFailureSucceeds() = runBlocking {
        val oldBytes = seedOldContent()

        // 只失败一次的注入器
        val ops = InjectingFileOps(failAt = InjectingFileOps.Step.WRITE)
        val repository = NotesRepository(sandbox, ops)

        val first = runCatching { repository.save(listOf(newNote())) }.exceptionOrNull()
        assertTrue("第一次必须失败，实际是 $first", first is IOException)
        assertOldFileIntact(oldBytes)
        assertEquals("注入应恰好发生一次", 1, ops.injectedCount)

        // 同一个 Repository 实例 + 同一个路径，第二次不再注入
        repository.save(listOf(newNote()))

        assertEquals("第二次不应再注入", 1, ops.injectedCount)
        assertEquals("v2 新内容", NotesRepository(sandbox).load().single().title)
        assertEquals("第二版正文", NotesRepository(sandbox).load().single().content)
        assertEquals("恢复后不应残留 .tmp", emptyList<String>(), filesDir.listTempNotesFiles())

        val trace = ops.trace
        assertEquals("两次保存各建一个临时文件：$trace", 2, trace.count { it == "createTemp" })
        assertEquals(
            "只有第二次写出了完整内容：$trace",
            1,
            trace.count { it.startsWith("write(") && !it.contains("partial") }
        )
        assertEquals("第二次应真的 rename 成功：$trace", 1, trace.count { it == "rename(true)" })
    }

    // ================= 公共断言 =================

    /** 用真实 IO 写入 v1，返回磁盘上的原始字节 */
    private suspend fun seedOldContent(): ByteArray {
        NotesRepository(sandbox).save(listOf(oldNote()))
        assertTrue("前置：v1 必须已落盘", target.isFile)
        return target.readBytes()
    }

    /** 失败之后：旧文件逐字节不变、临时文件已清理、新实例仍能读到 v1 */
    private suspend fun assertOldFileIntact(before: ByteArray) {
        assertTrue("旧文件必须还在", target.isFile)
        assertArrayEquals("notes.json 字节必须逐字节不变", before, target.readBytes())
        assertEquals("notes.json 长度必须不变", before.size.toLong(), target.length())
        assertEquals("notes.json SHA-256 必须不变", before.sha256(), target.sha256Hex())

        assertEquals("本次 notes-*.tmp 必须被清理", emptyList<String>(), filesDir.listTempNotesFiles())

        val reloaded = NotesRepository(sandbox).load()
        assertEquals("读回应当仍是一条 v1", 1, reloaded.size)
        assertEquals("v1 旧内容", reloaded.single().title)
        assertEquals("第一版正文", reloaded.single().content)
        assertEquals(1_760_000_100_000L, reloaded.single().updatedAt)
    }

    /** 恢复保存之后：v2 落到磁盘，能被新实例读到，且没有残留 */
    private suspend fun assertSavedNewContent(oldBytes: ByteArray) {
        val reloaded = NotesRepository(sandbox).load()
        assertEquals(1, reloaded.size)
        assertEquals("v2 新内容", reloaded.single().title)
        assertEquals("第二版正文", reloaded.single().content)
        assertEquals(1_760_000_200_000L, reloaded.single().updatedAt)
        assertNotEquals("恢复后哈希必须变化", oldBytes.sha256(), target.sha256Hex())
        assertEquals("恢复后不应残留 .tmp", emptyList<String>(), filesDir.listTempNotesFiles())
    }

    private fun oldNote() = Note(
        id = "note-v1",
        title = "v1 旧内容",
        content = "第一版正文",
        createdAt = 1_760_000_000_000L,
        updatedAt = 1_760_000_100_000L
    )

    private fun newNote() = Note(
        id = "note-v2",
        title = "v2 新内容",
        content = "第二版正文",
        createdAt = 1_760_000_000_000L,
        updatedAt = 1_760_000_200_000L
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    /**
     * 测试专用故障注入器：只失败一次，之后完全走 [RealNotesFileOps]。
     * 所有真实步骤（建临时文件、写字节、fsync、rename）都委托给真实实现，
     * 注入点只有"在哪一步抛"和"rename 返回 false"。
     */
    private class InjectingFileOps(
        private val delegate: NotesFileOps = RealNotesFileOps,
        private val failAt: Step
    ) : NotesFileOps {

        enum class Step { WRITE, SYNC, RENAME }

        private val events = mutableListOf<String>()
        val trace: List<String> get() = events.toList()

        var injectedCount = 0
            private set

        override fun createTemp(parent: File): File {
            events += "createTemp"
            return delegate.createTemp(parent)
        }

        override fun openStream(temp: File): FileOutputStream {
            events += "openStream"
            return delegate.openStream(temp)
        }

        override fun write(out: FileOutputStream, bytes: ByteArray) {
            if (failAt == Step.WRITE && injectedCount == 0) {
                injectedCount++
                val half = bytes.size / 2
                delegate.write(out, bytes.copyOf(half))
                out.flush()
                events += "write:partial($half/${bytes.size})-injected"
                throw IOException("injected: write failed after $half of ${bytes.size} bytes")
            }
            delegate.write(out, bytes)
            events += "write(${bytes.size})"
        }

        override fun sync(out: FileOutputStream) {
            if (failAt == Step.SYNC && injectedCount == 0) {
                injectedCount++
                events += "sync-injected"
                throw IOException("injected: fsync failed")
            }
            delegate.sync(out)
            events += "sync"
        }

        override fun rename(from: File, to: File): Boolean {
            if (failAt == Step.RENAME && injectedCount == 0) {
                injectedCount++
                events += "rename-injected-false"
                return false
            }
            val renamed = delegate.rename(from, to)
            events += "rename($renamed)"
            return renamed
        }
    }
}
