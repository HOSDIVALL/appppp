package com.example.myapplicationfirstapp.data

/** Usage metadata only; opening a note must not change its content or updatedAt. */
data class RecentNoteUsage(
    val noteId: String,
    val lastOpenedAt: Long,
    val openOrder: Long
)

/** Filter and deduplicate before applying the limit; never infer usage from updatedAt. */
fun recentNoteIds(
    usages: List<RecentNoteUsage>,
    activeNoteIds: Set<String>,
    limit: Int = 6
): List<String> {
    require(limit >= 0) { "limit must not be negative" }
    return usages.asSequence()
        .filter { it.noteId.isNotBlank() && it.noteId in activeNoteIds }
        .sortedWith(compareByDescending<RecentNoteUsage> { it.openOrder }.thenBy { it.noteId })
        .map { it.noteId }
        .distinct()
        .take(limit)
        .toList()
}
