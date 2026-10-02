package com.multisuperplayer.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.player.MspDecoderKind
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.ui.chrome.LocalAppChrome
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
 * 亮度/音量提示泡、以及双击快进提示留在屏幕上的时长。
 *
 * 比控制条短得多：这两个是**手指还在屏幕上的操作**的反馈，手指一松就该让开。
 */
private const val GESTURE_HINT_TIMEOUT_MS = 800L

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
) {
    val viewModel: PlayerViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entry by viewModel.currentEntry.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val bufferedMs by viewModel.bufferedPositionMs.collectAsStateWithLifecycle()
    val artworkAccent by viewModel.artworkAccent.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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

    // 直接把「哪条媒体」推给字幕 ViewModel，而不是让它去订阅播放内核：
    // 这个 LaunchedEffect 就是两者之间唯一的连接点，读代码时一眼能看到。
    // key 用整个 entry（而不是 entry.id）：换歌、以及同一条片子重新构造
    // （队列刷新）都会重新绑一次，而 `bindEntry` 自己会把同 id 的那一次
    // 降级为「只刷对象、不重扫」。
    LaunchedEffect(entry) {
        subtitleViewModel.bindEntry(entry)
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

    // 横过来 = 进全屏。反向不成立：横屏按返回键退出全屏之后设备仍是横屏，
    // 布局不该跳回竖屏那一套（那正是 `isLandscape` 只用来选布局、不用来判断
    // 「是不是全屏」的原因）。
    LaunchedEffect(isLandscape) {
        if (isLandscape) ui.applyFullscreen(true)
    }

    PlayerFullscreenEffect(fullscreen = ui.fullscreen)

    PlayerBackHandler(
        locked = ui.locked,
        fullscreen = ui.fullscreen,
        onExitFullscreen = { ui.applyFullscreen(false) },
        onRevealLockedControls = ui::revealLockedControls,
    )

    // 底部导航栏让位。`onDispose` 里一定要还回来：不还的话，用户从全屏播放页
    // 切到设置页，底部导航栏就永远消失了——他会以为应用坏了，而且再也切不回媒体库。
    val chrome = LocalAppChrome
    DisposableEffect(ui.fullscreen) {
        chrome.updateBottomBarVisible(!ui.fullscreen)
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

    // 画面比例 = 「这一部片子临时改过的」优先，否则用设置里的默认值。
    //
    // 这里**不**把临时改动写回设置：看一部老片时裁掉两边是这一部片子的事，
    // 不该让下一部也默认被裁（见 `PlaybackSettings.aspectRatioMode` 的注释）。
    // 存默认值的地方是设置页。
    val aspectRatio = ui.aspectRatio(settings.aspectRatioMode ?: AspectRatioMode.DEFAULT)

    // 长按加速用的倍速。在这里算**一次**，同时交给手势层和提示泡——两边都走
    // 同一个函数，就能保证「提示泡写 2×」和「真的下给内核的 2×」是同一个数。
    val boostSpeed = SpeedBoostOptions.normalize(settings.boostSpeed)

    // 离开播放页时把加速收掉。
    //
    // 手势层里的 `finally` 已经覆盖了绝大多数情况（协程被取消时会跑），但那是
    // 「手势协程生命周期」的保证，而页面被换掉时还可能有别的顺序（比如这页的
    // 组合先被丢掉、ViewModel 后清）。速度是全局的播放状态，漏收的后果是
    // 「回到媒体库还在 2 倍速放着」——用户只会把它描述成「播放器抽风了」。
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
        modifier = modifier,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSkipNext = viewModel::skipToNext,
        onSkipPrevious = viewModel::skipToPrevious,
        onSeekTo = viewModel::seekTo,
        onSeekBy = viewModel::seekBy,
        onCycleRepeat = viewModel::cycleRepeatMode,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleAbRepeat = viewModel::cycleAbRepeat,
        onOpenSubtitles = { showSubtitleSheet = true },
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
            onUseAuto = subtitleViewModel::useAutoSelection,
            onRescan = subtitleViewModel::rescan,
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
    onTogglePlayPause: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onSeekBy: (Long) -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onOpenSubtitles: () -> Unit = {},
    onCycleAbRepeat: () -> Unit = {},
    onSpeedBoost: (Boolean) -> Unit = {},
) {
    // 手势只在视频上挂。音频页中间是可滚动的歌词/封面，一层吃掉全部触摸的
    // 手势层会和滚动直接抢事件——那种「歌词划不动」的 bug 极难归因。
    val gesturesEnabled = entry?.kind == MediaKind.VIDEO && !ui.locked
    val gestureModifier = Modifier.playerGestures(
        enabled = gesturesEnabled,
        controller = windowController,
        positionMs = positionMs,
        durationMs = state.durationMs,
        // 竖屏的控制条在画面外面、一直可见，轻点不需要做任何事（传 null）。
        onTap = if (isLandscape) ui::toggleControls else null,
        onDoubleTap = { side ->
            val delta = if (side == PlayerGestures.Side.LEFT) {
                -PlayerGestures.DOUBLE_TAP_SEEK_MS
            } else {
                PlayerGestures.DOUBLE_TAP_SEEK_MS
            }
            onSeekBy(delta)
            // 双击只知道「跳了 10 秒」，落点由内核算——所以不填 targetMs。
            ui.applySeekHint(PlayerSeekHint(deltaMs = delta))
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
            SubtitleOverlay(
                state = subtitleState,
                positionMs = positionMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
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
    }
}

/**
 * 竖屏：画面在上，控制在画面**外面**的一条列。
 *
 * 信息层次按「用户有多需要看它」从上到下排：画面 → 标题/解码方式 → 进度 →
 * 功能 → 传输控制。
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
            aspectRatioLabel = aspectRatio.label,
            abRepeat = state.abRepeat,
            onOpenSpeed = { ui.openSheet(PlayerSheet.SPEED) },
            onOpenAspectRatio = { ui.openSheet(PlayerSheet.ASPECT_RATIO) },
            onCycleAbRepeat = onCycleAbRepeat,
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

        // 出错时用居中的小胶囊而不是竖屏那条通栏横幅：横屏的上下两条已经被
        // 控制层占着，通栏横幅会盖住「退出全屏」那个按钮，而错误信息通常要
        // 一直挂到下一次成功播放为止——用户会被彻底关在全屏里出不去。
        state.errorMessage?.let { message ->
            ErrorBadge(message = message, modifier = Modifier.align(Alignment.Center))
        }

        PlayerControlsOverlay(
            visible = ui.controlsVisible,
            locked = ui.locked,
            lockHintVisible = ui.lockHintVisible,
            state = state,
            entry = entry,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            speed = state.playbackSpeed,
            aspectRatioLabel = aspectRatio.label,
            subtitlesActive = subtitleState.isRendering,
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

/**
 * 手势反馈：亮度/音量提示、拖动进度提示。
 *
 * 两者都放在画面正中间：手指在屏幕的上下两端拖动时，中间的提示不会被手挡住。
 * 长按加速的提示泡不在这个组合里（它在顶部），见 [PlayerBoostIndicator]。
 */
@Composable
private fun GestureHints(ui: PlayerUiState, modifier: Modifier = Modifier) {
    ui.levelHint?.let { hint ->
        PlayerLevelIndicator(hint = hint, modifier = modifier)
    }
    ui.seekHint?.let { hint ->
        PlayerSeekIndicator(hint = hint, modifier = modifier)
    }
}

/**
 * 进度提示：双击时是「快进 10 秒」，水平拖动时是「会跳到 00:52 / 01:00」。
 *
 * 双击跳转本身在进度条上是看得见的（全屏时那条进度条正跟着控制层一起出现），
 * 但**全屏且控制层已淡出**的时候画面里什么都没有，用户会怀疑「是不是没反应」。
 * 这个胶囊就是那个「有反应」。对水平拖动它还多一层作用：手指正按在画面上，
 * 想看的位置常常就在手指底下，而这里写着松手会落到哪里。
 */
@Composable
private fun PlayerSeekIndicator(hint: PlayerSeekHint, modifier: Modifier = Modifier) {
    // deltaMs == 0（拖出去又拖回原处）算「快进」：此时显示的是「+0 秒」，
    // 方向和数字都是诚实的，没必要为这一个中间态再造一个图标。
    val forward = hint.deltaMs >= 0
    val target = hint.targetMs
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
            if (target == null) {
                // 双击：只知道「跳了多少」，落点由内核算，不猜。
                Text(
                    text = "${abs(hint.deltaMs) / 1000} 秒",
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${TimeFormat.clock(target)} / ${TimeFormat.clock(hint.durationMs)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = signedSeconds(hint.deltaMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
            text = "${SpeedBoostOptions.format(speed)} 加速中",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 「+12 秒」/「-8 秒」/「0 秒」。 */
private fun signedSeconds(deltaMs: Long): String {
    val seconds = abs(deltaMs) / 1000
    return when {
        deltaMs > 0L -> "+$seconds 秒"
        deltaMs < 0L -> "-$seconds 秒"
        else -> "0 秒"
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
private fun TrackInfo(entry: MediaEntry, decoderKind: MspDecoderKind) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
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
        val line = listOfNotNull(entry.subtitle.takeIf { it.isNotBlank() }, decoderLabelOf(decoderKind))
            .joinToString("　·　")
        if (line.isNotBlank()) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

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
            text = "没有正在播放的内容",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "到「媒体库」里选一条音频或视频即可开始播放。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
