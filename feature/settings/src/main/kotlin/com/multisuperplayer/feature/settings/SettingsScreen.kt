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
import androidx.compose.material.icons.outlined.RecordVoiceOver
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
 * 设置**入口页**：六行，每行一句话说清「现在是什么状态」，点进去才是具体设置。
 *
 * 为什么把它拆成入口页 + 五个子页，而不是继续把设置项铺在一页里：
 *
 * 1. 一页铺开的表在设置项变多之后必然要滚两三屏，而用户每次进来只为一件事。
 *    找「长按倍速」要先滚过所有主题选项，这个成本会随每一项新增而增长。
 * 2. 子页是**真的导航目的地**（见 `MspApp.kt` 的路由表），不是页内的 `if (showX)`。
 *    这一点很关键：页内切换时返回键会直接把用户踢出设置（甚至退出应用），
 *    而他只是想关掉这一页。
 * 3. 「关于」这类信息页和「调什么」的设置页混在一起，是后面加「检查更新」、
 *    开源许可、导出日志时最别扭的地方。分开之后它们各有各的地方。
 *
 * 这个页面因此**不需要**任何写入回调，只读摘要 + 五条导航。
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAsrSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val fileAccessGranted by viewModel.fileAccessGranted.collectAsStateWithLifecycle()
    val asrEntry by viewModel.asrEntry.collectAsStateWithLifecycle()
    val localModelEntry by viewModel.localModelEntry.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 两件事都是「发生在这一页之外、没有任何回调」的变化：
    // 一是「所有文件访问」这个系统设置项；二是 ASR 模型——用户可能刚在播放页的
    // 字幕面板里把它下完。所以订阅 `ON_RESUME` 重新问一次，而不是只读一次构造值——
    // 那样用户会看到「我明明开了 / 明明下完了，这里还写着没有」。
    // 本地模型同理：它是在「字幕与翻译 → 本地模型」子页里下的。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshFileAccess()
                viewModel.refreshAsrStatus()
                viewModel.refreshLocalModelStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScreen(
        theme = theme,
        buildInfo = viewModel.buildInfo,
        playback = playback,
        translation = translation,
        asrEntry = asrEntry,
        localModelEntry = localModelEntry,
        softwareDecodingAvailable = viewModel.softwareDecodingAvailable,
        fileAccessSupported = viewModel.fileAccessSupported,
        fileAccessGranted = fileAccessGranted,
        modifier = modifier,
        onOpenAppearance = onOpenAppearance,
        onOpenPlayback = onOpenPlayback,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenAsrSettings = onOpenAsrSettings,
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
    asrEntry: AsrEntryState = AsrEntryState(),
    /**
     * 本地翻译模型的状态。选中的服务商不是设备上那一个时它不会被显示，
     * 但那个判断在 [SettingsSummaries.translation] 里（与缺项判定同源），这里不做。
     */
    localModelEntry: LocalModelEntryState = LocalModelEntryState(),
    softwareDecodingAvailable: Boolean = true,
    fileAccessSupported: Boolean = true,
    fileAccessGranted: Boolean = false,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAsrSettings: () -> Unit = {},
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
            // 六行平铺，不加小节标题：「设置」标题下紧跟一个「设置」小节是废话，
            // 而这几个分类各自就是一个小节名，再加一层分组只是多两行留白。
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
                    // 选的是「设备上运行」时，摘要里会把模型名和「下了没有」一起说出来：
                    // 本地翻译的失败原因九成就在这里（模型没下载），而它不像地址/密钥
                    // 那样打开子页就能看到——它只在不联网的这台手机上。
                    subtitle = SettingsSummaries
                        .translation(translation, localModelEntry.status)
                        .string(),
                    onClick = onOpenTranslationSettings,
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    // 排在「字幕与翻译」后面：它是另一条**产出字幕**的路（本机识别），
                    // 与上面那条「把字幕翻成另一种语言」是两件事，但用户找它们时
                    // 脑子里是同一句话（「我要给这部片子配字幕」），所以挨着。
                    title = stringResource(R.string.msp_settings_asr),
                    subtitle = SettingsSummaries.asr(asrEntry.model, asrEntry.status).string(),
                    onClick = onOpenAsrSettings,
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
