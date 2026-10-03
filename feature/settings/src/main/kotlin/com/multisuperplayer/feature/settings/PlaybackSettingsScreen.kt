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
import androidx.compose.material.icons.outlined.Restore
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 播放设置。从设置入口页推上来。
 *
 * 这里放的都是**内核真的会读**的项。一个没人读的开关，用户拨它只会得到
 * 「看起来生效了但什么都没发生」，比没有这个开关更糟。（v0.5.15 之前这段注释
 * 举的例子是「字幕字号」，它在 v0.5.16 有了消费者，现在在「字幕与翻译」那一页里。）
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
        onSetRecordRecentPlays = viewModel::setRecordRecentPlays,
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
    onSetRecordRecentPlays: (Boolean) -> Unit = {},
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
                title = { Text(stringResource(R.string.msp_settings_playback)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.msp_settings_section_decoding)) }
            item {
                // 这个开关**不当**「让更多文件能放」用：内核默认就是
                // 「系统解码器优先，解不了/解失败自动换 FFmpeg」。它真正解决的是
                // 另一类问题——硬件解码器不报错，但画面花屏、变色、音画不同步。
                // 不说清楚的话，所有「放不了」的用户都会先来拨它，然后觉得没用。
                val force = playback.forceSoftwareDecoding ?: false
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_force_software),
                    subtitle = when {
                        !softwareDecodingAvailable ->
                            stringResource(R.string.msp_settings_force_software_no_ffmpeg)

                        force ->
                            stringResource(R.string.msp_settings_force_software_on)

                        else ->
                            stringResource(R.string.msp_settings_force_software_off)
                    },
                    checked = force && softwareDecodingAvailable,
                    enabled = softwareDecodingAvailable,
                    onCheckedChange = onSetForceSoftwareDecoding,
                    help = stringResource(R.string.msp_settings_force_software_help),
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_picture_speed)) }
            item {
                // 画面比例的**默认值**。不是「当前值」：播放页里临时切到「裁剪」
                // 看完一部片子，不应该让下一部也默认被裁掉两边。
                val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.AspectRatio,
                    title = stringResource(R.string.msp_settings_default_aspect),
                    value = aspect.label.string(),
                    subtitle = stringResource(R.string.msp_settings_default_aspect_desc),
                    onClick = { openDialog = PlaybackDialog.ASPECT_RATIO },
                    help = stringResource(R.string.msp_settings_default_aspect_help),
                )
            }

            item {
                // 和画面比例相反，这个是**全局**的：播放页里点倍速也会写回同一个值，
                // 所以这里显示的就是「下次打开会用的速度」，不需要额外说明。
                val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(R.string.msp_settings_default_speed),
                    value = PlaybackSpeedOptions.format(speed),
                    subtitle = stringResource(R.string.msp_settings_default_speed_desc),
                    onClick = { openDialog = PlaybackDialog.SPEED },
                    help = stringResource(R.string.msp_settings_default_speed_help),
                )
            }

            item {
                // 长按画面的倍速。和「默认倍速」是两件事：默认倍速是「我想一直
                // 用这个速度播」，这个是「我想临时听快一点」——所以它的档位表里
                // 没有 0.5×/0.75× 这种「比原速慢」的值，最小值就是 1.5×。
                SettingChoiceRow(
                    icon = Icons.Outlined.FastForward,
                    title = stringResource(R.string.msp_settings_boost_speed),
                    value = SpeedBoostOptions.format(playback.boostSpeed),
                    subtitle = stringResource(R.string.msp_settings_boost_speed_desc),
                    onClick = { openDialog = PlaybackDialog.BOOST_SPEED },
                )
            }

            item {
                val remember = playback.rememberPosition ?: true
                SettingsSwitchRow(
                    // 用 Restore（回卷）而不是 History：History 留给下面那一行，
                    // 因为它和「最近」标签用的是同一个图标，用户要能一眼认出
                    // 那个开关管的是哪一页。
                    icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_remember_position),
                    subtitle = if (remember) {
                        stringResource(R.string.msp_settings_remember_position_on)
                    } else {
                        stringResource(R.string.msp_settings_remember_position_off)
                    },
                    checked = remember,
                    enabled = true,
                    onCheckedChange = onSetRememberPosition,
                    help = stringResource(R.string.msp_settings_remember_position_help),
                )
            }

            item {
                // 和上面那一行相邻，副标题必须把两者的区别说清：两个开关长得很像，
                // 而「关错了」的结果只是一段时间后发现列表空了——用户根本不会联想到它。
                val recordRecent = playback.recordRecentPlays ?: true
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_record_recent),
                    subtitle = if (recordRecent) {
                        stringResource(R.string.msp_settings_record_recent_on)
                    } else {
                        stringResource(R.string.msp_settings_record_recent_off)
                    },
                    checked = recordRecent,
                    enabled = true,
                    onCheckedChange = onSetRecordRecentPlays,
                    help = stringResource(R.string.msp_settings_record_recent_help),
                )
            }
        }
    }

    // 对话框画在 `Scaffold` 外面，而不是塞进 `LazyColumn` 的 item 里：
    // 放进 item 的话它会随列表滚走，而对话框是浮层，本就不该有自己的滚动位置。
    when (openDialog) {
        PlaybackDialog.ASPECT_RATIO -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_default_aspect),
            options = AspectRatioMode.entries,
            selected = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT,
            label = { it.label.string() },
            description = { it.description.string() },
            // 选完就关：只有一个选项要选，让用户再去按一次「确定」是多余的一步。
            onSelect = { mode ->
                onSetAspectRatioMode(mode)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        PlaybackDialog.SPEED -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_default_speed),
            options = PlaybackSpeedOptions.PRESETS,
            // 存的值可能不在档位表里（改了档位表、或被别的入口写进来的旧值）：
            // 用最近档位高亮，不能一个都不亮——那看起来像「没设置过」。
            selected = PlaybackSpeedOptions.nearestPreset(
                playback.speed ?: PlaybackSpeedOptions.DEFAULT,
            ),
            label = { PlaybackSpeedOptions.format(it) },
            description = {
                if (it == PlaybackSpeedOptions.DEFAULT) stringResource(R.string.msp_settings_speed_normal) else null
            },
            onSelect = { speed ->
                onSetSpeed(speed)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        PlaybackDialog.BOOST_SPEED -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_boost_speed),
            // 注意这里**不能**用 `PlaybackSpeedOptions.PRESETS`：那张表有 10 档，
            // 包含 0.5×/0.75×，而「按住反而变慢」既不是这个功能的意图，也会让
            // 用户以为按住是在出问题。
            options = SpeedBoostOptions.PRESETS,
            selected = SpeedBoostOptions.normalize(playback.boostSpeed),
            label = { SpeedBoostOptions.format(it) },
            description = {
                if (it == SpeedBoostOptions.DEFAULT) stringResource(R.string.msp_settings_speed_default) else null
            },
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
