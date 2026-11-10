package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationfirstapp.data.Recording
import com.example.myapplicationfirstapp.data.formatClock
import com.example.myapplicationfirstapp.data.formatDuration
import kotlinx.coroutines.delay

/**
 * 录音页。
 *
 * 空闲态是一列「标题 + 时长大小 + 右侧竖胶囊三点」，点行进详情，
 * 点三点弹抽屉。录音态只留波形卡片 + 计时 + 删除/保存——
 * 中间的录音钮不在这里画，它由上层浮层从导航栏飞过来落在这个空位上
 * （见 [onStageAnchor]），这样"加号飞下来变成暂停键"才连得上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    state: RecorderState,
    dark: Boolean,
    onOpen: (Recording) -> Unit,
    onOpenActions: (Recording) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = recorderPalette(dark)

    // 提示文字过两秒自己消失
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2200)
            state.message = null
        }
    }

    // 列表页一直留在底下，录制界面从上方盖上去。
    // 用叠加而不是二选一，圆角两侧才能露出后面的内容，抽屉感才成立
    Box(modifier = modifier.fillMaxSize()) {
        RecordHome(
            state = state,
            palette = palette,
            onOpen = onOpen,
            onOpenActions = onOpenActions
        )

        AnimatedVisibility(
            visible = state.isRecording,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 320f)
            ) { height -> height } + fadeIn(tween(110)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 380f)
            ) { height -> height } + fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize()
        ) {
            RecordingStage(
                state = state,
                palette = palette,
                onDiscard = { state.stop(discard = true) },
                onSave = { state.stop(discard = false) }
            )
        }
    }
}

// ============ 空闲态：列表 ============

@Composable
private fun RecordHome(
    state: RecorderState,
    palette: RecorderPalette,
    onOpen: (Recording) -> Unit,
    onOpenActions: (Recording) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---------- 标题 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 参考 Rhythm 音乐库：标题上方留一大段空白，
                    // 整页的重心才会落下来，不会顶在状态栏底下
                    .padding(
                        start = AppSpacing.pageText,
                        end = AppSpacing.pageText,
                        top = 42.dp
                    )
            ) {
                Text(
                    text = "Recorder",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp
                    ),
                    color = palette.textPrimary
                )
                Text(
                    text = if (state.recordings.isEmpty()) {
                        "点右下角的麦克风开始第一段"
                    } else {
                        "共 ${state.recordings.size} 段 · 合计 ${
                            formatDuration(state.recordings.sumOf { it.durationMs })
                        }"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.recordings.isEmpty()) {
                EmptyRecordings(palette = palette, modifier = Modifier.fillMaxSize())
            } else {
                val listState = rememberLazyListState()
                val bg = palette.background
                val topFade by animateFloatAsState(
                    targetValue = if (listState.canScrollBackward) 1f else 0f,
                    animationSpec = tween(180),
                    label = "recordTopFade"
                )
                val bottomFade by animateFloatAsState(
                    targetValue = if (listState.canScrollForward) 1f else 0f,
                    animationSpec = tween(180),
                    label = "recordBottomFade"
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // 同笔记页：列表与滚动条都止于悬浮导航栏上方
                            .padding(bottom = 96.dp)
                            .nonInteractiveScrollbar(
                                state = listState.scrollIndicatorState!!,
                            orientation = Orientation.Vertical,
                                thumbColor = palette.wave,
                                trackColor = Color.Transparent,
                                thickness = 8.dp,
                                thumbMinLength = 48.dp,
                                thumbMaxLengthFraction = 0.32f,
                                isFadeEnabled = false,
                                mainAxisTrackInset = 10.dp,
                                crossAxisTrackInset = 6.dp
                            ),
                        contentPadding = PaddingValues(
                            start = AppSpacing.page,
                            end = AppSpacing.page,
                            bottom = 64.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)
                    ) {
                        items(state.recordings, key = { it.id }) { recording ->
                            val playing = state.isPlaying && state.loaded?.id == recording.id
                            RecordingRow(
                                recording = recording,
                                palette = palette,
                                playing = playing,
                                transcribingProgress = if (state.transcribingId == recording.id) {
                                    state.transcribeProgress
                                } else {
                                    null
                                },
                                onOpen = { onOpen(recording) },
                                onActions = { onOpenActions(recording) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .align(Alignment.TopCenter)
                            .alpha(topFade)
                            .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f))))
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .align(Alignment.BottomCenter)
                            .offset(y = (-96).dp)
                            .alpha(bottomFade)
                            .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)))
                    )

                }
            }
        }

        // ---------- 模型下载提示 ----------
        ModelBanner(
            palette = palette,
            ready = state.modelReady,
            progress = state.modelProgress,
            onDownload = { state.downloadModel() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp)
        )

        // ---------- 一次性提示 ----------
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(spring(stiffness = Spring.StiffnessMediumLow)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 168.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (palette.isDark) Color(0xFF2E3024) else MaterialTheme.colorScheme.inverseSurface
            ) {
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (palette.isDark) palette.textPrimary else MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * 本地转写模型的入口。
 *
 * 平时藏在列表底部一行小字里，只有没下载时才出现——
 * 不下载也能正常录音，只是没有文字稿，别拿一个大弹窗堵住用户。
 */
@Composable
private fun ModelBanner(
    palette: RecorderPalette,
    ready: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (ready) return

    Surface(
        shape = CircleShape,
        color = palette.pillContainer,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    progress != null -> "模型下载中 ${(progress * 100).toInt()}%"
                    else -> "本地转写模型 · 228MB"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (progress == null) {
                GhostPill(
                    text = "下载",
                    color = palette.onPillSelected,
                    container = palette.pillSelected,
                    onClick = onDownload
                )
            }
        }
    }
}

// ============ 列表行（网易云那套：文字 + 右侧竖胶囊） ============

@Composable
private fun RecordingRow(
    recording: Recording,
    palette: RecorderPalette,
    playing: Boolean,
    transcribingProgress: Float?,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)
) {
    val container = if (playing) palette.wave else MaterialTheme.colorScheme.surfaceContainerLow
    val titleColor = if (playing) Color.White else palette.textPrimary
    val subColor = if (playing) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onOpen,
        shape = shape,
        color = container,
        modifier = modifier.fillMaxWidth().height(72.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // 左侧 16 与卡片内缩一致；右侧 12 —— 40dp 三点按钮里的 20dp 图标
                // 再内缩 10dp，图标视觉右缘正好落在 22dp，与文字侧留白观感一致
                .padding(start = AppSpacing.cardInset, end = AppSpacing.cardInsetTrailing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 规范左侧图标：24dp 图标置于 40dp primaryContainer 圆形上
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (playing) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.PlayArrow else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (playing) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recording.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (recording.starred) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "已收藏",
                            tint = if (playing) Color.White else palette.wave,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = buildString {
                        append(formatDuration(recording.durationMs))
                        append(" · ")
                        append(recording.sizeText)
                        when {
                            transcribingProgress != null ->
                                append(" · 转写 ${(transcribingProgress * 100).toInt()}%")
                            recording.hasTranscript -> append(" · 已转写")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 规范右侧保留：竖向三点操作按钮
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onActions),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "录音选项",
                    tint = subColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ============ 录制中 ============

@Composable
private fun RecordingStage(
    state: RecorderState,
    palette: RecorderPalette,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    val clock = formatClock(state.elapsedMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 顶上两个圆角 + 一层阴影，上滑上来时像一只从底下推上来的抽屉
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                clip = false
            )
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(palette.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ---------- 波形卡片 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(30.dp))
                .background(palette.card)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (state.isPaused) "已暂停" else "正在录音",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.wave,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 22.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WaveformBars(
                        amplitudes = state.liveAmplitudes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        barWidth = 3.dp,
                        barGap = 3.dp,
                        unplayedColor = palette.wave.copy(alpha = if (state.isPaused) 0.55f else 0.95f),
                        alignEnd = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------- 计时 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isPaused) palette.textSecondary else palette.recordButton)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = clock,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = palette.textPrimary
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        // ---------- 底部操作区：三段式胶囊按钮组 ----------
        RecordingActionButtons(
            paused = state.isPaused,
            onTogglePause = { if (state.isPaused) state.resume() else state.pause() },
            onSave = onSave,
            onDiscard = onDiscard,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

// ============ 小组件 ============

@Composable
private fun GhostPill(
    text: String,
    color: Color,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = color,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun EmptyRecordings(palette: RecorderPalette, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 56.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(palette.card),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有录音",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "点右下角的麦克风开始第一段",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}

private fun languageLabel(code: String): String = when (code) {
    "zh" -> "中文"
    "en" -> "English"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "yue" -> "粤语"
    else -> "已转写"
}
