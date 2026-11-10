package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.Recording
import com.example.myapplicationfirstapp.data.formatDuration
import com.example.myapplicationfirstapp.data.formatNoteTime

/**
 * 录音 → 笔记：先选一条笔记，再决定挂成附件还是插进正文。
 *
 * 从录音操作抽屉里的「添加到笔记」打开。列表按最近修改排序，
 * 每行显示标题 + 一句摘要，够用来认人了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePickerSheet(
    recording: Recording,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onPick: (note: Note, inline: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inline by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Text(
            text = "添加到笔记",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(
            text = "把「${recording.displayName}」· ${formatDuration(recording.durationMs)} 放进：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        PillToggle(
            labels = listOf("挂成附件", "插到正文"),
            selectedIndex = if (inline) 1 else 0,
            onSelect = { inline = it == 1 },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )

        Text(
            text = if (inline) {
                "录音会作为播放卡片出现在正文末尾，打开笔记就能听"
            } else {
                "录音挂在正文上方的附件区，不打乱正文排版"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        if (notes.isEmpty()) {
            Text(
                text = "还没有笔记。先去主页新建一条，再回来添加。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(top = 8.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    NotePickRow(
                        note = note,
                        recordingId = recording.id,
                        onClick = { onPick(note, inline) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun NotePickRow(note: Note, recordingId: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    val body = note.preview.replace('\n', ' ')
                    if (body.isNotBlank()) append(body)
                    if (body.isNotBlank()) append(" · ")
                    append(formatNoteTime(note.updatedAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 已经挂过这段录音就标出来，避免重复添加
        if (note.audio.contains(recordingId) || note.inlineAudio.contains(recordingId)) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "已包含这段录音",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
