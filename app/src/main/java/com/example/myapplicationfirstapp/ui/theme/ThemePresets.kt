package com.example.myapplicationfirstapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Notes GO 2.0 的主题预设。
 *
 * 和 Rhythm 一样，主题 = 换一整套全局配色，而不是只换一个主色。
 * 每套预设由一个色相（hue）推导出完整的 M3 颜色角色：
 * primary / secondary / tertiary / surface 容器层级 / outline 全部一起变，
 * 所以切换后深色浅色都成立，不会出现「按钮变了、卡片还是紫的」。
 *
 * 第一项「跟随系统」走 Android 12+ 的壁纸取色（莫奈），
 * 剩下的都是固定配色，用户在设置里选。
 */
@Immutable
data class ThemePreset(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    /** true = 读系统壁纸取色；低版本自动回退到 [hue] 配色 */
    val dynamic: Boolean = false,
    val hue: Float = 258f,
    /** 低饱和预设（如石墨）用它压掉色味 */
    val saturation: Float = 1f
) {
    fun displayName(english: Boolean): String = if (english) nameEn else nameZh

    fun scheme(dark: Boolean): ColorScheme =
        if (dark) darkPresetScheme(hue, saturation) else lightPresetScheme(hue, saturation)
}

val ThemePresets: List<ThemePreset> = listOf(
    ThemePreset("system", "跟随系统", "System", dynamic = true),
    ThemePreset("violet", "紫罗兰", "Violet", hue = 258f),
    ThemePreset("ocean", "海洋", "Ocean", hue = 214f),
    ThemePreset("teal", "青碧", "Teal", hue = 178f),
    ThemePreset("forest", "森林", "Forest", hue = 142f),
    ThemePreset("sunset", "日落", "Sunset", hue = 28f),
    ThemePreset("rose", "玫瑰", "Rose", hue = 344f),
    ThemePreset("graphite", "石墨", "Graphite", hue = 250f, saturation = 0.16f)
)

/** 按 id 取预设，找不到就回退到「跟随系统」 */
fun themePresetOf(id: String?): ThemePreset =
    ThemePresets.firstOrNull { it.id == id } ?: ThemePresets.first()

// ============ 配色推导 ============

/**
 * HSL → Color。用 HSL 而不是直接写死十六进制，
 * 是为了让 8 套主题共用同一套明度结构：只要色相不同，观感就统一。
 */
private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f

    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

private fun lightPresetScheme(hue: Float, sat: Float): ColorScheme {
    val s = sat.coerceIn(0f, 1f)
    val tertiaryHue = hue + 56f
    return lightColorScheme(
        primary = hsl(hue, 0.50f * s, 0.42f),
        onPrimary = Color.White,
        primaryContainer = hsl(hue, 0.66f * s, 0.90f),
        onPrimaryContainer = hsl(hue, 0.58f * s, 0.18f),
        inversePrimary = hsl(hue, 0.56f * s, 0.80f),

        secondary = hsl(hue, 0.24f * s, 0.44f),
        onSecondary = Color.White,
        secondaryContainer = hsl(hue, 0.34f * s, 0.90f),
        onSecondaryContainer = hsl(hue, 0.26f * s, 0.18f),

        tertiary = hsl(tertiaryHue, 0.34f * s, 0.44f),
        onTertiary = Color.White,
        tertiaryContainer = hsl(tertiaryHue, 0.42f * s, 0.91f),
        onTertiaryContainer = hsl(tertiaryHue, 0.30f * s, 0.18f),

        background = hsl(hue, 0.26f * s, 0.985f),
        onBackground = hsl(hue, 0.14f * s, 0.11f),
        surface = hsl(hue, 0.26f * s, 0.985f),
        onSurface = hsl(hue, 0.14f * s, 0.11f),
        surfaceVariant = hsl(hue, 0.18f * s, 0.92f),
        onSurfaceVariant = hsl(hue, 0.10f * s, 0.31f),
        surfaceTint = hsl(hue, 0.50f * s, 0.42f),

        inverseSurface = hsl(hue, 0.10f * s, 0.19f),
        inverseOnSurface = hsl(hue, 0.18f * s, 0.94f),

        error = hsl(0f, 0.65f, 0.42f),
        onError = Color.White,
        errorContainer = hsl(0f, 0.80f, 0.92f),
        onErrorContainer = hsl(0f, 0.60f, 0.20f),

        outline = hsl(hue, 0.07f * s, 0.50f),
        outlineVariant = hsl(hue, 0.11f * s, 0.78f),
        scrim = Color.Black,

        surfaceBright = hsl(hue, 0.26f * s, 0.99f),
        surfaceDim = hsl(hue, 0.14f * s, 0.87f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = hsl(hue, 0.24f * s, 0.965f),
        surfaceContainer = hsl(hue, 0.22f * s, 0.945f),
        surfaceContainerHigh = hsl(hue, 0.19f * s, 0.925f),
        surfaceContainerHighest = hsl(hue, 0.16f * s, 0.90f)
    )
}

private fun darkPresetScheme(hue: Float, sat: Float): ColorScheme {
    val s = sat.coerceIn(0f, 1f)
    val tertiaryHue = hue + 56f
    return darkColorScheme(
        primary = hsl(hue, 0.54f * s, 0.80f),
        onPrimary = hsl(hue, 0.54f * s, 0.20f),
        primaryContainer = hsl(hue, 0.44f * s, 0.32f),
        onPrimaryContainer = hsl(hue, 0.68f * s, 0.90f),
        inversePrimary = hsl(hue, 0.48f * s, 0.42f),

        secondary = hsl(hue, 0.30f * s, 0.80f),
        onSecondary = hsl(hue, 0.26f * s, 0.20f),
        secondaryContainer = hsl(hue, 0.22f * s, 0.30f),
        onSecondaryContainer = hsl(hue, 0.32f * s, 0.90f),

        tertiary = hsl(tertiaryHue, 0.36f * s, 0.80f),
        onTertiary = hsl(tertiaryHue, 0.30f * s, 0.20f),
        tertiaryContainer = hsl(tertiaryHue, 0.26f * s, 0.30f),
        onTertiaryContainer = hsl(tertiaryHue, 0.38f * s, 0.90f),

        background = hsl(hue, 0.15f * s, 0.06f),
        onBackground = hsl(hue, 0.16f * s, 0.92f),
        surface = hsl(hue, 0.15f * s, 0.06f),
        onSurface = hsl(hue, 0.16f * s, 0.92f),
        surfaceVariant = hsl(hue, 0.09f * s, 0.30f),
        onSurfaceVariant = hsl(hue, 0.11f * s, 0.78f),
        surfaceTint = hsl(hue, 0.54f * s, 0.80f),

        inverseSurface = hsl(hue, 0.16f * s, 0.92f),
        inverseOnSurface = hsl(hue, 0.10f * s, 0.19f),

        error = hsl(0f, 0.62f, 0.80f),
        onError = hsl(0f, 0.60f, 0.20f),
        errorContainer = hsl(0f, 0.52f, 0.30f),
        onErrorContainer = hsl(0f, 0.70f, 0.90f),

        outline = hsl(hue, 0.08f * s, 0.58f),
        outlineVariant = hsl(hue, 0.09f * s, 0.30f),
        scrim = Color.Black,

        surfaceBright = hsl(hue, 0.13f * s, 0.24f),
        surfaceDim = hsl(hue, 0.15f * s, 0.06f),
        surfaceContainerLowest = hsl(hue, 0.16f * s, 0.04f),
        surfaceContainerLow = hsl(hue, 0.14f * s, 0.10f),
        surfaceContainer = hsl(hue, 0.13f * s, 0.12f),
        surfaceContainerHigh = hsl(hue, 0.12f * s, 0.17f),
        surfaceContainerHighest = hsl(hue, 0.11f * s, 0.22f)
    )
}
