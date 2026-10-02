package com.multisuperplayer.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.AbRepeatState
import com.multisuperplayer.core.player.MspDecoderKind
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.MspRepeatMode
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.progressOf
import com.multisuperplayer.core.ui.text.string

/**
 * 解码方式的显示文字。
 *
 * 只有「软件解码真的介入了」才写出来。
 *
 * 这条信息存在的唯一意义是回答「FFmpeg 到底有没有生效」——用户排查花屏/变色/
 * 放不了时最需要知道的一件事，而它恰好是界面完全看不出来的：硬件解码和 FFmpeg
 * 解出来的画面长一样，只有这时候不一样。正常硬件解码时写「硬件解码」反而会把
 * 两行字的地方填满废话。
 *
 * 定义在这里而不是放在某一个界面文件里：它同时出现在竖屏的标题区和横屏的顶栏，
 * 两处各写一份的话，迟早会出现「同一部片子，横屏说 FFmpeg、竖屏说系统软件解码」。
 */
internal fun decoderLabelOf(decoderKind: MspDecoderKind): MspText? = when (decoderKind) {
    MspDecoderKind.FFMPEG -> MspText.Res(R.string.msp_player_decoder_ffmpeg)
    MspDecoderKind.SYSTEM_SOFTWARE -> MspText.Res(R.string.msp_player_decoder_system)
    MspDecoderKind.HARDWARE, MspDecoderKind.UNKNOWN -> null
}

/**
 * 进度条 + 两端时间 + A-B 循环标记。
 *
 * 拖动时用**本地**值覆盖真实位置：播放中位置每 200ms 变一次，如果滑块直接绑
 * `positionMs`，用户正在拖的那一下会被随后的刷新抢回去，表现为「拖不动/回弹」。
 * 松手才真正 seek，然后把本地值清掉交还给真实位置。
 */
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    abRepeat: AbRepeatState = AbRepeatState.None,
) {
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val progress = draggingValue ?: progressOf(positionMs, durationMs)
    val bufferedProgress = progressOf(bufferedMs, durationMs)
    val displayMs = if (draggingValue != null && durationMs > 0) (progress * durationMs).toLong() else positionMs

    val markerColor = MaterialTheme.colorScheme.primary
    val markers = remember(abRepeat, durationMs) {
        if (durationMs <= 0L) {
            emptyList()
        } else {
            listOfNotNull(abRepeat.startMs, abRepeat.endMs).map { (it.toFloat() / durationMs).coerceIn(0f, 1f) }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            // A-B 的两个标记用 `drawBehind` 画在滑块**底下**：单独放两个 1dp 宽的
            // Box 再靠 offset 定位也行，但那样要自己去猜轨道在垂直方向的位置，
            // 而轨道的高度是 Material3 内部实现（换一个版本就会错位）。
            // 这里只画相对高度的竖线，不依赖轨道的具体几何。
            modifier = Modifier.fillMaxWidth().drawBehind {
                if (markers.isEmpty()) return@drawBehind
                val strokeWidth = 2.dp.toPx()
                val markerHeight = 16.dp.toPx()
                val top = (size.height - markerHeight) / 2f
                markers.forEach { fraction ->
                    drawRect(
                        color = markerColor,
                        topLeft = Offset(x = fraction * size.width - strokeWidth / 2f, y = top),
                        size = Size(strokeWidth, markerHeight),
                    )
                }
            },
        ) {
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

/**
 * 竖屏画面左上角的「收起」。
 *
 * ## 它解决的是什么问题
 *
 * 离开播放页的办法本来只有两个：系统返回手势/按键，或者点底部导航的某个标签。
 * 前者在竖屏下没有任何可见的提示（横屏至少还有一个「退出全屏」的箭头），
 * 用户刚换手机、或者手势导航设置不一样时就会**找不到出口**；后者虽然能走，
 * 但它看起来像「切标签」而不是「把播放器收起来」——两件事的后果也确实不一样
 * （切标签是丢掉播放页，收起是回到刚才那个列表，两者都会继续播放）。
 *
 * 所以给它一个和横屏左上角那个箭头**同一个位置**的出口：图标 + 文字。
 *
 * ## 为什么带文字，而不是只有一个箭头
 *
 * 图标用的是和横屏左上角那个「退出全屏」**同一个箭头**：两处都是「离开这一页」，
 * 换一个图标只会让人以为两个按钮干的是不同的事。但箭头本身只能表达「回上一页」，
 * 而这里真正的后果是「播放**不停**，只是把这一页收起来」，所以旁边写一个字。
 * 这和倍速/比例那三个按钮的取舍一样（见 [PlayerActionChips]）：
 * 存在歧义的地方，写出来是零成本的。
 *
 * 半透明底是必须的：它压在画面（或者音频封面/歌词）上，而那张图的颜色不由我们决定。
 * 底色和横屏控制层用同一个 `surface`：这个按钮只是「换个地方」，不是
 * 一个需要被注意到的动作，抢眼反而会盖住画面里正在发生的事。
 *
 * @param onClick 由路由层决定（弹出播放页，回到刚才那个列表）。
 */
@Composable
internal fun PlayerCollapseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.msp_player_collapse)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                // 左边留得少、右边留得多：箭头自己就有一圈空白，再套一层内边距
                // 会让「收起」两个字看上去离图标很远。
                .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                // 无障碍读出来的应该是这个动作（「收起」），而不是箭头图标的
                // 「返回」之类的默认描述——所以图标自己不带描述。
                .semantics { contentDescription = label },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 播放控制按钮行。
 *
 * @param fullscreen 传入 `null` 表示这一行不提供全屏按钮（横屏下已经有退出的入口，
 *   两个入口并排只会让人犹豫点哪个）。
 * @param compact 横屏用：纵向内边距收窄，把高度让给画面。
 */
@Composable
internal fun PlayerTransportControls(
    state: MspPlaybackState,
    subtitlesActive: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean? = null,
    onToggleFullscreen: () -> Unit = {},
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = if (compact) 6.dp else 20.dp,
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(
                    if (state.shuffleEnabled) {
                        R.string.msp_player_shuffle_off
                    } else {
                        R.string.msp_player_shuffle_on
                    },
                ),
                tint = if (state.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        FilledTonalIconButton(onClick = onSkipPrevious) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.msp_player_previous),
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(if (compact) 56.dp else 64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.msp_player_pause else R.string.msp_player_play,
                ),
                modifier = Modifier.size(32.dp),
            )
        }

        FilledTonalIconButton(onClick = onSkipNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.msp_player_next),
            )
        }

        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = if (state.repeatMode == MspRepeatMode.ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = stringResource(
                    when (state.repeatMode) {
                        MspRepeatMode.OFF -> R.string.msp_player_repeat_off
                        MspRepeatMode.ALL -> R.string.msp_player_repeat_all
                        MspRepeatMode.ONE -> R.string.msp_player_repeat_one
                    },
                ),
                tint = if (state.repeatMode == MspRepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        // 字幕入口。高亮 = 现在屏幕上真的有字幕在显示，而不是「挂了字幕但关着」：
        // 只表达前者，用户扫一眼就知道现在这个按钮该不该点。
        IconButton(onClick = onOpenSubtitles) {
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = stringResource(R.string.msp_player_subtitle_entry),
                tint = if (subtitlesActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (fullscreen != null) {
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) {
                        Icons.Filled.FullscreenExit
                    } else {
                        Icons.Filled.Fullscreen
                    },
                    contentDescription = stringResource(
                        if (fullscreen) {
                            R.string.msp_player_exit_fullscreen
                        } else {
                            R.string.msp_player_enter_fullscreen
                        },
                    ),
                )
            }
        }
    }
}

/**
 * 「倍速 / A-B 循环 / 画面比例」三个文字按钮。
 *
 * 用**文字**而不是图标，原因是这三个东西都没有公认的图标：
 * 「倍速」用秒表、「A-B」用循环箭头、「比例」用方框——每一个都需要用户停下来
 * 猜一次，而它们各自只有一个状态要表达。直接写「1.5×」「A-B」「裁剪」是零歧义的。
 * 文字短，三个按钮加起来的宽度比原来那一堆 `IconButton` 还小。
 *
 * @param horizontalArrangement 竖屏下用 `Center`（下面还有一整行控制按钮，居中最稳），
 *   横屏的顶栏里也用 `Center`。
 */
@Composable
internal fun PlayerActionChips(
    speed: Float,
    aspectRatioLabel: String,
    abRepeat: AbRepeatState,
    onOpenSpeed: () -> Unit,
    onOpenAspectRatio: () -> Unit,
    onCycleAbRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipButton(
            text = PlaybackSpeedOptions.format(speed),
            active = speed != PlaybackSpeedOptions.DEFAULT,
            onClick = onOpenSpeed,
            description = stringResource(R.string.msp_player_speed),
        )
        ChipButton(
            text = abRepeat.chipLabel().string(),
            active = !abRepeat.isEmpty,
            onClick = onCycleAbRepeat,
            description = stringResource(R.string.msp_player_ab_repeat),
        )
        ChipButton(
            text = aspectRatioLabel,
            active = false,
            onClick = onOpenAspectRatio,
            description = stringResource(R.string.msp_player_aspect),
        )
    }
}

/**
 * A-B 按钮上的文字。
 *
 * 三个状态各写各的：只设了 A 的时候写「设 B」，用户才知道**现在这一下**是干嘛的。
 * 都写成「A-B」的话，用户按了第一下会以为没生效（画面上确实什么都没变，
 * 循环要等到 B 才成立）。
 */
private fun AbRepeatState.chipLabel(): MspText = when {
    isEmpty -> MspText.Plain("A-B")
    isWaitingForEnd -> MspText.Res(R.string.msp_player_set_b)
    else -> MspText.Plain("A-B")
}

@Composable
private fun ChipButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    // 无障碍描述要先把资源取出来再进 `semantics`：那里的 lambda 不是 `@Composable`，
    // 里面调不了 `stringResource`。
    val chipDescription = stringResource(
        if (active) R.string.msp_player_chip_active else R.string.msp_player_chip_inactive,
        description,
        text,
    )
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        // 无障碍文案把状态读出来：TalkBack 读「播放速度，1.5×，已启用」比只读一个
        // 「1.5×」有用得多——后者在语音里完全看不出它是当前速度还是可选项。
        modifier = Modifier.semantics { contentDescription = chipDescription },
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 竖直拖动时屏幕中间那个提示泡。
 *
 * 特意做得**很钝**（大圆角、半透明底、粗进度条）：它是手指正按着屏幕时用的，
 * 用户看到它只有零点几秒，所以要点是「一眼知道方向对不对」，
 * 而不是「读出准确的数字」。
 */
@Composable
internal fun PlayerLevelIndicator(hint: PlayerLevelHint, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (hint.isVolume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Brightness6,
                contentDescription = stringResource(
                    if (hint.isVolume) R.string.msp_player_volume else R.string.msp_player_brightness,
                ),
            )
            Text(
                text = "${hint.percent}%",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            LinearProgressIndicator(
                progress = { hint.percent / 100f },
                modifier = Modifier.padding(top = 8.dp).width(120.dp),
            )
        }
    }
}

/**
 * 横屏的全屏控制层。
 *
 * ## 为什么整层没有背景、而且只有上下两条有背景
 *
 * 这一层要盖在**整块屏幕**上（上下两条要能拉到边缘），但中间那一大块必须让
 * 手势穿过去——所以整层没有背景，也不常驻手势接收器。Compose 的命中测试
 * 只认有 `pointerInput` 的节点，因此中间那块空白区域的触摸会落到下面的手势层上，
 * 双击播放/暂停和竖直拖动照常工作。
 *
 * **不要在根节点上挂一个常驻的 `pointerInput`。** 同一个 Box 的兄弟节点里，
 * 命中测试在第一个命中的节点处停下（`sharePointerInputWithSiblings` 默认为 false），
 * 所以根节点一旦有 pointerInput，竖屏/横屏中间那一块就会被它吃掉，
 * 下面那个手势层再也收不到双击与拖动。锁定时需要的那层全屏点击层因此是
 * **锁定分支内部的一个兄弟节点**，见下面锁定分支的注释。
 *
 * ## 为什么是上下两条，而不是把控制按钮放在画面旁边
 *
 * 横屏时画面是铺满的，任何常驻在旁边的东西都是在永久地吃掉画面。所以全部做成
 * 覆盖层 + 自动淡出：看的时候一点都不挡，需要的时候点一下出现。
 */
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    locked: Boolean,
    lockHintVisible: Boolean,
    state: MspPlaybackState,
    entry: MediaEntry?,
    positionMs: Long,
    bufferedMs: Long,
    speed: Float,
    aspectRatioLabel: String,
    subtitlesActive: Boolean,
    onExitFullscreen: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenAspectRatio: () -> Unit,
    onCycleAbRepeat: () -> Unit,
    onToggleLock: () -> Unit,
    onRevealLockedControls: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {

        if (locked) {
            // 锁定时只留一个「点一下才有反应」的解锁按钮。
            //
            // 这一层全屏的透明点击层声明的**早于**后面的解锁按钮：同一个 Box 里
            // 后声明的节点画在上面、也先参与命中测试，所以点在按钮上时事件归按钮，
            // 点在别处才轮到这层——「锁定时到处都点不动」和「解锁按钮能用」
            // 同时成立靠的就是这个顺序，改变两者先后会直接让解锁失效。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onRevealLockedControls() }
                    },
            )
            AnimatedVisibility(
                visible = lockHintVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(topStart = 16.dp),
                ) {
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.LockOpen,
                            contentDescription = stringResource(R.string.msp_player_unlock),
                        )
                    }
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExitFullscreen) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_player_exit_fullscreen),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            text = entry?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        decoderLabelOf(state.decoderKind)?.let { label ->
                            Text(
                                text = label.string(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PlayerActionChips(
                        speed = speed,
                        aspectRatioLabel = aspectRatioLabel,
                        abRepeat = state.abRepeat,
                        onOpenSpeed = onOpenSpeed,
                        onOpenAspectRatio = onOpenAspectRatio,
                        onCycleAbRepeat = onCycleAbRepeat,
                    )
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.msp_player_lock),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    PlayerSeekBar(
                        positionMs = positionMs,
                        bufferedMs = bufferedMs,
                        durationMs = state.durationMs,
                        enabled = state.hasKnownDuration,
                        onSeekTo = onSeekTo,
                        abRepeat = state.abRepeat,
                    )
                    PlayerTransportControls(
                        state = state,
                        subtitlesActive = subtitlesActive,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onCycleRepeat = onCycleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onOpenSubtitles = onOpenSubtitles,
                        compact = true,
                    )
                }
            }
        }
    }
}
