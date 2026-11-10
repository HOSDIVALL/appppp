package com.example.myapplicationfirstapp.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 待办清单里的一项。
 *
 * [id] 单独存在而不是用下标：改文字、增删项时行的身份不会串，
 * 列表页直接勾选也不会因为重排点到隔壁那一条。
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val done: Boolean = false
)

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
    /** 附件图片的文件名，实际文件在 filesDir/attachments/ 下 */
    val images: List<String> = emptyList(),
    /**
     * 附件录音的 id（即录音文件名，见 Recording.id）。
     * 只存引用不复制文件；录音被删时卡片降级成「录音已删除」，笔记本身不受影响。
     */
    val audio: List<String> = emptyList(),
    val deletedAt: Long? = null,
    val checklist: Boolean = false,
    val todos: List<TodoItem> = emptyList(),
    /** 提醒时间戳；null 表示没设提醒。到点由系统的闹钟叫醒，不靠 App 常驻后台 */
    val remindAt: Long? = null
) {
    val displayTitle: String
        get() = title.ifBlank { "无标题" }

    /** 列表卡片上的一句话摘要：去掉正文里的图片标记，避免露出一串文件名 */
    val preview: String
        get() = when {
            checklist && todos.isNotEmpty() ->
                todos.joinToString(" · ") { it.text.trim() }.trim()

            else -> stripMedia(content)
                .replace('\n', ' ')
                .trim()
                .ifBlank {
                    when {
                        images.isNotEmpty() && audio.isNotEmpty() ->
                            "${images.size} 张图片 · ${audio.size} 段录音"

                        images.isNotEmpty() -> "${images.size} 张图片"
                        audio.isNotEmpty() -> "${audio.size} 段录音"
                        else -> "暂无内容"
                    }
                }
        }

    /** 正文里用 `![图片](文件名)` 引用到的图片 */
    val inlineImages: List<String>
        get() = InlineImage.namesIn(content)

    /** 正文里用 `!audio(id)` 引用到的录音 */
    val inlineAudio: List<String>
        get() = InlineAudio.idsIn(content)

    val isTrashed: Boolean
        get() = deletedAt != null

    /** 清单完成情况，给卡片上的进度条用 */
    val doneCount: Int
        get() = todos.count { it.done }

    val hasTodos: Boolean
        get() = checklist && todos.isNotEmpty()

    /** 搜索时要匹配的全部文字：标题、正文、清单项（媒体标记不参与） */
    val searchText: String
        get() = if (checklist) {
            "$title\n${todos.joinToString("\n") { it.text }}"
        } else {
            "$title\n${stripMedia(content)}"
        }

    /** 提醒已经过点了（通知发过、或错过后一直没处理） */
    val reminderOverdue: Boolean
        get() = (remindAt ?: Long.MAX_VALUE) <= System.currentTimeMillis()
}

// ============ 正文 ↔ 清单 互转 ============

/**
 * 把正文按行拆成待办项。
 *
 * 已经写成 `- [ ]` / `- [x]` 的行会保留勾选状态，
 * 其余非空行一律当成未完成项——这样「把一段笔记切成清单」不用重新抄一遍。
 */
fun contentToTodos(content: String): List<TodoItem> =
    content.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || isMediaOnly(trimmed)) return@mapNotNull null

            // 行内夹着图片/录音标记时先摘掉，免得变成「- [ ] ![图片](xxx)」这种待办
            val cleaned = stripMedia(trimmed).replace('\n', ' ').trim()
            if (cleaned.isEmpty()) return@mapNotNull null

            val match = MARKER_PATTERN.find(cleaned)
            if (match != null) {
                TodoItem(
                    text = match.groupValues[2].trim(),
                    done = match.groupValues[1].equals("x", ignoreCase = true)
                )
            } else {
                TodoItem(text = cleaned)
            }
        }
        .filter { it.text.isNotEmpty() }

/**
 * 清单转回正文：写成 `- [x] 已做` / `- [ ] 未做`。
 * 再用 [contentToTodos] 读回来时勾选状态不丢，所以来回切换模式是安全的。
 */
fun todosToContent(todos: List<TodoItem>): String =
    todos
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { todo ->
            if (todo.done) "- [x] ${todo.text.trim()}" else "- [ ] ${todo.text.trim()}"
        }

private val MARKER_PATTERN = Regex("""^[-*+]\s*\[([ xX])\]\s*(.*)$""")

// ============ 与 JSON 互转（用于本地保存） ============

fun Note.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("content", content)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("colorIndex", colorIndex)
    put("pinned", pinned)
    put("tags", JSONArray(tags))
    put("images", JSONArray(images))
    if (audio.isNotEmpty()) put("audio", JSONArray(audio))
    if (deletedAt != null) put("deletedAt", deletedAt)
    if (checklist) put("checklist", true)
    if (remindAt != null) put("remindAt", remindAt)
    if (todos.isNotEmpty()) {
        put(
            "todos",
            JSONArray().apply {
                todos.forEach { todo ->
                    put(
                        JSONObject().apply {
                            put("id", todo.id)
                            put("text", todo.text)
                            put("done", todo.done)
                        }
                    )
                }
            }
        )
    }
}

fun noteFromJson(json: JSONObject): Note = Note(
    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
    title = json.optString("title"),
    content = json.optString("content"),
    // 老版本文件没有 createdAt，用 updatedAt 顶上
    createdAt = json.optLong("createdAt", json.optLong("updatedAt", System.currentTimeMillis())),
    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
    colorIndex = json.optInt("colorIndex", 0),
    pinned = json.optBoolean("pinned", false),
    tags = json.optJSONArray("tags")?.let { array ->
        (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() }
    } ?: emptyList(),
    images = json.optJSONArray("images")?.let { array ->
        (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() }
    } ?: emptyList(),
    // 老笔记没有 audio 字段，读成空列表即可
    audio = json.optJSONArray("audio")?.let { array ->
        (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() }
    } ?: emptyList(),
    deletedAt = if (json.has("deletedAt")) json.optLong("deletedAt") else null,
    remindAt = if (json.has("remindAt")) json.optLong("remindAt") else null,
    checklist = json.optBoolean("checklist", false),
    todos = json.optJSONArray("todos")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            TodoItem(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                text = item.optString("text"),
                done = item.optBoolean("done", false)
            )
        }
    } ?: emptyList()
)

fun notesToJson(notes: List<Note>): String =
    JSONArray().apply { notes.forEach { put(it.toJson()) } }.toString()

/** 解析失败时返回空列表，而不是让 App 崩溃 */
fun notesFromJson(text: String): List<Note> = runCatching {
    val array = JSONArray(text)
    (0 until array.length()).map { noteFromJson(array.getJSONObject(it)) }
}.getOrDefault(emptyList())
