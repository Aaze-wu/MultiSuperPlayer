package com.multisuperplayer.player

import androidx.lifecycle.ViewModel
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.PlaybackController

/**
 * 播放发起器。
 *
 * 存在的唯一理由：Compose 里拿 Koin 依赖的标准入口是 `koinViewModel()`（本项目
 * 用的 `koin-androidx-compose` 只提供这一个入口，没有 `koinInject`），
 * 而「点了列表里的一条 → 把整个列表交给播放器 → 跳到播放页」这个动作
 * 属于导航骨架，不属于媒体库，也不属于播放页。
 *
 * 它**不保存任何状态**：队列、当前条目、播放位置全在
 * [PlaybackController] 里。这样从通知栏或车载面板切歌之后，
 * 播放页显示的仍然是对的。
 */
class AppPlaybackViewModel(
    private val controller: PlaybackController,
) : ViewModel() {

    /**
     * 用 [entries] 作为队列并从 [index] 开始播放。
     *
     * 队列取的是**界面当前筛选后的列表**，不是全库：用户在「视频」标签下点一条，
     * 期望的是接着播下一个视频，而不是播完这个之后突然冒出一首歌。
     */
    fun startPlayback(entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        controller.setQueue(entries, startIndex = index.coerceIn(entries.indices))
    }
}
