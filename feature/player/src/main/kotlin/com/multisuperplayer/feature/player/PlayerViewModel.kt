package com.multisuperplayer.feature.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.multisuperplayer.core.data.artwork.ArtworkColors
import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.EqualizerBandGain
import com.multisuperplayer.core.player.EqualizerController
import com.multisuperplayer.core.player.EqualizerCurve
import com.multisuperplayer.core.player.EqualizerRequest
import com.multisuperplayer.core.player.EqualizerStatus
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.MspRepeatMode
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import com.multisuperplayer.core.player.PlaybackController
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SleepTimerState
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.player.TrackSelectionController
import com.multisuperplayer.core.player.audioTracks
import com.multisuperplayer.core.player.clampPlaybackSpeed
import com.multisuperplayer.core.ui.theme.ArtworkAccent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 播放页 ViewModel。
 *
 * 这一层几乎不做事——**这是刻意的**。所有播放状态都归 [PlaybackController] 所有，
 * 而它是 Koin 单例、生命周期比任何一个页面都长。如果这里再存一份「当前在播什么」，
 * 就会出现两份真相：用户从通知栏切了歌，播放页显示的还是切之前那条。
 *
 * 所以这里只做转发 + 暴露给界面，不持有任何可变状态。
 *
 * 唯一的例外是 [artworkAccent]：它是个**派生**值（从当前封面上算出来的），
 * 不是又存了一份播放状态。
 */
class PlayerViewModel(
    private val controller: PlaybackController,
    /** 片源里的可选轨道。只用到音频那一维（字幕那维归 [SubtitleViewModel]）。 */
    private val tracks: TrackSelectionController,
    artworkPalette: ArtworkPaletteRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    /** 均衡器。它在 `:core:player` 里，生命周期跟着内核而不是这一页。 */
    private val equalizer: EqualizerController,
) : ViewModel() {

    /**
     * 播放偏好（画面比例默认值用得到）。
     *
     * 初值是一个**全是 null** 的设置对象，也就是「什么都没设置过」。
     * 这不是占位符：界面在首帧就按「默认值」画，而磁盘上的值几毫秒后到，
     * 由 [com.multisuperplayer.core.data.settings.PlaybackSettings] 那套
     * 「可空 = 没设置过」的约定保证两者语义一致。
     *
     * `WhileSubscribed` + 5 秒：转屏会让这一页重建，保留 5 秒可以避免
     * 重建时跟着设置一起闪一下（与 [artworkAccent] 同一个理由）。
     */
    val settings: StateFlow<PlaybackSettings> = playbackSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PlaybackSettings())

    /**
     * 均衡器开关。
     *
     * 从 [settings] 派生而不是单独存一份：面板上的开关和磁盘里的值必须是同一本账，
     * 否则会出现「开关是开的、声音没变」（写盘失败）或者反过来的情况，
     * 而用户只能看到开关这一个信息。
     *
     * `== true`：数据层用 null 表示「从没设置过」，而界面上只有开/关两种样子，
     * 默认关这个决定只在这一行。
     */
    val equalizerEnabled: StateFlow<Boolean> = settings
        .map { it.equalizerEnabled == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

    /**
     * 均衡器曲线。读不出来（没设置过、或者存进去的是垃圾）时是平直的。
     *
     * 投影到**标准频段**上（见 `EqualizerCurve.onStandardBands`）：面板上永远是
     * 那五根滑块，下标和曲线下标一一对应。解析放在这里（而不是数据层）是因为
     * 它只能有一处：`EqualizerCurve.decode` 返回 null 表示「读不动」，回落到平直
     * 是这一行的事。
     */
    val equalizerCurve: StateFlow<List<EqualizerBandGain>> = settings
        .map(::currentCurve)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            EqualizerCurve.flat(),
        )

    /**
     * 均衡器现在处于哪种状态：还没开始播放 / 可用 / 这台设备不支持。
     *
     * 三种状态都要能区分，因为它们的界面文案完全不同——把「还没开始播放」
     * 说成「不支持」会让人以为这台手机就是不行（实际上面板开早了）。
     */
    val equalizerStatus: StateFlow<EqualizerStatus> = equalizer.status

    init {
        // 均衡器跟着**内核**走，不跟着这一页走：退出播放页之后音乐还在放，
        // 效果也得还在。所以这里只 start（幂等），**绝不** release——
        // release 会把正在生效的均衡器连根拔掉，而那件事不该由「关闭一个页面」
        // 触发。
        equalizer.start()
        viewModelScope.launch {
            // 以持久化值为唯一真相来下发：开关和曲线在同一个 collector 里一起落，
            // 不会出现「曲线落下去了、开关没落」这种半套状态。
            settings.collect(::applyEqualizer)
        }
    }

    val state: StateFlow<MspPlaybackState> = controller.state
    val currentEntry: StateFlow<MediaEntry?> = controller.currentEntry
    val positionMs: StateFlow<Long> = controller.positionMs
    val bufferedPositionMs: StateFlow<Long> = controller.bufferedPositionMs
    val queue: StateFlow<List<MediaEntry>> = controller.queue
    val currentIndex: StateFlow<Int> = controller.currentIndex

    /**
     * 睡眠定时状态。
     *
     * ## 为什么它在内核里，而不是在这个 ViewModel 里
     *
     * 这一页**会被整个摘掉**：切到「我的」标签、或者从播放条回到列表再进来，
     * `PlayerViewModel` 就没了。定时器如果活在这里，「切个标签回来定时就没了」，
     * 而用户是在睡觉前设的它——那时候根本不会有人看着屏幕。
     *
     * 同理，它也不能只活在这个页面的 Compose 状态里（`PlayerUiState` 那种）：
     * 那连转屏都活不过去。
     */
    val sleepTimer: StateFlow<SleepTimerState> = controller.sleepTimer

    /**
     * 当前片源里的音频轨。没有媒体（或者只有一条）时界面不画音轨入口。
     *
     * 不过滤语言、不过滤默认标记：面板要把**全部**音轨列出来给用户选，
     * 做筛选（比如藏掉评论轨）就是替用户做决定。
     */
    val audioTracks: StateFlow<List<MspTrackInfo>> = tracks.tracks
        .map { list -> list.audioTracks() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * 当前封面的取色结果。null 同时表示「没有封面」、「取不出来」和「算出来没有
     * 可用色相」三种情况——对界面而言它们需要的反应完全一样：回退到用户选的强调色。
     *
     * `distinctUntilChanged` 用的是**封面 uri** 而不是整个 [MediaEntry]：媒体库重新
     * 扫描后 `MediaEntry` 会是新实例（内容一模一样），不去重的话会在每次库刷新时
     * 白跑一次解码 + 量化。
     *
     * `mapLatest` 而不是 `map`：连着切歌时旧的那次提取必须被取消。不然两张封面
     * 几乎同时算完，最后写进去的可能是**上一首**的颜色，而且看不出来。
     *
     * 只有 `mapLatest` 需要显式 opt-in（`flatMapLatest` 同样标注为实验）；这里
     * 确实需要「后到的请求作废先前的」这个语义，所以声明式地接受它，而不是加一
     * 把锁去自己实现一遍。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val artworkAccent: StateFlow<ArtworkAccent?> = controller.currentEntry
        .map { entry -> entry?.artworkUri?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()
        .mapLatest { uri -> artworkPalette.colorsFor(uri)?.toArtworkAccent() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * 底层的 Media3 [Player]，**只**用于交给 `PlayerView` 渲染画面/字幕。
     *
     * 不要在别处调用它的方法：它的所有操作必须发生在创建它的线程上，
     * 而 [PlaybackController] 的成员函数内部会自己切线程。绕过控制器直接调它，
     * 得到的就是那种「偶尔才崩一次」的 `IllegalStateException`。
     */
    val player: Player get() = controller.player

    fun togglePlayPause() = controller.togglePlayPause()

    fun skipToNext() = controller.skipToNext()

    fun skipToPrevious() = controller.skipToPrevious()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    /**
     * 设置播放速度，并**记住**它。
     *
     * 写回设置是有意的：用 1.5 倍听播客的人希望下一集也是 1.5 倍。
     * 两件事必须一起做——先下令给内核（画面立刻变）、再写盘（下次生效）。
     *
     * 存的是 [clampPlaybackSpeed] 算出的**请求值**，而不是写完去回读
     * `state.playbackSpeed`。回读看起来更「以内核为准」，实际是错的：内核命令要
     * 切主线程，回读必然拿到**上一个**速度——实测就是「选 1.5×、界面显示 1.5×、
     * 存进设置的却是 1.0，重启后回到 1×」。夹取规则只有一份（内核和这里共用
     * 同一个函数），所以「请求的值」和「内核真正用的值」一定是同一个。
     *
     * 写盘失败只记日志：设置存不下来是小事，不该把正在播的东西打断
     * （何况 `viewModelScope` 里漏出去的异常会直接崩掉应用）。
     */
    fun setSpeed(speed: Float) {
        val applied = clampPlaybackSpeed(speed)
        controller.setSpeed(applied)
        viewModelScope.launch {
            try {
                playbackSettings.setSpeed(applied)
            } catch (error: Exception) {
                MspLog.w(TAG, error) { "倍速写盘失败，本次会话仍然生效" }
            }
        }
    }

    /**
     * 长按画面的临时加速：`true` = 按下，`false` = 松手。
     *
     * ## 为什么不走 [setSpeed]
     *
     * [setSpeed] 会把速度**写回设置**。长按加速是「按住这两秒」的事，写盘的话
     * 一次手滑就等于把用户的默认倍速永久改成了 2×（而且中途松手时的「恢复原速」
     * 会变成「把默认倍速改成原速」——两个 bug 叠在一起，用户看到的是
     * 「设置里的默认倍速自己变了」）。所以这里只下令给内核，不碰设置。
     *
     * ## 恢复用的是设置里的值，不是「加速前读到的值」
     *
     * 两者在正常情况下一样，但「加速前读一次」会在某个边界上出错：用户按住的同时
     * 另一处把速度改了（比如倍速面板），恢复时就会把一个更旧的值写回去。
     * 设置里的值才是「用户想要的速度」这本账。
     *
     * ## 为什么要一个字段记着「正在加速」
     *
     * 因为「松手」这个事件可能来两次（手势层的兜底 + 离开页面时的 onDispose），
     * 也可能在从未加速过的时候就来一次。没有这个标记的话，一次多余的
     * `setSpeedBoost(false)` 会把用户当前的倍速重设一遍——听起来无害，但用户如果
     * 正好在加速期间用面板改了倍速，这一次重设就会把它抹掉。有标记之后这个调用
     * 是幂等的。
     */
    fun setSpeedBoost(boosting: Boolean) {
        if (boosting == speedBoostActive) return
        speedBoostActive = boosting
        if (boosting) {
            controller.setSpeed(SpeedBoostOptions.normalize(settings.value.boostSpeed))
        } else {
            controller.setSpeed(
                clampPlaybackSpeed(settings.value.speed ?: PlaybackSpeedOptions.DEFAULT),
            )
        }
    }

    /**
     * 本次会话里「我们正主动改成加速倍速」这个事实。
     *
     * 它不是播放状态的副本（那个在内核的 `state` 里），内核也不知道「有根手指
     * 正按着屏幕」——恢复原速这件事只能由发起方自己负责。
     */
    private var speedBoostActive = false

    /** A-B 循环按「空 → 定 A → 定 B → 清空」轮转。 */
    fun cycleAbRepeat() = controller.cycleAbRepeat()
    /** 循环模式按「关 → 单曲 → 列表」轮转。 */
    fun cycleRepeatMode() {
        val next = when (controller.state.value.repeatMode) {
            MspRepeatMode.OFF -> MspRepeatMode.ALL
            MspRepeatMode.ALL -> MspRepeatMode.ONE
            MspRepeatMode.ONE -> MspRepeatMode.OFF
        }
        controller.setRepeatMode(next)
    }

    fun toggleShuffle() = controller.setShuffleEnabled(!controller.state.value.shuffleEnabled)

    /** 手选一条音频轨。只对**当前这条媒体**有效（切条目时内核会清掉覆盖）。 */
    fun selectAudioTrack(track: MspTrackInfo) {
        tracks.selectTrack(MspTrackKind.AUDIO, track.id)
    }

    /** 音频轨回到自动挑选。字幕轨不受影响。 */
    fun useAutomaticAudioTrack() {
        tracks.useAutomaticAudioTrack()
    }

    /**
     * 设一个「再过 N 毫秒暂停」的睡眠定时。传 `null` 等于取消。
     *
     * 档位表（5/10/15/30/45/60/90 分钟）属于界面：见
     * [com.multisuperplayer.core.player.SleepTimerOptions]。这里只认毫秒，
     * 免得把一份可调的展示产物钉进这一层。
     */
    fun setSleepTimer(durationMs: Long?) = controller.setSleepTimer(durationMs)

    /** 设「本集结束就暂停」。 */
    fun setSleepTimerUntilItemEnd() = controller.setSleepTimerUntilItemEnd()

    /** 取消睡眠定时。 */
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    /** 跳到队列里的第 [index] 项并开始播放（越界时内核忽略）。 */
    fun playQueueItem(index: Int) = controller.playQueueItem(index)

    /** 从队列里删掉第 [index] 项（删的是当前项时播放继续往前走一格）。 */
    fun removeQueueItem(index: Int) = controller.removeQueueItem(index)

    /** 把第 [from] 项移到第 [to] 项。 */
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)

    /** 清空队列并停止播放。 */
    fun clearQueue() = controller.clearQueue()

    /**
     * 打开/关闭均衡器。
     *
     * 只写盘，不直接下令给内核：下发这一件事交给 [init] 里那个订阅设置的
     * collector，于是「开关」和「曲线」永远一起落，也就不会出现
     * 「关掉了，但用的是刚刚拖动出来的曲线」这种半套状态。
     */
    fun setEqualizerEnabled(enabled: Boolean) {
        persistEqualizer { playbackSettings.setEqualizerEnabled(enabled) }
    }

    /**
     * 把整条曲线存下来（选预设、或者拖完之后）。
     *
     * 存**整条**曲线而不是「第几段改成了多少」：设备段数不一样，存下标在另一台
     * 设备上就是另一条曲线（见 `EqualizerBandGain`），而这个方法的调用方手里
     * 本来就有整条曲线。
     */
    fun setEqualizerCurve(curve: List<EqualizerBandGain>) {
        if (curve.isEmpty()) return
        persistEqualizer { playbackSettings.setEqualizerBandGains(EqualizerCurve.encode(curve)) }
    }

    /**
     * 拖动某个频段时的**临时**预览：只下发给内核，**不写盘**。
     *
     * ## 为什么拖动的每一帧都要下发
     *
     * 均衡器就是要一边拖一边听。只在下发最后一次（松手）的话，用户是在
     * 「盲调」——五次尝试里四次听不到自己刚做了什么。
     *
     * ## 为什么不写盘
     *
     * 一次拖动会产生几十个中间值，全写进去就是把 DataStore 当记事本用；
     * 真正要存的是**松手时的那一个**（调用方在 `onValueChangeFinished` 里调
     * [setEqualizerCurve]）。
     *
     * ## 为什么可以读 [settings]
     *
     * 拖动期间一次盘都没写，所以 `settings.value` 里的曲线不会过期，
     * 这一层就不需要再存一份「正在拖的曲线」——那正是「两份真相」的开头。
     */
    fun previewEqualizerBand(bandIndex: Int, gainDb: Float) {
        val current = settings.value
        val curve = currentCurve(current)
        if (bandIndex !in curve.indices) return
        equalizer.apply(
            EqualizerRequest(
                enabled = current.equalizerEnabled == true,
                curve = curve.mapIndexed { index, band ->
                    if (index == bandIndex) band.copy(gainDb = gainDb) else band
                },
            ),
        )
    }

    /** 把设置里那一套均衡器状态下发给内核（开关 + 曲线一起）。 */
    private fun applyEqualizer(settings: PlaybackSettings) {
        equalizer.apply(
            EqualizerRequest(
                enabled = settings.equalizerEnabled == true,
                curve = currentCurve(settings),
            ),
        )
    }

    /**
     * 设置里那条曲线，投影到界面画的五个标准频段上。
     *
     * [equalizerCurve]、[previewEqualizerBand] 和 [applyEqualizer] 必须走**同一个**
     * 函数：只要有一处自己解一遍，就会多出一份「界面上的第 3 根是哪一段」的答案，
     * 而两个答案一旦不一致，症状就是「拖了滑块，变的是另一段」——它不会报错。
     */
    private fun currentCurve(settings: PlaybackSettings): List<EqualizerBandGain> =
        EqualizerCurve.onStandardBands(
            EqualizerCurve.decode(settings.equalizerBandGains) ?: EqualizerCurve.flat(),
        )

    /**
     * 写均衡器设置。
     *
     * 写盘失败只记日志，和 [setSpeed] 一样：设置存不下来是小事，不该把正在播的
     * 东西打断（何况 `viewModelScope` 里漏出去的异常会直接崩掉应用）。
     */
    private fun persistEqualizer(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                MspLog.w(TAG, error) { "均衡器设置写盘失败，本次会话仍然生效" }
            }
        }
    }

    private companion object {
        const val TAG = "PlayerViewModel"

        /**
         * 停止订阅后继续保留上次的取色结果 5 秒。
         *
         * 用处是配置变化（旋转、深浅色切换）：播放页会被重建，如果存值是
         * `WhileSubscribed(0)`，主题会先掉回用户强调色再跳回封面色，闪一下。
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/**
 * 数据层的 ARGB `Int` → UI 层的 [Color]。
 *
 * 这四行是「数据层不依赖 Compose」的那个决定要付出的全部代价，放在这里
 * 而不是放到 `:core:ui` 里去：那样 `:core:ui` 就得认识 `:core:data`，
 * 而「界面依赖数据」和「数据依赖界面」是两件事，后者会把分层拆掉。
 */
private fun ArtworkColors.toArtworkAccent(): ArtworkAccent = ArtworkAccent(
    lightPrimary = Color(lightPrimary),
    lightContainer = Color(lightContainer),
    darkPrimary = Color(darkPrimary),
    darkContainer = Color(darkContainer),
)
