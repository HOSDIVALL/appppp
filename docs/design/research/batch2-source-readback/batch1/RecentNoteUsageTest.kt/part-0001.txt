package com.example.myapplicationfirstapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentNoteUsageTest {
    @Test fun orderSurvivesClockRollback() {
        val rows = listOf(RecentNoteUsage("a", 900L, 1L), RecentNoteUsage("b", 100L, 2L))
        assertEquals(listOf("b", "a"), recentNoteIds(rows, setOf("a", "b")))
    }

    @Test fun excludesInactiveAndUnknownNotes() {
        val rows = listOf(RecentNoteUsage("gone", 3L, 3L), RecentNoteUsage("a", 1L, 1L))
        assertEquals(listOf("a"), recentNoteIds(rows, setOf("a")))
    }

    @Test fun deduplicatesBeforeLimiting() {
        val rows = listOf(
            RecentNoteUsage("a", 3L, 3L), RecentNoteUsage("a", 2L, 2L),
            RecentNoteUsage("b", 1L, 1L)
        )
        assertEquals(listOf("a", "b"), recentNoteIds(rows, setOf("a", "b"), 2))
    }

    @Test fun equalOrdersHaveDeterministicIdOrder() {
        val rows = listOf(RecentNoteUsage("b", 1L, 1L), RecentNoteUsage("a", 1L, 1L))
        assertEquals(listOf("a", "b"), recentNoteIds(rows, setOf("a", "b")))
    }

    @Test fun zeroLimitAndEmptyInputStayEmpty() {
        assertEquals(emptyList<String>(), recentNoteIds(emptyList(), emptySet()))
        assertEquals(emptyList<String>(), recentNoteIds(listOf(RecentNoteUsage("a", 1L, 1L)), setOf("a"), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLimitIsRejected() {
        recentNoteIds(emptyList(), emptySet(), -1)
    }

    @Test fun doesNotMutateInput() {
        val rows = mutableListOf(RecentNoteUsage("a", 1L, 1L), RecentNoteUsage("b", 2L, 2L))
        val before = rows.toList()
        recentNoteIds(rows, setOf("a", "b"))
        assertEquals(before, rows)
    }

    // ---- 展示映射：最近使用 → 可展示的笔记 ----

    @Test fun displayKeepsOpenOrderNotUpdatedAtOrder() {
        val old = Note(id = "old", title = "老笔记", updatedAt = 9_000L)
        val fresh = Note(id = "fresh", title = "刚编辑过", updatedAt = 1_000L)
        val rows = listOf(RecentNoteUsage("fresh", 10L, 1L), RecentNoteUsage("old", 20L, 2L))
        // openOrder 2 的 old 排在前：哪怕 fresh 的 updatedAt 更新，也不能拿它冒充历史
        assertEquals(listOf("old", "fresh"), recentNotesForDisplay(rows, listOf(old, fresh)).map { it.id })
    }

    @Test fun displayExcludesTrashedAndUnknownNotes() {
        val active = Note(id = "a", title = "A")
        val trashed = Note(id = "b", title = "B", deletedAt = 1_000L)
        val rows = listOf(
            RecentNoteUsage("b", 3L, 3L),
            RecentNoteUsage("missing", 2L, 2L),
            RecentNoteUsage("a", 1L, 1L)
        )
        assertEquals(listOf("a"), recentNotesForDisplay(rows, listOf(active, trashed)).map { it.id })
    }

    @Test fun displayAppliesLimitAfterFiltering() {
        val notes = (1..8).map { Note(id = "n$it", title = "笔记$it") }
        val rows = (1..8).map { RecentNoteUsage("n$it", it.toLong(), it.toLong()) }
        val shown = recentNotesForDisplay(rows, notes)
        assertEquals(6, shown.size)
        assertEquals(listOf("n8", "n7", "n6", "n5", "n4", "n3"), shown.map { it.id })
    }

    @Test fun displayOfEmptyUsageIsEmpty() {
        assertEquals(emptyList<Note>(), recentNotesForDisplay(emptyList(), listOf(Note(id = "a"))))
    }
}
