package com.multisuperplayer.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SystemUpdate
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
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import com.multisuperplayer.core.data.power.KeepAliveState
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 设置**入口页**：七行，每行一句话说清「现在是什么状态」，点进去才是具体设置。
 *
 * 为什么把它拆成入口页 + 七个子页，而不是继续把设置项铺在一页里：
 *
 * 1. 一页铺开的表在设置项变多之后必然要滚两三屏，而用户每次进来只为一件事。
 *    找「长按倍速」要先滚过所有主题选项，这个成本会随每一项新增而增长。
 * 2. 子页是**真的导航目的地**（见 `MspApp.kt` 的路由表），不是页内的 `if (showX)`。
 *    这一点很关键：页内切换时返回键会直接把用户踢出设置（甚至退出应用），
 *    而他只是想关掉这一页。
 * 3. 「关于」这类信息页和「调什么」的设置页混在一起，是后面加「检查更新」、
 *    开源许可、导出日志时最别扭的地方。分开之后它们各有各的地方。
 *
 * 这个页面因此**不需要**任何写入回调，只读摘要 + 七条导航。
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAsrSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val keepAliveState by viewModel.keepAliveState.collectAsStateWithLifecycle()
    val asrEntry by viewModel.asrEntry.collectAsStateWithLifecycle()
    val localModelEntry by viewModel.localModelEntry.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 三件事都是「发生在这一页之外、没有任何回调」的变化：
    // 一是四项权限（权限是系统里的状态，去系统设置里改完回来进程还活着，收不到通知）；
    // 二是 ASR 模型——用户可能刚在播放页的字幕面板里把它下完；
    // 三是本地翻译模型，它是在「字幕与翻译 → 本地模型」子页里下的。
    // 所以订阅 `ON_RESUME` 重新问一次，而不是只读一次构造值——
    // 那样用户会看到「我明明开了 / 明明下完了，这里还写着没有」。
    //
    // 同一类里还有「后台保活」那一行：电池优化白名单是在**系统页面**里改的，
    // 用户申请完回到这里，进程还活着，也没有任何回调。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 带 Activity 是为了让「被拒过一次」与「已被永久拒绝」分得开。
                // 入口页的副标题只用得上「允许了没有」，但同一份状态也喂给权限页（
                // 两边共用这一个 ViewModel），所以在这里算准一点。
                viewModel.refreshPermissions(context.findActivity())
                viewModel.refreshAsrStatus()
                viewModel.refreshLocalModelStatus()
                viewModel.refreshKeepAlive()
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
        permissionState = permissionState,
        keepAliveState = keepAliveState,
        modifier = modifier,
        onOpenAppearance = onOpenAppearance,
        onOpenPlayback = onOpenPlayback,
        onOpenTranslationSettings = onOpenTranslationSettings,
        onOpenAsrSettings = onOpenAsrSettings,
        onOpenAbout = onOpenAbout,
        onOpenPermissions = onOpenPermissions,
        onOpenKeepAlive = onOpenKeepAlive,
        onOpenUpdate = onOpenUpdate,
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
    permissionState: PermissionSnapshot = PermissionSnapshot(),
    /**
     * 「后台保活」那一行的档位。默认给 [KeepAliveState.RESTRICTED]：
     * 那是**保守且大概率正确**的那一档（新装的应用都不在白名单里），
     * 而反过来默认「已加入」会让预览和真实首帧说一句不成立的话。
     */
    keepAliveState: KeepAliveState = KeepAliveState.RESTRICTED,
    onOpenAppearance: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onOpenAsrSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
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
            // 七行平铺，不加小节标题：「设置」标题下紧跟一个「设置」小节是废话，
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
                    // 排在「字幕与翻译」后面：它是另一条**产出字幕**的路（本机或云端识别），
                    // 与上面那条「把字幕翻成另一种语言」是两件事，但用户找它们时
                    // 脑子里是同一句话（「我要给这部片子配字幕」），所以挨着。
                    //
                    // 摘要传的是整份设置而不是 `model + status`：走云端时那一行必须换个说法，
                    // 而这个判断只有 `SettingsSummaries.asr` 能做（见它的 KDoc）。
                    title = stringResource(R.string.msp_settings_asr),
                    subtitle = SettingsSummaries.asr(asrEntry.settings, asrEntry.status).string(),
                    onClick = onOpenAsrSettings,
                )
            }
            item {
                SettingActionRow(
                    // 这一行原本叫「文件访问」、只能把用户送去系统页面开「所有文件访问」。
                    // 现在它是个真的导航目的地：进去是四项权限的清单，
                    // 「所有文件访问」只是其中一行（另见 README 第 7 节的说明）。
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.msp_settings_permissions),
                    subtitle = PermissionSummaries.entry(permissionState).string(),
                    onClick = onOpenPermissions,
                )
            }
            item {
                SettingActionRow(
                    // 排在「权限」之后：这两行都是「应用需要系统让步」那一类，
                    // 但它们是两套独立机制（白名单管网络，厂商管家管后台清理），
                    // 所以是两行而不是权限页里的一行——混进去会让人以为
                    // 「权限都给了就不会被杀」。（权限页那里的说明也指着这一页。）
                    //
                    // 图标用「省电」而不是「闪电」：系统设置里那一项就叫「电池优化」，
                    // 用户是拿着这行的名字去系统里找对应开关的。
                    icon = Icons.Outlined.BatterySaver,
                    title = stringResource(R.string.msp_settings_keep_alive),
                    subtitle = KeepAliveSummaries.entry(keepAliveState).string(),
                    onClick = onOpenKeepAlive,
                )
            }
            item {
                SettingActionRow(
                    // 排在「关于」之前而不是放进去：「检查更新」是个**动作**
                    // （按下去要去下载、要跳系统安装器），而「关于」里全是
                    // 看一眼就走的只读信息。把它放进关于页会把一个动作藏在
                    // 一个信息页的后面，而这一行本身给的信息（当前版本）
                    // 和关于页里那行重复，但用户找「更新」时看的是入口页。
                    icon = Icons.Outlined.SystemUpdate,
                    title = stringResource(R.string.msp_settings_update),
                    subtitle = UpdateSummaries.entry(buildInfo).string(),
                    onClick = onOpenUpdate,
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
