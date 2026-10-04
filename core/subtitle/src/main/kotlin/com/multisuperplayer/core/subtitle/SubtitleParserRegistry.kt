package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.text.MspText

/**
 * 统一入口：内容 → [ParseResult]。
 *
 * 判定顺序：显式 hint → 内容嗅探 → 后缀。嗅探失败时会把所有解析器都试一遍，
 * 取第一个解析出 cue 的结果（而不是直接报错），因为用户下载的字幕文件
 * 什么后缀都有。
 */
class SubtitleParserRegistry(
    parsers: List<SubtitleParser> = defaultParsers(),
) {
    private val parsers: List<SubtitleParser> = parsers

    fun parserFor(format: SubtitleFormat): SubtitleParser? =
        parsers.firstOrNull { format in it.supportedFormats }

    /**
     * @param hint 已知格式（例如用户手动指定）。为 null 时自动判定。
     * @param fileName 带后缀的文件名，仅用于兜底判定。
     * @throws SubtitleParseException 所有候选解析器都没能解析出任何一条 cue。
     */
    fun parse(
        content: String,
        hint: SubtitleFormat? = null,
        fileName: String? = null,
    ): ParseResult {
        val normalized = SubtitleText.normalizeNewlines(content)
        if (normalized.isBlank()) {
            throw SubtitleParseException(MspText.Res(R.string.msp_subtitle_error_empty))
        }

        val detected = hint
            ?.takeIf { it != SubtitleFormat.UNKNOWN }
            ?: SubtitleFormatDetector.detect(normalized, fileName)

        parserFor(detected)?.let { parser ->
            val result = runCatching { parser.parse(normalized) }.getOrNull()
            if (result != null && result.cues.isNotEmpty()) return result
        }

        // 兜底：逐个尝试，谁先解析出内容就用谁，并在告警里记下真实格式，
        // 便于用户反馈「我的字幕显示不出来」时定位。
        val attempts = mutableListOf<String>()
        for (parser in parsers) {
            if (parser.supportedFormats.contains(detected)) continue
            val result = runCatching { parser.parse(normalized) }.getOrNull() ?: continue
            if (result.cues.isNotEmpty()) {
                return result.copy(
                    warnings = result.warnings + MspText.Res(
                        R.string.msp_subtitle_warn_format_mismatch,
                        detected.name,
                        result.format.name,
                    ),
                )
            }
            attempts += parser.format.name
        }

        throw SubtitleParseException(
            MspText.Res(
                R.string.msp_subtitle_error_unparsable,
                detected.name,
                (attempts + detected.name).joinToString(),
            ),
        )
    }

    companion object {
        fun defaultParsers(): List<SubtitleParser> = listOf(
            SrtParser(),
            WebVttParser(),
            LrcParser(),
            AssParser(),
            TtmlParser(),
        )
    }
}
