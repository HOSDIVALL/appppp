from pathlib import Path
import subprocess

base = '5c688af4d30f01f8939319cad47b2393a8ebaa99'
root = Path('app/src/main/java/com/example/myapplicationfirstapp/ui')
source = root / 'NotesListScrollbar.kt'
test = Path('app/src/test/java/com/example/myapplicationfirstapp/ui/NotesListScrollbarMathTest.kt')
helper = root / 'ScrollbarMath.kt'
def require(condition, message):
    if not condition:
        raise SystemExit(message)

def git(*args):
    return subprocess.check_output(['git', *args])

require(git('rev-parse', 'HEAD').decode().strip() == base, 'Wrong baseline')
require(subprocess.run(['git', 'symbolic-ref', '-q', 'HEAD'], capture_output=True).returncode == 1, 'Use a detached worktree')
for path in (source, test):
    require(path.read_bytes().replace(b'\r\n', b'\n') == git('show', 'HEAD:' + path.as_posix()).replace(b'\r\n', b'\n'), f'{path}: modified before migration')
require(not helper.exists() and not (root / 'ScrollbarAxisMath.kt').exists(), 'Do not stack extractions')
raw = source.read_bytes()
s = raw.decode('utf-8').replace('\r\n', '\n')
def replace(old, new):
    global s
    require(s.count(old) == 1, f'{source}: unexpected anchor {old[:90]!r}')
    s = s.replace(old, new, 1)

replace('private class AxisTracker {', 'internal class AxisTracker {')
marker = '\ninternal class AxisTracker {\n'
head, tail = s.split(marker)
require(tail.rstrip().endswith('fun itemSizePx(index: Int, stridePx: Float): Float = stridePx\n}'), 'Unexpected tracker boundary')
s = head
replace('import androidx.compose.foundation.lazy.LazyListItemInfo\n', '') if s.count('LazyListItemInfo') == 1 else None
replace('(availableHeight - handleHeightPx).coerceAtLeast(1f)', 'scrollbarTravel(availableHeight, handleHeightPx)')
replace('(targetHandleTop / stats.scrollableHeight).coerceIn(0f, 1f)', 'scrollbarTouchProgress(targetHandleTop, stats.scrollableHeight)')
replace('displayProgress * stats.scrollableHeight', 'scrollbarHandleY(displayProgress, stats.scrollableHeight)')
replace('''if (newProgress >= 1f) {
                (displayNotes.size - 1).coerceAtLeast(0)
            } else {
                (newProgress * stats.maxScrollIndex).toInt().coerceIn(0, (displayNotes.size - 1).coerceAtLeast(0))
            }''', 'scrollbarTarget(newProgress, stats.maxScrollIndex, displayNotes.size)')
replace('''!sourceIsScrolling &&
                            estimatedStepPx >= coarseJumpThresholdPx &&
                            handleDeltaPx >= smoothJumpMinDistancePx''', 'scrollbarSmooth(sourceIsScrolling, estimatedStepPx, handleDeltaPx, coarseJumpThresholdPx, smoothJumpMinDistancePx)')
replace('''val text = it.displayTitle.trim()
            val first = text.firstOrNull { c -> c.isLetterOrDigit() } ?: '#'
            when {
                first.isDigit() -> "#"
                first in 'a'..'z' -> first.uppercaseChar().toString()
                first in 'A'..'Z' -> first.toString()
                else -> first.toString()
            }''', 'scrollbarGlyph(it.displayTitle)')
replace('scaleX = 0.82f + 0.18f * bubbleAnim', 'scaleX = scrollbarScale(bubbleAnim)')
replace('scaleY = 0.82f + 0.18f * bubbleAnim', 'scaleY = scrollbarScale(bubbleAnim)')
replace('translationX = (1f - bubbleAnim) * with(density) { 8.dp.toPx() }', 'translationX = scrollbarSlide(bubbleAnim, with(density) { 8.dp.toPx() })')
math = '''package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.lazy.LazyListItemInfo

internal fun scrollbarTravel(height: Float, handle: Float) = (height - handle).coerceAtLeast(1f)
internal fun scrollbarTouchProgress(top: Float, travel: Float) = (top / travel).coerceIn(0f, 1f)
internal fun scrollbarHandleY(progress: Float, travel: Float) = progress * travel
internal fun scrollbarTarget(progress: Float, max: Int, count: Int): Int {
    val last = (count - 1).coerceAtLeast(0)
    return if (progress >= 1f) last else (progress * max).toInt().coerceIn(0, last)
}
internal fun scrollbarSmooth(scrolling: Boolean, step: Float, delta: Float, minStep: Float, minDelta: Float) =
    !scrolling && step >= minStep && delta >= minDelta
internal fun scrollbarGlyph(title: String): String {
    val first = title.trim().firstOrNull { it.isLetterOrDigit() } ?: '#'
    return when {
        first.isDigit() -> "#"
        first in 'a'..'z' -> first.uppercaseChar().toString()
        else -> first.toString()
    }
}
internal fun scrollbarScale(progress: Float) = 0.82f + 0.18f * progress
internal fun scrollbarSlide(progress: Float, slide: Float) = (1f - progress) * slide
'''
tests = '''package com.example.myapplicationfirstapp.ui

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
'''
ending = '\r\n' if b'\r\n' in raw else '\n'
source.write_bytes((s.rstrip() + '\n').replace('\n', ending).encode('utf-8'))
helper.write_bytes((math + marker + tail).encode('utf-8'))
test.write_bytes(tests.encode('utf-8'))
print('Scrollbar migration applied; tests not run.')
