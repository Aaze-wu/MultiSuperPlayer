package com.multisuperplayer.core.player

/**
 * 播放状态的**只读快照**，供 UI 渲染。
 *
 * 为什么不让 UI 直接读 `ExoPlayer`：
 * - `ExoPlayer` 只能在创建它的线程上访问，UI 直接读会要求 UI 层懂 Media3 的线程规则；
 * - Media3 的 `Player` 回调是逐项变化的，UI 想「一次拿到全部相关状态」很难；
 * - `core:player` 之外的东西都依赖 Media3，换内核（比如以后加 VLC/FFmpeg 直通）时改动面巨大。
 *
 * 所以边界定在：内核 → `MspPlaybackState` → UI，UI 只认这个 data class。
 *
 * ## 为什么这里**没有**播放位置
 *
 * 位置每秒要刷新 5 次，而 `isPlaying`／`durationMs` 这些只在事件发生时变。
 * 混在同一个 data class 里，等于让整块 UI（视频层、歌词层、控制条）每 200ms
 * 重组一次——播放页上最贵的往往是歌词列表。所以位置单独放在
 * `PlaybackController.positionMs` 这个流里，只有真正需要它的「时钟」组件去订阅。
 *
 * 代价是组合进度时要同时读两个来源，这比「同一份状态有两个来源」安全得多。
 *
 * 位置/时长刻意用毫秒而不是 `Duration`：Media3 在「还没准备好」时返回
 * `C.TIME_UNSET`（一个很负的哨兵值），直接透传会让格式化代码算出天文数字。
 * 这里统一收敛成 `0`。
 */
data class MspPlaybackState(
    /** 当前媒体条目的 `MediaEntry.id`，没有媒体时为 null。 */
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasEnded: Boolean = false,
    /** 总时长；`0` 表示未知（直播流、元数据还没解析出来）。 */
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val repeatMode: MspRepeatMode = MspRepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val volume: Float = 1f,
    /**
     * 当前**实际在用**的解码器类型，见 [MspDecoderKind]。
     *
     * 这不是一个装饰性字段。软件解码比硬件解码显著更耗电，
     * 「这个文件一放就发烫/掉帧」是一个真实的用户疑问，而答案就在这个字段里——
     * 没它的话，「是不是走了软解」只能靠猜。
     */
    val decoderKind: MspDecoderKind = MspDecoderKind.UNKNOWN,
    /** 最近一次错误的人类可读描述；成功播放后会被清空。 */
    val errorMessage: String? = null,
) {
    val hasMedia: Boolean get() = mediaId != null

    /** 时长未知时进度条不应显示成一个 0 长度的条，UI 需要据此换一种呈现。 */
    val hasKnownDuration: Boolean get() = durationMs > 0L
}

/** 与 Media3 的 `Player.REPEAT_MODE_*` 一一对应，但不去引用它（见 [MspPlaybackState] 的说明）。 */
enum class MspRepeatMode {
    OFF,
    ONE,
    ALL,
}

/**
 * 实际在用的解码器类型。
 *
 * ⚠️ 这里刻意把软件解码拆成两个值，而不是一个笼统的 `SOFTWARE`：软件解码有两个来源
 * ——系统自带的软件解码器（MediaCodec 里的非硬件实现）和内置 FFmpeg，两者的
 * 兼容性与耗电都不一样。合并成一个值后，界面就无法回答「到底有没有走上 FFmpeg」
 * 这个排查时最要紧的问题。
 */
enum class MspDecoderKind {
    /** SoC 里的专用解码器。最省电，也最不兼容。 */
    HARDWARE,

    /** 系统自带的软件解码器（MediaCodec 的非硬件实现）。 */
    SYSTEM_SOFTWARE,

    /** 内置的 FFmpeg 软件解码器。能解的音视频编码最全，但费 CPU。 */
    FFMPEG,

    /** 还没开始解码，或者本安装包读不到解码器（例如缺对应 CPU 架构的原生库）。 */
    UNKNOWN,
}

/**
 * 计算进度。
 *
 * 放在这里而不是 [MspPlaybackState] 的成员属性，是因为位置不在状态里，
 * 进度永远是「位置 × 状态」两个来源组合出来的结果。
 */
fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
