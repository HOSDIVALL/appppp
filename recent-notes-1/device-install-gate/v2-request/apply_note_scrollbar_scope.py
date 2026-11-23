#!/usr/bin/env python3
from pathlib import Path
import subprocess

BASE = "5c688af4d30f01f8939319cad47b2393a8ebaa99"
UI = Path("app/src/main/java/com/example/myapplicationfirstapp/ui")
SCREEN = UI / "NotesListScreen.kt"
SCROLLBAR = UI / "NotesListScrollbar.kt"


def once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected source anchor ({text.count(old)} matches): {old[:120]}")
    return text.replace(old, new, 1)


def read_norm(path: Path) -> tuple[str, str]:
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    return raw.decode("utf-8").replace("\r\n", "\n"), newline


def write_norm(path: Path, text: str, newline: str) -> None:
    path.write_bytes(text.replace("\n", newline).encode("utf-8"))


SCROLLBAR_KT = """package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 右侧笔记列表滚动条（限位版）：
 * - 轨道和触摸热区仅覆盖当前可见笔记行，排除轮播、摘要、搜索、筛选、底部导航与尾部留白
 * - 拖动只定位当前结果中的实际笔记行，首末端不跳到轮播或分区标题；无可滚动笔记时隐藏
 * - 拖动开始时固定有效行程并用手指增量更新，避免轨道随列表滚动跳动；列表模型变化时重置状态
 */
@Composable
fun NotesListScrollbar(
    listState: LazyListState,
    displayNotes: List<Note?>,
    sortMode: SortMode,
    strings: AppStrings,
    contentKey: Int,
    modifier: Modifier = Modifier,
    trackTopPx: Float = 0f,
    trackBottomPx: Float = Float.NaN,
    minHeight: Dp = 48.dp,
    thickness: Dp = 8.dp,
    indicatorExpandedWidth: Dp = 24.dp,
    indicatorExpandedWidthBoost: Dp = 4.dp,
    indicatorRightCornerRadius: Dp = 6.dp,
    paddingEnd: Dp = 4.dp,
    trackGap: Dp = 8.dp
) {
    val noteIndices = remember(displayNotes) {
        displayNotes.indices.filter { displayNotes[it] != null }
    }
    val hasScrollableNotes by remember(listState, noteIndices) {
        derivedStateOf {
            if (noteIndices.size < 2) return@derivedStateOf false
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf false
            val firstIdx = noteIndices.first()
            val lastIdx = noteIndices.last()
            val firstItem = visible.firstOrNull { it.index == firstIdx }
            val lastItem = visible.lastOrNull { it.index == lastIdx }
            val firstFullyVisible = firstItem != null && firstItem.offset >= info.viewportStartOffset
            val lastFullyVisible = lastItem != null && (lastItem.offset + lastItem.size) <= info.viewportEndOffset
            !(firstFullyVisible && lastFullyVisible)
        }
    }

    val tracker = remember(listState, contentKey) { AxisTracker() }
    val expandedIndicatorWidth = indicatorExpandedWidth + indicatorExpandedWidthBoost

    BoxWithConstraints(modifier = modifier) {
        var isPressed by remember(listState, contentKey) { mutableStateOf(false) }
        var isDragging by remember(listState, contentKey) { mutableStateOf(false) }
        var dragProgress by remember(listState, contentKey) { mutableFloatStateOf(-1f) }
        var pendingScrollIndex by remember(listState, contentKey) { mutableIntStateOf(-1) }
        var frozenTrackTopPx by remember(listState, contentKey) { mutableFloatStateOf(0f) }
        var frozenTrackHeightPx by remember(listState, contentKey) { mutableFloatStateOf(0f) }
        var frozenTravelPx by remember(listState, contentKey) { mutableFloatStateOf(1f) }
        val displayedProgress = remember(listState, contentKey) { Animatable(0f) }
        var hasSyncedDisplayedProgress by remember(listState, contentKey) { mutableStateOf(false) }

        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val handleHeightPx = with(density) { minHeight.toPx() }
        val resolvedTopPx = if (trackBottomPx.isNaN()) 0f else trackTopPx.coerceIn(0f, fullHeightPx)
        val resolvedBottomPx = if (trackBottomPx.isNaN()) fullHeightPx else trackBottomPx.coerceIn(resolvedTopPx, fullHeightPx)
        val liveTrackHeightPx = (resolvedBottomPx - resolvedTopPx).coerceAtLeast(0f)

        val latestResolvedTopPx by rememberUpdatedState(resolvedTopPx)
        val latestLiveTrackHeightPx by rememberUpdatedState(liveTrackHeightPx)
        val latestNoteIndices by rememberUpdatedState(noteIndices)

        val activeTopPx = if (isDragging && frozenTrackHeightPx > 0f) frozenTrackTopPx else resolvedTopPx
        val activeTrackHeightPx = if (isDragging && frozenTrackHeightPx > 0f) frozenTrackHeightPx else liveTrackHeightPx

        if (!isDragging && (!hasScrollableNotes || liveTrackHeightPx < handleHeightPx)) {
            return@BoxWithConstraints
        }
        if (noteIndices.isEmpty() || activeTrackHeightPx < 1f) {
            return@BoxWithConstraints
        }

        val primaryColor = MaterialTheme.colorScheme.primary
        val trackBgColor = MaterialTheme.colorScheme.secondaryContainer
        val indicatorRightCornerRadiusPx = with(density) { indicatorRightCornerRadius.toPx() }

        val isInteracting = isPressed || isDragging

        val animatedWidth by animateDpAsState(
            targetValue = if (isInteracting) expandedIndicatorWidth else thickness,
            animationSpec = tween(durationMillis = 200),
            label = "WidthAnimation"
        )

        val iconAlpha by animateFloatAsState(
            targetValue = if (isInteracting) 1f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "IconAlpha"
        )

        val coarseJumpThresholdPx = with(density) { 16.dp.toPx() }
        val smoothJumpMinDistancePx = with(density) { 10.dp.toPx() }

        fun targetNoteIndexForProgress(progress: Float): Int {
            val indices = latestNoteIndices
            if (indices.isEmpty()) return -1
            val slot = scrollbarTarget(progress, (indices.size - 1).coerceAtLeast(1), indices.size)
            return indices[slot]
        }

        fun getScrollStats(trackHeightPx: Float): ScrollStats {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItemsCount = layoutInfo.totalItemsCount

            if (visibleItems.isEmpty() || totalItemsCount == 0 || noteIndices.isEmpty()) {
                return ScrollStats(0f, 1, scrollbarTravel(trackHeightPx, handleHeightPx))
            }

            tracker.resetIfNeeded(totalItemsCount, layoutInfo.mainAxisItemSpacing)
            tracker.observe(visibleItems, layoutInfo.mainAxisItemSpacing)

            val firstNoteIndex = noteIndices.first()
            val lastNoteIndex = noteIndices.last()
            val viewportHeightPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val itemStridePx = tracker.representativeStridePx(visibleItems, layoutInfo.mainAxisItemSpacing)

            val noteStartPx = tracker.distanceBefore(firstNoteIndex, itemStridePx)
            val noteEndPx = tracker.distanceBefore(lastNoteIndex, itemStridePx) +
                tracker.itemSizePx(lastNoteIndex, itemStridePx)
            val noteContentSpanPx = (noteEndPx - noteStartPx).coerceAtLeast(1f)
            val noteScrollablePx = (noteContentSpanPx - viewportHeightPx).coerceAtLeast(1f)
            val currentScrollPx = tracker.distanceBefore(listState.firstVisibleItemIndex, itemStridePx) +
                listState.firstVisibleItemScrollOffset
            val boundedNoteScrollPx = (currentScrollPx - noteStartPx).coerceIn(0f, noteScrollablePx)

            val lastVisibleNote = visibleItems.lastOrNull { it.index == lastNoteIndex }
            val atNoteStart = listState.firstVisibleItemIndex < firstNoteIndex ||
                (listState.firstVisibleItemIndex == firstNoteIndex && listState.firstVisibleItemScrollOffset == 0)
            val atNoteEnd = !listState.canScrollForward ||
                (lastVisibleNote != null && lastVisibleNote.offset + lastVisibleNote.size <= layoutInfo.viewportEndOffset)

            val realProgress = when {
                atNoteStart -> 0f
                atNoteEnd -> 1f
                else -> (boundedNoteScrollPx / noteScrollablePx).coerceIn(0f, 0.999f)
            }

            val maxNoteStep = (noteIndices.size - 1).coerceAtLeast(1)
            val scrollableHeight = scrollbarTravel(trackHeightPx, handleHeightPx)
            return ScrollStats(realProgress, maxNoteStep, scrollableHeight)
        }

        LaunchedEffect(listState, contentKey) {
            snapshotFlow { pendingScrollIndex }
                .distinctUntilChanged()
                .collectLatest { index ->
                    if (index >= 0) {
                        listState.scrollToItem(index)
                    }
                }
        }

        LaunchedEffect(listState, contentKey, activeTrackHeightPx, minHeight, isDragging) {
            if (isDragging) return@LaunchedEffect

            snapshotFlow { getScrollStats(activeTrackHeightPx) }
                .distinctUntilChanged()
                .collectLatest { stats ->
                    val targetProgress = stats.progress
                    if (!hasSyncedDisplayedProgress) {
                        displayedProgress.snapTo(targetProgress)
                        hasSyncedDisplayedProgress = true
                    } else {
                        val sourceIsScrolling = listState.isScrollInProgress
                        val handleDeltaPx = abs(targetProgress - displayedProgress.value) * stats.scrollableHeight
                        val estimatedStepPx = stats.scrollableHeight / stats.maxScrollIndex.coerceAtLeast(1).toFloat()
                        val shouldSmoothJump = scrollbarSmooth(
                            sourceIsScrolling,
                            estimatedStepPx,
                            handleDeltaPx,
                            coarseJumpThresholdPx,
                            smoothJumpMinDistancePx
                        )

                        if (shouldSmoothJump) {
                            displayedProgress.animateTo(
                                targetValue = targetProgress,
                                animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing)
                            )
                        } else {
                            displayedProgress.snapTo(targetProgress)
                        }
                    }
                }
        }

        LaunchedEffect(isDragging, dragProgress, contentKey) {
            if (isDragging && dragProgress >= 0f) {
                displayedProgress.snapTo(dragProgress)
                hasSyncedDisplayedProgress = true
            }
        }

        val indicatorPath = remember { Path() }
        val visualProgress = displayedProgress.value
        val displayProgress = if (isDragging && dragProgress >= 0f) dragProgress else visualProgress
        val travelPx = if (isDragging && frozenTrackHeightPx > 0f) {
            frozenTravelPx
        } else {
            scrollbarTravel(activeTrackHeightPx, handleHeightPx)
        }
        val handleY = scrollbarHandleY(displayProgress, travelPx)

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, activeTopPx.roundToInt()) }
                .height(with(density) { activeTrackHeightPx.toDp() })
                .width(expandedIndicatorWidth + paddingEnd)
                .pointerInput(contentKey) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            try {
                                awaitRelease()
                            } finally {
                                isPressed = false
                            }
                        }
                    )
                }
                .pointerInput(contentKey) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val lockedHeight = latestLiveTrackHeightPx.coerceAtLeast(handleHeightPx)
                            val lockedTravel = scrollbarTravel(lockedHeight, handleHeightPx)
                            frozenTrackTopPx = latestResolvedTopPx
                            frozenTrackHeightPx = lockedHeight
                            frozenTravelPx = lockedTravel
                            isDragging = true
                            val currentVisual = displayedProgress.value
                            val currentHandleY = scrollbarHandleY(currentVisual, lockedTravel)
                            val isTouchOnHandle = offset.y >= currentHandleY && offset.y <= (currentHandleY + handleHeightPx)
                            val startProgress = if (isTouchOnHandle) {
                                currentVisual
                            } else {
                                scrollbarTouchProgress(offset.y - handleHeightPx / 2f, lockedTravel)
                            }
                            dragProgress = startProgress
                            pendingScrollIndex = targetNoteIndexForProgress(startProgress)
                        },
                        onDragEnd = {
                            isDragging = false
                            dragProgress = -1f
                            pendingScrollIndex = -1
                            frozenTrackHeightPx = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            dragProgress = -1f
                            pendingScrollIndex = -1
                            frozenTrackHeightPx = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val lockedTravel = frozenTravelPx.coerceAtLeast(1f)
                            val baseProgress = if (dragProgress >= 0f) dragProgress else displayedProgress.value
                            val nextProgress = (baseProgress + dragAmount.y / lockedTravel).coerceIn(0f, 1f)
                            dragProgress = nextProgress
                            pendingScrollIndex = targetNoteIndexForProgress(nextProgress)
                        }
                    )
                }
        ) {
            val rightAnchorX = with(density) { expandedIndicatorWidth.toPx() }
            val trackX = rightAnchorX - with(density) { thickness.toPx() / 2f }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackStrokeWidth = thickness.toPx()
                val indicatorWidthPx = animatedWidth.toPx()
                val gapPx = trackGap.toPx()
                val indicatorLeftCornerRadius = indicatorWidthPx / 2f
                val maxAllowedRightCornerRadius = minOf(indicatorWidthPx / 2f, handleHeightPx / 2f)
                val resolvedRightCornerRadius = indicatorRightCornerRadiusPx.coerceIn(0f, maxAllowedRightCornerRadius)
                val currentIndicatorX = rightAnchorX - indicatorWidthPx

                if (handleY > gapPx) {
                    drawLine(
                        color = trackBgColor,
                        start = Offset(trackX, 0f),
                        end = Offset(trackX, handleY - gapPx),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                if (handleY + handleHeightPx + gapPx < size.height) {
                    drawLine(
                        color = trackBgColor,
                        start = Offset(trackX, handleY + handleHeightPx + gapPx),
                        end = Offset(trackX, size.height),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                indicatorPath.rewind()
                indicatorPath.addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(currentIndicatorX, handleY),
                            size = Size(indicatorWidthPx, handleHeightPx)
                        ),
                        topLeft = CornerRadius(indicatorLeftCornerRadius),
                        bottomLeft = CornerRadius(indicatorLeftCornerRadius),
                        topRight = CornerRadius(resolvedRightCornerRadius),
                        bottomRight = CornerRadius(resolvedRightCornerRadius)
                    )
                )
                drawPath(indicatorPath, color = primaryColor)
            }

            if (iconAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { (rightAnchorX - animatedWidth.toPx()).toDp() },
                            y = with(density) { handleY.toDp() }
                        )
                        .size(width = animatedWidth, height = minHeight)
                        .graphicsLayer { alpha = iconAlpha },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        val activeTargetIndex = if (pendingScrollIndex >= 0) {
            pendingScrollIndex
        } else {
            noteIndices.firstOrNull { it >= listState.firstVisibleItemIndex } ?: noteIndices.last()
        }
        val targetNote = displayNotes.getOrNull(activeTargetIndex)
        val glyph = targetNote?.let { scrollbarGlyph(it.displayTitle) }.orEmpty()

        val showBubble = isDragging && glyph.isNotEmpty()
        val bubbleAnim by animateFloatAsState(
            targetValue = if (showBubble) 1f else 0f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "bubbleAnim"
        )

        if (bubbleAnim > 0.01f) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = -(expandedIndicatorWidth + paddingEnd + 10.dp),
                        y = with(density) { (activeTopPx + handleY + (handleHeightPx - 40.dp.toPx()) / 2f).toDp() }
                    )
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = bubbleAnim
                        scaleX = scrollbarScale(bubbleAnim)
                        scaleY = scrollbarScale(bubbleAnim)
                        translationX = scrollbarSlide(bubbleAnim, with(density) { 8.dp.toPx() })
                    }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = glyph,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private data class ScrollStats(
    val progress: Float,
    val maxScrollIndex: Int,
    val scrollableHeight: Float
)
"""


def main() -> None:
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != BASE:
        raise SystemExit(f"Wrong HEAD ({head}); expected pinned base {BASE}")

    screen_text, screen_nl = read_norm(SCREEN)
    scrollbar_text, scrollbar_nl = read_norm(SCROLLBAR)

    if "buildNotesHomeListModel(" not in screen_text:
        raise SystemExit("Apply candidate-full.diff before running apply_note_scrollbar_scope.py")
    if "scrollbarTravel(" not in scrollbar_text:
        raise SystemExit("Apply candidate-full.diff (including scrollbar migration) before running this script")

    old_call = """            NotesListScrollbar(
                listState = listState,
                displayNotes = displayNotes,
                sortMode = state.sortMode,
                strings = s,
                contentKey = listDataKey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxSize()
                    .padding(bottom = ListVisualTokens.listBottomReserve)
            )"""

    new_call = """            val noteTrackBounds by remember(listState, displayNotes) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
                    if (viewportHeight <= 0) return@derivedStateOf 0f to 0f
                    var minTop = Int.MAX_VALUE
                    var maxBottom = Int.MIN_VALUE
                    for (item in info.visibleItemsInfo) {
                        if (displayNotes.getOrNull(item.index) != null) {
                            val top = (item.offset - info.viewportStartOffset).coerceIn(0, viewportHeight)
                            val bottom = (item.offset + item.size - info.viewportStartOffset).coerceIn(0, viewportHeight)
                            if (bottom > top) {
                                if (top < minTop) minTop = top
                                if (bottom > maxBottom) maxBottom = bottom
                            }
                        }
                    }
                    if (minTop >= maxBottom) 0f to 0f else minTop.toFloat() to maxBottom.toFloat()
                }
            }
            NotesListScrollbar(
                listState = listState,
                displayNotes = displayNotes,
                sortMode = state.sortMode,
                strings = s,
                contentKey = listDataKey,
                trackTopPx = noteTrackBounds.first,
                trackBottomPx = noteTrackBounds.second,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxSize()
                    .padding(bottom = ListVisualTokens.listBottomReserve)
            )"""

    screen_updated = once(screen_text, old_call, new_call)
    write_norm(SCREEN, screen_updated, screen_nl)
    write_norm(SCROLLBAR, SCROLLBAR_KT, scrollbar_nl)
    subprocess.run(["git", "diff", "--stat", "--", str(SCREEN), str(SCROLLBAR)], check=True)


if __name__ == "__main__":
    main()
