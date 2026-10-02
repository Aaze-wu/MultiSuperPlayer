package com.multisuperplayer.core.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ExoPlayerController"

/** 位置刷新间隔。200ms 是「歌词高亮够跟手」与「别把主线程刷爆」之间的折中。 */
private const val TICK_INTERVAL_MS = 200L

/**
 * [PlaybackController] 的 Media3 实现。
 *
 * ## 构造时机
 *
 * `ExoPlayer` 必须在**有 Looper 的线程**（实践上就是主线程）上创建，因此这个类
 * 只能从主线程实例化。放在 Koin 里由 `single { }` 懒加载时，第一次注入必然发生在
 * Activity/ViewModel 创建过程中，也就是主线程——但这一点很脆弱，所以这里显式写清楚。
 *
 * ## 与 MediaSessionService 的关系
 *
 * 目前内核由本类直接持有，生命周期跟随进程。接入 `MediaSessionService`
 * （后台播放 + 通知栏 + 车机/蓝牙控制）之后，`ExoPlayer` 会**移交给 Service**，
 * 本类降级成「遥控器」：拿 `MediaController` 转发命令、把 `Player.Listener`
 * 的回调映射成同样的 StateFlow。接口故意设计成可以这样替换。
 */
class ExoPlayerController(
    context: Context,
    private val dispatchers: DispatcherProvider,
    /**
     * 软件解码支持探测。默认值是「没有」而不是 null：拿不到原生库是一种
     * **正常状态**（比如装到了未打包的 CPU 架构上），应该降级成「一台普通的
     * Media3 播放器」，而不是启动就崩。
     */
    private val softwareDecoders: SoftwareDecoderSupport = SoftwareDecoderSupport.Unavailable,
) : PlaybackController {

    private val appContext: Context = context.applicationContext

    /**
     * 整个生命周期里**唯一**的解码器管理器；null 表示「本内核没有 FFmpeg 可用」。
     *
     * 这一个实例必须同时交给两个地方：渲染器工厂（`NextRenderersFactory
     * .setDecoderManager`）和 [attachDecoderManager]。**必须是同一个对象**——
     * `DecoderManager.attach(player)` 会校验「这个 player 就是用我建的」，
     * 拿另一个实例去 attach 会抛
     * `IllegalStateException: Set this DecoderManager on NextRenderersFactory before building the player`。
     *
     * 这个约束编译器看不见、单测也看不见（两个实例长得一模一样），只有真机启动才
     * 暴露出来；而且症状很间接：一条 warn 日志 + 「强制软件解码」静默失效，
     * 界面照常播放。所以这里刻意声明在 [player] **之前**：`player` 的初始化表达式
     * 会读它，而属性初始化严格按声明顺序执行。
     */
    private val decoderManager: DecoderManager? = createDecoderManager()

    /**
     * 已经成功挂到内核上的那个管理器；null 表示没挂上。
     *
     * 和 [decoderManager] 分开，是因为「创建出来」和「挂上去」是两件事：attach 失败
     * 的时候渲染器工厂**仍然**在用着那个管理器（工厂是在 build 之前就拿到手的），
     * 所以把 [decoderManager] 置空只会反过来谎报「本包没有 FFmpeg」。这个字段因此
     * 只代表一件事：**能不能运行期切换解码方式、能不能读到实际用的是哪种解码器**。
     */
    private var attachedDecoderManager: DecoderManager? = null

    /**
     * 用户设置的「强制软件解码」（持久化的那份在 `:core:data`）。
     *
     * 存在这里是为了让自动回退能够**退回**到它：[retryWithSoftwareDecoding] 会把
     * 解码方式临时改成 FFmpeg，而那个临时状态不能一直留着——
     * 否则一条 AC-3 音轨会把后续所有文件都拉成软解，耗电变化用户完全看不见原因。
     */
    private var forceSoftwareDecoding = false

    /**
     * 已经因解码失败自动回退过的那一条媒体。
     *
     * 用 media id 而不是布尔值：绑定成进程级会让「列表里第 3 条是 AC-3、
     * 第 7 条是 DTS」这种情况下后面的文件白白放弃回退机会。
     */
    private var fellBackMediaId: String? = null

    override val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .apply {
            // 永远装上带 FFmpeg 的渲染器工厂（只要这个安装包里有 FFmpeg），
            // 而不是「开关打开时才装」：渲染器工厂只能在 Builder 阶段指定，
            // 要按设置项重建 player 的话，开关一动就丢失当前播放位置。
            //
            // 用的是字段 `decoderManager`，不是再调一次 `createDecoderManager()`：
            // 工厂持有的实例和 [attachDecoderManager] 挂上去的必须是同一个对象。
            decoderManager?.let { manager ->
                val factory = NextRenderersFactory(appContext).setDecoderManager(manager)
                // 显式打开扩展渲染器。`setDecoderManager` 自己也会把优先级提到 ON，
                // 但那是内部实现细节；不写下来的话，「哪天升级 nextlib 后 FFmpeg
                // 悄悄不再被选中」只能靠人肉回放一个 AC-3 文件才发现。
                factory.setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                )
                setRenderersFactory(factory)
            }
        }
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 后台播放网络流时，熄屏后不能因为 CPU 休眠而断流。
            setWakeMode(C.WAKE_MODE_NETWORK)
        }

    /**
     * 内核回调驱动的作用域。用主线程调度器，保证所有 `ExoPlayer` 访问都在同一线程。
     */
    private val scope = CoroutineScope(
        dispatchers.main + SupervisorJob() + CoroutineName("MspPlayer"),
    )

    private val _state = MutableStateFlow(MspPlaybackState())
    override val state: StateFlow<MspPlaybackState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaEntry>>(emptyList())
    override val queue: StateFlow<List<MediaEntry>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentEntry = MutableStateFlow<MediaEntry?>(null)
    override val currentEntry: StateFlow<MediaEntry?> = _currentEntry.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    override val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    /**
     * 错误文案要先记下来再 publish。
     *
     * 原因：`publish()` 每次都从 `player` 现读字段，而 Media3 没有「当前错误」这个
     * 可读属性——错误是通过回调递过来的。不缓存它，publish 就会把错误刷成 null，
     * UI 上的错误提示会一闪而过。
     */
    private var pendingErrorMessage: String? = null

    /** 避免重复 `startService`：每调一次都会投递一次 onStartCommand。 */
    private var serviceRunning = false

    init {
        // 必须在 addListener 之后：`attach` 会立刻 apply 一次解码器选择，
        // 那时若监听器已就位，随后的 publish 会把真实解码方式直接写上界面。
        player.addListener(
            object : Player.Listener {
                /**
                 * 只挂 `onEvents` 一处刷新。
                 *
                 * Media3 保证「状态批次变化」时最后调用它，覆盖了
                 * onIsPlayingChanged / onRepeatModeChanged / onVolumeChanged …
                 * 全部回调。逐个 override 一遍的写法很容易漏掉新加的字段，
                 * 症状是「改了音量但 UI 不动」。
                 */
                override fun onEvents(player: Player, events: Player.Events) {
                    publish()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // 真正开始播放（或恢复）就清掉上一次的错误提示。
                    // 这里只改缓存，实际写进 Flow 由紧随其后的 onEvents 完成。
                    if (playbackState == Player.STATE_READY) pendingErrorMessage = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 先试自动回退。回退成功就逄回，不设错误文案——
                    // 用户看到的是「黑一下接着放」，而不是一条错误提示。
                    if (retryWithSoftwareDecoding(error)) return

                    pendingErrorMessage = PlaybackErrorMapper.describe(
                        errorCode = error.errorCode,
                        causeName = error.cause?.let { it::class.java.simpleName },
                        softwareDecoding = softwareAttemptForCurrentMedia(),
                    )
                    MspLog.e(TAG, error) { "播放失败：$pendingErrorMessage" }
                    publish()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    MspLog.d(TAG) { "切换到条目 ${mediaItem?.mediaId}（原因 $reason）" }
                    publish()
                }
            },
        )

        scope.launch {
            while (isActive) {
                // 只在播放中刷新位置：暂停时位置不会变，白刷就是白耗电。
                if (player.isPlaying) refreshPosition()
                delay(TICK_INTERVAL_MS)
            }
        }

        attachDecoderManager()
    }

    // ---------------------------------------------------------------- 内部状态同步

    /**
     * 把内核当前状态抄进 Flow。只在主线程调用。
     */
    private fun publish() {
        _state.value = MspPlaybackState(
            mediaId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            hasEnded = player.playbackState == Player.STATE_ENDED,
            // C.TIME_UNSET 与负数都表示「未知」，统一收敛成 0，
            // 否则下游的时长格式化会算出天文数字。
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            playbackSpeed = player.playbackParameters.speed,
            repeatMode = player.repeatMode.toMsp(),
            shuffleEnabled = player.shuffleModeEnabled,
            volume = player.volume,
            decoderKind = currentDecoderKind(),
            errorMessage = pendingErrorMessage,
        )
        syncCurrentEntry()
        refreshPosition()
    }

    private fun syncCurrentEntry() {
        val index = player.currentMediaItemIndex
        if (index >= 0 && index != _currentIndex.value) _currentIndex.value = index
        val entry = _queue.value.getOrNull(index)
        if (entry != _currentEntry.value) _currentEntry.value = entry
    }

    private fun refreshPosition() {
        val position = player.currentPosition.coerceAtLeast(0L)
        if (position != _positionMs.value) _positionMs.value = position
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        if (buffered != _bufferedPositionMs.value) _bufferedPositionMs.value = buffered
    }

    /**
     * 从内核现读「实际在用哪种解码器」。
     *
     * 读 `activeVideoMode`/`activeAudioMode`（**已经在用**的那一个），而不是
     * `videoMode`/`audioMode`（**要求用的**那一个）：自动回退刚发生的一瞬间两者
     * 不同，而用户想知道的是「现在到底在用哪个」。
     */
    private fun currentDecoderKind(): MspDecoderKind {
        val manager = attachedDecoderManager ?: return MspDecoderKind.UNKNOWN
        val kinds = listOfNotNull(
            manager.activeVideoMode?.toMspKind(),
            manager.activeAudioMode?.toMspKind(),
        )
        // 报「最费 CPU 的那一路」：两路取其一的话，音频走的 FFmpeg 而视频是硬解时
        // 报「硬件解码」会让人以为很省电，而整体耗电由最贵的那路决定。
        return DECODER_KIND_SEVERITY.firstOrNull { it in kinds } ?: MspDecoderKind.UNKNOWN
    }

    /** 把所有命令统一切到主线程。 */
    private inline fun onMain(crossinline block: () -> Unit) {
        scope.launch { block() }
    }

    /**
     * 把播放服务拉起来，这样才有通知栏 / 锁屏 / 蓝牙控制。
     *
     * 用 `startService` 而**不是** `startForegroundService`：后者带一个
     * 「5 秒内必须 startForeground」的硬契约，一旦播放没真的起来（例如 uri 立刻失败），
     * 系统会直接判 ANR 崩溃。这里从用户的前台操作发起，`startService` 完全合法，
     * 服务转为前台的时机交给 Media3 自己按 `isPlaybackOngoing()` 判断。
     *
     * 失败不抛：通知栏控制是增强功能，拿不到它不应该让播放本身不可用。
     */
    private fun ensureServiceStarted() {
        if (serviceRunning) return
        runCatching { appContext.startService(Intent(appContext, MspPlaybackService::class.java)) }
            .onSuccess { serviceRunning = true }
            .onFailure { error ->
                MspLog.w(TAG, error) { "启动播放服务失败，将只在前台播放（无通知栏控制）" }
            }
    }

    private fun stopServiceIfRunning() {
        if (!serviceRunning) return
        runCatching { appContext.stopService(Intent(appContext, MspPlaybackService::class.java)) }
        serviceRunning = false
    }

    // -------------------------------------------------------------------- 解码方式

    /** 构造解码器管理器；本安装包没有 FFmpeg 时返回 null。 */
    private fun createDecoderManager(): DecoderManager? {
        if (!softwareDecoders.available) return null
        return runCatching { DecoderManager(DecoderMode.AUTO, DecoderMode.AUTO) }
            .onFailure { error -> MspLog.w(TAG, error) { "解码器管理器无法创建，退回普通内核" } }
            .getOrNull()
    }

    /**
     * 把解码器管理器挂到内核上。
     *
     * 注意这里**不能**再调一次 [createDecoderManager]：管理器是 [player] 初始化时
     * 交给渲染器工厂的那一个（见 [decoderManager]），attach 另一个实例会被 nextlib
     * 的 `check` 拦下来，结果是「能播但开关静默失效」——真机试出来的坑，别改回去。
     *
     * 失败只记日志、不抛也不动 [decoderManager]：`attach` 要求 track selector 是
     * `DefaultTrackSelector`（`ExoPlayer.Builder` 默认就给这个，我们也没换过），
     * 所以只有在契约被破坏时才会失败；此时宁可退回「一台普通的 Media3 播放器」，
     * 也不要让整个播放器起不来。
     */
    private fun attachDecoderManager() {
        val manager = decoderManager ?: return
        attachedDecoderManager = runCatching {
            manager.attach(player)
            manager
        }.onFailure { error ->
            MspLog.w(TAG, error) { "解码器管理器挂载失败，将无法运行期切换解码方式" }
        }.getOrNull()

        // 把持久化的偏好应用到内核。放在这里（而不是让上层在启动时调）是因为
        // 上层要到播放页才拿得到控制器，而那时可能已经在播放了。
        applyDecoderMode(baseDecoderMode())
    }

    /** 用户设置的解码方式（不含自动回退的临时状态）。 */
    private fun baseDecoderMode(): DecoderMode =
        if (forceSoftwareDecoding) DecoderMode.FFMPEG else DecoderMode.AUTO

    /**
     * 把两路解码器都设成 [mode]。
     *
     * 两路都要设：只改视频的话，纯音频文件（AC-3/DTS/TrueHD）根本没有视频轨，
     * 回退永远不会发生——这是这类功能最常见的遗漏。
     *
     * 失败只记日志不抛：解码方式是一个「尽量让它生效」的偏好，不是播放的前置条件。
     */
    private fun applyDecoderMode(mode: DecoderMode) {
        val manager = attachedDecoderManager ?: return
        runCatching {
            manager.selectVideoDecoder(mode)
            manager.selectAudioDecoder(mode)
        }.onFailure { error -> MspLog.w(TAG, error) { "切换解码方式失败：$mode" } }
    }

    /** 如果上一次播放曾自动回退，恢复到用户设置的解码方式。 */
    private fun resetDecoderModeIfFellBack() {
        if (fellBackMediaId == null) return
        fellBackMediaId = null
        applyDecoderMode(baseDecoderMode())
    }

    /**
     * 出错的那一刻，FFmpeg 软件解码算什么情况。
     *
     * 四个分支的顺序不能变：先排「本包根本没带」（这是最确定的事实），
     * 再看「已经明确切过去过」（手工强制 / 上一次自动回退），
     * 最后才用「还有没有非 FFmpeg 的解码器在用」倒推。
     * 倒推放最后是因为它是**间接证据**：切换失败时它也会返回「两路都是 FFmpeg」。
     */
    private fun softwareAttemptForCurrentMedia(): SoftwareDecodingAttempt = when {
        !softwareDecoders.available -> SoftwareDecodingAttempt.UNAVAILABLE
        fellBackMediaId == player.currentMediaItem?.mediaId -> SoftwareDecodingAttempt.FAILED
        forceSoftwareDecoding -> SoftwareDecodingAttempt.FAILED
        !holdsNonFfmpegDecoder() -> SoftwareDecodingAttempt.FAILED
        else -> SoftwareDecodingAttempt.NOT_TRIED
    }

    /**
     * 这一路是不是还有非 FFmpeg 解码器在用。
     *
     * 读的是 `videoMode`/`audioMode`（**要求用的**）而不是 active 系列：前者由
     * `selectVideoDecoder` 同步写下，后者要等解码器真正被选中才有值，在错误回调
     * 这一刻读它可能还是旧值，会把「已经切过了」误判成「还没切」然后无限重试。
     */
    private fun holdsNonFfmpegDecoder(): Boolean {
        // 没挂上管理器 ⇒ 一律当作「没有可回退的空间」：切都切不了，
        // 让策略判 REPORT 才是实话（否则会无限重试）。
        val manager = attachedDecoderManager ?: return false
        return manager.videoMode != DecoderMode.FFMPEG || manager.audioMode != DecoderMode.FFMPEG
    }

    /**
     * 解码失败的自动回退。返回 true 表示已接手处理。
     *
     * 决定本身在 [DecoderFallbackPolicy] 里（纯函数、可穷举单测），这里只负责
     * 「怎么把决定做出来」。
     *
     * 续播位置必须自己存下来再 seek 回去：失败后内核停在 idle，位置会归零，
     * 不主动恢复的话用户会从头开始听——一个 20 分钟的进度白白丢掉。
     */
    private fun retryWithSoftwareDecoding(error: PlaybackException): Boolean {
        val decision = DecoderFallbackPolicy.decide(
            errorCode = error.errorCode,
            alreadyRetried = fellBackMediaId == player.currentMediaItem?.mediaId,
            ffmpegAvailable = softwareDecoders.available,
            holdsNonFfmpegDecoder = holdsNonFfmpegDecoder(),
        )
        if (decision != DecoderFallbackDecision.RETRY_WITH_FFMPEG) return false
        val manager = attachedDecoderManager ?: return false

        val resumeAt = player.currentPosition.coerceAtLeast(0L)
        val resumePlaying = player.playWhenReady
        fellBackMediaId = player.currentMediaItem?.mediaId
        MspLog.w(TAG, error) {
            "解码失败（${error.errorCode}），改用 FFmpeg 软件解码，从 ${resumeAt}ms 续播"
        }

        val applied = runCatching {
            manager.selectVideoDecoder(DecoderMode.FFMPEG)
            manager.selectAudioDecoder(DecoderMode.FFMPEG)
            // 库自己只会在「播放状态非 idle」时补一次 prepare（见它的
            // requiresMediaCodecRestart），而解码失败后内核恰好停在 idle，
            // 所以重新起播必须由我们做；否则表现为「黑一下就不动了」。
            player.prepare()
            player.seekTo(resumeAt)
            player.playWhenReady = resumePlaying
        }
        if (applied.isFailure) {
            // 回退本身失败（比渲染器已经释放）。当成普通错误报告，
            // 不要再往上抛——回调里抛异常会直接干掉播放线程。
            MspLog.w(TAG, applied.exceptionOrNull()) { "自动回退到 FFmpeg 失败" }
            fellBackMediaId = null
            return false
        }
        pendingErrorMessage = null
        publish()
        return true
    }

    // ---------------------------------------------------------------------- 命令实现

    override fun setQueue(entries: List<MediaEntry>, startIndex: Int, playWhenReady: Boolean) {
        if (entries.isEmpty()) {
            stopAndClear()
            return
        }
        val safeIndex = startIndex.coerceIn(0, entries.lastIndex)
        onMain {
            _queue.value = entries
            _currentIndex.value = safeIndex
            pendingErrorMessage = null
            // 上一次播放自动回退到 FFmpeg 的状态到这里就结束。
            //
            // 在这里恢复而不是在 `onMediaItemTransition` 里：切换条目时调用
            // `selectVideoDecoder` 会（在需要重启 MediaCodec 的情况下）触发一次
            // `player.prepare()`，那会把刚开始的新文件又重启一遍，表现为
            // 「切歌时顿一下」甚至循环重启。而 setQueue 是「用户主动开一个新的播放会话」，
            // 此时重启是预期行为。
            resetDecoderModeIfFellBack()
            player.setMediaItems(
                entries.map(MediaItemMapper::toMediaItem),
                safeIndex,
                C.TIME_UNSET,
            )
            player.prepare()
            player.playWhenReady = playWhenReady
            if (playWhenReady) ensureServiceStarted()
            publish()
        }
    }

    override fun togglePlayPause() {
        onMain {
            if (player.isPlaying) {
                player.pause()
            } else {
                // 播到结尾后 `play()` 是**无效**的：内核停在 STATE_ENDED，
                // 不会自己回到开头。不先 seek 回去，用户会以为按钮坏了。
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                ensureServiceStarted()
                player.play()
            }
            publish()
        }
    }

    override fun pause() {
        onMain {
            player.pause()
            publish()
        }
    }

    override fun resume() {
        onMain {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            ensureServiceStarted()
            player.play()
            publish()
        }
    }

    override fun seekTo(positionMs: Long) {
        onMain {
            player.seekTo(positionMs.coerceAtLeast(0L))
            refreshPosition()
            publish()
        }
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(_positionMs.value + deltaMs)
    }

    override fun skipToNext() {
        onMain {
            player.seekToNextMediaItem()
            publish()
        }
    }

    override fun skipToPrevious() {
        onMain {
            player.seekToPrevious()
            publish()
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        onMain {
            player.setPlaybackSpeed(clamped)
            publish()
        }
    }

    override fun setRepeatMode(mode: MspRepeatMode) {
        onMain {
            player.repeatMode = mode.toMedia3()
            publish()
        }
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        onMain {
            player.shuffleModeEnabled = enabled
            publish()
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        onMain {
            player.volume = clamped
            publish()
        }
    }

    override fun setForceSoftwareDecoding(enabled: Boolean) {
        onMain {
            if (forceSoftwareDecoding == enabled) return@onMain
            forceSoftwareDecoding = enabled
            // 手动改了设置，之前的自动回退记录就作废了——用户刚刚明确表达了他想要什么。
            fellBackMediaId = null
            applyDecoderMode(baseDecoderMode())
            publish()
        }
    }

    override fun stopAndClear() {
        onMain {
            player.stop()
            player.clearMediaItems()
            _queue.value = emptyList()
            _currentEntry.value = null
            _currentIndex.value = 0
            _positionMs.value = 0L
            _bufferedPositionMs.value = 0L
            pendingErrorMessage = null
            // 队列都空了，通知栏上的播放控制就没有意义——主动停服务，
            // 否则会留下一个既停不下来、点了也没反应的幽灵通知。
            stopServiceIfRunning()
            publish()
        }
    }

    override fun release() {
        // 先取消计时器再释放内核，否则那条循环可能在下一次 tick 时碰到已释放的 player。
        scope.cancel()
        stopServiceIfRunning()
        player.release()
    }

    private companion object {
        /** 超过 4 倍速时 Sonic 的音质损失已经不可接受，超过 0.25 倍则几乎听不出内容。 */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4.0f
    }
}

private fun Int.toMsp(): MspRepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> MspRepeatMode.ONE
    Player.REPEAT_MODE_ALL -> MspRepeatMode.ALL
    else -> MspRepeatMode.OFF
}

private fun MspRepeatMode.toMedia3(): Int = when (this) {
    MspRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    MspRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    MspRepeatMode.ALL -> Player.REPEAT_MODE_ALL
}

/** 把两路解码器归并成一个结论时用的排序：从最费 CPU 到最省。 */
private val DECODER_KIND_SEVERITY = listOf(
    MspDecoderKind.FFMPEG,
    MspDecoderKind.SYSTEM_SOFTWARE,
    MspDecoderKind.HARDWARE,
)
