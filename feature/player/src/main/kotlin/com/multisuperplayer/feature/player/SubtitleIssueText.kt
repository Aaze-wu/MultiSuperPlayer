package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.isAutoMatchable
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.player.EmbeddedPreReadState
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
    // `displayName` 是 `DVB Subtitle`（单数、且是内部叫法），英文里读起来不像一项格式名，
    // 所以这一条也走资源。PGS / VobSub 那两个名字是业界通用的写法，直接用 `displayName`。
    SubtitleFormat.DVB -> MspText.Res(R.string.msp_player_format_dvb)
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

/** 候选行的副标题：来源、格式、语言、以及「是不是和片名吻合」。 */
internal fun SubtitleSource.describeDetails(): MspText {
    // 生成的字幕有两个格子必须跳过，理由在下面两处注释里：
    // 它们描述的是「外来文件怎么和片名对上」，而这条文件是我们自己刚写出来的。
    val generated = origin == SubtitleOrigin.GENERATED_ASR

    return MspText.join(
        SUBTITLE_DETAIL_SEPARATOR,
        buildList {
            // 来源放在最前：它比格式更影响用户能拿它做什么（比如它可以被重新生成）。
            if (generated) add(MspText.Res(R.string.msp_player_detail_generated))
            add(format.label())
            // 生成的字幕不带语言标签（识别时没有做语种判定）。显示「语言未知」
            // 是在报一个我们自己没打算知道的字段——用户刚按下那个按钮，他知道这是什么语言。
            if (!generated) {
                add(
                    languageTag?.let { MspText.Plain(it) }
                        ?: MspText.Res(R.string.msp_player_detail_language_unknown),
                )
            }
            // 「双语」跟显示模式里那一条同名同义，直接复用，免得两处译法慢慢分叉。
            if (isBilingual) add(MspText.Res(R.string.msp_player_mode_bilingual))
            if (isForced) add(MspText.Res(R.string.msp_player_detail_forced))
            // 不显示具体分数：数字对用户没有意义，但「会不会被自动选中」有。
            // 门槛和关联规则绑在一起，见 `AUTO_MATCH_SCORE`。
            //
            // 生成的字幕不报这一格：它的文件名本来就是根据片名拼的，
            // 「与片名吻合」在这里是句废话。
            if (isAutoMatchable && !generated) add(MspText.Res(R.string.msp_player_detail_matches_title))
        },
    )
}

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

/**
 * 内嵌轨在标题位上写什么（`内嵌字幕 1` / 它自己的 `label`）。
 *
 * ## 为什么不直接用 [MspTrackInfo.displayLabel]
 *
 * `displayLabel` 的兜底顺序是 `label ?: language ?: 兜底文案`，而设备上实测
 * （`embedded-test.mkv`、一条 `chi` 轨）`label` 是 null、`language` 是 `zh`，
 * 于是标题位写出来是 **`zh`**——下面那行小字里本来就有一模一样的 `zh`
 * （格式 · **语言** · 默认…），屏幕上就成了：
 *
 * ```
 * zh
 * SubRip · zh · 默认 · 尚未读到台词
 * ```
 *
 * 标题位重复小字里的语言、而且给的是**语言标签**这种代码：用户看到的是
 * 「这个界面不知道这条轨叫什么」。语言已经由小字那行负责了，标题位该说的是
 * 「这是第几条内嵌轨」——那正是用户点了哪一条的依据。
 *
 * 轨**自带** label 的时候（有些 mkv 会写「简体中文」）当然用它的：那是别人给这条轨
 * 起的名字，比序号有用。
 */
internal fun MspTrackInfo.embeddedTitle(): MspText =
    label?.takeIf { it.isNotBlank() }?.let { MspText.Plain(it) }
        ?: MspText.Res(R.string.msp_player_embedded_track, indexInGroup + 1)

/**
 * 「当前挂着哪条」那一格下面那行小字：格式 / 语言 / 默认…，再加一句状态说明。
 *
 * 内嵌轨是**边播边读**的：轨很快就认下来了（容器一解析出轨道清单就认），而第一句
 * 台词要等播放头走到有字幕的地方才到。这一段窗口里面板上如果只写着「内嵌字幕 1」
 * 加一行格式，看起来就跟一份**空字幕**一样，用户会以为切过去没生效、或者字幕坏了。
 *
 * `cueCount == 0` 时补的那句话必须是**进行时**（「还没读到」）而不是断言
 * （「没有台词」）：Media3 要播完才知道总数，我们现在确实不知道。
 *
 * ## 为什么还要看 [preRead]
 *
 * 「还没读到台词」在两种情形下说的是不同的事，而用户的动作完全不同：
 * 预读**正在跑**时他应该等，预读**失败**时他应该去检查字幕（而且在预读成功之前，
 * 字幕速率是按流式表换算的，调快的方向本来就不生效）。所以预读有结论时
 * （`Reading` / `Failed`）用它替换掉那句「尚未读到台词」——它比后者精确得多。
 * `Ready` / `Off` 时行为与改动前完全一致。
 *
 * 抽成纯函数是为了能断言——这句话只在真的**一行都没有**时出现，一有台词就必须消失。
 *
 * ## 位图轨为什么不能走上面那三个分支
 *
 * PGS / VobSub / DVB 轨不预读（预读的门槛是 `isTextRenderable()`），也不会累积
 * cue——内核在位图这条路上给的是「此刻该显示什么」，放完就空。于是 `cueCount`
 * 恒为 0、`preRead` 恒为 `Off`，上面那个 `when` 会选到「尚未读到台词」：
 * **屏幕上明明有字幕，面板却说一行都没读到**，而且这句话永远不会消失。
 *
 * 位图轨在这一格该说的是它自己的事：「字幕时间轴 / 速率对它不生效」。这两根滑块
 * 就在同一个面板下面，用户真的会去拖它们，而拖了不会有任何变化。
 */
internal fun embeddedStatusDetails(
    track: MspTrackInfo,
    cueCount: Int,
    preRead: EmbeddedPreReadState = EmbeddedPreReadState.Off,
): MspText {
    val note: MspText? = if (track.isBitmapRenderable()) {
        MspText.Res(R.string.msp_player_embedded_bitmap_note)
    } else {
        when (preRead) {
            EmbeddedPreReadState.Reading -> MspText.Res(R.string.msp_player_embedded_prereading)
            // 失败时不管已经读到几行都说：速率要按**整表**换算才准确，
            // 而「预读失败」是用户唯一能看出「为什么调了没反应」的线索。
            is EmbeddedPreReadState.Failed -> MspText.Res(R.string.msp_player_embedded_preread_failed)
            EmbeddedPreReadState.Off, is EmbeddedPreReadState.Ready ->
                if (cueCount > 0) null else MspText.Res(R.string.msp_player_embedded_lines_pending)
        }
    }
    return if (note == null) {
        track.describeDetails()
    } else {
        MspText.join(
            SUBTITLE_DETAIL_SEPARATOR,
            listOf(track.describeDetails(), note),
        )
    }
}
