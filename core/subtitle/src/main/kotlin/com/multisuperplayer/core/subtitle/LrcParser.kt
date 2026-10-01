package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.KaraokeSegment
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.subtitle.internal.Timecode

/**
 * LRC / 增强型 LRC 歌词解析器。
 *
 * 普通 LRC 只标「开始时间」，所以结束时间靠**下一个时间点**回填——注意是
 * 「下一个时间点」而不是「下一条歌词」：`[00:10.00]` 这种空文本行常常被用来
 * 标记间奏结束，丢掉它会让上一句歌词一直挂到下一句歌词开始。
 *
 * @param mergeSameTimestampAsTranslation 同一时间码出现两行时，把第二行当成译文
 *   （中文圈的双语 LRC 普遍这么写）。设 false 时两行会合并成多行原文。
 * @param offsetPolicy 见 [OffsetPolicy]。默认按 `时间 = 标签时间 - offset` 处理。
 */
class LrcParser(
    private val mergeSameTimestampAsTranslation: Boolean = true,
    private val offsetPolicy: OffsetPolicy = OffsetPolicy.SUBTRACT,
) : SubtitleParser {

    enum class OffsetPolicy {
        /** `[offset:+500]` 表示歌词整体提前 500ms（多数播放器的行为）。 */
        SUBTRACT,

        /** `[offset:+500]` 表示歌词整体延后 500ms。 */
        ADD,
    }

    override val format: SubtitleFormat = SubtitleFormat.LRC

    override val supportedFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.LRC, SubtitleFormat.ENHANCED_LRC)

    private data class RawPoint(
        val rawTimeMs: Long,
        val text: String,
        val karaoke: List<KaraokeSegment>,
    )

    override fun parse(content: String): ParseResult {
        val warnings = mutableListOf<String>()
        val metadata = mutableMapOf<String, String>()
        val rawPoints = mutableListOf<RawPoint>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            // 1) 元数据行：[ti:标题] [ar:歌手] [offset:+500] …
            val metaMatch = METADATA.matchEntire(line)
            if (metaMatch != null) {
                metadata[metaMatch.groupValues[1].lowercase()] = metaMatch.groupValues[2].trim()
                continue
            }

            // 2) 时间标签行：一行可以有多个 [mm:ss.xx]
            val timeTags = TIME_TAG.findAll(line).toList()
            if (timeTags.isEmpty()) {
                if (line.isNotEmpty() && !line.startsWith("[")) {
                    warnings += "无法识别的行：${line.take(40)}"
                }
                continue
            }
            // 标签必须聚在行首，中间夹正文说明这不是 LRC 行。
            val bodyStart = timeTags.last().range.last + 1
            if (timeTags.first().range.first != 0) {
                warnings += "时间标签前有内容，已忽略该行：${line.take(40)}"
                continue
            }

            val body = line.substring(bodyStart).trim()
            val (text, karaoke) = parseInlineTimestamps(body)
            for (tag in timeTags) {
                val tagMs = Timecode.toMillis(tag.value.removeSurrounding("[", "]")) ?: continue
                rawPoints += RawPoint(rawTimeMs = tagMs, text = text, karaoke = karaoke)
            }
        }

        if (rawPoints.isEmpty()) {
            throw SubtitleParseException("LRC 中没有找到任何可解析的时间标签")
        }

        val offsetMs = metadata["offset"]?.let(::normalizeOffset) ?: 0L
        val totalMs = metadata["length"]?.let { Timecode.toMillis(it) }

        // offset 必须在读完整个文件后才能应用——[offset:] 标签可能出现在任何位置。
        fun adjust(ms: Long): Long {
            val shifted = when (offsetPolicy) {
                OffsetPolicy.SUBTRACT -> ms - offsetMs
                OffsetPolicy.ADD -> ms + offsetMs
            }
            return shifted.coerceAtLeast(0L)
        }

        val ordered = rawPoints
            .map { it.copy(rawTimeMs = adjust(it.rawTimeMs)) }
            .sortedBy { it.rawTimeMs }

        val cues = mutableListOf<SubtitleCue>()
        var index = 0
        ordered.forEachIndexed { position, point ->
            if (point.text.isEmpty()) return@forEachIndexed // 空文本只当时间锚点用
            index += 1
            val nextTimeMs = ordered.getOrNull(position + 1)?.rawTimeMs
            val endMs = nextTimeMs
                ?: totalMs?.let(::adjust)
                ?: (point.rawTimeMs + TAIL_DURATION_MS)
            cues += SubtitleCue(
                index = index,
                startMs = point.rawTimeMs,
                endMs = endMs.coerceAtLeast(point.rawTimeMs + CuePostProcess.MIN_CUE_DURATION_MS),
                text = point.text,
                karaoke = point.karaoke.map { it.copy(startMs = adjust(it.startMs)) },
            )
        }

        val merged = if (mergeSameTimestampAsTranslation) {
            mergeTranslations(cues, warnings)
        } else {
            cues
        }

        val format = if (INLINE_TIMESTAMP.containsMatchIn(content)) {
            SubtitleFormat.ENHANCED_LRC
        } else {
            SubtitleFormat.LRC
        }

        if (offsetMs != 0L) {
            metadata["offsetAppliedMs"] = offsetMs.toString()
        }

        return ParseResult(
            format = format,
            cues = CuePostProcess.finish(merged, fillMissingEnd = true),
            metadata = metadata,
            warnings = warnings,
        )
    }

    /** 解析 `<00:12.34>逐字` 行内标签；返回 (去掉标签的文本, 逐字分段)。 */
    private fun parseInlineTimestamps(body: String): Pair<String, List<KaraokeSegment>> {
        if (!body.contains(INLINE_TIMESTAMP)) return body to emptyList()

        val text = StringBuilder()
        val segments = mutableListOf<KaraokeSegment>()
        var cursor = 0
        var segmentStartMs: Long? = null
        var segmentTextStart = 0

        for (match in INLINE_TIMESTAMP.findAll(body)) {
            text.append(body, cursor, match.range.first)
            cursor = match.range.last + 1
            val timeMs = Timecode.toMillis(match.value.removeSurrounding("<", ">")) ?: continue
            segmentStartMs?.let { start ->
                segments += KaraokeSegment(
                    startMs = start,
                    durationMs = (timeMs - start).coerceAtLeast(0L),
                    text = text.substring(segmentTextStart),
                )
            }
            segmentStartMs = timeMs
            segmentTextStart = text.length
        }
        text.append(body, cursor, body.length)

        segmentStartMs?.let { start ->
            segments += KaraokeSegment(startMs = start, durationMs = 0L, text = text.substring(segmentTextStart))
        }
        return text.toString().trim() to segments.filter { it.text.isNotEmpty() }
    }

    /**
     * 同一时间码的连续两行 → 原文 + 译文。
     *
     * 只在**恰好两行**且都非空时合并：三行以上更可能是重复的副歌，
     * 把第三行当译文会显示成垃圾。
     *
     * 实现上必须先量出「同一时间码的连续段长度」再决定，而不是只看下一行：
     * 早期写法用 `getOrNull(i + 2)?.startMs != current.startMs` 判断「只有两行」，
     * 但列表**到此结束**时 `getOrNull` 返回 null，`null != startMs` 成立，
     * 于是三行里最后两行照样被合并——测试断言 `cues.size == 3` 才把它抓出来。
     */
    private fun mergeTranslations(cues: List<SubtitleCue>, warnings: MutableList<String>): List<SubtitleCue> {
        if (cues.size < 2) return cues
        val result = mutableListOf<SubtitleCue>()
        var i = 0
        while (i < cues.size) {
            val current = cues[i]
            var runEnd = i + 1
            while (runEnd < cues.size && cues[runEnd].startMs == current.startMs) runEnd++
            val runLength = runEnd - i
            when {
                runLength == 2 -> {
                    result += current.copy(translation = cues[i + 1].text)
                }
                runLength > 2 -> {
                    warnings += "时间码 ${current.startMs}ms 上有 $runLength 行文本，未做双语合并"
                    for (k in i until runEnd) result += cues[k]
                }
                else -> result += current
            }
            i = runEnd
        }
        return result
    }

    private fun normalizeOffset(raw: String): Long {
        val text = raw.trim()
        val negative = text.startsWith("-")
        val value = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
        return (if (negative) -value else value).toLong()
    }

    private companion object {
        const val TAIL_DURATION_MS = 10_000L

        val METADATA = Regex("""^\[([A-Za-z]{2,10}):(.*)]$""")
        val TIME_TAG = Regex("""\[\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?]""")
        val INLINE_TIMESTAMP = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")
    }
}
