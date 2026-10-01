package com.multisuperplayer.core.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
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
) : PlaybackController {

    private val appContext: Context = context.applicationContext

    override val player: ExoPlayer = ExoPlayer.Builder(appContext)
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
                    pendingErrorMessage = PlaybackErrorMapper.describe(
                        errorCode = error.errorCode,
                        causeName = error.cause?.let { it::class.java.simpleName },
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
    }

    // ---------------------------------------------------------------- 内部状态同步

    /** 把内核当前状态抄进 Flow。只在主线程调用。 */
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
