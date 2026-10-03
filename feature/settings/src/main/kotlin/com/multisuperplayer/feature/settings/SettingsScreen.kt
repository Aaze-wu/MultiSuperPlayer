package com.multisuperplayer.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 设置**入口页**：五行，每行一句话说清「现在是什么状态」，点进去才是具体设置。
 *
 * 为什么把它拆成入口页 + 四个子页，而不是继续把设置项铺在一页里：
 *
 * 1. 一页铺开的表在设置项变多之后必然要滚两三屏，而用户每次进来只为一件事。
 *    找「长按倍速」要先滚过所有主题选项，这个成本会随每一项新增而增长。
 * 2. 子页是**真的导航目的地**（见 `MspApp.kt` 的路由表），不是页内的 `if (showX)`。
 *    这一点很关键：页内切换时返回键会直接把用户踢出设置（甚至退出应用），
 *    而他只是想关掉这一页。
 * 3. 「关于」这类信息页和「调什么」的设置页混在一起，是后面加「检查更新」、
 *    开源许可、导出日志时最别扭的地方。分开之后它们各有各的地方。
 *
 * 这个页面因此**不需要**任何写入回调，只读摘要 + 四条导航。
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val fileAccessGranted by viewModel.fileAccessGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 「所有文件访问」是系统设置项，**没有回调**：用户去系统设置里开完再回到这里，
    // 除了重新问一次没有别的办法知道。所以订阅 `ON_RESUME`，而不是只读一次构造值——
    // 那样用户会看到「我明明开了，这里还写着未开启」。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshFileAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        theme = theme,
        buildInfo = viewModel.buildInfo,
        playback = playback,
        translation = translation,
        softwareDecodingAvailable = viewModel.softwareDecodingAvailable,
        fileAccessSupported = viewModel.fileAccessSupported,
        fileAccessGranted = fileAccessGranted,
        modifier = modifier,
        onOpenAppearance = onOpenAppearance,
        onOpenPlayback = onOpenPlayback,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenAbout = onOpenAbout,
        // 「系统没有这一项」的情况由 `fileAccessSupported` 在下面挡住
        // （`SettingActionRow.enabled`），所以这里**不**再包一层「能不能跳」的判断：
        // 多一个 if 就多一条可能与界面不一致的真相。
        onOpenFileAccess = { context.startActivity(viewModel.fileAccessIntent()) },
    )
}

/**
 * 无 ViewModel、无状态版本，方便预览里直接喂假数据。
 *
 * 摘要文案全部来自 [SettingsSummaries]（纯函数、有单测），这一层只负责把它们摆出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeSettings,
    buildInfo: AppBuildInfo = AppBuildInfo.Unknown,
    modifier: Modifier = Modifier,
    playback: PlaybackSettings = PlaybackSettings(),
    translation: TranslationSettings = TranslationSettings(),
    softwareDecodingAvailable: Boolean = true,
    fileAccessSupported: Boolean = true,
    fileAccessGranted: Boolean = false,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenFileAccess: () -> Unit = {},
) {
    // 系统取色要 Android 12。判断放这里而不是塞进 [SettingsSummaries]：
    // `Build.VERSION.SDK_INT` 在 JVM 单测里恒为 0，进了纯函数就测不了「支持」那条分支。
    val systemColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.msp_settings_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 四行平铺，不加小节标题：「设置」标题下紧跟一个「设置」小节是废话，
            // 而四个分类各自就是一个小节名，再加一层分组只是多两行留白。
            item {
                SettingActionRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.msp_settings_appearance),
                    subtitle = SettingsSummaries.appearance(theme, systemColorSupported).string(),
                    onClick = onOpenAppearance,
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.PlayCircle,
                    title = stringResource(R.string.msp_settings_playback),
                    subtitle = SettingsSummaries.playback(playback, softwareDecodingAvailable).string(),
                    onClick = onOpenPlayback,
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.Translate,
                    // 标题和 [TranslationSettingsScreen] 的标题保持一致：那一页现在装的是
                    // 「字幕外观 + 翻译设置」两段，所以叫「字幕与翻译」而不是「字幕翻译」。
                    // 页面标题和入口名不一样会让人怀疑自己点错了地方。
                    title = stringResource(R.string.msp_settings_translation),
                    subtitle = SettingsSummaries.translation(translation).string(),
                    onClick = onOpenTranslationSettings,
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.msp_settings_file_access),
                    subtitle = SettingsSummaries.fileAccess(
                        supported = fileAccessSupported,
                        granted = fileAccessGranted,
                    ).string(),
                    onClick = onOpenFileAccess,
                    // 系统没有这一页时置灰：点进去也找不到开关（见 `SettingActionRow`）。
                    enabled = fileAccessSupported,
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.msp_settings_about),
                    subtitle = SettingsSummaries.about(buildInfo).string(),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
