package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings

/**
 * 录制页底部操作区。
 *
 * 三段式：左右各一枚 72×96 的竖胶囊（删除 / 保存），中间一枚 160×96 的全胶囊
 * 暂停•继续，三枚之间留 8dp 缝，合计 [GroupTotalWidth] 320dp，整组水平居中。
 *
 * 内容极简：两侧只剩居中排布的图标（文案全部下沉到 contentDescription，
 * 由读屏播报），中央只剩英文 Pause / Resume，同一颗按钮按状态换词。
 *
 * 配色一律取自主题角色，不接颜色入参：主操作 primary / onPrimary，
 * 两侧 primaryContainer / onPrimaryContainer，图标与文字都靠 Surface 的
 * contentColor 下发，组件内不再逐个子项覆盖 tint / color。
 *
 * 窄窗（分屏 / 小屏）下 320dp 摆不下一行，退化成两行：上排主操作撑满整宽，
 * 下排删除与保存各占一半。判定用 [BoxWithConstraints] 的真实可用宽度，
 * 不猜屏幕尺寸。
 *
 * 删除走 [onDiscard] 原有的丢弃流程——这里只负责先弹一层确认，绝不自己删文件。
 */
@Composable
fun RecordingActionButtons(
    paused: Boolean,
    onTogglePause: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    strings: AppStrings = LocalStrings.current
) {
    // 删除确认：确认框这件事归按钮组自己管，业务侧仍旧只收到一个 onDiscard
    var confirmingDiscard by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= GroupTotalWidth) {
            // ---------- 一行：72 + 8 + 160 + 8 + 72 = 320dp ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SideAction(
                    contentDescription = strings.recActionDeleteA11y,
                    icon = Icons.Filled.Delete,
                    onClick = { confirmingDiscard = true },
                    width = SideWidth,
                    height = GroupHeight
                )

                Spacer(modifier = Modifier.width(GroupGap))

                HeroAction(
                    paused = paused,
                    onClick = onTogglePause,
                    label = if (paused) strings.recActionResume else strings.recActionPause,
                    modifier = Modifier
                        .width(HeroWidth)
                        .height(GroupHeight)
                )

                Spacer(modifier = Modifier.width(GroupGap))

                SideAction(
                    contentDescription = strings.recActionSaveA11y,
                    icon = Icons.Filled.Check,
                    onClick = onSave,
                    width = SideWidth,
                    height = GroupHeight
                )
            }
        } else {
            // ---------- 两行回退：主操作独占一行，删除/保存平分下一行 ----------
            Column(modifier = Modifier.fillMaxWidth()) {
                HeroAction(
                    paused = paused,
                    onClick = onTogglePause,
                    label = if (paused) strings.recActionResume else strings.recActionPause,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GroupHeight)
                )

                Spacer(modifier = Modifier.height(GroupGap))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GroupGap)
                ) {
                    WideSideAction(
                        contentDescription = strings.recActionDeleteA11y,
                        icon = Icons.Filled.Delete,
                        onClick = { confirmingDiscard = true },
                        modifier = Modifier.weight(1f)
                    )
                    WideSideAction(
                        contentDescription = strings.recActionSaveA11y,
                        icon = Icons.Filled.Check,
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(strings.recDiscardTitle) },
            text = { Text(strings.recDiscardBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDiscard = false
                        onDiscard()
                    }
                ) {
                    // 破坏性动作沿用主题的 error 角色，与按钮组的 primary 体系无关
                    Text(
                        text = strings.recDelete,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

/** 中央主操作：160×96 全胶囊，只有一行英文文案（Pause / Resume），水平垂直居中。 */
@Composable
private fun HeroAction(
    paused: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = HeroContentPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 侧边操作（一行布局）：72×96 竖胶囊，只有一枚图标，水平垂直居中。
 * 无障碍说明走 contentDescription，屏上不出现文字。
 */
@Composable
private fun SideAction(
    contentDescription: String,
    icon: ImageVector,
    onClick: () -> Unit,
    width: Dp,
    height: Dp
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier
            .width(width)
            .height(height)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(SideIconSize)
            )
        }
    }
}

/** 侧边操作（两行回退）：占满半宽的矮胶囊，同样只有一枚居中图标。 */
@Composable
private fun WideSideAction(
    contentDescription: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = modifier.height(FallbackSideHeight)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(SideIconSize)
            )
        }
    }
}

// ============ 尺寸常量 ============

/** 一整行的高度。 */
private val GroupHeight = 96.dp

/** 中央暂停•继续的宽度。 */
private val HeroWidth = 160.dp

/** 左右两枚竖胶囊的宽度（两侧必须同宽，中央才落在整组正中）。 */
private val SideWidth = 72.dp

/** 三枚按钮之间的缝隙。 */
private val GroupGap = 8.dp

/** 一行布局所需的最小宽度：72 + 8 + 160 + 8 + 72 = 320dp。 */
private val GroupTotalWidth = HeroWidth + (SideWidth + GroupGap) * 2

/** 两行回退时下排按钮的高度。 */
private val FallbackSideHeight = 64.dp

/** 中央按钮左右内边距。 */
private val HeroContentPadding = 16.dp

/** 两侧按钮图标尺寸（一行布局与回退布局共用）。 */
private val SideIconSize = 28.dp
