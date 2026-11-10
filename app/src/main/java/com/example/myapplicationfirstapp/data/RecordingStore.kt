package com.example.myapplicationfirstapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 转写出来的一句话，带它在音频里的起止时间（毫秒） */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * 一段录音。
 *
 * 音频本体是 filesDir/recordings 下的 .m4a；同名 .json 存附属信息
 * （标题、波形包络、转写结果）。**两者是解耦的**——json 丢了或坏了，
 * 录音照样能从文件本身列出来、照样能播，不会因为附属信息损坏而丢音频。
 */
data class Recording(
    /** 文件名，同时当唯一 id 用 */
    val id: String,
    val file: File,
    val createdAt: Long,
    val durationMs: Long,
    /** 用户起的名字，空则回退到日期 */
    val title: String = "",
    /** 波形包络，0..1000，约 10 点/秒降采样后保存，用于列表小波形与播放页大波形 */
    val amplitudes: List<Int> = emptyList(),
    /** 转写结果；空表示还没转过 */
    val segments: List<TranscriptSegment> = emptyList(),
    /** 识别出的语种：zh / en / ja / ko / yue，null 表示还没转 */
    val language: String? = null,
    /** 收藏（星标） */
    val starred: Boolean = false
) {
    val displayName: String
        get() = title.ifBlank { DATE_FORMAT.format(Date(createdAt)) }

    val sizeText: String
        get() {
            val kb = file.length() / 1024.0
            return if (kb < 1024) "${kb.toInt()} KB" else String.format(Locale.CHINA, "%.1f MB", kb / 1024)
        }

    val hasTranscript: Boolean
        get() = segments.isNotEmpty()

    /** 列表卡片上的一句话摘要 */
    val previewText: String
        get() = segments.firstOrNull()?.text.orEmpty()

    /** 全文，导出 / 存成笔记时用 */
    val transcriptText: String
        get() = segments.joinToString("\n") { it.text }
}

private val DATE_FORMAT = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
private val FILE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

/** 录音文件与元数据的读写 */
class RecordingStore(context: Context) {

    private val appContext = context.applicationContext

    private val dir: File
        get() = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 新录音的文件名（还没写任何内容，交给 MediaRecorder 往里录） */
    fun newFile(): File = File(dir, "REC_${FILE_FORMAT.format(Date())}.m4a")

    fun fileOf(id: String): File = File(dir, id)

    /**
     * 扫描目录。时长优先取元数据，没有就用文件自己的；
     * 录制过程中 App 被杀掉的话文件还在，列表里也还能看到并播放。
     */
    suspend fun list(): List<Recording> = withContext(Dispatchers.IO) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.equals("m4a", true) }
            ?: return@withContext emptyList()

        val recordings = files.map { file ->
            val meta = readMeta(file.name)
            Recording(
                id = file.name,
                file = file,
                createdAt = meta?.createdAt ?: file.lastModified(),
                durationMs = meta?.durationMs?.takeIf { it > 0 } ?: readDuration(file),
                title = meta?.title.orEmpty(),
                amplitudes = meta?.amplitudes ?: emptyList(),
                segments = meta?.segments ?: emptyList(),
                language = meta?.language,
                starred = meta?.starred ?: false
            )
        }.sortedByDescending { it.createdAt }

        // 顺手清掉没有对应音频的孤儿 json，别让目录越攒越脏
        val alive = files.map { it.name }.toSet()
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.forEach { json ->
                val audioName = json.nameWithoutExtension + ".m4a"
                if (audioName !in alive) runCatching { json.delete() }
            }

        recordings
    }

    suspend fun save(recording: Recording) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("id", recording.id)
            put("title", recording.title)
            put("createdAt", recording.createdAt)
            put("durationMs", recording.durationMs)
            recording.language?.let { put("lang", it) }
            if (recording.starred) put("starred", true)
            if (recording.amplitudes.isNotEmpty()) {
                put("amps", JSONArray(recording.amplitudes))
            }
            if (recording.segments.isNotEmpty()) {
                put("segs", JSONArray().apply {
                    recording.segments.forEach { seg ->
                        put(JSONObject().apply {
                            put("s", seg.startMs)
                            put("e", seg.endMs)
                            put("t", seg.text)
                        })
                    }
                })
            }
        }
        runCatching {
            metaFile(recording.id).writeText(json.toString(), Charsets.UTF_8)
        }
    }

    fun delete(recording: Recording): Boolean {
        runCatching { metaFile(recording.id).delete() }
        return runCatching { recording.file.delete() }.getOrDefault(false)
    }

    /** 只改元数据里的一项时用，省得外面拼整个 Recording */
    suspend fun updateTitle(id: String, title: String) {
        val meta = readMeta(id) ?: return
        save(meta.toRecording(id, File(dir, id)).copy(title = title))
    }

    private fun metaFile(id: String) = File(dir, id.substringBeforeLast('.') + ".json")

    private fun readMeta(id: String): Meta? = runCatching {
        val file = metaFile(id)
        if (!file.exists()) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        Meta(
            title = json.optString("title"),
            createdAt = json.optLong("createdAt", 0L),
            durationMs = json.optLong("durationMs", 0L),
            language = json.optString("lang").takeIf { it.isNotBlank() },
            starred = json.optBoolean("starred", false),
            amplitudes = json.optJSONArray("amps")?.let { array ->
                List(array.length()) { array.optInt(it, 0) }
            } ?: emptyList(),
            segments = json.optJSONArray("segs")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val text = obj.optString("t")
                    if (text.isBlank()) return@mapNotNull null
                    TranscriptSegment(
                        startMs = obj.optLong("s", 0L),
                        endMs = obj.optLong("e", 0L),
                        text = text
                    )
                }
            } ?: emptyList()
        )
    }.getOrNull()

    private fun readDuration(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private data class Meta(
        val title: String,
        val createdAt: Long,
        val durationMs: Long,
        val language: String?,
        val starred: Boolean,
        val amplitudes: List<Int>,
        val segments: List<TranscriptSegment>
    ) {
        fun toRecording(id: String, file: File) = Recording(
            id = id,
            file = file,
            createdAt = createdAt,
            durationMs = durationMs,
            title = title,
            amplitudes = amplitudes,
            segments = segments,
            language = language,
            starred = starred
        )
    }

    private companion object {
        const val DIR_NAME = "recordings"
    }
}

/** 00:07 / 1:02:34 这样的时长文案 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}

/**
 * 录音计时用的文案：`00:02.0` —— 和 Google 录音机一样带一位小数。
 * 录音时看一眼就知道「确实在走」，比整秒更有反馈。
 */
fun formatClock(millis: Long): String {
    val tenths = (millis / 100).coerceAtLeast(0)
    val minutes = tenths / 600
    val seconds = (tenths / 10) % 60
    val decimal = tenths % 10
    return if (minutes >= 60) {
        val hours = minutes / 60
        String.format(Locale.CHINA, "%d:%02d:%02d.%d", hours, minutes % 60, seconds, decimal)
    } else {
        String.format(Locale.CHINA, "%02d:%02d.%d", minutes, seconds, decimal)
    }
}

/** 转写段落前的时间戳：01:12 */
fun formatTimestamp(millis: Long): String = formatDuration(millis)
