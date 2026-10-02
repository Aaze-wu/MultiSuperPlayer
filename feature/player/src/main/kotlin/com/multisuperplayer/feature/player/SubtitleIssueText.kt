package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.isAutoMatchable

/**
 * 字幕相关的界面文案。
 *
 * 集中在一个文件里，是因为它们是**同一批**要跟着语言走的东西。等做多语言时
 * 整个文件搬进 `strings.xml` 就行，不会散落在十几个组合函数里。
 *
 * 现在整个 `feature:player` 的界面文案都是内联中文（`PlayerScreen.kt` 也是），
 * 所以这里保持一致，而不是只在字幕这一块引入资源引用。
 */

/** 显示模式的名字。 */
internal fun SubtitleDisplayMode.label(): String = when (this) {
    SubtitleDisplayMode.OFF -> "隐藏"
    SubtitleDisplayMode.ORIGINAL_ONLY -> "仅原文"
    SubtitleDisplayMode.TRANSLATION_ONLY -> "仅译文"
    SubtitleDisplayMode.BILINGUAL -> "双语"
}

/**
 * 把加载过程中的问题说成一句用户看得懂的话。
 *
 * `when` 穷尽所有分支，所以新增一种失败时编译器会提醒补文案——
 * 这正是**不能**用一个通用的「加载失败」兜底的地方：每种失败的下一步操作
 * 都不一样（去授权限 / 换个文件夹 / 重命名字幕），一句话兜住等于什么都没说。
 */
internal fun SubtitleIssue.describe(): String = when (this) {
    SubtitleIssue.NoDirectory ->
        "无法确定这个文件所在的文件夹，没法自动找同名字幕。"

    SubtitleIssue.DirectoryInvisible ->
        "读不到这个文件所在的文件夹，可能是没有存储权限。"

    is SubtitleIssue.ScanFailed ->
        "查找字幕时出错：$message"

    SubtitleIssue.NoSubtitles ->
        "这个文件夹里没有字幕文件。"

    SubtitleIssue.NoMatch ->
        "文件夹里有字幕，但文件名和这条片子对不上。可以在下面手动选一个。"

    is SubtitleIssue.LoadFailed ->
        "「$fileName」读取失败：$message"
}

/** 候选行的副标题：格式、语言、以及「是不是和片名吻合」。 */
internal fun SubtitleSource.describeDetails(): String = buildList {
    add(format.displayName)
    add(languageTag ?: "未标语言")
    if (isBilingual) add("双语")
    if (isForced) add("强制")
    // 不显示具体分数：数字对用户没有意义，但「会不会被自动选中」有。
    // 门槛和关联规则绑在一起，见 `AUTO_MATCH_SCORE`。
    if (isAutoMatchable) add("与片名吻合")
}.joinToString(" · ")

/** 一句话概括当前挂着的字幕。 */
internal fun SubtitleSource.describeAttached(): String = buildList {
    add(format.displayName)
    languageTag?.let { add(it) }
}.joinToString(" · ")
