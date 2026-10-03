package com.multisuperplayer.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.subtitleSourceOfDocument
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.player.MspDecoderKind
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.SleepTimerOptions
import com.multisuperplayer.core.player.SleepTimerState
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.ui.chrome.LocalAppChrome
import com.multisuperplayer.core.ui.text.string
import com.multisuperplayer.core.ui.theme.LocalArtworkAccentState
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 控制条和提示层自动消失的时间。
 *
 * 4 秒：短到「想看画面时它自己就没了」，长到够读完两行标题。这个值只影响全屏
 * 状态下那两条覆盖层——竖屏下控制条在画面外面，一直是可见的。
 */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/** 锁定时那个孤零零的解锁按钮露出来的时长。它只提示「这里有东西」，不需要太久。 */
private const val LOCK_HINT_TIMEOUT_MS = 3_000L

/**
 * 亮度/音量、拖动进度的提示泡留在屏幕上的时长。
 *
 * 比控制条短得多：这两个是**手指还在屏幕上的操作**的反馈，手指一松就该让开。
 */
private const val GESTURE_HINT_TIMEOUT_MS = 800L

/**
 * 双击播放/暂停的提示留在屏幕上的时长。
 *
 * 比上一个长一点：它不是「手指还在屏幕上」的反馈（双击是一次已经做完的动作），
 * 而是唯一能证明「双击起作用了」的东西——横屏全屏时控制条多半已经淡出，
 * 按钮图标那个变化用户根本看不到，提示丢得太快跟没有提示几乎一样。
 */
private const val PLAY_PAUSE_HINT_TIMEOUT_MS = 1_000L

/**
 * 播放页入口（有状态）。
 *
 * 除了转发状态，它还负责三件「必须在树里同时看到两端」的事：
 * 1. 把当前封面的取色结果推给最外层的主题（见 [LocalArtworkAccentState]）；
 * 2. 把「要不要全屏」推给应用外壳，让底部导航栏让位（见 [LocalAppChrome]）；
 * 3. 全屏/锁定的副作用（系统栏、方向、返回键），见 [PlayerFullscreenEffect]。
 *
 * 三件事的共同点是：它们的**两端**（这一层知道的事实 / 需要跟着变的外壳）
 * 隔着好几层组合，中间那些层对它们一无所知。用 CompositionLocal 而不是
 * 一层层传参数，就是为了让中间那些层保持无知。
 */
@Composable
fun PlayerRoute(
    modifier: Modifier = Modifier,
    onOpenTranslationSettings: () -> Unit,
    // 「收起」：离开播放页回到刚才那个列表（播放不停，底部会出现迷你播放器）。
    //
    // 它是一个**回调**而不是这里的一句 `popBackStack()`：这一层根本拿不到导航栈，
    // 而且「收起等于弹掉这一页」是应用骨架的决定，不是播放页面自己的决定。
    onCollapse: () -> Unit = {},
    pendingSubtitle: SubtitleSource? = null,
    onPendingSubtitleApplied: () -> Unit = {},
) {
    val viewModel: PlayerViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entry by viewModel.currentEntry.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val bufferedMs by viewModel.bufferedPositionMs.collectAsStateWithLifecycle()
    val artworkAccent by viewModel.artworkAccent.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    // 睡眠定时与队列。两个都是「不常变」的值（用户点一下才变一次，定时到点变一次），
    // 所以订在这一层不会把播放页拖进频繁重组。
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()

    val subtitleViewModel: SubtitleViewModel = koinViewModel()
    val subtitleState by subtitleViewModel.state.collectAsStateWithLifecycle()
    val translationState by subtitleViewModel.translationState.collectAsStateWithLifecycle()
    val exportMessage by subtitleViewModel.exportMessage.collectAsStateWithLifecycle()
    var showSubtitleSheet by remember { mutableStateOf(false) }

    // 导出要分两步：先记住用户选的是「哪种格式 + 哪种模式」（弹菜单的那一刻就知道），
    // 再等 SAF 回来拿到目标 uri（可能要过好几分钟，用户还得翻目录）。
    // 把两者同时从 launcher 回调里取出来是不行的：回调里只有 uri。
    var pendingExport by remember { mutableStateOf<Pair<SubtitleExportFormat, SubtitleExportMode>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        // octet-stream 而不是 text/plain：DocumentsUI 不会给已知的文本类型补扩展名/改名字，
        // 文件名里自己带的 .srt / .ass 才能原样保留。
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        // uri 为 null = 用户取消了。此时什么都不做，不要编一句「导出成功」。
        if (uri != null && pending != null) {
            subtitleViewModel.exportTo(uri, pending.first, pending.second)
        }
    }

    // 手动挑字幕文件。
    //
    // 用 `OpenDocument(arrayOf("*/*"))` 而不是按 MIME 过滤：字幕文件没有可靠的 MIME
    // （`.srt` 常常是 `application/octet-stream`，`.ts` 甚至是 `video/mp2t`），
    // 过滤的后果是「我明明有这个文件，选择器里根本看不见」。挑错了由解析器报
    // 「无法解析这个字幕文件」，那句话说得出原因，而「找不到文件」说不出。
    //
    // 不需要 `takePersistableUriPermission`：这个选择**不记忆**（换条目就回到自动），
    // 一次性的读权限足够读完它，而长期持有会白占一个持久授权名额。
    val context = LocalContext.current
    val manualSubtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // uri 为 null = 用户取消了。此时什么都不做，不要编一句「已挂上字幕」。
        if (uri != null) {
            subtitleViewModel.selectSource(subtitleSourceOfDocument(context.contentResolver, uri))
        }
    }

    // 直接把「哪条媒体」推给字幕 ViewModel，而不是让它去订阅播放内核：
    // 这个 LaunchedEffect 就是两者之间唯一的连接点，读代码时一眼能看到。
    // key 用整个 entry（而不是 entry.id）：换歌、以及同一条片子重新构造
    // （队列刷新）都会重新绑一次，而 `bindEntry` 自己会把同 id 的那一次
    // 降级为「只刷对象、不重扫」。
    LaunchedEffect(entry) {
        subtitleViewModel.bindEntry(entry)
    }

    // 用户在文件浏览页亲手挑了一条字幕。
    //
    // **必须排在上面那个 `bindEntry` 之后**：`bindEntry` 会把手动选择重置回
    // 「自动」（一条手动指定的字幕只对那一条媒体有效），而首次组合时两个
    // `LaunchedEffect` 是按声明顺序启动的——顺序反过来，刚挂上的字幕会被
    // 同一次组合里的重置抹掉，症状是「点了字幕跳过来，面板里却是自动匹配」。
    //
    // 消费（而不是订阅）也是故意的：留着它，用户下次从媒体库进播放页会被再挂一次，
    // 而他这次想要的是自动匹配——「莫名挂上了上次的字幕」是解释不出来的。
    LaunchedEffect(pendingSubtitle) {
        val source = pendingSubtitle ?: return@LaunchedEffect
        subtitleViewModel.selectSource(source)
        onPendingSubtitleApplied()
    }

    // 播放进度 → 字幕 ViewModel。自动翻译就挂在这条线上（“翻到当前位置”），
    // 所以它必须在面板关着的时候也一直跑：用户开的就是「边看边译」。
    // 这里不管节流：onPositionChanged 自己拿 cue 序号去重，同一句只会触发一次。
    LaunchedEffect(positionMs) {
        subtitleViewModel.onPositionChanged(positionMs)
    }

    // 用 LaunchedEffect 而不是直接把值写进去：写入发生在组合期间会造成
    // 「在组合中改 state」，Compose 会直接报错或产生一帧的错色。
    //
    // key 是 artworkAccent 本身，所以换歌（包括换成「没有封面」）都会重新写一次；
    // **离开播放页时不擦掉**——擦了的话，从媒体库回来会看到主题先跳回预设色
    // 再跳回封面色。
    val accentState = LocalArtworkAccentState.current
    LaunchedEffect(artworkAccent) {
        accentState.update(artworkAccent)
    }

    // ------------------------------------------------------------------ 全屏与锁定

    val isLandscape = rememberIsLandscape()
    // 初值取当前方向：横屏进入播放页时如果先按竖屏画一帧再跳成横屏，那是一次
    // 肉眼可见的闪动。`remember` 让它表达的是「这一页的意图」，
    // 转屏之后不会被重新初始化。
    val ui = rememberPlayerUiState(initialFullscreen = isLandscape)
    val windowController = rememberPlayerWindowController()

    // 「横过来」的条件里必须带上「窗口够不够大」。
    //
    // 只看窗口方向是不够的：**画中画小窗自己就是横窗口**（16:9 的小窗在系统
    // 配置里就是 `land`），于是这条规则在整个画中画期间都成立——先在小窗里把
    // `fullscreen` 打开（在那一两百 dp 的窗口里看不出任何问题），退出小窗时那个
    // `true` 就变成一次真的 `SENSOR_LANDSCAPE` 请求：用户手里竖着的手机被锁成
    // 横屏，而他只是把小窗点开又点掉。判定条件与边界见 [PlayerFullscreenRules]。
    val smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp

    // 横过来 = 进全屏。反向不成立：横屏按返回键退出全屏之后设备仍是横屏，
    // 布局不该跳回竖屏那一套（那正是 `isLandscape` 只用来选布局、不用来判断
    // 「是不是全屏」的原因）。
    LaunchedEffect(isLandscape, smallestScreenWidthDp) {
        if (
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = isLandscape,
                smallestScreenWidthDp = smallestScreenWidthDp,
            )
        ) {
            ui.applyFullscreen(true)
        }
    }

    PlayerFullscreenEffect(fullscreen = ui.fullscreen, inPip = ui.inPip)

    // ------------------------------------------------------------------ 画中画

    // 控制器把状态接管在 Activity 上（`PictureInPictureParams` 挂在 Activity 上，
    // 不是挂在组合上）。它在离开播放页时会自己清场（见 `rememberPlayerPipController`），
    // 这是必须的：留下来的 `setAutoEnterEnabled(true)` 会把**整个媒体库**
    // 在用户下一次按 Home 时塞进小窗口。
    val pip = rememberPlayerPipController()

    // 小窗口的进出由系统推过来，不是我们猜的。订阅的是 `ComponentActivity` 上的
    // `addOnPictureInPictureModeChangedListener`，所以不必为了「播放页想知道自己
    // 在不在画中画里」去改 `MainActivity`（播放功能模块也不该认识那个类）。
    DisposableEffect(pip) {
        pip.observeModeChanges(ui::applyPipMode)
        onDispose { pip.stopObservingModeChanges() }
    }

    // 进画中画时把字幕面板收掉。
    //
    // 面板是 `ModalBottomSheet`，在两百 dp 宽的小窗里它会把画面整个盖住；
    // 而小窗里我们不画控制条，用户没有任何地方能点到关闭。
    LaunchedEffect(ui.inPip) {
        if (ui.inPip) showSubtitleSheet = false
    }

    // 画面尺寸与「划走自动进小窗」都顺着这条线推给控制器。
    //
    // `autoEnter` 只在**视频 + 正在播**时为真：音频没有画面，滑回桌面时弹出一个
    // 纯黑小窗口是最没有意义的一种自动行为；暂停时也不自动进（用户暂停多半
    // 是要走了，把暂停的画面缩成小窗只会让他多关一次）。低于 API 31 时这个
    // 参数被控制器忽略（那里没有「自动进入」这回事）。
    LaunchedEffect(state.videoSize, state.isPlaying, entry?.kind, pip) {
        pip.update(
            video = state.videoSize,
            isPlaying = state.isPlaying,
            autoEnter = state.isPlaying && PlayerPipRules.shouldOfferEntry(
                kind = entry?.kind,
                supported = true,
            ),
        )
    }

    PlayerBackHandler(
        locked = ui.locked,
        fullscreen = ui.fullscreen,
        onExitFullscreen = { ui.applyFullscreen(false) },
        onRevealLockedControls = ui::revealLockedControls,
    )

    // 底部导航栏让位。`onDispose` 里一定要还回来：不还的话，用户从全屏播放页
    // 切到设置页，底部导航栏就永远消失了——他会以为应用坏了，而且再也切不回媒体库。
    //
    // 画中画也算让位（看 [PlayerFullscreenRules.shouldShowBottomBar]）：小窗实测
    // w379dp h213dp，而 NavigationBar 固定 80dp——不藏它，小窗就变成「上面一小条
    // 画面 + 下面一条导航」。注意这个 `DisposableEffect` 是**没有旁路**的：
    // 小窗里那条底部栏属于应用外壳，播放页里那句「只画画面」的提前返回管不到它。
    val chrome = LocalAppChrome
    DisposableEffect(ui.fullscreen, ui.inPip) {
        chrome.updateBottomBarVisible(
            PlayerFullscreenRules.shouldShowBottomBar(fullscreen = ui.fullscreen, inPip = ui.inPip),
        )
        onDispose { chrome.updateBottomBarVisible(true) }
    }

    // 控制条自动淡出。只在**播放中**消失：暂停时用户正盯着画面找按钮，
    // 这时候把控制条收走是最气人的一种「智能」。
    LaunchedEffect(ui.controlsVisible, ui.openSheet, ui.locked, state.isPlaying) {
        if (ui.controlsVisible && ui.openSheet == null && !ui.locked && state.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            ui.hideControls()
        }
    }

    LaunchedEffect(ui.lockHintVisible) {
        if (ui.lockHintVisible) {
            delay(LOCK_HINT_TIMEOUT_MS)
            ui.hideLockHint()
        }
    }

    // 手势提示泡的计时。key 是提示本身，所以拖动过程中每变一次都会把计时重置——
    // 手指还按着的时候它不会消失。
    LaunchedEffect(ui.levelHint) {
        if (ui.levelHint != null) {
            delay(GESTURE_HINT_TIMEOUT_MS)
            ui.applyLevelHint(null)
        }
    }

    LaunchedEffect(ui.seekHint) {
        if (ui.seekHint != null) {
            delay(GESTURE_HINT_TIMEOUT_MS)
            ui.applySeekHint(null)
        }
    }

    LaunchedEffect(ui.playPauseHint) {
        if (ui.playPauseHint != null) {
            delay(PLAY_PAUSE_HINT_TIMEOUT_MS)
            ui.applyPlayPauseHint(null)
        }
    }

    // 画面比例 = 「这一部片子临时改过的」优先，否则用设置里的默认值。
    //
    // 这里**不**把临时改动写回设置：看一部老片时裁掉两边是这一部片子的事，
    // 不该让下一部也默认被裁（见 `PlaybackSettings.aspectRatioMode` 的注释）。
    // 存默认值的地方是设置页。
    val aspectRatio = ui.aspectRatio(settings.aspectRatioMode ?: AspectRatioMode.DEFAULT)

    // 长按加速用的倍速。在这里算**一次**，同时交给手势层和提示泡——两边都走
    // 同一个函数，就能保证「提示泡写 2×」和「真的下给内核的 2×」是同一个数。
    val boostSpeed = SpeedBoostOptions.normalize(settings.boostSpeed)

    // 音轨入口只在**多于一条**音轨时出现：单轨片源上那个按钮永远只有一个选项，
    // 点开也做不了任何事，却白占一格（竖屏那排芯片一共才三个位置）。
    //
    // 按钮上的字是**当前这条轨的名字**，所以它同时回答了「现在听的是谁」和
    // 「去哪儿换」；选不中单独一条时（多码率自适应、或还没解析出轨道）写「自动」。
    val audioTrackChip = if (audioTracks.size < 2) {
        null
    } else {
        PlayerBarChip(
            label = audioTrackChipLabel(
                tracks = audioTracks,
                autoLabel = stringResource(R.string.msp_player_audio_auto),
                // 用 `context.getString` 而不是 `stringResource`：这个 lambda 不是
                // composable，而「音轨 3」必须走 `_n` 那个键（带占位符），
                // 不能让两种语言各自拼一遍。
                fallbackOf = { track ->
                    context.getString(R.string.msp_player_audio_track_n, track.indexInGroup + 1)
                },
            ),
            // 不点亮：字已经把状态写全了（和画面比例芯片同一条理由）。
            onClick = { ui.openSheet(PlayerSheet.AUDIO_TRACK) },
        )
    }

    // 睡眠定时入口。
    //
    // 芯片上的字分三种情况：没定时写功能名（「睡眠定时」）、定了时长写**时长**
    // （「30 分钟」/「3 小时 20 分」）、定在集末写「本集结束」。写时长而不是
    // 「已开启」，是因为用户下次看这行时要回答的问题是「我设了多久」。
    //
    // 时长按**总时长**算（`durationLabel`）而不是去档位表里认：自定义出来的
    // 200 分钟不在那张表里，认不出就只剩一句「已开启」可选——恰好没有回答
    // 用户想问的那个问题。
    //
    // **不在这里算倒计时**：那是个每秒都在变的值，写在这里会让整个播放页
    // 每秒重组一次。倒计时的时钟在面板内部（见 `PlayerSleepTimerSheet`）。
    val sleepTimerChip = PlayerBarChip(
        label = when (val timer = sleepTimer) {
            is SleepTimerState.Off -> stringResource(R.string.msp_player_sleep_timer)
            is SleepTimerState.UntilItemEnd -> SleepTimerOptions.untilItemEndLabel().string()
            is SleepTimerState.Countdown -> SleepTimerOptions.durationLabel(timer.totalMs).string()
        },
        // 只有定时真的开着才点亮。“没定时”是默认状态，把它也点亮的话
        // 这个芯片会永远是亮的，亮不亮就不再传递任何信息。
        active = sleepTimer !is SleepTimerState.Off,
        onClick = { ui.openSheet(PlayerSheet.SLEEP_TIMER) },
    )

    // 队列入口。只有一条（或空）时不出现：那时点开来只能看到一个自己和
    // 一个被禁用的「清空队列」，白跑一趟。
    val queueChip = if (queue.size < 2) {
        null
    } else {
        PlayerBarChip(
            label = stringResource(R.string.msp_player_queue_chip, queue.size),
            // 和音轨芯片同一条理由：字已经把信息说全了，不需要再点一个颜色。
            onClick = { ui.openSheet(PlayerSheet.QUEUE) },
        )
    }

    // 画中画入口。
    //
    // 两件事同时成立才给入口：设备支持（有些 ROM 根本没有这个特性）、当前放的是
    // **视频**（音频没有画面可缩）。不需要再判「现在是不是已经在小窗里」——
    // 小窗里这一整行根本不画（见 `PlayerScreen` 里那个 `if (ui.inPip)` 分支）。
    //
    // 判断放在 `PlayerPipRules` 里而不是写在这里，是为了让它能被纯 JVM 单测覆盖
    // ——`PackageManager.hasSystemFeature` 在单测里拿不到。
    val pipChip = if (PlayerPipRules.shouldOfferEntry(entry?.kind, pip.isSupported)) {
        PlayerBarChip(
            label = stringResource(R.string.msp_player_pip),
            // 不点亮：「画中画」是一个**动作**（把画面缩成小窗），不是一个状态。
            // 真要说状态，那是「现在就在小窗里」——可那时这一行根本看不见。
            onClick = { pip.enter(video = state.videoSize, isPlaying = state.isPlaying) },
        )
    } else {
        null
    }

    // 三个会话级入口打包成一个对象再往下穿（见 `PlayerSessionChipSet` 的 KDoc：
    // 平铺三个参数要穿过五层，以后加第四个入口就得改十处）。
    val sessionChips = PlayerSessionChipSet(
        sleepTimer = sleepTimerChip,
        queue = queueChip,
        pip = pipChip,
    )

    // 离开播放页时把加速收掉。
    //
    // 睡眠定时**有意不在这里收**：它是内核状态，用户是在睡觉前设的它，
    // 这一页被摘掉（比如转到媒体库去挑下一首）之后它还要继续生效。
    DisposableEffect(Unit) {
        onDispose { viewModel.setSpeedBoost(false) }
    }

    PlayerScreen(
        state = state,
        entry = entry,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        player = viewModel.player,
        ui = ui,
        aspectRatio = aspectRatio,
        isLandscape = isLandscape,
        windowController = windowController,
        boostSpeed = boostSpeed,
        onSpeedBoost = viewModel::setSpeedBoost,
        subtitleState = subtitleState,
        audioTrackChip = audioTrackChip,
        sessionChips = sessionChips,
        modifier = modifier,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSkipNext = viewModel::skipToNext,
        onSkipPrevious = viewModel::skipToPrevious,
        onSeekTo = viewModel::seekTo,
        onCycleRepeat = viewModel::cycleRepeatMode,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleAbRepeat = viewModel::cycleAbRepeat,
        onOpenSubtitles = { showSubtitleSheet = true },
        onCollapse = onCollapse,
    )

    // 面板放在路由这一层而不是 PlayerScreen 里：它是窗口级的浮层（ModalBottomSheet），
    // 不是页面内容的一部分；放在页面里会被归入「无状态页面」的职责里，
    // 而那个组合函数的全部意义就是「给它什么画什么」。
    if (showSubtitleSheet) {
        SubtitleTrackPicker(
            state = subtitleState,
            translation = translationState,
            exportMessage = exportMessage,
            onDismiss = { showSubtitleSheet = false },
            onSelectMode = subtitleViewModel::setDisplayMode,
            onSelectSource = subtitleViewModel::selectSource,
            onSelectEmbedded = subtitleViewModel::selectEmbeddedTrack,
            onNudgeTimeline = subtitleViewModel::nudgeTimelineOffset,
            onResetTimeline = subtitleViewModel::resetTimelineOffset,
            onSetTextSize = subtitleViewModel::setTextSize,
            onSetLineSpacing = subtitleViewModel::setLineSpacing,
            onSetOutline = subtitleViewModel::setOutline,
            onSetBottomMargin = subtitleViewModel::setBottomMargin,
            onResetStyle = subtitleViewModel::resetStyle,
            onUseAuto = subtitleViewModel::useAutoSelection,
            onRescan = subtitleViewModel::rescan,
            onPickFile = { manualSubtitleLauncher.launch(arrayOf("*/*")) },
            onTranslateAll = subtitleViewModel::translateAll,
            onTranslateUpTo = { subtitleViewModel.translateUpTo(positionMs) },
            onCancelTranslation = subtitleViewModel::cancelTranslation,
            onRetryFailed = subtitleViewModel::retryFailedTranslation,
            onOpenTranslationSettings = onOpenTranslationSettings,
            onExport = { format, mode ->
                pendingExport = format to mode
                exportLauncher.launch(subtitleViewModel.suggestedExportName(format, mode))
            },
            onDismissExportMessage = subtitleViewModel::clearExportMessage,
        )
    }

    when (ui.openSheet) {
        PlayerSheet.SPEED -> PlayerSpeedSheet(
            // 显示的是**内核里真实的值**，不是设置里的持久值：设置里那个是
            // 「下次打开用多少」，而两个值在「刚拨完档位、DataStore 还没回写」
            // 的那一瞬间是不一样的。用户看到必须是前者。
            current = state.playbackSpeed,
            onSelect = { speed ->
                viewModel.setSpeed(speed)
                ui.closeSheet()
            },
            onDismiss = ui::closeSheet,
        )

        PlayerSheet.ASPECT_RATIO -> PlayerAspectRatioSheet(
            current = aspectRatio,
            videoSize = state.videoSize,
            onSelect = { mode ->
                ui.setAspectRatio(mode)
                ui.closeSheet()
            },
            onDismiss = ui::closeSheet,
        )

        PlayerSheet.AUDIO_TRACK -> PlayerAudioTrackSheet(
            tracks = audioTracks,
            // 面板**不**自己关：用户要在这里听效果（配音对不对、5.1 是不是真的在响），
            // 切完就关掉的话他每试一条都要重新点开一次。
            onSelect = viewModel::selectAudioTrack,
            onUseAuto = viewModel::useAutomaticAudioTrack,
            onDismiss = ui::closeSheet,
        )

        PlayerSheet.SLEEP_TIMER -> PlayerSleepTimerSheet(
            state = sleepTimer,
            // 面板说「分钟」，内核说「毫秒」：换算放在接线的这一行。
            // 让面板自己乘 60000 会把单位知识拆到两个地方（档位表在内核里），
            // 两边一旦不一致就是「选了 30 分钟，实际 30 秒」这种看不出来的错。
            onSelectMinutes = { minutes -> viewModel.setSleepTimer(minutes * SleepTimerOptions.MINUTE_MS) },
            onSelectUntilItemEnd = viewModel::setSleepTimerUntilItemEnd,
            onClear = viewModel::cancelSleepTimer,
            onDismiss = ui::closeSheet,
        )

        // 队列面板**不**在点条目后自己关：这个面板的存在意义就是「看着队列
        // 挑一条」，关掉就回到了「盲选」——而挑歌本来就是一件会反复的事。
        PlayerSheet.QUEUE -> PlayerQueueSheet(
            queue = queue,
            currentIndex = currentIndex,
            shuffleEnabled = state.shuffleEnabled,
            onPlay = viewModel::playQueueItem,
            onRemove = viewModel::removeQueueItem,
            onMove = viewModel::moveQueueItem,
            onClear = viewModel::clearQueue,
            onDismiss = ui::closeSheet,
        )

        null -> Unit
    }
}

/**
 * 播放页（无状态）。
 *
 * ## 两套布局
 *
 * 横屏时用**覆盖式**布局（全屏画面 + 自动淡出的控制层），竖屏时用**三段式**
 * （画面在上、控制在下）。这不是省事：横屏的可用高度只有 400dp 出头，把控制条
 * 摆在画面下面等于永久吃掉三分之一；而竖屏的画面本来就只占屏幕中间一块，
 * 再叠一层控制层则会挡住字幕。
 *
 * 决定用哪套的是 [isLandscape]（设备的事实），不是 [PlayerUiState.fullscreen]
 * （用户的意图）：横屏退出全屏之后设备还是横屏，布局不该跳回竖屏那一套。
 *
 * @param player 只交给 `PlayerView` 用。整个页面除了那一处，任何地方都不碰它。
 * @param windowController 手势改亮度/音量用的通道。默认 null = 手势不生效，
 *   这样预览和单测可以直接 omit 它。
 * @param subtitleState 当前字幕状态。默认值是「什么都没挂」，这样预览和单测
 *   可以直接 omit 它。
 */
@Composable
fun PlayerScreen(
    state: MspPlaybackState,
    entry: MediaEntry?,
    positionMs: Long,
    bufferedMs: Long,
    player: Player?,
    ui: PlayerUiState,
    modifier: Modifier = Modifier,
    aspectRatio: AspectRatioMode = AspectRatioMode.DEFAULT,
    isLandscape: Boolean = false,
    windowController: PlayerWindowController? = null,
    boostSpeed: Float = SpeedBoostOptions.DEFAULT,
    subtitleState: SubtitleUiState = SubtitleUiState(),
    /** 音轨入口的芯片配置，`null` = 这个片源没有多条音轨。 */
    audioTrackChip: PlayerBarChip? = null,
    /** 睡眠定时 / 队列 / 画中画三个会话级入口。默认空集 = 一个都不画。 */
    sessionChips: PlayerSessionChipSet = PlayerSessionChipSet(),
    onTogglePlayPause: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onOpenSubtitles: () -> Unit = {},
    onCycleAbRepeat: () -> Unit = {},
    onSpeedBoost: (Boolean) -> Unit = {},
    onCollapse: () -> Unit = {},
) {
    // 手势只在视频上挂。音频页中间是可滚动的歌词/封面，一层吃掉全部触摸的
    // 手势层会和滚动直接抢事件——那种「歌词划不动」的 bug 极难归因。
    //
    // 画中画里也不挂：那类小窗口上的一次误拖就是一次跳转，而进度条在那里根本
    // 看不见（那正是把状态写进 `PlayerUiState` 而不是留在这里的原因）。
    val gesturesEnabled = entry?.kind == MediaKind.VIDEO && !ui.locked && !ui.inPip
    val gestureModifier = Modifier.playerGestures(
        enabled = gesturesEnabled,
        controller = windowController,
        positionMs = positionMs,
        durationMs = state.durationMs,
        // 竖屏的控制条在画面外面、一直可见，轻点不需要做任何事（传 null）。
        onTap = if (isLandscape) ui::toggleControls else null,
        // 双击 = 播放/暂停。
        //
        // 提示说的是**切换之后**的状态：手势回调手上只有「切换前」的状态，而切换
        // 是异步的（命令要走到内核再传回来），等它回来再算会晚一拍。取反就是结果。
        onDoubleTap = {
            val willPlay = !state.isPlaying
            onTogglePlayPause()
            ui.applyPlayPauseHint(willPlay)
        },
        // 拖动中只显示提示，松手才真的跳（见 PlayerGestureModifier 的 KDoc）。
        onSeekPreview = ui::applySeekHint,
        onSeekCommit = onSeekTo,
        onLevelChange = ui::applyLevelHint,
        onSpeedBoost = { boosting ->
            // 提示泡和真正的加速是同一件事的两个面，两者都在同一帧里完成，
            // 不会出现「提示写着 2× 而声音还是原速」。
            ui.applySpeedBoost(if (boosting) boostSpeed else null)
            onSpeedBoost(boosting)
        },
    )

    // 画面层做成一个「接收外框 modifier」的 lambda 在两套布局之间复用：
    // 两种布局对它的要求完全一样（铺满给定的那块地方、字幕贴画面底部），
    // 唯一不同的是给它多大地方。
    val videoLayer: @Composable (Modifier) -> Unit = { layerModifier ->
        PlayerVideoSurface(
            player = player,
            mode = aspectRatio,
            videoSize = state.videoSize,
            isPlaying = state.isPlaying,
            modifier = layerModifier.then(gestureModifier),
        ) {
            // 字幕层叠在画面矩形**里面**（见 PlayerVideoSurface 的 overlay 参数），
            // 所以它跟着画面的实际高度走，而不会跑到黑边里。
            //
            // 这里只留「左右各 16dp」这种与用户设置无关的边距：**离底边多远由字幕样式
            // 决定**，而样式是全局设置、和这里的布局常量不是一回事。两边都写一份的话，
            // 用户把「底部距离」调到最大时，实际位置会是「设置值 + 12dp」——多出来的
            // 那一截没有任何地方能解释。
            SubtitleOverlay(
                state = subtitleState,
                positionMs = positionMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }

    // ---- 画中画里只画画面 ----
    //
    // 系统给的小窗口只有一两百 dp 宽，这一页的常设内容（标题、进度条、两排芯片、
    // 传输控制）在那里既放不下也没法点，排版出来是一团互相压住的碎片。所以那类
    // 窗口里只画**画面那一层**：视频 + 字幕。
    //
    // 字幕留着：窝在小窗里看片时字幕恰恰是最需要的东西。控制条/芯片/面板都不画，
    // 面板尤其不能画（`PlayerRoute` 里那个 `LaunchedEffect(ui.inPip)` 就是为此
    // 把字幕面板收掉的）。
    //
    // 这里复用同一个 `videoLayer`，而不是另写一个只有画面的版本：字幕样式的
    // 挂载点、画面比例的算法、播放器绑定都只有一份，不会再一次出现
    // 「竖屏写了、小窗忘了」的偏差。
    if (ui.inPip) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            videoLayer(Modifier.fillMaxSize())
        }
        return
    }

    if (isLandscape) {
        LandscapeLayout(
            state = state,
            entry = entry,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            ui = ui,
            aspectRatio = aspectRatio,
            subtitleState = subtitleState,
            audioTrackChip = audioTrackChip,
            sessionChips = sessionChips,
            videoLayer = videoLayer,
            modifier = modifier,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeekTo = onSeekTo,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
            onOpenSubtitles = onOpenSubtitles,
            onCycleAbRepeat = onCycleAbRepeat,
        )
    } else {
        PortraitLayout(
            state = state,
            entry = entry,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            ui = ui,
            aspectRatio = aspectRatio,
            subtitleState = subtitleState,
            audioTrackChip = audioTrackChip,
            sessionChips = sessionChips,
            videoLayer = videoLayer,
            modifier = modifier,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeekTo = onSeekTo,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
            onOpenSubtitles = onOpenSubtitles,
            onCycleAbRepeat = onCycleAbRepeat,
            onCollapse = onCollapse,
        )
    }
}

/**
 * 竖屏：画面在上，控制在画面**外面**的一条列。
 *
 * 信息层次按「用户有多需要看它」从上到下排：画面 → 标题/解码方式 → 进度 →
 * 功能 → 传输控制。
 *
 * 左上角还叠着一个「收起」（[PlayerExitChip]），它不在这条层次里：
 * 它不是「看片子」需要的信息，而是这一页的出口。
 */
@Composable
private fun PortraitLayout(
    state: MspPlaybackState,
    entry: MediaEntry?,
    positionMs: Long,
    bufferedMs: Long,
    ui: PlayerUiState,
    aspectRatio: AspectRatioMode,
    subtitleState: SubtitleUiState,
    audioTrackChip: PlayerBarChip?,
    sessionChips: PlayerSessionChipSet,
    videoLayer: @Composable (Modifier) -> Unit,
    modifier: Modifier,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onCycleAbRepeat: () -> Unit,
    onCollapse: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        state.errorMessage?.let { ErrorBanner(it) }

        if (entry == null) {
            NothingPlaying()
            return@Column
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (entry.kind == MediaKind.VIDEO) {
                videoLayer(Modifier.fillMaxSize())
            } else {
                AudioStage(
                    entry = entry,
                    subtitleState = subtitleState,
                    positionMs = positionMs,
                    onSeekTo = onSeekTo,
                )
            }
            if (state.isBuffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            GestureHints(ui = ui, modifier = Modifier.align(Alignment.Center))
            ui.speedBoost?.let { speed ->
                PlayerBoostIndicator(
                    speed = speed,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            // 竖屏的出口。横屏不摆它：那边左上角已经是「退出全屏」的箭头，
            // 两个入口叠在同一个位置只会让人犹疑点哪个（和 PlayerTransportControls
            // 里 `fullscreen = null` 是同一条理由）。
            //
            // 声明在**最后**：同一个 Box 里后声明的节点画在上面、也先参与命中测试，
            // 所以点在按钮上时事件归按钮，不会顺手触发下面手势层的双击播放/暂停。
            PlayerExitChip(
                label = stringResource(R.string.msp_player_collapse),
                onClick = onCollapse,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }

        TrackInfo(entry = entry, decoderKind = state.decoderKind)

        PlayerSeekBar(
            // 时长未知时进度条没有意义（拖了也没目标），直接禁用。
            // 注意这里传的是 `state.durationMs`，不是「缓冲到的位置」：两者含义不同。
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = state.durationMs,
            enabled = state.hasKnownDuration,
            onSeekTo = onSeekTo,
            abRepeat = state.abRepeat,
        )

        PlayerActionChips(
            speed = state.playbackSpeed,
            abRepeat = state.abRepeat,
            onOpenSpeed = { ui.openSheet(PlayerSheet.SPEED) },
            onCycleAbRepeat = onCycleAbRepeat,
            aspectRatio = PlayerBarChip(
                label = aspectRatio.label.string(),
                onClick = { ui.openSheet(PlayerSheet.ASPECT_RATIO) },
            ),
            audioTrack = audioTrackChip,
            modifier = Modifier.fillMaxWidth(),
        )

        // 睡眠定时 / 队列 / 画中画。单独一行（不是并进上面那排芯片）：那一排在
        // 竖屏下已经把宽度用完了，而且这三个是「管这次播放会话」、
        // 不是「调这条媒体」。
        PlayerSessionChips(
            session = sessionChips,
            modifier = Modifier.fillMaxWidth(),
        )

        PlayerTransportControls(
            state = state,
            subtitlesActive = subtitleState.isRendering,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
            onOpenSubtitles = onOpenSubtitles,
            fullscreen = ui.fullscreen,
            onToggleFullscreen = { ui.applyFullscreen(!ui.fullscreen) },
        )
    }
}

/**
 * 横屏：画面铺满，控制层浮在上面自动淡出。
 *
 * 这一层里**没有** `TrackInfo` 那种常驻文字：横屏时每一个常驻元素都是在永久地
 * 吃掉画面。标题和解码方式挪进了顶栏（跟着控制层一起出现/消失）。
 *
 * 唯一的例外是**音频**：它没有画面可吃，于是约束正好反过来——满屏空白才是浪费，
 * 控制条应该常驻。所以音频横屏走 [AudioLandscapeLayout]（左封面、右歌词、
 * 控制压在歌词底下），视频继续走下面这套覆盖层。两种媒体在这里分道扬镳，
 * 而不是硬凑一套能同时待两边的控件。
 */
@Composable
private fun LandscapeLayout(
    state: MspPlaybackState,
    entry: MediaEntry?,
    positionMs: Long,
    bufferedMs: Long,
    ui: PlayerUiState,
    aspectRatio: AspectRatioMode,
    subtitleState: SubtitleUiState,
    audioTrackChip: PlayerBarChip?,
    sessionChips: PlayerSessionChipSet,
    videoLayer: @Composable (Modifier) -> Unit,
    modifier: Modifier,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onCycleAbRepeat: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (entry == null) {
            NothingPlaying()
            return@Box
        }

        if (entry.kind == MediaKind.VIDEO) {
            videoLayer(Modifier.fillMaxSize())
        } else {
            AudioLandscapeLayout(
                state = state,
                entry = entry,
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                ui = ui,
                subtitleState = subtitleState,
                audioTrackChip = audioTrackChip,
                sessionChips = sessionChips,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onSeekTo = onSeekTo,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onOpenSubtitles = onOpenSubtitles,
                onCycleAbRepeat = onCycleAbRepeat,
            )
        }

        if (state.isBuffering) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        GestureHints(ui = ui, modifier = Modifier.align(Alignment.Center))
        ui.speedBoost?.let { speed ->
            PlayerBoostIndicator(
                speed = speed,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // 出错时用居中的小胶囊而不是竖屏那条通栏横幅：横屏的上下两条已经被
        // 控制层占着，通栏横幅会盖住「退出全屏」那个按钮，而错误信息通常要
        // 一直挂到下一次成功播放为止——用户会被彻底关在全屏里出不去。
        state.errorMessage?.let { message ->
            ErrorBadge(message = message, modifier = Modifier.align(Alignment.Center))
        }

        // 覆盖式控制层只给视频。音频横屏的控制条常驻在自己的版面里（见
        // AudioLandscapeLayout），如果再叠一层：
        // 1. 上下就会出现两套「退出全屏」；
        // 2. 那一层淡出之后音频页没有手势层可以把它唤回来（手势层的条件是
        //    `kind == VIDEO`，音频在横屏下根本收不到点击）——这正是 v0.5.12
        //    验证时发现的「控制条消失后找不回来」。常驻是结构性修法。
        if (entry.kind == MediaKind.VIDEO) {
            PlayerControlsOverlay(
                visible = ui.controlsVisible,
                locked = ui.locked,
                lockHintVisible = ui.lockHintVisible,
                state = state,
                entry = entry,
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                speed = state.playbackSpeed,
                aspectRatioLabel = aspectRatio.label.string(),
                subtitlesActive = subtitleState.isRendering,
                audioTrackChip = audioTrackChip,
                sessionChips = sessionChips,
                onExitFullscreen = { ui.applyFullscreen(false) },
                onOpenSpeed = { ui.openSheet(PlayerSheet.SPEED) },
                onOpenAspectRatio = { ui.openSheet(PlayerSheet.ASPECT_RATIO) },
                onCycleAbRepeat = onCycleAbRepeat,
                onToggleLock = { ui.applyLocked(!ui.locked) },
                onRevealLockedControls = ui::revealLockedControls,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onSeekTo = onSeekTo,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onOpenSubtitles = onOpenSubtitles,
            )
        }
    }
}

/**
 * 横屏音频：左边封面与曲目信息，右边歌词，控制条压在歌词底下常驻。
 *
 * ## 为什么音频不能用视频那一套
 *
 * 视频横屏的版面是「画面铺满 + 控制层浮在上面自动淡出」，其中的两条约束（不挡画面、
 * 不留空）对音频都不成立：音频没有画面可挡，而满屏的封面/歌词之间的空白也不该空着。
 * 所以这里反过来：**什么都不淡出**，控制条永远在。
 *
 * ## 为什么控制条不浮起来（不是覆盖层）
 *
 * 视频那套是「常驻 = 永久吃掉画面」，音频没有这个代价；更关键的是，音频横屏没有手势
 * 层（见 `PlayerRoute` 里 `gesturesEnabled` 的条件），浮层一旦淡出就**再也唤不回来**。
 * 用一个 `Row` 把版面真分成两栏、控制条占一条真实的高度，这个 bug 就不存在了：
 * 没有「隐藏」这个状态，也就没有「找回来」这件事。
 *
 * ## 锁定在这里没有意义（但必须处理）
 *
 * 锁定挡的是「拖动亮度/音量」和「双击播放暂停」这两种误触，而这两件事只发生在
 * 有手势层的画面上。音频横屏没有手势层，锁定没有东西可挡。但 `ui.locked` 是
 * `PlayerUiState` 上的状态、跟着播放页活着：从视频横屏带着 locked=true 转过来，
 * 这一页既不画锁定按钮也不会解析放按钮，用户就永远解不开了。所以进来主动解掉。
 */
@Composable
private fun AudioLandscapeLayout(
    state: MspPlaybackState,
    entry: MediaEntry,
    positionMs: Long,
    bufferedMs: Long,
    ui: PlayerUiState,
    subtitleState: SubtitleUiState,
    audioTrackChip: PlayerBarChip?,
    sessionChips: PlayerSessionChipSet,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onCycleAbRepeat: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (ui.locked) ui.applyLocked(false)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左栏固定占 1/3：封面是正方形的，宽度定下来它的高度也就定下来了，
        // 剩下两栏不用跟着封面换算。
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            val artworkSize = AudioLandscapeRules.artworkSize(
                availableWidth = maxWidth,
                availableHeight = maxHeight,
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 出口。位置和视频横屏的「退出全屏」**在同一个角**（左上），
                // 只是那一处是跟着覆盖层淡出的，这里常驻。
                PlayerExitChip(
                    label = stringResource(R.string.msp_player_exit_fullscreen),
                    onClick = { ui.applyFullscreen(false) },
                    modifier = Modifier.align(Alignment.Start).padding(top = 8.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AudioArtwork(entry = entry, size = artworkSize)
                    // 和竖屏是同一个 composable：标题 + 「格式 · 解码方式」。
                    // 复用而不是另写一份，就不会出现「竖屏写了、横屏忘了」的偏差。
                    TrackInfo(entry = entry, decoderKind = state.decoderKind)
                }
            }
        }

        // 右栏：歌词（占满剩余高度）+ 常驻控制条。
        Column(modifier = Modifier.weight(2f).fillMaxHeight()) {
            val slot = AudioLandscapeRules.lyricsSlot(subtitleState)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (slot) {
                    is LyricsSlot.Lines -> LyricsPane(
                        document = slot.document,
                        positionMs = positionMs,
                        mode = subtitleState.effectiveMode,
                        onSeekTo = onSeekTo,
                        modifier = Modifier.fillMaxSize(),
                        timelineOffsetMs = subtitleState.timelineOffsetMs,
                    )

                    is LyricsSlot.Notice -> LyricsNotice(
                        kind = slot.kind,
                        onOpenSubtitles = onOpenSubtitles,
                    )
                }

                // 只在真的在画歌词时铺：说明态是居中的一块，本来就不贴边，
                // 铺上去反而会把那句说明的上下沾上一层灰。
                if (slot is LyricsSlot.Lines) {
                    val fade = MaterialTheme.colorScheme.surface
                    LyricEdgeFade(Alignment.TopCenter, listOf(fade, Color.Transparent))
                    LyricEdgeFade(Alignment.BottomCenter, listOf(Color.Transparent, fade))
                }
            }

            AudioLandscapeControls(
                state = state,
                ui = ui,
                subtitleState = subtitleState,
                audioTrackChip = audioTrackChip,
                sessionChips = sessionChips,
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onSeekTo = onSeekTo,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onOpenSubtitles = onOpenSubtitles,
                onCycleAbRepeat = onCycleAbRepeat,
            )
        }
    }
}

/**
 * 横屏音频歌词区上下两条渐变的高度。
 *
 * 歌词是可滚动的，视口上下必然切在某一行中间。硬切看上去像渲染错了：
 * 下半行正好贴在控制条上，像被压掉一块。铺一条渐变就变成「这一行滑出去了」。
 */
private val LYRIC_EDGE_FADE = 28.dp

/**
 * 歌词区一条边缘渐变。
 *
 * 铺在歌词**上面**，但只有 `background`、没有任何指针输入，所以不参与命中测试：
 * 歌词行还是能点（点歌词跳到那一句），渐变不会变成一层挡手的膜。
 *
 * 颜色由调用方给全：写「顶边从底色到透明、底边从透明到底色」比在这里用
 * `Alignment` 反推方向读起来直接。
 */
@Composable
private fun BoxScope.LyricEdgeFade(edge: Alignment, colors: List<Color>) {
    Box(
        modifier = Modifier
            .align(edge)
            .fillMaxWidth()
            .height(LYRIC_EDGE_FADE)
            .background(Brush.verticalGradient(colors)),
    )
}

/**
 * 横屏音频右栏底部的常驻控制条：进度 → 倍速/A-B → 传输控制。
 *
 * 里面**没有**画面比例按钮：音频没有画面比例可调，摆出来只会让人点开一个
 * 永远无效的面板（呼应 [PlayerActionChips] 里那个可选参数）。
 *
 * 铺一层很淡的 `surfaceVariant`：不为了好看，而是让「这一条是控件区」和上面
 * 可滚动的歌词在视觉上分开——歌词自己也有点按高亮，两者同色会糊成一片。
 */
@Composable
private fun AudioLandscapeControls(
    state: MspPlaybackState,
    ui: PlayerUiState,
    subtitleState: SubtitleUiState,
    audioTrackChip: PlayerBarChip?,
    sessionChips: PlayerSessionChipSet,
    positionMs: Long,
    bufferedMs: Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onCycleAbRepeat: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            PlayerSeekBar(
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                durationMs = state.durationMs,
                enabled = state.hasKnownDuration,
                onSeekTo = onSeekTo,
                abRepeat = state.abRepeat,
            )
            PlayerActionChips(
                speed = state.playbackSpeed,
                abRepeat = state.abRepeat,
                onOpenSpeed = { ui.openSheet(PlayerSheet.SPEED) },
                onCycleAbRepeat = onCycleAbRepeat,
                // 音频横屏不摆画面比例（没画面可调），但音轨照摆：
                // 多音轨的音频文件（不同语言的配音、不同音质）正是最需要切的地方。
                audioTrack = audioTrackChip,
                modifier = Modifier.fillMaxWidth(),
            )
            PlayerTransportControls(
                state = state,
                subtitlesActive = subtitleState.isRendering,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onCycleRepeat = onCycleRepeat,
                onToggleShuffle = onToggleShuffle,
                onOpenSubtitles = onOpenSubtitles,
                // 这一行不提供全屏按钮：横屏**就是**全屏，左上角那个「退出全屏」
                // 才是有意义的那一个（和视频横屏同一条理由）。
                fullscreen = null,
                onToggleFullscreen = {},
                compact = true,
            )
            // 音频横屏的控制条是常驻的（不淡出），所以这几行不担心「隐藏时看不见」：
            // 用户随时能看到定时还开着没有、队列里还有多少首。
            PlayerSessionChips(
                session = sessionChips,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 右栏「没有歌词可看」时的说明 + 去选字幕的入口。
 *
 * 说明和入口是两个东西：说明回答「现在是什么情况」（四种，见 [LyricsPlaceholder]），
 * 入口回答「我能做什么」。只给说明，用户得自己去上面那排图标里找字幕；只给入口，
 * 用户不知道自己是「没有歌词」还是「歌词坏了」。
 */
@Composable
private fun LyricsNotice(
    kind: LyricsPlaceholder,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Subtitles,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(kind.messageRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(
            onClick = onOpenSubtitles,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.msp_player_lyrics_pick),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 手势反馈：亮度/音量提示、拖动进度提示、双击播放/暂停提示。
 *
 * 三者都放在画面正中间：手指在屏幕上下两端拖动时，中间的提示不会被手挡住。
 * 它们画在**同一个位置**上，所以 [PlayerUiState] 保证同一时刻最多只有一个非空
 * （见那边的 `applyXxx`）。长按加速的提示泡不在这个组合里（它在顶部），
 * 见 [PlayerBoostIndicator]。
 */
@Composable
private fun GestureHints(ui: PlayerUiState, modifier: Modifier = Modifier) {
    ui.levelHint?.let { hint ->
        PlayerLevelIndicator(hint = hint, modifier = modifier)
    }
    ui.seekHint?.let { hint ->
        PlayerSeekIndicator(hint = hint, modifier = modifier)
    }
    ui.playPauseHint?.let { playing ->
        PlayerPlayPauseIndicator(playing = playing, modifier = modifier)
    }
}

/**
 * 进度提示：拖动中显示「会跳到 00:52 / 01:00」。
 *
 * 拖动本身已经能说明手势被识别了（画面上的进度条在动），但它**说不出落点**，
 * 而手指正按在画面上、想看的位置常常就在手指底下。这个胶囊就是那个落点。
 */
@Composable
private fun PlayerSeekIndicator(hint: PlayerSeekHint, modifier: Modifier = Modifier) {
    // deltaMs == 0（拖出去又拖回原处）算「前进」：此时显示的是「+0 秒」，
    // 方向和数字都是诚实的，没必要为这一个中间态再造一个图标。
    val forward = hint.deltaMs >= 0
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = null,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${TimeFormat.clock(hint.targetMs)} / ${TimeFormat.clock(hint.durationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = signedSeconds(hint.deltaMs).string(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 双击播放/暂停的提示。
 *
 * 用「图标 + 文字」而不是只弹一个大图标：播放/暂停这两个状态是互斥的，一个
 * Pause 图标单看只能说「你点到了某个按钮」，而「已暂停」这三个字把状态一起说
 * 清楚——它正是用户双击之后最需要确认的那件事。
 *
 * @param playing true = 刚切成播放中，false = 刚切成已暂停。
 */
@Composable
private fun PlayerPlayPauseIndicator(playing: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = null,
            )
            Text(
                text = stringResource(
                    if (playing) R.string.msp_player_playing else R.string.msp_player_paused,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * 「2× 加速中」的提示。
 *
 * 加速是一个**没有别的反馈**的状态：画面完全没变，声音变快了很容易被当成
 * 「手机卡了」或者「音画不同步」。这个胶囊是唯一能把这三种情况区分开的东西，
 * 所以它得一直挂着（不像其他提示泡那样几秒后自己消失）。
 *
 * 放在顶部而不是中间：长按的时候手指是不动的，不需要避开；而中间那块要留给
 * 「拖进度」的提示，两者虽然不会同时出现，但位置固定下来比猜「现在会不会撞"更可靠。
 */
@Composable
private fun PlayerBoostIndicator(speed: Float, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(
                R.string.msp_player_boost,
                SpeedBoostOptions.format(speed),
            ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 「+12 秒」/「-8 秒」/「0 秒」。正负号由各自的资源带，因为中文用「+/-」、
 * 而「秒」字在不同语言里的位置也不一样。 */
private fun signedSeconds(deltaMs: Long): MspText {
    val seconds = abs(deltaMs) / 1000
    return when {
        deltaMs > 0L -> MspText.Res(R.string.msp_player_offset_ahead, seconds)
        deltaMs < 0L -> MspText.Res(R.string.msp_player_offset_behind, seconds)
        else -> MspText.Res(R.string.msp_player_offset_zero)
    }
}

/**
 * 音频页的主体：封面 + 歌词。
 *
 * 有歌词时封面**缩小并上移**，把中间那块让给歌词；没歌词时保持原来那个大封面。
 * 不做「封面 + 歌词叠加」是因为两条信息会互相遮：封面是图，歌词是字。
 */
@Composable
private fun AudioStage(
    entry: MediaEntry,
    subtitleState: SubtitleUiState,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val document = subtitleState.document
    if (!subtitleState.isRendering || document == null) {
        AudioArtwork(entry = entry)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AudioArtwork(
            entry = entry,
            size = 112.dp,
            modifier = Modifier.padding(top = 12.dp),
        )
        LyricsPane(
            document = document,
            positionMs = positionMs,
            mode = subtitleState.effectiveMode,
            onSeekTo = onSeekTo,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * 音频封面占位。
 *
 * TODO(封面): 拿到 `entry.artworkUri` 后用 `ContentResolver.loadThumbnail()` 取内嵌封面，
 * 再用 palette-ktx 从封面里取主题色。现在先只画一个音符。
 */
@Composable
private fun AudioArtwork(
    entry: MediaEntry,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 图标跟着封面等比缩：写死 96dp 的话，缩小的封面里会被音符撑满。
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

@Composable
private fun TrackInfo(
    entry: MediaEntry,
    decoderKind: MspDecoderKind,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // 解码方式那一行的文字由 decoderLabelOf 统一提供（横屏的顶栏用的是同一份），
        // 为空时整行都不画，不留一行占位的空白。
        //
        // 只有「软件解码真的介入了」才会显示（见 decoderLabelOf）：硬件解码和
        // FFmpeg 解出来的画面长一样，这行字唯一的用处就是回答「FFmpeg 到底有没有生效」。
        val details = buildList {
            entry.subtitle.takeIf { it.isNotBlank() }?.let { add(MspText.Plain(it)) }
            decoderLabelOf(decoderKind)?.let { add(it) }
        }
        if (details.isNotEmpty()) {
            Text(
                text = MspText.join(TRACK_SEPARATOR, details).string(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 标题行里片名和字幕信息之间的分隔符。用资源而不是代码里的字面量：
 * 中文用全角间隔号（字宽紧凑），英文用半角加空格，否则会出现字挤在一起或空隙过大。 */
private val TRACK_SEPARATOR: MspText = MspText.Res(R.string.msp_player_track_sep)

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 横屏用的紧凑错误提示，见 [LandscapeLayout] 里那段「为什么不用通栏横幅」。 */
@Composable
private fun ErrorBadge(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                // 上限三行：再长的错误信息（比如一长串编解码器名字）会把画面
                // 整个盖住，而用户需要的是「知道出错了」，细节在日志里。
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NothingPlaying() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.msp_player_nothing_playing),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.msp_player_nothing_playing_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
