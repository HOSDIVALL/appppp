package com.example.myapplicationfirstapp.ui



import androidx.compose.runtime.Stable

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateListOf

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import com.example.myapplicationfirstapp.data.AppSettings

import com.example.myapplicationfirstapp.data.InlineAudio

import com.example.myapplicationfirstapp.data.InlineImage

import com.example.myapplicationfirstapp.data.Note

import com.example.myapplicationfirstapp.data.NotesRepository

import com.example.myapplicationfirstapp.data.ReminderScheduler

import com.example.myapplicationfirstapp.data.SortMode

import com.example.myapplicationfirstapp.data.TodoItem

import com.example.myapplicationfirstapp.data.contentToTodos

import com.example.myapplicationfirstapp.data.mediaAnchors

import com.example.myapplicationfirstapp.data.mergeMediaIntoText

import com.example.myapplicationfirstapp.data.repairLegacyMediaLines

import com.example.myapplicationfirstapp.data.todosToContent

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.launch

import android.net.Uri

import java.io.File



/**

 * 列表的快速筛选。标签筛选另有 [NotesState.selectedTag]，

 * 两者是「与」的关系：既符合类型，又带这个标签。

 */

enum class NoteFilter { ALL, TODO, PINNED, REMINDER }



/**

 * 笔记列表的「唯一数据源」。

 *

 * 界面只读这里的数据、只通过这里的方法改动数据，

 * 每次改动都会自动写回文件（[persist]），所以不需要手点保存。

 */

@Stable

class NotesState(

    private val repository: NotesRepository,

    private val settings: AppSettings,

    private val scope: CoroutineScope,

    private val scheduler: ReminderScheduler

) {

    private val _notes = mutableStateListOf<Note>()



    /** 全部笔记，包含回收站里的 */

    val notes: List<Note> get() = _notes



    var query by mutableStateOf("")

        private set



    /** null 表示「全部」标签 */

    var selectedTag by mutableStateOf<String?>(null)

        private set



    /** 类型快筛：全部 / 待办 / 置顶 / 提醒 */

    var filter by mutableStateOf(NoteFilter.ALL)

        private set



    var sortMode by mutableStateOf(settings.sortMode)

        private set



    var loaded by mutableStateOf(false)

        private set



    private val activeNotes: List<Note>

        get() = _notes.filter { it.deletedAt == null }



    /** 所有用过的标签，按拼音/字典序排 */

    val allTags: List<String>

        get() = activeNotes.flatMap { it.tags }.distinct().sorted()



    /** 回收站里的笔记，最近删除的在前 */

    val trashedNotes: List<Note>

        get() = _notes.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }



    /** 列表页真正展示的内容：过滤 + 搜索 + 排序 + 置顶优先 */

    val visibleNotes: List<Note>

        get() {

            val keyword = query.trim()

            val tag = selectedTag?.takeIf { it in allTags }



            val filtered = activeNotes.filter { note ->

                (tag == null || tag in note.tags) &&

                    when (filter) {

                        NoteFilter.ALL -> true

                        NoteFilter.TODO -> note.checklist

                        NoteFilter.PINNED -> note.pinned

                        NoteFilter.REMINDER -> note.remindAt != null

                    } &&

                    (keyword.isEmpty() || note.searchText.contains(keyword, true))

            }

            return sorted(filtered)

        }



    suspend fun load() {

        val saved = repository.load()



        // 启动时顺手清理超过 30 天的回收站内容

        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MILLIS

        val kept = saved.filter { note ->

            note.deletedAt?.let { it > cutoff } ?: true

        }



        _notes.clear()

        _notes.addAll(kept)



        // 顺手清掉历史数据里的空待办行、以及正文里残留的「- [ ] ![图片](x)」

        val cleaned = kept.map { note ->

            var fixed = note

            if (fixed.checklist &&

                fixed.todos.any { it.text.isBlank() } &&

                fixed.todos.any { it.text.isNotBlank() }

            ) {

                fixed = fixed.copy(todos = fixed.todos.filter { it.text.isNotBlank() })

            }

            if (!fixed.checklist) {

                val repaired = repairLegacyMediaLines(fixed.content)

                if (repaired != fixed.content) fixed = fixed.copy(content = repaired)

            }

            fixed

        }

        if (cleaned != kept) {

            _notes.clear()

            _notes.addAll(cleaned)

            persist()

        }



        if (kept.size != saved.size) {

            // 顺手把过期笔记的图片文件也删掉，别让垃圾文件留在手机上

            val purged = saved - kept.toSet()

            repository.deleteAttachments(purged.flatMap(::allImageNames))

            persist()

        }

        loaded = true



        // 启动时把还没到点的提醒重新排一遍：

        // 重装、重启、系统清理过后，闹钟都不会自己回来，这里补上

        val now = System.currentTimeMillis()

        cleaned.filter { it.deletedAt == null && (it.remindAt ?: 0) > now }

            .forEach(scheduler::schedule)

    }



    fun search(keyword: String) {

        query = keyword

    }



    fun selectTag(tag: String?) {

        selectedTag = tag

    }



    fun selectFilter(value: NoteFilter) {

        filter = value

    }



    fun changeSortMode(mode: SortMode) {

        sortMode = mode

        settings.sortMode = mode

    }



    fun findNote(id: String?): Note? = id?.let { noteId -> _notes.firstOrNull { it.id == noteId } }



    /**

     * 新建一条空笔记并返回它，调用方拿到 id 后直接跳到编辑页。

     * [checklist] 为 true 时建的是待办清单，自带一个空行，进去就能敲第一件事。

     */

    fun createNote(checklist: Boolean = false): Note {

        val now = System.currentTimeMillis()

        val note = Note(

            createdAt = now,

            updatedAt = now,

            colorIndex = activeNotes.size % 3,

            checklist = checklist,

            todos = if (checklist) listOf(TodoItem()) else emptyList()

        )

        _notes.add(0, note)

        persist()

        return note

    }



    fun updateNote(id: String, title: String, content: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val old = _notes[index]

        // 内容没变就不写文件，避免每次重组都触发一次磁盘写入

        if (old.title == title && old.content == content) return



        _notes[index] = old.copy(

            title = title,

            content = content,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    // ============ 待办清单 ============



    /**

     * 在「文本 / 清单」之间切换，并把已有内容带过去：

     * - 转清单：正文按行拆成待办（`- [x]` 前缀的勾选状态会保留）

     * - 转文本：待办写回成 `- [ ] / - [x]` 行，再切回清单时状态不丢

     */

    fun setChecklist(id: String, enabled: Boolean) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (note.checklist == enabled) return



        _notes[index] = if (enabled) {

            val converted = contentToTodos(note.content)

            note.copy(

                checklist = true,

                // 正文留着不删：清单模式下它不显示，但里面的图片/录音标记要留着，

                // 切回文本时才能把媒体按原位插回去

                todos = converted.ifEmpty { listOf(TodoItem()) },

                updatedAt = System.currentTimeMillis()

            )

        } else {

            note.copy(

                checklist = false,

                content = mergeMediaIntoText(

                    text = todosToContent(note.todos),

                    anchors = mediaAnchors(note.content)

                ),

                todos = emptyList(),

                updatedAt = System.currentTimeMillis()

            )

        }

        persist()

    }



    fun toggleTodo(id: String, todoId: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        val todoIndex = note.todos.indexOfFirst { it.id == todoId }

        if (todoIndex < 0) return



        val updated = note.todos.toMutableList()

        updated[todoIndex] = updated[todoIndex].let { it.copy(done = !it.done) }

        _notes[index] = note.copy(todos = updated, updatedAt = System.currentTimeMillis())

        persist()

    }



    fun updateTodoText(id: String, todoId: String, text: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        val todoIndex = note.todos.indexOfFirst { it.id == todoId }

        if (todoIndex < 0) return

        if (note.todos[todoIndex].text == text) return



        val updated = note.todos.toMutableList()

        updated[todoIndex] = updated[todoIndex].copy(text = text)

        _notes[index] = note.copy(todos = updated, updatedAt = System.currentTimeMillis())

        persist()

    }



    /**

     * 新增一项待办。[afterId] 指定插在哪一项后面（回车续行），

     * 为空则追加到末尾。返回新项，方便界面把焦点移过去。

     */

    fun addTodo(id: String, text: String = "", afterId: String? = null): TodoItem? {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return null



        val note = _notes[index]

        val item = TodoItem(text = text)

        val updated = note.todos.toMutableList()

        val at = afterId?.let { anchor ->

            updated.indexOfFirst { it.id == anchor }.let { if (it < 0) updated.size else it + 1 }

        } ?: updated.size

        updated.add(at, item)



        _notes[index] = note.copy(todos = updated, updatedAt = System.currentTimeMillis())

        persist()

        return item

    }



    fun removeTodo(id: String, todoId: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (note.todos.none { it.id == todoId }) return



        _notes[index] = note.copy(

            todos = note.todos.filterNot { it.id == todoId },

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    /** 一键清掉已完成项，返回清掉的条数 */

    fun clearCompletedTodos(id: String): Int {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return 0



        val note = _notes[index]

        val remaining = note.todos.filterNot { it.done }

        val removed = note.todos.size - remaining.size

        if (removed <= 0) return 0



        _notes[index] = note.copy(todos = remaining, updatedAt = System.currentTimeMillis())

        persist()

        return removed

    }



    /**

     * 清掉空白的待办行。离开编辑页时调用：

     * 点「添加待办」加了一行却没写，不该在清单里留一个空壳。

     * 整篇一条都没写时不动，方便下次进来接着写。

     */

    fun pruneBlankTodos(id: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (!note.checklist) return



        val kept = note.todos.filter { it.text.isNotBlank() }

        if (kept.isEmpty() || kept.size == note.todos.size) return



        _notes[index] = note.copy(todos = kept)

        persist()

    }



    fun togglePin(id: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        _notes[index] = _notes[index].let { it.copy(pinned = !it.pinned) }

        persist()

    }



    fun addTag(id: String, tag: String) {

        val clean = tag.trim().removePrefix("#")

        if (clean.isEmpty()) return



        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (clean in note.tags) return



        _notes[index] = note.copy(tags = note.tags + clean, updatedAt = System.currentTimeMillis())

        persist()

    }



    fun removeTag(id: String, tag: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (tag !in note.tags) return



        _notes[index] = note.copy(

            tags = note.tags - tag,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    // ============ 回收站 ============



    /** 移到回收站（软删除），返回被移动的笔记供撤销使用 */

    fun moveToTrash(id: String): Note? {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return null



        // 进了回收站就别再响了，不然删掉的笔记还会弹提醒

        scheduler.cancel(id)



        val trashed = _notes[index].copy(deletedAt = System.currentTimeMillis())

        _notes[index] = trashed

        persist()

        return trashed

    }



    fun restoreFromTrash(id: String): Note? {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return null



        val restored = _notes[index].copy(deletedAt = null)

        _notes[index] = restored

        if ((restored.remindAt ?: 0) > System.currentTimeMillis()) scheduler.schedule(restored)

        persist()

        return restored

    }



    fun deleteForever(id: String): Note? {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return null



        scheduler.cancel(id)

        val removed = _notes.removeAt(index)

        repository.deleteAttachments(allImageNames(removed))

        persist()

        return removed

    }



    fun emptyTrash(): Int {

        val trashed = _notes.filter { it.deletedAt != null }

        if (trashed.isEmpty()) return 0



        trashed.forEach { scheduler.cancel(it.id) }

        _notes.removeAll { it.deletedAt != null }

        repository.deleteAttachments(trashed.flatMap(::allImageNames))

        persist()

        return trashed.size

    }



    // ============ 提醒 ============



    /**

     * 设置或取消提醒。

     * 先撤掉旧闹钟再排新的：同一篇笔记连改三次时间，也不能留下三个闹钟。

     */

    fun setReminder(id: String, at: Long?) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (note.remindAt == at) return



        scheduler.cancel(id)



        _notes[index] = note.copy(remindAt = at, updatedAt = System.currentTimeMillis())

        val updated = _notes[index]

        if (at != null && at > System.currentTimeMillis()) scheduler.schedule(updated)

        persist()

    }



    // ============ 图片附件 ============



    fun imageFile(name: String): File = repository.attachmentFile(name)



    /**

     * 从相册导入一张图，压缩后挂到笔记上。

     * @return 生成的文件名，失败返回 null

     */

    suspend fun importImage(id: String, uri: Uri): String? {

        val name = repository.importImage(uri) ?: return null

        addImage(id, name)

        return name

    }



    /**

     * 相机拍完回来：先压缩（相机存的是原图），再挂到笔记上。

     * @return 是否成功；失败说明相机没真正写入文件，调用方应把它清掉

     */

    suspend fun attachCameraImage(id: String, name: String): Boolean {

        val file = repository.attachmentFile(name)

        if (!file.exists() || file.length() == 0L) return false



        repository.compressAttachment(name)

        addImage(id, name)

        return true

    }



    fun addImage(id: String, name: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (name in note.images) return



        _notes[index] = note.copy(

            images = note.images + name,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    fun removeImage(id: String, name: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (name !in note.images) return



        // 正文里如果还有这个标记，一起清掉，否则预览会留一个空白占位

        val cleanedContent = InlineImage.PATTERN.replace(note.content) { match ->

            if (match.groupValues[1] == name) "" else match.value

        }



        _notes[index] = note.copy(

            content = cleanedContent,

            images = note.images - name,

            updatedAt = System.currentTimeMillis()

        )

        repository.deleteAttachment(name)

        persist()

    }



    /** 拍照前先占一个文件名，相机 App 直接把照片写进这个文件 */

    fun prepareCameraTarget(): Pair<String, Uri>? = repository.createCameraTarget()



    /** 拍照取消或失败时，删掉那个空文件 */

    fun discardImageFile(name: String) {

        repository.deleteAttachment(name)

    }



    // ============ 录音附件 ============



    /**

     * 把一段录音挂到笔记上（只存 id 引用，不复制音频文件）。

     * 同一段录音在一篇笔记里只挂一次。

     */

    fun addAudio(id: String, recordingId: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (recordingId in note.audio) return



        _notes[index] = note.copy(

            audio = note.audio + recordingId,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    /** Update body and its audio index in one state mutation and persistence request. */
    fun removeInlineAudioReference(
        id: String,
        recordingId: String,
        title: String,
        content: String
    ) {
        val index = _notes.indexOfFirst { it.id == id }
        if (index < 0) return
        val note = _notes[index]
        val stillReferenced = recordingId in InlineAudio.idsIn(content)
        _notes[index] = note.copy(
            title = title,
            content = content,
            audio = if (stillReferenced) note.audio else note.audio - recordingId,
            updatedAt = System.currentTimeMillis()
        )
        persist()
    }

    fun removeAudio(id: String, recordingId: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        if (recordingId !in note.audio) return



        _notes[index] = note.copy(

            audio = note.audio - recordingId,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    /**

     * 从录音页「添加到笔记」→「插到正文」。

     *

     * 既把录音记进 [Note.audio]（这样删笔记时能顺着清文件），

     * 也在正文末尾补一个 `!audio(id)` 标记——编辑页会把它渲染成可播放的卡片。

     */

    fun addAudioToBody(id: String, recordingId: String) {

        val index = _notes.indexOfFirst { it.id == id }

        if (index < 0) return



        val note = _notes[index]

        // 清单笔记没有正文（内容在 todos 里），插正文等于插进黑洞，直接当附件挂上

        if (note.checklist) {

            addAudio(id, recordingId)

            return

        }

        if (InlineAudio.idsIn(note.content).contains(recordingId)) return



        val marker = InlineAudio.inline(recordingId)

        val body = note.content.trimEnd()



        _notes[index] = note.copy(

            content = if (body.isEmpty()) marker else "$body\n\n$marker",

            audio = if (recordingId in note.audio) note.audio else note.audio + recordingId,

            updatedAt = System.currentTimeMillis()

        )

        persist()

    }



    // ============ 备份 ============



    /** 导出全部笔记（含回收站）和图片，成功返回条数，失败返回 -1 */

    suspend fun exportBackup(uri: Uri): Int {

        val snapshot = _notes.toList()

        return if (repository.exportBackup(uri, snapshot)) snapshot.size else -1

    }



    /**

     * 用备份覆盖当前数据。

     *

     * 选择「覆盖」而不是「合并」：合并时同 id 的笔记谁新谁旧、

     * 图片文件名撞车都很难解释清楚，覆盖的行为最可预期。

     * @return 导入的条数，失败返回 -1

     */

    suspend fun importBackup(uri: Uri): Int {

        val imported = repository.importBackup(uri) ?: return -1



        // 先把备份里没有的旧图片删掉，再整体替换笔记

        repository.pruneAttachments(imported.flatMap(::allImageNames).toSet())



        _notes.clear()

        _notes.addAll(imported)

        persist()

        return imported.size

    }



    /** 一条笔记引用到的所有图片：附件列表 + 正文里的标记，去重 */

    private fun allImageNames(note: Note): List<String> =

        (note.images + note.inlineImages).distinct()



    private fun sorted(list: List<Note>): List<Note> {

        val pinnedFirst = compareByDescending<Note> { it.pinned }

        return when (sortMode) {

            SortMode.UPDATED -> list.sortedWith(pinnedFirst.thenByDescending { it.updatedAt })

            SortMode.CREATED -> list.sortedWith(pinnedFirst.thenByDescending { it.createdAt })

            SortMode.TITLE -> list.sortedWith(

                pinnedFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }

            )

        }

    }



    private fun persist() {

        val snapshot = _notes.toList()

        scope.launch { repository.save(snapshot) }

    }



    private companion object {

        /** 回收站保留 30 天 */

        const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

    }

}



