package com.example.myapplicationfirstapp.data

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一次转写的结果：分句 + 识别出的语种 */
data class TranscriptResult(
    val segments: List<TranscriptSegment>,
    val language: String?
)

/**
 * 本地转写：m4a → 16k PCM → 能量 VAD 切句 → SenseVoice 逐句识别。
 *
 * 全部在手机上跑，音频不出设备。代价是首次要下 228MB 模型，
 * 以及一次只能跑一段（模型加载就要几百 MB 内存，并发没有意义）。
 */
class SpeechTranscriber(context: Context) {

    private val appContext = context.applicationContext
    private val store = AsrModelStore(appContext)
    private val lock = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    val isModelReady: Boolean
        get() = store.isReady

    /** 已经下好多少字节，用来在界面上显示断点续传的进度 */
    fun downloadedBytes(): Long = store.currentBytes()

    suspend fun ensureModel(onProgress: (downloaded: Long, total: Long) -> Unit) {
        store.ensureDownloaded(onProgress)
    }

    /**
     * 转写一整段录音。
     *
     * [onProgress] 是 0..1；转写过程中解码和识别是流水线交替进行的，
     * 所以进度按解码位置估算——识别一直比解码快，不会拖后腿。
     */
    suspend fun transcribe(
        file: File,
        onProgress: (Float) -> Unit = {}
    ): TranscriptResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val asr = obtainRecognizer()
                ?: return@withContext TranscriptResult(emptyList(), null)

            val segments = ArrayList<TranscriptSegment>()
            // 每句都可能判出不同语种，最后取出现最多的那个
            val languageVotes = HashMap<String, Int>()

            val vad = EnergyVad { samples, startMs, endMs ->
                val result = recognize(asr, samples)
                val text = cleanText(result.first)
                // 纯标点、纯符号的段落是噪声被硬识别出来的，直接丢
                if (isMeaningful(text)) {
                    normalizeLanguage(result.second)?.let { code ->
                        languageVotes[code] = (languageVotes[code] ?: 0) + 1
                    }
                    segments += TranscriptSegment(startMs, endMs, text)
                }
            }

            Log.i(TAG, "开始解码 ${file.name}")
            AudioDecoder.decode(
                file = file,
                onChunk = { vad.accept(it) },
                onProgress = { fraction -> onProgress(fraction * 0.92f) }
            )
            Log.i(TAG, "解码完成，切出 ${segments.size} 句")
            vad.flush()
            onProgress(1f)

            TranscriptResult(segments, languageVotes.maxByOrNull { it.value }?.key)
        }
    }

    /** 预热：让界面在"开始转写"时先把模型读进内存，别在用户等着时卡一下 */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        runCatching { obtainRecognizer() }
        Unit
    }

    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun obtainRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!store.isReady) return null

        val senseVoiceModel = OfflineSenseVoiceModelConfig(
            model = store.modelFile.absolutePath,
            language = "auto",
            useInverseTextNormalization = true
        )
        val asrModelConfig = OfflineModelConfig().apply {
            tokens = store.tokensFile.absolutePath
            numThreads = 2
            modelType = "sense_voice"
            senseVoice = senseVoiceModel
        }
        val config = OfflineRecognizerConfig().apply {
            modelConfig = asrModelConfig
        }

        Log.i(TAG, "创建识别器：model=${store.modelFile.absolutePath} tokens=${store.tokensFile.absolutePath}")
        return runCatching { AsrFactory.create(config) }
            .onSuccess { Log.i(TAG, "识别器创建成功") }
            .onFailure { Log.e(TAG, "识别器创建失败", it) }
            .getOrNull()
            ?.also { recognizer = it }
    }

    private fun recognize(asr: OfflineRecognizer, samples: FloatArray): Pair<String, String> {
        Log.i(TAG, "识别一段 ${samples.size / 16} ms")
        val stream = asr.createStream()
        return try {
            stream.acceptWaveform(samples, AudioDecoder.TARGET_RATE)
            asr.decode(stream)
            val result = asr.getResult(stream)
            (result.text ?: "") to (result.lang ?: "")
        } catch (e: Throwable) {
            "" to ""
        } finally {
            runCatching { stream.release() }
        }
    }

    /** 至少得有一个字母、数字或汉字，否则就是噪声被硬凑出来的标点 */
    private fun isMeaningful(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /** SenseVoice 会在文本里带 <|zh|><|Speech|> 之类的标签，去掉只留正文 */
    private fun cleanText(raw: String): String {
        val withoutTags = raw.replace(TAG_REGEX, " ")
        return withoutTags
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeLanguage(raw: String?): String? {
        val value = raw?.lowercase()?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("zh") || value.contains("chinese") || value.contains("中文") -> "zh"
            value.startsWith("en") || value.contains("english") -> "en"
            value.startsWith("ja") || value.contains("japanese") -> "ja"
            value.startsWith("ko") || value.contains("korean") -> "ko"
            value.startsWith("yue") || value.contains("cantonese") || value.contains("粤语") -> "yue"
            else -> null
        }
    }

    private companion object {
        const val TAG = "AsrTrace"
        val TAG_REGEX = Regex("<\\|[^|]*\\|>")
    }
}
