package com.example.myapplicationfirstapp.ui

/** A single ordered model drives both LazyColumn entries and scrollbar note slots. */
internal enum class NotesHomeItemKind {
    CAROUSEL, SUMMARY, SEARCH, FILTERS, EMPTY, PINNED_HEADER, RECENT_HEADER, NOTE
}

internal data class NotesHomeItem<T : Any>(
    val key: String,
    val kind: NotesHomeItemKind,
    val note: T? = null,
    val indexInGroup: Int = 0,
    val groupSize: Int = 0,
    val startsUnsectionedGroup: Boolean = false
)

internal data class NotesHomeListModel<T : Any>(val items: List<NotesHomeItem<T>>) {
    val displayNotes: List<T?> get() = items.map { it.note }
}

internal fun <T : Any> buildNotesHomeListModel(
    visibleNotes: List<T>,
    isFiltering: Boolean,
    hasRecentNotes: Boolean,
    idOf: (T) -> String,
    isPinned: (T) -> Boolean
): NotesHomeListModel<T> {
    val notes = visibleNotes.toList()
    val ids = notes.map(idOf)
    require(ids.distinct().size == ids.size) { "Note IDs must be unique" }
    val rows = mutableListOf<NotesHomeItem<T>>()
    fun header(key: String, kind: NotesHomeItemKind) {
        rows += NotesHomeItem<T>(key = "home:$key", kind = kind)
    }
    fun group(group: List<T>, sectioned: Boolean) {
        group.forEachIndexed { index, note ->
            rows += NotesHomeItem(
                key = "note:${idOf(note)}",
                kind = NotesHomeItemKind.NOTE,
                note = note,
                indexInGroup = index,
                groupSize = group.size,
                startsUnsectionedGroup = !sectioned && index == 0
            )
        }
    }
    if (hasRecentNotes && !isFiltering && notes.isNotEmpty()) {
        header("carousel", NotesHomeItemKind.CAROUSEL)
    }
    header("summary", NotesHomeItemKind.SUMMARY)
    header("search", NotesHomeItemKind.SEARCH)
    header("filters", NotesHomeItemKind.FILTERS)
    val pinned = notes.filter(isPinned)
    val regular = notes.filterNot(isPinned)
    when {
        notes.isEmpty() -> header("empty", NotesHomeItemKind.EMPTY)
        !isFiltering && pinned.isNotEmpty() && regular.isNotEmpty() -> {
            header("pinned", NotesHomeItemKind.PINNED_HEADER)
            group(pinned, sectioned = true)
            header("recent", NotesHomeItemKind.RECENT_HEADER)
            group(regular, sectioned = true)
        }
        else -> group(notes, sectioned = false)
    }
    return NotesHomeListModel(rows.toList())
}

/** Values are window dp, never an inset list/container width or an orientation flag. */
internal data class RecentNotesLayoutSpec(
    val tablet: Boolean,
    val itemWidthDp: Float,
    val heightDp: Float,
    val horizontalPaddingDp: Float,
    val topPaddingDp: Float,
    val bottomPaddingDp: Float
)

internal fun recentNotesLayoutSpec(
    windowWidthDp: Float,
    windowHeightDp: Float
): RecentNotesLayoutSpec {
    require(windowWidthDp.isFinite() && windowWidthDp > 0f)
    require(windowHeightDp.isFinite() && windowHeightDp > 0f)
    val compactHeight = windowHeightDp < 480f
    return if (windowWidthDp < 600f) {
        RecentNotesLayoutSpec(
            tablet = false,
            itemWidthDp = windowWidthDp,
            heightDp = if (compactHeight) 280f else windowWidthDp,
            horizontalPaddingDp = 0f,
            topPaddingDp = 0f,
            bottomPaddingDp = 0f
        )
    } else {
        val expandedWidth = windowWidthDp >= 840f
        RecentNotesLayoutSpec(
            tablet = true,
            itemWidthDp = if (expandedWidth) 360f else 280f,
            heightDp = if (compactHeight) 240f else 280f,
            horizontalPaddingDp = if (expandedWidth) 32f else 16f,
            topPaddingDp = 32f,
            bottomPaddingDp = 8f
        )
    }
}

/** Resolve the selected ID in the new order; only fall back to an index if it disappeared. */
internal fun recentNotesIndexAfterChange(
    noteIds: List<String>,
    selectedNoteId: String?,
    previousIndex: Int
): Int {
    require(noteIds.distinct().size == noteIds.size) { "Carousel IDs must be unique" }
    if (noteIds.isEmpty()) return 0
    val retainedIndex = selectedNoteId?.let(noteIds::indexOf) ?: -1
    return if (retainedIndex >= 0) retainedIndex else previousIndex.coerceIn(0, noteIds.lastIndex)
}
