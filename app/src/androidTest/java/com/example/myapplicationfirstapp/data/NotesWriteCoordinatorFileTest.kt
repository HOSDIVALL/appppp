package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SAVE-ORDER-2 / 真实文件：协调器的顺序判断必须作用在**磁盘上**，而不是内存变量。
 *
 * 这里 [NotesWriteCoordinator.save] 的写回调调用的是真实的 [NotesRepository.save]，
 * 断言也落在真实 notes.json 的字节与哈希上；重新读取走新的 Repository 实例。
 *
 * 隔离是**路径隔离**：真实 notes.json 与沙箱路径不同，测试前后指纹比对一致；
 * 不等于「进程内任何时刻都绝对没有触碰过真实路径」。
 */
@RunWith(AndroidJUnit4::class)
class NotesWriteCoordinatorFileTest {

    private lateinit var sandbox: IsolatedAppContext
    private var realBefore: String = "<unset>"

    @Before
    fun setUp() {
        realBefore = realNotesFingerprint()
        sandbox = isolatedStorage("coordinator-file")
    }

    /** 指纹核验与沙箱清理在**测试失败时也执行**；清理失败不会被静默忽略 */
    @After
    fun tearDown() {
        if (!::sandbox.isInitialized) return
        val violations = finishIsolatedTest(sandbox, realBefore)
        assertTrue("收尾检查未通过：\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun coordinatorPersistsNewestSnapshotToRealFile() = runBlocking {
        val context = sandbox
        val filesDir = context.getFilesDir()
        val target = File(filesDir, "notes.json")

        val repository = NotesRepository(context)
        val coordinator = NotesWriteCoordinator()

        // 起点：磁盘上先有一份「基线」内容（直接写，不经过协调器）
        repository.save(listOf(snapshot("基线标题", "基线正文", 1_760_000_000_000L)))
        assertTrue(target.isFile)
        val baselineHash = target.sha256Hex()

        // 调度之前就把两个快照和它们的 ticket 都留好
        val staleNotes = listOf(snapshot("旧快照", "旧的正文，绝不能覆盖新内容", 1_760_000_100_000L))
        val freshNotes = listOf(snapshot("新快照", "新的正文，来自后发生的编辑", 1_760_000_200_000L))
        val staleTicket = coordinator.reserve()
            ?: error("coordinator refused to reserve stale ticket")
        val freshTicket = coordinator.reserve()
            ?: error("coordinator refused to reserve fresh ticket")

        // 1) 新快照先经协调器、走真实 Repository 写盘
        val freshSaved = coordinator.save(freshTicket) { repository.save(freshNotes) }
        assertTrue("新快照应当写入成功", freshSaved)

        assertTrue(target.isFile)
        val freshHash = target.sha256Hex()
        assertTrue("磁盘内容应当已经变了", freshHash != baselineHash)
        val freshRaw = target.readText(Charsets.UTF_8)
        assertTrue("磁盘上应是新内容", freshRaw.contains("新快照"))
        assertFalse("磁盘上不该再有基线内容", freshRaw.contains("基线标题"))

        // 2) 再执行旧快照的保存请求：必须被跳过
        var staleWriteRan = false
        val staleSaved = coordinator.save(staleTicket) {
            staleWriteRan = true
            repository.save(staleNotes)
        }

        assertFalse("旧快照必须被判定为过期", staleSaved)
        assertFalse("被跳过的请求不能真的去写盘", staleWriteRan)

        // 3) 磁盘没有被旧快照动过：字节哈希不变，且读回来仍是新内容
        assertEquals("旧请求之后 notes.json 必须逐字节不变", freshHash, target.sha256Hex())
        val rawAfterStale = target.readText(Charsets.UTF_8)
        assertFalse("旧内容不得出现在文件里", rawAfterStale.contains("旧快照"))
        assertEquals(freshRaw, rawAfterStale)

        val reloaded = NotesRepository(context).load()
        assertEquals(1, reloaded.size)
        assertEquals("新快照", reloaded.single().title)
        assertEquals("新的正文，来自后发生的编辑", reloaded.single().content)
        assertEquals(1_760_000_200_000L, reloaded.single().updatedAt)

        // 4) 协调器没有被卡住：之后的新 ticket 仍能正常写盘
        val laterTicket = coordinator.reserve() ?: error("coordinator stuck after skip")
        val laterSaved = coordinator.save(laterTicket) {
            repository.save(listOf(snapshot("再后来", "后续编辑", 1_760_000_300_000L)))
        }
        assertTrue(laterSaved)
        assertEquals("再后来", NotesRepository(context).load().single().title)
        assertEquals("正常路径不应残留 .tmp", emptyList<String>(), filesDir.listTempNotesFiles())
        // 真实 notes.json 的指纹比对在 @After 里做，测试失败时同样执行

        println("[SAVE-ORDER-2] coordinator file test ok: stale skipped, disk kept newest, later save recovered")
    }

    private fun snapshot(title: String, content: String, updatedAt: Long) = Note(
        id = "note-$title",
        title = title,
        content = content,
        createdAt = 1_759_999_000_000L,
        updatedAt = updatedAt,
        checklist = false,
        todos = emptyList()
    )
}
