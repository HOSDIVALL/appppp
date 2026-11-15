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
}
