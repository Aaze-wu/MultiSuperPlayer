package com.multisuperplayer.player

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.ui.chrome.AppChromeState
import com.multisuperplayer.core.ui.chrome.LocalAppChrome
import com.multisuperplayer.core.ui.chrome.LocalAppChromeState
import com.multisuperplayer.core.ui.text.string
import com.multisuperplayer.core.ui.theme.ArtworkAccentState
import com.multisuperplayer.core.ui.theme.LocalArtworkAccentState
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults
import com.multisuperplayer.feature.library.BrowseRoute
import com.multisuperplayer.feature.library.LibraryRoute
import com.multisuperplayer.feature.library.PlaylistsRoute
import com.multisuperplayer.feature.library.RecentRoute
import com.multisuperplayer.feature.player.PlayerRoute
import com.multisuperplayer.feature.settings.AboutRoute
import com.multisuperplayer.feature.settings.AppearanceSettingsRoute
import com.multisuperplayer.feature.settings.AsrSettingsRoute
import com.multisuperplayer.feature.settings.KeepAliveRoute
import com.multisuperplayer.feature.settings.UpdateRoute
import com.multisuperplayer.feature.settings.LocalModelSettingsRoute
import com.multisuperplayer.feature.settings.PermissionsRoute
import com.multisuperplayer.feature.settings.PermissionsViewModel
import com.multisuperplayer.feature.settings.PlaybackSettingsRoute
import com.multisuperplayer.feature.settings.SettingsRoute
import com.multisuperplayer.feature.settings.SettingsViewModel
import com.multisuperplayer.feature.settings.StartupUpdateDialog
import com.multisuperplayer.feature.settings.TranslationSettingsRoute
import com.multisuperplayer.feature.settings.UpdateViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 应用级脚手架：主题 + 底部导航 + 导航图。
 *
 * 三个标签页都是**顶层**目的地，互相之间是平级切换而不是压栈，
 * 所以切标签时用 [findStartDestination] + `launchSingleTop` 把栈压回起点，
 * 重复点同一个标签不会越点越深。
 *
 * **刻意不用**官方那套 `saveState + restoreState`：它的存/取 key 是「起始目的地」，
 * 也就是媒体库，于是所有压在媒体库之上的页面（尤其是播放页）都会变成
 * 「媒体库标签的返回栈」的一部分，切回来时被一起恢复。详见 [MspAppScaffold]
 * 里那段切换逻辑上的注释。
 */
@Composable
fun MspApp() {
    // 主题包在最外层：它要覆盖底部导航栏，也要覆盖以后可能出现的对话框。
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()

    // 首次启动（安装后第一次进来）的那一次权限申请。
    //
    // 三条刻意的取舍：
    // - **放在这里而不是 `MainActivity.onCreate`**：`onCreate` 里弹授权框会抢在首帧
    //   之前，用户看到的是「应用刚打开就一个弹窗」而不知道它是谁。等到界面画完
    //   再延迟一小段，用户至少已经看到媒体库的样子。
    // - **延迟 1.2 秒而不是等某个信号**：要的效果是「界面已经稳定」，没有一个可靠的
    //   「首帧画完了」事件可以等（`LaunchedEffect` 只保证组合完成，不保证已经绘制）。
    // - **只做一次**：判断和记账在 `AppPermissions.hasAskedAtStartup/markStartupAsked`
    //   里（那是**安装级**的一次性开关）。没有这一层，每次冷启动都会弹一次
    //   —— 系统在用户拒绝后不会重复弹框，于是用户看到的是「启动时屏幕闪一下」。
    val permissionsViewModel: PermissionsViewModel = koinViewModel()

    // 全应用**唯一**的权限 launcher，挂在根上而不是权限页里。
    //
    // 为什么必须有待办通道（`pending`）而不是直接调：申请权限是「一次性事件」，
    // 必须发生在组合完成之后。若直接把 launcher 递给每一行，点一下就调一次，
    // 而同一个 launcher 同时弹两个框时后一个会把前一个挤掉——两个框都以为
    // 「已经申请过了」的那个则会被记账（`markAsked`），于是永久拒绝与「没问过」
    // 分不开。所以申请请求统一排队，根上一次只处理一个。
    val pendingPermissions by permissionsViewModel.pending.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 结果本身不看：`RequestMultiplePermissions` 回的是「这一次问了什么」，
        // 而真相在系统里（媒体那一项还要看「只勾了一部分视频」这种结果）。
        //
        // 立刻用当前 Activity 重算一次状态，而不是只等页面的 `ON_RESUME`：
        // 带 Activity 的刷新能分清「被拒过一次」与「已永久拒绝」，而有些权限
        // 系统根本不弹框就直接拒（例如没有蓝牙适配器的设备上的 `BLUETOOTH_CONNECT`），
        // 那种情况压根没有 ON_RESUME 可等——不在这里刷新的话，权限页会一直停在
        // 「未开启，可以在这里申请」，按下去什么都不会发生。
        permissionsViewModel.onRequestLaunched(activity)
    }

    // 启动时那一次自动检查更新用的实例。
    //
    // 建在这一层（而不是更新页里）是刻意的：它要活得比任何一个页面久，
    // 因为查到的东西会变成一个浮在整个界面之上的弹窗，而那个弹窗和用户
    // 当前在哪一页无关。理由与作用域问题见 `UpdateViewModel` 的类注释。
    val updateViewModel: UpdateViewModel = koinViewModel()

    // 需求③：安装后的第一次启动申请一次权限；紧接着（等权限框处理完）查一次更新。
    //
    // 两件事写在同一个 `LaunchedEffect` 里，是因为它们之间有一条**顺序**要求：
    // 更新弹窗必须等权限流程走完。两个框同时挂在屏幕上时，权限框是系统的、
    // 更新框是本应用自己的，谁盖住谁不由我们决定，而用户看到的是
    // 「刚打开就一堆框、而且不知道它们在问什么」。
    //
    // `requestAtStartup()` 是同步的（记账和排队都在返回前做完），所以它返回之后
    // `pending.value` 就是确定的：非空 = 有一个权限框正在等系统回答。
    // 不能靠「等一会儿看看」——那个间隔要多少是个纯猜的值。
    LaunchedEffect(Unit) {
        delay(STARTUP_PERMISSION_DELAY_MS)
        permissionsViewModel.requestAtStartup()
        if (permissionsViewModel.pending.value != null) {
            // 等系统框被回答（`onRequestLaunched` 会把待办清成 null）。
            // **带上限**：等不到也不能干脆不查——那会让「每次启动查一次」
            // 在最需要它的时候静默失效，而且不留下任何痕迹。
            withTimeoutOrNull(PERMISSION_SETTLE_TIMEOUT_MS) {
                permissionsViewModel.pending.first { it == null }
            }
        }
        updateViewModel.checkAtLaunch()
    }

    // 待办一旦出现就交给 launcher（首次启动那一次也走这条路，所以它同样会被弹出来）。
    LaunchedEffect(pendingPermissions) {
        val requested = pendingPermissions ?: return@LaunchedEffect
        permissionLauncher.launch(requested.toTypedArray())
    }

    // 封面取色的通道。持有者放在这一层只有一个原因：MspTheme 在树的最外层，
    // 而真正知道「现在放的是哪张封面」的是深处的播放页——让深处写、浅处读，
    // 比从 App 往下打通一条参数链短得多。remember 保证只建一次。
    val artworkAccentState = remember { ArtworkAccentState() }

    // 底部导航栏的显隐通道，同一种模式：播放页知道自己进全屏了，
    // 而需要让位的 NavigationBar 在这上面好几层。
    val chromeState = remember { AppChromeState() }

    CompositionLocalProvider(
        LocalArtworkAccentState provides artworkAccentState,
        LocalAppChromeState provides chromeState,
    ) {
        MspTheme(
            baseTheme = MspBaseTheme.fromId(theme.baseThemeId),
            accent = MspAccent.fromId(theme.accentId),
            // null = 用户没设置过，用 [MspThemeDefaults] 里的默认值（两个都是关）。
            useDynamicColor = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR,
            colorFromArtwork = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK,
        ) {
            MspAppScaffold(updateViewModel = updateViewModel)
        }
    }
}

private enum class MspDestination(
    val route: String,
    val label: MspText,
    val icon: ImageVector,
) {
    LIBRARY("library", MspText.Res(R.string.msp_nav_library), Icons.Outlined.LibraryMusic),
    BROWSE("browse", MspText.Res(R.string.msp_nav_browse), Icons.Outlined.FolderOpen),
    RECENT("recent", MspText.Res(R.string.msp_nav_recent), Icons.Outlined.History),
    // QueueMusic 有 auto-mirrored 版本：RTL 语言里这个图标是带方向的
    // （音符后面跟一条线），直接写 Icons.Outlined.QueueMusic 在阿拉伯语下会画反。
    PLAYLISTS(
        "playlists",
        MspText.Res(R.string.msp_nav_playlists),
        Icons.AutoMirrored.Outlined.QueueMusic,
    ),
    SETTINGS("settings", MspText.Res(R.string.msp_nav_settings), Icons.Outlined.Settings),
    ;

    companion object {
        val START: MspDestination = LIBRARY
    }
}

/**
 * 播放页不再是标签页：它从迷你播放器推上来（媒体库/最近播放/播放列表里点一条也直接进）。
 *
 * 它仍然是**真的导航目的地**（不是 `if (showPlayer)`），理由和其它子页面一样：
 * 用户在播放页按返回键应该回到刚才那个列表，而不是退出应用。
 */
private const val PLAYER_ROUTE = "player"

/**
 * 翻译设置不是标签页，而是从设置页（或播放页字幕面板）推上来的普通目的地。
 * 它必须是一个**真的导航目的地**，而不是设置页内部的一个 `if (showX)`：
 * 否则用户在那里按返回键就会直接退出设置（甚至退出应用），
 * 而他只是想把这一页关掉。
 */
private const val TRANSLATION_SETTINGS_ROUTE = "settings/translation"

/**
 * 设置入口页底下的四张子页面。
 *
 * 它们和 [TRANSLATION_SETTINGS_ROUTE] 是同一回事：都是真目的地。
 * 返回键只会弹掉当前这一张，回到设置入口页，而不是直接退出设置。
 *
 * 路径前缀统一为 `settings/`：将来要加子页面时，光看字符串就知道它挂在设置下面。
 */
private const val APPEARANCE_SETTINGS_ROUTE = "settings/appearance"
private const val PLAYBACK_SETTINGS_ROUTE = "settings/playback"
// 模型管理要能离开页面继续下载（见 `AsrSettingsViewModel`），所以它是独立目的地；
// 下载任务活在它自己的 `viewModelScope` 里，返回设置页不会把下载掐掉。
private const val ASR_SETTINGS_ROUTE = "settings/asr"
private const val ABOUT_ROUTE = "settings/about"

/**
 * 权限页：列出用户能自己改的那四项权限（媒体读取 / 所有文件访问 / 通知 / 蓝牙）。
 *
 * 它是一个**真目的地**，而不是把原来的「文件访问」那一行继续当外链：现在那一行进去
 * 是一份清单，而「所有文件访问」只是其中一行。这也是 README 第 7 节里那句
 * 「设置页里有一行『文件访问』，点它跳到系统的授权页面」变成过去式的原因。
 */
private const val PERMISSIONS_ROUTE = "settings/permissions"

/**
 * 后台保活页：电池优化白名单 + 厂商后台管理入口。
 *
 * 和权限页一样是**真目的地**。两页分开是因为它们解决的不是同一类问题：
 * 权限页列的是「应用声明过什么」（系统说了算），这一页处理的是
 * **厂商自己加的那层后台限制**（连 API 都没有，只能靠包名猜页面）。
 * 合成一页会让用户以为「权限都给了就不会被杀」。
 */
private const val KEEP_ALIVE_ROUTE = "settings/keep-alive"

/**
 * 检查更新页：现在的数据源是 GitHub Releases，但页面上看到的只有「有没有新版本」。
 *
 * 它是**真目的地**而不是一个对话框：下载要能离开页面继续跑（任务活在
 * `UpdateViewModel` 的 `viewModelScope` 里），而且用户可以在下载期间去看
 * 版本说明、改通道、填令牌。对话框一关就把这些全掐了。
 */
private const val UPDATE_ROUTE = "settings/update"

/**
 * 首次启动申请权限前先等多久（毫秒）。
 *
 * 意义不是「等界面画完」——没有这样的事件可等——而是「别抢在用户看清这是哪个应用之前」。
 * 1200ms 是实测手感：媒体库第一帧已经出来（首页要等一次媒体库扫描，扫描完才有东西），
 * 而用户也还没有时间点进任何一处。想改这个值只需要改这一处。
 */
private const val STARTUP_PERMISSION_DELAY_MS = 1200L

/**
 * 等权限框被回答的最长时间（毫秒）。
 *
 * 权限框一般几秒内就有答案（点「允许」/「不允许」，或者在框外点一下把它关掉）。
 * 这条上限防的是**永远等不到**：系统框在某些设备上可能既不回调也不关闭
 * （用户把应用切到后台、框还挂在系统那边），那样一来更新检查就整次启动都不会发生。
 * 30 秒之后再查——此时可能和权限框重叠，但重叠比「永远不查」好。
 */
private const val PERMISSION_SETTLE_TIMEOUT_MS = 30_000L

/**
 * 本地翻译模型的下载与删除。
 *
 * 比 [TRANSLATION_SETTINGS_ROUTE] 多一层（挂在翻译设置下面）：它从那一页的
 * 「本地模型」那一行进来，而不是直接从设置入口页。
 *
 * 它必须是**独立目的地**，而不是翻译设置页里的一块：下载要能离开这一屏继续跑，
 * 而下载任务活在 `LocalModelSettingsViewModel` 自己的 `viewModelScope` 里——
 * 返回上一页不会把它提前。另外这一页的每个动作都要读磁盘、跑几十秒，
 * 不应该让「只是来改目标语言」的人碰上。
 */
private const val LOCAL_MODEL_ROUTE = "settings/translation/local"

@Composable
private fun MspAppScaffold(updateViewModel: UpdateViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playbackViewModel: AppPlaybackViewModel = koinViewModel()
    val chrome = LocalAppChrome
    // 启动弹窗的数据源。读的是 `MspApp` 建的那个实例（同一个 Activity 作用域），
    // 所以用户在弹窗上点「忽略此版本」之后，更新页立刻也知道这一版被忽略了。
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // 切标签只在这里定义一次。播放页里的「去设置」也要走同一条路径：
    // 先摘掉播放页、再导航，否则返回栈里会叠成「播放页 → 设置」，
    // 而用户点底部「设置」看到的是另一番景象（返回键回不到播放页）。
    // 复制一份逻辑到播放页迟早会和这里分叉——一处改了另一处没改，
    // 症状是「从字幕面板去设置之后返回键失灵」。
    val navigateToTab: (MspDestination) -> Unit = { destination ->
        navController.popBackStack(PLAYER_ROUTE, inclusive = true)
        navController.navigate(destination.route) {
            // 这里**不**写 saveState/restoreState：每个标签页各自都是单页，
            // 真正需要留住的状态（列表滚动位置、筛选词）挂在**那个标签自己的**
            // NavBackStackEntry 上，而它作为栈底永远不会被弹出，不必靠
            // 「存取返回栈」来保。而一旦写上，播放页会被当成「起始标签的状态」
            // 一起保存，于是「在播放页点媒体库」会立刻把它恢复回来——看起来就是没反应。
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    // 三个页面都能「点一条就放」：走同一个入口，保证「队列 = 当前列表」
    // 这条规则不会只在某一个页面上成立。
    val playFrom: (List<MediaEntry>, Int) -> Unit = { entries, startIndex ->
        playbackViewModel.startPlayback(entries, startIndex)
        navController.navigate(PLAYER_ROUTE) { launchSingleTop = true }
    }

    // 用户在文件浏览页点了一条字幕文件 —— 跨页信箱，就一个值。
    //
    // 为什么住在这一层（而不是某个 ViewModel 里）：它是浏览页和播放页之间**唯一**的
    // 通道。两个页面属于互不认识的模块（`feature:library` / `feature:player`），
    // 而 `SubtitleViewModel` 是播放页私有的（`PlayerRoute` 里 `koinViewModel()`，
    // 离开播放页就销毁）——没有一个共同的祖先能同时拿到两端，只有导航图这一层能。
    //
    // 用 `remember` 而不是 `rememberSaveable` 是故意的：[SubtitleSource] 不是可序列化
    // 类型，而旋转会丢的也只是「用户得再点一次这条字幕」——它生效了就会立刻被消费掉，
    // 能被旋转丢掉的只可能是还没生效的那一个。
    var pendingSubtitle by remember { mutableStateOf<SubtitleSource?>(null) }

    val openSubtitle: (SubtitleSource) -> Unit = { source ->
        pendingSubtitle = source
        // 直接跳到播放页看结果：用户在这一页点字幕的全部意图就是「给正在看的那个
        // 片子挂上」，停在这一页只会让他以为没生效。`launchSingleTop` 与 [playFrom]
        // 同一条理由。
        navController.navigate(PLAYER_ROUTE) { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            // 全屏播放时整条导航栏都不画。
            //
            // 必须**连根拔掉**（不画）而不是画一个高度为 0 的容器：
            // `Scaffold` 会先量 bottomBar、再把它的高度加进 `innerPadding`，
            // 一个高度为 0 的 NavigationBar 仍会按自己的最小高度参与测量，
            // 于是 `innerPadding.bottom` 不为零，全屏画面底部会白留一条。
            // 而且这条留白只有在真机上才看得出来（预览里没有导航栏高度）。
            if (!chrome.bottomBarVisible) return@Scaffold
            // 迷你播放器叠在导航栏**上面**，而不是塞进某一页里：它属于应用骨架，
            // 在哪个标签上都该在那儿；放进媒体库页的话，切到设置它就不见了。
            //
            // 唯一的例外是播放页自己：那个页面整屏就是它要指向的东西，
            // 再叠一条同名的迷你播放器只是白占 130px，还把播放器自己的进度条
            // 和底部的播放/暂停按钮挤在了一起。注意这里用的是「路由」而不是
            // `chrome.bottomBarVisible`——底部栏的显示由全屏逻辑单独管，
            // 两处都去写同一个开关会互相覆盖（谁后跑谁赢，看起来就是闪一下）。
            val nowPlaying by playbackViewModel.nowPlaying.collectAsStateWithLifecycle()
            val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
            Column {
                nowPlaying?.takeIf { currentRoute != PLAYER_ROUTE }?.let { entry ->
                    MiniPlayer(
                        entry = entry,
                        state = playbackState,
                        position = playbackViewModel.positionMs,
                        onClick = {
                            navController.navigate(PLAYER_ROUTE) { launchSingleTop = true }
                        },
                        onTogglePlayPause = playbackViewModel::togglePlayPause,
                        onSkipPrevious = playbackViewModel::skipToPrevious,
                        onSkipNext = playbackViewModel::skipToNext,
                    )
                }
                NavigationBar {
                    MspDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                // 切标签前先把播放页**摘掉**（丢弃，不保存）。
                                //
                                // 播放页不是标签页，而是从列表推上来的普通目的地。
                                // 如果把 "saveState + restoreState" 那套标准写法直接用在这里，
                                // 存/取的 key 是「起始标签」＝媒体库，于是**它之上的一切**
                                // 都会被打包成「媒体库标签的返回栈」，播放页也在里面；
                                // 下一次点「媒体库」时 restoreState 会把它原样恢复回来。
                                // 表现就是：在播放页点「媒体库」没反应，从设置页点「媒体库」
                                // 反而莫名其妙跳回播放页。
                                //
                                // 直接丢弃没有任何代价：播放页显示什么完全由 PlayerViewModel
                                // 从 PlaybackController（Koin 单例）读出来，不靠导航参数，
                                // 也不在导航状态里存任何东西——底层播放也照旧继续。
                                navigateToTab(destination)
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label.string()) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MspDestination.START.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(MspDestination.LIBRARY.route) {
                LibraryRoute(
                    // 重复点同一个列表项不应该在返回栈里堆出十几个播放页，
                    // 所以这条导航统一走 playFrom 里的 launchSingleTop。
                    onPlayRequest = playFrom,
                )
            }
            composable(MspDestination.BROWSE.route) {
                BrowseRoute(
                    onPlayRequest = playFrom,
                    onOpenSubtitle = openSubtitle,
                )
            }
            composable(MspDestination.RECENT.route) {
                RecentRoute(onPlayRequest = playFrom)
            }
            composable(MspDestination.PLAYLISTS.route) {
                PlaylistsRoute(onPlayRequest = playFrom)
            }
            composable(PLAYER_ROUTE) {
                PlayerRoute(
                    // 竖屏左上角那个「收起」。它和系统返回键在这里是**同一件事**：
                    // 播放页只有一个上级，弹掉它就回到刚才那个列表（继续播放，
                    // 底部换成迷你播放器）。不要在这里 pop 到某个固定目的地——
                    // 用户可能是从媒体库、浏览页、最近播放或播放列表进来的。
                    onCollapse = { navController.popBackStack() },
                    // 用户在文件浏览页指定的字幕（如果有）。取走即清空：这条请求只对
                    // 「刚跳过来的这一次」有效，留着它会让下一次从媒体库进播放页时
                    // 莫名其妙又挂上同一条。清空必须发生在**应用之后**，所以由
                    // 播放页在真的调完 selectSource 后回调告诉我们。
                    pendingSubtitle = pendingSubtitle,
                    onPendingSubtitleApplied = { pendingSubtitle = null },
                    // 字幕面板里的「去设置」：没配置翻译服务时，用户要在同一个界面里
                    // 走到填密钥的地方。非要他先退出面板、再点底部设置，是没必要的绕路。
                    // 直接落到翻译设置页，而不是设置页的第一屏（那里全是主题）。
                    onOpenTranslationSettings = {
                        navController.popBackStack(PLAYER_ROUTE, inclusive = true)
                        navController.navigate(MspDestination.SETTINGS.route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                        navController.navigate(TRANSLATION_SETTINGS_ROUTE) { launchSingleTop = true }
                    },
                )
            }

            // 这里**不**新建 SettingsViewModel：viewModelStoreOwner 是 Activity，
            // 所以拿到的和 MspApp 顶层那个是同一个实例 —— 用户在设置页改主题，
            // 上面 MspTheme 的实参会立刻跟着变。若这里改成独立作用域，
            // 主题就会变成「要重启才生效」。
            composable(MspDestination.SETTINGS.route) {
                // 设置页现在只是入口：只列条目和「当前状态」，设置项本身在子页面里。
                // 这样加新设置项只会让这个列表变长一行，不会把一页堆成一千行。
                SettingsRoute(
                    onOpenAppearance = { navController.navigate(APPEARANCE_SETTINGS_ROUTE) },
                    onOpenPlayback = { navController.navigate(PLAYBACK_SETTINGS_ROUTE) },
                    onOpenTranslationSettings = { navController.navigate(TRANSLATION_SETTINGS_ROUTE) },
                    onOpenAsrSettings = { navController.navigate(ASR_SETTINGS_ROUTE) },
                    onOpenAbout = { navController.navigate(ABOUT_ROUTE) },
                    onOpenPermissions = { navController.navigate(PERMISSIONS_ROUTE) },
                    onOpenKeepAlive = { navController.navigate(KEEP_ALIVE_ROUTE) },
                    onOpenUpdate = { navController.navigate(UPDATE_ROUTE) },
                )
            }
            composable(TRANSLATION_SETTINGS_ROUTE) {
                TranslationSettingsRoute(
                    onBack = { navController.popBackStack() },
                    // 本地模型的入口就在那一页上（选中的服务商是「设备上运行」时才出现），
                    // 而不是在设置入口页再加一行：那行会与「服务商」这个选择器说同一件事。
                    onOpenLocalModel = { navController.navigate(LOCAL_MODEL_ROUTE) },
                )
            }
            composable(LOCAL_MODEL_ROUTE) {
                LocalModelSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(ASR_SETTINGS_ROUTE) {
                AsrSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(PERMISSIONS_ROUTE) {
                PermissionsRoute(onBack = { navController.popBackStack() })
            }
            composable(KEEP_ALIVE_ROUTE) {
                KeepAliveRoute(onBack = { navController.popBackStack() })
            }

            composable(UPDATE_ROUTE) {
                // **显式传进去**，不用默认的 `koinViewModel()`：
                // 默认那个会把实例挂在 `NavBackStackEntry` 上，于是更新页看到的
                // 是一份全新的状态（「还没有检查过」），而启动弹窗说的是另一个实例
                // 查到的东西。用户看到的就是「弹窗说有新版本、进去说没检查过」。
                UpdateRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = updateViewModel,
                )
            }
            composable(APPEARANCE_SETTINGS_ROUTE) {
                AppearanceSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(PLAYBACK_SETTINGS_ROUTE) {
                PlaybackSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(ABOUT_ROUTE) {
                AboutRoute(onBack = { navController.popBackStack() })
            }
        }
    }

    // 启动检查查到新版本时挂在屏幕上的那个框。
    //
    // 写在 `Scaffold` **外面**、`NavHost` 之外：它说的是一件和「用户当前在哪一页」
    // 无关的事。放进导航图里（比如让设置页去渲染）的话，用户一按返回、或者自己进
    // 设置页看了一眼又退出来，这个框就跟着那一页一起没了——而那正是用户还没做决定
    // 的时候。放在这里，它一直挂到用户选中三个按钮里的一个。
    updateState.startupPrompt?.let { release ->
        StartupUpdateDialog(
            release = release,
            onUpdateNow = {
                // 三步的顺序是有理由的：
                // - **先关框**：不清掉 `startupPrompt` 的话，用户从更新页返回时会看到
                //   一个已经过时的框（那时包可能正在下、或者已经下好了）；
                // - **再跳页**：进度、失败重试、未知来源授权全在更新页上，用户得看着
                //   它们；而安装那一刻要拉起系统安装器，那件事只能由一个**活着的**
                //   页面来接（见 `StartupUpdateDialog` 的注释）；
                // - **最后才开始下载**：`download()` 只写 ViewModel 状态，和跳页没有
                //   竞争关系，但放在后面读起来就是「先让他看到进度条，再让它转起来」。
                updateViewModel.dismissStartupPrompt()
                // `launchSingleTop`：用户本来就停在更新页上时（弹窗是上一次启动留下的、
                // 还没关掉），再压一页进去只会让返回键多按一次。
                navController.navigate(UPDATE_ROUTE) { launchSingleTop = true }
                updateViewModel.download()
            },
            onLater = updateViewModel::dismissStartupPrompt,
            onIgnore = updateViewModel::ignoreStartupPrompt,
        )
    }
}
