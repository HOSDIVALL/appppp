package com.example.myapplicationfirstapp.ui

import org.junit.Assert.*
import org.junit.Test

class NotesListScrollbarMathTest {
    @Test fun emptyTrackerFallsBack() { assertEquals(150f, AxisTracker().representativeStridePx(emptyList(), 0), 0f) }
    @Test fun unobservedPrefixUsesFallback() { assertEquals(300f, AxisTracker().distanceBefore(3, 100f), 0f) }
    @Test fun emptyPrefixIsZero() { assertEquals(0f, AxisTracker().distanceBefore(0, 100f), 0f) }
    @Test fun shortTrackHasOnePixelTravel() { assertEquals(1f, scrollbarTravel(80f, 126f), 0f) }
    @Test fun travelSubtractsHandle() { assertEquals(874f, scrollbarTravel(1000f, 126f), 0f) }
    @Test fun touchAndHandleRoundTrip() {
        for (i in 0..10) {
            val p = i / 10f
            assertEquals(p, scrollbarTouchProgress(scrollbarHandleY(p, 874f), 874f), 0.00001f)
        }
        assertEquals(0f, scrollbarTouchProgress(-1f, 874f), 0f)
        assertEquals(1f, scrollbarTouchProgress(900f, 874f), 0f)
    }
    @Test fun endTargetsActualLastSlot() { assertEquals(7, scrollbarTarget(1f, 4, 8)) }
    @Test fun intermediateTargetUsesEstimateAndClamps() {
        assertEquals(2, scrollbarTarget(0.5f, 4, 8))
        assertEquals(1, scrollbarTarget(0.9f, 8, 2))
        assertEquals(0, scrollbarTarget(1f, 8, 0))
    }
    @Test fun smoothingChecksBothThresholdsAndIdleSource() {
        assertTrue(scrollbarSmooth(false, 42f, 27f, 42f, 27f))
        assertFalse(scrollbarSmooth(true, 42f, 27f, 42f, 27f))
        assertFalse(scrollbarSmooth(false, 41f, 27f, 42f, 27f))
        assertFalse(scrollbarSmooth(false, 42f, 26f, 42f, 27f))
    }
    @Test fun glyphPreservesChineseAndMapsLeadingDigits() {
        assertEquals("A", scrollbarGlyph("『alpha」"))
        assertEquals("季", scrollbarGlyph(" 季度"))
        assertEquals("#", scrollbarGlyph("3 月"))
        assertEquals("#", scrollbarGlyph("!"))
    }
    @Test fun bubbleAnimationEndpoints() {
        assertEquals(0.82f, scrollbarScale(0f), 0.00001f)
        assertEquals(1f, scrollbarScale(1f), 0.00001f)
        assertEquals(8f, scrollbarSlide(0f, 8f), 0f)
        assertEquals(0f, scrollbarSlide(1f, 8f), 0f)
    }
}
