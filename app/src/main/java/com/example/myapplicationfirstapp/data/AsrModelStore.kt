package com.example.myapplicationfirstapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地语音识别模型的存放与下载。
 *
 * SenseVoice Small（int8）约 228MB，塞进 APK 会让安装包从 51MB 涨到 280MB，
 * 而且大部分人根本用不上——所以改成首次使用时下载，存在私有目录里。
 *
 * 下载源用 ModelScope 而不是 GitHub / HuggingFace：国内直连可用、免登录，
 * 而且支持断点续传（换网、锁屏中断了能接着下，不用从头再来）。
 */
class AsrModelStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "asr").apply { mkdirs() }

    val modelFile = File(dir, MODEL_NAME)
    val tokensFile = File(dir, TOKENS_NAME)

    /** 两个文件都在、体积也像样才算可用 */
    val isReady: Boolean
        get() = modelFile.length() >= MIN_MODEL_BYTES && tokensFile.length() >= MIN_TOKENS_BYTES

    /** 已下好的部分占目标的比例，用于断点续传的进度显示 */
    fun currentBytes(): Long = modelFile.length() + tokensFile.length()

    suspend fun ensureDownloaded(onProgress: (downloaded: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isReady) return@withContext

            // 小文件先下，失败也不心疼
            downloadFile(TOKENS_URL, tokensFile, MIN_TOKENS_BYTES) { _, _ -> }
            downloadFile(MODEL_URL, modelFile, MIN_MODEL_BYTES, onProgress)
        }

    fun deleteAll() {
        runCatching { modelFile.delete() }
        runCatching { tokensFile.delete() }
    }

    /**
     * 带断点续传的下载：先写到 .part，成功后改名。
     * 中途被杀掉，下次接着从 .part 的长度往下要。
     */
    private fun downloadFile(
        url: String,
        target: File,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        if (target.length() >= minBytes) return

        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NotesGO/1.0 (Android)")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // 服务端不认断点就从头来
                if (code == 416) {
                    part.delete()
                    existing = 0
                    throw IllegalStateException("下载失败（$code），请重试")
                }
                throw IllegalStateException("下载失败（$code）")
            }

            val appending = code == 206 && existing > 0
            if (!appending) {
                existing = 0
                part.delete()
            }

            val contentLength = connection.contentLengthLong.coerceAtLeast(0)
            val total = if (contentLength > 0) contentLength + existing else minBytes

            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.seek(existing)
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport > 2L * 1024 * 1024) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }

            if (part.length() < minBytes) {
                throw IllegalStateException("下载不完整，请重试")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("保存模型失败")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private companion object {
        const val MODEL_NAME = "model.int8.onnx"
        const val TOKENS_NAME = "tokens.txt"

        /** 2026-11 实际体积 239,233,841；给点余量，别因为版本微调就判定损坏 */
        const val MIN_MODEL_BYTES = 200L * 1024 * 1024
        const val MIN_TOKENS_BYTES = 200L * 1024

        const val BASE = "https://modelscope.cn/models/poloniumrock/SenseVoiceSmallOnnx/resolve/master"
        const val MODEL_URL = "$BASE/$MODEL_NAME"
        const val TOKENS_URL = "$BASE/$TOKENS_NAME"
    }
}
