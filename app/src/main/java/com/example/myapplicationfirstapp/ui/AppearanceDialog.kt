package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.ThemeMode

/**
 * 外观设置：全局深浅色。
 *
 * 只有一个选项组——深浅色是整个 App 的属性，不该由某个页面自己决定。
 * 录音页、录音详情页、底部导航都会跟着这里一起变。
 */
@Composable
fun AppearanceDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外观") },
        text = {
            Column {
                Text(
                    text = "深浅色对整个 App 生效，录音页也跟着走。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ThemeChip(
                            label = mode.label,
                            selected = mode == current,
                            onClick = { onSelect(mode) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (current == ThemeMode.SYSTEM) {
                    Text(
                        text = "当前跟随系统：换系统的深色模式，App 会一起变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            PillButton(text = "完成", onClick = onDismiss)
        }
    )
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content
        )
    }
}
