package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.CuePosition
import com.multisuperplayer.core.model.KaraokeSegment
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.subtitle.internal.Timecode

/**
 * WebVTT (.vtt) 解析器。
 *
 * 覆盖：WEBVTT 头、NOTE 注释块、STYLE 块、cue 标识行、cue 设置
 * （`line:` / `position:` / `align:` / `size:` / `region:`）、
 * `<v Speaker>` / `<c.class>` / `<b>` 行内标签、`<00:00:02.000>` 行内时间戳（卡拉OK）。
 */
class WebVttParser : SubtitleParser {

    override val format: SubtitleFormat = SubtitleFormat.VTT

    override fun parse(content: String): ParseResult {
        val warnings = mutableListOf<String>()
        val cues = mutableListOf<SubtitleCue>()
        val metadata = mutableMapOf<String, String>()

        val blocks = SubtitleText.splitBlocks(content)
        var autoIndex = 0
        var seenHeader = false

        for (block in blocks) {
            val firstLine = block.firstOrNull()?.trim().orEmpty()

            if (!seenHeader) {
                if (firstLine.startsWith("WEBVTT", ignoreCase = true)) {
                    seenHeader = true
                    firstLine.removePrefix("WEBVTT").trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { metadata["header"] = it }
                    // 头部块里可能紧跟 NOTE/STYLE，继续处理剩余行
                } else if (firstLine.isNotEmpty()) {
                    warnings += "缺少 WEBVTT 头，按无头 VTT 解析"
                    seenHeader = true
                }
            }

            when {
                firstLine.startsWith("NOTE", ignoreCase = true) -> Unit
                firstLine.startsWith("STYLE", ignoreCase = true) -> {
                    metadata["styleBlock.${metadata.size}"] = block.drop(1).joinToString("\n")
                }
                firstLine.startsWith("REGION", ignoreCase = true) -> {
                    metadata["regionBlock.${metadata.size}"] = block.drop(1).joinToString("\n")
                }
                else -> {
                    val timingLineIndex = block.indexOfFirst { it.contains("-->") }
                    if (timingLineIndex < 0) continue

                    val timingLine = block[timingLineIndex]
                    val range = Timecode.parseRange(timingLine)
                    if (range == null) {
                        warnings += "时间码无法解析：${timingLine.take(60)}"
                        continue
                    }
                    val (startMs, endMsRaw) = range
                    autoIndex += 1
                    val identifier = block.take(timingLineIndex).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

                    val settings = parseSettings(timingLine.substringAfter("-->", ""))
                    val body = block.drop(timingLineIndex + 1).joinToString("\n").trim()
                    if (body.isEmpty()) continue

                    val parsed = parseBody(body, startMs)

                    cues += SubtitleCue(
                        index = identifier?.toIntOrNull() ?: autoIndex,
                        startMs = startMs,
                        endMs = if (endMsRaw > startMs) endMsRaw else startMs + 1_000,
                        text = parsed.text,
                        karaoke = parsed.karaoke,
                        actor = parsed.speaker,
                        position = settings.position,
                        overrides = settings.raw,
                    )
                }
            }
        }

        return ParseResult(
            format = SubtitleFormat.VTT,
            cues = CuePostProcess.finish(cues, fillMissingEnd = false),
            metadata = metadata,
            warnings = warnings,
        )
    }

    private data class BodyResult(
        val text: String,
        val karaoke: List<KaraokeSegment>,
        val speaker: String?,
    )

    private fun parseBody(raw: String, startMs: Long): BodyResult {
        var speaker: String? = null
        val textBuilder = StringBuilder()
        val karaoke = mutableListOf<KaraokeSegment>()

        // 行内标签：<v Name>、</v>、<c.class>、</c>、<b>…；行内时间戳 <00:00:02.000>
        var cursorMs = startMs
        var segmentStartMs = startMs
        var segmentTextStart = 0
        var segmentOpen = false

        val tagPattern = Regex("""<([^>]*)>""")
        var lastIndex = 0
        for (match in tagPattern.findAll(raw)) {
            textBuilder.append(raw, lastIndex, match.range.first)
            lastIndex = match.range.last + 1

            val tag = match.groupValues[1].trim()
            when {
                tag.startsWith("v ", ignoreCase = true) || tag.equals("v", ignoreCase = true) -> {
                    speaker = tag.substringAfter(' ', "").trim().ifEmpty { null }
                }
                tag.startsWith("/") -> Unit // 闭合标签
                tag.startsWith("c") || tag.startsWith("b") || tag.startsWith("i") ||
                    tag.startsWith("u") || tag.startsWith("ruby") || tag.startsWith("lang") -> Unit
                else -> {
                    // 行内时间戳 → 卡拉OK 分段
                    val timestamp = Timecode.parseClockToken(tag)
                    if (timestamp != null) {
                        if (segmentOpen) {
                            karaoke += KaraokeSegment(
                                startMs = segmentStartMs,
                                durationMs = (timestamp - segmentStartMs).coerceAtLeast(0L),
                                text = textBuilder.substring(segmentTextStart),
                            )
                        }
                        segmentStartMs = timestamp
                        cursorMs = timestamp
                        segmentTextStart = textBuilder.length
                        segmentOpen = true
                    }
                }
            }
        }
        textBuilder.append(raw, lastIndex, raw.length)

        if (segmentOpen) {
            karaoke += KaraokeSegment(
                startMs = segmentStartMs,
                durationMs = 0L,
                text = textBuilder.substring(segmentTextStart),
            )
        }

        val text = SubtitleText.decodeEntities(textBuilder.toString())
            .replace('\u00A0', ' ')
            .trim()
        return BodyResult(text = text, karaoke = karaoke.filter { it.text.isNotEmpty() }, speaker = speaker)
    }

    private data class Settings(val position: CuePosition?, val raw: Map<String, String>)

    private fun parseSettings(tail: String): Settings {
        val raw = mutableMapOf<String, String>()
        var anchorX: Float? = null
        var anchorY: Float? = null
        var align: Int? = null

        for (token in tail.trim().split(' ', '\t').filter { it.isNotBlank() }) {
            val key = token.substringBefore(':', "").trim()
            val value = token.substringAfter(':', "").trim()
            if (key.isEmpty()) continue
            raw[key] = value
            when (key) {
                "position" -> anchorX = percentageOrNull(value)
                "line" -> anchorY = percentageOrNull(value)
                "align" -> align = when (value.lowercase()) {
                    "start", "left" -> 1
                    "center", "middle" -> 2
                    "end", "right" -> 3
                    else -> null
                }
            }
        }

        val position = if (anchorX != null || anchorY != null || align != null) {
            CuePosition(
                anchorX = anchorX ?: 0.5f,
                anchorY = anchorY ?: 0.9f,
                alignment = align ?: 2,
            )
        } else {
            null
        }
        return Settings(position = position, raw = raw)
    }

    private fun percentageOrNull(value: String): Float? {
        val numeric = value.removeSuffix("%").trim()
        val parsed = numeric.toFloatOrNull() ?: return null
        return if (value.endsWith("%")) (parsed / 100f).coerceIn(0f, 1f) else null
    }
}
