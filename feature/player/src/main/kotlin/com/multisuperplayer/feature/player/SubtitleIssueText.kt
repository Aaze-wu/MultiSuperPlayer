package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.isAutoMatchable
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.player.MspTrackInfo


/**
 * 字幕相关的界面文案。
 *
 * 集中在一个文件里，是因为它们是**同一批**要跟着语言走的东西。
 *
 * 这些函数返回 [MspText] 而不是 `String`：它们大多要在纯逻辑里拼句子
 * （「格式 · 语言 · 强制」），而拼接必须发生在**取到资源之后**——`String` 一旦
 * 提前拼好，语言就锁死在拼接时的那个 `Context` 上了，切语言不会跟着变。
 */

/** 显示模式的名字。 */
internal fun SubtitleDisplayMode.label(): MspText = when (this) {
    SubtitleDisplayMode.OFF -> MspText.Res(R.string.msp_player_mode_off)
    SubtitleDisplayMode.ORIGINAL_ONLY -> MspText.Res(R.string.msp_player_mode_original_only)
    SubtitleDisplayMode.TRANSLATION_ONLY -> MspText.Res(R.string.msp_player_mode_translation_only)
    SubtitleDisplayMode.BILINGUAL -> MspText.Res(R.string.msp_player_mode_bilingual)
}

/**
 * 字幕格式的名字。
 *
 * [SubtitleFormat.displayName] 是格式自己的名字（`SubRip`、`WebVTT`……），
 * 中立的 ASCII，直接用；只有少数几个格式在中文里另有说法，才走资源。
 * 之所以不在 `core:model` 里放资源，是因为那个模块刻意零依赖。
 */
internal fun SubtitleFormat.label(): MspText = when (this) {
    SubtitleFormat.LRC -> MspText.Res(R.string.msp_player_format_lrc)
    SubtitleFormat.ENHANCED_LRC -> MspText.Res(R.string.msp_player_format_lrc_enhanced)
    SubtitleFormat.UNKNOWN -> MspText.unknown()
    else -> MspText.Plain(displayName)
}

/**
 * 把加载过程中的问题说成一句用户看得懂的话。
 *
 * `when` 穷尽所有分支，所以新增一种失败时编译器会提醒补文案——
 * 这正是**不能**用一个通用的「加载失败」兜底的地方：每种失败的下一步操作
 * 都不一样（去授权限 / 换个文件夹 / 重命名字幕），一句话兜住等于什么都没说。
 */
internal fun SubtitleIssue.describe(): MspText = when (this) {
    SubtitleIssue.NoDirectory -> MspText.Res(R.string.msp_player_issue_no_directory)

    SubtitleIssue.DirectoryInvisible -> MspText.Res(R.string.msp_player_issue_directory_invisible)

    is SubtitleIssue.ScanFailed -> MspText.Res(R.string.msp_player_issue_scan_failed, message)

    SubtitleIssue.NoSubtitles -> MspText.Res(R.string.msp_player_issue_no_subtitles)

    SubtitleIssue.NoMatch -> MspText.Res(R.string.msp_player_issue_no_match)

    is SubtitleIssue.LoadFailed ->
        MspText.Res(R.string.msp_player_issue_load_failed, fileName, message)
}

/** 列表项之间的分隔符。语言不同密度也不同（中文用半角点，英文用半角点加空格）。
 *
 * 字幕候选行、内嵌字幕轨的副标题、音轨副标题都用它：三处的信息结构一样
 *（「几个短字段并排」），用同一个分隔符才看起来是一套东西。
 */
internal val SUBTITLE_DETAIL_SEPARATOR: MspText = MspText.Res(R.string.msp_player_detail_sep)

/** 候选行的副标题：格式、语言、以及「是不是和片名吻合」。 */
internal fun SubtitleSource.describeDetails(): MspText = MspText.join(
    SUBTITLE_DETAIL_SEPARATOR,
    buildList {
        add(format.label())
        add(
            languageTag?.let { MspText.Plain(it) }
                ?: MspText.Res(R.string.msp_player_detail_language_unknown),
        )
        // 「双语」跟显示模式里那一条同名同义，直接复用，免得两处译法慢慢分叉。
        if (isBilingual) add(MspText.Res(R.string.msp_player_mode_bilingual))
        if (isForced) add(MspText.Res(R.string.msp_player_detail_forced))
        // 不显示具体分数：数字对用户没有意义，但「会不会被自动选中」有。
        // 门槛和关联规则绑在一起，见 `AUTO_MATCH_SCORE`。
        if (isAutoMatchable) add(MspText.Res(R.string.msp_player_detail_matches_title))
    },
)

/** 一句话概括当前挂着的字幕。 */
internal fun SubtitleSource.describeAttached(): MspText = MspText.join(
    SUBTITLE_DETAIL_SEPARATOR,
    buildList {
        add(format.label())
        languageTag?.let { add(MspText.Plain(it)) }
    },
)

/**
 * 内嵌字幕轨的副标题：格式、语言、「默认/强制」。
 *
 * 不复用 [describeDetails]：那个是给文件用的，会顺带报「与片名吻合」——内嵌轨
 * 没有文件名，这句话在这里是无意义的（它当然属于这部片子）。
 *
 * 也不需要「内嵌」这个词：它就在「片源自带的字幕」标题下面。
 */
internal fun MspTrackInfo.describeDetails(): MspText = MspText.join(
    SUBTITLE_DETAIL_SEPARATOR,
    buildList {
        // 用 subtitleFormat() 而不是 subtitleFormatOf(mimeType)：Media3 给内嵌文本轨
        // 的 mime 是它自己的 cue 包（`application/x-media3-cues`），格式在 `codecs` 里。
        // 直接看 mimeType 的话这一格会永远显示「未知」。
        add(subtitleFormat().label())
        add(
            language?.let { MspText.Plain(it) }
                ?: MspText.Res(R.string.msp_player_detail_language_unknown),
        )
        if (isDefault) add(MspText.Res(R.string.msp_player_detail_default_track))
        if (isForced) add(MspText.Res(R.string.msp_player_detail_forced))
    },
)
