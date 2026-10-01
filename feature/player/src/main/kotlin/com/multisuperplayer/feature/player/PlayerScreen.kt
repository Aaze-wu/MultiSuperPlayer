package com.multisuperplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.MspRepeatMode
import com.multisuperplayer.core.player.progressOf
import com.multisuperplayer.core.ui.theme.LocalArtworkAccentState
import org.koin.androidx.compose.koinViewModel

/**
 * 播放页入口（有状态）。
 *
 * 除了转发状态，它还负责把「当前封面的取色结果」推给最外层的主题
 * （见 [LocalArtworkAccentState]）。这件事必须在这一层做：只有这里同时知道
 * 「哪个是当前条目」和「主题的最外层在哪」。
 */
@Composable
fun PlayerRoute(modifier: Modifier = Modifier) {
    val viewModel: PlayerViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entry by viewModel.currentEntry.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val bufferedMs by viewModel.bufferedPositionMs.collectAsStateWithLifecycle()
    val artworkAccent by viewModel.artworkAccent.collectAsStateWithLifecycle()

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

    PlayerScreen(
        state = state,
        entry = entry,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        player = viewModel.player,
        modifier = modifier,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSkipNext = viewModel::skipToNext,
        onSkipPrevious = viewModel::skipToPrevious,
        onSeekTo = viewModel::seekTo,
        onCycleRepeat = viewModel::cycleRepeatMode,
        onToggleShuffle = viewModel::toggleShuffle,
    )
}

/**
 * 播放页（无状态）。
 *
 * @param player 只交给 `PlayerView` 用。整个页面除了那一处，任何地方都不碰它。
 */
@Composable
fun PlayerScreen(
    state: MspPlaybackState,
    entry: MediaEntry?,
    positionMs: Long,
    bufferedMs: Long,
    player: Player?,
    modifier: Modifier = Modifier,
    onTogglePlayPause: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        state.errorMessage?.let { ErrorBanner(it) }

        if (entry == null) {
            NothingPlaying()
            return@Column
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (entry.kind == MediaKind.VIDEO) {
                VideoSurface(player = player, isPlaying = state.isPlaying)
            } else {
                AudioArtwork(entry = entry)
            }
            if (state.isBuffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        TrackInfo(entry = entry)

        SeekBar(
            // 时长未知时进度条没有意义（拖了也没目标），直接禁用。
            // 注意这里传的是 `state.durationMs`，不是「缓冲到的位置」：两者含义不同。
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = state.durationMs,
            enabled = state.hasKnownDuration,
            onSeekTo = onSeekTo,
        )

        TransportControls(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
        )
    }
}

/**
 * 视频画面。
 *
 * `useController = false`：控件由我们自己用 Compose 画。开着 Media3 自带的控件，
 * 它会和 Compose 的控制条同时出现在屏幕上，而且两套 UI 的显隐逻辑会互相打架
 * （Media3 的控件按触摸事件自己淡入淡出，Compose 那边完全不知情）。
 *
 * 之所以还要用 `PlayerView` 而不是 `SurfaceView`：字幕渲染、画面比例、
 * 抗锯齿这些都在 `PlayerView` 里做好了，自己写一层等于重新踩一遍它的坑。
 */
@Composable
private fun VideoSurface(player: Player?, isPlaying: Boolean) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(false)
            }
        },
        update = { view ->
            // 必须先判断再赋值：`player` 的 setter 每次都会触发一次
            // SurfaceView 的重新绑定，无条件赋值等于每次重组都闪一下黑屏。
            if (view.player !== player) view.player = player
            view.keepScreenOn = isPlaying
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
}

/**
 * 音频封面占位。
 *
 * TODO(封面): 拿到 `entry.artworkUri` 后用 `ContentResolver.loadThumbnail()` 取内嵌封面，
 * 再用 palette-ktx 从封面里取主题色。现在先只画一个音符。
 */
@Composable
private fun AudioArtwork(entry: MediaEntry) {
    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(96.dp),
        )
    }
}

@Composable
private fun TrackInfo(entry: MediaEntry) {
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
        val subtitle = entry.subtitle
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 进度条 + 两端时间。
 *
 * 拖动时用**本地**值覆盖真实位置：播放中位置每 200ms 变一次，如果滑块直接绑
 * `positionMs`，用户正在拖的那一下会被随后的刷新抢回去，表现为「拖不动/回弹」。
 * 松手才真正 seek，然后把本地值清掉交还给真实位置。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
) {
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val progress = draggingValue ?: progressOf(positionMs, durationMs)
    val bufferedProgress = progressOf(bufferedMs, durationMs)
    val displayMs = if (draggingValue != null) (progress * durationMs).toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box {
            // 二级进度条（已缓冲）画在滑块下面。用一条细线而不是再放一个 Slider：
            // 两个 Slider 叠加时长按/拖拽的手势会互相抢，只有一个能真正工作。
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedProgress)
                    .height(2.dp)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Slider(
                value = progress,
                onValueChange = { draggingValue = it },
                onValueChangeFinished = {
                    draggingValue?.let { onSeekTo((it * durationMs).toLong()) }
                    draggingValue = null
                },
                enabled = enabled,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = TimeFormat.clock(displayMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (enabled) TimeFormat.clock(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportControls(
    state: MspPlaybackState,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (state.shuffleEnabled) "关闭随机播放" else "开启随机播放",
                tint = if (state.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        FilledTonalIconButton(onClick = onSkipPrevious) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                modifier = Modifier.size(32.dp),
            )
        }

        FilledTonalIconButton(onClick = onSkipNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
        }

        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = if (state.repeatMode == MspRepeatMode.ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = when (state.repeatMode) {
                    MspRepeatMode.OFF -> "循环已关闭"
                    MspRepeatMode.ALL -> "列表循环"
                    MspRepeatMode.ONE -> "单曲循环"
                },
                tint = if (state.repeatMode == MspRepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
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
