package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 首页列表的本版局部视觉规格（UX-HOME-VISUAL-1）。
 *
 * 只服务 [NotesListScreen]，不改 [AppSpacing]：全站那条 24dp 水平基线、
 * 卡片内 16dp 缩进、行高 72dp 都不在这里重新定义，只是把「本页分组条目」
 * 需要的新数值单列出来，免得以后调全局间距时连带把分组形状改掉。
 *
 * 这些是这版 App 自己的设计选择，不是某个外部组件库的官方常量。
 */
object ListVisualTokens {

    /** 逻辑组外侧角：组的第一条上沿、最后一条下沿 */
    val groupOuterCorner = 24.dp

    /** 逻辑组内侧角：组内相邻两条的接缝，让它们看起来是「一张表里的行」 */
    val groupInnerCorner = 6.dp

    /** 组内相邻两条的间隙 */
    val intraGroupGap = 4.dp

    /** 两个逻辑组之间的留白（含分区标题上沿） */
    val interGroupGap = 12.dp

    /** 筛选行 -> 第一组之间：没有分区标题时自己撑开的留白 */
    val listTopGap = 12.dp

    /** 行高下限；内容多（两行摘要）时行可以自然变高，不裁字 */
    val rowMinHeight = 72.dp

    /** 卡片内部水平缩进（前导图形与文字） */
    val cardInset = 16.dp

    /** 更多按钮固定触控尺寸，不因滑块出现而缩小 */
    val moreButtonSize = 48.dp

    /**
     * 更多按钮右缘相对卡片内缩的额外让位。
     *
     * 卡片右缘在页面 24dp 基线上，按钮原本再内缩 16dp（合计距屏幕右缘 40dp），
     * 正好与右侧 40dp 的滑块局部命中区「贴边」——不相交但也没有间隔。
     * 按任务书「有冲突优先调整本页内部尾边距」，这里再让 4dp，换来 4dp 可测量的空档。
     */
    val moreTrailingClearance = 4.dp

    /** 按压反馈的内容缩放（只做绘制变换，不改布局与命中） */
    const val pressScale = 0.98f

    /** 按压缩放时长；系统「移除动画」时由 Compose 自行把时长压到 0 */
    const val pressAnimationMillis = 120

    // ---------- 右侧快速定位滚动条（阶段B） ----------

    /** 常态视觉宽度 */
    val scrollbarIdleWidth = 12.dp

    /** 按住/拖动时展开的视觉宽度 */
    val scrollbarActiveWidth = 28.dp

    /** 手柄最小高度 */
    val scrollbarMinThumb = 48.dp

    /** 局部命中区宽度（只覆盖手柄那一段，不铺满整高） */
    val scrollbarHitWidth = 40.dp

    /** 视觉轨道离屏幕右缘的间距 */
    val scrollbarTrackInset = 2.dp

    /** 列表底部为悬浮导航让出的高度，滚动条行程必须扣掉它 */
    val listBottomReserve = 96.dp
}

/**
 * 逻辑分组的纯计算部分：只做「第几条 / 共几条 -> 位置」与「位置 -> 四角」，
 * 不碰 Compose 状态，方便用 JVM 单测锁住首/中/尾/单项的角值。
 */
object ListGrouping {

    enum class Slot { SINGLE, FIRST, MIDDLE, LAST }

    /**
     * 按整组位置判定，和视口可见范围无关——滑动时形状不会跟着变。
     * 传入的 index 必须是该组内的下标（不是整个 LazyColumn 的下标）。
     */
    fun slotOf(indexInGroup: Int, groupSize: Int): Slot = when {
        groupSize <= 1 -> Slot.SINGLE
        indexInGroup <= 0 -> Slot.FIRST
        indexInGroup >= groupSize - 1 -> Slot.LAST
        else -> Slot.MIDDLE
    }

    /** 四个角的半径，顺序与 [RoundedCornerShape] 一致：[topStart, topEnd, bottomEnd, bottomStart] */
    fun cornerValuesDp(slot: Slot, outer: Float, inner: Float): FloatArray = when (slot) {
        Slot.SINGLE -> floatArrayOf(outer, outer, outer, outer)
        Slot.FIRST -> floatArrayOf(outer, outer, inner, inner)
        Slot.MIDDLE -> floatArrayOf(inner, inner, inner, inner)
        Slot.LAST -> floatArrayOf(inner, inner, outer, outer)
    }

    /**
     * 同一条形状要同时交给扣动背景与卡片本身：只改卡片会让滑动时的红底
     * 仍然露 16dp 直角，所以调用点只有一个 shape 变量。
     */
    fun shapeOf(
        slot: Slot,
        outer: Dp = ListVisualTokens.groupOuterCorner,
        inner: Dp = ListVisualTokens.groupInnerCorner
    ): Shape {
        val c = cornerValuesDp(slot, outer.value, inner.value)
        return RoundedCornerShape(c[0].dp, c[1].dp, c[2].dp, c[3].dp)
    }
}
