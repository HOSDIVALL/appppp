package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.Recording
import com.example.myapplicationfirstapp.data.formatDuration
import com.example.myapplicationfirstapp.data.formatNoteTime

/** Shared picker UI. All insertion and persistence remain owned by the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPickSheet(
    recordings: List<Recording>,
    inline: Boolean,
    onInlineChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onInsertAudio: (Recording) -> Unit,
    onInsertTranscript: (Recording) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow
    ) {
        // Header and placement controls scroll with the rows on short screens.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "picker_header") {
                Column {
                    Text("插入录音", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${recordings.size} 段录音 · 选择插入音频或已转写的文字",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("音频插入位置", style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    PillToggle(
                        labels = listOf("作为附件", "插入正文"),
                        selectedIndex = if (inline) 1 else 0,
                        onSelect = { onInlineChange(it == 1) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (inline) "音频卡片插入正文；文字始终插入编辑位置。"
                        else "音频显示在附件区；文字始终插入编辑位置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (recordings.isEmpty()) {
                item(key = "picker_empty") {
                    Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text("还没有录音", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("先到录音页录一段，再回来插入。",
                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(recordings, key = { "recording:${it.id}" }) { rec ->
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLowest) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(shape = CircleShape, color = scheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Mic, contentDescription = null,
                                        tint = scheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rec.displayName, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${formatDuration(rec.durationMs)} · ${formatNoteTime(rec.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (rec.hasTranscript) "已转写 · ${rec.segments.size} 句" else "未转写 · 暂不可插入文字",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (rec.hasTranscript) scheme.primary else scheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Two equally sized, labelled targets; no clickable parent row.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onInsertTranscript(rec) }, enabled = rec.hasTranscript,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("插入文字") }
                            FilledTonalButton(
                                onClick = { onInsertAudio(rec) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("插入录音") }
                        }
                    }
                }
            }
        }
    }
}
