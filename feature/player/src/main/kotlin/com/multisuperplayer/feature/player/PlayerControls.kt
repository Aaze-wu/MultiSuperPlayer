package com.multisuperplayer.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Immutable
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
import com.multisuperplayer.core.model.text.MspText
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
 * 左上角的出口胶囊：一个返回箭头 + 一句话。
 *
 * 两处用它：竖屏画面左上角的「收起」（弹出播放页，回刚才那个列表），以及横屏音频页
 * 左上角的「退出全屏」——音频横屏没有覆盖式控制层，那个位置本来空着，而它需要
 * 一个常驻的、看得见的出口。
 *
 * ## 它解决的是什么问题
 *
 * 离开播放页的办法本来只有两个：系统返回手势/按键，或者点底部导航的某个标签。
 * 前者在竖屏下没有任何可见的提示（横屏至少还有一个「退出全屏」的箭头），
 * 用户刚换手机、或者手势导航设置不一样时就会**找不到出口**；后者虽然能走，
 * 但它看起来像「切标签」而不是「把播放器收起来」——两件事的后果也确实不一样
 * （切标签是丢掉播放页，收起是回到刚才那个列表，两者都会继续播放）。
 *
 * 所以给它一个位置固定的出口，和横屏左上角那个箭头**在同一个角**：图标 + 文字。
 *
 * ## 为什么带文字，而不是只有一个箭头
 *
 * 图标两处都用同一个 `ArrowBack`：两处都是「离开这一页」，换一个图标只会让人
 * 以为两个按钮干的是不同的事；而第一版用 `KeyboardArrowDown`（⌄）时，实机截图
 * 一眼就是「展开/折叠面板」的错误暗示。但箭头本身只能表达「回上一页」，说不清
 * 后果是「收起这一页、播放不停」还是「退出全屏」，所以旁边必须写字——两个具名的
 * 出口（[R.string.msp_player_collapse] / [R.string.msp_player_exit_fullscreen]）
 * 用同一个组件、`label` 由调用方给，就是为了让这两个名字各自被说出口。
 * 这和倍速/比例那三个按钮的取舍一样（见 [PlayerActionChips]）：
 * 存在歧义的地方，写出来是零成本的。
 *
 * 半透明底是必须的：它压在画面（或者音频封面/歌词）上，而那张图的颜色不由我们决定。
 * 底色和横屏控制层用同一个 `surface`：这个按钮只是「换个地方」，不是
 * 一个需要被注意到的动作，抢眼反而会盖住画面里正在发生的事。
 *
 * @param label 这个出口叫什么。**必须**是「离开这里会发生什么」的说法，
 *   而不是「上一页」这种方位词。
 * @param onClick 由路由层决定（弹出播放页，或者退出全屏）。
 */
@Composable
internal fun PlayerExitChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    // 侧按钮个数必须和下面真正摆出来的那几个对得上：多算一个会把播放键白白缩小一档，
    // 少算一个就是又回到「最后一个被压成缝」。加/删按钮时这里要一起改，
    // 而 TransportLayoutRulesTest 会拿着这组数字去校验「放得下」。
    val sideSlots = if (fullscreen != null) 6 else 5

    // 按**实际拿到的宽度**排版，而不是按屏幕宽度：这一行外面还可能套着父级的
    // 内边距或 weight，猜错了就等于没修（见 TransportLayoutRules）。
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = TransportLayoutRules.layoutFor(
            availableWidth = maxWidth,
            sideSlots = sideSlots,
            compact = compact,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = layout.horizontalPadding,
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
                modifier = Modifier.size(layout.playButtonSize),
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
}

/**
 * 「倍速 / A-B 循环 / 画面比例」三个文字按钮。
 *
 * 用**文字**而不是图标，原因是这三个东西都没有公认的图标：
 * 「倍速」用秒表、「A-B」用循环箭头、「比例」用方框——每一个都需要用户停下来
 * 猜一次，而它们各自只有一个状态要表达。直接写「1.5×」「A-B」「裁剪」是零歧义的。
 * 文字短，三个按钮加起来的宽度比原来那一堆 `IconButton` 还小。
 */
@Composable
internal fun PlayerActionChips(
    speed: Float,
    abRepeat: AbRepeatState,
    onOpenSpeed: () -> Unit,
    onCycleAbRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: PlayerBarChip? = null,
    audioTrack: PlayerBarChip? = null,
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
        // 画面比例只在有画面的地方出现。做成一个整体的可选参数而不是两个可空的
        // 字段：文字和动作必须同时有或同时没有，拆成两个参数就多了一种
        // 「有文字没动作」的非法组合要防。
        aspectRatio?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_aspect),
            )
        }
        // 音轨按钮上的字就是**当前这条轨的名字**，所以它同时回答了「现在听的是哪条」
        // 和「去哪儿换」两个问题。单轨片源下调用方会传 null（永远只有一个选项的
        // 按钮只会占地方）。
        //
        // 名字来自容器自己的标签，长度完全不受我们控制（`Commentary by director`
        // 是真实存在的值）。一行四个芯片的横屏里，一个长标签会把旁边两个挤出屏幕——
        // 所以这里限宽并省略：宁可显示「国语（…」，也不要把「倍速」挤没。
        audioTrack?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_audio_track),
                modifier = Modifier.widthIn(max = CHIP_MAX_WIDTH),
            )
        }
    }
}

/**
 * 「睡眠定时 / 播放队列 / 均衡器 / 屏幕常亮 / 画中画」五个会话级入口。
 *
 * ## 为什么单独一行，而不是并进 [PlayerActionChips]
 *
 * 那一行在竖屏下已经把宽度用完了（四个芯片加上内边距差不多就是一块屏宽），
 * 再塞两个进去，英文界面上必然有一个被挤掉。这两件事的性质也不同：
 * [PlayerActionChips] 是「调当前这条媒体怎么放」，这几个是「管这一次播放会话」。
 *
 * ## 为什么用 `FlowRow`
 *
 * 芯片上的文字长度不完全由我们决定（「本集结束」/「End of this item」差一倍），
 * 等分的 `Row` 会在英文下把文字裁掉（倍速芯片上实测踩过一次）。`FlowRow`
 * 让它真放不下时自己折到第二行，而不是裁掉。
 *
 * ## 为什么不在这里显示「还剩多久」
 *
 * 倒计时是个**每秒都在变**的值，摆在控制条上意味着这一行（以及它所在的那层
 * 控制层）每秒重组一次。而控制条在播放中是会自动淡出的——用户想看倒计时的时候
 * （睡前盯着时间）恰好是这个值根本不在屏幕上的时候。真正需要它的地方是定时面板内部，
 * 那里的倒计时有自己的时钟（见 `PlayerSleepTimerSheet`）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSessionChips(
    session: PlayerSessionChipSet,
    modifier: Modifier = Modifier,
) {
    // 一个都没有就不占位置：留一行空白会把下面的控制条往下推，而那一行什么都说明不了。
    if (session.isEmpty) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        session.sleepTimer?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_sleep_timer),
            )
        }
        session.queue?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_queue),
                // 队列芯片上的字是「队列 12」，长度不受控，和音轨芯片同样处理。
                modifier = Modifier.widthIn(max = CHIP_MAX_WIDTH),
            )
        }
        // 均衡器放在画中画**前面**：画中画是最后一个，因为它点了会离开这个界面。
        session.equalizer?.let { chip ->
            ChipButton(
                // 芯片上的字是**现在的档位**（「低音增强」/「自定义」），没开的时候
                // 写功能名（「均衡器」）——和睡眠定时芯片同一套读法：用户下次看这行
                // 时要回答的问题是「现在声音是什么样」。
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_equalizer),
            )
        }
        // 「屏幕常亮」跟均衡器同一套读法，标签写的是**现在屏幕会怎样**
        // （「屏幕常亮」/「允许熄屏」）而不是功能名：这一个开关的后果在屏幕外面
        // （几十秒后才看得到），用户唯一能当场确认的就是这句话。
        session.keepScreenOn?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                // 无障碍描述给的是**这个开关的名字**，不是当前状态：状态在
                // `msp_player_chip_active/inactive` 里已经读出来了。
                description = stringResource(R.string.msp_player_keep_screen_on),
            )
        }
        // 画中画排最后：它和上面几个的区别是**点了会离开这个界面**（画面缩成一个小
        // 窗口），摆在最靠后/最靠边的位置，误触的代价最小。
        session.pip?.let { chip ->
            ChipButton(
                text = chip.label,
                active = chip.active,
                onClick = chip.onClick,
                description = stringResource(R.string.msp_player_pip),
            )
        }
    }
}

/**
 * 控制条上「一个可选值」的按钮配置（画面比例、音轨）。
 *
 * `null`（不给）表示这一处没有这件事——音频页就是「没有画面比例」那种情况：
 * 声音没有画面比例可调，把它画出来只会让用户点开一个永远无效的面板。
 * 音轨也同理，只是判断依据不同：只有**多条**音轨时才给。
 *
 * [active] 表示「现在不是默认状态」：[PlayerActionChips] 里的倍速、A-B 靠它高亮，
 * 而画面比例和音轨目前都传 `false`——它们的标签本身已经把状态写全了
 * （「裁剪」/「国语」），再点亮一次不增加任何信息。
 *
 * 公开（而不是 `internal`）是因为它出现在 `PlayerScreen` 的参数表上，而那个组合
 * 函数是公开的——公开函数不能暴露 internal 类型。
 */
data class PlayerBarChip(
    val label: String,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 会话级入口的一整组（睡眠定时 / 队列 / 均衡器 / 屏幕常亮 / 画中画）。
 *
 * ## 为什么不写成几个平铺的参数
 *
 * 这一组要穿过 `PlayerScreen` → `PortraitLayout` / `LandscapeLayout` →
 * `AudioLandscapeLayout` → `AudioLandscapeControls`（以及 `PlayerControlsOverlay`）
 * 好几层。每加一个入口，那几层签名各加一行、各处转发各加一行——十几个几乎相同的
 * 改动点，漏掉任意一处就是「这个按钮在某些版面上不出现」（横屏有、竖屏没有），
 * 而那种偏差在代码里看不出来，只有把两种方向都试一遍才发现得了。
 *
 * 打包成一个对象之后，加入口变成「加一个字段 + 一处渲染」，穿参的那几层不用动。
 *
 * ## 字段的空值含义各不相同
 *
 * [sleepTimer] 为 null 是「预览/单测里没给」，[queue] 为 null 是「队列里没东西可看」，
 * [equalizer] 与 [keepScreenOn] 为 null 是「预览/单测里没给」——真机上这两个入口一直在：
 * 均衡器是在开始播放**之前**就能设的（那时设备支不支持都还不知道），屏幕常亮则是
 * 「这一次播放我想不想让它一直亮着」（设置里那个是全局默认，两件事）。
 * 把它们藏起来会让功能变成「先放一会儿才出现」，而用户找的是一个开关。
 *
 * 公开（而不是 internal）是因为它出现在 `PlayerScreen` 的参数表上，而那个组合
 * 函数是公开的——公开函数不能暴露 internal 类型。
 */
@Immutable
data class PlayerSessionChipSet(
    val sleepTimer: PlayerBarChip? = null,
    val queue: PlayerBarChip? = null,
    val equalizer: PlayerBarChip? = null,
    val keepScreenOn: PlayerBarChip? = null,
    val pip: PlayerBarChip? = null,
) {
    /**
     * 所有入口都没有。
     *
     * 「要不要占位置」的判断点和「要不要画」的渲染点必须是同一个集合：分开写的话，
     * 以后加第五个入口时只改了一边，就会得到一行空白的间距（或一个跑出边界的东西）。
     */
    val isEmpty: Boolean
        get() = sleepTimer == null && queue == null && equalizer == null &&
            keepScreenOn == null && pip == null
}

/**
 * 芯片上文字的最大宽度。
 *
 * 只有标签完全由容器决定的那个芯片（音轨）用得上：倍速是 `1.5×`、A-B 是
 * `A-B`、画面比例是三个两字词，都是我们自己写的。
 */
private val CHIP_MAX_WIDTH = 96.dp

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
    modifier: Modifier = Modifier,
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
        modifier = modifier.semantics { contentDescription = chipDescription },
    ) {
        // 单行 + 省略：芯片是一排里的格位，换行会把整排推高、把控件区挤变形。
        // TalkBack 读的还是完整文案（在 [semantics] 里），所以截断只影响视觉。
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    /** 音轨入口的配置。`null` = 这个片源只有一条音轨（或还没有轨道信息）。 */
    audioTrackChip: PlayerBarChip? = null,
    /** 睡眠定时 / 队列 / 均衡器 / 画中画四个会话级入口。默认空集 = 一个都不画。 */
    sessionChips: PlayerSessionChipSet = PlayerSessionChipSet(),
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
                        abRepeat = state.abRepeat,
                        onOpenSpeed = onOpenSpeed,
                        onCycleAbRepeat = onCycleAbRepeat,
                        aspectRatio = PlayerBarChip(
                            label = aspectRatioLabel,
                            onClick = onOpenAspectRatio,
                        ),
                        audioTrack = audioTrackChip,
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
                    // 摆在传输控制**下面**（而不是上面）：下面那一条是屏幕最下缘，
                    // 越靠下的东西越不容易被手指挡住，而这三个入口是需要点准的。
                    PlayerSessionChips(
                        session = sessionChips,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}
