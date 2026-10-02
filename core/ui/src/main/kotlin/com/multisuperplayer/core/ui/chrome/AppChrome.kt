package com.multisuperplayer.core.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用外壳（底部导航栏）的可见性。
 *
 * ## 为什么需要它
 *
 * 全屏播放要把底部导航栏让出来，但**决定全屏的是播放页**（树的很深处），
 * 而**画底部导航栏的是 `MspAppScaffold` 的 `Scaffold`**（树的很浅处）。
 * 两者之间隔着 `NavHost`，导航状态里**不能**塞这个标志：`MspApp` 那套
 * 「切标签就丢弃播放页」的逻辑依赖返回栈干净，往 `NavBackStackEntry` 的
 * `SavedStateHandle` 里塞一个「页面自己要不要全屏」的布尔量，等于把 UI 状态
 * 混进了导航状态，而且播放页被丢弃时它不会自动清——切回设置页会发现底部栏没了。
 *
 * 所以用一个和 [com.multisuperplayer.core.ui.theme.ArtworkAccentState] 完全同构的
 * 共享可变对象：深处写、浅处读，中间不需要任何参数链。
 *
 * ## 为什么是「隐藏」而不是「不可见」
 *
 * `bottomBar = { }` 空实现和「不提供 bottomBar」在 `Scaffold` 里结果不同：
 * 前者仍然占位（`innerPadding` 里留着导航栏的高度），全屏时画面下方会有一条
 * 空白。这里直接返回 null 的 `bottomBar`，`Scaffold` 才会把底部内边距也一起收掉。
 */
@Stable
class AppChromeState {

    /**
     * 底部导航栏是否可见。
     *
     * 默认 true：任何没显式声明全屏的页面（媒体库、设置）都保持原样，
     * 不需要它们自己再打开一次。
     */
    var bottomBarVisible: Boolean by mutableStateOf(true)
        private set

    /**
     * 写入入口。
     *
     * 叫 `updateBottomBarVisible` 而不是 `setBottomBarVisible`：后者会和上面那个
     * 属性的 setter 撞 JVM 签名（都是 `setBottomBarVisible(Z)V`），Kotlin 报
     * 「Platform declaration clash」。这个错误在编辑器里常看不到，只有真编译才出。
     */
    fun updateBottomBarVisible(visible: Boolean) {
        bottomBarVisible = visible
    }
}

/**
 * 没人提供时的默认实例。
 *
 * 单测和预览里直接渲染 `MspAppScaffold`（或者某个页面）不会因为 local 没提供而炸；
 * 往一个没人读的对象里写也是无害的。
 */
private val DefaultAppChromeState = AppChromeState()

val LocalAppChromeState = staticCompositionLocalOf { DefaultAppChromeState }

/**
 * 供给方（`MspApp`）和消费方（播放页）都用的语法糖。
 *
 * 读取 `bottomBarVisible` 的那些组合函数会在全屏切换时重组，别的不会——
 * 这就是用 [staticCompositionLocalOf] 传**对象**而不是传**值**的意义。
 */
val LocalAppChrome: AppChromeState
    @Composable get() = LocalAppChromeState.current
