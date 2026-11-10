package com.example.myapplicationfirstapp.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把 m4a 解成 16kHz 单声道 float —— sherpa-onnx 只吃这种格式。
 *
 * 为什么不用 MediaPlayer / SoundPool：它们只负责播，拿不到样本。
 * 这里走 MediaExtractor + MediaCodec，逐块解码、逐块降采样、逐块交给调用方，
 * 全程只保留几百 KB 的中间数据——一小时的录音也不会把内存吃爆。
 */
object AudioDecoder {

    const val TARGET_RATE = 16000

    /** 低通截止：略低于 8kHz 的奈奎斯特，避免降采样把高频折回来 */
    private const val CUTOFF_HZ = 7000.0

    /**
     * 边解边回调。[onChunk] 拿到的是 16k 单声道 float（-1..1），
     * [onProgress] 拿到 0..1 的进度（按解码时间戳估算）。
     */
    suspend fun decode(
        file: File,
        onChunk: (FloatArray) -> Unit,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("这段文件里没有音轨")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音轨格式读不出来")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } ?: 0L

            val resampler = MonoResampler(sourceRate, TARGET_RATE, channels)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var lastPositionUs = 0L

            while (true) {
                coroutineContext.ensureActive()

                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            lastPositionUs = extractor.sampleTime
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)!!
                    if (info.size > 0) {
                        val pcm = buffer.toShortPcm(info.offset, info.size)
                        val mono = toMono(pcm, channels)
                        val resampled = resampler.process(mono)
                        if (resampled.isNotEmpty()) onChunk(resampled)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (durationUs > 0) {
                        onProgress(
                            (lastPositionUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        )
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }

            // 收尾：把重采样器里剩的尾巴也吐出去
            val tail = resampler.flush()
            if (tail.isNotEmpty()) onChunk(tail)
            onProgress(1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun ByteBuffer.toShortPcm(offset: Int, size: Int): ShortArray {
        position(offset)
        limit(offset + size)
        val shorts = ShortArray(size / 2)
        asShortBuffer().get(shorts)
        return shorts
    }

    private fun toMono(pcm: ShortArray, channels: Int): FloatArray {
        if (channels == 1) {
            return FloatArray(pcm.size) { pcm[it] / 32768f }
        }
        val frames = pcm.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until channels) {
                sum += pcm[frame * channels + channel] / 32768f
            }
            sum / channels
        }
    }

    /**
     * 单声道重采样器：两阶巴特沃斯低通 + 线性插值。
     *
     * 直接把 44.1k 抽成 16k 会把 8k 以上的成分折回到人声频段，
     * 听感上是"发闷"，对识别是实打实的噪声，所以先滤再抽。
     */
    private class MonoResampler(
        private val sourceRate: Int,
        private val targetRate: Int,
        private val channels: Int
    ) {
        private val ratio = sourceRate.toDouble() / targetRate
        private val passthrough = sourceRate == targetRate

        private var pending = FloatArray(0)
        private var position = 0.0

        private val filterA = Biquad.lowPass(CUTOFF_HZ, sourceRate.toDouble())
        private val filterB = Biquad.lowPass(CUTOFF_HZ, sourceRate.toDouble())

        fun process(input: FloatArray): FloatArray {
            if (input.isEmpty()) return input
            if (passthrough) return input

            val filtered = FloatArray(input.size) { index ->
                val value = input[index].toDouble()
                filterB.process(filterA.process(value)).toFloat()
            }

            pending = if (pending.isEmpty()) filtered else pending + filtered

            val out = ArrayList<Float>((pending.size / ratio).toInt() + 1)
            while (true) {
                val index = position.toInt()
                if (index + 1 >= pending.size) break
                val fraction = (position - index).toFloat()
                out.add(pending[index] * (1 - fraction) + pending[index + 1] * fraction)
                position += ratio
            }

            val consumed = position.toInt()
            if (consumed > 0) {
                pending = pending.copyOfRange(consumed.coerceAtMost(pending.size), pending.size)
                position -= consumed
            }
            return out.toFloatArray()
        }

        fun flush(): FloatArray {
            val out = FloatArray(pending.size)
            pending.copyInto(out)
            pending = FloatArray(0)
            position = 0.0
            return out
        }
    }

    /** RBJ 二阶低通 */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            fun lowPass(cutoff: Double, sampleRate: Double, q: Double = 0.7071): Biquad {
                val w0 = 2.0 * PI * cutoff / sampleRate
                val cosW = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 - cosW) / 2.0 / a0,
                    b1 = (1.0 - cosW) / a0,
                    b2 = (1.0 - cosW) / 2.0 / a0,
                    a1 = -2.0 * cosW / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }
        }
    }
}
