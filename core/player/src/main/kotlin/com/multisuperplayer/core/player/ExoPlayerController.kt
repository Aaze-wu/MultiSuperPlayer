package com.multisuperplayer.core.player

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
    /**
     * 续播位置的存取。默认参数是「什么都不存」而不是 null：
     * 见 [PlaybackPositionStore.None]。
     */
    private val positionStore: PlaybackPositionStore = PlaybackPositionStore.None,
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

    /** A-B 循环状态。见 [AbRepeatPolicy]。 */
    private var abRepeat: AbRepeatState = AbRepeatState.None

    /** 用户是否允许记住播放进度（持久化的那份在 `:core:data`）。 */
    private var rememberPosition = true

    /**
     * 用户是否允许记录「最近播放」（持久化的那份在 `:core:data`）。
     *
     * 和 [rememberPosition] 是两件事：那个管「位置记不记」，这个管「播放过什么记不记」。
     * 它管的是**新条目**：关掉之后不再给「从没播过的」媒体建记录，已经在库里的条目
     * 照旧更新（否则「已经记下的」就冻住了，而设置里那句文案说的是它们不会被删掉）。
     * 两种建记录的方式都要挡住——「位置太短」（[PlaybackPositionStore.markPlayed]）
     * 和「播完了」（`write(id, 0)`，见 [persistTrackedPosition]），漏掉后者时
     * 一个从头看到尾的短片仍然会冒进列表，实测踩过。
     */
    private var recordRecentPlays = true

    /**
     * 正在跟踪的媒体、它的位置和时长。
     *
     * 为什么要另存一份而不是每次现读 `player`：切条目之后
     * `player.currentPosition` 立刻变成 0、`player.duration` 变成新文件的时长，
     * 于是「把上一条的位置存下来」这件事**已经没有数据可用了**。时长也一样，
     * 不缓存它的话，[persistTrackedPosition] 会拿新文件的时长去判旧文件
     * 「是不是看完了」，然后错误地清掉记录。
     */
    private var trackedMediaId: String? = null
    private var trackedPositionMs = 0L
    private var trackedDurationMs = 0L

    /** 上一次落盘的时刻，用于限流。 */
    private var lastSaveAtMs = 0L

    /** 上一轮回调时是不是在播；用于识别「刚停下来」这个瞬间。 */
    private var wasPlaying = false

    /** 轨道清单（音频 + 字幕）。没有媒体时是空列表。 */
    private val _tracks = MutableStateFlow<List<MspTrackInfo>>(emptyList())
    override val tracks: StateFlow<List<MspTrackInfo>> = _tracks.asStateFlow()

    /**
     * 轨道 id → Media3 的 `TrackGroup`。
     *
     * 选轨必须拿 `TrackGroup` 实例去构造覆盖（Media3 没有「按 id 选」的入口），
     * 而 id 是我们自己编的。每次 `onTracksChanged` 重建这份映射：
     * 切到另一条媒体后，旧的 `TrackGroup` 已经不属于当前播放列表，拿它构造的
     * 覆盖什么也不会发生（用户点了没反应，而且不报错）。
     */
    private val trackGroups = mutableMapOf<String, TrackGroup>()

    /**
     * 用户手选的字幕轨 id；null = 还没选过（那时走自动挑）。
     *
     * 只对**当前这条媒体**有效，切条目时清空（见 `onMediaItemTransition`）：
     * 手选留在下一条上，就成了「我明明没选过却是这条」。
     */
    private var textTrackChoice: String? = null

    /** 内嵌字幕行。切换条目时清空。 */
    private val _embeddedSubtitle = MutableStateFlow(EmbeddedSubtitleState())
    override val embeddedSubtitle: StateFlow<EmbeddedSubtitleState> = _embeddedSubtitle.asStateFlow()

    /**
     * 字幕偏好的语言，取自**应用界面语言**。
     *
     * 还没有「字幕偏好语言」这个设置项（那是语言/翻译那一版的事），所以先用界面语言
     * 当成弱推测：用户把界面调成中文，多半也想看中文字幕。它只参与自动选轨，
     * 选错了用户在面板里换一下就行。
     */
    private val preferredTextLanguages: List<String> by lazy {
        val configuration = appContext.resources.configuration
        @Suppress("DEPRECATION")
        val locales = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            configuration.locales
        } else {
            // 单个 locale 的兼容分支：这里**必须**用 `configuration.locale`，
            // 不能用 `Locale.getDefault()`——后者是进程级的，和用户给本应用单独指定的
            // 语言可能不一致，自动选轨就会挑到另一种语言的轨。
            android.os.LocaleList(configuration.locale)
        }
        (0 until locales.size()).mapNotNull { normalizeLanguageTag(locales.get(it).toLanguageTag()) }
    }

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
        // 必须在 addListener 之前：这两条参数决定了「第一条媒体加载完时选哪条字幕轨」，
        // 而选择结果是通过 onTracksChanged 递过来的。晚一步设，第一次选择就已经
        // 按 Media3 的默认值（= 什么都不选）做完了。
        //
        // Media3 的默认轨道参数**不选**任何文本轨：内嵌字幕「存在但不显示」的根因就在这。
        // `setPreferredTextLanguages` 按界面语言挑；`setSelectUndeterminedTextLanguage`
        // 兜住「轨道没标语言」的片源（否则它一条都不会选，而用户在面板里能看到它，
        // 就会以为「点不动」）。
        //
        // 注意这两条设下去之后**内核自己就会选字幕轨**，于是 [autoSelectTextTrack] 里
        // 那套保守规则（强制轨排最后、语言对不上就不挂）多数时候轮不到执行。
        // 两者的分工写在那个方法的 KDoc 里，改这里之前先读它。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*preferredTextLanguages.toTypedArray())
            .setSelectUndeterminedTextLanguage(true)
            .build()

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
                    // 从「在播」变成「不在播」就是个关键时机：用户按了暂停、拔了耳机、
                    // 丢了音频焦点、或者文件真的播完了。这些时刻正是用户要离开的时刻，
                    // 等下一个 5 秒节拍可能已经被系统回收了。
                    if (wasPlaying && !player.isPlaying) {
                        trackPosition()
                        persistTrackedPosition()
                    }
                    wasPlaying = player.isPlaying
                    publish()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // 真正开始播放（或恢复）就清掉上一次的错误提示。
                    // 这里只改缓存，实际写进 Flow 由紧随其后的 onEvents 完成。
                    if (playbackState == Player.STATE_READY) pendingErrorMessage = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 先试自动回退。回退成功就直接返回，不设错误文案——
                    // 用户看到的是「黑一下接着放」，而不是一条错误提示。
                    if (retryWithSoftwareDecoding(error)) return

                    // `describe` 返回的是「哪一条 + 什么参数」，在这里解析成字符串：
                    // UI 状态里放一个已经取过语言的 `String`，是因为它在会话中途
                    // 换语言的场景下没必要跟着变（错误提示本身就活不过几秒）。
                    // 用 `appContext` 而不是 `context` 是为了避免把 Activity 泄漏进
                    // 播放器的长生命周期回调里。
                    pendingErrorMessage = PlaybackErrorMapper.describe(
                        errorCode = error.errorCode,
                        causeName = error.cause?.let { it::class.java.simpleName },
                        softwareDecoding = softwareAttemptForCurrentMedia(),
                    ).resolve(appContext.resources)
                    MspLog.e(TAG, error) { "播放失败：$pendingErrorMessage" }
                    publish()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    MspLog.d(TAG) { "切换到条目 ${mediaItem?.mediaId}（原因 $reason）" }
                    // A-B 循环是「针对某一个文件里的一段」的状态。切到另一条之后，
                    // 上一首的 A/B 毫无意义，而且很可能落在新文件外面（然后就是
                    // 每个节拍都 seek 的死循环）。直接清掉。
                    if (abRepeat != AbRepeatState.None) abRepeat = AbRepeatState.None
                    // 手选的字幕轨、已经读到的内嵌字幕行都属于**上一条**媒体：
                    // 台词留着会跟着新片子的时间轴显示（内容和位置都不对），
                    // 而用户完全看不出是「没收走」造成的。
                    textTrackChoice = null
                    // 音轨同理，而且更隐蔽：`TrackGroup.id` 在不同片源里**可能一模一样**
                    // （容器里的轨道编号），所以留着手选的覆盖，下一部片子会静默地用上
                    //「第 0 条音轨」这个选择——比如下一部片子里那是导演评论音轨。
                    // 字幕那边靠 `textTrackChoice = null` 之后重跑自动挑选覆盖了旧覆盖，
                    // 音频没有自动挑选那一步，只能显式清。
                    clearAudioOverride()
                    _embeddedSubtitle.value = EmbeddedSubtitleState()
                    publish()
                }

                /**
                 * 轨道清单变了（换条目、片源信息解析完成、自适应档位变化）。
                 *
                 * 只在 `onEvents` 里刷新是不够的：那个回调不会因为轨道清单变化而触发，
                 * 而「多音轨片源」正是靠这里才第一次出现在界面上。
                 */
                override fun onTracksChanged(tracks: Tracks) {
                    syncTracks(tracks)
                }

                /**
                 * 该显示哪些字幕行。
                 *
                 * 内嵌字幕进界面**只有这一条路**：cue 被收进 `embeddedSubtitle`，
                 * 由我们自己的字幕层（和外挂字幕同一套）渲染、翻译、导出。
                 *
                 * 注意「只有这一条路」不是凭空成立的：`PlayerView` 自己也会拿到同一批
                 * cue 并画一遍（它内部的 `SubtitleView`），所以**播放页里**
                 * `PlayerVideoSurface` 必须把那一层遮掉，否则同一句字幕会在屏幕上看两遍。
                 * 两者都在同一个 `Player` 上取数据，改这里之前先读那份 KDoc。
                 *
                 * 位图字幕（PGS / VobSub）的 `Cue.text` 是空的，这里会自然丢掉它们；
                 * 关掉 `SubtitleView` 之后它们也就真的不显示了（README 第 12 条）。
                 */
                override fun onCues(cueGroup: CueGroup) {
                    val texts = cueGroup.cues.mapNotNull { cue: Cue ->
                        cue.text?.toString()?.takeIf { it.isNotBlank() }
                    }
                    val next = _embeddedSubtitle.value.receive(
                        atMs = cueGroup.presentationTimeUs / 1_000L,
                        texts = texts,
                    )
                    if (next.cues.size != _embeddedSubtitle.value.cues.size) {
                        MspLog.d(TAG) { "内嵌字幕读到 ${next.cues.size} 行" }
                    }
                    _embeddedSubtitle.value = next
                }
            },
        )

        scope.launch {
            while (isActive) {
                // 只在播放中刷新位置：暂停时位置不会变，白刷就是白耗电。
                if (player.isPlaying) {
                    refreshPosition()
                    trackPosition()
                    enforceAbRepeat()
                    savePositionIfDue()
                }
                // A-B 循环生效时把节拍提到 50ms：200ms 的粒度意味着一秒的循环区间
                // 会被拉长两成，听感上是一个明显的「拖拍」。只在用它时才付这个代价。
                delay(if (abRepeat.isActive) AB_TICK_INTERVAL_MS else TICK_INTERVAL_MS)
            }
        }

        attachDecoderManager()
    }

    // ------------------------------------------------------------------ 轨道选择

    /**
     * 把内核的轨道清单抄进 Flow，并在用户还没手选时自动挑一条字幕轨。
     *
     * ## 为什么要自动挑
     *
     * Media3 的默认轨道选择参数**不会选任何文本轨**（实测：一个带内嵌中文字幕的
     * MKV 播到有台词的地方屏幕上一个字都没有，日志里连 TrackSelector 的行都没有）。
     * 所以「容器里明明有字幕却不显示」不是渲染器的问题，而是没人选它。
     */
    private fun syncTracks(tracks: Tracks) {
        trackGroups.clear()
        val list = ArrayList<MspTrackInfo>()
        var groupIndex = 0
        for (group in tracks.groups) {
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> MspTrackKind.AUDIO
                C.TRACK_TYPE_TEXT -> MspTrackKind.TEXT
                else -> null
            }
            // 不支持的轨（没有解码器）不列出来：点了也不会有声音/字幕，
            // 而用户会以为是播放器坏了。`isSupported` 已经把这一点问清楚了。
            if (kind == null || !group.isSupported) {
                groupIndex++
                continue
            }
            val mediaTrackGroup = group.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val id = trackId(kind, mediaTrackGroup, i)
                trackGroups[id] = mediaTrackGroup
                list += MspTrackInfo(
                    id = id,
                    kind = kind,
                    label = format.label?.takeIf { it.isNotBlank() },
                    language = normalizeLanguageTag(format.language),
                    mimeType = format.sampleMimeType.orEmpty(),
                    codec = format.codecs?.takeIf { it.isNotBlank() },
                    channelCount = format.channelCount.takeIf { kind == MspTrackKind.AUDIO && it > 0 },
                    indexInGroup = i,
                    isSelected = group.isTrackSelected(i),
                    isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            groupIndex++
        }
        _tracks.value = list
        autoSelectTextTrack(list)
    }

    /**
     * 内核没有选任何文本轨时，我们自己补一条。
     *
     * ## 这是**第二层**兜底，不是主路径
     *
     * `init` 里已经给 Media3 设了 `setPreferredTextLanguages`（取自界面语言）+
     * `setSelectUndeterminedTextLanguage(true)`（兜住没标语言的轨），所以**绝大多数
     * 情况下内核自己就选好了**：语言与界面语言一致、语言未标、或者容器标了
     * `default` 的轨，都由 Media3 先选。那时下面第 2 步直接返回，
     * [bestEmbeddedTextTrack] 算出来的结果会被丢掉——**那不是死代码**，
     * 而是「内核选得更早」。
     *
     * 我们这套规则真正生效的角落只有一个：**语言对不上、而容器标了默认轨**。
     * Media3 的 `preferredTextLanguages` 匹配不上它就不会选，
     * `setSelectUndeterminedTextLanguage` 只管「未标语言」那一种，于是内核留空，
     * 由我们按 [bestEmbeddedTextTrack] 的保守规则挂上。
     *
     * ## 为什么每个提前返回都要留一条日志
     *
     * 这里出过一个**很难查**的问题：日志里 `自动选中内嵌字幕轨「…」` 一千多行
     * **一次都没出现**，一度被当成「整条自动挑选路径是死代码」。真实原因是
     * **第 3 步被内核抢先**——它和另外两个提前返回在日志里长得完全一样（都是空白），
     * 于是「代码没执行」和「执行了但没走到底」分不开。
     *
     * 三个提前返回各留一条**能互相区分**的日志：它们说的都是「没改选轨」，
     * 但一个说「用户手选过」、一个说「没什么可选的」、一个说「内核已经选好了」。
     * 下次遇到同样的问题，看日志就够了，不用再从「日志里没有」倒推「代码没跑」。
     */
    private fun autoSelectTextTrack(list: List<MspTrackInfo>) {
        // 用户今天手选过就别自动改了——那会把用户的明确选择反复推翻。
        if (textTrackChoice != null) {
            MspLog.d(TAG) { "字幕轨已由用户手选（$textTrackChoice），不做自动挑选" }
            return
        }
        val auto = list.bestEmbeddedTextTrack(preferredTextLanguages)
        if (auto == null) {
            MspLog.d(TAG) { "没有可自动挂上的内嵌字幕轨（偏好语言 $preferredTextLanguages）" }
            return
        }
        if (list.any { it.kind == MspTrackKind.TEXT && it.isSelected }) {
            // 这一条**必须**留下：内核是选好了的，而上面两种是「没人选」。
            // 对用户可见的结果一样（都能在面板里看到轨），含义却相反。
            MspLog.d(TAG) {
                "内核已自行选中文本轨「${list.firstSelectedTextTrack()?.displayLabel("?")}」，不介入"
            }
            return
        }
        selectTrack(MspTrackKind.TEXT, auto.id)
        MspLog.d(TAG) {
            "自动选中内嵌字幕轨「${auto.displayLabel(auto.id)}」（语言 ${auto.language ?: "未标"}）"
        }
    }

    /**
     * 轨道 id：同一条媒体内稳定，跨条目不承诺。
     *
     * 用 `TrackGroup.id`（容器里那份，稳定）而不是「第几个组」：自适应音轨的不同
     * 档位会共享一个组，而组顺序在片源重新解析后并不保证不变。
     */
    private fun trackId(kind: MspTrackKind, group: TrackGroup, indexInGroup: Int): String =
        "${kind.name}:${group.id}:$indexInGroup"

    override fun selectTrack(kind: MspTrackKind, id: String) {
        val group = trackGroups[id] ?: run {
            MspLog.d(TAG) { "选轨：$id 不在当前片源里，忽略" }
            return
        }
        val index = trackIndexOf(id, group) ?: 0
        MspLog.d(TAG) { "选轨 $id" }
        if (kind == MspTrackKind.TEXT) textTrackChoice = id
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, listOf(index)))
            .setTrackTypeDisabled(trackTypeOf(kind), false)
            .build()
        // 覆盖生效后内核会重新回调 `onTracksChanged`，清单里的 isSelected 随之更新；
        // 但那条路径依赖内核的调度，这里先按已选好算一遍，界面才不会有一下「没勾上」的帧。
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(isSelected = true) else it }
    }

    override fun useAutomaticTracks() {
        textTrackChoice = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        // 清掉覆盖后内核不一定回调 `onTracksChanged`（参数值变了但清单没变），
        // 所以自己再算一遍：自动挑选那一步就在 [syncTracks] 里。
        syncTracks(player.currentTracks)
    }

    /**
     * 音频轨回到内核自动挑选。**不动**字幕轨的选择。
     *
     * 没有 `audioTrackChoice` 那种字段：字幕需要一个字段去压住 `syncTracks` 里的
     * 自动挑选，而音频的自动挑选本来就是内核自己在做（我们只负责盖覆盖）。
     */
    override fun useAutomaticAudioTrack() {
        MspLog.d(TAG) { "音轨回到自动挑选" }
        clearAudioOverride()
        // 同 [useAutomaticTracks]：清掉覆盖不保证有回调，自己重算一次清单，
        // 面板上的单选才会立刻落到内核真正选中的那条上。
        syncTracks(player.currentTracks)
    }

    /** 清掉音频类型的轨道覆盖，把选版权交回内核。 */
    private fun clearAudioOverride() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
    }

    private fun trackTypeOf(kind: MspTrackKind): Int = when (kind) {
        MspTrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
        MspTrackKind.TEXT -> C.TRACK_TYPE_TEXT
    }

    /** 从 id 里取回组内下标（id 的最后一段）。 */
    private fun trackIndexOf(id: String, group: TrackGroup): Int? {
        val raw = id.substringAfterLast(':').toIntOrNull()
        return raw?.takeIf { it in 0 until group.length }
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
            videoSize = player.videoSize.toMspVideoSize(),
            abRepeat = abRepeat,
            decoderKind = currentDecoderKind(),
            errorMessage = pendingErrorMessage,
        )
        syncCurrentEntry()
        trackPosition()
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

    // ------------------------------------------------------------------ 续播位置

    private fun durationOfCurrentMedia(): Long =
        player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    /**
     * 把「正在跟踪的那条媒体」的最新位置和时长记下来。
     *
     * 两个时机：位置刷新（每 200ms）和发布状态时。必须在**换条目的那一刻**
     * 还能拿到旧条目的位置与时长，所以才会有 [trackedPositionMs]/[trackedDurationMs]
     * 这份副本——切完之后 `player` 上已经换人了。
     */
    private fun trackPosition() {
        val id = player.currentMediaItem?.mediaId
        if (id == null) {
            trackedMediaId = null
            trackedPositionMs = 0L
            trackedDurationMs = 0L
            return
        }
        if (id != trackedMediaId) {
            // 先落上一条的盘，再开始跟踪新的。
            persistTrackedPosition()
            trackedMediaId = id
            trackedPositionMs = 0L
            trackedDurationMs = 0L
            lastSaveAtMs = 0L
        }
        trackedPositionMs = player.currentPosition.coerceAtLeast(0L)
        val duration = durationOfCurrentMedia()
        if (duration > 0L) trackedDurationMs = duration
    }

    /**
     * 把 [trackedMediaId] 的进度落盘。
     *
     * 落什么由 [ResumePolicy.persistStep] 决定（纯函数，四种开关组合的边界都在那边的
     * 单测里）：值得记就写位置；看完了就把位置归零；位置太短就只把时间戳推到今天。
     *
     * 两条容易搞错的边界，都在设备上踩过：
     *
     * - **「播完了」不能顺手新建记录。** 归零是必要的（留着 99% 下次会直接从片尾开始，
     *   看起来像文件坏了），但 `write(id, 0)` 会**建出**一条记录，于是关掉「记录最近播放」
     *   之后，一个从头看到尾的短片仍然会冒进列表里。所以关掉那个开关时改成
     *   [PlaybackPositionStore.resetPosition]（只清已有记录，没有就什么都不做）。
     * - **「位置太短」要保住已有位置。** 昨天看到 40 分钟的那条，不会被今天的 3 秒改写，
     *   但「刚播过」这个事实要记下来——以前这里是「什么都不做」，所以一个 20 秒的片段
     *   播到第 8 秒、或者一部电影看一眼就退出，磁盘上不会留下任何痕迹，用户刚播过的
     *   东西在「最近播放」里根本不出现，看起来就像那个功能没做。
     */
    private fun persistTrackedPosition() {
        val id = trackedMediaId ?: return
        val step = ResumePolicy.persistStep(
            positionMs = trackedPositionMs,
            durationMs = trackedDurationMs,
            rememberPosition = rememberPosition,
            recordRecentPlays = recordRecentPlays,
        )
        val position = trackedPositionMs
        val action: (suspend () -> Unit)? = when (step) {
            ResumePolicy.Persist.POSITION -> { { positionStore.write(id, position) } }
            ResumePolicy.Persist.ZERO -> { { positionStore.write(id, 0L) } }
            ResumePolicy.Persist.ZERO_IF_RECORDED -> { { positionStore.resetPosition(id) } }
            ResumePolicy.Persist.MARK_PLAYED -> { { positionStore.markPlayed(id) } }
            ResumePolicy.Persist.NOTHING -> null
        }
        if (action == null) return
        // 不阻塞主线程：DataStore 要重写整个文件并 fsync，放在节拍里做
        // 会让位置条每 5 秒卡一下。失败也不能影响播放。
        scope.launch {
            runCatching { action() }
                .onFailure { error -> MspLog.w(TAG, error) { "保存播放进度失败" } }
        }
    }

    /**
     * 每 [SAVE_INTERVAL_MS] 落一次盘（只在「记住播放位置」开着时）。
     *
     * 「记住播放位置」关掉时这里直接返回，但**不等于什么都不记**：暂停、切条目、退出
     * 播放器这几条路径都会直接调 [persistTrackedPosition]，那时 [ResumePolicy.persistStep]
     * 仍然可能给出 `MARK_PLAYED`（用户要的是「每次从头播，但看得见看过什么」）。
     * 节拍这条路只服务于「位置」，所以它认的是那个开关，而不是这个函数被谁调用。
     */
    private fun savePositionIfDue() {
        if (!rememberPosition) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveAtMs < SAVE_INTERVAL_MS) return
        lastSaveAtMs = now
        persistTrackedPosition()
    }

    /**
     * A-B 循环的执行者：越过 B 点就绕回 A 点。
     *
     * 用「轮询 + `seekTo`」而不是 `MediaItem.ClippingConfiguration` + 单曲循环：
     * 后者要重建 MediaItem、要抢用户的循环模式，而且会触发 `onMediaItemTransition`
     * 从而把我们自己的「切条目就清 A-B」逻辑反过来打自己。这个功能只是个临时的
     * 「反复听一段」工具，行为可预测比省一点 seek 开销重要得多。
     */
    private fun enforceAbRepeat() {
        val range = abRepeat
        if (!range.isActive) return
        val start = range.startMs ?: return
        val end = range.endMs ?: return
        val duration = durationOfCurrentMedia()

        // B 落在文件外面（换了更短的媒体，或者设 B 时时长还没解析出来）：
        // 夹一次。不做这件事的后果不是「循环不准」，而是**每个节拍都 seek 一次**
        // ——[AbRepeatState.hasPassedEnd] 会永远为真，播放器表现为卡死并疯狂重复解码。
        if (duration > 0L && end > duration) {
            val clamped = AbRepeatPolicy.clampTo(range, duration)
            abRepeat = clamped
            publish()
            return
        }

        if (player.currentPosition < end) return
        player.seekTo(start)
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

    /**
     * 把所有命令统一切到主线程。
     *
     * 已经在主线程时**直接执行**，不绕一次调度。
     *
     * `scope` 用的是 `Dispatchers.Main`（不是 `Main.immediate`），无条件 `launch`
     * 会把每个命令推迟一个主循环回合。对「点一下、等一帧」这种调用完全看不出来，
     * 但它让「写 -> 立刻读」变成错的：`viewModelScope` 是 `Main.immediate`，
     * 在点击回调里启动的协程会在**同一个主线程任务内**内联跑完，于是它读到的
     * `state` 还是命令落地前的旧值。实测踩到的就是这个：选 1.5×，把回读到的速度
     * 写进设置，存下去的是 1.0（`feature:player` 的 `PlayerViewModel.setSpeed`
     * 现在改成自己算，不再回读）。
     *
     * 同步执行后 `publish()` 在返回前就把新状态发出去了，命令与状态在任何调用者
     * 眼里都是同一时刻的事，不再依赖「内核恰好走到哪一步」。
     *
     * 线程约定没有变：[PlaybackController] 承诺的「任意线程可调」靠的仍然是下面
     * 那个 `else` 分支，内部执行位置始终是主线程。
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else scope.launch { block() }
    }

    /**
     * 把播放服务拉起来，这样才有通知栏 / 锁屏 / 蓝牙控制。
     *
     * 用 `startService` 而**不是** `startForegroundService`：后者带一个
     * 「5 秒内必须 startForeground」的硬契约，一旦播放没真的起来（例如 uri 立刻失败），
     * 系统会直接判 ANR 崩溃。这里从用户的前台操作发起，`startService` 完全合法，
     * 服务转为前台的时机交给 Media3 自己按 `isPlaybackOngoing()` 判断。
     *
     * 上面这句话**依赖**服务侧的一件事：`MspPlaybackService.onCreate` 里必须调
     * `addSession(session)`。Media3 的 `MediaNotificationManager` 第一行就是
     * `if (!isSessionAdded(session) || !shouldShowNotification(session)) { 撤掉通知; return }`，
     * 而 `isSessionAdded` 只在 `addSession()` 或者有 `MediaController` 连上来之后才为真——
     * 本项目界面直接持有 `ExoPlayer`，一个 `MediaController` 都没有。少了那一行，
     * 症状是「能播、`dumpsys media_session` 里状态是 PLAYING，但通知栏什么都没有、
     * 没有任何日志」，而且 `isPlaybackOngoing()` 也跟着是 false，于是前台服务也起不来。
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

    override fun setQueue(
        entries: List<MediaEntry>,
        startIndex: Int,
        playWhenReady: Boolean,
        resumePositionMs: Long?,
    ) {
        if (entries.isEmpty()) {
            stopAndClear()
            return
        }
        val safeIndex = startIndex.coerceIn(0, entries.lastIndex)
        onMain {
            // 先把上一条的位置落盘：换完队列 currentPosition 就归零了。
            persistTrackedPosition()
            trackedMediaId = null
            trackedPositionMs = 0L
            trackedDurationMs = 0L

            _queue.value = entries
            _currentIndex.value = safeIndex
            pendingErrorMessage = null
            // 新的播放会话，A-B 状态不该跨会话残留。
            abRepeat = AbRepeatState.None
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
            // 续播：seek 放在 prepare 之后、playWhenReady 之前。
            // 反过来的话，内核会先把开头几十毫秒放出来再跳走（听起来像卡了一下）。
            resumePositionMs?.takeIf { it > 0L }?.let { player.seekTo(it) }
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
            // 用户主动暂停 = 用户要走了。不等到下一个 5 秒节拍。
            trackPosition()
            persistTrackedPosition()
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
        val clamped = clampPlaybackSpeed(speed)
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

    override fun cycleAbRepeat() {
        onMain {
            abRepeat = AbRepeatPolicy.advance(
                current = abRepeat,
                positionMs = player.currentPosition,
                durationMs = durationOfCurrentMedia(),
            )
            publish()
        }
    }

    override fun setRememberPosition(enabled: Boolean) {
        onMain {
            if (rememberPosition == enabled) return@onMain
            // 关掉之前先落一次盘：否则「关掉记忆」会顺手把刚才那一段也丢掉。
            if (!enabled) persistTrackedPosition()
            rememberPosition = enabled
        }
    }

    override fun setRecordRecentPlays(enabled: Boolean) {
        onMain {
            // 不需要像 [setRememberPosition] 那样先落盘：关掉这个开关只是不再写
            // 「刚播过」，续播位置该写的时候照旧写（它是另一个开关管的）。
            recordRecentPlays = enabled
        }
    }

    override fun stopAndClear() {
        onMain {
            // 关掉播放器之前把位置存下来——这一条是「返回」路径上最常走的一步。
            trackPosition()
            persistTrackedPosition()
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
        // 倍速的上下界不在这一层：它是 [PlaybackController] 的契约（界面按它提供档位），
        // 所以定义在接口那个文件里，这里直接用。

        /**
         * 播放进度落盘的间隔。
         *
         * 5 秒是「被系统杀掉时最多丢 5 秒」和「别每个节拍都写盘」之间的折中：
         * DataStore 每次写都要重写整个文件并 fsync，1 秒一次在低端机上能看到掉帧。
         */
        const val SAVE_INTERVAL_MS = 5_000L

        /** A-B 循环生效时的位置刷新间隔。见 [enforceAbRepeat]。 */
        const val AB_TICK_INTERVAL_MS = 50L
    }
}

private fun Int.toMsp(): MspRepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> MspRepeatMode.ONE
    Player.REPEAT_MODE_ALL -> MspRepeatMode.ALL
    else -> MspRepeatMode.OFF
}

private fun androidx.media3.common.VideoSize.toMspVideoSize(): MspVideoSize =
    MspVideoSize(
        widthPx = width,
        heightPx = height,
        // Media3 在还没解析出视频轨时给的是 0/0，pixelWidthHeightRatio 有可能是
        // 0 或负数。这里不修正，交给 MspVideoSize.isValid 去判——修一个假的 1.0
        // 进去反而会让「无效尺寸」和「1:1 视频」变得无法区分。
        pixelWidthHeightRatio = pixelWidthHeightRatio,
    )

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
