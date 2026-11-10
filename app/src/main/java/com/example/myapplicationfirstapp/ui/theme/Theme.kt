package com.example.myapplicationfirstapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Material You 主题。
 *
 * 配色：Android 12+ 直接读壁纸取色（莫奈 / Monet），换壁纸 App 配色就跟着变；
 * 低版本回退到 Material 3 默认配色（Pixel 原生那套紫）。
 *
 * 形状：使用 M3 基线圆角（4/8/12/16/28），不再全局用超大圆角，
 * 这样按钮、卡片、输入框的观感才和系统设置页一致。
 */
private val PixelShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PixelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: ThemePreset = ThemePresets.first(),
    typography: Typography = AppTypography,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // 「跟随系统」= Android 12+ 壁纸取色（莫奈）；其余预设是固定配色，
    // 低版本没有动态取色能力时也回退到固定配色。
    val colorScheme = when {
        preset.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> preset.scheme(darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = PixelShapes,
        typography = typography,
        content = content
    )
}
