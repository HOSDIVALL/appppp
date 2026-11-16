package com.example.myapplicationfirstapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesHomeListModelTest {
    private data class Item(val id: String, val pinned: Boolean = false)
    private fun model(
        notes: List<Item> = emptyList(),
        filtering: Boolean = false,
        recent: Boolean = false
    ) = buildNotesHomeListModel(notes, filtering, recent, Item::id, Item::pinned)

    @Test fun emptyModelHasOnlyRealHeaderAndEmptySlots() {
        val result = model(recent = true)
        assertEquals(listOf(NotesHomeItemKind.SUMMARY, NotesHomeItemKind.SEARCH,
            NotesHomeItemKind.FILTERS, NotesHomeItemKind.EMPTY), result.items.map { it.kind })
        assertEquals(listOf<Item?>(null, null, null, null), result.displayNotes)
    }

    @Test fun noHistoryDoesNotReserveACarouselSlot() {
        assertEquals(NotesHomeItemKind.SUMMARY, model(listOf(Item("a"))).items.first().kind)
    }

    @Test fun historyAddsExactlyOneLeadingCarouselSlot() {
        val rows = model(listOf(Item("a")), recent = true).items
        assertEquals(NotesHomeItemKind.CAROUSEL, rows.first().kind)
        assertEquals(1, rows.count { it.kind == NotesHomeItemKind.CAROUSEL })
    }

    @Test fun filteringHidesCarouselWithoutAnIndexHole() {
        val result = model(listOf(Item("a")), filtering = true, recent = true)
        assertFalse(result.items.any { it.kind == NotesHomeItemKind.CAROUSEL })
        assertEquals(3, result.displayNotes.indexOfFirst { it != null })
    }

    @Test fun filteredOrderIsPreservedWithoutSectionHeaders() {
        val input = listOf(Item("regular"), Item("pinned", true))
        val result = model(input, filtering = true)
        assertEquals(input, result.displayNotes.filterNotNull())
        assertFalse(result.items.any { it.kind == NotesHomeItemKind.PINNED_HEADER ||
            it.kind == NotesHomeItemKind.RECENT_HEADER })
    }

    @Test fun unfilteredMixedNotesFormTwoIndependentGroups() {
        val rows = model(listOf(Item("r"), Item("p1", true), Item("p2", true))).items
        val notes = rows.filter { it.kind == NotesHomeItemKind.NOTE }
        assertEquals(listOf("p1", "p2", "r"), notes.map { it.note!!.id })
        assertEquals(listOf(0, 1, 0), notes.map { it.indexInGroup })
        assertEquals(listOf(2, 2, 1), notes.map { it.groupSize })
        assertTrue(notes.none { it.startsUnsectionedGroup })
        assertEquals(1, rows.count { it.kind == NotesHomeItemKind.PINNED_HEADER })
        assertEquals(1, rows.count { it.kind == NotesHomeItemKind.RECENT_HEADER })
    }

    @Test fun aSinglePinnedGroupHasNoPhantomSectionHeaders() {
        val result = model(listOf(Item("p", true)))
        val row = result.items.last()
        assertTrue(row.startsUnsectionedGroup)
        assertEquals(1, row.groupSize)
        assertEquals(4, result.items.size)
    }

    @Test fun noteKeysCannotCollideWithHeaderKeys() {
        val rows = model(listOf(Item("home:filters"), Item("home:summary")), recent = true).items
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test fun scrollbarSlotsAlignOneToOneAndRetainOriginalObjects() {
        val a = Item("a", true)
        val b = Item("b")
        val result = model(listOf(a, b), recent = true)
        assertEquals(result.items.size, result.displayNotes.size)
        result.items.forEachIndexed { index, row ->
            if (row.kind == NotesHomeItemKind.NOTE) assertSame(row.note, result.displayNotes[index])
            else assertEquals(null, result.displayNotes[index])
        }
        assertSame(a, result.displayNotes.filterNotNull()[0])
        assertSame(b, result.displayNotes.filterNotNull()[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateNoteIdsAreRejectedBeforeLazyKeysAreBuilt() {
        model(listOf(Item("a"), Item("a")))
    }

    @Test fun phoneItemWidthUsesWindowWidth() {
        val spec = recentNotesLayoutSpec(393f, 852f)
        assertFalse(spec.tablet)
        assertEquals(393f, spec.itemWidthDp, 0f)
        assertEquals(393f, spec.heightDp, 0f)
        assertEquals(0f, spec.horizontalPaddingDp, 0f)
    }

    @Test fun compactHeightPhoneUses280() {
        val spec = recentNotesLayoutSpec(500f, 400f)
        assertFalse(spec.tablet)
        assertEquals(500f, spec.itemWidthDp, 0f)
        assertEquals(280f, spec.heightDp, 0f)
    }

    @Test fun mediumWidthTabletUses280And16() {
        val spec = recentNotesLayoutSpec(700f, 1000f)
        assertTrue(spec.tablet)
        assertEquals(280f, spec.itemWidthDp, 0f)
        assertEquals(280f, spec.heightDp, 0f)
        assertEquals(16f, spec.horizontalPaddingDp, 0f)
        assertEquals(32f, spec.topPaddingDp, 0f)
        assertEquals(8f, spec.bottomPaddingDp, 0f)
    }

    @Test fun expandedWidthTabletUses360And32() {
        val spec = recentNotesLayoutSpec(1000f, 1200f)
        assertEquals(360f, spec.itemWidthDp, 0f)
        assertEquals(32f, spec.horizontalPaddingDp, 0f)
    }

    @Test fun compactHeightTabletUses240IndependentlyOfWidth() {
        val spec = recentNotesLayoutSpec(1000f, 400f)
        assertTrue(spec.tablet)
        assertEquals(360f, spec.itemWidthDp, 0f)
        assertEquals(240f, spec.heightDp, 0f)
    }

    @Test fun mediumWidthAndHeightBoundariesAreInclusive() {
        val spec = recentNotesLayoutSpec(600f, 480f)
        assertTrue(spec.tablet)
        assertEquals(280f, spec.itemWidthDp, 0f)
        assertEquals(280f, spec.heightDp, 0f)
    }

    @Test fun expandedWidthBoundaryIsInclusive() {
        assertEquals(360f, recentNotesLayoutSpec(840f, 900f).itemWidthDp, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteWindowSizeIsRejected() {
        recentNotesLayoutSpec(Float.NaN, 800f)
    }

    @Test fun reorderingRetainsSelectedNoteId() {
        assertEquals(0, recentNotesIndexAfterChange(listOf("b", "a", "c"), "b", 1))
    }

    @Test fun deletedSelectionFallsBackToNearestValidIndex() {
        assertEquals(1, recentNotesIndexAfterChange(listOf("a", "c"), "b", 8))
    }

    @Test fun emptyCarouselHasASafeInitialIndex() {
        assertEquals(0, recentNotesIndexAfterChange(emptyList(), "b", 8))
    }

    @Test fun retainedIdWinsOverAStaleOutOfRangeIndex() {
        assertEquals(1, recentNotesIndexAfterChange(listOf("a", "b"), "b", 100))
    }

    @Test fun missingIdAndNegativeIndexClampToZero() {
        assertEquals(0, recentNotesIndexAfterChange(listOf("a", "b"), null, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCarouselIdsAreRejected() {
        recentNotesIndexAfterChange(listOf("a", "a"), "a", 0)
    }
}
