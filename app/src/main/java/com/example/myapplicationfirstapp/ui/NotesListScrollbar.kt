package com.example.myapplicationfirstapp.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * 100% 严格对齐 Rhythm 原版 ExpressiveScrollBar.kt 源码实现：
 * - 轨道：在手柄上下各留 gap (8dp)，上方从 0 到 handleY-gap，下方从 handleY+height+gap 到总高，两端 StrokeCap.Round
 * - 手柄形状：左侧伸出大半圆 (indicatorWidth / 2)，右侧保持小圆角 (6dp) 的非对称胶囊！
 * - 手柄内部：展开时淡入 Material unfold_more (上下双箭头图标)
 * - 气泡：手柄左侧 42dp 的 40×40dp 圆球，单字居中，绝不遮挡正文！
 */
@Composable
fun NotesListScrollbar(
    listState: LazyListState,
    displayNotes: List<Note?>,
    sortMode: SortMode,
    strings: AppStrings,
    contentKey: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 48.dp,
    thickness: Dp = 8.dp,
    indicatorExpandedWidth: Dp = 24.dp,
    indicatorExpandedWidthBoost: Dp = 4.dp,
    indicatorRightCornerRadius: Dp = 6.dp,
    paddingEnd: Dp = 4.dp,
    trackGap: Dp = 8.dp
) {
    val canScrollForward by remember(listState) { derivedStateOf { listState.canScrollForward } }
    val canScrollBackward by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val canScroll = canScrollForward || canScrollBackward

    val tracker = remember(listState) { AxisTracker() }
    val expandedIndicatorWidth = indicatorExpandedWidth + indicatorExpandedWidthBoost

    BoxWithConstraints(modifier = modifier) {
        if (!canScroll) return@BoxWithConstraints

        var isPressed by remember(listState) { mutableStateOf(false) }
        var isDragging by remember(listState) { mutableStateOf(false) }
        var dragProgress by remember(listState) { mutableFloatStateOf(-1f) }
        var pendingScrollIndex by remember(listState) { mutableIntStateOf(-1) }
        val displayedProgress = remember(listState) { Animatable(0f) }
        var hasSyncedDisplayedProgress by remember(listState) { mutableStateOf(false) }

        val primaryColor = MaterialTheme.colorScheme.primary
        val trackBgColor = MaterialTheme.colorScheme.secondaryContainer
        val indicatorRightCornerRadiusPx = with(LocalDensity.current) { indicatorRightCornerRadius.toPx() }

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

        val density = LocalDensity.current
        val constraintsMaxHeight = maxHeight
        val coarseJumpThresholdPx = with(density) { 16.dp.toPx() }
        val smoothJumpMinDistancePx = with(density) { 10.dp.toPx() }

        fun getScrollStats(): ScrollStats {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItemsCount = layoutInfo.totalItemsCount

            if (visibleItems.isEmpty() || totalItemsCount == 0) {
                return ScrollStats(0f, 1, 1f)
            }

            tracker.resetIfNeeded(totalItemsCount, layoutInfo.mainAxisItemSpacing)
            tracker.observe(visibleItems, layoutInfo.mainAxisItemSpacing)

            val viewportHeightPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val itemStridePx = tracker.representativeStridePx(visibleItems, layoutInfo.mainAxisItemSpacing)
            val estimatedVisibleItems = (viewportHeightPx / itemStridePx).coerceAtLeast(1f)
            val lastItemIndex = (totalItemsCount - 1).coerceAtLeast(0)

            val currentScrollPx = (tracker.distanceBefore(listState.firstVisibleItemIndex, itemStridePx) + listState.firstVisibleItemScrollOffset).coerceAtLeast(0f)
            val totalScrollableContentPx = (
                layoutInfo.beforeContentPadding + layoutInfo.afterContentPadding
            ).toFloat() + (
                tracker.distanceBefore(lastItemIndex, itemStridePx) + tracker.itemSizePx(lastItemIndex, itemStridePx) - viewportHeightPx
            ).coerceAtLeast(1f)

            val approxMaxIndex = (totalItemsCount - estimatedVisibleItems).toInt().coerceAtLeast(1)

            val boundedScrollPx = currentScrollPx.coerceIn(0f, totalScrollableContentPx)
            val realProgress = if (!listState.canScrollForward) 1f
            else if (!listState.canScrollBackward) 0f
            else (boundedScrollPx / totalScrollableContentPx).coerceIn(0f, 0.999f)

            val availableHeight = with(density) { constraintsMaxHeight.toPx() }
            val handleHeightPx = with(density) { minHeight.toPx() }
            val scrollableHeight = (availableHeight - handleHeightPx).coerceAtLeast(1f)

            return ScrollStats(realProgress, approxMaxIndex, scrollableHeight)
        }

        fun updateProgressFromTouch(touchY: Float, grabOffset: Float) {
            val stats = getScrollStats()
            val targetHandleTop = touchY - grabOffset
            val newProgress = (targetHandleTop / stats.scrollableHeight).coerceIn(0f, 1f)
            dragProgress = newProgress
            pendingScrollIndex = if (newProgress >= 1f) {
                (displayNotes.size - 1).coerceAtLeast(0)
            } else {
                (newProgress * stats.maxScrollIndex).toInt().coerceIn(0, (displayNotes.size - 1).coerceAtLeast(0))
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow { pendingScrollIndex }
                .distinctUntilChanged()
                .collectLatest { index ->
                    if (index >= 0) {
                        listState.scrollToItem(index)
                    }
                }
        }

        LaunchedEffect(listState, constraintsMaxHeight, minHeight, isDragging) {
            if (isDragging) return@LaunchedEffect

            snapshotFlow { getScrollStats() }
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
                        val shouldSmoothJump = !sourceIsScrolling &&
                            estimatedStepPx >= coarseJumpThresholdPx &&
                            handleDeltaPx >= smoothJumpMinDistancePx

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

        LaunchedEffect(isDragging, dragProgress) {
            if (isDragging && dragProgress >= 0f) {
                displayedProgress.snapTo(dragProgress)
                hasSyncedDisplayedProgress = true
            }
        }

        val indicatorPath = remember { Path() }

        val handleHeightPx = with(density) { minHeight.toPx() }
        val visualProgress = displayedProgress.value
        val displayProgress = if (isDragging && dragProgress >= 0f) dragProgress else visualProgress
        val stats = getScrollStats()
        val handleY = displayProgress * stats.scrollableHeight

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(expandedIndicatorWidth + paddingEnd)
                .pointerInput(Unit) {
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
                .pointerInput(Unit) {
                    var grabOffset = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val isTouchOnHandle = offset.y >= handleY && offset.y <= (handleY + handleHeightPx)
                            if (isTouchOnHandle) {
                                grabOffset = offset.y - handleY
                                dragProgress = visualProgress
                                pendingScrollIndex = listState.firstVisibleItemIndex
                            } else {
                                grabOffset = handleHeightPx / 2f
                                updateProgressFromTouch(offset.y, grabOffset)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            dragProgress = -1f
                            pendingScrollIndex = -1
                        },
                        onDragCancel = {
                            isDragging = false
                            dragProgress = -1f
                            pendingScrollIndex = -1
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            updateProgressFromTouch(change.position.y, grabOffset)
                        }
                    )
                }
        ) {
            val rightAnchorX = with(density) { (expandedIndicatorWidth).toPx() }
            val trackX = rightAnchorX - with(density) { thickness.toPx() / 2f }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackStrokeWidth = thickness.toPx()
                val indicatorWidthPx = animatedWidth.toPx()
                val gapPx = trackGap.toPx()
                // Rhythm 核心手柄几何：左侧是大半圆，右侧是小圆角！
                val indicatorLeftCornerRadius = indicatorWidthPx / 2f
                val maxAllowedRightCornerRadius = minOf(indicatorWidthPx / 2f, handleHeightPx / 2f)
                val resolvedRightCornerRadius = indicatorRightCornerRadiusPx.coerceIn(0f, maxAllowedRightCornerRadius)
                val currentIndicatorX = rightAnchorX - indicatorWidthPx

                // 1. 上方淡色轨道（与手柄隔开 8dp gap，绝对不接触）
                if (handleY > gapPx) {
                    drawLine(
                        color = trackBgColor,
                        start = Offset(trackX, 0f),
                        end = Offset(trackX, handleY - gapPx),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                // 2. 下方淡色轨道（与手柄隔开 8dp gap，绝对不接触）
                if (handleY + handleHeightPx + gapPx < size.height) {
                    drawLine(
                        color = trackBgColor,
                        start = Offset(trackX, handleY + handleHeightPx + gapPx),
                        end = Offset(trackX, size.height),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                // 3. 非对称胶囊手柄（左大圆角向外伸出，右小圆角靠外边）
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

            // 4. 手柄内部上下箭头图标（展开交互时渐入淡显）
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

        // 5. 对齐 v4 原型规范的 40×40dp 圆形定位气泡
        val activeTargetIndex = if (pendingScrollIndex >= 0) pendingScrollIndex else listState.firstVisibleItemIndex
        val targetNote = displayNotes.getOrNull(activeTargetIndex.coerceIn(0, (displayNotes.size - 1).coerceAtLeast(0)))
        val glyph = targetNote?.let {
            val text = it.displayTitle.trim()
            val first = text.firstOrNull { c -> c.isLetterOrDigit() } ?: '#'
            when {
                first.isDigit() -> "#"
                first in 'a'..'z' -> first.uppercaseChar().toString()
                first in 'A'..'Z' -> first.toString()
                else -> first.toString()
            }
        }.orEmpty()

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
                        y = with(density) { (handleY + (handleHeightPx - 40.dp.toPx()) / 2f).toDp() }
                    )
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = bubbleAnim
                        scaleX = 0.82f + 0.18f * bubbleAnim
                        scaleY = 0.82f + 0.18f * bubbleAnim
                        translationX = (1f - bubbleAnim) * with(density) { 8.dp.toPx() }
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

private class AxisTracker {
    private val strides = HashMap<Int, Float>()
    private var totalItems = -1
    private var spacing = -1

    fun resetIfNeeded(total: Int, itemSpacing: Int) {
        if (total != totalItems || itemSpacing != spacing) {
            strides.clear()
            totalItems = total
            spacing = itemSpacing
        }
    }

    fun observe(visibleItems: List<LazyListItemInfo>, itemSpacing: Int) {
        visibleItems.zipWithNext().forEach { (curr, next) ->
            if (next.index == curr.index + 1) {
                strides[curr.index] = (next.offset - curr.offset).toFloat()
            }
        }
    }

    fun representativeStridePx(visibleItems: List<LazyListItemInfo>, itemSpacing: Int): Float {
        val observed = strides.values.toList()
        if (observed.isNotEmpty()) {
            return observed.sorted()[observed.size / 2]
        }
        return visibleItems.firstOrNull()?.let { (it.size + itemSpacing).toFloat() } ?: 150f
    }

    fun distanceBefore(index: Int, stridePx: Float): Float {
        var sum = 0f
        for (i in 0 until index) {
            sum += strides[i] ?: stridePx
        }
        return sum
    }

    fun itemSizePx(index: Int, stridePx: Float): Float = stridePx
}
