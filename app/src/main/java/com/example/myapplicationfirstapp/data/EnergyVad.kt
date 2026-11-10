package com.example.myapplicationfirstapp.data

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 极简的能量 VAD：把长音频切成一句一句。
 *
 * 为什么自己写而不是上 Silero：那个模型虽然只有 0.6MB，但要多一份模型文件、
 * 多一条下载链路。笔记录音大多是单人在安静环境说话，能量门限配合自适应底噪
 * 已经够用；真遇到嘈杂环境再换也不迟。
 *
 * 切段的三个约束：
 * - 太短（<0.4 秒）的当噪声丢掉；
 * - 段尾留 400ms 静音再收，避免把尾音切断；
 * - 超过 25 秒强制断开——SenseVoice 一次吃不下太长的音频。
 */
class EnergyVad(
    private val sampleRate: Int = 16000,
    private val onSegment: (samples: FloatArray, startMs: Long, endMs: Long) -> Unit
) {
    private val frameSize = sampleRate * FRAME_MS / 1000
    private val thresholdFloor = 0.006f

    private val frame = FloatArray(frameSize)
    private var frameFill = 0
    private var totalSamples = 0L

    private var noiseFloor = 0.012f
    private var inSpeech = false
    private var segStartSample = 0L
    private var silenceFrames = 0

    /** 每段最多 30 秒的缓冲，复用同一块内存 */
    private val segBuffer = FloatArray(sampleRate * 30)
    private var segSize = 0

    /** 说话前一瞬的音频，用来补上被门限切掉的口气和字头 */
    private val history = ArrayDeque<FloatArray>(PRE_ROLL_FRAMES)

    fun accept(samples: FloatArray) {
        var index = 0
        while (index < samples.size) {
            val take = minOf(frameSize - frameFill, samples.size - index)
            System.arraycopy(samples, index, frame, frameFill, take)
            frameFill += take
            index += take

            if (frameFill == frameSize) {
                handleFrame(frame.copyOf())
                frameFill = 0
            }
        }
    }

    fun flush() {
        if (inSpeech) closeSegment()
        if (frameFill > 0) {
            handleFrame(frame.copyOf(frameFill).let { it.copyOf(frameSize) })
            frameFill = 0
        }
    }

    private fun handleFrame(samples: FloatArray) {
        val frameStart = totalSamples
        totalSamples += frameSize

        var sum = 0f
        for (value in samples) sum += value * value
        val rms = sqrt(sum / samples.size)

        if (!inSpeech) {
            // 说话时别让底噪被自己的声音带跑
            noiseFloor = if (rms < noiseFloor) {
                noiseFloor * 0.9f + rms * 0.1f
            } else {
                noiseFloor * 0.999f + rms * 0.001f
            }
        }

        val threshold = max(thresholdFloor, noiseFloor * 3.2f)
        val speech = rms > threshold

        if (!inSpeech) {
            history.addLast(samples)
            if (history.size > PRE_ROLL_FRAMES) history.removeFirst()

            if (speech) {
                inSpeech = true
                silenceFrames = 0
                segSize = 0
                segStartSample = frameStart - history.size.toLong() * frameSize

                history.forEach { buffered ->
                    appendToSegment(buffered)
                }
                history.clear()
            }
        } else {
            appendToSegment(samples)

            if (speech) {
                silenceFrames = 0
            } else {
                silenceFrames++
            }

            val tooLong = segSize >= segBuffer.size - frameSize
            val longSilence = silenceFrames * FRAME_MS >= TAIL_MS

            if (tooLong || longSilence) {
                closeSegment()
                // 还在说就立刻开新的一段，别把后面的话丢掉
                if (speech) {
                    inSpeech = true
                    silenceFrames = 0
                    segSize = 0
                    segStartSample = totalSamples
                }
            }
        }
    }

    private fun appendToSegment(samples: FloatArray) {
        if (segSize + samples.size > segBuffer.size) {
            closeSegment()
            segStartSample = totalSamples - samples.size
            segSize = 0
        }
        System.arraycopy(samples, 0, segBuffer, segSize, samples.size)
        segSize += samples.size
    }

    private fun closeSegment() {
        inSpeech = false
        silenceFrames = 0

        // 段尾那截静音不要，留 100ms 缓冲
        val trim = (TAIL_TRIM_MS * sampleRate / 1000).coerceAtMost(segSize / 2)
        val usable = segSize - trim
        if (usable <= sampleRate * MIN_SEGMENT_MS / 1000) {
            segSize = 0
            return
        }

        val startMs = segStartSample * 1000 / sampleRate
        val endMs = (segStartSample + usable) * 1000 / sampleRate
        onSegment(segBuffer.copyOf(usable), startMs, endMs)
        segSize = 0
    }

    private companion object {
        const val FRAME_MS = 20
        const val PRE_ROLL_FRAMES = 15      // 300ms
        const val TAIL_MS = 400
        const val TAIL_TRIM_MS = 100
        const val MIN_SEGMENT_MS = 600
    }
}
