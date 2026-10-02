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
import com.multisuperplayer.core.ui.theme.ArtworkAccentState
import com.multisuperplayer.core.ui.theme.LocalArtworkAccentState
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspTheme
import com.multisuperplayer.feature.library.LibraryRoute
import com.multisuperplayer.feature.player.PlayerRoute
import com.multisuperplayer.feature.settings.SettingsRoute
import com.multisuperplayer.feature.settings.SettingsViewModel
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

    CompositionLocalProvider(LocalArtworkAccentState provides artworkAccentState) {
        MspTheme(
            baseTheme = MspBaseTheme.fromId(theme.baseThemeId),
            accent = MspAccent.fromId(theme.accentId),
            // null = 用户没设置过，用默认（开）。
            useDynamicColor = theme.useDynamicColor ?: true,
            colorFromArtwork = theme.colorFromArtwork ?: false,
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

@Composable
private fun MspAppScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playbackViewModel: AppPlaybackViewModel = koinViewModel()

    Scaffold(
        bottomBar = {
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
                            navController.popBackStack(MspDestination.PLAYER.route, inclusive = true)
                            navController.navigate(destination.route) {
                                // 这里**不**写 saveState/restoreState：三个标签页各自都是
                                // 单页，真正需要留住的状态（列表滚动位置、筛选词）挂在
                                // **媒体库自己的** NavBackStackEntry 上，而它作为栈底
                                // 永远不会被弹出，不必靠「存取返回栈」来保。
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
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
            composable(MspDestination.PLAYER.route) { PlayerRoute() }

            // 这里**不**新建 SettingsViewModel：viewModelStoreOwner 是 Activity，
            // 所以拿到的和 MspApp 顶层那个是同一个实例 —— 用户在设置页改主题，
            // 上面 MspTheme 的实参会立刻跟着变。若这里改成独立作用域，
            // 主题就会变成「要重启才生效」。
            composable(MspDestination.SETTINGS.route) { SettingsRoute() }
        }
    }
}
