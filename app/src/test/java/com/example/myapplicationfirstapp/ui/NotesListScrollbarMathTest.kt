package com.example.myapplicationfirstapp.ui

import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动条的纯计算：可见性、位置比例、手柄长度、目标下标、下标->笔记的吸附、排序标签。
 *
 * 这里锁的是「估算不能算出越界或空转」：整表高度拿不到真实值，只能按可见项均值估算，
 * 所以边界（到顶/到底/内容不足一屏/全为非笔记项）都要有明确结果，不能靠界面兜。
 */
class NotesListScrollbarMathTest {

    private fun metrics(
        total: Int = 100,
        firstIndex: Int = 0,
        firstOffset: Int = 0,
        viewport: Float = 2000f,
        average: Float = 200f,
        forward: Boolean = true,
        backward: Boolean = false,
        track: Float = 2000f
    ) = ScrollbarMath.metrics(
        totalItems = total,
        firstVisibleIndex = firstIndex,
        firstVisibleOffsetPx = firstOffset,
        viewportPx = viewport,
        averageItemPx = average,
        canScrollForward = forward,
        canScrollBackward = backward,
        minThumbPx = 126f, // 48dp @ density 2.625
        trackPx = track
    )

    @Test
    fun `内容不足一屏时隐藏`() {
        val m = metrics(total = 3, average = 100f, viewport = 2000f, forward = false, backward = false)
        assertFalse(m.visible)
    }

    @Test
    fun `没有项目或轨道为零时隐藏`() {
        assertFalse(metrics(total = 0).visible)
        assertFalse(metrics(track = 0f).visible)
        assertFalse(metrics(viewport = 0f).visible)
    }

    @Test
    fun `到顶时比例为0到底为1`() {
        val top = metrics(firstIndex = 0, forward = true, backward = false)
        assertEquals(0f, top.fraction, 0.0001f)
        val bottom = metrics(firstIndex = 90, forward = false, backward = true)
        assertEquals(1f, bottom.fraction, 0.0001f)
    }

    @Test
    fun `中段比例落在区间内且随下标单调不减`() {
        var prev = -1f
        for (i in 0..80 step 8) {
            val m = metrics(firstIndex = i, forward = true, backward = i > 0)
            assertTrue("index=$i fraction=${m.fraction}", m.fraction in 0f..1f)
            assertTrue("index=$i 应不小于前一档", m.fraction >= prev - 0.0001f)
            prev = m.fraction
        }
    }

    @Test
    fun `手柄长度不小于48dp且不超过轨道`() {
        val m = metrics(total = 64, average = 220f, viewport = 2000f, track = 2000f)
        val thumbPx = m.thumbFraction * 2000f
        assertTrue("thumb=$thumbPx", thumbPx >= 126f)
        assertTrue("thumb=$thumbPx", thumbPx <= 2000f)
    }

    @Test
    fun `目标下标随拖动比例单调且不越界`() {
        val total = 64
        val avg = 220f
        val viewport = 2000f
        var prev = -1
        for (step in 0..10) {
            val (index, offset) = ScrollbarMath.targetScroll(step / 10f, total, avg, viewport)
            assertTrue("index=$index", index in 0 until total)
            assertTrue("offset=$offset", offset >= 0)
            assertTrue("比例增大时下标不应回退", index >= prev)
            prev = index
        }
        assertEquals(0, ScrollbarMath.targetScroll(0f, total, avg, viewport).first)

        // 拖到底时第一个可见项不是最后一条：视口还装着约 viewport/avg 条，
        // 把最后一条顶到列表顶端反而是「滚过头」。所以断言按行程反算。
        val maxOffset = avg * total - viewport
        val expectedIndex = (maxOffset / avg).toInt()
        val (endIndex, endOffset) = ScrollbarMath.targetScroll(1f, total, avg, viewport)
        assertEquals(expectedIndex, endIndex)
        assertTrue("末尾偏移应仍在项内：$endOffset", endOffset <= avg.toInt())
        assertEquals(maxOffset.toInt(), (endIndex * avg).toInt() + endOffset)
    }

    @Test
    fun `空列表拖动不产生负下标`() {
        val (index, offset) = ScrollbarMath.targetScroll(0.5f, 0, 200f, 1000f)
        assertEquals(0, index)
        assertEquals(0, offset)
    }

    @Test
    fun `端点吸附到首项与末项而不靠估算`() {
        val total = 67          // 含筛选行、两个分区标题、64 条笔记
        val avg = 220f          // 可见项均值，和真实整表高度不等
        val viewport = 1418f

        // 估算式在比例 1 时给出的下标明显小于末项（混合行高），所以端点必须吸附
        val estimated = (avg * total - viewport) / avg
        assertTrue("估算下标应小于末项：$estimated", estimated < total - 1)

        assertEquals(0 to 0, ScrollbarMath.resolveTarget(0f, total, avg, viewport))
        assertEquals(0 to 0, ScrollbarMath.resolveTarget(-0.2f, total, avg, viewport))
        assertEquals(
            (total - 1) to 0,
            ScrollbarMath.resolveTarget(1f, total, avg, viewport)
        )
        assertEquals(
            (total - 1) to 0,
            ScrollbarMath.resolveTarget(1.5f, total, avg, viewport)
        )
        // 中段仍走估算，不被端点吸附吃掉
        assertEquals(
            ScrollbarMath.targetScroll(0.5f, total, avg, viewport),
            ScrollbarMath.resolveTarget(0.5f, total, avg, viewport)
        )
        // 空表不产生负下标
        assertEquals(0 to 0, ScrollbarMath.resolveTarget(1f, 0, avg, viewport))
    }

    @Test
    fun `下标落到非笔记项时吸附到最近的笔记`() {
        // 0=筛选行 1=标题 2/3=笔记 4=标题 5=笔记
        val isNote = { i: Int -> i == 2 || i == 3 || i == 5 }
        assertEquals(2, ScrollbarMath.nearestNoteIndex(6, 0, isNote))
        assertEquals(2, ScrollbarMath.nearestNoteIndex(6, 1, isNote))
        assertEquals(3, ScrollbarMath.nearestNoteIndex(6, 3, isNote))
        // 4 是标题，向后找到 5
        assertEquals(5, ScrollbarMath.nearestNoteIndex(6, 4, isNote))
        // 越界下标按末尾处理
        assertEquals(5, ScrollbarMath.nearestNoteIndex(6, 99, isNote))
    }

    @Test
    fun `全部是非笔记项时返回负一而不是猜一条`() {
        assertEquals(-1, ScrollbarMath.nearestNoteIndex(3, 0) { false })
        assertEquals(-1, ScrollbarMath.nearestNoteIndex(0, 0) { true })
    }

    @Test
    fun `标题标签数字记井号拉丁大写汉字原字`() {
        assertEquals("#", ScrollbarMath.titleGlyph("3 月旅行计划"))
        assertEquals("#", ScrollbarMath.titleGlyph("2026 年度目标"))
        assertEquals("A", ScrollbarMath.titleGlyph("Alpha 项目交接"))
        assertEquals("Z", ScrollbarMath.titleGlyph("zebra 采购清单"))
        assertEquals("季", ScrollbarMath.titleGlyph("季度复盘与下周计划"))
        // 前导标点不是有效字符，取后面第一个字母数字
        assertEquals("重", ScrollbarMath.titleGlyph("「重点」待办"))
        assertEquals("X", ScrollbarMath.titleGlyph("  xyz"))
        assertEquals("#", ScrollbarMath.titleGlyph(""))
    }

    @Test
    fun `气泡文案按排序取首字或日期`() {
        val note = Note(
            id = "n1",
            title = "Alpha 项目交接",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        assertEquals("A", ScrollbarMath.bubbleLabel(note, SortMode.TITLE, english = false))
        val updated = ScrollbarMath.bubbleLabel(note, SortMode.UPDATED, english = false)
        val created = ScrollbarMath.bubbleLabel(note, SortMode.CREATED, english = false)
        assertTrue(updated.isNotBlank())
        assertTrue(created.isNotBlank())
        // 不同排序必须给出不同的东西，不能所有排序都显示同一个标题
        assertTrue(updated != "A")
        assertEquals(updated, created) // 这条数据两个时间戳相同
    }
}
