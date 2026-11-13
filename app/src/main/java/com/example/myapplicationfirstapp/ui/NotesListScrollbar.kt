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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.data.formatNoteTime
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滚动条的位置估算。
 *
 * 对齐 Rhythm 的 ExpressiveScrollBarMetrics 语义：
 * - 代表步长取「可见相邻项步长的中位数」，不是平均值——一条超高行不会把整条行程带偏；
 * - 已观察到的步长按项下标缓存，用来修正「前缀距离」；
 * - 前缀距离 = 下标×代表步长 + 已观察步长相对代表值的修正，
 *   所以混合行高下越滚越准，但**不假设**知道全表真实高度；
 * - progress ≥ 1 直接给最后一项，不靠估算算末尾。
 *
 * LazyColumn 只暴露可见项，所以这里仍然只能"估算"，证据里不许写成像素精确。
 */
object ScrollbarEstimate {

    /** LazyColumn 的一个显示项：下标、相对视口上沿的偏移、高度 */
    data class Item(val index: Int, val offsetPx: Int, val sizePx: Int)

    data class Position(
        /** 0 = 顶部，1 = 底部 */
        val progress: Float,
        /** 是否应该显示滚动条 */
        val visible: Boolean
    )

    /** 中位数；偶数个时取中间两个的均值，空集返回 0 */
    fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toFloat()
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** 相邻可见项之间的步长（含行距），只保留正数 */
    fun stepsOf(items: List<Item>): List<Int> =
        items.sortedBy { it.index }
            .zipWithNext { a, b -> b.offsetPx - a.offsetPx }
            .filter { it > 0 }

    /**
     * 估算器。只做整数像素运算，不依赖 Compose，能直接单测。
     *
     * [update] 每次布局回来都喂一遍；总项数或内容键变化会清掉已观察步长
     * （对应源码「总条数或 spacing 改变时清观测」）。
     */
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
                // 只有一个可见项时拿它的高度兜底，至少有量级
                representativeStepPx = sorted.first().sizePx.toFloat()
            }

            // 最后一项没有「到下一项」的步长，不记
            sorted.forEachIndexed { i, item ->
                val next = sorted.getOrNull(i + 1) ?: return@forEachIndexed
                observedSteps[item.index] = next.offsetPx - item.offsetPx
            }
        }

        fun representativeStepPx(): Float = representativeStepPx

        /** 下标 -> 该下标相对列表顶部的距离（估算） */
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

        /** 顶部距离 -> (显示下标, 项内偏移)，末尾方向不外溢 */
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

        /**
         * 当前进度。首/尾用 canScrollForward / canScrollBackward 校正，
         * 免得估算误差让手柄在端点前一点点就停住。
         */
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

    /**
     * 固定长度手柄的几何：手柄高恒定，只有位置跟着进度走。
     *
     * travelPx = 轨道高 − 上下各一个 gap − 手柄高；progress 0 时手柄贴在上 gap，
     * 1 时贴在下 gap，因此两端都不会被切掉。
     */
    fun thumbTopPx(progress: Float, trackPx: Float, thumbPx: Float, gapPx: Float): Float {
        val travel = (trackPx - 2f * gapPx - thumbPx).coerceAtLeast(1f)
        return gapPx + progress.coerceIn(0f, 1f) * travel
    }

    fun travelPx(trackPx: Float, thumbPx: Float, gapPx: Float): Float =
        (trackPx - 2f * gapPx - thumbPx).coerceAtLeast(1f)

    /** 手柄顶端 -> 进度（拖动用，和 [thumbTopPx] 互逆） */
    fun progressOfThumbTop(thumbTopPx: Float, trackPx: Float, thumbPx: Float, gapPx: Float): Float {
        val travel = travelPx(trackPx, thumbPx, gapPx)
        return ((thumbTopPx - gapPx) / travel).coerceIn(0f, 1f)
    }

    /** 该不该显示：内容不足一屏、没有项、轨道太矮都不显示，不造假行程 */
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

    /**
     * 是否该给 70ms 平滑：只在「源未滚动、每次手柄位移≥16dp、总跳变≥10dp」时成立。
     * 不是所有滚动都加平滑，直接贴手更接近源行为。
     */
    fun shouldSmooth(
        sourceWasScrolled: Boolean,
        perStepPx: Float,
        totalJumpPx: Float,
        stepThresholdPx: Float,
        jumpThresholdPx: Float
    ): Boolean =
        !sourceWasScrolled && abs(perStepPx) >= stepThresholdPx && abs(totalJumpPx) >= jumpThresholdPx

    /** 气泡入场起点缩放（源：.82 → 1，180ms，同时从下方 8dp 滑入） */
    const val BUBBLE_MIN_SCALE = 0.82f

    /** 气泡入场的缩放曲线：进度 0 → .82，进度 1 → 1 */
    fun bubbleScale(alpha: Float): Float =
        BUBBLE_MIN_SCALE + (1f - BUBBLE_MIN_SCALE) * alpha.coerceIn(0f, 1f)

    /** 气泡入场的位移曲线：进度 0 → 屏幕下方 slidePx，进度 1 → 0 */
    fun bubbleSlidePx(alpha: Float, slidePx: Float): Float =
        (1f - alpha.coerceIn(0f, 1f)) * slidePx

    /**
     * 显示下标 -> 最近的笔记项。
     *
     * LazyColumn 里混着筛选行、分区标题、空状态，这些位置没有 note；
     * 先往后找，再往前找，找不到返回 -1（此时气泡不显示，不猜标题）。
     */
    fun nearestNoteIndex(displayCount: Int, index: Int, isNote: (Int) -> Boolean): Int {
        if (displayCount <= 0) return -1
        val start = index.coerceIn(0, displayCount - 1)
        for (i in start until displayCount) if (isNote(i)) return i
        for (i in start downTo 0) if (isNote(i)) return i
        return -1
    }

    /**
     * 标题排序时的定位标签：trim 后扫第一个字母或数字，数字记 #，拉丁字母大写，
     * 汉字保留原字（不做拼音，也不假装成拼音首字母）。
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

    /** 气泡文案：按当前排序取「首字」或「对应日期」，永远绑当前目标笔记而不是下标 */
    fun bubbleLabel(note: Note, sortMode: SortMode, english: Boolean): String = when (sortMode) {
        SortMode.TITLE -> titleGlyph(note.displayTitle)
        SortMode.UPDATED -> formatNoteTime(note.updatedAt, english)
        SortMode.CREATED -> formatNoteTime(note.createdAt, english)
    }
}

/** 只是存一个可取消的滚动作业，不参与重组比较 */
private class ScrollJobSlot {
    var job: Job? = null
}

/**
 * 右侧固定长度手柄的快速定位条（对齐 Rhythm ExpressiveScrollBar 的已读参数）。
 *
 * - 手柄高恒定 48dp，不按内容比例伸缩；常态 8dp、交互 28dp、右留白 4dp、轨道上下各 8dp gap；
 * - 整层只画，只有手柄那一段 40dp 宽的局部区域接收手势，其余位置照常交给 LazyColumn；
 * - 按住只展开，拖动才显示气泡；气泡有标签才显示，入场 180ms（alpha + scale .82→1 + 滑入 8dp）。
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
    // 按住只展开；真正产生位移后才算拖动（气泡只在拖动时出现）
    var dragMoved by remember { mutableStateOf(false) }
    // 拖动期间手动跟手的进度；松手后回到估算值
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    val scrollJobSlot = remember { ScrollJobSlot() }

    // 数据/筛选/排序/置顶变化：终止旧拖动、取消在跑的滚动请求，
    // 避免继续用旧下标跳到别的笔记上。
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
    // LazyListItemInfo.offset 是「项相对视口上沿」的位置，滚出视口时为负，取反才是已滚过像素
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

        // 手势协程的 key 只保留显示项数：一旦把随滚动变化的量放进 key，
        // 列表每滚一帧协程就被取消，drag 状态会卡住。
        val latestThumbTop = rememberUpdatedState(thumbTopPx)
        val latestTravelPx = rememberUpdatedState(travelPx)
        val latestGate = rememberUpdatedState(gapPx)
        val latestThumbPx = rememberUpdatedState(thumbPx)
        val latestTrackPx = rememberUpdatedState(trackPx)
        val latestSteps = rememberUpdatedState(
            displayNotes.size to estimatedTotalPx to viewportPx
        )

        val thumbColor = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (active) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(size.width - insetPx - widthAnim, gapPx),
                    size = Size(widthAnim, (size.height - 2f * gapPx).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(widthAnim / 2f)
                )
            }
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - insetPx - widthAnim, thumbTopPx),
                size = Size(widthAnim, thumbPx),
                cornerRadius = CornerRadius(widthAnim / 2f)
            )
        }

        // 手势层是**固定不动**的 40dp 宽整条（高度铺满），不是跟着手柄走的那个盒子：
        // 跟着走的话 position 是相对节点的，节点一动坐标就自我抵消，会抖成 ANR。
        // 命中测试自己做：按下的点不落在手柄带里就直接返回、不消费，
        // 事件照旧交给下面的 LazyColumn，所以只有手柄那一段真正吃手势。
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
                        if (down.position.y < grabbedTop || down.position.y > grabbedBottom) {
                            // 不在手柄上：不消费，让列表自己处理这次触摸
                            return@awaitEachGesture
                        }
                        down.consume()
                        dragging = true
                        dragMoved = false
                        dragProgress = null
                        dragIndex = -1
                        // 抓取偏移：在拇指任意位置按下都跟手，不把拇指"吸"到指针中心
                        val grabOffset = down.position.y - grabbedTop
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                // 层是固定的，所以这里可以直接用绝对坐标
                                val thumbTop = (change.position.y - grabOffset)
                                    .coerceIn(latestGate.value,
                                        latestGate.value + latestTravelPx.value)
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
                                        // 只改视口：不写 NotesState、不动 notes.json、不改 updatedAt
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

        // 拖动中的定位气泡：有标签才显示；180ms 入场（alpha + .82→1 + 8dp 滑入）
        val bubbleIndex = ScrollbarEstimate.nearestNoteIndex(
            displayCount = displayNotes.size,
            index = if (dragIndex >= 0) dragIndex else firstIndex,
            isNote = { displayNotes.getOrNull(it) != null }
        )
        val targetNote = displayNotes.getOrNull(bubbleIndex)
        val label = targetNote?.let {
            ScrollbarEstimate.bubbleLabel(it, sortMode, strings.english)
        }.orEmpty()
        val bubbleTarget = if (active && dragMoved && label.isNotEmpty()) 1f else 0f
        val bubbleAnim by animateFloatAsState(
            targetValue = bubbleTarget,
            animationSpec = tween(ListVisualTokens.scrollbarBubbleMillis),
            label = "scrollbarBubble"
        )
        if (bubbleAnim > 0.01f && label.isNotEmpty()) {
            val slidePx = with(density) { ListVisualTokens.scrollbarBubbleSlide.toPx() }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = -(ListVisualTokens.scrollbarHitWidth + 8.dp),
                        y = with(density) { (thumbTopPx + thumbPx / 2f).toDp() - 18.dp }
                    )
                    .graphicsLayer {
                        alpha = bubbleAnim
                        val scale = ScrollbarEstimate.bubbleScale(bubbleAnim)
                        scaleX = scale
                        scaleY = scale
                        translationY = ScrollbarEstimate.bubbleSlidePx(bubbleAnim, slidePx)
                    }
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
