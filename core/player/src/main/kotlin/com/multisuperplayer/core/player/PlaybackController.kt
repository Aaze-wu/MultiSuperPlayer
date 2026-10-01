package com.multisuperplayer.core.player

import androidx.media3.common.Player
import com.multisuperplayer.core.model.MediaEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核的统一门面。
 *
 * UI 层（`:feature:player`、`:feature:library`）只依赖这个接口，不直接碰 Media3。
 * 这样做的直接好处：以后要加「FFmpeg 直通内核」「VLC 兼容内核」时，
 * UI 一行都不用改。
 *
 * ## 线程约定
 *
 * 所有成员函数都可以从任意线程调用，内部会切到主线程执行——`ExoPlayer` 要求
 * 所有操作发生在创建它的线程上，把这个约束泄漏给 UI 是 bug 的温床。
 * 所有 [StateFlow] 的**值**都在主线程更新，但订阅者可以在任何线程读。
 *
 * ## 状态分流的理由
 *
 * - [state]：离散事实（在播/缓冲/时长/模式/错误），只在事件发生时变；
 * - [positionMs] / [bufferedPositionMs]：连续变化，每 200ms 一次。
 *
 * 分开是因为进度条和歌词高亮需要高频位置，而它们只是播放页的一小块。
 * 若把位置塞进 [state]，每次 tick 都会让订阅 [state] 的所有组件重组。
 */
interface PlaybackController {

    /** 离散播放状态。**不含播放位置**，理由见类注释。 */
    val state: StateFlow<MspPlaybackState>

    /** 当前播放队列。 */
    val queue: StateFlow<List<MediaEntry>>

    /** 队列中的当前下标；队列为空时为 0。 */
    val currentIndex: StateFlow<Int>

    /** 当前正在播放的条目；没有媒体时为 null。 */
    val currentEntry: StateFlow<MediaEntry?>

    /** 当前播放位置（毫秒），播放中每 200ms 更新一次。 */
    val positionMs: StateFlow<Long>

    /** 已缓冲到的位置（毫秒），更新频率低于 [positionMs]。 */
    val bufferedPositionMs: StateFlow<Long>

    /**
     * 底层 Media3 `Player`，**只用于把它挂到 `PlayerView`/`SurfaceView` 上**（视频画面）。
     *
     * 其他一切状态都请读 [state]：直接读 `player.isPlaying` 不会触发重组，
     * 而且绕过了统一门面，等于把线程约束又泄漏回去了。
     */
    val player: Player

    /**
     * 设置播放队列。
     *
     * @param startIndex 从哪一项开始播，越界会被夹到有效范围。
     * @param playWhenReady 设置完是否立即播放。
     */
    fun setQueue(
        entries: List<MediaEntry>,
        startIndex: Int = 0,
        playWhenReady: Boolean = true,
    )

    /** 播放/暂停切换。已经播到结尾时再次播放会从头开始。 */
    fun togglePlayPause()

    fun pause()

    fun resume()

    /** 跳到指定位置（毫秒），负值会被夹到 0。 */
    fun seekTo(positionMs: Long)

    /** 相对当前位置跳转，例如「后退 10 秒」传 -10_000。 */
    fun seekBy(deltaMs: Long)

    /** 下一项（循环模式下会绕回）。 */
    fun skipToNext()

    /**
     * 上一项。
     *
     * 注意语义：按行业惯例，播放超过 3 秒后按「上一首」是**重播当前项**，
     * 只有刚开头按才是真的切到上一项。这个判断交给内核（`seekToPrevious()`），
     * 不要在上层自己用位置判断——否则两个地方会给出不一致的结果。
     */
    fun skipToPrevious()

    /** 播放速度，会被夹到 0.25×~4×。 */
    fun setSpeed(speed: Float)

    fun setRepeatMode(mode: MspRepeatMode)

    fun setShuffleEnabled(enabled: Boolean)

    /** 音量（0~1）。这是播放器内部音量，不是系统音量。 */
    fun setVolume(volume: Float)

    /** 停止并清空队列（用于「关闭播放器」）。 */
    fun stopAndClear()

    /** 释放内核资源。调用后本对象不可再用。 */
    fun release()
}
