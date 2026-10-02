package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
import org.koin.androidx.compose.koinViewModel

/**
 * 播放设置。从设置入口页推上来。
 *
 * 这里放的都是**内核真的会读**的项。像「字幕字号」这类还没有消费者的项不写：
 * 一个没人读的开关，用户拨它只会得到「看起来生效了但什么都没发生」，
 * 比没有这个开关更糟。
 */
@Composable
fun PlaybackSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    PlaybackSettingsScreen(
        playback = playback,
        softwareDecodingAvailable = viewModel.softwareDecodingAvailable,
        onBack = onBack,
        onSetForceSoftwareDecoding = viewModel::setForceSoftwareDecoding,
        onSetAspectRatioMode = viewModel::setAspectRatioMode,
        onSetSpeed = viewModel::setSpeed,
        onSetBoostSpeed = viewModel::setBoostSpeed,
        onSetRememberPosition = viewModel::setRememberPosition,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    playback: PlaybackSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    softwareDecodingAvailable: Boolean = true,
    onSetForceSoftwareDecoding: (Boolean) -> Unit = {},
    onSetAspectRatioMode: (AspectRatioMode) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetBoostSpeed: (Float) -> Unit = {},
    onSetRememberPosition: (Boolean) -> Unit = {},
) {
    // 当前打开的选择对话框（null = 没开）。
    //
    // 用「对话框 + 当前值」而不是像主题基底那样把选项全铺在页面上：画面比例 4 项、
    // 倍速 10 项，全铺开会让「播放」这一段比你真正要改的那一行长三倍。
    var openDialog: PlaybackDialog? by remember { mutableStateOf(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("播放") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("解码") }
            item {
                // 这个开关**不当**「让更多文件能放」用：内核默认就是
                // 「系统解码器优先，解不了/解失败自动换 FFmpeg」。它真正解决的是
                // 另一类问题——硬件解码器不报错，但画面花屏、变色、音画不同步。
                // 不说清楚的话，所有「放不了」的用户都会先来拨它，然后觉得没用。
                val force = playback.forceSoftwareDecoding ?: false
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                    title = "强制软件解码",
                    subtitle = when {
                        !softwareDecodingAvailable ->
                            "本安装包不含 FFmpeg（CPU 架构不受支持），打开也不会生效"

                        force ->
                            "已用 FFmpeg 解码。画面异常时用它排查；代价是耗电和发热明显变高。"

                        else ->
                            "默认不勾：系统解码器放不了或放错时，内核会自动改用内置的 FFmpeg。" +
                                "只有当画面花屏/变色/音画不同步（硬件解码器出错）时才需要勾上。"
                    },
                    checked = force && softwareDecodingAvailable,
                    enabled = softwareDecodingAvailable,
                    onCheckedChange = onSetForceSoftwareDecoding,
                )
            }

            item { SectionHeader("画面与速度") }
            item {
                // 画面比例的**默认值**。不是「当前值」：播放页里临时切到「裁剪」
                // 看完一部片子，不应该让下一部也默认被裁掉两边。
                val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.AspectRatio,
                    title = "默认画面比例",
                    value = aspect.label,
                    subtitle = "只影响之后打开的文件。在播放页里临时改的比例不会写到这里。",
                    onClick = { openDialog = PlaybackDialog.ASPECT_RATIO },
                )
            }

            item {
                // 和画面比例相反，这个是**全局**的：播放页里点倍速也会写回同一个值，
                // 所以这里显示的就是「下次打开会用的速度」，不需要额外说明。
                val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.Speed,
                    title = "默认倍速",
                    value = PlaybackSpeedOptions.format(speed),
                    subtitle = "跨文件保留：播放页里改了倍速，这里也会跟着变。",
                    onClick = { openDialog = PlaybackDialog.SPEED },
                )
            }

            item {
                // 长按画面的倍速。和「默认倍速」是两件事：默认倍速是「我想一直
                // 用这个速度播」，这个是「我想临时听快一点」——所以它的档位表里
                // 没有 0.5×/0.75× 这种「比原速慢」的值，最小值就是 1.5×。
                SettingChoiceRow(
                    icon = Icons.Outlined.FastForward,
                    title = "长按倍速",
                    value = SpeedBoostOptions.format(playback.boostSpeed),
                    subtitle = "按住画面时用这个速度，松手回到原来的速度。",
                    onClick = { openDialog = PlaybackDialog.BOOST_SPEED },
                )
            }

            item {
                val remember = playback.rememberPosition ?: true
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = "记住播放位置",
                    subtitle = if (remember) {
                        "下次打开同一个文件时接着上次的位置播"
                    } else {
                        "每次都从头播。已经记住的位置不会被删掉，重新打开这个开关就能继续用。"
                    },
                    checked = remember,
                    enabled = true,
                    onCheckedChange = onSetRememberPosition,
                )
            }
        }
    }

    // 对话框画在 `Scaffold` 外面，而不是塞进 `LazyColumn` 的 item 里：
    // 放进 item 的话它会随列表滚走，而对话框是浮层，本就不该有自己的滚动位置。
    when (openDialog) {
        PlaybackDialog.ASPECT_RATIO -> ChoiceDialog(
            title = "默认画面比例",
            options = AspectRatioMode.entries,
            selected = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT,
            label = { it.label },
            description = { it.description },
            // 选完就关：只有一个选项要选，让用户再去按一次「确定」是多余的一步。
            onSelect = { mode ->
                onSetAspectRatioMode(mode)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        PlaybackDialog.SPEED -> ChoiceDialog(
            title = "默认倍速",
            options = PlaybackSpeedOptions.PRESETS,
            // 存的值可能不在档位表里（改了档位表、或被别的入口写进来的旧值）：
            // 用最近档位高亮，不能一个都不亮——那看起来像「没设置过」。
            selected = PlaybackSpeedOptions.nearestPreset(
                playback.speed ?: PlaybackSpeedOptions.DEFAULT,
            ),
            label = { PlaybackSpeedOptions.format(it) },
            description = { if (it == PlaybackSpeedOptions.DEFAULT) "正常速度" else null },
            onSelect = { speed ->
                onSetSpeed(speed)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        PlaybackDialog.BOOST_SPEED -> ChoiceDialog(
            title = "长按倍速",
            // 注意这里**不能**用 `PlaybackSpeedOptions.PRESETS`：那张表有 10 档，
            // 包含 0.5×/0.75×，而「按住反而变慢」既不是这个功能的意图，也会让
            // 用户以为按住是在出问题。
            options = SpeedBoostOptions.PRESETS,
            selected = SpeedBoostOptions.normalize(playback.boostSpeed),
            label = { SpeedBoostOptions.format(it) },
            description = { if (it == SpeedBoostOptions.DEFAULT) "默认值" else null },
            onSelect = { speed ->
                onSetBoostSpeed(speed)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        null -> Unit
    }
}

/** 播放页上会弹出的选择对话框。原来是叫 `SettingsDialog`，拆页后只剩播放这一处，名字跟着走。 */
private enum class PlaybackDialog { ASPECT_RATIO, SPEED, BOOST_SPEED }
