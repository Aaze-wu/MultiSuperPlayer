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
 * 所以用 [findStartDestination] + `launchSingleTop` + `restoreState` 这一套
 * 标准写法：重复点同一个标签不会越点越深，切回来还能恢复上次的滚动位置。
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
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
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
