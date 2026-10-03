package com.multisuperplayer.core.player

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import kotlinx.coroutines.flow.StateFlow

/**
 * 可切换的轨道种类。
 *
 * 只有这两种：视频轨（多角度）在实际片源里几乎只出现在 BD 原盘，而我们把
 * 「切轨」这件事做成一个功能之前，先把它限定在**用户真的会用到**的两类上。
 */
enum class MspTrackKind {
    AUDIO,
    TEXT,
}

/**
 * 一条可选的轨道（音频或字幕）。
 *
 * ## 为什么带 `id`
 *
 * 选轨必须能**稳定地指回**某一条：列表是每一帧重新渲染的，语言/标签相同的
 * 两条轨（`简` / `繁` 都标着 `zh`）只能靠 id 区分。id 由 [id] 的构造方负责，
 * 规则是「同一条媒体内稳定、跨条目不承诺」——见 `ExoPlayerController` 的构造。
 *
 * ## 为什么 `language` 是归一化过的
 *
 * Matroska 用 ISO-639-2（`chi`），MP4 用 ISO-639-1（`zh`），不归一化的话
 * 「按语言挑轨」会在一种容器里永远匹配不上。见 [normalizeLanguageTag]。
 */
data class MspTrackInfo(
    val id: String,
    val kind: MspTrackKind,
    /** 容器里写的标签（`国语`、`Commentary`），常常没有。 */
    val label: String? = null,
    /** 归一化成 ISO-639-1 的语言（`zh`/`en`），认不出来时为 null。 */
    val language: String? = null,
    /** 轨道内容的 MIME（`application/x-subrip`、`audio/ac3`）。 */
    val mimeType: String = "",
    /** 在所属 `TrackGroup` 里的下标。内嵌字幕用它回填 `SubtitleTrack.embeddedTrackIndex`。 */
    val indexInGroup: Int = 0,
    /** 编解码器名（`subrip`、`ac-3`），仅供展示。 */
    val codec: String? = null,
    /** 声道数，只有音频轨有（`0` 与未知都收敛成 null）。 */
    val channelCount: Int? = null,
    val isSelected: Boolean = false,
    /** 容器标了「默认轨」。 */
    val isDefault: Boolean = false,
    /** 容器标了「强制轨」——只覆盖外语台词那几句。 */
    val isForced: Boolean = false,
) {
    /**
     * 主标题的取值顺序：容器标签 → 语言 → [fallback]。
     *
     * [fallback] 由界面层给出（「音轨 3」这种本地化文案），因为这一层没有资源。
     */
    fun displayLabel(fallback: String): String =
        label?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }
            ?: fallback

    /**
     * 这条轨道**真正的**字幕内容 MIME（见 [subtitleMimeOf]）。
     *
     * 不能直接用 [mimeType]：Media3 把**从容器里解析出来的文本轨**统一报成
     * `application/x-media3-cues`，真正的格式放在 `codecs` 里。实测（MKV 里两条
     * subrip 轨）：
     *
     * ```
     * kind=TEXT mime='application/x-media3-cues' codec=application/x-subrip lang=zh
     * ```
     *
     * 只看 [mimeType] 的后果有两个，而且第二个更糟：
     * 1. 格式显示成「未知」——`codecs` 就在手边，白白丢掉；
     * 2. [isTextRenderable] 判 false ⇒ 这条轨从面板的清单里**整个消失**，而
     *    「当前挂着哪条」那条路（[firstSelectedTextTrack]）不看格式、照样把它画在
     *    面板顶部。于是同一个面板上面写着「已自动选中「zh」」、下面一条可选项都没有。
     */
    fun subtitleMimeType(): String = subtitleMimeOf(mimeType = mimeType, codec = codec)

    /** 格式认不出来时是 [SubtitleFormat.UNKNOWN]，界面照实显示「未知」。 */
    fun subtitleFormat(): SubtitleFormat = subtitleFormatOf(subtitleMimeType())

    /**
     * 这条轨道的内容能不能进我们的**文本**字幕层。
     *
     * 位图字幕（PGS / VobSub）只能由渲染器直接画到画面上，而我们没有挂 Media3 的
     * `SubtitleView`（见 `SubtitleOrigin.EMBEDDED` 的设计说明）。所以它们**不会**
     * 显示——这一点必须明说，而不是让它们在列表里装作可以选，用户点完什么都没发生
     * 只会以为播放器坏了。（它们的 `Cue.text` 也是空的，`onCues` 那一侧会自然丢掉，
     * 这里只是提前把它们从清单里摘出去。）
     */
    fun isTextRenderable(): Boolean {
        val mime = subtitleMimeType()
        if (mime in BITMAP_SUBTITLE_MIMES) return false
        // Media3 用它自己的 cue 包送过来的（而且没在 `codecs` 里写真实 MIME）：能走到
        // 这里就说明它认得这个格式、并且已经解成文本了，所以哪怕我们还没见过这个
        // MIME 也算可渲染——判 false 的代价是「屏幕上明明在放字幕，面板里一条都选不到」。
        if (mime == MEDIA3_CUES_MIME) return true
        if (mime.startsWith("text/")) return true
        if (mime.contains("cea-608") || mime.contains("cea-708")) return true
        return subtitleFormatOf(mime) != SubtitleFormat.UNKNOWN
    }
}

/**
 * Media3 送给字幕轨的 cue 包 MIME（= `MimeTypes.APPLICATION_MEDIA3_CUES`）。
 *
 * 容器里解析出来的字幕轨，Media3 会把它**重写**成这个 MIME，把原始格式挪到
 * `Format.codecs`（`SubtitleTranscodingTrackOutput` 的行为）。所以对字幕轨来说
 * `sampleMimeType` 几乎不携带信息，`codecs` 才是格式。
 *
 * 在这里写成常量而不是 import，是为了让本文件保持「不依赖 Media3 的纯逻辑」
 * （它的单测才不会被动拖进一个播放器）。常量值用 `javap -constants` 对着
 * `media3-common:1.11.1` 核对过。
 */
private const val MEDIA3_CUES_MIME = "application/x-media3-cues"

/**
 * 位图字幕的 MIME——我们画不出来，必须从「可选字幕轨」里排除。
 *
 * 值来自 `javap -constants androidx.media3.common.MimeTypes`（1.11.1）：
 * `APPLICATION_PGS` / `APPLICATION_VOBSUB` / `APPLICATION_DVBSUBS`。注意**没有**
 * `application/x-pgs` 这种写法：原来的单测里写的是它，而它在 Media3 里根本不存在，
 * 于是「排掉位图字幕」那一段其实从来没有被真的执行过（测试是绿的，因为它测的是
 * 一个永远不会出现的值）。
 *
 * 为什么这些轨**会**带着 cue 包 MIME 进来：media3-extractor 的
 * `DefaultSubtitleParserFactory` 同样处理 pgs / vobsub / dvbsubs，而它输出的轨正是被
 * 重写成 cue 包的（`PgsParser` 产出的 `Cue` 只有 bitmap、`text` 为空）。所以
 * 「cue 包 MIME 一律可渲染」那条宽松规则挡不住 PGS，只有这份名单拦得住它。
 */
private val BITMAP_SUBTITLE_MIMES = setOf(
    "application/pgs",
    "application/vobsub",
    "application/dvbsubs",
)

/**
 * 轨道 MIME 的归一化：把 Media3 的 cue 包 MIME 换回容器里写的那个真实格式。
 *
 * `codecs` 只在**看起来是 MIME**（带 `/`）时才采信：字幕轨的 `codecs` 就是一个 MIME
 * （`application/x-subrip`），而音频/视频轨的是 RFC 6381 编解码器名（`mp4a.40.2`），
 * 后者绝不能被当成字幕格式。采信之后**不再筛格式**：`codecs=application/pgs` 正是我们
 * 要排掉的那个位图字幕，把它丢掉会让它退化成 cue 包 MIME、反而被判成可渲染。
 */
private fun subtitleMimeOf(mimeType: String, codec: String?): String {
    val mime = mimeType.trim().lowercase()
    if (mime != MEDIA3_CUES_MIME) return mime
    val fromCodecs = codec?.trim()?.lowercase().orEmpty()
    return fromCodecs.takeIf { it.contains('/') } ?: mime
}

/**
 * 「这一条 cue 还没结束」的结束时间。
 *
 * ## 为什么内嵌 cue 的结束时间是后验的
 *
 * Media3 的回调语义是「**此刻该显示什么**」，每次递过来的是当下成立的那一批 cue，
 * 而且 `Cue` 本身**只有开始时间、没有结束时间**（结束时间由下一批回调隐式表达，
 * 字幕放完时给一批空的）。所以上一条的 `endMs` 只能等到下一批到来时才回填。
 *
 * 用 `Long.MAX_VALUE` 而不是「留一个 null 字段」：`SubtitleCue.endMs` 是非空 Long，
 * 而开放中的 cue 在语义上确实是「到现在为止有效」，`cueAt` 不需要为它加分支。
 */
const val OPEN_CUE_END_MS = Long.MAX_VALUE

/**
 * 已经从容器里读出来的内嵌字幕。
 *
 * 只存 cue，不存「是哪条轨」：轨道选择是 `SubtitleViewModel` 的事，
 * 这里只回答「至今为止读到过哪些行」。
 */
data class EmbeddedSubtitleState(
    /** 按开始时间升序的 cue。字幕轨是流式到的，只包含**已经播到的**那一段。 */
    val cues: List<SubtitleCue> = emptyList(),
) {
    /**
     * 收下新的一批 cue。
     *
     * ## 四件事，缺一不可
     *
     * 1. **同一批合成一行**：Media3 按「一个 `Cue` 一行」给，两行字幕是**两个**
     *    `Cue`。我们的字幕层是「一条 cue 里可以有多行」（`cueLinesFor` 按 `\n` 切），
     *    所以这里必须用 `\n` 拼成一条，否则屏幕上只会出现两行里的第一行。
     * 2. **回填结束时间**：上一批里还开放着的 cue 在此刻结束。
     * 3. **不重复追加**：来回拖进度条会把同一句反复送来，不去重的话字幕层里会出现
     *    几十条一模一样的行，而「已译 N/M」这种计数会一路涨到荒谬的值。
     * 4. **保持有序**：先往前拖到没看过的地方、再往回拖，到达的 cue 可能比已有的
     *    **更早**。`SubtitleDocument.cueAt` 是二分查找，列表一乱它就返回错的行。
     *    乱序插入会顺带重排后面的 `index`；译文是按 `index + 原文` 存的（见
     *    `TranslationEditsStore`），所以重排只是让它的键换了位置，不会串行。
     *
     * `texts` 全是空白就直接返回：字幕放完时 Media3 给的是一批空内容，
     * 那正是「结束了」的信号，不是一条新字幕。
     */
    fun receive(atMs: Long, texts: List<String>): EmbeddedSubtitleState {
        val now = atMs.coerceAtLeast(0L)
        val closed = if (cues.any { it.endMs == OPEN_CUE_END_MS }) {
            cues.map { cue ->
                if (cue.endMs == OPEN_CUE_END_MS) cue.copy(endMs = now.coerceAtLeast(cue.startMs)) else cue
            }
        } else {
            cues
        }

        val text = texts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (text.isEmpty()) return EmbeddedSubtitleState(closed)

        // 同一时刻已经有过一模一样的一行：这是重放，不是新字幕。
        if (closed.any { it.startMs == now && it.text == text }) return EmbeddedSubtitleState(closed)

        val cue = SubtitleCue(index = 0, startMs = now, endMs = OPEN_CUE_END_MS, text = text)
        // 常见情况（顺序播放）直接追加，只有「往回拖到没看过的地方」才需要插入排序。
        val merged = if (closed.isEmpty() || now >= closed.last().startMs) {
            closed + cue
        } else {
            (closed + cue).sortedBy { it.startMs }
        }
        // index 就是位置：`cueIndex` 与人工译文都按它取用，必须连续且从 0 开始。
        return EmbeddedSubtitleState(merged.mapIndexed { index, c -> c.copy(index = index) })
    }
}

/**
 * 语言代码归一化：ISO-639-2/B（Matroska 常写 `chi`）→ ISO-639-1（`zh`）。
 *
 * 认不出来 / `und`（未定）/ 空 → null，也就是「这条轨没告诉我们是什么语言」。
 * `und` 必须当成 null：它字面意思是「未定」，拿它去和用户语言比会出现
 * 「所有未标语言的轨都算匹配」这种荒谬结果。
 *
 * 只保留主语言子标签（`zh-Hans-CN` → `zh`）：容器里写 `zh-Hant` 而用户是
 * `zh-Hans` 时，我们宁可算匹配——用户在面板上还能自己换，而漏匹配的后果是
 * 「有中文字幕却不自动挂上」。
 */
fun normalizeLanguageTag(raw: String?): String? {
    val tag = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = tag.substringBefore('-').substringBefore('_').lowercase()
    if (primary.isEmpty() || primary == "und") return null
    return LANGUAGE_ALIASES[primary] ?: primary
}

/** ISO-639-2/B → ISO-639-1。只收录常见语言，认不出来时保留原样。 */
private val LANGUAGE_ALIASES = mapOf(
    "chi" to "zh", "zho" to "zh", "cmn" to "zh", "yue" to "zh", "wuu" to "zh",
    "eng" to "en", "jpn" to "ja", "kor" to "ko",
    "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de",
    "spa" to "es", "rus" to "ru", "por" to "pt", "ita" to "it",
    "nld" to "nl", "dut" to "nl", "pol" to "pl", "swe" to "sv",
    "ukr" to "uk", "ces" to "cs", "cze" to "cs", "dan" to "da",
    "fin" to "fi", "nor" to "no", "ell" to "el", "gre" to "el",
    "ara" to "ar", "heb" to "he", "hin" to "hi", "ben" to "bn",
    "tha" to "th", "vie" to "vi", "ind" to "id", "msa" to "ms",
    "tur" to "tr", "fas" to "fa", "per" to "fa", "ron" to "ro", "rum" to "ro",
)

/** 这条轨的语言在不在 [preferred] 里。语言未知一律算不匹配（见 [normalizeLanguageTag]）。 */
fun MspTrackInfo.matchesLanguage(preferred: List<String>): Boolean {
    val mine = language ?: return false
    return preferred.any { normalizeLanguageTag(it) == mine }
}

/**
 * 同分时的取舍顺序：**非强制轨优先，其次默认轨优先**。
 *
 * 强制轨只覆盖外语台词那几句，自动挂上它大部分时间是空屏；
 * 旁边有完整字幕时选它就是选错。
 */
private val AUTO_PICK_ORDER: Comparator<MspTrackInfo> =
    compareBy<MspTrackInfo>({ if (it.isForced) 1 else 0 }, { if (it.isDefault) 0 else 1 })

/**
 * 自动挑一条内嵌字幕轨来挂。null = **不自动挂**（用户可以自己去面板里选）。
 *
 * ## 规则（两层，顺序不能反）
 *
 * 1. **语言对得上**：在匹配的那些里按 [AUTO_PICK_ORDER] 挑。
 * 2. **语言对不上**：只有容器自己标了「默认轨」才挂。
 *
 * 第 2 条是刻意的保守：片源里只有一条法语字幕、用户看的是中文界面时，
 * 自动挂上它等于「一打开就冒出一堆看不懂的字幕」。自动逻辑宁可什么都不做，
 * 让人发现「有一条可选」，也不要替人做错决定——这和外挂字幕那边
 * `AUTO_MATCH_SCORE` 宁可漏配不可错配是同一个取舍。
 *
 * `preferred` 是**字幕偏好语言**，当前取自应用界面语言（还没有独立的设置项，
 * 那一项属于语言/翻译那一版）。空列表 = 只认「默认轨」标记。
 */
fun List<MspTrackInfo>.bestEmbeddedTextTrack(preferred: List<String>): MspTrackInfo? {
    val texts = embeddedTextTracks()
    if (texts.isEmpty()) return null
    val matching = texts.filter { it.matchesLanguage(preferred) }
    if (matching.isNotEmpty()) return matching.minWithOrNull(AUTO_PICK_ORDER)
    return texts.filter { it.isDefault }.minWithOrNull(AUTO_PICK_ORDER)
}

/**
 * 轨道清单 + 内嵌字幕的**窄接口**。
 *
 * 单独抽出来，是为了让字幕那一层（`SubtitleViewModel`）只依赖这四样东西，
 * 而不是整个 `PlaybackController`：它需要的就是「有哪些内嵌字幕轨」「选一条」
 * 「读到哪些行了」，不需要暂停/倍速/队列。依赖面越小，单测里假造它就越便宜。
 */
interface TrackSelectionController {

    /** 当前媒体条目里的可选轨道。没有媒体时为**空列表**（而不是 null）。 */
    val tracks: StateFlow<List<MspTrackInfo>>

    /**
     * 从容器里读到的内嵌字幕行（流式累积）。
     *
     * 切换媒体条目时**清空**：上一部片子的台词套到新片子上，位置对不上、内容也不对，
     * 而用户从界面上完全看不出这是「没收走」造成的。
     */
    val embeddedSubtitle: StateFlow<EmbeddedSubtitleState>

    /**
     * 选定某一条轨道（用户明确点的）。
     *
     * 记住的范围是**当前这条媒体**：换到下一条时回到自动挑选。理由和字幕面板
     * 「手动选择只对那条媒体有效」一致——留着手选会让用户在下一条上看到
     * 「明明没选过却是另一条」。
     *
     * id 认不出来（那一条已经不在当前片源里）时是**空操作**，不抛异常：
     * 列表和选择之间隔着几帧，用户点的那条完全可能在点击落地前就消失了。
     */
    fun selectTrack(kind: MspTrackKind, id: String)

    /**
     * 回到**自动**挑选：清掉用户手选的那条，重新按语言/默认标记选一条。
     *
     * 和 [selectTrack] 对称：一个说「就这条」，一个说「你别管了」。没有这个入口，
     * 用户点了「自动」以后内嵌轨会仍旧停在他上一次手选的那条上——界面说自动、
     * 实际没自动，而且看不出来。
     */
    fun useAutomaticTracks()

    /**
     * 音频轨回到自动挑选。
     *
     * ## 为什么不复用 [useAutomaticTracks]
     *
     * 那个方法会把**字幕轨**的手选一起清掉。音轨面板上的「自动」不是「字幕也回到
     * 自动」的意思，而用户在那里点一下却发现字幕的位置也换了，是完全不可预期的——
     * 两个面板各自只能改自己那一维。
     */
    fun useAutomaticAudioTrack()
}

/** 只留音频轨。 */
fun List<MspTrackInfo>.audioTracks(): List<MspTrackInfo> =
    filter { it.kind == MspTrackKind.AUDIO }

/**
 * 音频轨当前处于「哪一条」。
 *
 * 返回 null 有两种含义，而界面对它们的画法**一样**（都写「自动」）：
 * 1. 内核正在同一个 `TrackGroup` 的多个档位之间自适应。DASH/HLS 的同语言多码率
 *    会让**多条**同时报 `isSelected`，此时说「正在听第 2 条」是错的；
 * 2. 一条都没选中——轨道信息还没解析出来，或者这个片源没有音频。
 *
 * 用 `singleOrNull` 而不是 `firstOrNull`：[firstOrNull] 在上面的第一种情况里会
 * 从自适应档位中随便挑一条显示，而那条很可能不是此刻真正在解码的那条。
 */
fun List<MspTrackInfo>.selectedAudioTrack(): MspTrackInfo? =
    filter { it.kind == MspTrackKind.AUDIO && it.isSelected }.singleOrNull()

/** 只留能在我们自己的字幕层里渲染的文本轨（位图字幕、以及没有文本的轨都排除）。 */
fun List<MspTrackInfo>.embeddedTextTracks(): List<MspTrackInfo> =
    filter { it.kind == MspTrackKind.TEXT && it.isTextRenderable() }

/** 内核当前实际选中的那条文本轨（没有就是 null）。 */
fun List<MspTrackInfo>.firstSelectedTextTrack(): MspTrackInfo? =
    firstOrNull { it.kind == MspTrackKind.TEXT && it.isSelected }

/**
 * 容器里的字幕轨属于哪种格式。
 *
 * 认不出来返回 [SubtitleFormat.UNKNOWN]，界面照实显示「未知格式」——
 * 猜一个格式出来会让用户以为「这文件就是 SRT 吧」，然后拿
 * 「为什么 SRT 渲染出来是乱的」来问。
 */
fun subtitleFormatOf(mimeType: String?): SubtitleFormat {
    val mime = mimeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return SubtitleFormat.UNKNOWN
    return when (mime) {
        "application/x-subrip", "application/x-srt", "application/srt", "text/srt" -> SubtitleFormat.SRT
        // `application/x-mp4-vtt`（带连字符）是 Media3 的 `APPLICATION_MP4VTT`，MP4 内封
        // WebVTT 走的正是它；原来只写了没连字符的那个写法，于是这种轨一律显示「未知」。
        "text/vtt", "application/x-vtt", "application/x-mp4vtt", "application/x-mp4-vtt" -> SubtitleFormat.VTT
        "text/x-ssa", "application/x-ssa" -> SubtitleFormat.SSA
        "text/x-ass", "application/x-ass" -> SubtitleFormat.ASS
        "application/ttml+xml", "application/x-ttml+xml", "text/ttml" -> SubtitleFormat.TTML
        else -> SubtitleFormat.UNKNOWN
    }
}
