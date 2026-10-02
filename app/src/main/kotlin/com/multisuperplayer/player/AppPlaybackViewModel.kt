package com.multisuperplayer.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.PlaybackController
import kotlinx.coroutines.launch

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
 *
 * 它还负责一件看起来不属于导航的事：**把持久化的播放偏好喂给内核**。
 * 放在这里的理由是生命周期，不是职责划分——这个 ViewModel 挂在 Activity 上，
 * 整个应用的存活期都在；而播放页在切标签时会被**整个摘掉**（见 `MspApp` 里的
 * 长注释），用户在设置里拨完开关、切回播放页时，那个 ViewModel 已经重建过一轮了，
 * 靠它去读设置会出现：「开关是开的，但当前这首歌还是用硬件解码播的」。
 */
class AppPlaybackViewModel(
    private val controller: PlaybackController,
    playbackSettings: PlaybackSettingsRepository,
) : ViewModel() {

    init {
        // DataStore 的 Flow 会先把**当前值**发一次，所以这里同时也覆盖了
        // 「启动时就已经是开着的」这种情况，不需要额外读一次盘。
        // `setForceSoftwareDecoding` 内部有「值没变就直接返回」的短路，
        // 所以重复下发不会把正在播的东西打断。
        viewModelScope.launch {
            playbackSettings.settings.collect { settings ->
                controller.setForceSoftwareDecoding(settings.forceSoftwareDecoding == true)
            }
        }
    }

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
