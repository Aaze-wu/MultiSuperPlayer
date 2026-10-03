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
interface PlaybackController : TrackSelectionController {

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
     * 睡眠定时当前的状态。
     *
     * ## 为什么不并进 [state]
     *
     * [SleepTimerState.Countdown] 的**剩余时间**是连续变化的，而 [state] 的语义是
     * 「离散事实，只在事件发生时变」。并进去之后，倒计时显示要每秒刷新，
     * 于是**所有**订阅 [state] 的组件（进度条、控制条、歌词行……）每秒重组一次。
     * 这里单独一条流：只有画倒计时的那一行会跟着变。
     *
     * ## 状态本身会不会变
     *
     * 会，但很少：设/取消定时、以及到期停下。所以它既是「离散事实」也是
     * 「连续变化」，最终按**读者**分：读它离散语义的（有没有定时）和读剩余时间的
     * 是同一批人，都只关心倒计时那一行，因此单独一条流是对的。
     *
     * 注意这里存的是**绝对截止时刻**（见 [SleepTimerState]），所以界面自己按
     * 本地时钟算剩余量即可，不需要内核每秒发一次新值。
     */
    val sleepTimer: StateFlow<SleepTimerState>

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

    /**
     * 直接把播放位置跳到队列里的第 [index] 项并开始播放（越界则什么都不做）。
     *
     * ## 为什么不是「拖到第 N 首」用 [skipToNext] 连按 N 次
     *
     * 那会把中间每一首都真的播一下（每个都会触发 `onMediaItemTransition`，
     * 清掉 A-B 循环、手选的音轨/字幕轨，并写一条续播记录）。用户点队列里
     * 第 8 首的意思是「放第 8 首」，不是「路过一下前 7 首」。
     *
     * 越界时**什么都不做**而不是夹到最近的一首：这个下标来自界面（可能已经过期），
     * 点「第 20 首」却开始放第 12 首，用户会以为列表错乱了。
     */
    fun playQueueItem(index: Int)

    /**
     * 从队列里删掉第 [index] 项（越界则什么都不做）。
     *
     * ## 删掉当前项会怎样
     *
     * 播放继续，往前走一格（删的是最后一项时退到前一项）；删空整个队列等于
     * [stopAndClear]。这条语义必须写清楚，因为另一个选择（停下来）也说得通，
     * 而用户点的是「从列表里去掉这一条」，不是「停止播放」。
     *
     * ## 为什么不是「改完队列重新 setQueue 一遍」
     *
     * 那是一次**重开播放会话**：画面上会闪一下、缓冲重来、手选的音轨和字幕
     * 全部回到自动。用户删掉队列里第 5 首不该等于把当前这一集重新开一次。
     * 所以内核走 Media3 的增量接口（`removeMediaItem`）。
     */
    fun removeQueueItem(index: Int)

    /**
     * 把第 [from] 项移到第 [to] 项（语义：先抽出来，再插到 [to]）。
     *
     * 越界或 `from == to` 时什么都不做——拖动时手指经常没真正跨过任何一行。
     * 乱序播放（shuffle）开着时界面应当**不允许**拖拽重排：Media3 维护的乱序
     * 顺序和用户看到的列表顺序是两套下标，拖动会得到用户没预期的结果
     * （内核这边不拦，因为「关掉 shuffle 再排」也是合法的用法）。
     */
    fun moveQueueItem(from: Int, to: Int)

    /**
     * 清空队列并停止播放。
     *
     * 等价于 [stopAndClear]，单独列出来是为了让界面上的「清空队列」不必去理解
     * 「停止播放」和「清空队列」在实现上是同一件事——它们将来未必还是。
     */
    fun clearQueue()

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
     * 语义：**按一次就切上一条**，不做「播放超过几秒先重播当前项」那一套。
     * 那是 `seekToPrevious()` 的默认行为（阈值 3 秒）：播过 3 秒之后按下去，
     * 它执行的是「回到本条目开头」，于是要按第二次（那时位置已经接近 0）
     * 才真的切到上一条。对「上一首」这个按钮来说这很难理解——按下去时间码归零，
     * 而队列里明明还有上一条。
     *
     * 只有队列里**真的没有上一条**时（停在第一条且没开列表循环）才回到本条目开头：
     * 那时候「切上一条」是空操作，按钮会变成「按下去什么都不发生」。
     *
     * 「循环 / 随机的绕回」「单曲循环时就是这一条」都交给内核，规则见
     * [PreviousTrackRules]，**不要在上层自己用位置或下标判断**——否则两个地方
     * 会给出不一致的结果。
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
     * 设一个「再过 [durationMs] 毫秒就暂停」的睡眠定时。
     *
     * 传 null、0 或负数等于 [cancelSleepTimer]：调用方表达「关掉」的方式就是把
     * 时长去掉，而不是额外再判一次该调哪个函数（那种分支迟早会有一边漏掉）。
     *
     * ## 到期是「暂停」而不是「停止」
     *
     * 这两个差别很大：停止会清空队列，用户睡醒后点播放等于从头开始（而且是
     * 另一条会话）；暂停则停在原处，第二天点一下「播放」就接着看。
     * 另外「暂停」不会触发 `onMediaItemTransition`，所以 A-B 循环、手选的
     * 音轨/字幕轨、正在显示的那句字幕全都还在。
     *
     * ## 时长为什么不用枚举/档位类型
     *
     * 档位表属于界面（[SleepTimerOptions] 就是给界面用的），内核只认毫秒。
     * 让内核认识「5 分钟档」会把一个可配置的展示产物钉进协议里。
     */
    fun setSleepTimer(durationMs: Long?)

    /**
     * 设一个「当前这一集放完就暂停」的定时。
     *
     * 单独一个入口而不是用 `setSleepTimer(片长 - 当前位置)`：片长可能还没解析出来
     * （未知时长、直播流），而且用户拖动进度条之后那个算出来的时长就错了。
     * 这一档等的是一个**播放事件**，只有在事件发生的地方才判得准。
     *
     * 已经在倒计时中时调用它会**替换**掉倒计时（两个定时只能有一个生效，
     * 同时挂着两个的话「哪个先到」这种问题没有用户能理解的答案）。
     */
    fun setSleepTimerUntilItemEnd()

    /** 取消睡眠定时（没有定时时是空操作）。 */
    fun cancelSleepTimer()

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
