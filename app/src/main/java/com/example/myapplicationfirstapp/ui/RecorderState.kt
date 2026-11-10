package com.example.myapplicationfirstapp.ui

import android.content.Context

import android.media.AudioAttributes

import android.media.MediaPlayer

import android.media.MediaRecorder

import android.media.PlaybackParams

import android.os.Build

import android.os.SystemClock

import androidx.compose.runtime.Stable

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import com.example.myapplicationfirstapp.data.AppSettings

import com.example.myapplicationfirstapp.data.AsrModelStore

import com.example.myapplicationfirstapp.data.Recording

import com.example.myapplicationfirstapp.data.RecordingStore

import com.example.myapplicationfirstapp.data.SpeechTranscriber

import com.example.myapplicationfirstapp.ui.haptics.HapticCue

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.channels.BufferOverflow

import kotlinx.coroutines.flow.MutableSharedFlow

import kotlinx.coroutines.flow.SharedFlow

import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.delay

import kotlinx.coroutines.isActive

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import java.io.File

import kotlin.math.log10

/**

 * 录音与播放的状态中枢。

 *

 * 录音走 MediaRecorder 写 AAC 的 .m4a；播放走 MediaPlayer。

 * 和上一版最大的不同是**接了真实振幅**：录音时每 100ms 读一次

 * getMaxAmplitude，换算成 dB 后归一到 0..1000，既能实时画波形，

 * 也能存下来在列表和详情页里还原成一模一样的形状。

 *

 * 另外补齐了暂停/继续、拖动定位、倍速、循环——这些是播放页要用的。

 */

@Stable

class RecorderState(

    private val context: Context,

    private val settings: AppSettings,

    private val scope: CoroutineScope

) {

    private val store = RecordingStore(context)

    private val transcriber = SpeechTranscriber(context)

    /**
     * 录音流程的一次性触觉事件。
     *
     * replay = 0：UI 没在收集时（后台、别的页面）事件直接丢掉，回来不会补震。
     * extraBufferCapacity = 1 + DROP_OLDEST：连点最多留最后一次，不会排队重放。
     */
    private val _hapticCues = MutableSharedFlow<HapticCue>(

        replay = 0,

        extraBufferCapacity = 1,

        onBufferOverflow = BufferOverflow.DROP_OLDEST

    )

    val hapticCues: SharedFlow<HapticCue> = _hapticCues.asSharedFlow()

    private fun cue(cue: HapticCue) {

        _hapticCues.tryEmit(cue)

    }

    private var recorder: MediaRecorder? = null

    private var player: MediaPlayer? = null

    private var currentFile: File? = null

    private var tickJob: Job? = null

    private var playJob: Job? = null

    private var segmentStartedAt = 0L

    private var accumulatedMs = 0L

    /** 整段录音的全部采样点（保存时降采样） */

    private val allAmps = ArrayList<Int>(1024)

    /** 波形卡片上滚动显示的那一小段（环形，只留最近若干点） */

    private val recentAmps = ArrayDeque<Int>(LIVE_POINTS)

    var recordings by mutableStateOf<List<Recording>>(emptyList())

        private set

    var isRecording by mutableStateOf(false)

        private set

    var isPaused by mutableStateOf(false)

        private set

    var elapsedMs by mutableStateOf(0L)

        private set

    /** 实时波形：最近 [LIVE_POINTS] 个采样点 */

    var liveAmplitudes by mutableStateOf<List<Int>>(emptyList())

        private set

    // ---------- 播放 ----------

    /** 当前装载进播放器的录音，null 表示没有 */

    var loaded by mutableStateOf<Recording?>(null)

        private set

    var isPlaying by mutableStateOf(false)

        private set

    var positionMs by mutableStateOf(0L)

        private set

    var playDurationMs by mutableStateOf(0L)

        private set

    /**

     * seek 保护窗截止时刻（elapsedRealtime）。

     * MediaPlayer 的 seek 是异步的：刚 seek 完的一小段时间里 currentPosition

     * 仍返回旧位置，tick 循环若照单全收就会把进度拉回去（看起来像 ±5 秒失灵）。

     */

    private var seekGuardUntil = 0L
    private var seekTargetMs = 0L
    private var seekStartedAt = 0L

    /** Shared notification for discontinuities in the playback timeline. */
    var timelineRevision by mutableStateOf(0L)
        private set

    /** 0.5x / 0.75x / 1x / 1.25x / 1.5x / 2x / 3x */

    var speed by mutableStateOf(1f)

        private set

    var looping by mutableStateOf(false)

        private set

    /** 一次性提示，界面消费完置空 */

    var message by mutableStateOf<String?>(null)

    // ---------- 本地转写 ----------

    /** 模型是否已下好（下载/删除后要手动刷新，所以单独存一份） */

    var modelReady by mutableStateOf(false)

        private set

    /** 模型下载进度 0..1，null 表示没在下 */

    var modelProgress by mutableStateOf<Float?>(null)

        private set

    /** 正在转写的录音 id */

    var transcribingId by mutableStateOf<String?>(null)

        private set

    var transcribeProgress by mutableStateOf(0f)

        private set

    suspend fun load() {

        recordings = store.list()

        modelReady = transcriber.isModelReady

    }

    // ============ 转写 ============

    /** 下载 SenseVoice 模型（约 228MB，支持断点续传） */

    fun downloadModel() {

        if (modelProgress != null) return

        modelProgress = 0f

        scope.launch {

            runCatching {

                transcriber.ensureModel { done, total ->

                    if (total > 0) {

                        modelProgress = (done.toFloat() / total).coerceIn(0f, 1f)

                    }

                }

            }.onSuccess {

                modelReady = transcriber.isModelReady

                message = if (modelReady) "转写模型已就绪" else "模型没下完整，再试一次"

            }.onFailure {

                modelReady = transcriber.isModelReady

                message = "模型下载失败：${it.message ?: "网络异常"}"

            }

            modelProgress = null

        }

    }

    /** 手动转写某一段 */

    fun transcribe(recording: Recording) {

        if (transcribingId != null || modelProgress != null) return

        if (!transcriber.isModelReady) {

            message = "先在录音页下载转写模型"

            return

        }

        transcribingId = recording.id

        transcribeProgress = 0f

        scope.launch {

            val result = runCatching {

                transcriber.transcribe(recording.file) { fraction ->

                    transcribeProgress = fraction

                }

            }.getOrNull()

            transcribingId = null

            transcribeProgress = 0f

            if (result == null) {

                message = "转写失败，这段音频可能读不出来"

                return@launch

            }

            if (result.segments.isEmpty()) {

                message = "这段里没听出人声"

                return@launch

            }

            store.save(

                recording.copy(segments = result.segments, language = result.language)

            )

            message = "转好了，共 ${result.segments.size} 句"

            load()

        }

    }

    fun deleteModel() {

        transcriber.release()

        runCatching { AsrModelStore(context).deleteAll() }

        modelReady = false

        message = "已删除本地模型"

    }

    // ============ 录制 ============

    /** 开始录音。权限由界面负责申请，这里只负责录 */

    fun start() {

        if (isRecording) return

        stopPlayback()

        val file = store.newFile()

        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            MediaRecorder(context)

        } else {

            @Suppress("DEPRECATION")

            MediaRecorder()

        }

        val started = runCatching {

            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)

            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            newRecorder.setAudioSamplingRate(44_100)

            newRecorder.setAudioEncodingBitRate(128_000)

            newRecorder.setOutputFile(file.absolutePath)

            newRecorder.prepare()

            newRecorder.start()

        }.isSuccess

        if (!started) {

            runCatching { newRecorder.release() }

            runCatching { file.delete() }

            message = "录音没能启动，检查一下麦克风权限"

            // 采音都没开始，这里震一下不会进录音
            cue(HapticCue.Failure)

            return

        }

        recorder = newRecorder

        currentFile = file

        allAmps.clear()

        recentAmps.clear()

        liveAmplitudes = emptyList()

        segmentStartedAt = System.currentTimeMillis()

        accumulatedMs = 0L

        elapsedMs = 0L

        isPaused = false

        isRecording = true

        startTicking()

    }

    /** 暂停录制：音频流断开，恢复后接着写同一个文件 */

    fun pause() {

        if (!isRecording || isPaused) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        runCatching { recorder?.pause() }.onSuccess {

            accumulatedMs += System.currentTimeMillis() - segmentStartedAt

            isPaused = true

            elapsedMs = accumulatedMs

            // 放在 pause() 真正返回之后：这时音频流已经断开，
            // 震动不会被自己的麦克风录进去
            cue(HapticCue.Paused)

        }.onFailure { message = "这台机器不支持中途暂停"

            cue(HapticCue.Failure)

        }

    }

    fun resume() {

        if (!isRecording || !isPaused) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        runCatching { recorder?.resume() }.onSuccess {

            segmentStartedAt = System.currentTimeMillis()

            isPaused = false

            // 这里故意不震：resume 返回时麦克风已经重新打开，
            // 任何震动都可能被自己录进去。录音优先于手感。

        }.onFailure { message = "继续录音失败"

            cue(HapticCue.Failure)

        }

    }

    /**

     * 停止并保存。

     * [discard] 为 true 表示用户点了 Delete，直接丢掉这段文件。

     */

    fun stop(discard: Boolean = false) {

        val active = recorder ?: return

        val file = currentFile

        val duration = if (isPaused) accumulatedMs else {

            accumulatedMs + (System.currentTimeMillis() - segmentStartedAt)

        }

        isRecording = false

        isPaused = false

        recorder = null

        currentFile = null

        tickJob?.cancel()

        tickJob = null

        // stop() 在录制过短时会抛 RuntimeException，吞掉并按失败处理

        val stopped = runCatching { active.stop() }.isSuccess

        runCatching { active.release() }

        val amps = allAmps.toList()

        allAmps.clear()

        recentAmps.clear()

        liveAmplitudes = emptyList()

        elapsedMs = 0L

        scope.launch {

            if (discard) {

                val removed = file == null || runCatching { file.delete() }.getOrDefault(false)

                message = "已丢弃这段录音"

                load()

                // 文件真的删掉了才给成功反馈；删不掉算失败
                cue(if (removed) HapticCue.Discarded else HapticCue.Failure)

                return@launch

            }

            if (!stopped || duration < MIN_DURATION_MS) {

                // 按一下就松手 / 太短：不留半截文件
                runCatching { file?.delete() }

                message = if (stopped) "时间太短，没有保存" else "这段录音没录上"

                load()

                cue(HapticCue.Failure)

                return@launch

            }

            val saved = file

            val savedOk = if (saved != null) {

                runCatching {

                    store.save(

                        Recording(

                            id = saved.name,

                            file = saved,

                            createdAt = saved.lastModified().takeIf { it > 0 }

                                ?: System.currentTimeMillis(),

                            durationMs = duration,

                            amplitudes = downsample(amps)

                        )

                    )

                }.isSuccess

            } else false

            if (!savedOk) {

                message = "这段录音没录上"

                load()

                cue(HapticCue.Failure)

                return@launch

            }

            message = "已保存 ${formatShort(duration)}"

            load()

            // 文件写完之后才发成功反馈
            cue(HapticCue.Saved)

            // 录完自动转：模型没下好就先不动，别偷偷跑 228MB 流量

            if (settings.autoTranscribe && transcriber.isModelReady) {

                recordings.firstOrNull { it.id == saved?.name }?.let { transcribe(it) }

            }

        }

    }

    private fun startTicking() {

        tickJob?.cancel()

        tickJob = scope.launch {

            while (isActive && isRecording) {

                if (!isPaused) {

                    elapsedMs = accumulatedMs + (System.currentTimeMillis() - segmentStartedAt)

                    sampleAmplitude()

                }

                delay(SAMPLE_INTERVAL_MS)

            }

        }

    }

    private fun sampleAmplitude() {

        val raw = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

        val level = normalize(raw)

        allAmps.add(level)

        if (recentAmps.size >= LIVE_POINTS) recentAmps.removeFirst()

        recentAmps.addLast(level)

        liveAmplitudes = recentAmps.toList()

    }

    // ============ 播放 ============

    /** 把某段录音装进播放器（不自动播） */

    fun open(recording: Recording) {

        if (loaded?.id == recording.id && player != null) return

        releasePlayer()

        val newPlayer = MediaPlayer()

        val ok = runCatching {

            newPlayer.setAudioAttributes(

                AudioAttributes.Builder()

                    .setUsage(AudioAttributes.USAGE_MEDIA)

                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

                    .build()

            )

            newPlayer.setDataSource(recording.file.absolutePath)

            newPlayer.prepare()

        }.isSuccess

        if (!ok) {

            runCatching { newPlayer.release() }

            message = "这段录音打不开了"

            return

        }

        newPlayer.isLooping = looping

        newPlayer.setOnCompletionListener {

            isPlaying = false

            playJob?.cancel()

            if (!looping) positionMs = playDurationMs
            timelineRevision++

        }

        player = newPlayer

        loaded = recording

        playDurationMs = newPlayer.duration.toLong().coerceAtLeast(0L)

        positionMs = 0L

        isPlaying = false

    }

    /** 列表行上的小播放钮：同一段就切换播放/暂停，不同段就换载再播 */

    fun togglePlayInline(recording: Recording) {

        if (loaded?.id != recording.id) {

            open(recording)

            startPlayback()

        } else {

            togglePlay()

        }

    }

    fun togglePlay() {

        if (player == null) {

            loaded?.let { open(it) } ?: return

        }

        if (isPlaying) pausePlayback() else startPlayback()

    }

    fun startPlayback() {

        val active = player ?: return

        runCatching { active.start() }.onSuccess {

            isPlaying = true

            applySpeed()

            startPlayTicking()

        }.onFailure { message = "播放失败" }

    }

    fun pausePlayback() {

        runCatching { player?.pause() }

        isPlaying = false

        playJob?.cancel()

    }

    fun seekTo(ms: Long) {
        val active = player ?: return
        val target = ms.coerceIn(0L, playDurationMs)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                active.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            } else {
                active.seekTo(target.toInt())
            }
        }.onSuccess {
            positionMs = target
            seekTargetMs = target
            seekStartedAt = SystemClock.elapsedRealtime()
            seekGuardUntil = seekStartedAt + 1_000L
            timelineRevision++
        }.onFailure {
            message = "播放定位失败，请重试"
        }
    }

    fun skipBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun changeSpeed(value: Float) {

        speed = value.coerceIn(0.5f, 3f)

        if (isPlaying) applySpeed()

    }

    fun cycleSpeed() {

        val index = SPEEDS.indexOfFirst { it == speed }

        changeSpeed(SPEEDS[(index + 1) % SPEEDS.size])

    }

    fun toggleLoop() {

        looping = !looping

        runCatching { player?.isLooping = looping }

    }

    /** 详情页退出时调用，把播放器彻底放掉 */

    fun releasePlayer() {

        playJob?.cancel()

        playJob = null

        runCatching { player?.stop() }

        runCatching { player?.release() }

        player = null

        loaded = null

        isPlaying = false

        positionMs = 0L

        playDurationMs = 0L

        speed = 1f
        seekGuardUntil = 0L
        seekTargetMs = 0L
        seekStartedAt = 0L
        timelineRevision++

    }

    @Deprecated("保留旧名字，外部调用语义未变")

    fun stopPlayback() = pausePlayback()

    private fun applySpeed() {

        val active = player ?: return

        runCatching {

            active.playbackParams = (active.playbackParams ?: PlaybackParams())

                .setSpeed(speed)

                .setPitch(1f)

        }

    }

    private fun startPlayTicking() {

        playJob?.cancel()

        playJob = scope.launch {

            while (isActive && isPlaying) {

                runCatching { player?.currentPosition?.toLong() }

                    .getOrNull()

                    ?.let { tick ->

                        val now = SystemClock.elapsedRealtime()
                        val guarded = now < seekGuardUntil
                        val expectedAdvance = if (isPlaying) {
                            ((now - seekStartedAt).coerceAtLeast(0L) * speed).toLong()
                        } else 0L
                        val lower = (seekTargetMs - 250L).coerceAtLeast(0L)
                        val upper = seekTargetMs + expectedAdvance + 250L
                        if (!guarded || tick in lower..upper) {
                            if (guarded) seekGuardUntil = 0L
                            val next = tick.coerceIn(0L, playDurationMs)
                            if (next < positionMs - 250L) timelineRevision++
                            positionMs = next
                        }

                    }

                delay(60)

            }

        }

    }

    // ============ 管理 ============

    fun delete(recording: Recording) {

        if (loaded?.id == recording.id) releasePlayer()

        val removed = store.delete(recording)

        message = if (removed) "已删除这段录音" else "删除失败"

        // 删除真的完成了才震；确认框弹出时不动
        cue(if (removed) HapticCue.Discarded else HapticCue.Failure)

        scope.launch { load() }

    }

    fun deleteById(id: String) {

        recordings.firstOrNull { it.id == id }?.let(::delete)

    }

    /** 重命名。传空字符串就等于恢复成日期标题 */

    fun rename(recording: Recording, title: String) {

        scope.launch {

            store.save(recording.copy(title = title.trim()))

            load()

        }

    }

    /** 收藏 / 取消收藏 */

    fun toggleStar(recording: Recording) {

        val target = recording.copy(starred = !recording.starred)

        scope.launch {

            store.save(target)

            load()

        }

        message = if (target.starred) "已收藏" else "已取消收藏"

    }

    /** 离开录音页 / 退出时收尾，别让麦克风和扬声器一直占着 */

    fun release() {

        if (isRecording) stop()

        releasePlayer()

        transcriber.release()

    }

    private fun formatShort(millis: Long): String {

        val seconds = (millis / 1000).coerceAtLeast(0)

        return if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"

    }

    private fun normalize(raw: Int): Int {

        if (raw <= 0) return 0

        // getMaxAmplitude 给的是 0..32767 的线性峰值；转成 dB 再压到 0..1000，

        // 否则小声说话画出来几乎是一条平线

        val db = 20.0 * log10(raw.toDouble() / 32767.0)

        val level = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0)

        return (level * 1000).toInt()

    }

    /** 保存前把上万个采样点压到 ≤2000 个，取每段最大值以免削掉峰值 */

    private fun downsample(source: List<Int>, target: Int = MAX_POINTS): List<Int> {

        if (source.size <= target) return source

        val out = ArrayList<Int>(target)

        val step = source.size.toDouble() / target

        for (i in 0 until target) {

            val from = (i * step).toInt()

            val to = (((i + 1) * step).toInt()).coerceAtMost(source.size)

            var peak = 0

            for (j in from until to) if (source[j] > peak) peak = source[j]

            out.add(peak)

        }

        return out

    }

    companion object {

        /** 实时波形保留最近多少个点（约 24 秒） */

        private const val LIVE_POINTS = 240

        /** 保存时最多留多少个包络点 */

        private const val MAX_POINTS = 2000

        private const val SAMPLE_INTERVAL_MS = 100L

        /** 短于 1 秒的录音当作误触 */

        private const val MIN_DURATION_MS = 1000L

        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    }

}
