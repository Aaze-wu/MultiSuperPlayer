package com.multisuperplayer.core.model

/**
 * 字幕 / 歌词的容器格式。
 *
 * 注意 LRC 与「增强型 LRC」共用 `.lrc` 后缀，所以**不能只靠后缀判断**：
 * 解析器要嗅探内容（是否含 `<mm:ss.xx>` 逐字标签 / `[ar:]` 标签），
 * 见 `core:subtitle` 的 `SubtitleFormatDetector`。
 */
enum class SubtitleFormat(
    val displayName: String,
    val extensions: List<String>,
) {
    SRT("SubRip", listOf("srt")),
    VTT("WebVTT", listOf("vtt", "webvtt")),
    ASS("Advanced SubStation Alpha", listOf("ass")),
    SSA("SubStation Alpha", listOf("ssa")),
    LRC("LRC 歌词", listOf("lrc")),
    ENHANCED_LRC("增强型 LRC（逐字）", listOf("lrc", "elrc")),
    TTML("TTML / DFXP / SMPTE-TT", listOf("ttml", "dfxp", "xml")),
    VOBSUB("VobSub", listOf("idx", "sub")),
    PGS("PGS (Sup)", listOf("sup")),
    UNKNOWN("未知", emptyList()),
    ;

    val isLyricStyle: Boolean
        get() = this == LRC || this == ENHANCED_LRC

    companion object {
        /** 后缀 → 候选格式（可能多个，例如 lrc 同时对应两种）。 */
        fun candidatesForExtension(extension: String): List<SubtitleFormat> {
            val ext = extension.lowercase().removePrefix(".")
            return entries.filter { ext in it.extensions }
        }
    }
}

/** 字幕轨来源，决定 UI 上显示哪种图标、以及能否被重新生成。 */
enum class SubtitleOrigin {
    /** 外挂字幕文件（同目录/用户选择）。 */
    EXTERNAL_FILE,

    /** 容器内嵌字幕轨。 */
    EMBEDDED,

    /** 本地/云端 ASR 生成。 */
    GENERATED_ASR,

    /** 由另一条轨道翻译得到。 */
    TRANSLATED,
}

/**
 * 一句话（字幕 cue / 歌词行）。
 *
 * @param text 原文；换行用 `\n` 表示（SRT/ASS 的多行已归一化）。
 * @param translation 译文，可为空。翻译是「挂」在原文上的，不生成新轨道，
 *   这样时间轴永远只有一个真相来源。
 * @param karaoke 逐字时间（增强型 LRC / ASS 的 `\k`），为空表示整行高亮。
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val karaoke: List<KaraokeSegment> = emptyList(),
    /** ASS/SSA 的样式名（对应 [SubtitleDocument.styles] 的 key）。 */
    val styleName: String? = null,
    /** ASS 的 Name 字段（对白角色），可用于「只显示某角色」。 */
    val actor: String? = null,
    /** 解析出的显式位置（ASS `\pos` / `\an` / 默认样式）。 */
    val position: CuePosition? = null,
    /** ASS 行内覆盖标签（`\an8`、`\fs20`、`\b1`…）原样保留，渲染阶段解析。 */
    val overrides: Map<String, String> = emptyMap(),
    /** ASS 的 Comment 行，默认不渲染。 */
    val isComment: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    fun isActiveAt(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** 逐字/逐段时间的片段（卡拉OK 高亮）。 */
data class KaraokeSegment(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * 字幕位置。全部用 0..1 归一化比例存储，
 * 这样换分辨率/换方向（横竖屏、画中画、投屏）时不需要任何重新计算。
 */
data class CuePosition(
    /** 0 = 左边，1 = 右边。 */
    val anchorX: Float = 0.5f,
    /** 0 = 顶边，1 = 底边。 */
    val anchorY: Float = 0.9f,
    /** ASS 的对齐编号 `\an1..\an9`，1=左下 … 9=右上。 */
    val alignment: Int = 2,
    val marginLeftPercent: Float = 0f,
    val marginRightPercent: Float = 0f,
    val marginVerticalPercent: Float = 0f,
)

/**
 * 字幕样式定义（ASS `[V4+ Styles]` 一行的领域表示）。
 *
 * 所有字段可空：含义是「未指定 → 用用户偏好 / 应用默认值兜底」，
 * 而不是「未指定 = 透明」。渲染链路上一律按「最近的非空值」解析。
 */
data class SubtitleStyleDef(
    val name: String? = null,
    val fontFamily: String? = null,
    /** 相对播放器基准字号的倍率。 */
    val fontScale: Float? = null,
    val primaryColorArgb: Long? = null,
    val secondaryColorArgb: Long? = null,
    val outlineColorArgb: Long? = null,
    val backColorArgb: Long? = null,
    /** 相对字号的比例（ASS 用「相对字号」的百分比）。 */
    val outlineWidth: Float? = null,
    val shadowWidth: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikeThrough: Boolean? = null,
    val spacing: Float? = null,
    val angleDegrees: Float? = null,
    val alignment: Int? = null,
    val marginLeftPercent: Float? = null,
    val marginRightPercent: Float? = null,
    val marginVerticalPercent: Float? = null,
)

/** 一条字幕轨的元信息（不含 cue 本体，便于在列表里传递）。 */
data class SubtitleTrack(
    val id: String,
    val origin: SubtitleOrigin = SubtitleOrigin.EXTERNAL_FILE,
    val format: SubtitleFormat = SubtitleFormat.UNKNOWN,
    /** BCP-47，例如 `zh-CN`、`en`；未知为 null。 */
    val languageTag: String? = null,
    val label: String? = null,
    /** 外挂字幕的文件位置。 */
    val sourceUri: String? = null,
    /** Media3 `TrackGroup` 中的索引，内嵌轨用。 */
    val embeddedTrackIndex: Int? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /**
     * 该轨道由哪条轨道派生（翻译轨 → 原轨）。
     * 删除原轨时必须一并删除派生轨，否则会留下永远高亮不了的空壳。
     */
    val derivedFromTrackId: String? = null,
    val cueCount: Int = 0,
)

/** 解析结果：轨道元信息 + 全部 cue + 样式表 + 解析告警。 */
data class SubtitleDocument(
    val track: SubtitleTrack,
    val cues: List<SubtitleCue>,
    val styles: Map<String, SubtitleStyleDef> = emptyMap(),
    /** ASS 的 `[Script Info]` 默认样式。 */
    val defaultStyle: SubtitleStyleDef? = null,
    /** `[Script Info]` / `[Aegisub Project Garbage]` 里的元数据（标题、原语言…）。 */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * 非致命问题（跳过的坏行、重叠时间轴…）。
     * 刻意收集而不是抛异常：一行坏掉不该让整部片子没字幕。
     */
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = cues.isEmpty()

    /**
     * 二分查找给定播放位置应显示的 cue。
     * 字幕列表动辄上千条，线性扫描会在低端机上掉帧，所以这里用二分。
     */
    fun cueAt(positionMs: Long): SubtitleCue? {
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }
}
