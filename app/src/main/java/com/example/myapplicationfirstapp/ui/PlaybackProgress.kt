package com.example.myapplicationfirstapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/** UI-only extrapolation; never writes estimated positions back to the player. */
@Composable
internal fun rememberPlaybackProgress(state: RecorderState): State<Float> {
    val duration = state.playDurationMs
    val playing = state.isPlaying
    val revision = state.timelineRevision
    val result = remember(state.loaded?.id, duration, playing, revision) {
        mutableFloatStateOf(playbackFraction(state.positionMs, duration))
    }
    LaunchedEffect(result) {
        if (!playing || duration <= 0L) return@LaunchedEffect
        var basePosition = state.positionMs.toFloat()
        var baseNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val tick = state.positionMs.toFloat()
                if (tick > basePosition) {
                    basePosition = tick
                    baseNanos = now
                }
                val extra = if (baseNanos == 0L) 0f else {
                    ((now - baseNanos) / 1_000_000f * state.speed).coerceIn(0f, 160f)
                }
                val next = ((basePosition + extra) / duration).coerceIn(0f, 1f)
                if (next > result.floatValue) result.floatValue = next
            }
        }
    }
    return result
}

private fun playbackFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
