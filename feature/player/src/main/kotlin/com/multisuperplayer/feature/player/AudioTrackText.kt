package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.selectedAudioTrack

/**
 * 音轨相关的界面文案。
 *
 * 和 `SubtitleIssueText.kt` 分开，是因为那个文件里的每个函数都挂在字幕的那一套
 * 概念上（格式、强制、与片名吻合），而音轨副标题要说的东西交集只有「语言」
 * 一个字段。硬塞进一个文件只会让两边都被对方的下一个字段带着长歪。
 */

/**
 * 音轨的副标题：语言、「N 声道」、「默认」、编解码。
 *
 * ## 为什么声道数是必须的
 *
 * 容器里的标签常常只写语言（`国语`、`zh`），而同一个语言下**真的会有多条**轨：
 * 普通话 5.1 与国语 2.0、原声与配音。只显示语言的话，列表里会出现两行一模一样
 * 的文字，用户点哪一行都是猜。声道数是这两个之间唯一写在容器里、
 * 又一眼看得懂的差别。
 *
 * 编解码（`ac-3` / `eac3` / `dts`）放在最后：它是给在意环绕格式的人看的，
 * 对别人是无害的尾巴。认不出来时**留空**，不要写「未知」——那会把一行里
 * 最有用的语言/声道信息淹掉。
 */
internal fun MspTrackInfo.describeAudioDetails(): MspText = MspText.join(
    SUBTITLE_DETAIL_SEPARATOR,
    buildList {
        add(
            language?.let { MspText.Plain(it) }
                ?: MspText.Res(R.string.msp_player_detail_language_unknown),
        )
        channelCount?.let { add(MspText.Res(R.string.msp_player_channels, it)) }
        if (isDefault) add(MspText.Res(R.string.msp_player_detail_default_track))
        codec?.takeIf { it.isNotBlank() }?.let { add(MspText.Plain(it)) }
    },
)

/**
 * 控制条上音轨按钮的字。
 *
 * 三条规则，合起来是一句话：「现在**听的是谁**」。
 * 1. 内核明确选中了一条 → 那条轨的名字（和面板里的主标题**同一个**字符串，
 *    否则按钮上写「国语」、面板里写「zh」，用户会以为是两条轨）；
 * 2. 一条都没选、或者多条同时选中（同一个语言的多个码率在自适应）→ 「自动」；
 * 3. 调用方只在**多于一条**音轨时才问这个问题（见 `PlayerRoute`）。
 */
internal fun audioTrackChipLabel(
    tracks: List<MspTrackInfo>,
    fallbackOf: (MspTrackInfo) -> String,
    autoLabel: String,
): String {
    val selected = tracks.selectedAudioTrack() ?: return autoLabel
    return selected.displayLabel(fallbackOf(selected))
}
