package com.example.myapplicationfirstapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import com.example.myapplicationfirstapp.R
import java.io.File

/*
 * 字体方案（两款字体均为 OFL 开源协议，可随 App 自由分发打包）：
 *  - 英文：Geom（Thanos Poulakidas 设计，Google Fonts 收录的几何无衬线），
 *    与参考的 Rhythm 播放器用的是同一款。
 *  - 中文：思源黑体 SC（Adobe + Google 联合开发，即 Noto Sans CJK SC）。
 *
 * 「Geom + 思源黑体」选项的三个 TTF 不是原始可变字体，而是构建期合成的
 * 静态字重：用 fontTools 把 Geom 与思源黑体分别实例化到 400 / 500 / 700，
 * 逐字重合并（Geom 在前，拉丁与数字字形取胜），再子集化到
 * GB2312 全集 + 拉丁扩展 + 全角标点等约 8700 个码点，
 * 得到 app_font_400 / 500 / 700（每份约 2.4MB）。
 *
 * 必须合并成单文件的原因：Compose 的 FontFamily 列表是「整段文本命中
 * 哪个字体就用哪个」，并不逐字回退——中文写在 Geom 后面也会整段落进
 * 系统字体。合并后一处声明全局生效，正文输入框（TextField）等
 * 所有控件都吃到同一套中西文字体。
 *
 * 另有单独的 geom_var.ttf（Geom 原始可变字体，105KB）给「Geom」选项用：
 * 拉丁/数字走 Geom（可变字重轴 wght 动态取 400/500/700），汉字不在其
 * 字符集内，由平台逐字回退到系统字体——这正是 Rhythm 的本味。
 *
 * 个别 GB2312 之外的生僻字（如「龍」）不在这份字符集里，
 * 会自动回退到系统字体显示，不影响日常使用。
 */

/** 「Geom + 思源黑体」合并家族：拉丁/数字走 Geom，汉字走思源黑体 SC */
val AppFontFamily = FontFamily(
    Font(R.font.app_font_400, FontWeight.Normal),
    Font(R.font.app_font_500, FontWeight.Medium),
    Font(R.font.app_font_700, FontWeight.Bold)
)

/**
 * Geom 三个字重。
 *
 * 原来是把同一个可变字体资源挂三次、用 FontVariation 取不同 wght，
 * 实测在这台机器上三条都落回 400——标题设成 Bold 也一点没变粗。
 * 改成用 fontTools 把 wght 轴实例化成三个独立文件（各约 53KB），
 * 字重才会真正生效；汉字不在 Geom 字符集里，照旧逐字回退到系统字体。
 */
private val GeomFamily = FontFamily(
    Font(R.font.geom_400, FontWeight.Normal),
    Font(R.font.geom_500, FontWeight.Medium),
    Font(R.font.geom_700, FontWeight.Bold)
)

/**
 * 主题字体选项。id 持久化在 SharedPreferences（AppSettings.fontId），
 * 新增选项时保持已发布的 id 不变即可。
 */
enum class FontOption(
    val id: String,
    val label: String,
    val desc: String
) {
    GEOM("geom", "Geom", "默认 · Rhythm 同款，拉丁 Geom / 中文系统字体"),
    MERGED("merged", "Geom + 思源黑体", "中西文合并版，思源黑体覆盖 GB2312 全字重"),
    SYSTEM("system", "系统默认", "无衬线黑体，跟随系统设置"),
    SERIF("serif", "系统衬线", "宋体风格（Noto Serif），适合沉浸阅读"),
    MONO("mono", "系统等宽", "等宽风格，数字与代码感"),
    CUSTOM("custom", "自定义字体", "由导入的 .ttf / .otf 提供");

    companion object {
        fun fromId(id: String?): FontOption = entries.firstOrNull { it.id == id } ?: GEOM
    }
}

/** 各内置选项对应的 FontFamily（CUSTOM 除外，它由导入文件动态解析） */
fun FontOption.builtInFamily(): FontFamily = when (this) {
    FontOption.GEOM -> GeomFamily
    FontOption.MERGED -> AppFontFamily
    FontOption.SYSTEM -> FontFamily.Default
    FontOption.SERIF -> FontFamily.Serif
    FontOption.MONO -> FontFamily.Monospace
    FontOption.CUSTOM -> AppFontFamily
}

/**
 * 从导入的字体文件构建 FontFamily。同一文件挂到 400/500/700 三个档位，
 * 保证任何字重请求都命中该文件（自定义字体多为单字重，粗体会与其
 * 常规体一致，与 Rhythm 导入字体的行为一致；若导入的是可变字体，
 * FontVariation 会按档位取到真实字重）。
 * 文件缺失或已被清理时返回 null，由调用方回退默认。
 */
fun customFontFamily(file: File?): FontFamily? {
    if (file == null || !file.isFile) return null
    return FontFamily(
        Font(file, FontWeight.Normal, FontStyle.Normal),
        Font(file, FontWeight.Medium, FontStyle.Normal),
        Font(file, FontWeight.Bold, FontStyle.Normal)
    )
}

/**
 * Material 3 规范基准字号（display 57/45/36，headline 32/28/24…），
 * 只换字体与字重，不改动尺寸节奏，与系统排版节奏保持一致。
 * 代码里若请求未声明的字重（如 SemiBold=600），Compose 会自动
 * 取家族里最接近的档位。
 *
 * 函数化原因：字体可在「字体设置」里切换（FontOption），需要按
 * 当前选择即时生成一份 Typography 应用到 MaterialTheme。
 */
fun appTypography(family: FontFamily): Typography {
    fun style(
        size: Int,
        line: Int,
        weight: FontWeight = FontWeight.Normal,
        spacing: Float = 0f
    ) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = spacing.sp
    )

    return Typography(
        displayLarge = style(57, 64),
        displayMedium = style(45, 52),
        displaySmall = style(36, 44),
        headlineLarge = style(32, 40),
        headlineMedium = style(28, 36),
        headlineSmall = style(24, 32),
        titleLarge = style(22, 28, weight = FontWeight.Medium),
        titleMedium = style(16, 24, weight = FontWeight.Medium, spacing = 0.15f),
        titleSmall = style(14, 20, weight = FontWeight.Medium, spacing = 0.1f),
        bodyLarge = style(16, 24, spacing = 0.5f),
        bodyMedium = style(14, 20, spacing = 0.25f),
        bodySmall = style(12, 16, spacing = 0.4f),
        labelLarge = style(14, 20, weight = FontWeight.Medium, spacing = 0.1f),
        labelMedium = style(12, 16, weight = FontWeight.Medium, spacing = 0.5f),
        labelSmall = style(11, 16, weight = FontWeight.Medium, spacing = 0.5f)
    )
}

/** 默认（合并字体）的 Typography，供无参数调用 Theme 时兜底 */
val AppTypography = appTypography(AppFontFamily)
