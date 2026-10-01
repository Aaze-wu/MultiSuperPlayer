package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat

/**
 * 内容嗅探优先、后缀兜底的格式判定。
 *
 * 为什么不只看后缀：
 * - `a.lrc` 可能是普通 LRC 也可能是增强型 LRC，得看有没有 `<mm:ss.xx>` 标签；
 * - 大量 `.txt` 字幕其实是 SRT；
 * - 下载来的字幕经常被改名成 `.sub` / `.xml`，后缀毫无意义。
 */
object SubtitleFormatDetector {

    private val ASS_SECTION = Regex("""(?im)^\s*\[(script info|v4\+? *styles|events)\]\s*$""")
    private val ASS_V4_PLUS_STYLES = Regex("""(?im)^\s*\[v4\+ *styles\]\s*$""")
    private val ASS_V4_STYLES = Regex("""(?im)^\s*\[v4 *styles\]\s*$""")
    private val ARROW_LINE = Regex("""-->""")
    private val SRT_MILLIS = Regex("""\d[.,]\d{1,3}\s*-->|\d\s*-->\s*\d""")
    private val SRT_COMMA_FRACTION = Regex("""\d,\d{1,3}\s*-->""")
    private val VTT_DOT_FRACTION = Regex("""\d\.\d{1,3}\s*-->""")
    private val LRC_TIMETAG = Regex("""^\s*\[\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?]""")
    private val LRC_METADATA = Regex("""^\s*\[(ti|ar|al|au|by|offset|length|re|ve|tool|encoding)\s*:""", RegexOption.IGNORE_CASE)
    private val LRC_INLINE_TIMETAG = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val TTML_ROOT = Regex("""(?is)^\s*(?:<\?xml[^>]*>\s*)?(?:<!--.*?-->\s*)*<tt[\s>]""")

    fun detect(content: String, fileName: String? = null): SubtitleFormat {
        val text = SubtitleText.normalizeNewlines(content)
        val head = text.take(HEAD_SAMPLE_CHARS)

        sniffFromContent(text, head)?.let { return it }

        // 后缀兜底：`SubtitleFormat.candidatesForExtension` 可能给出多个候选，
        // 用内容特征再挑一次，仍无法区分时按更常见的那个算。
        fileName?.substringAfterLast('.', "")?.let { ext ->
            val candidates = SubtitleFormat.candidatesForExtension(ext)
            when {
                candidates.size == 1 -> return candidates.first()
                candidates.size > 1 -> return candidates.first()
            }
        }
        return SubtitleFormat.UNKNOWN
    }

    private fun sniffFromContent(text: String, head: String): SubtitleFormat? {
        if (head.startsWith("WEBVTT", ignoreCase = true)) return SubtitleFormat.VTT

        if (TTML_ROOT.containsMatchIn(head)) return SubtitleFormat.TTML

        if (ASS_SECTION.containsMatchIn(head)) {
            return when {
                ASS_V4_PLUS_STYLES.containsMatchIn(head) -> SubtitleFormat.ASS
                ASS_V4_STYLES.containsMatchIn(head) -> SubtitleFormat.SSA
                else -> SubtitleFormat.ASS
            }
        }

        if (ARROW_LINE.containsMatchIn(head)) {
            // 有逗号毫秒 → SRT；纯点分 → VTT（VTT 允许省略毫秒，那种情况只能靠
            // 是否出现 VTT 专有设置 / cue id 来判断，这里退化为 SRT）。
            return when {
                SRT_COMMA_FRACTION.containsMatchIn(head) -> SubtitleFormat.SRT
                VTT_DOT_FRACTION.containsMatchIn(head) -> SubtitleFormat.VTT
                SRT_MILLIS.containsMatchIn(head) -> SubtitleFormat.SRT
                else -> SubtitleFormat.SRT
            }
        }

        val lrcScore = countMatches(LRC_TIMETAG, text, 8)
        if (lrcScore > 0) {
            val hasInline = LRC_INLINE_TIMETAG.containsMatchIn(text)
            val hasMetadata = LRC_METADATA.containsMatchIn(text)
            return if (hasInline) SubtitleFormat.ENHANCED_LRC else SubtitleFormat.LRC
        }

        return null
    }

    private fun countMatches(regex: Regex, text: String, limit: Int): Int {
        var count = 0
        for (line in text.lineSequence()) {
            if (regex.containsMatchIn(line)) {
                count++
                if (count >= limit) break
            }
        }
        return count
    }

    private const val HEAD_SAMPLE_CHARS = 8_192
}
