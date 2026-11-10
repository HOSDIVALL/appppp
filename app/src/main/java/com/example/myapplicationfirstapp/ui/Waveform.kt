package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * 一根一根柱子拼出来的波形。
 *
 * 数据来自录音时采的振幅包络（0..1000）。两种用法：
 * - 实时：只画最近若干个点，最新的靠在右边，看起来像在往左滚；
 * - 总览：整段摊平，用 [playedFraction] 把放过的部分染成另一种颜色。
 *
 * 每根柱子的高度取它负责那一小段里的**最大值**而不是平均值，
 * 否则几十个点平均下来峰值会被抹平，波形会显得毛刺尽失。
 */
@Composable
fun WaveformBars(
    amplitudes: List<Int>,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    barGap: Dp = 3.dp,
    minBarHeight: Dp = 2.dp,
    unplayedColor: Color,
    playedColor: Color = unplayedColor,
    /** 0..1；小于 0 表示不区分已播/未播 */
    playedFraction: Float = -1f,
    /** 实时波形：最新的点靠右对齐 */
    alignEnd: Boolean = false
) {
    val normalized = if (amplitudes.isEmpty()) placeholderEnvelope() else amplitudes

    Canvas(modifier = modifier) {
        val widthPx = barWidth.toPx()
        val gapPx = barGap.toPx()
        val pitch = widthPx + gapPx
        val count = floor((size.width + gapPx) / pitch).toInt().coerceAtLeast(1)
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f
        val minHalf = minBarHeight.toPx() / 2f

        val bars = pickBars(normalized, count, alignEnd)

        bars.forEachIndexed { index, value ->
            val ratio = (value / 1000f).coerceIn(0f, 1f)
            // 竖直方向开个根号：小音量不至于缩成一条线，大音量也不会顶到边
            val half = (maxHalf * kotlin.math.sqrt(ratio)).coerceAtLeast(minHalf)

            val left = if (alignEnd && bars.size < count) {
                size.width - (bars.size - index) * pitch
            } else {
                index * pitch
            }
            if (left < -widthPx) return@forEachIndexed

            val played = playedFraction >= 0f && (left + widthPx / 2f) / size.width <= playedFraction
            drawRoundRect(
                color = if (played) playedColor else unplayedColor,
                topLeft = Offset(left, centerY - half),
                size = Size(widthPx, half * 2f),
                cornerRadius = CornerRadius(widthPx / 2f)
            )
        }
    }
}

/** 从完整包络里挑出正好 count 根柱子 */
private fun pickBars(source: List<Int>, count: Int, alignEnd: Boolean): List<Int> {
    if (source.size <= count) return source
    return if (alignEnd) {
        source.takeLast(count)
    } else {
        val step = source.size.toDouble() / count
        List(count) { index ->
            val from = (index * step).toInt()
            val to = (((index + 1) * step).toInt()).coerceAtMost(source.size)
            var peak = 0
            for (i in from until to) if (source[i] > peak) peak = source[i]
            peak
        }
    }
}

/**
 * 老录音没有振幅数据时的兜底形状。
 * 用一段固定的起伏，至少看起来还是一段音频，而不是一条断掉的直线。
 */
private fun placeholderEnvelope(size: Int = 160): List<Int> = List(size) { index ->
    val t = index.toFloat() / size
    // 一串有停顿的"说话"：7 个音节的包络 + 一点抖动
    val phrase = (sin(t * PI.toFloat() * 7f) * 0.5f + 0.5f).coerceIn(0f, 1f)
    val jitter = abs(sin(index * 1.7f)) * 0.55f + 0.45f
    (70 + phrase * phrase * jitter * 720).toInt()
}
