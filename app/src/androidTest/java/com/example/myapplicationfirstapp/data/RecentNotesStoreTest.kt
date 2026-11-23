package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真实 SQLite：最近使用的独立元数据库 recent_notes.db。
 *
 * 边界说明：
 * - 用**真实 SQLiteOpenHelper + 真实数据库文件**，库文件落在沙箱 `databases/` 下；
 * - 「重启」用 [RecentNotesStore.openOnSameDatabase] 新开一个实例，证明行落在磁盘上，
 *   不等于进程被杀后的恢复实验；
 * - 不读不写 notes.json，真实 notes.json 与真实 recent_notes.db 的指纹前后必须一致。
 */
@RunWith(AndroidJUnit4::class)
class RecentNotesStoreTest {

    private lateinit var sandbox: IsolatedAppContext
    private var realNotesBefore: String = "<unset>"
    private var realDbExistedBefore: Boolean = false

    @Before
    fun setUp() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        realNotesBefore = realNotesFingerprint()
        realDbExistedBefore = File(realContext.getDatabasePath("recent_notes.db").path).exists()
        sandbox = isolatedStorage("recent-store")
    }

    @After
    fun tearDown() {
        if (!::sandbox.isInitialized) return
        val violations = finishIsolatedTest(sandbox, realNotesBefore).toMutableList()
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        val realDb = File(realContext.getDatabasePath("recent_notes.db").path)
        if (realDb.exists() != realDbExistedBefore) {
            violations += "数据库隔离失效：真实 recent_notes.db 存在性前后不一致（before=$realDbExistedBefore after=${realDb.exists()}）"
        }
        assertTrue("收尾检查未通过：\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    private fun store(): RecentNotesStore = RecentNotesStore.openOnSameDatabase(sandbox)

    @Test
    fun recordsAndOrdersByOpenOrder() = runBlocking {
        val store = store()
        store.recordOpened("a", 100L)
        store.recordOpened("b", 200L)
        store.recordOpened("c", 300L)

        val rows = store.load()
        assertEquals(listOf("c", "b", "a"), rows.map { it.noteId })
        assertEquals(300L, rows.first().lastOpenedAt)
        assertTrue("openOrder 必须递增", rows[0].openOrder > rows[1].openOrder)
    }

    @Test
    fun reopeningMovesToTopWithoutDuplicates() = runBlocking {
        val store = store()
        store.recordOpened("a", 100L)
        store.recordOpened("b", 200L)
        store.recordOpened("a", 300L)

        val rows = store.load()
        assertEquals(listOf("a", "b"), rows.map { it.noteId })
        assertEquals("同一条笔记只能有一行", 2, rows.size)
        // 同名不同 ID 必须各记一行，不能串
        store.recordOpened("a2", 400L)
        assertEquals(listOf("a2", "a", "b"), store.load().map { it.noteId })
    }

    @Test
    fun orderSurvivesClockRollback() = runBlocking {
        val store = store()
        store.recordOpened("a", 900L)
        store.recordOpened("b", 100L) // 时钟被回拨

        assertEquals(listOf("b", "a"), store.load().map { it.noteId })
    }

    @Test
    fun rowsSurviveNewStoreInstance() = runBlocking {
        store().recordOpened("a", 100L)

        val reopened = RecentNotesStore.openOnSameDatabase(sandbox)
        assertEquals(listOf("a"), reopened.load().map { it.noteId })
        assertTrue(
            "数据库文件必须真的落在沙箱里",
            sandbox.getDatabasePath("recent_notes.db").isFile
        )
    }

    @Test
    fun forgetRemovesOnlyThatRow() = runBlocking {
        val store = store()
        store.recordOpened("a", 100L)
        store.recordOpened("b", 200L)

        assertEquals(listOf("a"), store.forget("b").map { it.noteId })
        assertEquals(listOf("a"), store.load().map { it.noteId })
        // 重复忘记同一个 id 是幂等的
        assertEquals(listOf("a"), store.forget("b").map { it.noteId })
    }

    @Test
    fun forgetAllRemovesBatch() = runBlocking {
        val store = store()
        store.recordOpened("a", 100L)
        store.recordOpened("b", 200L)
        store.recordOpened("c", 300L)

        val left = store.forgetAll(listOf("a", "c", "never-recorded"))
        assertEquals(listOf("b"), left.map { it.noteId })
        assertEquals(listOf("b"), store.load().map { it.noteId })
    }

    @Test
    fun forgetAllWithBlankIdsIsNoOp() = runBlocking {
        val store = store()
        store.recordOpened("a", 100L)

        assertEquals(listOf("a"), store.forgetAll(listOf("", " ")).map { it.noteId })
    }

    @Test
    fun blankNoteIdIsRejected() {
        val failure = runCatching { runBlocking { store().recordOpened(" ") } }.exceptionOrNull()
        assertTrue("空 id 必须被拒绝", failure is IllegalArgumentException)
    }

    @Test
    fun negativeTimestampIsRejected() {
        val failure = runCatching { runBlocking { store().recordOpened("a", -1L) } }.exceptionOrNull()
        assertTrue("负时间戳必须被拒绝", failure is IllegalArgumentException)
    }

    /**
     * 库文件被写坏时，Android 的 DefaultDatabaseErrorHandler 会删掉坏库重建，
     * 所以真实表现是「历史清空、不崩溃」，而不是往上抛异常。
     * 这条用例把这个行为钉住：坏库之后 App 仍可正常记录。
     */
    @Test
    fun corruptDatabaseIsRecreatedInsteadOfCrashing() = runBlocking {
        val dbFile = sandbox.getDatabasePath("recent_notes.db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(4096) { 0x41 })

        val store = store()
        val recovered = runCatching { store.load() }
        assertTrue("坏库不应把打开流程炸掉：${recovered.exceptionOrNull()}", recovered.isSuccess)
        assertEquals("坏库重建后历史为空", emptyList<String>(), recovered.getOrThrow().map { it.noteId })

        // 重建后的库必须还能用
        assertEquals(listOf("a"), store.recordOpened("a", 100L).map { it.noteId })
    }

    /**
     * 打不开数据库（目录不可写）时必须把错误抛给调用方，
     * 不能静默返回空列表 —— 否则 NotesState 无从把错误落到 recentError。
     */
    @Test
    fun unopenableDatabaseSurfacesError() = runBlocking {
        val dbFile = sandbox.getDatabasePath("recent_notes.db")
        val dir = checkNotNull(dbFile.parentFile)
        check(dir.isDirectory)
        check(dir.setWritable(false, false)) { "无法把沙箱数据库目录设为只读" }
        try {
            val failure = runCatching { store().load() }.exceptionOrNull()
            assertNotNull("打不开的库必须抛错", failure)
            assertTrue(
                "应为 SQLite 异常，实际：${failure?.javaClass?.name}",
                failure is android.database.SQLException
            )
        } finally {
            dir.setWritable(true, true)
        }
        assertFalse("不得写进真实 notes.json", File(sandbox.rootDir, "files/notes.json").exists())
    }
}
