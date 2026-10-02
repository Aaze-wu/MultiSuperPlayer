package com.multisuperplayer.player

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.ui.chrome.AppChromeState
import com.multisuperplayer.core.ui.chrome.LocalAppChrome
import com.multisuperplayer.core.ui.chrome.LocalAppChromeState
import com.multisuperplayer.core.ui.theme.ArtworkAccentState
import com.multisuperplayer.core.ui.theme.LocalArtworkAccentState
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults
import com.multisuperplayer.feature.library.LibraryRoute
import com.multisuperplayer.feature.player.PlayerRoute
import com.multisuperplayer.feature.settings.AboutRoute
import com.multisuperplayer.feature.settings.AppearanceSettingsRoute
import com.multisuperplayer.feature.settings.PlaybackSettingsRoute
import com.multisuperplayer.feature.settings.SettingsRoute
import com.multisuperplayer.feature.settings.SettingsViewModel
import com.multisuperplayer.feature.settings.TranslationSettingsRoute
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
            MspAppScaffold()
        }
    }
}

private enum class MspDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    LIBRARY("library", "媒体库", Icons.Outlined.LibraryMusic),
    PLAYER("player", "正在播放", Icons.Outlined.PlayCircle),
    SETTINGS("settings", "设置", Icons.Outlined.Settings),
    ;

    companion object {
        val START: MspDestination = LIBRARY
    }
}

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
private const val ABOUT_ROUTE = "settings/about"

@Composable
private fun MspAppScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playbackViewModel: AppPlaybackViewModel = koinViewModel()
    val chrome = LocalAppChrome

    // 切标签只在这里定义一次。播放页里的「去设置」也要走同一条路径：
    // 先摘掉播放页、再导航，否则返回栈里会叠成「播放页 → 设置」，
    // 而用户点底部「设置」看到的是另一番景象（返回键回不到播放页）。
    // 复制一份逻辑到播放页迟早会和这里分叉——一处改了另一处没改，
    // 症状是「从字幕面板去设置之后返回键失灵」。
    val navigateToTab: (MspDestination) -> Unit = { destination ->
        navController.popBackStack(MspDestination.PLAYER.route, inclusive = true)
        navController.navigate(destination.route) {
            // 这里**不**写 saveState/restoreState：三个标签页各自都是单页，
            // 真正需要留住的状态（列表滚动位置、筛选词）挂在**媒体库自己的**
            // NavBackStackEntry 上，而它作为栈底永远不会被弹出，不必靠
            // 「存取返回栈」来保。而一旦写上，播放页会被当成「媒体库标签的状态」
            // 一起保存，于是「在播放页点媒体库」会立刻把它恢复回来——看起来就是没反应。
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
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
            NavigationBar {
                MspDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            // 切标签前先把播放页**摘掉**（丢弃，不保存）。
                            //
                            // 播放页不是标签页，而是从媒体库推上来的普通目的地。
                            // 如果把 "saveState + restoreState" 那套标准写法直接用在这里，
                            // 存/取的 key 是「起始目的地」＝媒体库，于是**媒体库之上的一切**
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
                        label = { Text(destination.label) },
                    )
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
                    onPlayRequest = { entries, startIndex ->
                        playbackViewModel.startPlayback(entries, startIndex)
                        // 同一个标签重复点不会压栈；从媒体库进播放页也不应该
                        // 在返回栈里堆出十几个播放页。
                        navController.navigate(MspDestination.PLAYER.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(MspDestination.PLAYER.route) {
                PlayerRoute(
                    // 字幕面板里的「去设置」：没配置翻译服务时，用户要在同一个界面里
                    // 走到填密钥的地方。非要他先退出面板、再点底部设置，是没必要的绕路。
                    // 直接落到翻译设置页，而不是设置页的第一屏（那里全是主题）。
                    onOpenTranslationSettings = {
                        navController.popBackStack(MspDestination.PLAYER.route, inclusive = true)
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
                    onOpenAbout = { navController.navigate(ABOUT_ROUTE) },
                )
            }
            composable(TRANSLATION_SETTINGS_ROUTE) {
                TranslationSettingsRoute(onBack = { navController.popBackStack() })
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
}
