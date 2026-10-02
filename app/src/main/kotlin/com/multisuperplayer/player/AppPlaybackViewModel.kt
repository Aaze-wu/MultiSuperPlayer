package com.multisuperplayer.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.PlaybackController
import com.multisuperplayer.core.player.PlaybackPositionStore
import com.multisuperplayer.core.player.PlaybackSpeedOptions
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
    private val positionStore: PlaybackPositionStore,
) : ViewModel() {

    /**
     * 最近一次读到的设置。
     *
     * 存在的唯一理由是 [startPlayback] 需要一个**同步可读**的当前设置：那个函数
     * 已经被一个 `suspend` 调用（读续播位置）占着，再在里面 `first()` 一次设置
     * 会让「点一下视频」多绕一次磁盘往返。
     *
     * 不需要加锁：`viewModelScope` 跑在主线程上，`PlaybackSettingsRepository` 的
     * `store.data` 收集也在主线程上，两边不可能并发。
     */
    private var latestSettings: PlaybackSettings = PlaybackSettings()

    init {
        // DataStore 的 Flow 会先把**当前值**发一次，所以这里同时也覆盖了
        // 「启动时就已经是开着的」这种情况，不需要额外读一次盘。
        // `setForceSoftwareDecoding` 内部有「值没变就直接返回」的短路，
        // 所以重复下发不会把正在播的东西打断。
        viewModelScope.launch {
            playbackSettings.settings.collect { settings ->
                latestSettings = settings
                controller.setForceSoftwareDecoding(settings.forceSoftwareDecoding == true)
                // null = 用户没设置过 = 默认记住（见 PlaybackSettings.rememberPosition）。
                controller.setRememberPosition(settings.rememberPosition != false)

                // 倍速只在**什么都没在播**的时候下发。
                //
                // 不加这个判断会有一个很隐蔽的 bug：用户在播放页点 2×，那个动作
                // 先把值写进内核、再把值写进设置；设置回写触发的这次收集如果无条件
                // 下发，就会拿一个稍早的快照去覆盖用户刚下的命令——连点 1.5×、2×
                // 时表现为「最后停在 1.5×」，而界面上的高亮是 2×。
                if (controller.currentEntry.value == null) {
                    controller.setSpeed(settings.speed ?: PlaybackSpeedOptions.DEFAULT)
                }
            }
        }
    }

    /**
     * 用 [entries] 作为队列并从 [index] 开始播放。
     *
     * 队列取的是**界面当前筛选后的列表**，不是全库：用户在「视频」标签下点一条，
     * 期望的是接着播下一个视频，而不是播完这个之后突然冒出一首歌。
     *
     * 续播位置是**异步**读的，所以这个方法先挂起一下再真正开始播。这个延迟是
     * 几毫秒（一个小文件的读），而它换来的是「点开就接着上次」这个必须正确的行为——
     * 所以不做成「先从头响、读到位置再跳」：那会让每一次打开都先冒一下片头。
     */
    fun startPlayback(entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        val start = index.coerceIn(entries.indices)
        val entry = entries[start]

        viewModelScope.launch {
            val resumeMs = if (latestSettings.rememberPosition != false) {
                positionStore.read(entry.id)
            } else {
                // 关掉了续播就**不去读**，也不去清：用户关掉这个开关的意思是
                // 「别跳」，不是「把我记住的东西删掉」——他可能只是这一阵子
                // 想看片头，过两天再打开还应该接着上次。
                null
            }

            // 顺序要紧：倍速和「记住位置」都必须在下令加载之前生效，
            // 否则新的一条会先用上一次的倍速放一小段再被改过来。
            controller.setSpeed(latestSettings.speed ?: PlaybackSpeedOptions.DEFAULT)
            controller.setQueue(entries, startIndex = start, resumePositionMs = resumeMs)
        }
    }
}
