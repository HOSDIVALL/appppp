package com.example.myapplicationfirstapp.data

/**
 * 正文里的图片引用。
 *
 * 语法沿用 Markdown：`![说明](文件名)`，例如 `![图片](img_1793460504417.jpg)`。
 *
 * 为什么用「文本标记」而不是给图片单独存位置？
 * 因为正文本身就是一个字符串，标记跟着文字一起移动、复制、删除，
 * 不需要维护「图片在哪一段、第几个字」这种很容易错位的结构。
 *
 * 文件本身仍然记在 [Note.images] 里，所以删除笔记、清理回收站时
 * 依然能按那一份清单把文件删掉，不会出现孤儿图片。
 */
object InlineImage {

    internal val PATTERN = Regex("""!\[[^\]\n]*]\(\s*([^)\s]+)\s*\)""")

    /** 正文里引用到的所有文件名（按出现顺序） */
    fun namesIn(content: String): List<String> =
        PATTERN.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

    /** 去掉图片标记，用于列表预览和搜索摘要 */
    fun strip(content: String): String =
        PATTERN.replace(content, "")
            .replace(Regex("""\n[ \t]*\n[ \t]*\n+"""), "\n\n")
            .trim()

    /** 插入到正文用的标记；前后带换行，保证图片独占一段 */
    fun marker(name: String): String = "\n![图片]($name)\n"

    /** 块编辑器里用的单行标记（段落之间由 blocksToContent 负责分隔） */
    fun inline(name: String): String = "![图片]($name)"
}

/**
 * 正文里的录音引用，语法 `!audio(录音 id)`。
 *
 * 和图片一样只存引用：录音文件在录音库里，笔记这边记的是 id（即文件名）。
 * 录音被删掉时，正文里这一块会降级成「录音已删除」，不牵连整篇笔记。
 */
object InlineAudio {

    internal val PATTERN = Regex("""!audio\(\s*([^)\s]+)\s*\)""")

    /** 正文里引用到的录音 id（按出现顺序） */
    fun idsIn(content: String): List<String> =
        PATTERN.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

    fun strip(content: String): String =
        PATTERN.replace(content, "")
            .replace(Regex("""\n[ \t]*\n[ \t]*\n+"""), "\n\n")
            .trim()

    fun marker(id: String): String = "\n!audio($id)\n"

    fun inline(id: String): String = "!audio($id)"
}

/** 去掉正文里所有媒体标记，只留文字：字数统计、列表摘要、搜索都用它 */
fun stripMedia(content: String): String =
    InlineAudio.PATTERN.replace(InlineImage.PATTERN.replace(content, ""), "")
        .replace(Regex("""[ \t]*\n[ \t]*\n[ \t]*\n+"""), "\n\n")
        .trim()

/** 这一行是否只有媒体标记（转清单时应该丢掉这种行） */
fun isMediaOnly(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return InlineImage.PATTERN.matches(trimmed) || InlineAudio.PATTERN.matches(trimmed)
}

/**
 * 修历史数据：老版本把正文转成清单时，会把图片/录音标记当成待办项，
 * 于是正文里留下了 `- [ ] ![图片](x)` 这种行——显示出来就是一串文件名。
 *
 * 这里只处理「清单前缀 + 纯媒体标记」的行，把前缀去掉，让图片回到正文里；
 * 前缀后面还有正文的行不动，避免误伤用户自己写的清单。
 */
fun repairLegacyMediaLines(content: String): String {
    if (content.isEmpty()) return content

    var changed = false
    val fixed = content.lines().map { line ->
        if (LEGACY_MEDIA_LINE.matches(line)) {
            changed = true
            ""
        } else {
            val prefix = LEGACY_MEDIA_PREFIX.find(line)
            if (prefix != null) {
                changed = true
                line.substring(prefix.range.last + 1)
            } else {
                line
            }
        }
    }

    if (!changed) return content
    return fixed.joinToString("\n")
        .replace(Regex("""\n[ \t]*\n[ \t]*\n+"""), "\n\n")
        .trim()
}

/** `- [ ] ` 后面紧跟媒体标记（不吞掉标记本身） */
private val LEGACY_MEDIA_PREFIX =
    Regex("""^\s*[-*+]\s*\[[ xX]]\s*(?=!\[[^\]\n]*]\(|!audio\()""")

/** 只剩一个空的 `- [ ]` 行 */
private val LEGACY_MEDIA_LINE = Regex("""^\s*[-*+]\s*\[[ xX]]\s*$""")

/** 正文里的一处媒体标记，以及它前面有多少行「真正的文字」 */
data class MediaAnchor(val marker: String, val textLineIndex: Int)

/**
 * 收集正文里的媒体标记及其锚点（前面有几行文字）。
 * 清单模式下正文不显示，但标记要留着——切回文本时靠它把图/录音放回原来的位置。
 */
fun mediaAnchors(content: String): List<MediaAnchor> {
    if (content.isEmpty()) return emptyList()

    val matches = buildList {
        InlineImage.PATTERN.findAll(content).forEach { add(it.range.first to it.value) }
        InlineAudio.PATTERN.findAll(content).forEach { add(it.range.first to it.value) }
    }.sortedBy { it.first }

    return matches.map { (index, marker) ->
        MediaAnchor(marker, textLineCountBefore(content, index))
    }
}

private fun textLineCountBefore(content: String, index: Int): Int {
    var count = 0
    var lineStart = 0

    while (lineStart < index) {
        val lineEnd = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
        val line = content.substring(lineStart, minOf(lineEnd, index)).trim()
        if (line.isNotEmpty() && !isMediaOnly(line)) count++
        if (lineEnd >= content.length) break
        lineStart = lineEnd + 1
    }
    return count
}

/**
 * 把 [anchors] 按原来的顺序插回清单转出来的正文里。
 *
 * 锚点是「前面有几行字」，清单一行一项，所以正好对应待办下标；
 * 用户删改过条目时下标会越界，夹到合法范围即可（宁可挪到末尾，也不丢内容）。
 */
fun mergeMediaIntoText(text: String, anchors: List<MediaAnchor>): String {
    if (anchors.isEmpty()) return text

    val lines = text.lines().toMutableList()
    var offset = 0
    anchors.sortedBy { it.textLineIndex }.forEach { anchor ->
        val at = (anchor.textLineIndex + offset).coerceIn(0, lines.size)
        lines.add(at, anchor.marker)
        offset++
    }

    return lines.joinToString("\n")
        .replace(Regex("""\n[ \t]*\n[ \t]*\n+"""), "\n\n")
        .trim()
}

/** 正文拆分后的块：一段文字、一张图片，或者一段录音 */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val name: String) : ContentBlock
    data class Audio(val id: String) : ContentBlock
}

/**
 * 把正文按媒体标记切成若干块。
 *
 * 编辑页直接拿它当「所见即所得」的模型：文字块是可编辑的输入框，
 * 图片/录音块渲染成真的图片和播放器，标记本身不会出现在界面上。
 */
fun parseContentBlocks(content: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    var cursor = 0

    // 图片和录音两类标记按出现位置合并成一条时间线
    val matches = buildList {
        InlineImage.PATTERN.findAll(content).forEach { match ->
            add(MediaMatch(match.range.first, match.range.last, ContentBlock.Image(match.groupValues[1])))
        }
        InlineAudio.PATTERN.findAll(content).forEach { match ->
            add(MediaMatch(match.range.first, match.range.last, ContentBlock.Audio(match.groupValues[1])))
        }
    }.sortedBy { it.start }

    matches.forEach { match ->
        if (match.block is ContentBlock.Image && match.block.name.isBlank()) return@forEach
        if (match.block is ContentBlock.Audio && match.block.id.isBlank()) return@forEach

        val text = content.substring(cursor, match.start).trim('\n')
        if (text.isNotBlank()) blocks += ContentBlock.Text(text.trimEnd())

        blocks += match.block
        cursor = match.end + 1
    }

    val tail = content.substring(cursor).trim('\n')
    if (tail.isNotBlank()) blocks += ContentBlock.Text(tail.trimEnd())

    return blocks
}

private data class MediaMatch(val start: Int, val end: Int, val block: ContentBlock)

/**
 * 编辑页的初始块列表：保证至少有一个可写的文字块，
 * 而且最后一块一定是文字——否则正文以图片/录音结尾时没地方继续打字。
 */
fun editableBlocksOf(content: String): List<ContentBlock> {
    val parsed = parseContentBlocks(content).toMutableList()
    if (parsed.isEmpty() || parsed.last() !is ContentBlock.Text) {
        parsed += ContentBlock.Text("")
    }
    return parsed
}

/**
 * 块 → 正文。段落之间空一行，媒体各占一段。
 *
 * 空文字块不会写成空段落，避免正文里堆一串空行。
 */
fun blocksToContent(blocks: List<ContentBlock>): String =
    blocks.mapNotNull { block ->
        when (block) {
            is ContentBlock.Text -> block.text.trim('\n').ifBlank { null }
            is ContentBlock.Image -> InlineImage.inline(block.name)
            is ContentBlock.Audio -> InlineAudio.inline(block.id)
        }
    }.joinToString("\n\n")
