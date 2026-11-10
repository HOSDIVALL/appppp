package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.Canvas

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.material3.FilterChip

import androidx.compose.material3.FilterChipDefaults

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Delete

import androidx.compose.material.icons.filled.Description

import androidx.compose.material.icons.filled.Forward5

import androidx.compose.material.icons.filled.Replay5

import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.geometry.CornerRadius

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.res.painterResource

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import com.example.myapplicationfirstapp.R

import com.example.myapplicationfirstapp.data.Recording

import com.example.myapplicationfirstapp.data.TranscriptSegment

import com.example.myapplicationfirstapp.data.formatDuration

import com.example.myapplicationfirstapp.data.formatTimestamp

import java.text.SimpleDateFormat

import java.util.Date

import java.util.Locale

import kotlin.math.roundToInt

/**

 * 录音详情页。

 *

 * 布局：文字稿在上（占满剩余空间，播放时自动跟随到当前那句），

 * 播放面板贴底——波形、时间、进度条、±5s、播放胶囊、循环/倍速都在拇指够得着的范围。

 * 文字稿每段前面带时间戳，点一下跳到那句。

 */

@Composable

fun RecordingDetailScreen(

    recording: Recording,

    state: RecorderState,

    dark: Boolean,

    onBack: () -> Unit,

    onDelete: () -> Unit,

    onMessage: (String) -> Unit,

    modifier: Modifier = Modifier,

    onTranscribe: (() -> Unit)? = null,

    modelReady: Boolean = true,

    transcribing: Boolean = false,

    transcribeProgress: Float = 0f

) {

    val palette = recorderPalette(dark)

    val listState = rememberLazyListState()

    // 进程被回收后回来，播放器是空的，这里补一次装载

    LaunchedEffect(recording.id) {

        if (state.loaded?.id != recording.id) state.open(recording)

    }

    val progress = if (state.playDurationMs > 0) {

        (state.positionMs.toFloat() / state.playDurationMs).coerceIn(0f, 1f)

    } else {

        0f

    }

    val frameProgress = rememberPlaybackProgress(state)

    // 手指按住进度条时，界面上的时间和圆球跟着手指走，松手才真的 seek

    var scrubFraction by remember(recording.id) { mutableStateOf<Float?>(null) }

    val shownFraction = scrubFraction ?: progress

    val shownPositionMs = (shownFraction * state.playDurationMs).toLong()

    // 正在念的那一句

    val activeIndex = remember(recording.segments, state.positionMs) {

        recording.segments.indexOfLast { it.startMs <= state.positionMs }

    }

    Column(

        modifier = modifier

            .fillMaxSize()

            .background(palette.background)

    ) {

        // ---------- 顶栏 ----------

        Row(

            modifier = Modifier

                .fillMaxWidth()

                .padding(start = 12.dp, end = 12.dp, top = 8.dp),

            verticalAlignment = Alignment.CenterVertically

        ) {

            RoundIcon(

                container = palette.card,

                tint = palette.textPrimary,

                onClick = onBack

            ) {

                Icon(

                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,

                    contentDescription = "返回",

                    tint = palette.textPrimary,

                    modifier = Modifier.size(20.dp)

                )

            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {

                Text(

                    text = recording.displayName,

                    style = MaterialTheme.typography.titleMedium,

                    fontWeight = FontWeight.SemiBold,

                    color = palette.textPrimary,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis

                )

                Text(

                    // 标题已经是日期时别再重复一遍

                    text = if (recording.title.isBlank()) {

                        formatDuration(recording.durationMs) + " · " + recording.sizeText

                    } else {

                        "${DETAIL_DATE.format(Date(recording.createdAt))} · " +

                            formatDuration(recording.durationMs)

                    },

                    style = MaterialTheme.typography.labelSmall,

                    color = palette.textSecondary

                )

            }

            RoundIcon(

                container = palette.card,

                tint = palette.textSecondary,

                onClick = onDelete

            ) {

                Icon(

                    imageVector = Icons.Default.Delete,

                    contentDescription = "删除",

                    tint = palette.textSecondary,

                    modifier = Modifier.size(19.dp)

                )

            }

        }

        // ---------- 上方：文字稿 ----------

        Row(

            modifier = Modifier

                .fillMaxWidth()

                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp),

            verticalAlignment = Alignment.CenterVertically

        ) {

            Text(

                text = "文字稿",

                style = MaterialTheme.typography.titleSmall,

                fontWeight = FontWeight.SemiBold,

                color = palette.textPrimary

            )

            Spacer(modifier = Modifier.weight(1f))

            if (recording.hasTranscript) {

                Text(

                    text = "${recording.segments.size} 段",

                    style = MaterialTheme.typography.labelSmall,

                    color = palette.textSecondary

                )

            }

        }

        if (recording.hasTranscript) {

            // 播放时文字稿跟着念到的那一句走

            LaunchedEffect(activeIndex, state.isPlaying) {

                if (state.isPlaying && activeIndex >= 0) {

                    listState.animateScrollToItem(activeIndex)

                }

            }

            LazyColumn(

                state = listState,

                modifier = Modifier

                    .weight(1f)

                    .fillMaxWidth(),

                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),

                verticalArrangement = Arrangement.spacedBy(4.dp)

            ) {

                itemsIndexed(recording.segments) { index, segment ->

                    TranscriptRow(

                        segment = segment,

                        palette = palette,

                        active = index == activeIndex,

                        onClick = { state.seekTo(segment.startMs) }

                    )

                }

            }

        } else {

            Box(

                modifier = Modifier

                    .weight(1f)

                    .fillMaxWidth()

                    .padding(horizontal = 24.dp),

                contentAlignment = Alignment.TopCenter

            ) {

                Column(

                    horizontalAlignment = Alignment.CenterHorizontally,

                    modifier = Modifier.padding(top = 24.dp)

                ) {

                    Icon(

                        imageVector = Icons.Filled.Description,

                        contentDescription = null,

                        tint = palette.textSecondary,

                        modifier = Modifier.size(26.dp)

                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    when {

                        transcribing -> {

                            Text(

                                text = "正在转写 ${(transcribeProgress * 100).toInt()}%",

                                style = MaterialTheme.typography.bodyMedium,

                                color = palette.textPrimary

                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Box(

                                modifier = Modifier

                                    .width(180.dp)

                                    .height(4.dp)

                                    .clip(CircleShape)

                                    .background(palette.wave.copy(alpha = 0.22f))

                            ) {

                                Box(

                                    modifier = Modifier

                                        .fillMaxWidth(transcribeProgress.coerceIn(0f, 1f))

                                        .height(4.dp)

                                        .clip(CircleShape)

                                        .background(palette.wave)

                                )

                            }

                        }

                        !modelReady -> {

                            Text(

                                text = "还没有文字稿",

                                style = MaterialTheme.typography.bodyMedium,

                                color = palette.textSecondary

                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(

                                text = "先在录音页下载本地转写模型",

                                style = MaterialTheme.typography.labelSmall,

                                color = palette.textSecondary

                            )

                        }

                        onTranscribe != null -> {

                            Text(

                                text = "还没有文字稿",

                                style = MaterialTheme.typography.bodyMedium,

                                color = palette.textSecondary

                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            SmallPill(text = "转成文字", palette = palette, onClick = onTranscribe)

                        }

                    }

                }

            }

        }

        // ---------- 下方：播放面板（贴底，拇指够得着） ----------

        Surface(

            modifier = Modifier.fillMaxWidth(),

            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),

            color = MaterialTheme.colorScheme.surfaceContainer,

            shadowElevation = 12.dp

        ) {

            Column(

                modifier = Modifier

                    .navigationBarsPadding()

                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)

            ) {

                // 波形卡片

                Box(

                    modifier = Modifier

                        .fillMaxWidth()

                        .height(96.dp)

                        .clip(RoundedCornerShape(24.dp))

                        .background(palette.card)

                        .padding(horizontal = 16.dp, vertical = 14.dp)

                ) {

                    WaveformBars(

                        amplitudes = recording.amplitudes,

                        modifier = Modifier.fillMaxSize(),

                        barWidth = 3.dp,

                        barGap = 3.dp,

                        unplayedColor = palette.wave.copy(alpha = 0.24f),

                        playedColor = palette.wavePlayed,

                        playedFraction = progress

                    )

                    // 播放头

                    Canvas(modifier = Modifier.fillMaxSize()) {

                        val x = (size.width * progress).coerceIn(0f, size.width)

                        drawRoundRect(

                            color = palette.wavePlayed,

                            topLeft = Offset(x - 1.5.dp.toPx(), 0f),

                            size = Size(3.dp.toPx(), size.height),

                            cornerRadius = CornerRadius(2.dp.toPx())

                        )

                    }

                }

                Spacer(modifier = Modifier.height(8.dp))

                // 时间

                Row(

                    modifier = Modifier

                        .fillMaxWidth()

                        .padding(horizontal = 6.dp),

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Text(

                        text = formatTime(shownPositionMs, showTenths = true),

                        style = MaterialTheme.typography.labelMedium,

                        fontWeight = FontWeight.SemiBold,

                        color = palette.wavePlayed

                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(

                        text = formatDuration(recording.durationMs),

                        style = MaterialTheme.typography.labelMedium,

                        color = palette.textSecondary

                    )

                }

                // 能拖的波浪进度条（绘制用官方 LinearWavyProgressIndicator）

                WavyScrubber(

                    progress = { frameProgress.value },

                    onScrub = { fraction -> scrubFraction = fraction },

                    onScrubFinished = { fraction ->

                        state.seekTo((fraction * state.playDurationMs).toLong())
                        scrubFraction = null

                    },

                    activeColor = palette.wavePlayed,

                    // Rhythm 的未播放轨道是主色约 25% 透明，不是灰色

                    trackColor = palette.wavePlayed.copy(alpha = 0.30f),

                    isPlaying = state.isPlaying,

                    modifier = Modifier.padding(horizontal = 6.dp)

                )

                // 控制：星形 ±5s + 大胶囊播放

                Row(

                    modifier = Modifier

                        .fillMaxWidth()

                        .padding(horizontal = 4.dp),

                    horizontalArrangement = Arrangement.Center,

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    StarActionButton(

                        container = palette.card,

                        onClick = { state.skipBy(-5_000) }

                    ) {

                        Icon(

                            imageVector = Icons.Filled.Replay5,

                            contentDescription = "后退 5 秒",

                            tint = palette.textPrimary,

                            modifier = Modifier.size(25.dp)

                        )

                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    CapsulePlayButton(

                        playing = state.isPlaying,

                        container = palette.textPrimary,

                        content = palette.background,

                        onClick = { state.togglePlay() },

                        modifier = Modifier.weight(1f)

                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    StarActionButton(

                        container = palette.card,

                        onClick = { state.skipBy(5_000) }

                    ) {

                        Icon(

                            imageVector = Icons.Filled.Forward5,

                            contentDescription = "前进 5 秒",

                            tint = palette.textPrimary,

                            modifier = Modifier.size(25.dp)

                        )

                    }

                }

                Spacer(modifier = Modifier.height(8.dp))

                // 循环 / 倍速

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.Center,

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    FilterChip(

                        selected = state.looping,

                        onClick = { state.toggleLoop() },

                        label = { Text("循环") },

                        shape = CircleShape,

                        border = null,

                        colors = FilterChipDefaults.filterChipColors(

                            containerColor = palette.card,

                            labelColor = palette.textPrimary,

                            selectedContainerColor = palette.wave.copy(alpha = 0.2f),

                            selectedLabelColor = palette.wavePlayed

                        )

                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    FilterChip(

                        selected = state.speed != 1f,

                        onClick = { state.cycleSpeed() },

                        label = {

                            Text(if (state.speed == 1f) "1x" else "${trimSpeed(state.speed)}x")

                        },

                        shape = CircleShape,

                        border = null,

                        colors = FilterChipDefaults.filterChipColors(

                            containerColor = palette.card,

                            labelColor = palette.textPrimary,

                            selectedContainerColor = palette.wave.copy(alpha = 0.2f),

                            selectedLabelColor = palette.wavePlayed

                        )

                    )

                }

            }

        }

    }

}

// ============ 小组件 ============

@Composable

private fun TranscriptRow(

    segment: TranscriptSegment,

    palette: RecorderPalette,

    active: Boolean,

    onClick: () -> Unit

) {

    Surface(

        onClick = onClick,

        shape = RoundedCornerShape(18.dp),

        color = if (active) palette.wave.copy(alpha = if (palette.isDark) 0.16f else 0.12f) else Color.Transparent

    ) {

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            Text(

                text = formatTimestamp(segment.startMs),

                style = MaterialTheme.typography.labelSmall,

                color = if (active) palette.wave else palette.textSecondary

            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(

                text = segment.text,

                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),

                color = if (active) palette.textPrimary else palette.textPrimary.copy(alpha = 0.86f)

            )

        }

    }

}

@Composable

private fun SmallPill(

    text: String,

    palette: RecorderPalette,

    onClick: () -> Unit,

    highlighted: Boolean = false

) {

    Surface(

        onClick = onClick,

        shape = CircleShape,

        color = if (highlighted) palette.wave.copy(alpha = 0.22f) else palette.card,

        contentColor = if (highlighted) palette.wave else palette.textPrimary

    ) {

        Text(

            text = text,

            style = MaterialTheme.typography.labelLarge,

            fontWeight = FontWeight.Medium,

            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)

        )

    }

}

@Composable

private fun RoundIcon(

    container: Color,

    tint: Color,

    onClick: () -> Unit,

    content: @Composable () -> Unit

) {

    Box(

        modifier = Modifier

            .size(42.dp)

            .clip(CircleShape)

            .background(container)

            .clickable(onClick = onClick),

        contentAlignment = Alignment.Center

    ) {

        content()

    }

}

private val DETAIL_DATE = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(millis: Long, showTenths: Boolean): String {

    val tenths = (millis / 100).coerceAtLeast(0)

    val minutes = tenths / 600

    val seconds = (tenths / 10) % 60

    return if (showTenths) {

        String.format(Locale.CHINA, "%02d:%02d.%d", minutes, seconds, tenths % 10)

    } else {

        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)

    }

}

private fun trimSpeed(speed: Float): String =

    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')
