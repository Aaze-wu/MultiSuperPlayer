package com.multisuperplayer.feature.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 当前设备是不是横屏。
 *
 * 用它来决定「用哪套布局」，而不是用 [isFullscreen]：全屏与否是**用户的意图**，
 * 横竖屏是**设备的事实**。两者会被同步（进横屏自动进全屏），但它们不是一回事——
 * 横屏按返回键退出全屏之后，设备仍然是横屏，布局就不该跳回竖屏那一套。
 */
@Composable
fun rememberIsLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 全屏的**副作用**：隐藏系统栏 + 把方向锁到横屏。
 *
 * ## 为什么是「锁横屏」而不是「跟随传感器」
 *
 * 全屏 = 用整块屏幕看电影。跟随传感器意味着用户躺在床上侧一下身，画面就转回竖屏
 * ——那时候他要么看不到全屏，要么得把手机举起来。所以进入全屏就锁定横屏，
 * 退出全屏再把方向放开（[ActivityInfo.SCREEN_ORIENTATION_PORTRAIT]，见下）。
 *
 * ## 退出全屏时请求竖屏，而不是 UNSPECIFIED
 *
 * 需求是「按返回键退出全屏」。如果退出时只把方向放开成 `UNSPECIFIED`，而用户
 * 手里还横着拿手机，系统会立刻按当前传感器方向恢复成横屏，紧接着
 * [rememberIsLandscape] 变回 true → 自动重新进入全屏。用户看到的是：按了退出、
 * 底部导航栏闪了一下、然后又全屏了——一次都退不出去。
 *
 * 而请求竖屏是安全的：进入这个组合时 [fullscreen] 的初值就是「当前是否横屏」，
 * 所以「竖屏 + 请求竖屏」是同一个状态，不会发生任何实际的旋转。真要再进横屏，
 * 用户把手机横过来就是一次**新的明确动作**（`configChanges` 会处理这次旋转）。
 *
 * ## 一定要在 onDispose 里复原
 *
 * 两个设置都是**粘性**的：不还回去，用户从播放页退到媒体库，得到的是一个
 * 没有状态栏/导航栏的媒体库；而 `requestedOrientation` 更糟——它会一直
 * 锁着整个 Activity，之后每个页面都只能是横屏。
 */
@Composable
fun PlayerFullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current

    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            // 划一下边缘临时唤出系统栏。没有这一条，用户在全屏里就再也看不到
            // 时间/电量，也拿不到通知栏——那不是一个播放器该有的行为。
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(fullscreen) {
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/**
 * 返回键在播放页上的**阶梯**。
 *
 * 顺序是刻意排的：先撤销「最近一次的、最轻的」状态，最后才离开页面。
 *
 * 1. **锁屏中**：什么都不做，只把解锁按钮**显示出来**。[locked] 的全部意义是
 *    「别让误触改到任何东西」，而返回键是明确的按键、不是误触，所以用它来
 *    唤出解锁按钮是安全的——但绝不能让它顺手解开锁或者退出页面，否则
 *    「锁着放在口袋里，掏出来发现已经退出播放页了」。
 * 2. **全屏中**：退出全屏，留在播放页。这一条就是需求里的「返回键先退全屏」。
 * 3. **其余情况**：`enabled = false`，返回键交还给导航栈（也就是回到媒体库）。
 *
 * 注意这里没有「按两次退出」那种设计：它需要计时器 + 一个正在倒计时的提示，
 * 而它解决的问题（误触返回键）在本应用里不存在——播放页只有一个上级。
 */
@Composable
fun PlayerBackHandler(
    locked: Boolean,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    onRevealLockedControls: () -> Unit,
) {
    BackHandler(enabled = locked || fullscreen) {
        if (locked) onRevealLockedControls() else onExitFullscreen()
    }
}
