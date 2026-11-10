package com.example.myapplicationfirstapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.ThemeMode
import com.example.myapplicationfirstapp.ui.i18n.AppLanguage
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings
import com.example.myapplicationfirstapp.ui.theme.ThemePreset
import com.example.myapplicationfirstapp.ui.theme.ThemePresets

private enum class SettingsPage { Root, Theme, Language }

/**
 * 设置页。从首页右上角进入。
 *
 * 主题和语言是这一版新增的两项：
 * - 主题：8 套预设，切换后整套 M3 颜色角色一起变（不是只换主色）
 * - 语言：中文 / English，切换后立即生效，不重建 Activity
 *
 * 原来的「外观」「字体」「回收站」「导入导出」也一并收进这里，
 * 首页顶栏只留回收站和设置两个入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preset: ThemePreset,
    language: AppLanguage,
    themeMode: ThemeMode,
    trashedCount: Int,
    dark: Boolean,
    onBack: () -> Unit,
    onSelectPreset: (ThemePreset) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onOpenFontSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var showModeDialog by remember { mutableStateOf(false) }

    // 返回键：二级页先回根页，根页才退出设置
    BackHandler {
        if (page != SettingsPage.Root) page = SettingsPage.Root else onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (page != SettingsPage.Root) page = SettingsPage.Root else onBack()
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                }
            },
            title = {
                Text(
                    text = when (page) {
                        SettingsPage.Root -> s.settingsTitle
                        SettingsPage.Theme -> s.themePageTitle
                        SettingsPage.Language -> s.languagePageTitle
                    },
                    fontWeight = FontWeight.SemiBold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 380f)
                ) { dir * it / 3 } + fadeIn(spring(stiffness = 320f))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = spring(dampingRatio = 1f, stiffness = 420f)
                    ) { -dir * it / 4 } + fadeOut())
            },
            label = "settingsPage"
        ) { current ->
            when (current) {
                SettingsPage.Root -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item { SectionHeader(s.sectionAppearance) }

                    item {
                        SettingsRow(
                            headline = s.themeLabel,
                            supporting = preset.displayName(s.english),
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(themeSwatchBrush(preset, dark))
                                )
                            },
                            trailing = { Chevron() },
                            onClick = { page = SettingsPage.Theme }
                        )
                    }

                    item {
                        SettingsRow(
                            headline = s.languageLabel,
                            supporting = if (language == AppLanguage.EN) s.languageEn else s.languageZh,
                            leading = { LeadingIcon(Icons.Filled.Language) },
                            trailing = { Chevron() },
                            onClick = { page = SettingsPage.Language }
                        )
                    }

                    item {
                        SettingsRow(
                            headline = s.darkModeLabel,
                            supporting = when (themeMode) {
                                ThemeMode.SYSTEM -> s.modeSystem
                                ThemeMode.LIGHT -> s.modeLight
                                ThemeMode.DARK -> s.modeDark
                            },
                            leading = { LeadingIcon(Icons.Filled.DarkMode) },
                            trailing = { Chevron() },
                            onClick = { showModeDialog = true }
                        )
                    }

                    item { SectionHeader(s.sectionData) }

                    item {
                        SettingsRow(
                            headline = s.fontLabel,
                            leading = { LeadingIcon(Icons.Filled.FontDownload) },
                            trailing = { Chevron() },
                            onClick = onOpenFontSettings
                        )
                    }

                    item {
                        SettingsRow(
                            headline = s.trash,
                            supporting = if (trashedCount > 0) s.trashCount(trashedCount) else null,
                            leading = { LeadingIcon(Icons.Filled.Archive) },
                            trailing = { Chevron() },
                            onClick = onOpenTrash
                        )
                    }

                    item {
                        SettingsRow(
                            headline = s.exportBackup,
                            leading = { LeadingIcon(Icons.Filled.FileUpload) },
                            onClick = onExport
                        )
                    }

                    item {
                        SettingsRow(
                            headline = s.importBackup,
                            leading = { LeadingIcon(Icons.Filled.FileDownload) },
                            onClick = onImport
                        )
                    }

                    item { SectionHeader(s.sectionAbout) }

                    item {
                        SettingsRow(
                            headline = s.versionLabel,
                            supporting = s.aboutHint,
                            leading = { LeadingIcon(Icons.Filled.Info) },
                            trailing = {
                                Text(
                                    text = s.versionValue,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                SettingsPage.Theme -> ThemePicker(
                    current = preset,
                    dark = dark,
                    onSelect = {
                        onSelectPreset(it)
                        page = SettingsPage.Root
                    }
                )

                SettingsPage.Language -> LanguagePicker(
                    current = language,
                    onSelect = {
                        onSelectLanguage(it)
                        page = SettingsPage.Root
                    }
                )
            }
        }
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(s.darkModeLabel) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onSelectThemeMode(mode)
                                    showModeDialog = false
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    onSelectThemeMode(mode)
                                    showModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> s.modeSystem
                                    ThemeMode.LIGHT -> s.modeLight
                                    ThemeMode.DARK -> s.modeDark
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text(s.done) }
            }
        )
    }
}

// ============ 主题选择 ============

@Composable
private fun ThemePicker(
    current: ThemePreset,
    dark: Boolean,
    onSelect: (ThemePreset) -> Unit
) {
    val s = LocalStrings.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 28.dp)
    ) {
        item {
            Text(
                text = s.themePageHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        items(ThemePresets, key = { it.id }) { item ->
            val selected = item.id == current.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(themeSwatchBrush(item, dark)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.dynamic) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName(s.english),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/** 主题色块：普通预设是纯 primary，「跟随系统」用四色扫掠表示会随壁纸变 */
@Composable
private fun themeSwatchBrush(preset: ThemePreset, dark: Boolean): Brush {
    return if (preset.dynamic) {
        Brush.sweepGradient(
            listOf(
                Color(0xFF6750A4),
                Color(0xFF00658E),
                Color(0xFF2E7D32),
                Color(0xFFB3261E),
                Color(0xFF6750A4)
            )
        )
    } else {
        val scheme = preset.scheme(dark)
        Brush.linearGradient(listOf(scheme.primary, scheme.primaryContainer))
    }
}

// ============ 语言选择 ============

@Composable
private fun LanguagePicker(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit
) {
    val s = LocalStrings.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 28.dp)
    ) {
        item {
            Text(
                text = s.languagePageHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        items(AppLanguage.entries, key = { it.tag }) { lang ->
            val selected = lang == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onSelect(lang) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (lang == AppLanguage.EN) s.languageEn else s.languageZh,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ============ 通用小件 ============

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsRow(
    headline: String,
    leading: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = supporting?.let {
            { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@Composable
private fun LeadingIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
}
