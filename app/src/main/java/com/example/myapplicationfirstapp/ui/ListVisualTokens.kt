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

    /**
     * 更多按钮的可见尺寸：按源（RH-09）是 32×44dp，不是 48×48。
     *
     * 上一版按任务书写成 48×48，被判定为与源不符（2026-11-13 用户确认以源为准），
     * 这里改回 32×44；命中区同时改为 32×44，不再额外撑到 48。
     */
    val moreButtonWidth = 32.dp
    val moreButtonHeight = 44.dp

    /**
     * 更多按钮右缘相对卡片内缩的额外让位。
     *
     * 按钮缩小到 32 宽后，右侧空档由「尾边距 + 卡片余量」共同构成，
     * 仍然要保证按钮触控区和右侧滑块的局部命中区有可测量的间隔。
     */
    val moreTrailingClearance = 4.dp

    /** 按压反馈的内容缩放（只做绘制变换，不改布局与命中） */
    const val pressScale = 0.98f

    /** 按压缩放时长；系统「移除动画」时由 Compose 自行把时长压到 0 */
    const val pressAnimationMillis = 120

    // ---------- 右侧快速定位滚动条（对齐 ExpressiveScrollBar 已读参数） ----------

    /** 手柄高度固定 48dp：不按内容比例伸缩，这是与系统滚动条最大的区别 */
    val scrollbarThumbHeight = 48.dp

    /** 常态视觉宽度（源：细宽 8dp） */
    val scrollbarIdleWidth = 8.dp

    /** 按住/拖动时展开的视觉宽度（源：24 + 4 = 28dp） */
    val scrollbarActiveWidth = 28.dp

    /** 局部命中区宽度（只覆盖手柄那一段，不铺满整高） */
    val scrollbarHitWidth = 40.dp

    /** 手柄右缘离容器右缘的留白（源：4dp） */
    val scrollbarRightInset = 4.dp

    /** 轨道上下两端各留的 gap（源：8dp），手柄到端点不贴边 */
    val scrollbarTrackGap = 8.dp

    /** 宽度展开/收起的时长（源：200ms） */
    const val scrollbarWidthMillis = 200

    /** 定位气泡入场时长（源：alpha/scale/slide 各 180ms） */
    const val scrollbarBubbleMillis = 180

    /** 气泡入场时的滑入距离（源：8dp） */
    val scrollbarBubbleSlide = 8.dp

    /** 松手后回到估算位置的平滑时长（源：70ms，且只在判据满足时用） */
    const val scrollbarSettleMillis = 70

    /** 70ms 平滑的判据：单次手柄位移 ≥16dp、总跳变 ≥10dp（源） */
    val scrollbarSmoothStep = 16.dp
    val scrollbarSmoothJump = 10.dp

    // ---------- 分组菜单（源：列表 24/6，菜单 16/4） ----------

    /** 菜单组外侧角 */
    val menuOuterCorner = 16.dp

    /** 菜单组内相邻行接缝 */
    val menuInnerCorner = 4.dp

    /** 菜单行之间 3dp */
    val menuRowGap = 3.dp

    /** 菜单容器内边距 8dp（源 .menu{padding:8px}）：条目要缩进，外角 16 才露得出来 */
    val menuContainerPadding = 8.dp

    /** 菜单行内边距：水平 14 / 垂直 10 */
    val menuContentHorizontal = 14.dp
    val menuContentVertical = 10.dp

    /** 图标底 28dp、图标 16dp */
    val menuIconPlate = 28.dp
    val menuIconSize = 16.dp

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

    /**
     * 菜单形状：源里菜单是 16/4，和列表行的 24/6 是两套，
     * 单选行四角全 16，多行按首/中/尾收口。
     */
    fun menuShapeOf(
        slot: Slot,
        outer: Dp = ListVisualTokens.menuOuterCorner,
        inner: Dp = ListVisualTokens.menuInnerCorner
    ): Shape = shapeOf(slot, outer, inner)
}
