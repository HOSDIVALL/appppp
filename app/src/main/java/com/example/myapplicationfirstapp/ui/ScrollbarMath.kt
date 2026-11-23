package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.lazy.LazyListItemInfo

internal fun scrollbarTravel(height: Float, handle: Float) = (height - handle).coerceAtLeast(1f)
internal fun scrollbarTouchProgress(top: Float, travel: Float) = (top / travel).coerceIn(0f, 1f)
internal fun scrollbarHandleY(progress: Float, travel: Float) = progress * travel
internal fun scrollbarTarget(progress: Float, max: Int, count: Int): Int {
    val last = (count - 1).coerceAtLeast(0)
    return if (progress >= 1f) last else (progress * max).toInt().coerceIn(0, last)
}
internal fun scrollbarSmooth(scrolling: Boolean, step: Float, delta: Float, minStep: Float, minDelta: Float) =
    !scrolling && step >= minStep && delta >= minDelta
internal fun scrollbarGlyph(title: String): String {
    val first = title.trim().firstOrNull { it.isLetterOrDigit() } ?: '#'
    return when {
        first.isDigit() -> "#"
        first in 'a'..'z' -> first.uppercaseChar().toString()
        else -> first.toString()
    }
}
internal fun scrollbarScale(progress: Float) = 0.82f + 0.18f * progress
internal fun scrollbarSlide(progress: Float, slide: Float) = (1f - progress) * slide

internal class AxisTracker {
    private val strides = HashMap<Int, Float>()
    private var totalItems = -1
    private var spacing = -1

    fun resetIfNeeded(total: Int, itemSpacing: Int) {
        if (total != totalItems || itemSpacing != spacing) {
            strides.clear()
            totalItems = total
            spacing = itemSpacing
        }
    }

    fun observe(visibleItems: List<LazyListItemInfo>, itemSpacing: Int) {
        visibleItems.zipWithNext().forEach { (curr, next) ->
            if (next.index == curr.index + 1) {
                strides[curr.index] = (next.offset - curr.offset).toFloat()
            }
        }
    }

    fun representativeStridePx(visibleItems: List<LazyListItemInfo>, itemSpacing: Int): Float {
        val observed = strides.values.toList()
        if (observed.isNotEmpty()) {
            return observed.sorted()[observed.size / 2]
        }
        return visibleItems.firstOrNull()?.let { (it.size + itemSpacing).toFloat() } ?: 150f
    }

    fun distanceBefore(index: Int, stridePx: Float): Float {
        var sum = 0f
        for (i in 0 until index) {
            sum += strides[i] ?: stridePx
        }
        return sum
    }

    fun itemSizePx(index: Int, stridePx: Float): Float = stridePx
}
