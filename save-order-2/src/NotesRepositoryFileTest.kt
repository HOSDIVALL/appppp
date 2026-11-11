package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SAVE-ORDER-2 / 真实文件：整条 save() → notes.json → load() 链路。
 *
 * 说明清楚边界：
 * - 用的是**真实文件系统**（沙箱目录里的真 notes.json），不是内存字符串；
 * - 「重新读取」用的是**新的 NotesRepository 实例**，不是进程重启，
 *   所以本测试证明的是「写进文件、再从文件读回」，不证明进程被杀后的恢复行为。
 */
@RunWith(AndroidJUnit4::class)
class NotesRepositoryFileTest {

    @Test
    fun repositorySaveReloadRoundTrip() = runBlocking {
        val realBefore = realNotesFingerprint()
        val context = isolatedStorage("round-trip")
        val filesDir = context.getFilesDir()
        val target = File(filesDir, "notes.json")
        assertFalse("沙箱里本不该有旧文件", target.exists())

        val note = Note(
            id = "note-round-trip-1",
            title = "周会要点",
            content = "1. 保存链路需要串行\n2. 图片引用也要一起存\n![图片](img_1001.jpg)",
            createdAt = 1_760_000_000_000L,
            updatedAt = 1_760_000_900_000L,
            colorIndex = 3,
            pinned = true,
            tags = listOf("工作", "复盘"),
            images = listOf("img_1001.jpg", "img_1002.jpg"),
            checklist = true,
            todos = listOf(
                TodoItem(id = "todo-1", text = "确认写盘顺序", done = true),
                TodoItem(id = "todo-2", text = "补失败提示", done = false),
                TodoItem(id = "todo-3", text = "回归图片引用", done = true)
            ),
            remindAt = 1_760_003_600_000L
        )

        NotesRepository(context).save(listOf(note))

        // ---- 磁盘上真的有这个文件，且内容是完整 JSON ----
        assertTrue("notes.json 应已落盘", target.isFile)
        assertTrue("文件不应为空", target.length() > 0L)

        val raw = target.readText(Charsets.UTF_8)
        val array = JSONArray(raw)
        assertEquals(1, array.length())
        val json = array.getJSONObject(0)
        assertEquals("note-round-trip-1", json.getString("id"))
        assertEquals("周会要点", json.getString("title"))
        assertEquals(note.content, json.getString("content"))
        assertEquals(1_760_000_000_000L, json.getLong("createdAt"))
        assertEquals(1_760_000_900_000L, json.getLong("updatedAt"))
        assertEquals(3, json.getInt("colorIndex"))
        assertTrue(json.getBoolean("pinned"))
        assertTrue(json.getBoolean("checklist"))
        assertEquals(1_760_003_600_000L, json.getLong("remindAt"))
        assertFalse("deletedAt 为 null 时不应写出该字段", json.has("deletedAt"))
        assertTrue("tags 必须写进文件", json.has("tags"))
        assertTrue("images 必须写进文件", json.has("images"))
        assertTrue("todos 必须写进文件", json.has("todos"))

        val rawImages = json.getJSONArray("images")
        assertEquals(2, rawImages.length())
        assertEquals("img_1001.jpg", rawImages.getString(0))
        assertEquals("img_1002.jpg", rawImages.getString(1))

        val rawTodos = json.getJSONArray("todos")
        assertEquals(3, rawTodos.length())
        assertEquals("todo-2", rawTodos.getJSONObject(1).getString("id"))
        assertEquals("补失败提示", rawTodos.getJSONObject(1).getString("text"))
        assertFalse("第二项未勾选", rawTodos.getJSONObject(1).getBoolean("done"))
        assertTrue("第三项已勾选", rawTodos.getJSONObject(2).getBoolean("done"))

        // ---- 新实例读回（不是同一个 repository 的内存缓存，也不是进程重启） ----
        val reloaded = NotesRepository(context).load()

        assertEquals(1, reloaded.size)
        val back = reloaded.single()
        assertEquals(note.id, back.id)
        assertEquals(note.title, back.title)
        assertEquals(note.content, back.content)
        assertEquals(note.createdAt, back.createdAt)
        assertEquals(note.updatedAt, back.updatedAt)
        assertEquals(note.colorIndex, back.colorIndex)
        assertEquals(note.pinned, back.pinned)
        assertEquals(note.tags, back.tags)
        assertEquals(note.images, back.images)
        assertEquals(note.checklist, back.checklist)
        assertEquals(note.remindAt, back.remindAt)
        assertEquals(note.deletedAt, back.deletedAt)
        assertEquals("待办的 id / 文本 / 勾选状态都要原样回来", note.todos, back.todos)
        assertEquals(note, back)

        // 正文里的图片引用仍然能被解析出来
        assertEquals(listOf("img_1001.jpg"), back.inlineImages)

        // ---- 原子写的临时文件必须被清理 ----
        assertEquals("正常路径不应残留 .tmp", emptyList<String>(), filesDir.listTempNotesFiles())

        // ---- 真实笔记文件一个字节都没被碰过 ----
        assertEquals("沙箱测试不得改动真实 notes.json", realBefore, realNotesFingerprint())

        println("[SAVE-ORDER-2] round-trip ok: ${back.title}, ${back.todos.count { it.done }}/${back.todos.size} done")
    }
}
