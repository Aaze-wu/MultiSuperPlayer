package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.CuePosition
import com.multisuperplayer.core.model.KaraokeSegment
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleStyleDef
import com.multisuperplayer.core.subtitle.internal.Timecode

/**
 * ASS / SSA 解析器。
 *
 * 覆盖 `[Script Info]`、`[V4+ Styles]` / `[V4 Styles]` / `[V4++ Styles]`、
 * `[Events]`（Dialogue / Comment）三段。
 *
 * 设计取舍：
 * - **尊重文件里的 `Format:` 行**。字段顺序不是固定的，很多工具（Aegisub 的
 *   不同版本、格式转换器）会写出不同顺序，按固定下标取值会整片错位。
 * - 行内覆盖标签（`{\an8}`、`{\fs20}`…）原样存进 [SubtitleCue.overrides]，
 *   渲染层再解释。解析阶段不能丢，否则用户无法在「字幕样式」里覆盖它。
 * - 卡拉OK `\k` / `\kf` / `\ko` / `\K` 统一建模为 [KaraokeSegment]（不区分
 *   扫动/描边两种动画，那属于渲染细节）。
 */
class AssParser : SubtitleParser {

    override val format: SubtitleFormat = SubtitleFormat.ASS

    override val supportedFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

    private data class RawEvent(
        val startMs: Long,
        val endMs: Long,
        val styleName: String?,
        val actor: String?,
        val marginLeft: Int?,
        val marginRight: Int?,
        val marginVertical: Int?,
        val isComment: Boolean,
        val text: String,
    )

    override fun parse(content: String): ParseResult {
        val warnings = mutableListOf<String>()
        val metadata = mutableMapOf<String, String>()
        val styles = mutableMapOf<String, SubtitleStyleDef>()
        val rawEvents = mutableListOf<RawEvent>()
        val formatBySection = mutableMapOf<String, List<String>>()

        var section = ""
        for (rawLine in content.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("!:")) continue

            if (trimmed.startsWith("[")) {
                section = trimmed.lowercase().removeSurrounding("[", "]").trim()
                continue
            }

            val colon = trimmed.indexOf(':')
            val key = if (colon > 0) trimmed.substring(0, colon).trim().lowercase() else ""
            val value = if (colon >= 0) trimmed.substring(colon + 1).trim() else ""

            when {
                key.isEmpty() -> Unit

                key == "format" -> formatBySection[section] = value.split(',').map { it.trim().lowercase() }

                section.contains("script info") -> metadata[key] = value

                section.contains("styles") -> if (key == "style") {
                    val fields = splitFields(value, formatBySection[section] ?: DEFAULT_STYLE_FIELDS)
                    parseStyle(fields)?.let { style ->
                        val name = style.name ?: "Default"
                        styles[name] = style
                    } ?: run { warnings += "样式行缺少 Name 字段，已跳过" }
                }

                section.contains("events") -> if (key == "dialogue" || key == "comment") {
                    val fields = splitFields(value, formatBySection[section] ?: DEFAULT_EVENT_FIELDS)
                    parseEvent(fields, isComment = key == "comment", warnings)?.let { rawEvents += it }
                }
            }
        }

        val playResX = metadata["playresx"]?.toFloatOrNull()?.takeIf { it > 0f }
        val playResY = metadata["playresy"]?.toFloatOrNull()?.takeIf { it > 0f }
        val resolvedX = playResX ?: DEFAULT_PLAY_RES_X
        val resolvedY = playResY ?: DEFAULT_PLAY_RES_Y
        if (playResX == null || playResY == null) {
            warnings += "缺少 PlayResX/PlayResY，按 ${DEFAULT_PLAY_RES_X.toInt()}x${DEFAULT_PLAY_RES_Y.toInt()} 估算位置"
        }

        val defaultStyle = styles["default"]

        val cues = rawEvents.map { event ->
            val parsed = parseEventText(event.text, event.startMs)
            val style = event.styleName?.let { styles[it] }
                ?: defaultStyle
                ?: styles.values.firstOrNull()

            SubtitleCue(
                index = 0, // 稍后统一编号
                startMs = event.startMs,
                endMs = if (event.endMs > event.startMs) event.endMs else event.startMs + CuePostProcess.MIN_CUE_DURATION_MS,
                text = parsed.text,
                karaoke = parsed.karaoke,
                styleName = event.styleName ?: style?.name,
                actor = event.actor,
                position = resolvePosition(style, event, parsed.overrides, resolvedX, resolvedY),
                overrides = parsed.overrides,
                isComment = event.isComment,
            )
        }
            .filter { it.text.isNotEmpty() }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .mapIndexed { index, cue -> cue.copy(index = index + 1) }

        val detected = if (formatBySection.keys.any { it.contains("v4+") }) SubtitleFormat.ASS else format

        return ParseResult(
            format = detected,
            cues = CuePostProcess.dropExactDuplicates(cues),
            styles = styles,
            defaultStyle = defaultStyle,
            metadata = metadata,
            warnings = warnings,
        )
    }

    // -----------------------------------------------------------------------
    // 字段切分
    // -----------------------------------------------------------------------

    /**
     * 按 `Format:` 声明的字段数切分。
     *
     * `limit = fields.size` 很关键：ASS 的 `Text` 字段本身包含逗号，
     * 不限长度切分会把一句话切碎。
     */
    private fun splitFields(payload: String, fields: List<String>): Map<String, String> {
        val parts = payload.split(',', limit = fields.size)
        return fields.mapIndexedNotNull { index, name ->
            val value = parts.getOrNull(index) ?: return@mapIndexedNotNull null
            name to value.trim()
        }.toMap()
    }

    private fun parseStyle(fields: Map<String, String>): SubtitleStyleDef? {
        val name = fields["name"]?.takeIf { it.isNotEmpty() } ?: return null
        return SubtitleStyleDef(
            name = name,
            fontFamily = fields["fontname"]?.takeIf { it.isNotEmpty() },
            fontScale = null, // Fontsize 是脚本像素，需要 PlayResY 才能换算，留给渲染层
            primaryColorArgb = parseAssColor(fields["primarycolour"]),
            secondaryColorArgb = parseAssColor(fields["secondarycolour"]),
            outlineColorArgb = parseAssColor(fields["outlinecolour"] ?: fields["tertiarycolour"]),
            backColorArgb = parseAssColor(fields["backcolour"]),
            outlineWidth = fields["outline"]?.toFloatOrNull(),
            shadowWidth = fields["shadow"]?.toFloatOrNull(),
            bold = parseAssBool(fields["bold"]),
            italic = parseAssBool(fields["italic"]),
            underline = parseAssBool(fields["underline"]),
            strikeThrough = parseAssBool(fields["strikeout"]),
            spacing = fields["spacing"]?.toFloatOrNull(),
            angleDegrees = fields["angle"]?.toFloatOrNull(),
            alignment = parseAlignment(fields["alignment"]),
            marginLeftPercent = null,
            marginRightPercent = null,
            marginVerticalPercent = null,
        )
    }

    private fun parseEvent(
        fields: Map<String, String>,
        isComment: Boolean,
        warnings: MutableList<String>,
    ): RawEvent? {
        val startMs = Timecode.toMillis(fields["start"]) ?: run {
            warnings += "事件缺少可解析的 Start：${fields["start"]}"
            return null
        }
        val endMs = Timecode.toMillis(fields["end"]) ?: (startMs + CuePostProcess.MIN_CUE_DURATION_MS)
        val text = fields["text"].orEmpty()
        if (text.isBlank()) return null
        return RawEvent(
            startMs = startMs,
            endMs = endMs,
            styleName = fields["style"]?.takeIf { it.isNotEmpty() && it != "*" },
            actor = fields["name"]?.takeIf { it.isNotEmpty() },
            marginLeft = fields["marginl"]?.toIntOrNull()?.takeIf { it > 0 },
            marginRight = fields["marginr"]?.toIntOrNull()?.takeIf { it > 0 },
            marginVertical = fields["marginv"]?.toIntOrNull()?.takeIf { it > 0 },
            isComment = isComment,
            text = text,
        )
    }

    // -----------------------------------------------------------------------
    // 正文与行内标签
    // -----------------------------------------------------------------------

    private data class EventText(
        val text: String,
        val overrides: Map<String, String>,
        val karaoke: List<KaraokeSegment>,
    )

    private val tagPattern = Regex("""\\([a-zA-Z]{1,4})([^\\]*)""")

    private fun parseEventText(raw: String, startMs: Long): EventText {
        val builder = StringBuilder()
        val overrides = mutableMapOf<String, String>()
        val karaoke = mutableListOf<KaraokeSegment>()

        var pendingKaraokeCs: Long? = null
        var segmentStartMs = startMs
        var segmentTextStart = 0

        fun appendText(chunk: String) {
            builder.append(
                chunk.replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace("\\h", "\u00A0"),
            )
        }

        /**
         * 结束当前卡拉OK 段，并把时间指针前移。
         *
         * [pendingKaraokeCs] 为 null 表示还没遇到过 `\k`（例如行首有一段普通文字），
         * 此时既不产出片段也不推进指针——否则行首那句会被算到错的时间上，
         * 而且后面每一段都会整体提前。
         */
        fun closeSegment() {
            val durationCs = pendingKaraokeCs ?: return
            val text = builder.substring(segmentTextStart)
            if (text.isNotEmpty()) {
                karaoke += KaraokeSegment(
                    startMs = segmentStartMs,
                    durationMs = durationCs * 10, // ASS 的 \k 单位是厘秒
                    text = text,
                )
            }
            segmentStartMs += durationCs * 10
        }

        fun beginSegment(durationCs: Long) {
            closeSegment()
            segmentTextStart = builder.length
            pendingKaraokeCs = durationCs
        }

        var index = 0
        while (index < raw.length) {
            if (raw[index] == '{') {
                val end = raw.indexOf('}', index + 1)
                if (end < 0) {
                    appendText(raw.substring(index + 1))
                    break
                }
                val block = raw.substring(index + 1, end)
                for (tag in tagPattern.findAll(block)) {
                    val name = tag.groupValues[1].lowercase()
                    val value = tag.groupValues[2].trim()
                    when (name) {
                        "k", "kf", "ko" -> {
                            // \k50 = 50 厘秒。取不到数字的 \k（少见）直接忽略，
                            // 不能当成 0，否则会产出一个零长度的空段。
                            value.takeWhile { it.isDigit() }.toLongOrNull()?.let(::beginSegment)
                        }
                        else -> overrides[name] = value
                    }
                }
                index = end + 1
            } else {
                val nextBrace = raw.indexOf('{', index).let { if (it < 0) raw.length else it }
                appendText(raw.substring(index, nextBrace))
                index = nextBrace
            }
        }

        // 收尾最后一段：它的时长来自最后一个 \k 标签。
        closeSegment()

        return EventText(
            text = builder.toString().trim('\n', ' '),
            overrides = overrides,
            karaoke = karaoke.filter { it.text.isNotEmpty() },
        )
    }

    // -----------------------------------------------------------------------
    // 位置与颜色
    // -----------------------------------------------------------------------

    private fun resolvePosition(
        style: SubtitleStyleDef?,
        event: RawEvent,
        overrides: Map<String, String>,
        playResX: Float,
        playResY: Float,
    ): CuePosition? {
        val alignment = parseAlignment(overrides["an"])
            ?: style?.alignment
            ?: 2

        // {\pos(x,y)} 是绝对坐标，优先。
        overrides["pos"]?.let { raw ->
            val numbers = Regex("""-?\d+(?:\.\d+)?""").findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()
            if (numbers.size >= 2) {
                return CuePosition(
                    anchorX = (numbers[0] / playResX).coerceIn(0f, 1f),
                    anchorY = (numbers[1] / playResY).coerceIn(0f, 1f),
                    alignment = alignment,
                )
            }
        }

        val marginV = (event.marginVertical ?: 0).takeIf { it > 0 }
        val anchorY = when {
            alignment in MIDDLE_ALIGNMENTS -> 0.5f
            alignment in TOP_ALIGNMENTS -> marginV?.let { (it / playResY).coerceIn(0f, 1f) } ?: DEFAULT_TOP_ANCHOR_Y
            else -> marginV?.let { (1f - it / playResY).coerceIn(0f, 1f) } ?: DEFAULT_BOTTOM_ANCHOR_Y
        }

        val hasCustomAnchor = marginV != null || alignment != 2
        return if (hasCustomAnchor) {
            CuePosition(
                anchorX = when (alignment) {
                    in LEFT_ALIGNMENTS -> 0f
                    in RIGHT_ALIGNMENTS -> 1f
                    else -> 0.5f
                },
                anchorY = anchorY,
                alignment = alignment,
            )
        } else {
            null
        }
    }

    /**
     * ASS 颜色：`&HAABBGGRR`。两个坑都在名字里：
     * - 分量顺序是 **BGR**（低位字节是 R，不是 B）；
     * - alpha 存的是「透明度」：`&H00` 完全不透明，`&HFF` 完全透明，
     *   与本项目的 ARGB 正好相反，必须取反。
     *
     * 写反了不会报错：所有颜色都会静默互换红蓝（纯白纯黑看不出来），
     * 所以这里每条分量都要有断言。
     */
    private fun parseAssColor(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hex = when {
            text.startsWith("&H", ignoreCase = true) -> text.substring(2).trimEnd('&')
            else -> return text.toLongOrNull()?.let(::decimalBgrToArgbLong)
        }
        val value = hex.toLongOrNull(16) ?: return null
        return bgrToArgb(value, hasAlphaByte = value > 0xFFFFFF)
    }

    /** SSA 老格式里颜色字段是十进制整数，但字节布局同样是 `BBGGRR`（无 alpha）。 */
    private fun decimalBgrToArgbLong(value: Long): Long = bgrToArgb(value, hasAlphaByte = false)

    private fun bgrToArgb(value: Long, hasAlphaByte: Boolean): Long {
        val red = value and 0xFF
        val green = (value shr 8) and 0xFF
        val blue = (value shr 16) and 0xFF
        val alpha = if (hasAlphaByte) 0xFF - ((value shr 24) and 0xFF) else 0xFF
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun parseAssBool(raw: String?): Boolean? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        // ASS 里 -1 = true，0 = false；也有工具写 1。
        return value != 0
    }

    private fun parseAlignment(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in 1..9 }
    }

    private companion object {
        val DEFAULT_STYLE_FIELDS = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour", "outlinecolour",
            "backcolour", "bold", "italic", "underline", "strikeout", "scalex", "scaley",
            "spacing", "angle", "borderstyle", "outline", "shadow", "alignment",
            "marginl", "marginr", "marginv", "encoding",
        )

        val DEFAULT_EVENT_FIELDS = listOf(
            "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
        )

        const val DEFAULT_PLAY_RES_X = 384f
        const val DEFAULT_PLAY_RES_Y = 288f

        /** \an7/8/9 且没写 MarginV 时的顶部锚点。 */
        const val DEFAULT_TOP_ANCHOR_Y = 0.1f

        /** 默认（\an2）底部锚点。 */
        const val DEFAULT_BOTTOM_ANCHOR_Y = 0.9f

        /** ASS 的 \an 编号：左下起，1 2 3 / 4 5 6 / 7 8 9。 */
        val TOP_ALIGNMENTS = setOf(7, 8, 9)
        val MIDDLE_ALIGNMENTS = setOf(4, 5, 6)
        val LEFT_ALIGNMENTS = setOf(1, 4, 7)
        val RIGHT_ALIGNMENTS = setOf(3, 6, 9)
    }
}
