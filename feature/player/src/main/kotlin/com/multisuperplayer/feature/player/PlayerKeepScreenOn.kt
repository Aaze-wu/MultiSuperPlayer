package com.multisuperplayer.feature.player

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 「播放时不让屏幕自动熄灭」的判据。
 *
 * 单独抽成一个纯对象，是因为这条功能真正会出错的地方全是**条件**，而不是系统调用：
 * 「暂停了该不该放开」「画中画里算不算」「临时开关和设置里那个谁优先」。这三件事在
 * 真机上都要出画面、等超时、还要看得到屏幕真的灭没灭才能验一次，而写成纯函数之后
 * 在 JVM 单测里就是几行断言（同 [PlayerPipRules] 的理由：`unitTests.isReturnDefaultValues`
 * 让框架类在单测里不可用，所以系统调用那一层越薄越好）。
 */
internal object PlayerKeepScreenOnRules {

    /**
     * 现在该不该让屏幕保持常亮。
     *
     * 三个条件缺一不可：
     *
     * - **正在播放**。暂停就把限制放开，这一条是整个功能里唯一会被用户察觉的细节：
     *   按下暂停、把手机放下，那一刻正是他最期待屏幕自己熄掉的时候。做成「进了播放页
     *   就一直亮着」的话，用户放下手机去看书，回来发现屏幕亮了一小时——那是比
     *   「看着看着黑了」更让人恼火的结果（后者只是烦，前者是耗电和烧屏）。
     * - **用户没关掉它**（设置里的默认值与播放页的临时开关已经合成一个值）。
     * - **不在画中画里**。画中画是「用小窗继续看、同时去干别的事」，而干别的事的时候
     *   屏幕必须能按用户的习惯熄灭。把这个小窗算作「正在播放所以不许熄屏」，等于
     *   让一个两百 dp 的小窗把整块屏幕钉亮。
     */
    fun shouldKeepScreenOn(isPlaying: Boolean, preference: Boolean, inPip: Boolean): Boolean =
        isPlaying && preference && !inPip
}

/**
 * 让当前窗口在 [keepOn] 期间保持常亮。
 *
 * ## 为什么用窗口 flag，而不是 `view.keepScreenOn`
 *
 * 这一页原先写的是 `PlayerView.keepScreenOn = isPlaying`（见 [PlayerVideoSurface] 的历史），
 * 那个写法有两个漏掉的情况，而且两个都是**静默**的：
 *
 * 1. **音频没有 `PlayerView`**。纯音频页根本不构造那层视图，于是「播放时不让屏幕熄灭」
 *    对听歌、听播客完全不生效——而用户看不出区别，只会觉得「这个开关有时管用有时不管用」。
 * 2. **`View.keepScreenOn` 是「这个视图可见时才要求常亮」**。它由 `ViewRootImpl` 在
 *    遍历时汇总成窗口的 flag，一旦视图不在树上（切版面、进画中画、离开这一页）就自动
 *    撤销——看起来像是优点，但它和「我们自己想在某个时刻放开」这件事没有共同语言：
 *    没有任何一处能表达「暂停了，现在允许熄灭」以外的状态（比如「临时开关被关掉了」
 *    在视图还挂着的时候必须立刻放开）。
 *
 * 窗口 flag 只有一处、覆盖两种内容、并且能被一次 `DisposableEffect` 精确地开关。
 * 代价是它**是粘的**：`addFlags` 之后没人清就会一直亮着，所以 [onDispose] 里的
 * `clearFlags` 不是清理代码，而是功能本身的一半（和 `PlayerFullscreenEffect` 里
 * 「方向也要在 onDispose 里还回去」同一条规矩）。
 *
 * ## 为什么无状态那一层不调它
 *
 * 这个组合函数只该在**真的有一个窗口**的地方调用（`PlayerRoute`）。预览里没有 Activity
 * （[findActivity] 返回 null），这里对 null 的处理是「什么都不做」——不崩，但也别指望
 * 预览里能看到效果。
 */
@Composable
fun PlayerKeepScreenOnEffect(keepOn: Boolean) {
    val activity = LocalContext.current.findActivity()
    // key 里带上 `activity`：CompatActivity 换一个宿主窗口（比如应用内切到另一个
    // Activity）时，旧窗口上那个 flag 必须被清掉，而只写 `keepOn` 是不会触发
    // onDispose 的（同一个 key ⇒ 不重建 ⇒ 旧窗口一直亮着）。
    DisposableEffect(activity, keepOn) {
        val window = activity?.window
        if (window != null && keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            // 无条件清：进到这里只可能是「这一个 effect 刚才把 flag 加上去过」，
            // 或者是「它从来没加过」——两种情况下清一次都是对的，
            // 而条件清理反而会漏掉「先开、后关」那条路径（keepOn 从 true 变 false 时
            // 走的是 onDispose + 重新执行，中间没有别的机会清）。
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
