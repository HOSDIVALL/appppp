package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 对照组实现：手势、点击、拖动、键盘、无障碍语义全部交给官方 Slider，
 * 拇指由官方 SliderDefaults.Thumb 绘制，波浪用官方 LinearWavyProgressIndicator 铺在 track slot。
 *
 * 唯一自己写的部分：官方 Thumb 只吃一个固定 DpSize，圆点↔竖条这 250ms 的尺寸过渡
 * 用两个 animateDpAsState 补上，绘制和命中区域仍是官方的。
 *
 * 与 v1（自绘拇指 + 自写 awaitEachGesture）的区别：
 * - 不再自己算 fractionAt、不再自己发触感、不再自己消费 pointer
 * - semantics 由 Slider 提供（progressBarRangeInfo + setProgress + 拖动语义）
 */
@Composable
fun WavyScrubber(
    /** 播放进度 0..1，绘制期读取 */
    progress: () -> Float,
    /** 拖动中实时回调，用来让时间文字跟手 */
    onScrub: (Float) -> Unit,
    /** 松手提交，只有这一步才真的 seek */
    onScrubFinished: (Float) -> Unit,
    activeColor: Color,
    trackColor: Color,
    /** 暂停时给振幅动画一次重建缓存的机会，否则 seek 完会停在直线 */
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val state = remember {
        SliderState(value = progress().coerceIn(0f, 1f), trackRange = 0f..1f)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    var lastUserValue by remember { mutableStateOf<Float?>(null) }
    val interacting = pressed || dragged
    val latestInteracting by rememberUpdatedState(interacting)

    // 播放中把外部进度灌回 SliderState（每帧一次）；手指按住时不写，避免和拖动抢值。
    // LaunchedEffect(Unit) 只会捕获首次传入的 lambda——外部如果传的是
    // `{ 普通参数 }`（参数每帧重组都是新值），旧闭包就永远读 0。
    // 用 rememberUpdatedState 保证循环里永远调用最新那份 lambda。
    val latestProgress by rememberUpdatedState(progress)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (!latestInteracting && !state.isDragging) {
                    state.value = latestProgress().coerceIn(0f, 1f)
                }
            }
        }
    }

    // 官方 Thumb 的尺寸是常量（4×44dp），这里只补「圆点 ↔ 竖条」的过渡，不碰绘制
    val thumbWidth by animateDpAsState(
        targetValue = if (pressed) 6.dp else 16.dp,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "thumbWidth"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (pressed) 24.dp else 16.dp,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "thumbHeight"
    )

    val barStroke = remember(density) {
        Stroke(width = with(density) { 4.dp.toPx() }, cap = StrokeCap.Round)
    }
    // 振幅用稳定 lambda，避免每帧重建指示器的绘制缓存
    val amplitude = remember { { _: Float -> if (pressed) 0f else 0.3f } }

    Slider(
        state = state,
        onValueChange = { value ->
            val proposed = value.coerceIn(0f, 1f)
            // The state-based overload delegates updates to this callback.
            state.value = proposed
            lastUserValue = proposed
            onScrub(proposed)
        },
        onValueChangeFinished = {
            // Commit the last user proposal, not a possibly resynchronised value.
            val target = lastUserValue ?: state.value
            lastUserValue = null
            onScrubFinished(target)
        },
        modifier = modifier,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = activeColor,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        ),
        interactionSource = interaction,
        thumb = {
            // 编译期可用的官方重载只有 (interactionSource, modifier, colors, enabled, thumbSize)
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = SliderDefaults.colors(thumbColor = activeColor),
                enabled = enabled,
                thumbSize = DpSize(thumbWidth, thumbHeight)
            )
        },
        track = { sliderState ->
            // 波浪铺在 track slot 里，进度直接读 SliderState，数值不可能和拇指脱节
            LinearWavyProgressIndicator(
                progress = { sliderState.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                color = activeColor,
                trackColor = trackColor,
                stroke = barStroke,
                trackStroke = barStroke,
                gapSize = 4.dp,
                stopSize = 3.dp,
                amplitude = amplitude,
                wavelength = 24.dp
            )
        }
    )
}


