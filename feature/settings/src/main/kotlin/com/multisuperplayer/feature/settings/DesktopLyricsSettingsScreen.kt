package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.DesktopLyricsFontSize
import com.multisuperplayer.core.data.settings.DesktopLyricsOpacity
import com.multisuperplayer.core.data.settings.DesktopLyricsSettings
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 「桌面歌词」设置页。
 *
 * ## 为什么它是**独立**的一页，而不是「播放设置」里的几行
 *
 * 播放设置那一页讲的是「播放内核怎么工作」（解码、倍速、网络），而这个功能会
 * 在**别的应用上面**画出东西，还牵扯一项系统特殊授权。把它塞进播放设置里，
 * 「开启」这个动作会连带出一个用户没预期到的系统页面跳转，而那一页的位置在
 * 三个子页之外无处解释。
 *
 * ## 为什么这一页不直接控制浮窗
 *
 * 浮窗的宿主（`DesktopLyricsOverlayHost`）住在 `:feature:player`，是应用级的单例，
 * 它通过同一个 `DesktopLyricsSettingsRepository` 观察开关。所以这里只写偏好，
 * 浮窗自己会响应——不需要「先打开这一页才能改」这种耦合。
 */
@Composable
fun DesktopLyricsSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DesktopLyricsSettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.overlayGranted.collectAsStateWithLifecycle()

    // `SYSTEM_ALERT_WINDOW` 没有任何回调：用户去系统页面里打开它，进程还活着，
    // 我们收不到通知。和「所有文件访问」「后台保活」是同一类状态，
    // 只有回到前台时重读一次才知道对不对——少了这一步，用户授权完回来会看到
    // 这一页还说「缺少权限」，于是他会再点一次「去开启」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshOverlayPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DesktopLyricsSettingsScreen(
        settings = settings,
        overlayGranted = overlayGranted,
        onBack = onBack,
        // 打开开关的同时**如果权限还没给**，顺手把用户送到系统页面：不跳的话，
        // 他打开开关、回到桌面、什么都没有，而这一页已经不再提示他缺什么。
        onSetEnabled = { enabled ->
            viewModel.setEnabled(enabled)
            if (enabled && !overlayGranted) viewModel.openOverlaySettings(context.findActivity())
        },
        onSetLocked = viewModel::setLocked,
        onSetFontSize = viewModel::setFontSize,
        onSetOpacity = viewModel::setOpacity,
        onResetAppearance = viewModel::resetAppearance,
        onResetPosition = viewModel::resetPosition,
        onOpenOverlaySettings = { viewModel.openOverlaySettings(context.findActivity()) },
        modifier = modifier,
    )
}

/** 这一页上会弹出的选择框。和 `PlaybackDialog` 一样：一个枚举 + 一次 `when`。 */
private enum class DesktopLyricsDialog { FONT_SIZE, OPACITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLyricsSettingsScreen(
    settings: DesktopLyricsSettings,
    overlayGranted: Boolean,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetLocked: (Boolean) -> Unit,
    onSetFontSize: (DesktopLyricsFontSize) -> Unit,
    onSetOpacity: (DesktopLyricsOpacity) -> Unit,
    onResetAppearance: () -> Unit,
    onResetPosition: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openDialog: DesktopLyricsDialog? by remember { mutableStateOf(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_desktop_lyrics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
            item { SectionHeader(stringResource(R.string.msp_desktop_lyrics_section_switch)) }

            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.msp_desktop_lyrics_switch_title),
                    // 副标题分三种而不是两种：「开了但没有权限」这个组合下屏幕上什么都
                    // 不会出现，说成「已开启」等于骗人（理由同 `SettingsSummaries.desktopLyrics`）。
                    subtitle = when {
                        !settings.enabled -> stringResource(R.string.msp_desktop_lyrics_switch_off)
                        !overlayGranted -> stringResource(R.string.msp_desktop_lyrics_switch_no_permission)
                        else -> stringResource(R.string.msp_desktop_lyrics_switch_on)
                    },
                    checked = settings.enabled,
                    onCheckedChange = onSetEnabled,
                    icon = { Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = null) },
                    help = stringResource(R.string.msp_desktop_lyrics_switch_help),
                )
            }

            // 缺权限时这一行**必须**出现，而且要在开关下面：它是「为什么打开了却看不见」
            // 的唯一答案。已授权时不显示——一行永远写着「去开启」的按钮会让人以为
            // 点它有意义。
            if (settings.enabled && !overlayGranted) {
                item {
                    SettingActionButtonRow(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.msp_desktop_lyrics_grant_title),
                        subtitle = stringResource(R.string.msp_desktop_lyrics_grant_subtitle),
                        action = stringResource(R.string.msp_desktop_lyrics_grant_action),
                        onAction = onOpenOverlaySettings,
                    )
                }
            }

            item { InfoNote(stringResource(R.string.msp_desktop_lyrics_note)) }

            item { SectionHeader(stringResource(R.string.msp_desktop_lyrics_section_appearance)) }

            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.msp_desktop_lyrics_font_size_title),
                    value = settings.style.fontSize.label.string(),
                    subtitle = stringResource(R.string.msp_desktop_lyrics_font_size_subtitle),
                    onClick = { openDialog = DesktopLyricsDialog.FONT_SIZE },
                    help = stringResource(R.string.msp_desktop_lyrics_font_size_help),
                )
            }

            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.Opacity,
                    title = stringResource(R.string.msp_desktop_lyrics_opacity_title),
                    value = settings.style.opacity.label.string(),
                    subtitle = stringResource(R.string.msp_desktop_lyrics_opacity_subtitle),
                    onClick = { openDialog = DesktopLyricsDialog.OPACITY },
                    help = stringResource(R.string.msp_desktop_lyrics_opacity_help),
                )
            }

            item {
                SettingActionButtonRow(
                    icon = Icons.Outlined.RestartAlt,
                    title = stringResource(R.string.msp_desktop_lyrics_reset_appearance_title),
                    subtitle = stringResource(R.string.msp_desktop_lyrics_reset_appearance_subtitle),
                    action = stringResource(R.string.msp_desktop_lyrics_reset_appearance_action),
                    onAction = onResetAppearance,
                )
            }

            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.msp_desktop_lyrics_locked_title),
                    subtitle = if (settings.locked) {
                        stringResource(R.string.msp_desktop_lyrics_locked_subtitle_on)
                    } else {
                        stringResource(R.string.msp_desktop_lyrics_locked_subtitle_off)
                    },
                    checked = settings.locked,
                    onCheckedChange = onSetLocked,
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    // 锁定只管位置：它**不会**关掉桌面歌词，所以只在开着时才让它可拨。
                    // 关闭状态下锁定是个没有意义的状态（没有窗口可锁），拨它只会让
                    // 用户在下次打开时遇到一个「点不动」的浮窗。
                    enabled = settings.enabled,
                    help = stringResource(R.string.msp_desktop_lyrics_locked_help),
                )
            }

            item { SectionHeader(stringResource(R.string.msp_desktop_lyrics_section_position)) }

            item {
                SettingActionButtonRow(
                    icon = Icons.Outlined.CenterFocusStrong,
                    title = stringResource(R.string.msp_desktop_lyrics_reset_position_title),
                    subtitle = stringResource(R.string.msp_desktop_lyrics_reset_position_subtitle),
                    action = stringResource(R.string.msp_desktop_lyrics_reset_position_action),
                    onAction = onResetPosition,
                )
            }
        }
    }

    when (openDialog) {
        DesktopLyricsDialog.FONT_SIZE -> ChoiceDialog(
            title = stringResource(R.string.msp_desktop_lyrics_font_size_title),
            options = DesktopLyricsFontSize.entries,
            selected = settings.style.fontSize,
            label = { option -> option.label.string() },
            // 每个选项都把它的实际点数说出来：四个词（小/标准/大/特大）在两种语言里
            // 都不足以让人判断「特大到底多大」，而这一步选错要回到播放页才看得见。
            description = { option -> stringResource(R.string.msp_desktop_lyrics_font_size_value, option.sp) },
            onSelect = {
                onSetFontSize(it)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        DesktopLyricsDialog.OPACITY -> ChoiceDialog(
            title = stringResource(R.string.msp_desktop_lyrics_opacity_title),
            options = DesktopLyricsOpacity.entries,
            selected = settings.style.opacity,
            label = { option -> option.label.string() },
            description = { option -> stringResource(opacityDescription(option)) },
            onSelect = {
                onSetOpacity(it)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        null -> Unit
    }
}

/**
 * 不透明度四档各自的说明。
 *
 * 写的是**看到的效果**（40% = 几乎全透明）而不是数值：数值已经在左边那一列了，
 * 而用户选它的时候脑子里想的是「会不会挡住画面」。
 */
private fun opacityDescription(opacity: DesktopLyricsOpacity): Int = when (opacity) {
    DesktopLyricsOpacity.LIGHT -> R.string.msp_desktop_lyrics_opacity_desc_light
    DesktopLyricsOpacity.SOFT -> R.string.msp_desktop_lyrics_opacity_desc_soft
    DesktopLyricsOpacity.STRONG -> R.string.msp_desktop_lyrics_opacity_desc_strong
    DesktopLyricsOpacity.SOLID -> R.string.msp_desktop_lyrics_opacity_desc_solid
}
