package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.CuePosition
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.subtitle.internal.Timecode

/**
 * SubRip (.srt) 解析器。
 *
 * 兼容的现实写法：
 * - 序号行缺失（直接从时间码开始）
 * - 毫秒分隔可以用 `,` 或 `.`
 * - 省略小时：`00:01,000 --> 00:04,000`
 * - 时间码后跟坐标：`00:00:01,000 --> 00:00:04,000 X1:100 X2:200 Y1:.. Y2:..`
 * - 正文里的 `<br>`、`<i>/<b>/<u>/<font>` 标签（HTML 风格），以及 ASS 的 `{\an8}`
 *
 * 正文中的行内标记**原样保留**，由渲染层解释——解析阶段丢标签会不可逆地
 * 丢失用户的排版信息。
 */
class SrtParser : SubtitleParser {

    override val format: SubtitleFormat = SubtitleFormat.SRT

    override fun parse(content: String): ParseResult {
        val warnings = mutableListOf<MspText>()
        val cues = mutableListOf<SubtitleCue>()
        val blocks = SubtitleText.splitBlocks(content)

        var autoIndex = 0
        for (block in blocks) {
            val timingLineIndex = block.indexOfFirst { it.contains("-->") }
            if (timingLineIndex < 0) {
                // 允许块内全是注释/空行；有正文才是问题。
                if (block.any { it.isNotBlank() && it.toIntOrNull() == null }) {
                    warnings += MspText.Res(R.string.msp_subtitle_warn_srt_unknown_block, block.first().take(40))
                }
                continue
            }

            val timingLine = block[timingLineIndex]
            val range = Timecode.parseRange(timingLine)
            if (range == null) {
                warnings += MspText.Res(R.string.msp_subtitle_warn_bad_range, timingLine.take(60))
                continue
            }
            val (startMs, endMsRaw) = range

            val declaredIndex = block.getOrNull(timingLineIndex - 1)?.trim()?.toIntOrNull()
            autoIndex += 1

            val rawText = block.drop(timingLineIndex + 1).joinToString("\n").trim()
            if (rawText.isEmpty()) continue

            val position = parseCoordinateTail(timingLine)

            val endMs = if (endMsRaw < startMs) {
                warnings += MspText.Res(R.string.msp_subtitle_warn_srt_end_before_start, autoIndex)
                startMs + 1_000
            } else {
                endMsRaw
            }

            cues += SubtitleCue(
                index = declaredIndex ?: autoIndex,
                startMs = startMs,
                endMs = endMs,
                text = normalizeBody(rawText),
                position = position,
            )
        }

        val finished = CuePostProcess.dropExactDuplicates(CuePostProcess.finish(cues, fillMissingEnd = false))
        return ParseResult(
            format = SubtitleFormat.SRT,
            cues = finished,
            warnings = warnings,
        )
    }

    private fun normalizeBody(raw: String): String =
        SubtitleText.decodeEntities(SubtitleText.htmlBreaksToNewlines(raw)).trim()

    /**
     * SRT 的可选坐标尾部（`X1:100 X2:600 Y1:400 Y2:450`）→ 归一化位置。
     * 只有 X/Y 都齐全才换算，否则宁可为 null 走默认样式。
     */
    private fun parseCoordinateTail(timingLine: String): CuePosition? {
        val tail = timingLine.substringAfter("-->", "")
        val numbers = Regex("""([XY][12])\s*:\s*(\d+)""").findAll(tail)
            .associate { it.groupValues[1] to (it.groupValues[2].toFloatOrNull() ?: return null) }
        val x1 = numbers["X1"] ?: return null
        val x2 = numbers["X2"] ?: return null
        val y1 = numbers["Y1"] ?: return null
        val y2 = numbers["Y2"] ?: return null
        // SRT 的坐标系是脚本分辨率（常见 384/288 或 640/480），无法可靠换算成比例，
        // 这里只能给出相对关系，渲染层以字数为权重做近似。
        val width = (x2 - x1).takeIf { it > 0 } ?: return null
        val height = (y2 - y1).takeIf { it > 0 } ?: return null
        if (width <= 0 || height <= 0) return null
        return CuePosition(
            anchorX = ((x1 + x2) / 2f / SRT_ASSUMED_WIDTH).coerceIn(0f, 1f),
            anchorY = ((y2) / SRT_ASSUMED_HEIGHT).coerceIn(0f, 1f),
            alignment = 2,
        )
    }

    private companion object {
        /** SRT 坐标缺少分辨率信息，按最常见的 640x480 估。 */
        const val SRT_ASSUMED_WIDTH = 640f
        const val SRT_ASSUMED_HEIGHT = 480f
    }
}
