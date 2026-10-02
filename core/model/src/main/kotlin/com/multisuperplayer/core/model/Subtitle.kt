package com.multisuperplayer.core.model

/**
 * 字幕 / 歌词的容器格式。
 *
 * 注意 LRC 与「增强型 LRC」共用 `.lrc` 后缀，所以**不能只靠后缀判断**：
 * 解析器要嗅探内容（是否含 `<mm:ss.xx>` 逐字标签 / `[ar:]` 标签），
 * 见 `core:subtitle` 的 `SubtitleFormatDetector`。
 *
 * [displayName] 刻意是**中立的 ASCII 名**（格式自己的名字），不是给用户看的文案：
 * 本模块为了能被其它模块零成本依赖，不引用任何资源与工具类，
 * 于是「LRC 歌词」这种要跟着语言走的说法只能放在界面层。
 * 界面层用 `SubtitleFormat.label()`（`feature:player`）拿到本地化后的文本。
 */
enum class SubtitleFormat(
    val displayName: String,
    val extensions: List<String>,
) {
    SRT("SubRip", listOf("srt")),
    VTT("WebVTT", listOf("vtt", "webvtt")),
    ASS("Advanced SubStation Alpha", listOf("ass")),
    SSA("SubStation Alpha", listOf("ssa")),
    LRC("LRC", listOf("lrc")),
    ENHANCED_LRC("Enhanced LRC", listOf("lrc", "elrc")),
    TTML("TTML / DFXP / SMPTE-TT", listOf("ttml", "dfxp", "xml")),
    VOBSUB("VobSub", listOf("idx", "sub")),
    PGS("PGS (Sup)", listOf("sup")),
    UNKNOWN("Unknown", emptyList()),
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

    /**
     * 时间轴上最后一个**已经开始**的 cue——它可能已经结束了，只是后面还没有下一行。
     *
     * ## 为什么不复用 [cueAt]（两者不能互换）
     *
     * - [cueAt] 回答「此刻屏幕上该有字幕吗」：两条 cue 之间的空档返回 `null`。
     *   一部片子里大量静默段落不该继续挂着上一句台词。
     * - [cueFocusedAt] 回答「此刻该高亮哪一行」：LRC 只记录每行的开始时间、
     *   根本没有结束概念，用 [cueAt] 会让高亮在每句末尾闪一下或者整句消失。
     *
     * 所以：视频字幕层用 [cueAt]，歌词页与「跳到当前行」用 [cueFocusedAt]。
     * 两个方法都假定 `cues` 已按 `startMs` 升序排列（解析器的 `CuePostProcess` 保证）。
     *
     * @return 位置早于第一条 cue 时返回 `null`。
     */
    fun cueFocusedAt(positionMs: Long): SubtitleCue? =
        cues.getOrNull(cueFocusIndexAt(positionMs))

    /** [cueFocusedAt] 的下标版本；没有命中时返回 `-1`。 */
    fun cueFocusIndexAt(positionMs: Long): Int {
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/**
 * 逐字高亮的时间轴：把时长为 0 的片段补成可插值的区间。
 *
 * ## 为什么必须有这一步
 *
 * LRC 的内联标记 `<mm:ss.xx>` 只标**每一段的开始时间**，最后一段后面没有东西了，
 * 所以解析器只能给它 `durationMs = 0`（`LrcParser` 与 `WebVttParser` 的收尾片段
 * 都是这样）。ASS 的 `\k` 同理：末段时长取决于下一句什么时候来，文件里没写。
 *
 * 直接拿原始片段去插值，症状是**最后一句永远不会被高亮扫过去**——它在整句里占的
 * 比例是 0，看起来就像歌手漏唱了最后几个字。而这恰恰是最容易被忽略的：
 * 前面每句都正常，只有每行末尾差一点。
 *
 * 补的规则：
 * - 中间片段 → 下一段的开始时间；
 * - 最后一段 → 整句的结束时间（LRC 里就是下一行开始之前，也就是这一句被拉长的那一刻）；
 * - 连整句都没有结束时间（`endMs <= startMs`）→ 仍然是 0，
 *   此时渲染层按「一旦开始就整段高亮」处理，不会除以 0。
 *
 * 时长本来就大于 0 的片段原样返回，不做任何修正——解析器给出的明确时间优先。
 */
fun SubtitleCue.karaokeTimeline(): List<KaraokeSegment> {
    if (karaoke.isEmpty()) return emptyList()
    return karaoke.mapIndexed { index, segment ->
        if (segment.durationMs > 0L) {
            segment
        } else {
            val nextStartMs = karaoke.getOrNull(index + 1)?.startMs
            val resolvedEndMs = nextStartMs ?: endMs
            segment.copy(durationMs = (resolvedEndMs - segment.startMs).coerceAtLeast(0L))
        }
    }
}
