package com.example.myapplicationfirstapp.ui

import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动条纯计算：代表步长（中位数）、前缀修正、固定长度手柄几何、目标下标、标签解析。
 *
 * 这一版按 Rhythm ExpressiveScrollBarMetrics 的语义重写：
 * 手柄高恒定、步长取中位数、已观察步长按项缓存、progress≥1 直接给最后一项。
 * 锁的是「估算不能越界、不能空转、不能被单条超高行带偏」。
 */
class NotesListScrollbarMathTest {

    private fun items(vararg sizes: Int): List<ScrollbarEstimate.Item> {
        var offset = 0
        return sizes.mapIndexed { index, size ->
            ScrollbarEstimate.Item(index = index, offsetPx = offset, sizePx = size)
                .also { offset += size }
        }
    }

    private fun estimatorOf(items: List<ScrollbarEstimate.Item>, total: Int, key: Int = 0) =
        ScrollbarEstimate.Estimator().also { it.update(items, total, key) }

    // ---------- 中位数 ----------

    @Test
    fun `中位数不被单条超高行带偏`() {
        assertEquals(200f, ScrollbarEstimate.median(listOf(200, 200, 200)), 0.01f)
        // 一条 900 的行：均值会被拉到 375，中位数仍是 200
        assertEquals(200f, ScrollbarEstimate.median(listOf(200, 900, 200)), 0.01f)
        val mean = (200 + 900 + 200) / 3f
        assertTrue("均值应明显偏大：$mean", mean > ScrollbarEstimate.median(listOf(200, 900, 200)) + 200)
        // 偶数个取中间两个均值
        assertEquals(250f, ScrollbarEstimate.median(listOf(200, 300)), 0.01f)
        assertEquals(0f, ScrollbarEstimate.median(emptyList()), 0.01f)
    }

    @Test
    fun `相邻步长只保留正数并按下标排序`() {
        val mixed = listOf(
            ScrollbarEstimate.Item(2, 400, 200),
            ScrollbarEstimate.Item(0, 0, 200),
            ScrollbarEstimate.Item(1, 200, 200)
        )
        assertEquals(listOf(200, 200), ScrollbarEstimate.stepsOf(mixed))
    }

    // ---------- 估算器 ----------

    @Test
    fun `代表步长取可见相邻步长中位数`() {
        // 步长 200/200/900：中位数 200，均值被抬到 433
        val est = estimatorOf(items(200, 200, 900, 200), 64)
        assertEquals(200f, est.representativeStepPx(), 0.01f)
    }

    @Test
    fun `已观察步长会修正前缀距离`() {
        // 前 4 条 200，第 5 条 500：可见 5 条时中位步长 200，但第 4 条的步长被单独记下
        // 尺寸 200/200/500/200/200：步长是 200/500/200/200，中位数 200
        val est = estimatorOf(items(200, 200, 500, 200, 200), 10)
        assertEquals(200f, est.representativeStepPx(), 0.01f)
        // 未观察到的下标仍按代表步长
        assertEquals(400f, est.prefixDistancePx(2), 0.01f)
        // 已观察到的第 3 段用真实 500，而不是代表步长 200
        assertEquals(400f + 500f, est.prefixDistancePx(3), 0.01f)
        assertEquals(400f + 500f + 200f + 200f, est.prefixDistancePx(5), 0.01f)
    }

    @Test
    fun `总条数或内容键变化时清空已观察步长`() {
        val est = estimatorOf(items(200, 200, 400), 10, 1)
        assertEquals(600f, est.prefixDistancePx(3), 0.01f)
        // 条数变了：观测清空，回到只有代表步长的状态
        estimatorOf(items(200, 200, 400), 64, 1).let { fresh ->
            assertEquals(fresh.prefixDistancePx(3), 600f, 0.01f)
        }
        est.update(items(200, 200, 400), 64, 1)
        assertEquals(600f, est.prefixDistancePx(3), 0.01f)
        // 同样的下标，换了内容键也应重算（这里断言不崩且仍非负）
        est.update(items(300, 300, 300), 64, 2)
        assertTrue(est.prefixDistancePx(3) >= 900f)
    }

    @Test
    fun `目标下标与前缀距离互为逆运算`() {
        val est = estimatorOf(items(198, 225, 240, 210, 260, 190), 20)
        for (i in 0 until 6) {
            val distance = est.prefixDistancePx(i)
            val (index, offsetInItem) = est.targetFor(distance, 20)
            assertEquals("下标 $i 反查不一致", i, index)
            assertEquals(0, offsetInItem)
        }
    }

    @Test
    fun `目标下标不越界且空表返回零`() {
        val est = estimatorOf(items(200, 200), 3)
        assertEquals(0 to 0, est.targetFor(-100f, 3))
        val (lastIndex, _) = est.targetFor(1_000_000f, 3)
        assertEquals(2, lastIndex)
        assertEquals(0 to 0, est.targetFor(500f, 0))
    }

    @Test
    fun `进度在端点被校正为中段单调`() {
        val est = estimatorOf(items(200, 200, 200), 30)
        assertEquals(
            0f,
            est.progressOf(0, 0, 2000f, canScrollForward = true, canScrollBackward = false),
            0.001f
        )
        assertEquals(
            1f,
            est.progressOf(28, 0, 2000f, canScrollForward = false, canScrollBackward = true),
            0.001f
        )
        var prev = -1f
        for (index in 5..25 step 5) {
            val p = est.progressOf(index, 0, 2000f, canScrollForward = true, canScrollBackward = true)
            assertTrue("进度应单调不减：$prev -> $p", p >= prev)
            assertTrue("进度应在 0..1：$p", p in 0f..1f)
            prev = p
        }
    }

    @Test
    fun `内容不足一屏时不显示`() {
        val est = estimatorOf(items(200, 200), 2)
        assertFalse(
            ScrollbarEstimate.isVisible(
                totalItems = 2,
                estimatedTotalPx = est.estimatedTotalPx(),
                viewportPx = 2000f,
                trackPx = 2000f,
                canScrollForward = false,
                canScrollBackward = false
            )
        )
        // 能滚动但轨道为零也不显示
        assertFalse(
            ScrollbarEstimate.isVisible(60, 12000f, 2000f, 0f, true, true)
        )
        assertTrue(
            ScrollbarEstimate.isVisible(60, 12000f, 2000f, 2000f, true, false)
        )
    }

    // ---------- 固定长度手柄几何 ----------

    @Test
    fun `手柄长度恒定不随内容变`() {
        val thumb = 126f      // 48dp @ density 2.625
        val track = 1418f
        val gap = 21f         // 8dp
        val travel = ScrollbarEstimate.travelPx(track, thumb, gap)
        assertEquals(track - 2 * gap - thumb, travel, 0.01f)
        // 顶端与底端都不贴边，且不越界
        assertEquals(gap, ScrollbarEstimate.thumbTopPx(0f, track, thumb, gap), 0.01f)
        assertEquals(gap + travel, ScrollbarEstimate.thumbTopPx(1f, track, thumb, gap), 0.01f)
        assertEquals(track - gap - thumb,
            ScrollbarEstimate.thumbTopPx(1f, track, thumb, gap), 0.01f)
        // 超界夹紧
        assertEquals(gap, ScrollbarEstimate.thumbTopPx(-1f, track, thumb, gap), 0.01f)
        assertEquals(gap + travel, ScrollbarEstimate.thumbTopPx(2f, track, thumb, gap), 0.01f)
    }

    @Test
    fun `手柄顶端与进度互逆`() {
        val thumb = 126f
        val track = 1200f
        val gap = 21f
        for (step in 0..10) {
            val p = step / 10f
            val top = ScrollbarEstimate.thumbTopPx(p, track, thumb, gap)
            assertEquals(p, ScrollbarEstimate.progressOfThumbTop(top, track, thumb, gap), 0.001f)
        }
    }

    @Test
    fun `轨道太矮时行程至少一像素不除零`() {
        val travel = ScrollbarEstimate.travelPx(trackPx = 100f, thumbPx = 126f, gapPx = 21f)
        assertEquals(1f, travel, 0.01f)
        val p = ScrollbarEstimate.progressOfThumbTop(80f, 100f, 126f, 21f)
        assertTrue("不应出现 NaN 或越界：$p", p in 0f..1f)
    }

    @Test
    fun `70ms平滑只在判据满足时启用`() {
        val step = 42f   // 16dp
        val jump = 27f   // 10dp
        assertTrue(ScrollbarEstimate.shouldSmooth(false, 50f, 40f, step, jump))
        assertFalse("源已滚动不加平滑", ScrollbarEstimate.shouldSmooth(true, 50f, 40f, step, jump))
        assertFalse("单步太小不加平滑", ScrollbarEstimate.shouldSmooth(false, 30f, 40f, step, jump))
        assertFalse("总跳变太小不加平滑", ScrollbarEstimate.shouldSmooth(false, 50f, 20f, step, jump))
    }

    // ---------- 标签 ----------

    @Test
    fun `下标落到非笔记项时吸附到最近的笔记`() {
        // 0=筛选行 1=标题 2/3=笔记 4=标题 5=笔记
        val isNote = { i: Int -> i == 2 || i == 3 || i == 5 }
        assertEquals(2, ScrollbarEstimate.nearestNoteIndex(6, 0, isNote))
        assertEquals(2, ScrollbarEstimate.nearestNoteIndex(6, 1, isNote))
        assertEquals(3, ScrollbarEstimate.nearestNoteIndex(6, 3, isNote))
        assertEquals(5, ScrollbarEstimate.nearestNoteIndex(6, 4, isNote))
        assertEquals(5, ScrollbarEstimate.nearestNoteIndex(6, 99, isNote))
    }

    @Test
    fun `全部是非笔记项时返回负一而不是猜一条`() {
        assertEquals(-1, ScrollbarEstimate.nearestNoteIndex(3, 0) { false })
        assertEquals(-1, ScrollbarEstimate.nearestNoteIndex(0, 0) { true })
    }

    @Test
    fun `标题标签扫第一个字母数字且不转拼音`() {
        assertEquals("#", ScrollbarEstimate.titleGlyph("3 月旅行计划"))
        assertEquals("#", ScrollbarEstimate.titleGlyph("2026 年度目标"))
        assertEquals("A", ScrollbarEstimate.titleGlyph("Alpha 项目交接"))
        assertEquals("Z", ScrollbarEstimate.titleGlyph("zebra 采购清单"))
        assertEquals("季", ScrollbarEstimate.titleGlyph("季度复盘与下周计划"))
        // 前导标点不是有效字符，取后面第一个字母数字
        assertEquals("重", ScrollbarEstimate.titleGlyph("「重点」待办"))
        assertEquals("X", ScrollbarEstimate.titleGlyph("  xyz"))
        assertEquals("#", ScrollbarEstimate.titleGlyph(""))
        assertEquals("#", ScrollbarEstimate.titleGlyph("!!! ??"))
    }

    @Test
    fun `气泡文案按排序取首字或日期`() {
        val note = Note(
            id = "n1",
            title = "Alpha 项目交接",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        assertEquals("A", ScrollbarEstimate.bubbleLabel(note, SortMode.TITLE, english = false))
        val updated = ScrollbarEstimate.bubbleLabel(note, SortMode.UPDATED, english = false)
        val created = ScrollbarEstimate.bubbleLabel(note, SortMode.CREATED, english = false)
        assertTrue(updated.isNotBlank())
        assertTrue(created.isNotBlank())
        assertTrue(updated != "A")
        assertEquals(updated, created)
    }

    @Test
    fun `气泡文案随语言切换`() {
        val note = Note(
            id = "n2",
            title = "旅行清单",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        val zh = ScrollbarEstimate.bubbleLabel(note, SortMode.UPDATED, english = false)
        val en = ScrollbarEstimate.bubbleLabel(note, SortMode.UPDATED, english = true)
        assertTrue(zh.contains("月") || zh.contains("今天") || zh.contains("昨天"))
        assertFalse(en.any { it.code in 0x4E00..0x9FFF })
        assertTrue(en.any { it.isDigit() })
    }

    @Test
    fun `气泡入场缩放从 082 到 1`() {
        assertEquals(0.82f, ScrollbarEstimate.bubbleScale(0f), 1e-6f)
        assertEquals(1f, ScrollbarEstimate.bubbleScale(1f), 1e-6f)
        assertEquals(0.91f, ScrollbarEstimate.bubbleScale(0.5f), 1e-6f)
    }

    @Test
    fun `气泡入场位移从 slide 到 0 且随进度单调`() {
        val slide = 21f
        assertEquals(slide, ScrollbarEstimate.bubbleSlidePx(0f, slide), 1e-6f)
        assertEquals(0f, ScrollbarEstimate.bubbleSlidePx(1f, slide), 1e-6f)
        assertEquals(slide / 2f, ScrollbarEstimate.bubbleSlidePx(0.5f, slide), 1e-6f)
        var last = Float.MAX_VALUE
        for (i in 0..10) {
            val v = ScrollbarEstimate.bubbleSlidePx(i / 10f, slide)
            assertTrue(v <= last)
            last = v
        }
    }

    @Test
    fun `气泡入场进度越界不放大也不倒滑`() {
        assertEquals(ScrollbarEstimate.BUBBLE_MIN_SCALE, ScrollbarEstimate.bubbleScale(-3f), 1e-6f)
        assertEquals(1f, ScrollbarEstimate.bubbleScale(4f), 1e-6f)
        assertEquals(30f, ScrollbarEstimate.bubbleSlidePx(-1f, 30f), 1e-6f)
        assertEquals(0f, ScrollbarEstimate.bubbleSlidePx(2f, 30f), 1e-6f)
    }
}
