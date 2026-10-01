package com.multisuperplayer.feature.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.multisuperplayer.core.data.artwork.ArtworkColors
import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.MspRepeatMode
import com.multisuperplayer.core.player.PlaybackController
import com.multisuperplayer.core.ui.theme.ArtworkAccent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

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
    artworkPalette: ArtworkPaletteRepository,
) : ViewModel() {

    val state: StateFlow<MspPlaybackState> = controller.state
    val currentEntry: StateFlow<MediaEntry?> = controller.currentEntry
    val positionMs: StateFlow<Long> = controller.positionMs
    val bufferedPositionMs: StateFlow<Long> = controller.bufferedPositionMs
    val queue: StateFlow<List<MediaEntry>> = controller.queue
    val currentIndex: StateFlow<Int> = controller.currentIndex

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

    /** 快进/快退 10 秒。 */
    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

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

    private companion object {
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
