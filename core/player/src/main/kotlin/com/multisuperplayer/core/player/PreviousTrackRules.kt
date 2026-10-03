package com.multisuperplayer.core.player

/**
 * 按下「上一首」时内核该执行哪一条命令。
 *
 * ## 为什么这里要有第二条规则
 *
 * Media3 的 `seekToPrevious()` 不是一条「切上一条」的命令，它是**两条**命令的二选一。
 * 从字节码读出来的原始判断（`javap -c androidx.media3.common.BasePlayer`）是：
 *
 * ```
 * if (hasPreviousMediaItem() && getCurrentPosition() <= getMaxSeekToPreviousPosition()) {
 *     切到上一条
 * } else {
 *     回到本条目开头（position = 0）        // seekToCurrentItem(0L, …)
 * }
 * ```
 *
 * `getMaxSeekToPreviousPosition()` 默认是 **3000 ms**（`ExoPlayer.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS`）。
 * 于是「播过 3 秒之后按上一首」走的是 else 分支——**把当前这一条重头播**，
 * 要按第二下（那时位置已经接近 0，才落回第一个分支）才真的切到上一条。
 *
 * 对「上一首」这个按钮来说这是个很糟的默认：用户按了它，时间码归零、画面从头开始，
 * 而队列里明明还有上一条。它不崩、不报错、日志里一个字都没有——
 * 只是**一直表现得像按钮坏了一半**（实机上报的原话：『在音视频播放一段时间后，
 * 点击上一首，会先回到当前音视频首，再点一次才能切到上一个』）。
 *
 * 所以这里改成 `PREVIOUS_ITEM` 对应的 `seekToPreviousMediaItem()`：
 * 那个方法**不做位置判断**，直接走上一条（`seekToPreviousMediaItemInternal`
 * 里只有「上一条下标是多少」这一件事）。
 *
 * ## 为什么「没有上一条」要退化成「重播」
 *
 * `seekToPreviousMediaItem()` 在 `hasPreviousMediaItem() == false` 时是**空操作**
 * （`seekToPreviousMediaItemInternal` 的第一句就是下标为 `INDEX_UNSET` 时直接
 * `ignoreSeek()`）。队列里只有一条、或者停在第一条且没开列表循环时，
 * 按钮就会变成**按下去什么都不发生**——比原来那个「按两下才切走」更难理解，
 * 因为屏幕上连一点反馈都没有。
 *
 * 所以那一种情况仍然回到本条目开头。这不是我们发明的补丁：Media3 自己的
 * `seekToPrevious()` 在「没有上一条」时也走同一个 else 分支做同一件事，
 * 而 `seekTo(0L)` 与它是同一个内部调用（`seekTo(long)` 就是
 * `seekToCurrentItem(positionMs, SEEK_REASON_SEEK_ADJUSTMENT)`）。
 *
 * ## 两个入口，一个决定
 *
 * 「上一首」这个动作有两条完全不同的路，而两条路上读到的东西也不一样：
 *
 * 1. **应用内**（播放页控制条、迷你播放器）走 `ExoPlayerController.skipToPrevious`，
 *    它直接用 [actionFor] 的结果挑命令；
 * 2. **通知栏 / 锁屏 / 耳机线上那个「上一首」** 是 Media3 的 `MediaSession` 收下
 *    `COMMAND_SEEK_TO_PREVIOUS` 之后**直接调内核的 `seekToPrevious()`**
 *    （`PlayerWrapper` 只是个 `ForwardingPlayer`，一路转发到我们的 `ExoPlayer`）——
 *    那条路上没有一行我们的代码，也没有任何方法可以让我们插进去改。
 *
 * 第二条路只剩一个可用的旋钮：`ExoPlayer.setMaxSeekToPreviousPositionMs()`。
 * 它设成 `Long.MAX_VALUE` 之后，内核自己的 `seekToPrevious()` 也永远走
 * 「切上一条」那一支（见 `ExoPlayerController` 里那段注释），于是两条路给出同一个结果。
 *
 * 两处必须同时成立：只改一处的症状是「应用里按一下就切走了、通知栏按两下才切走」，
 * 而这种不一致**没有任何单测能发现**（那条路要真机 + 真 `MediaSession` 才跑得起来，
 * 这也是为什么决定本身要写在同一份文档里）。
 *
 * ## 为什么这个判断可以交给 `hasPreviousMediaItem()`
 *
 * `hasPreviousMediaItem()` 与 `seekToPreviousMediaItem()` 读的是**同一个**
 * `getPreviousMediaItemIndex()`（前者是它 `!= INDEX_UNSET`，后者是它对
 * `INDEX_UNSET` 直接放弃），所以两边不可能给出不一致的答案。那个下标由
 * `Timeline.getPreviousWindowIndex(index, repeatMode, shuffleEnabled)` 算出来
 * ⇒ 列表循环会绕到最后一条、单曲循环会返回**同一个**下标（内核于是重播这一条，
 * 这本来就是单曲循环该有的表现）、随机播放按随机序取上一条——这三件事都归内核管，
 * 我们这一层不需要自己算，也不该自己算。
 *
 * ## 为什么只能靠抽出来的纯函数来钉
 *
 * 真正的调用点在 [ExoPlayerController]，那里手上只有真 ExoPlayer：纯 JVM 单测
 * 跑不起 Android 内核，而这个缺陷的表现又恰好是「什么都不发生」——
 * 出错时不会有异常、不会有日志、不会有画面上任何变化，只能靠规则本身被钉住。
 */
internal object PreviousTrackRules {

    /** 按下「上一首」时该发哪一条命令。 */
    enum class Action {
        /** 切到上一条（列表循环 / 随机的绕回由内核决定）。 */
        PREVIOUS_ITEM,

        /** 队列里没有上一条：回到本条目开头。 */
        RESTART_CURRENT,
    }

    /**
     * @param hasPreviousItem 内核的 `player.hasPreviousMediaItem()`。
     */
    fun actionFor(hasPreviousItem: Boolean): Action =
        if (hasPreviousItem) Action.PREVIOUS_ITEM else Action.RESTART_CURRENT
}
