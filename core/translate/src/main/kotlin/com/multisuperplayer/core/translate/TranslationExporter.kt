package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument

/** 导出格式。只做这两种——它们是实际有人用的；ASS 的样式导出见下。 */
enum class SubtitleExportFormat(val extension: String, val label: MspText) {
    SRT("srt", MspText.Res(R.string.msp_translate_export_srt)),
    ASS("ass", MspText.Res(R.string.msp_translate_export_ass)),
}

/** 导出内容形态。 */
enum class SubtitleExportMode(val label: MspText) {
    /** 只有译文（没有译文的行回退原文，避免导出文件出现空洞）。 */
    TRANSLATION_ONLY(MspText.Res(R.string.msp_translate_export_mode_translation_only)),

    /** 原文一行、译文一行。做双语字幕或拿去校对时用这个。 */
    BILINGUAL(MspText.Res(R.string.msp_translate_export_mode_bilingual)),
}

/**
 * 生成导出文本。纯函数，可直接单测。
 *
 * ## 没有译文的行为什么会回退原文
 *
 * 因为导出的文件是要拿去用的：漏掉没有译文的行会让**时间轴出现空洞**，
 * 播放器读到那儿就是一段空白。宁可退回原文，也不要留洞。
 *
 * ## 下标一律用**列表位置**，不用 [SubtitleCue.index]
 *
 * 本模块里 `translatableIndices` / `planTranslationBatches` / `withTranslations`
 * 和引擎产出的 `Map<Int, String>` 全是按列表位置索引的。导出这里若改用
 * `cue.index`，遇到「解析器把 index 填成原始行号」的字幕文件就会**一行都对不上**
 * 而安静地导出成纯原文——那种 bug 看起来像"翻译没生效"。
 */
fun buildExportedSubtitle(
    document: SubtitleDocument,
    translations: Map<Int, String>,
    format: SubtitleExportFormat,
    mode: SubtitleExportMode,
): String {
    val lines = document.cues.mapIndexed { position, cue ->
        val translated = translations[position]?.trim()?.takeIf { it.isNotEmpty() }
            ?: cue.translation?.trim()?.takeIf { it.isNotEmpty() }
        val text = when {
            mode == SubtitleExportMode.TRANSLATION_ONLY -> translated ?: cue.text
            translated == null -> cue.text
            else -> cue.text + "\n" + translated
        }
        cue to text
    }
    return when (format) {
        SubtitleExportFormat.SRT -> buildSrt(lines)
        SubtitleExportFormat.ASS -> buildAss(document, lines)
    }
}

/** 导出文件名（不含目录）。带上目标语言代码，避免同一目录里互相覆盖。 */
fun exportFileName(
    sourceFileName: String,
    target: TranslationTarget,
    format: SubtitleExportFormat,
    mode: SubtitleExportMode,
): String {
    val base = sourceFileName.substringBeforeLast('.').ifBlank { "subtitle" }
    val suffix = if (mode == SubtitleExportMode.BILINGUAL) ".bilingual" else ""
    return "$base.${target.code}$suffix.${format.extension}"
}

// ------------------------------------------------------------------ SRT

private fun buildSrt(lines: List<Pair<SubtitleCue, String>>): String = buildString {
    var counter = 0
    for ((cue, text) in lines) {
        if (cue.isComment) continue
        counter++
        append(counter).append('\n')
        append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append('\n')
        append(text.replace("\r\n", "\n")).append("\n\n")
    }
}

/** `00:00:01,000` */
internal fun srtTime(millis: Long): String {
    val ms = millis.coerceAtLeast(0L)
    val hours = ms / 3_600_000
    val minutes = ms / 60_000 % 60
    val seconds = ms / 1_000 % 60
    val millisPart = ms % 1_000
    return buildString {
        append(hours.toString().padStart(2, '0')).append(':')
        append(minutes.toString().padStart(2, '0')).append(':')
        append(seconds.toString().padStart(2, '0')).append(',')
        append(millisPart.toString().padStart(3, '0'))
    }
}

// ------------------------------------------------------------------ ASS

/**
 * 一个够用就好的 ASS。
 *
 * 不试图还原原文件的全部样式：用户要的是「带上译文的字幕文件」，
 * 用来做双语挂载或拿去 Aegisub 继续改。所以样式表写一条 `Default`，
 * 位置/对齐从 cue 上带（`\an`），其余保持通用默认值。
 */
private fun buildAss(document: SubtitleDocument, lines: List<Pair<SubtitleCue, String>>): String =
    buildString {
        append("[Script Info]\n")
        append("; 由 MultiSuperPlayer 导出\n")
        document.metadata["Title"]?.let { append("Title: ").append(it).append('\n') }
        append("ScriptType: v4.00+\n")
        append("WrapStyle: 0\n")
        append("ScaledBorderAndShadow: yes\n")
        append("PlayResX: 1920\n")
        append("PlayResY: 1080\n\n")

        append("[V4+ Styles]\n")
        append(
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, " +
                "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, " +
                "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n",
        )
        append(
            "Style: Default,Noto Sans CJK SC,54,&H00FFFFFF,&H000000FF,&H00000000,&H80000000," +
                "0,0,0,0,100,100,0,0,1,2,1,2,40,40,48,1\n\n",
        )

        append("[Events]\n")
        append(
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n",
        )

        for ((cue, text) in lines) {
            if (cue.isComment) continue
            val style = cue.styleName?.takeIf { it.isNotBlank() } ?: "Default"
            val name = cue.actor.orEmpty()
            val alignment = cue.position?.alignment?.takeIf { it in 1..9 }
            val body = text
                .replace("\r\n", "\n")
                .replace("\n", "\\N")
                .replace("{", "\\{")
                .replace("}", "\\}")
            val prefix = alignment?.let { "{\\an$it}" }.orEmpty()
            append("Dialogue: 0,")
            append(assTime(cue.startMs)).append(',')
            append(assTime(cue.endMs)).append(',')
            append(escapeAssField(style)).append(',')
            append(escapeAssField(name)).append(",0,0,0,,")
            append(prefix).append(body).append('\n')
        }
    }

/** `0:00:01.00`（百分秒） */
internal fun assTime(millis: Long): String {
    val ms = millis.coerceAtLeast(0L)
    val hours = ms / 3_600_000
    val minutes = ms / 60_000 % 60
    val seconds = ms / 1_000 % 60
    val centis = ms % 1_000 / 10
    return buildString {
        append(hours).append(':')
        append(minutes.toString().padStart(2, '0')).append(':')
        append(seconds.toString().padStart(2, '0')).append('.')
        append(centis.toString().padStart(2, '0'))
    }
}

/** 逗号是 ASS 的字段分隔符，样式名/角色名里的逗号必须换掉，否则整行错位。 */
private fun escapeAssField(value: String): String = value.replace(',', '，').trim()
