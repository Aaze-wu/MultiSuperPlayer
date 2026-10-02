package com.multisuperplayer.core.player

import androidx.media3.common.Player
import com.multisuperplayer.core.model.MediaEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放速度的下界。
 *
 * 放在**接口**这一层而不是 `ExoPlayerController` 里面：倍速的可选档位由界面提供
 * （同目录的 `PlaybackSpeedOptions`），而它必须和内核真正接受的范围一致。
 * 各写一份的话，界面迟早会出现一个点了没反应的档位——那种「按钮像是坏的」的 bug
 * 很难被归因到「两个模块的常量不一样」。
 */
const val MIN_PLAYBACK_SPEED = 0.25f

/** 播放速度的上界，与 [MIN_PLAYBACK_SPEED] 同理。 */
const val MAX_PLAYBACK_SPEED = 4.0f

/**
 * 把任意输入夹到内核真正会用的那个速度。
 *
 * ## 为什么需要它：调用方不该靠「回读状态」问内核
 *
 * [PlaybackController.setSpeed] 只保证「命令被接受了」，它返回时内核走到哪一步
 * 是内核自己的事，`state.playbackSpeed` 未必已经是新值。于是「先 `setSpeed(1.5f)`、
 * 再读 `state.playbackSpeed` 写进设置」会把**别的**值存下去：实测就是界面上显示
 * 1.5×、设置里存了 1.0，重启后回到 1×，而这个症状看上去像「设置没保存」，
 * 实际是保存了一个错的值。
 *
 * 正确做法是：**调用方自己算出内核会用的值**，而把「怎么算」收敛到这个函数——
 * 内核与界面用同一份夹取规则，就不会出现「存下去的值和实际生效的值不一样」。
 * 回读这条路将来只会更差：`ExoPlayerController` 的注释里写明了它计划降级成
 * 「遥控器」（`MediaSessionService` + `MediaController`），那时命令要跨进程。
 *
 * ## 为什么非有限值一律当默认
 *
 * `Float.coerceIn` **不处理 NaN**：NaN 与上下界都比较为 false，于是原样返回，
 * 而 Media3 的 `setPlaybackSpeed(NaN)` 会直接抛异常。也不能把 NaN 当成
 * 「无限慢」夹成 0.25×——它表达的是「这个值没有意义」。所以与
 * [PlaybackSpeedOptions.format]/[PlaybackSpeedOptions.nearestPresetIndex] 保持口径一致：
 * 非有限值（NaN、±Infinity）一律退成 [PlaybackSpeedOptions.DEFAULT]。
 * 三处必须一致，否则会出现「文字写着 1×、存的却是 4×」这种对不上的状态。
 */
fun clampPlaybackSpeed(speed: Float): Float {
    if (!speed.isFinite()) return PlaybackSpeedOptions.DEFAULT
    return speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
}

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
     * @param resumePositionMs 从哪一毫秒接着播（续播）。null = 从头。
     *
     *   这里**同步**收这个值，而不是让内核自己去读盘，是为了消掉一个必现的竞态：
     *   异步读盘通常几十毫秒才回来，而用户完全可能在这几十毫秒里已经拖过进度条，
     *   于是「跳到旧位置」在「用户刚拖到新位置」之后生效，表现为进度条**自己弹回去**。
     *   读盘由调用方（`AppPlaybackViewModel`）在启动播放之前 await 完成。
     */
    fun setQueue(
        entries: List<MediaEntry>,
        startIndex: Int = 0,
        playWhenReady: Boolean = true,
        resumePositionMs: Long? = null,
    )

    /** 播放/暂停切换。已经播到结尾时再次播放会从头开始。 */
    fun togglePlayPause()

    fun pause()

    fun resume()

    /** 跳到指定位置（毫秒），负值会被夹到 0。 */
    fun seekTo(positionMs: Long)

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

    /** 播放速度，会被夹到 0.25×~4×。夹取规则见 [clampPlaybackSpeed]。 */
    fun setSpeed(speed: Float)

    fun setRepeatMode(mode: MspRepeatMode)

    fun setShuffleEnabled(enabled: Boolean)

    /** 音量（0~1）。这是播放器内部音量，不是系统音量。 */
    fun setVolume(volume: Float)

    /**
     * 是否强制改用 FFmpeg 软件解码（默认 false）。
     *
     * 关着的时候内核已经是「系统解码器优先，解不了/解失败了自动换 FFmpeg」——
     * 也就是说打开这个开关**不会让更多文件变得能放**，它解决的是另一类问题：
     * 硬件解码器当场不报错，但画面花屏/变色/音画不同步。这时候只能靠人放弃硬件解码。
     *
     * 本安装包没有 FFmpeg（见 [SoftwareDecoderSupport.available]）时调用它是空操作，
     * 不是错误：禁止用户使用一个不存在的功能，不该以崩溃的形式表达。
     */
    fun setForceSoftwareDecoding(enabled: Boolean)

    /**
     * 按一下「A-B 循环」：未设 → 设 A → 设 B 并开始 → 清空。
     *
     * 只有一个入口、内部读当前位置，而不是暴露 `setAbRepeat(a, b)`：后者要求
     * 调用方自己维护那个三状态的机器，而它只应该有一份（见 [AbRepeatState]）。
     */
    fun cycleAbRepeat()

    /**
     * 是否记住播放进度（默认 true）。关掉时既不读也不写，已有的记录**保留**——
     * 用户重新打开开关后，之前看过的位置还在，这比「关一下全清空」更符合预期。
     */
    fun setRememberPosition(enabled: Boolean)

    /**
     * 是否记录「最近播放」（默认 true）。
     *
     * 和 [setRememberPosition] 是两个开关，不是同一个：那个管「下次从哪开始播」，
     * 这个管「列表里记不记这一条」。四种组合都成立——例如「每次都从头播，但我
     * 想在首页看到最近播过什么」，或者反过来「接着播，但别留播放历史」。
     *
     * 关掉时已有的记录同样**保留**（理由见 [setRememberPosition]）。注意它**不**
     * 阻止续播位置的写入：一条续播位置本身就是一条播放记录，那是 [setRememberPosition]
     * 决定的事；这个开关只管「位置太短、不值得当续播点」那一种记录的写入。
     */
    fun setRecordRecentPlays(enabled: Boolean)

    /** 停止并清空队列（用于「关闭播放器」）。 */
    fun stopAndClear()

    /** 释放内核资源。调用后本对象不可再用。 */
    fun release()
}
