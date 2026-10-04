package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleStyleDef
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.subtitle.internal.Timecode

/**
 * 单个解析器的输出。
 *
 * 刻意不复用 [com.multisuperplayer.core.model.SubtitleDocument]：解析器不知道
 * 轨道从哪来（外挂文件？内嵌轨？ASR 生成？），那些元信息由调用方组装。
 */
data class ParseResult(
    val format: SubtitleFormat,
    val cues: List<SubtitleCue>,
    val styles: Map<String, SubtitleStyleDef> = emptyMap(),
    val defaultStyle: SubtitleStyleDef? = null,
    val metadata: Map<String, String> = emptyMap(),
    /**
     * 非致命问题（跳过的坏行、时间轴倒挂…）。
     * 收集而不抛异常：一行坏掉不该让整部片子没字幕。
     *
     * 类型是 [MspText] 而不是 `String`：这些句子会**原样列给用户看**
     * （字幕列表里逐条显示），而它们以前是硬编码的中文——切到英文界面
     * 就是一句中文，而且没有任何机制能让译者看见它。
     */
    val warnings: List<MspText> = emptyList(),
)

/**
 * 解析不了（格式判错、文件被截断到没有一条完整 cue）。
 *
 * 带的是 [MspText] 而不是 `message: String`：这句话会显示给用户（见
 * `SubtitleRepository.detailOr`），必须能翻译，而 `message` 只能是一个
 * 已经定死的字符串。这里的 `message` 是 `text.toString()` 的结果，
 * **只用来写日志**，不要拿去显示。
 */
class SubtitleParseException(val text: MspText) : Exception(text.toString())

interface SubtitleParser {
    val format: SubtitleFormat

    /** 支持的格式集合（LRC 解析器同时吃 LRC 与增强型 LRC）。 */
    val supportedFormats: Set<SubtitleFormat> get() = setOf(format)

    /**
     * @param content 已解码为 String 的正文字本（BOM 与换行已由
     *   [SubtitleParserRegistry] 归一化）。
     */
    fun parse(content: String): ParseResult
}

internal object SubtitleText {
    private val blankLine = Regex("""\r?\n[ \t]*\r?\n""")
    private val anyNewline = Regex("""\r\n|\r|\n""")

    fun normalizeNewlines(content: String): String =
        anyNewline.replace(content, "\n").removePrefix("\uFEFF")

    /** 按空行切分成块（SRT/VTT 的基本单位）。 */
    fun splitBlocks(content: String): List<List<String>> =
        blankLine.split(content)
            .map { block -> block.split('\n').map { it.trimEnd() } }
            .filter { block -> block.any { it.isNotBlank() } }

    fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", "\u00A0")
        .replace("&amp;", "&")

    /** `<br>`（SRT 常见的 HTML 换行）→ 真实换行。 */
    fun htmlBreaksToNewlines(text: String): String =
        Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE).replace(text, "\n")
}

/** 时间轴处理后置：排序 + 修正结束时间。 */
internal object CuePostProcess {

    /**
     * @param fillMissingEnd 结束时间缺失/倒挂时，是否用下一条的开始时间兜底。
     *   歌词（LRC）必须这么做——LRC 只标开始时间。
     */
    fun finish(cues: List<SubtitleCue>, fillMissingEnd: Boolean, tailDurationMs: Long = 10_000L): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        return sorted.mapIndexed { index, cue ->
            val next = sorted.getOrNull(index + 1)
            val end = when {
                cue.endMs > cue.startMs -> cue.endMs
                fillMissingEnd || cue.endMs <= cue.startMs -> {
                    val candidate = next?.startMs ?: (cue.startMs + tailDurationMs)
                    // 下一条就在同一毫秒开始（副歌重复/双语行）时，至少给一点显示时长。
                    if (candidate <= cue.startMs) cue.startMs + MIN_CUE_DURATION_MS else candidate
                }
                else -> cue.endMs
            }
            cue.copy(endMs = end)
        }
    }

    /** 连续重复的同一行文本（副歌循环）在时间轴处理里不算错，但同一毫秒完全重复要去重。 */
    fun dropExactDuplicates(cues: List<SubtitleCue>): List<SubtitleCue> {
        val seen = HashSet<Triple<Long, Long, String>>()
        return cues.filter { seen.add(Triple(it.startMs, it.endMs, it.text)) }
    }

    const val MIN_CUE_DURATION_MS = 500L
}

/** 时间码工具对解析器可见（`internal` 但同模块内共享）。 */
internal fun parseTimecodeOrNull(raw: String): Long? = Timecode.toMillis(raw)
