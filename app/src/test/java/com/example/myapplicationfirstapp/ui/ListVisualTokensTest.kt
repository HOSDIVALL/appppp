package com.example.myapplicationfirstapp.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住从源读出来的数值。
 *
 * 这一版之前吃过一次亏：任务书的 12dp/48×48 覆盖了源里的 8dp/32×44，
 * 结果「看起来像计划，但不像网页」。这些断言就是防再被改回去。
 *
 * 来源：Rhythm ExpressiveScrollBar（手柄、轨道、动效）+ RhythmGroupedMenu（菜单 16/4）+
 * Rhythm 播放列表行（更多 32×44、胶囊圆角）。
 */
class ListVisualTokensTest {

    @Test
    fun `滚动条几何与源一致`() {
        assertEquals(48.dp, ListVisualTokens.scrollbarThumbHeight)
        assertEquals(8.dp, ListVisualTokens.scrollbarIdleWidth)
        assertEquals(28.dp, ListVisualTokens.scrollbarActiveWidth)
        assertEquals(40.dp, ListVisualTokens.scrollbarHitWidth)
        assertEquals(4.dp, ListVisualTokens.scrollbarRightInset)
        assertEquals(8.dp, ListVisualTokens.scrollbarTrackGap)
    }

    @Test
    fun `滚动条动效时长与源一致`() {
        assertEquals(200, ListVisualTokens.scrollbarWidthMillis)
        assertEquals(180, ListVisualTokens.scrollbarBubbleMillis)
        assertEquals(8.dp, ListVisualTokens.scrollbarBubbleSlide)
        assertEquals(70, ListVisualTokens.scrollbarSettleMillis)
        assertEquals(16.dp, ListVisualTokens.scrollbarSmoothStep)
        assertEquals(10.dp, ListVisualTokens.scrollbarSmoothJump)
    }

    @Test
    fun `更多按钮是 32×44 不是 48×48`() {
        assertEquals(32.dp, ListVisualTokens.moreButtonWidth)
        assertEquals(44.dp, ListVisualTokens.moreButtonHeight)
    }

    @Test
    fun `分组菜单 16 比 4 且容器留 8`() {
        assertEquals(16.dp, ListVisualTokens.menuOuterCorner)
        assertEquals(4.dp, ListVisualTokens.menuInnerCorner)
        assertEquals(3.dp, ListVisualTokens.menuRowGap)
        assertEquals(8.dp, ListVisualTokens.menuContainerPadding)
        assertEquals(14.dp, ListVisualTokens.menuContentHorizontal)
        assertEquals(10.dp, ListVisualTokens.menuContentVertical)
        assertEquals(28.dp, ListVisualTokens.menuIconPlate)
        assertEquals(16.dp, ListVisualTokens.menuIconSize)
    }

    @Test
    fun `菜单形状按位置收口`() {
        val outer = ListVisualTokens.menuOuterCorner.value
        val inner = ListVisualTokens.menuInnerCorner.value
        // 顺序 topStart, topEnd, bottomEnd, bottomStart
        assertEquals(
            listOf(outer, outer, inner, inner),
            ListGrouping.cornerValuesDp(ListGrouping.Slot.FIRST, outer, inner).toList()
        )
        assertEquals(
            listOf(inner, inner, inner, inner),
            ListGrouping.cornerValuesDp(ListGrouping.Slot.MIDDLE, outer, inner).toList()
        )
        assertEquals(
            listOf(inner, inner, outer, outer),
            ListGrouping.cornerValuesDp(ListGrouping.Slot.LAST, outer, inner).toList()
        )
        assertEquals(
            listOf(outer, outer, outer, outer),
            ListGrouping.cornerValuesDp(ListGrouping.Slot.SINGLE, outer, inner).toList()
        )
    }
}
