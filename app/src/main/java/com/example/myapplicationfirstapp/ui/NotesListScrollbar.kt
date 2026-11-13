package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滚动条的位置估算（对齐 Rhythm ExpressiveScrollBarMetrics 语义）。
 */
object ScrollbarEstimate {

    data class Item(val index: Int, val offsetPx: Int, val sizePx: Int)

    fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toFloat()
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    fun stepsOf(items: List<Item>): List<Int> =
        items.sortedBy { it.index }
            .zipWithNext { a, b -> b.offsetPx - a.offsetPx }
            .filter { it > 0 }

    class Estimator {
        private val observedSteps = HashMap<Int, Int>()
        private var representativeStepPx = 0f
        private var totalCount = -1
        private var contentKey = Int.MIN_VALUE

        fun update(items: List<Item>, totalItems: Int, contentKey: Int) {
            if (totalItems != totalCount || contentKey != this.contentKey) {
                observedSteps.clear()
                representativeStepPx = 0f
            }
            totalCount = totalItems
            this.contentKey = contentKey

            val sorted = items.sortedBy { it.index }
            val steps = stepsOf(sorted)
            if (steps.isNotEmpty()) {
                representativeStepPx = median(steps)
            } else if (sorted.isNotEmpty() && representativeStepPx <= 0f) {
                representativeStepPx = sorted.first().sizePx.toFloat()
            }

            sorted.forEachIndexed { i, item ->
                val next = sorted.getOrNull(i + 1) ?: return@forEachIndexed
                observedSteps[item.index] = next.offsetPx - item.offsetPx
            }
        }

        fun representativeStepPx(): Float = representativeStepPx

        fun prefixDistancePx(index: Int): Float {
            if (index <= 0) return 0f
            val step = representativeStepPx
            var sum = 0f
            for (i in 0 until index) {
                val observed = observedSteps[i]
                sum += if (observed != null && observed > 0) observed.toFloat() else step
            }
            return sum
        }

        fun estimatedTotalPx(): Float {
            if (totalCount <= 0) return 0f
            if (totalCount == 1) return representativeStepPx
            return prefixDistancePx(totalCount)
        }

        fun targetFor(offsetPx: Float, totalItems: Int): Pair<Int, Int> {
            if (totalItems <= 0) return 0 to 0
            val target = offsetPx.coerceAtLeast(0f)
            var accumulated = 0f
            for (i in 0 until totalItems) {
                val step = stepAt(i)
                if (accumulated + step > target) {
                    return i to (target - accumulated).roundToInt().coerceAtLeast(0)
                }
                accumulated += step
            }
            return (totalItems - 1) to 0
        }

        private fun stepAt(index: Int): Float {
            val observed = observedSteps[index]
            return if (observed != null && observed > 0) observed.toFloat() else representativeStepPx
        }

        fun progressOf(
            firstIndex: Int,
            firstOffsetPx: Int,
            viewportPx: Float,
            canScrollForward: Boolean,
            canScrollBackward: Boolean
        ): Float {
            val total = estimatedTotalPx()
            if (total <= 0f || viewportPx <= 0f) return 0f
            if (!canScrollForward) return 1f
            if (!canScrollBackward) return 0f
            val maxOffset = (total - viewportPx).coerceAtLeast(1f)
            val offset = (prefixDistancePx(firstIndex) + firstOffsetPx).coerceAtLeast(0f)
            return (offset / maxOffset).coerceIn(0f, 1f)
        }
    }

    fun thumbTopPx(progress: Float, trackPx: Float, thumbPx: Float, gapPx: Float): Float {
        val travel = (trackPx - 2f * gapPx - thumbPx).coerceAtLeast(1f)
        return gapPx + progress.coerceIn(0f, 1f) * travel
    }

    fun travelPx(trackPx: Float, thumbPx: Float, gapPx: Float): Float =
        (trackPx - 2f * gapPx - thumbPx).coerceAtLeast(1f)

    fun progressOfThumbTop(thumbTopPx: Float, trackPx: Float, thumbPx: Float, gapPx: Float): Float {
        val travel = travelPx(trackPx, thumbPx, gapPx)
        return ((thumbTopPx - gapPx) / travel).coerceIn(0f, 1f)
    }

    fun isVisible(
        totalItems: Int,
        estimatedTotalPx: Float,
        viewportPx: Float,
        trackPx: Float,
        canScrollForward: Boolean,
        canScrollBackward: Boolean
    ): Boolean =
        (canScrollForward || canScrollBackward) &&
            totalItems > 0 &&
            viewportPx > 0f &&
            trackPx > 0f &&
            estimatedTotalPx > viewportPx

    fun shouldSmooth(
        sourceWasScrolled: Boolean,
        perStepPx: Float,
        totalJumpPx: Float,
        stepThresholdPx: Float,
        jumpThresholdPx: Float
    ): Boolean =
        !sourceWasScrolled && abs(perStepPx) >= stepThresholdPx && abs(totalJumpPx) >= jumpThresholdPx

    const val BUBBLE_MIN_SCALE = 0.82f

    fun bubbleScale(alpha: Float): Float =
        BUBBLE_MIN_SCALE + (1f - BUBBLE_MIN_SCALE) * alpha.coerceIn(0f, 1f)

    fun bubbleSlidePx(alpha: Float, slidePx: Float): Float =
        (1f - alpha.coerceIn(0f, 1f)) * slidePx

    fun nearestNoteIndex(displayCount: Int, index: Int, isNote: (Int) -> Boolean): Int {
        if (displayCount <= 0) return -1
        val start = index.coerceIn(0, displayCount - 1)
        for (i in start until displayCount) if (isNote(i)) return i
        for (i in start downTo 0) if (isNote(i)) return i
        return -1
    }

    /**
     * 对应 v4 的单字符/单字标签解析：
     * 数字记 #，英文字母大写，汉字取首个字；按当前排序取单字，
     * 绝不在气泡里塞长串时间文本，完美装进 40×40dp 圆形气泡中。
     */
    fun titleGlyph(title: String): String {
        val text = title.trim()
        if (text.isEmpty()) return "#"
        val first = text.firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return when {
            first.isDigit() -> "#"
            first in 'a'..'z' -> first.uppercaseChar().toString()
            first in 'A'..'Z' -> first.toString()
            else -> first.toString()
        }
    }

    /**
     * 对齐 v4 规范的气泡内容：
     * 标题或时间排序统一取首字/单字/首字母，保持极简单字圆球，不遮挡正文。
     */
    fun bubbleGlyph(note: Note, sortMode: SortMode): String = when (sortMode) {
        SortMode.TITLE -> titleGlyph(note.displayTitle)
        SortMode.UPDATED, SortMode.CREATED -> titleGlyph(note.displayTitle)
    }
}

private class ScrollJobSlot {
    var job: Job? = null
}

/**
 * 100% 对齐 Rhythm-Scrollbar-Motion-Lab-v4 网页原件的交互滚动条：
 * - 轨道：右侧 4dp 留白处保留 8dp 淡色背景，上下 8dp gap
 * - 手柄：常态 8dp -> 展开 28dp (平滑圆角)，深色 primary
 * - 气泡：40×40dp 完美圆形 (CircleShape)，primaryContainer 浅底深字，18sp 粗体单字，
 *   紧贴手柄左侧 42dp 处，绝不以大黑长条覆盖正文！
 */
@Composable
fun NotesListScrollbar(
    listState: LazyListState,
    displayNotes: List<Note?>,
    sortMode: SortMode,
    strings: AppStrings,
    contentKey: Int,
    modifier: Modifier = Modifier
) {
    val info = listState.layoutInfo
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val estimator = remember { ScrollbarEstimate.Estimator() }

    var dragging by remember { mutableStateOf(false) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    val scrollJobSlot = remember { ScrollJobSlot() }

    LaunchedEffect(contentKey) {
        scrollJobSlot.job?.cancel()
        scrollJobSlot.job = null
        dragging = false
        dragMoved = false
        dragProgress = null
        dragIndex = -1
    }

    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val visibleItems = info.visibleItemsInfo
    estimator.update(
        items = visibleItems.map { ScrollbarEstimate.Item(it.index, it.offset, it.size) },
        totalItems = displayNotes.size,
        contentKey = contentKey
    )
    val firstIndex = visibleItems.firstOrNull()?.index ?: 0
    val firstOffsetPx = -(visibleItems.firstOrNull()?.offset ?: 0)
    val estimatedTotalPx = estimator.estimatedTotalPx()
    val computedProgress = estimator.progressOf(
        firstIndex = firstIndex,
        firstOffsetPx = firstOffsetPx,
        viewportPx = viewportPx,
        canScrollForward = listState.canScrollForward,
        canScrollBackward = listState.canScrollBackward
    )

    BoxWithConstraints(modifier = modifier) {
        val trackPx = constraints.maxHeight.toFloat()
        if (trackPx <= 0f) return@BoxWithConstraints

        val thumbPx = with(density) { ListVisualTokens.scrollbarThumbHeight.toPx() }
        val gapPx = with(density) { ListVisualTokens.scrollbarTrackGap.toPx() }
        val visible = ScrollbarEstimate.isVisible(
            totalItems = displayNotes.size,
            estimatedTotalPx = estimatedTotalPx,
            viewportPx = viewportPx,
            trackPx = trackPx,
            canScrollForward = listState.canScrollForward,
            canScrollBackward = listState.canScrollBackward
        )
        if (!visible) return@BoxWithConstraints

        val shownProgress = (dragProgress ?: computedProgress).coerceIn(0f, 1f)
        val travelPx = ScrollbarEstimate.travelPx(trackPx, thumbPx, gapPx)
        val thumbTopPx = ScrollbarEstimate.thumbTopPx(shownProgress, trackPx, thumbPx, gapPx)

        val active = dragging
        val idleWidthPx = with(density) { ListVisualTokens.scrollbarIdleWidth.toPx() }
        val activeWidthPx = with(density) { ListVisualTokens.scrollbarActiveWidth.toPx() }
        val widthAnim by animateFloatAsState(
            targetValue = if (active) activeWidthPx else idleWidthPx,
            animationSpec = tween(ListVisualTokens.scrollbarWidthMillis),
            label = "scrollbarWidth"
        )
        val insetPx = with(density) { ListVisualTokens.scrollbarRightInset.toPx() }

        val latestThumbTop = rememberUpdatedState(thumbTopPx)
        val latestTravelPx = rememberUpdatedState(travelPx)
        val latestGate = rememberUpdatedState(gapPx)
        val latestThumbPx = rememberUpdatedState(thumbPx)
        val latestTrackPx = rememberUpdatedState(trackPx)

        val thumbColor = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(size.width - insetPx - idleWidthPx, gapPx),
                size = Size(idleWidthPx, (size.height - 2f * gapPx).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(idleWidthPx / 2f)
            )

            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - insetPx - widthAnim, thumbTopPx),
                size = Size(widthAnim, thumbPx),
                cornerRadius = CornerRadius(
                    x = if (active) 14.dp.toPx() else widthAnim / 2f,
                    y = if (active) 14.dp.toPx() else widthAnim / 2f
                )
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(ListVisualTokens.scrollbarHitWidth)
                .fillMaxHeight()
                .pointerInput(displayNotes.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grabbedTop = latestThumbTop.value
                        val grabbedBottom = grabbedTop + latestThumbPx.value
                        if (down.position.y < grabbedTop - 10 || down.position.y > grabbedBottom + 10) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        dragging = true
                        dragMoved = false
                        dragProgress = null
                        dragIndex = -1
                        val grabOffset = down.position.y - grabbedTop
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val thumbTop = (change.position.y - grabOffset)
                                    .coerceIn(latestGate.value, latestGate.value + latestTravelPx.value)
                                val next = ScrollbarEstimate.progressOfThumbTop(
                                    thumbTopPx = thumbTop,
                                    trackPx = latestTrackPx.value,
                                    thumbPx = latestThumbPx.value,
                                    gapPx = latestGate.value
                                )
                                if (next != dragProgress) {
                                    change.consume()
                                    dragMoved = true
                                    dragProgress = next

                                    val count = displayNotes.size
                                    val maxOffset = (estimatedTotalPx - viewportPx).coerceAtLeast(1f)
                                    val (index, offsetInItem) = if (next >= 0.999f) {
                                        (count - 1) to 0
                                    } else {
                                        estimator.targetFor(next * maxOffset, count)
                                    }
                                    dragIndex = index
                                    scrollJobSlot.job?.cancel()
                                    val target = index.coerceIn(0, (count - 1).coerceAtLeast(0))
                                    scrollJobSlot.job = scope.launch {
                                        listState.scrollToItem(target, offsetInItem)
                                    }
                                }
                            }
                        } finally {
                            dragging = false
                            dragMoved = false
                            dragProgress = null
                            dragIndex = -1
                        }
                    }
                }
        )

        // 100% 对齐 v4 规范的 40×40dp 圆形气泡
        val bubbleIndex = ScrollbarEstimate.nearestNoteIndex(
            displayCount = displayNotes.size,
            index = if (dragIndex >= 0) dragIndex else firstIndex,
            isNote = { displayNotes.getOrNull(it) != null }
        )
        val targetNote = displayNotes.getOrNull(bubbleIndex)
        val glyph = targetNote?.let {
            ScrollbarEstimate.bubbleGlyph(it, sortMode)
        }.orEmpty()

        val bubbleTarget = if (active && dragMoved && glyph.isNotEmpty()) 1f else 0f
        val bubbleAnim by animateFloatAsState(
            targetValue = bubbleTarget,
            animationSpec = tween(ListVisualTokens.scrollbarBubbleMillis),
            label = "scrollbarBubble"
        )

        if (bubbleAnim > 0.01f && glyph.isNotEmpty()) {
            val slidePx = with(density) { 8.dp.toPx() }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = -(42.dp),
                        y = with(density) { (thumbTopPx + (thumbPx - 40.dp.toPx()) / 2f).toDp() }
                    )
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = bubbleAnim
                        val scale = ScrollbarEstimate.bubbleScale(bubbleAnim)
                        scaleX = scale
                        scaleY = scale
                        translationX = (1f - bubbleAnim) * slidePx
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
