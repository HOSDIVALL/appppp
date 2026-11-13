package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.data.formatNoteTime
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 右侧快速定位滚动条的纯计算部分。
 *
 * LazyColumn 只暴露「已测量的可见项」，拿不到整表真实高度，所以整表行程是**估算**：
 * 可见项平均高度 × 总项数。证据里必须写明这一点，不能声称像素精确。
 * 首/尾两个端点用 canScrollForward / canScrollBackward 校正，保证到顶到底时手柄贴边。
 */
object ScrollbarMath {

    data class Metrics(
        val visible: Boolean,
        /** 当前滚动位置占可滚动行程的比例，0 = 顶部，1 = 底部 */
        val fraction: Float,
        /** 手柄长度占轨道长度的比例（已含最小高度） */
        val thumbFraction: Float
    )

    val Hidden = Metrics(visible = false, fraction = 0f, thumbFraction = 1f)

    /**
     * @param totalItems LazyColumn 的总显示项数（含筛选行、分区标题等非笔记项）
     * @param firstVisibleIndex 第一个可见项的显示下标
     * @param firstVisibleOffsetPx 第一个可见项已滚出视口上沿的像素
     * @param viewportPx 列表实际视口高度
     * @param averageItemPx 可见项平均高度（估算依据）
     * @param minThumbPx 手柄最小高度
     * @param trackPx 轨道高度
     */
    fun metrics(
        totalItems: Int,
        firstVisibleIndex: Int,
        firstVisibleOffsetPx: Int,
        viewportPx: Float,
        averageItemPx: Float,
        canScrollForward: Boolean,
        canScrollBackward: Boolean,
        minThumbPx: Float,
        trackPx: Float
    ): Metrics {
        val canScroll = canScrollForward || canScrollBackward
        if (!canScroll || totalItems <= 0 || viewportPx <= 0f || trackPx <= 0f) return Hidden

        val avg = averageItemPx.coerceAtLeast(1f)
        val estimatedTotal = avg * totalItems
        if (estimatedTotal <= viewportPx) return Hidden

        val fraction = when {
            !canScrollForward -> 1f
            !canScrollBackward -> 0f
            else -> {
                val offset = firstVisibleIndex * avg + firstVisibleOffsetPx
                val maxOffset = (estimatedTotal - viewportPx).coerceAtLeast(1f)
                (offset / maxOffset).coerceIn(0f, 1f)
            }
        }
        val thumbFraction = (viewportPx / estimatedTotal)
            .coerceIn((minThumbPx / trackPx).coerceAtMost(1f), 1f)
        return Metrics(visible = true, fraction = fraction, thumbFraction = thumbFraction)
    }

    /** 拖动比例 -> 目标显示下标 + 项内偏移，交给 LazyListState.scrollToItem */
    fun targetScroll(
        fraction: Float,
        totalItems: Int,
        averageItemPx: Float,
        viewportPx: Float
    ): Pair<Int, Int> {
        if (totalItems <= 0) return 0 to 0
        val avg = averageItemPx.coerceAtLeast(1f)
        val estimatedTotal = avg * totalItems
        val maxOffset = (estimatedTotal - viewportPx).coerceAtLeast(0f)
        val target = fraction.coerceIn(0f, 1f) * maxOffset
        val index = (target / avg).toInt().coerceIn(0, totalItems - 1)
        val offsetInItem = (target - index * avg).roundToInt().coerceAtLeast(0)
        return index to offsetInItem
    }

    /**
     * 拖动比例 -> (显示下标, 项内偏移)。端点吸附首/尾项。
     *
     * [targetScroll] 的下标来自「平均高 × 总项数」的估算，比例 1 时算出的下标会
     * 小于真实末项（混合行高时差好几条）。所以首/尾两个端点不参与估算，
     * 直接给首项/末项，交给 LazyListState 自己夹紧。
     */
    fun resolveTarget(
        fraction: Float,
        totalItems: Int,
        averageItemPx: Float,
        viewportPx: Float
    ): Pair<Int, Int> {
        if (totalItems <= 0) return 0 to 0
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0.001f) return 0 to 0
        if (f >= 0.999f) return (totalItems - 1) to 0
        return targetScroll(f, totalItems, averageItemPx, viewportPx)
    }

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
     * 标题排序时的定位标签：数字开头记 #，拉丁字母大写，汉字保留原字
     * （不做拼音，也不假装成拼音首字母）。
     */
    fun titleGlyph(title: String): String {
        val text = title.trim()
        if (text.isEmpty()) return "#"
        val first = text.first()
        return when {
            first.isDigit() -> "#"
            first in 'a'..'z' -> first.uppercaseChar().toString()
            first in 'A'..'Z' -> first.toString()
            first.isLetter() -> first.toString()
            else -> text.firstOrNull { it.isLetterOrDigit() }?.let { c ->
                if (c.isDigit()) "#" else c.uppercaseChar().toString()
            } ?: "#"
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
 * 右侧可拖动快速定位条。
 *
 * 只画不拦：整层是一个画布，真正接收手势的只有手柄那一段 40dp 宽的局部区域，
 * 其余位置照常交给 LazyColumn——不会出现「整屏盖一层手势」的情况。
 *
 * [displayNotes] 是 LazyColumn 显示项到笔记的映射（筛选行/分区标题为 null），
 * 气泡取标题或日期都按这个映射解析，不用 index 直接当下标。
 */
@Composable
fun NotesListScrollbar(
    listState: LazyListState,
    displayNotes: List<Note?>,
    sortMode: SortMode,
    strings: AppStrings,
    dataKey: Int,
    modifier: Modifier = Modifier
) {
    val info = listState.layoutInfo
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    // 拖动期间用手势算出的比例（比等列表回写布局更快，手柄跟手不跳）
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    // 在跑的滚动作业放在普通持有者里，不参与快照，避免每个移动事件都多一次失效
    val scrollJobSlot = remember { ScrollJobSlot() }

    // 数据/筛选/排序/置顶变化：终止旧拖动、取消在跑的滚动请求，
    // 避免继续用旧下标跳到别的笔记上。
    LaunchedEffect(dataKey) {
        scrollJobSlot.job?.cancel()
        scrollJobSlot.job = null
        dragging = false
        dragFraction = null
        dragIndex = -1
    }

    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val visibleItems = info.visibleItemsInfo
    val averageItemPx = if (visibleItems.isEmpty()) 1f
    else visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val firstIndex = visibleItems.firstOrNull()?.index ?: 0
    // LazyListItemInfo.offset 是「项相对视口上沿」的位置，滚出视口时是负数，
    // 取反才是已滚过的像素
    val firstOffsetPx = -(visibleItems.firstOrNull()?.offset ?: 0)

    BoxWithConstraints(modifier = modifier) {
        val trackPx = constraints.maxHeight.toFloat()
        if (trackPx <= 0f) return@BoxWithConstraints

        val minThumbPx = with(density) { ListVisualTokens.scrollbarMinThumb.toPx() }
        val metrics = ScrollbarMath.metrics(
            totalItems = displayNotes.size,
            firstVisibleIndex = firstIndex,
            firstVisibleOffsetPx = firstOffsetPx,
            viewportPx = viewportPx,
            averageItemPx = averageItemPx,
            canScrollForward = listState.canScrollForward,
            canScrollBackward = listState.canScrollBackward,
            minThumbPx = minThumbPx,
            trackPx = trackPx
        )
        if (!metrics.visible) return@BoxWithConstraints

        val computedFraction = metrics.fraction
        val shownFraction = (dragFraction ?: computedFraction).coerceIn(0f, 1f)
        val latestShownFraction = rememberUpdatedState(shownFraction)

        val thumbPx = (metrics.thumbFraction * trackPx).coerceAtLeast(minThumbPx).coerceAtMost(trackPx)
        val travelPx = (trackPx - thumbPx).coerceAtLeast(1f)
        val thumbTopPx = shownFraction * travelPx

        val active = dragging
        val idleWidthPx = with(density) { ListVisualTokens.scrollbarIdleWidth.toPx() }
        val activeWidthPx = with(density) { ListVisualTokens.scrollbarActiveWidth.toPx() }
        val widthAnim by animateFloatAsState(
            targetValue = if (active) activeWidthPx else idleWidthPx,
            animationSpec = tween(160),
            label = "scrollbarWidth"
        )
        val insetPx = with(density) { ListVisualTokens.scrollbarTrackInset.toPx() }

        // 手势协程的 key 必须是稳定值。
        // 一旦把随滚动变化的量（可见项平均高、视口高、手柄高）放进 key，
        // 列表每滚一帧 key 就变，协程被取消，drag 状态卡在 active——这正是之前
        // 「按下后只收到一个 move 事件就没了」的原因。
        // 所以 key 只留显示项数，其余数值一律通过 rememberUpdatedState 取最新。
        val latestTravelPx = rememberUpdatedState(travelPx)
        val latestAverageItemPx = rememberUpdatedState(averageItemPx)
        val latestViewportPx = rememberUpdatedState(viewportPx)
        val latestDisplayCount = rememberUpdatedState(displayNotes.size)

        val thumbColor = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (active) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(size.width - insetPx - widthAnim, 0f),
                    size = Size(widthAnim, size.height),
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

        // 局部命中区：宽 40dp、高=手柄高度，只跟着手柄移动。
        // 只有这一段接收手势，列表其它位置（含每行的更多按钮）不受影响。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(ListVisualTokens.scrollbarHitWidth)
                .height(with(density) { thumbPx.toDp() })
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .pointerInput(displayNotes.size) {
                    awaitEachGesture {
                        // 按下即展开并显示气泡（不等 touch slop），松手/取消收起
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        dragging = true
                        dragFraction = latestShownFraction.value
                        dragIndex = -1
                        // 无论正常结束、取消还是协程被取消，都必须复位：
                        // 否则 active 态会永久卡住，屏幕只剩一根加粗的死条。
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val dy = change.positionChange().y
                                if (dy == 0f) continue
                                change.consume()
                                val travel = latestTravelPx.value
                                val next = ((dragFraction ?: latestShownFraction.value) + dy / travel)
                                    .coerceIn(0f, 1f)
                                dragFraction = next

                                val count = latestDisplayCount.value
                                val (index, offsetInItem) = ScrollbarMath.resolveTarget(
                                    fraction = next,
                                    totalItems = count,
                                    averageItemPx = latestAverageItemPx.value,
                                    viewportPx = latestViewportPx.value
                                )
                                dragIndex = index
                                // 旧请求先取消：拖动中只允许一个目标生效，
                                // 不能出现两次 scrollToItem 抢着改视口
                                scrollJobSlot.job?.cancel()
                                val target = index.coerceIn(0, (count - 1).coerceAtLeast(0))
                                scrollJobSlot.job = scope.launch {
                                    // 只改视口：不写 NotesState、不动 notes.json、不改 updatedAt
                                    listState.scrollToItem(target, offsetInItem)
                                }
                            }
                        } finally {
                            dragging = false
                            dragFraction = null
                            dragIndex = -1
                        }
                    }
                }
        )

        // 拖动中的定位气泡：纯视觉/语义反馈，没有点击语义，不拦行点击
        val bubbleIndex = ScrollbarMath.nearestNoteIndex(
            displayCount = displayNotes.size,
            index = if (dragIndex >= 0) dragIndex else (info.visibleItemsInfo.firstOrNull()?.index ?: 0),
            isNote = { displayNotes.getOrNull(it) != null }
        )
        val targetNote = displayNotes.getOrNull(bubbleIndex)
        if (active && targetNote != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = -(ListVisualTokens.scrollbarHitWidth + 8.dp),
                        y = with(density) { (thumbTopPx + thumbPx / 2f).toDp() - 18.dp }
                    )
            ) {
                Text(
                    text = ScrollbarMath.bubbleLabel(targetNote, sortMode, strings.english),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
