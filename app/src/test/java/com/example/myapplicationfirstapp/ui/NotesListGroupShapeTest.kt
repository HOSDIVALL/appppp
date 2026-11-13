package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 分组形状只靠纯逻辑决定：第几条 / 共几条 -> 位置 -> 四角。
 *
 * 这组用例锁的是「逻辑首尾」这件事本身——形状必须由整组下标算出，
 * 不能依赖当前视口里能看见哪几条，否则滑动时圆角会跟着屏幕边界变。
 */
class NotesListGroupShapeTest {

    private val outer = ListVisualTokens.groupOuterCorner.value
    private val inner = ListVisualTokens.groupInnerCorner.value

    @Test
    fun `单条笔记四角都是外角`() {
        val slot = ListGrouping.slotOf(indexInGroup = 0, groupSize = 1)
        assertEquals(ListGrouping.Slot.SINGLE, slot)
        assertArrayEquals(floatArrayOf(outer, outer, outer, outer), ListGrouping.cornerValuesDp(slot, outer, inner), 0f)
    }

    @Test
    fun `两条一组时首尾各留外角`() {
        assertEquals(ListGrouping.Slot.FIRST, ListGrouping.slotOf(0, 2))
        assertEquals(ListGrouping.Slot.LAST, ListGrouping.slotOf(1, 2))
        assertArrayEquals(floatArrayOf(outer, outer, inner, inner), ListGrouping.cornerValuesDp(ListGrouping.Slot.FIRST, outer, inner), 0f)
        assertArrayEquals(floatArrayOf(inner, inner, outer, outer), ListGrouping.cornerValuesDp(ListGrouping.Slot.LAST, outer, inner), 0f)
    }

    @Test
    fun `中间条四角都是内角`() {
        for (i in 1..6) {
            val slot = ListGrouping.slotOf(indexInGroup = i, groupSize = 8)
            assertEquals("index=$i", ListGrouping.Slot.MIDDLE, slot)
            assertArrayEquals(floatArrayOf(inner, inner, inner, inner), ListGrouping.cornerValuesDp(slot, outer, inner), 0f)
        }
    }

    @Test
    fun `长组只在首尾用外角`() {
        val size = 56
        assertEquals(ListGrouping.Slot.FIRST, ListGrouping.slotOf(0, size))
        assertEquals(ListGrouping.Slot.LAST, ListGrouping.slotOf(size - 1, size))
        // 中段必须一条外角都没有：只要中间漏出一个 FIRST/LAST，分组看起来就断了
        assertEquals(0, (1 until size - 1).count { ListGrouping.slotOf(it, size) == ListGrouping.Slot.FIRST })
        assertEquals(0, (1 until size - 1).count { ListGrouping.slotOf(it, size) == ListGrouping.Slot.LAST })
        assertEquals(size - 2, (0 until size).count { ListGrouping.slotOf(it, size) == ListGrouping.Slot.MIDDLE })
    }

    @Test
    fun `空组与越界下标不会崩且落在尾位置`() {
        // 空组在界面里不会出现，但取角函数不能因此抛异常
        assertEquals(ListGrouping.Slot.SINGLE, ListGrouping.slotOf(0, 0))
        // 下标越界时按「末条」处理，避免运行期出现 ArrayIndexOutOfBounds 式的意外
        assertEquals(ListGrouping.Slot.LAST, ListGrouping.slotOf(99, 8))
    }

    @Test
    fun `形状同时带四个不同角且外角大于内角`() {
        val first = ListGrouping.shapeOf(ListGrouping.Slot.FIRST) as RoundedCornerShape
        val last = ListGrouping.shapeOf(ListGrouping.Slot.LAST) as RoundedCornerShape
        val middle = ListGrouping.shapeOf(ListGrouping.Slot.MIDDLE) as RoundedCornerShape

        assertEquals(CornerSize(24.dp), first.topStart)
        assertEquals(CornerSize(24.dp), first.topEnd)
        assertEquals(CornerSize(6.dp), first.bottomEnd)
        assertEquals(CornerSize(6.dp), first.bottomStart)

        assertEquals(CornerSize(6.dp), last.topStart)
        assertEquals(CornerSize(24.dp), last.bottomEnd)
        assertEquals(CornerSize(24.dp), last.bottomStart)

        assertEquals(CornerSize(6.dp), middle.topStart)
        assertEquals(CornerSize(6.dp), middle.bottomStart)

        assertNotEquals(first, last)
    }

    @Test
    fun `四角顺序与圆角枚举一致`() {
        // 顺序是 [topStart, topEnd, bottomEnd, bottomStart]，
        // 只有 LAST 的第二位仍然是内角——写反了会在滑动背景上露馅
        val last = ListGrouping.cornerValuesDp(ListGrouping.Slot.LAST, outer, inner)
        assertEquals(inner, last[0], 0f)
        assertEquals(inner, last[1], 0f)
        assertEquals(outer, last[2], 0f)
        assertEquals(outer, last[3], 0f)
    }
}
