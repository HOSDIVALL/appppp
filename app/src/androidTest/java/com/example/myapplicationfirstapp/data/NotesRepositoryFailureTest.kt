package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 *
 * 隔离是**路径隔离**：真实 notes.json 与沙箱路径不同，测试前后指纹比对一致。
 * 本机真实 `files/` 里本来就没有 notes.json，前后都是 `<absent>`，
 * 这只能证明隔离没有回落到真实路径，不能推成「进程内任何时刻绝对没有写入真实路径」。
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

    /** 指纹核验与沙箱清理在**测试失败时也执行**；清理失败不会被静默忽略 */
    @After
    fun tearDown() {
        if (!::sandbox.isInitialized) return
        val violations = finishIsolatedTest(sandbox, realBefore)
        assertTrue("收尾检查未通过：\n" + violations.joinToString("\n"), violations.isEmpty())
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

        // 第一次尝试的轨迹必须是精确的这三步：建临时文件 → 打开流 → 半写后失败。
        // 只检查"sync / rename 没出现"不够：重复事件、乱序、多写一次都逃得过去。
        assertAttemptTrace(
            "F1 第一次尝试",
            ops.trace,
            "createTemp",
            "openStream",
            "write(partial)"
        )

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

        // 精确四步：建临时文件 → 打开流 → 完整写 → sync 处注入失败。rename 一次都不该出现。
        assertAttemptTrace(
            "F2 第一次尝试",
            ops.trace,
            "createTemp",
            "openStream",
            "write(full)",
            "sync-injected"
        )

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

        // 精确五步，顺序固定、每步只允许一次：
        // createTemp → openStream → 完整写 → **真实 fd.sync** → rename 注入返回 false。
        // 仅断言"三个事件都出现过"是不够的：那放过重复、乱序、以及 rename 先于 sync 的实现错误。
        assertAttemptTrace(
            "F3 第一次尝试",
            ops.trace,
            "createTemp",
            "openStream",
            "write(full)",
            "sync",
            "rename-injected-false"
        )

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

        // 恢复重试**之前**，先把第一次尝试的轨迹钉死
        assertAttemptTrace(
            "F4 第一次尝试（恢复重试之前）",
            ops.trace,
            "createTemp",
            "openStream",
            "write(partial)"
        )

        // 同一个 Repository 实例 + 同一个路径，第二次不再注入
        repository.save(listOf(newNote()))

        assertEquals("第二次不应再注入", 1, ops.injectedCount)
        assertEquals("v2 新内容", NotesRepository(sandbox).load().single().title)
        assertEquals("第二版正文", NotesRepository(sandbox).load().single().content)
        assertEquals("恢复后不应残留 .tmp", emptyList<String>(), filesDir.listTempNotesFiles())

        // 两次尝试拼起来的完整轨迹也逐项核对：2 次建临时文件、1 次半写、1 次完整写、1 次真实 sync、1 次真实 rename
        assertAttemptTrace(
            "F4 两次尝试合计",
            ops.trace,
            "createTemp",
            "openStream",
            "write(partial)",
            "createTemp",
            "openStream",
            "write(full)",
            "sync",
            "rename(true)"
        )
    }

    // ================= 公共断言 =================

    /**
     * 用真实 IO 写入 v1，并**先证明基准是有效的**再返回磁盘原始字节。
     *
     * 不允许拿空文件或坏文件当「旧文件保持不变」的基准：
     * 一旦 v1 没写成功，后面的字节比对会毫无意义地通过。
     */
    private suspend fun seedOldContent(): ByteArray {
        NotesRepository(sandbox).save(listOf(oldNote()))

        assertTrue("前置：v1 必须已落盘", target.isFile)
        assertTrue("前置：v1 不得为空文件，实际 ${target.length()} 字节", target.length() > 0L)

        // 前置：磁盘上必须是可解析的 JSON（不是半截内容）
        val raw = target.readText(Charsets.UTF_8)
        val array = JSONArray(raw)
        assertEquals("前置：磁盘上应当只有一条笔记", 1, array.length())
        val json = array.getJSONObject(0)
        assertEquals("note-v1", json.getString("id"))
        assertEquals("v1 旧内容", json.getString("title"))
        assertEquals("第一版正文", json.getString("content"))
        assertEquals(1_760_000_100_000L, json.getLong("updatedAt"))

        // 前置：新实例读回来的对象必须等于预期 v1（字段级 + 整体相等）
        val reloaded = NotesRepository(sandbox).load()
        assertEquals("前置：新实例应当读回一条 v1", 1, reloaded.size)
        val back = reloaded.single()
        assertEquals("v1 旧内容", back.title)
        assertEquals("第一版正文", back.content)
        assertEquals(1_760_000_100_000L, back.updatedAt)
        assertEquals("前置：读回的笔记必须整体等于预期 v1", oldNote(), back)

        val bytes = target.readBytes()
        assertEquals("前置：读回字节长度应与文件一致", target.length(), bytes.size.toLong())
        return bytes
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

    /**
     * 精确断言一次（或一段）尝试的操作轨迹。
     *
     * 只检查"某些事件出现过"是不够的：重复、乱序、多做一步都会被放过。
     * 这里同时钉住**条数**与**每一步的顺序和形态**：
     * - `"createTemp"` / `"openStream"` / `"sync"` / `"sync-injected"` / `"rename(true)"` 等按字面精确匹配；
     * - `"write(full)"` 匹配完整写入（形如 `write(86)`，且不是 partial）；
     * - `"write(partial)"` 匹配半写后注入失败（形如 `write:partial(43/86)-injected`）。
     */
    private fun assertAttemptTrace(stage: String, actual: List<String>, vararg expected: String) {
        assertEquals(
            "$stage：轨迹条数必须精确，实际 ${actual.size} 步：$actual",
            expected.size,
            actual.size
        )
        expected.forEachIndexed { index, want ->
            val got = actual[index]
            val matched = when (want) {
                "write(full)" -> got.startsWith("write(") && !got.contains("partial")
                "write(partial)" -> got.startsWith("write:partial(") && got.endsWith("-injected")
                else -> got == want
            }
            assertTrue(
                "$stage：第 ${index + 1} 步应为 $want，实际为 $got；完整轨迹：$actual",
                matched
            )
        }
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
