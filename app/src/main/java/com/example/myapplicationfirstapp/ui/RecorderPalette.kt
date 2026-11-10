package com.example.myapplicationfirstapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 录音页的配色。
 *
 * 结构色全部取自 MaterialTheme——背景、卡片、文字、胶囊都跟着全局深浅色走，
 * 录音页不会自己变成另一套主题。只保留一个独立的三文鱼红作为「录音」这个动作的
 * 身份色，深浅色下都成立。
 */
@Immutable
data class RecorderPalette(
    val background: Color,
    val card: Color,
    val wave: Color,
    val wavePlayed: Color,
    val recordButton: Color,
    val onRecordButton: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val pillContainer: Color,
    val pillSelected: Color,
    val onPillSelected: Color,
    val accent: Color,
    val isDark: Boolean
)

/** 「录音」这个动作的身份色，导航栏和浮层按钮共用 */
val RecordAccent = Color(0xFFE9846C)
val OnRecordAccent = Color(0xFF33120C)

@Composable
fun recorderPalette(dark: Boolean): RecorderPalette {
    val scheme = MaterialTheme.colorScheme
    return RecorderPalette(
        background = scheme.surface,
        card = scheme.surfaceContainerHigh,
        wave = scheme.primary,
        wavePlayed = scheme.primary,
        recordButton = RecordAccent,
        onRecordButton = OnRecordAccent,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        pillContainer = scheme.surfaceContainerHighest,
        pillSelected = scheme.primary,
        onPillSelected = scheme.onPrimary,
        accent = scheme.primary,
        isDark = dark
    )
}
