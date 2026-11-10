package com.example.myapplicationfirstapp.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.AppSettings
import com.example.myapplicationfirstapp.ui.theme.FontOption
import com.example.myapplicationfirstapp.ui.theme.builtInFamily
import com.example.myapplicationfirstapp.ui.theme.customFontFamily
import java.io.File

/**
 * 字体状态：当前选项 + 已导入的自定义字体。
 * 变更立即写回 SharedPreferences（AppSettings），下次启动无缝恢复。
 */
class FontState(context: Context) {

    private val settings = AppSettings(context)

    var option by mutableStateOf(FontOption.fromId(settings.fontId))
        private set
    var customFontPath by mutableStateOf<String?>(settings.customFontPath)
        private set
    var customFontName by mutableStateOf<String?>(settings.customFontName)
        private set

    /** 自定义字体是否可用（选中它作为宿主 Typography） */
    val customAvailable: Boolean
        get() = customFontPath != null

    /**
     * 组合期解析：当前选项 → FontFamily。
     * 自定义字体文件损坏（如换机型后残留路径）时自动回退 Geom。
     */
    fun resolveFamily(): FontFamily = when (option) {
        FontOption.CUSTOM -> customFontFamily(customFontPath?.let(::File)) ?: FontOption.GEOM.builtInFamily()
        else -> option.builtInFamily()
    }

    /** 选择某个选项（点击卡片） */
    fun select(next: FontOption) {
        if (next == FontOption.CUSTOM && !customAvailable) return
        option = next
        settings.fontId = next.id
    }

    /** 导入成功：切换到自定义字体并持久化（文件已提前拷进内部存储） */
    fun importSucceeded(fontFile: File, displayName: String) {
        customFontPath = fontFile.path
        customFontName = displayName
        option = FontOption.CUSTOM
        settings.customFontPath = fontFile.path
        settings.customFontName = displayName
        settings.fontId = FontOption.CUSTOM.id
    }

    /** 清除自定义字体：删除文件、复位为默认 Geom */
    fun clearCustom() {
        customFontPath?.let { File(it).delete() }
        customFontPath = null
        customFontName = null
        option = FontOption.GEOM
        settings.customFontPath = null
        settings.customFontName = null
        settings.fontId = FontOption.GEOM.id
    }
}

/**
 * 字体设置对话框（Rhythm 风格）：大圆角卡片列表，
 * 每张卡 = 彩色圆形字标 + 名称/描述 + 预览句，选中卡高亮 + 对勾。
 */
@Composable
fun FontSettingsDialog(
    selected: FontOption,
    customName: String?,
    customFamily: FontFamily?,
    onSelect: (FontOption) -> Unit,
    onImportFont: () -> Unit,
    onClearCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("字体", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "选择主题字体，全 App 即刻生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FontOption.entries
                        .filter { it != FontOption.CUSTOM }
                        .forEach { option ->
                            OptionCard(
                                option = option,
                                selected = selected == option,
                                onClick = { onSelect(option) }
                            )
                        }

                    // 已导入过的自定义字体：作为一张可选卡参与切换
                    if (customName != null) {
                        OptionCard(
                            option = FontOption.CUSTOM,
                            selected = selected == FontOption.CUSTOM,
                            customLabel = customName,
                            customFamily = customFamily,
                            onClick = { onSelect(FontOption.CUSTOM) }
                        )
                        ClearCustomCard(name = customName, onClick = onClearCustom)
                    }

                    ImportFontCard(onClick = onImportFont)
                }

                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    PillButton(text = "完成", onClick = onDismiss)
                }
            }
        }
    }
}

/** 彩色圆钮的暗色字标背景统一用（Rhythm 的彩色圆钮都是浅底深字） */
private val CircleGlyphColor = Color(0xFF1D1A16)

/** 每个选项的圆钮配色（粉彩系，随卡片复制到 CircleShape） */
private fun optionCircleColor(option: FontOption): Color = when (option) {
    FontOption.GEOM -> Color(0xFFB7A5F9)
    FontOption.MERGED -> Color(0xFF9CDCC8)
    FontOption.SYSTEM -> Color(0xFFA9C7F5)
    FontOption.SERIF -> Color(0xFFF6C89F)
    FontOption.MONO -> Color(0xFFF2A7BC)
    FontOption.CUSTOM -> Color(0xFFC9D68F)
}

@Composable
private fun OptionCard(
    option: FontOption,
    selected: Boolean,
    customLabel: String? = null,
    customFamily: FontFamily? = null,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val label = customLabel?.let { "自定义字体 · $it" } ?: option.label
    val desc = customLabel
        ?.let { "全局生效 · 点击卡片右侧可清除" }
        ?: option.desc

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = contentColor
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleGlyph(
                    symbol = customLabel?.firstOrNull()?.uppercaseChar()?.toString() ?: optionGlyph(option),
                    background = optionCircleColor(option)
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = "已选择")
                }
            }
            Spacer(Modifier.size(12.dp))
            PreviewBox(family = customFamily ?: option.builtInFamily())
        }
    }
}

/** 预览句：日常中英数字都要看到，缩在卡片底部的一个圆角小面板 */
@Composable
private fun PreviewBox(family: FontFamily) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "敏捷的棕色狐狸跳过了懒狗",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family)
            )
            Text(
                "The quick brown fox jumps over the lazy dog 0123456789",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun CircleGlyph(symbol: String, background: Color) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleLarge,
            color = CircleGlyphColor
        )
    }
}

/** 导入入口：SAF 文件选择 .ttf / .otf，成功后自动切到自定义字体 */
@Composable
private fun ImportFontCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(optionCircleColor(FontOption.GEOM)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "导入", tint = CircleGlyphColor)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("导入自定义字体", style = MaterialTheme.typography.titleMedium)
                Text(
                    "从文件选择器选择 .ttf / .otf",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ClearCustomCard(name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("清除字体「$name」", style = MaterialTheme.typography.titleSmall)
                Text(
                    "删除已导入的字体文件并复位默认字体",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun optionGlyph(option: FontOption): String = when (option) {
    FontOption.GEOM -> "A"
    FontOption.MERGED -> "文"
    FontOption.SYSTEM -> "默"
    FontOption.SERIF -> "宋"
    FontOption.MONO -> "码"
    FontOption.CUSTOM -> "字"
}
