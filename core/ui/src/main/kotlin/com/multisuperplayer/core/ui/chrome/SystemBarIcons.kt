package com.multisuperplayer.core.ui.chrome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.view.WindowCompat

/**
 * 状态栏 / 导航栏**图标**的明暗，跟着**应用自己的主题**走。
 *
 * ## 这个 bug 的形状（v1.2.4）
 *
 * 图标的明暗原本有两个来源，两个都跟「用户选的主题」无关：
 *
 * 1. `app/src/main/res/values(-night)/themes.xml` 里的 `windowLightStatusBar`：
 *    它只认**系统的**深浅色；
 * 2. `MainActivity.onCreate` 里的 `enableEdgeToEdge()`：它默认按
 *    `isSystemInDarkTheme` 挑图标颜色，而且**只在那一次**挑。
 *
 * 于是只要「应用主题」和「系统深浅色」不一致，图标就会和背景撞在一起：
 * 系统深色 + 应用里选浅色 ⇒ 白色图标画在近白背景上；系统浅色 + 应用里选深色
 * （或纯黑）⇒ 深色图标画在黑背景上。症状就是「状态栏某些情况下看不清」，
 * 而它**不是**某个页面的问题，所以修在这里而不是各个屏幕里。
 *
 * 更糟的一条：Manifest 的 `configChanges` 带 `uiMode`，所以系统切换深浅色时
 * Activity 不会重建，`onCreate` 里的 `enableEdgeToEdge()` 也不会再跑一次——
 * 图标就停在旧颜色上，直到下次冷启动。
 *
 * ## 为什么是 Compose 副作用，而不是再改一次 XML
 *
 * XML 拿不到 DataStore 里的主题选择（冷启动那一帧只能按系统色画，这是它能做的
 * 全部）。真正的答案要等设置读出来，而那时 Compose 已经在跑了；`MspTheme` 是
 * 唯一知道「现在到底是浅色还是深色」的地方，副作用挂在它上面，就自动获得了：
 *
 * - 切换主题设置 ⇒ `isDark` 变 ⇒ 重跑；
 * - 系统深浅色变化 ⇒ `isSystemInDarkTheme()` 变 ⇒ 重组 ⇒ 重跑。
 *
 * `enableEdgeToEdge()` 仍然是必要的（它做的是「铺满屏幕」这件事），
 * 这里的写入在它之后执行，所以**我们说了算**。
 *
 * @param useDarkIcons 图标用深色（= 背景是浅色）。传 `!isDark`。
 */
@Composable
fun SystemBarIconAppearanceEffect(useDarkIcons: Boolean) {
    // 预览里没有真窗口，别去碰 WindowInsetsController。
    if (LocalInspectionMode.current) return
    val window = LocalContext.current.findActivity()?.window ?: return

    // key 里带 useDarkIcons：明暗是唯一需要重写的东西，换句话说这里
    // 只该在它变的时候写一次，而不是每次重组都写。
    DisposableEffect(window, useDarkIcons) {
        applySystemBarIconAppearance(window, useDarkIcons)
        onDispose { }
    }
}

/**
 * 把图标的明暗写到窗口上。
 *
 * 公开是因为播放页要**自己再声明一次**：全屏时系统栏是隐藏的，用户从边缘划出来
 * 的那一刻它浮在画面上（深色），图标必须按「深色背景」处理，而不是按主题——
 * 浅色主题下按主题算会得到深色图标，压在深色画面上等于看不见。
 *
 * 两个 flag 都写：只改状态栏会让底部导航栏在横屏时留一半错色，而
 * `WindowInsetsControllerCompat` 已经把 API 26/27（`SYSTEM_UI_FLAG_LIGHT_*`）
 * 和 30+（`WindowInsetsController`）的差异吃掉了，这里不需要版本判断。
 */
fun applySystemBarIconAppearance(window: Window?, useDarkIcons: Boolean) {
    if (window == null) return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = useDarkIcons
    controller.isAppearanceLightNavigationBars = useDarkIcons
}

/**
 * 从 Context 上找到真正的 Activity。
 *
 * Compose 给的 `LocalContext` 可能是包了好几层的 `ContextWrapper`
 * （`ContextThemeWrapper`、`ComponentActivity` 自己的包装），一层 `as? Activity`
 * 会静默拿到 null——症状是「副作用跑了但什么都没发生」，不报错。
 *
 * 和 `feature:settings`、`feature:player` 里那两个同名函数是同一件事；
 * 那两处先于这里存在，改它们属于无关重构，所以先各留一份，
 * 新增的调用方用这一份（`core:ui` 在依赖图的下游，谁都能引用）。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
