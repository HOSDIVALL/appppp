package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.Recording
import com.example.myapplicationfirstapp.data.formatDuration

/**
 * 录音条目的操作抽屉。
 *
 * 从底部推上来，顶部一栏是这段录音的身份（标题 + 时长大小），
 * 右边一枚星形按钮直接播放；下面是一列圆角条目。
 * 条目按"最常用的排在上面"来，删除单独放最后并且标红。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingActionSheet(
    recording: Recording,
    palette: RecorderPalette,
    playing: Boolean,
    transcribing: Boolean,
    modelReady: Boolean,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onAddToNote: () -> Unit,
    onOpenDetail: () -> Unit,
    onTranscribe: () -> Unit,
    onRename: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.background,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp)
        ) {
            // ---------- 头部 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (recording.hasTranscript) "已转写的录音" else "录音",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = recording.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatDuration(recording.durationMs)} · ${recording.sizeText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                StarActionButton(
                    container = palette.wave,
                    onClick = onTogglePlay,
                    size = 60.dp
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---------- 条目 ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetAction(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = if (playing) "暂停播放" else "播放",
                    palette = palette,
                    onClick = onTogglePlay
                )

                SheetAction(
                    icon = Icons.Filled.Description,
                    label = "查看文字稿",
                    palette = palette,
                    onClick = onOpenDetail
                )

                SheetAction(
                    icon = Icons.Filled.NoteAdd,
                    label = "添加到笔记",
                    palette = palette,
                    onClick = onAddToNote
                )

                SheetAction(
                    icon = Icons.Filled.GraphicEq,
                    label = when {
                        transcribing -> "正在转写…"
                        recording.hasTranscript -> "重新转成文字"
                        modelReady -> "转成文字"
                        else -> "转成文字（需先下载模型）"
                    },
                    palette = palette,
                    enabled = !transcribing,
                    onClick = onTranscribe
                )

                SheetAction(
                    icon = Icons.Filled.DriveFileRenameOutline,
                    label = "重命名",
                    palette = palette,
                    onClick = onRename
                )

                SheetAction(
                    icon = if (recording.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    label = if (recording.starred) "取消收藏" else "收藏",
                    palette = palette,
                    onClick = onToggleStar
                )

                SheetAction(
                    icon = Icons.Filled.Share,
                    label = "分享音频",
                    palette = palette,
                    onClick = onShare
                )

                SheetAction(
                    icon = Icons.Filled.DeleteOutline,
                    label = "删除这段录音",
                    palette = palette,
                    danger = true,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    palette: RecorderPalette,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    val textColor = when {
        danger -> MaterialTheme.colorScheme.error
        enabled -> palette.textPrimary
        else -> palette.textSecondary
    }
    val iconColor = when {
        danger -> MaterialTheme.colorScheme.error
        enabled -> palette.wave
        else -> palette.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.pillContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}
