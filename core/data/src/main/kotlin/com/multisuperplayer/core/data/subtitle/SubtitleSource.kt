package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat

/**
 * 自动挂载字幕的最低匹配分。
 *
 * `85` 对应「字幕名与媒体名互为前缀」这一档（`Show.S01E01.srt` 配
 * `Show.S01E01.1080p.mkv`），也就是**名字本身就是同一条片子**。
 *
 * 低于它的候选不会被自动应用，只出现在手动选择列表里。理由是不对称的：
 *
 * - 漏配的代价是用户多点两下；
 * - 错配的代价是「字幕时间轴完全对不上」或者「台词是另一部片子的」，
 *   而用户从界面上看不出这是匹配算法的问题，只会认为播放器坏了。
 *
 * 这个数字必须和 `SubtitleFileNaming` 里那几档分数保持一致，
 * `SubtitleFileNamingTest` 里有一条断言把两者钉在一起。
 */
const val AUTO_MATCH_SCORE = 85

/**
 * 这条候选有没有资格被自动挂上。
 *
 * 注意它比 [SubtitleSource.matchesMedia] 严得多：后者只回答「对得上吗」，
 * 用于列表里排序和分组；前者回答「够不够格不看用户一眼就用」。
 */
val SubtitleSource.isAutoMatchable: Boolean get() = matchScore >= AUTO_MATCH_SCORE

/**
 * 候选的统一排序：**列表顺序和「自动挑哪一条」用的是同一套规则**。
 *
 * ## 为什么必须只有一处
 *
 * 面板上的列表顺序是「自动选择」那行的解释：用户看到第一项不是被自动挂上的那条，
 * 会认为自动逻辑坏了。两处各写一份比较器，就等于把这个解释变成巧合。
 *
 * ## 四层，从「对不对」到「稳不稳」
 *
 * 1. **关联分降序**——这是唯一衡量「是不是这条片子的字幕」的量。
 * 2. **片名之外的标记越少越靠前**。这一层是真正解决「同分」的：
 *    `Show.srt` / `Show.chs.srt` / `Show.bilingual.srt` 的关联分**完全相等**
 *    （片名都等于媒体名，[SubtitleFileNaming.matchScore] 只看片名）。
 *    此时唯一有意义的区别就是「谁的名字里多说了点什么」——而多出来的每一段标记
 *    都是发布者在告诉你「这是一个变体」。名字和片子一模一样的那份，
 *    最可能就是「这片子的字幕」本身。
 *    （不用文件名字典序来当这一层：那等于让 `bilingual` 里那个 `b` 决定结果，
 *    换个名字答案就变，而且没人能从界面上理解为什么。）
 * 3. **强制字幕靠后**。`forced` 只覆盖外语对白那几句，挂上它大部分时间是空屏；
 *    旁边有完整字幕时不该选它。
 * 4. **文件名升序**——纯稳定性兜底。少了它，MediaStore 每次返回的顺序都能让
 *    「这次打开挂的字幕」变一次，而用户完全无从理解。
 *
 * 语言偏好（「我会中文，优先挑中文字幕」）刻意不在这里：那需要用户设置，
 * 属于翻译/语言那一版的事。**这一层将来就插在第 2 层之后**——在那之前，
 * 一个「未标语言」的文件和一个「简体中文」的文件只能靠标记数分先后。
 */
private val candidateOrder: Comparator<SubtitleSource> =
    compareByDescending<SubtitleSource> { it.matchScore }
        .thenBy { it.trailingTagCount }
        .thenBy { it.isForced }
        .thenBy { it.fileName }

/** 按 [candidateOrder] 排好序的候选。面板列表与自动挑选都走这里。 */
fun List<SubtitleSource>.sortedForSelection(): List<SubtitleSource> = sortedWith(candidateOrder)

/**
 * 从候选里挑出该自动挂上的那一条。
 *
 * 够格的不止一条时取排序第一——命名朴素的那条，且同分时结果稳定，
 * 不会因为 MediaStore 返回顺序变化而「每次打开挂的字幕都不一样」。
 */
fun List<SubtitleSource>.bestAutoMatch(): SubtitleSource? =
    sortedForSelection().firstOrNull { it.isAutoMatchable }

/**
 * 一条可用的外挂字幕（已经知道文件名、格式、语言，但**还没读内容**）。
 *
 * 分成「先列候选、再按需加载」两步：一条媒体旁边可能躺着十几个语言版本，
 * 全部读进来解析既慢又费内存，而用户通常只会看其中一个。
 */
data class SubtitleSource(
    val uri: String,
    val fileName: String,
    /** 由后缀推断的格式，仅用于展示与排序；真实格式以解析结果为准。 */
    val format: SubtitleFormat,
    /** BCP-47，认不出来时为 null。 */
    val languageTag: String?,
    val isForced: Boolean,
    /** 文件名标明这文件里同时含两种语言（`movie.中英.srt`）。 */
    val isBilingual: Boolean,
    val sizeBytes: Long,
    /**
     * 关联打分：`0` 表示和这条媒体对不上（只能手动选），越大越像。
     *
     * 已经包含「格式与媒体类型是否对口」的加减分，所以直接拿它排序即可。
     */
    val matchScore: Int,
    /**
     * 文件名里片名之外还带了几段标记（`chs`、`forced`、`双语`…）。
     *
     * 不参与打分，只用于同分候选之间的先后，见 [candidateOrder]。
     * 由 [SubtitleFileNaming.analyze] 算出后带过来，而不是排序时现算——
     * 比较器会被调用 O(n log n) 次，每次重解析一遍文件名属于白烧 CPU。
     */
    val trailingTagCount: Int,
) {
    /** 是否可以自动挂上（而不是只能出现在手动列表里）。 */
    val matchesMedia: Boolean get() = matchScore > SubtitleFileNaming.NO_MATCH
}

/** 扫描某条媒体的外挂字幕的结果。 */
sealed interface SubtitleScan {
    /** 扫到了候选，可能一个都不匹配（那时 `sources.any { it.matchesMedia }` 为 false）。 */
    data class Found(val sources: List<SubtitleSource>) : SubtitleScan

    /**
     * 拿不到媒体所在目录，无法自动查找。
     *
     * 两种来源：媒体库条目没有 `relativePath`（Android 9 及以下拿不到这个列），
     * 或路径本身没有上一级目录。**文件浏览器打开的条目不再落进这一档**——
     * 它的绝对路径能直接推出目录，见 [subtitleLookupOf]。
     */
    data object NoDirectory : SubtitleScan

    /** 目录读不到：查询没通 / 拒绝列目录，多半是权限问题。 */
    data object DirectoryInvisible : SubtitleScan

    /**
     * 查询本身失败。[message] 是给人看的说明：系统抛出来的原文包成 [MspText.Plain]，
     * 我们自己造的句子用 [MspText.Res]，界面才能按当前语言显示。
     */
    data class Failed(val message: MspText) : SubtitleScan
}

/** 加载并解析一条字幕的结果。 */
sealed interface SubtitleLoadResult {
    data class Loaded(val document: SubtitleDocument) : SubtitleLoadResult

    /**
     * [message] 是给人看的说明。系统抛出来的原文包成 [MspText.Plain]（不翻译），
     * 只有我们自己发现的失败才换成 [MspText.Res] 这样可翻译的句子——
     * 真实原因不能被一句翻译盖掉。
     */
    data class Failed(val fileName: String, val message: MspText) : SubtitleLoadResult
}
