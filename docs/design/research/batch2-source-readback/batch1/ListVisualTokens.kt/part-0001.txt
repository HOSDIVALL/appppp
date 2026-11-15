package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ListVisualTokens {
    val groupOuterCorner = 24.dp
    val groupInnerCorner = 6.dp
    val intraGroupGap = 4.dp
    val interGroupGap = 16.dp
    val listTopGap = 12.dp
    val rowMinHeight = 72.dp
    val cardInset = 16.dp

    val moreButtonWidth = 32.dp
    val moreButtonHeight = 44.dp
    val moreTrailingClearance = 4.dp

    const val pressScale = 0.98f
    const val pressAnimationMillis = 120

    // 滚动条与 v4 严格对齐
    val scrollbarThumbHeight = 48.dp
    val scrollbarIdleWidth = 8.dp
    val scrollbarActiveWidth = 28.dp
    val scrollbarHitWidth = 40.dp
    val scrollbarRightInset = 4.dp
    val scrollbarTrackGap = 8.dp

    const val scrollbarWidthMillis = 200
    const val scrollbarBubbleMillis = 180
    val scrollbarBubbleSlide = 8.dp
    const val scrollbarSettleMillis = 70
    val scrollbarSmoothStep = 16.dp
    val scrollbarSmoothJump = 10.dp

    val menuOuterCorner = 16.dp
    val menuInnerCorner = 4.dp
    val menuRowGap = 3.dp
    val menuContainerPadding = 8.dp
    val menuContentHorizontal = 14.dp
    val menuContentVertical = 10.dp
    val menuIconPlate = 28.dp
    val menuIconSize = 16.dp

    val listBottomReserve = 96.dp
}

object ListGrouping {
    enum class Slot { SINGLE, FIRST, MIDDLE, LAST }

    fun slotOf(indexInGroup: Int, groupSize: Int): Slot = when {
        groupSize <= 1 -> Slot.SINGLE
        indexInGroup <= 0 -> Slot.FIRST
        indexInGroup >= groupSize - 1 -> Slot.LAST
        else -> Slot.MIDDLE
    }

    fun cornerValuesDp(slot: Slot, outer: Float, inner: Float): FloatArray = when (slot) {
        Slot.SINGLE -> floatArrayOf(outer, outer, outer, outer)
        Slot.FIRST -> floatArrayOf(outer, outer, inner, inner)
        Slot.MIDDLE -> floatArrayOf(inner, inner, inner, inner)
        Slot.LAST -> floatArrayOf(inner, inner, outer, outer)
    }

    fun shapeOf(
        slot: Slot,
        outer: Dp = ListVisualTokens.groupOuterCorner,
        inner: Dp = ListVisualTokens.groupInnerCorner
    ): Shape {
        val c = cornerValuesDp(slot, outer.value, inner.value)
        return RoundedCornerShape(c[0].dp, c[1].dp, c[2].dp, c[3].dp)
    }

    fun menuShapeOf(
        slot: Slot,
        outer: Dp = ListVisualTokens.menuOuterCorner,
        inner: Dp = ListVisualTokens.menuInnerCorner
    ): Shape = shapeOf(slot, outer, inner)
}
