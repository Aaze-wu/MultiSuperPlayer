package com.multisuperplayer.feature.player

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.MspVideoSize

/**
 * 视频画面。
 *
 * `useController = false`：控件由我们自己用 Compose 画。开着 Media3 自带的控件，
 * 它会和 Compose 的控制条同时出现在屏幕上，而且两套 UI 的显隐逻辑会互相打架
 * （Media3 的控件按触摸事件自己淡入淡出，Compose 那边完全不知情）。
 *
 * 之所以还要用 `PlayerView` 而不是 `SurfaceView`：画面比例、抗锯齿、首帧前的
 * 挡板这些都在 `PlayerView` 里做好了，自己写一层等于重新踩一遍它的坑。**字幕不在
 * 这个列表里**——我们把它那套关掉了，见下面「为什么必须遮住 PlayerView 自己的字幕视图」。
 *
 * ## 为什么必须遮住 `PlayerView` 自己的字幕视图
 *
 * `PlayerView` 不只是个画面容器：它在 `setPlayer()` 里会把 player 的 cue 直接交给
 * 自己内部的 `SubtitleView`，并且**一直**跟着 `onCues` 更新（已用 `javap -c` 核过
 * media3-ui 1.11.1 的字节码：`setPlayer` 里 `subtitleView.setCues(...)` +
 * `player.addListener(componentListener)`，`ComponentListener.onCues` 再
 * `setCues(cueGroup.cues)`）。那是 Media3 的默认行为，不看我们意愿。
 *
 * 于是内嵌字幕会在屏幕上出现**两份**：一份是它画的（Media3 自己的排版/字号/位置），
 * 一份是 [overlay] 里我们自己的字幕层（`SubtitleOverlay`，按用户的字号/描边/
 * 底部距离/双语/翻译设置画）。两份位置和样式都不一样，看上去就是重影。
 * 外挂字幕文件不走 Media3（是我们自己解析的），所以只有内嵌轨会重。
 *
 * 附带一个更隐蔽的后果：显示模式选「隐藏」时，我们那份不画了，Media3 那份照画，
 * 于是**「隐藏」藏不住字幕**。关掉它之后「隐藏」才是真的隐藏。
 *
 * 我们把它设成 `GONE` 而不是从视图树里摘掉：`PlayerView` **从不**读除构造函数和
 * `setPlayer` 之外的地方动这个子视图的可见性（同上核过字节码，整个类里没有一处
 * `subtitleView.setVisibility`），所以设一次就够，不需要每次重组都伸手。
 *
 * ## 为什么外面还要包一层 `BoxWithConstraints`
 *
 * `PlayerView` 内部的 `AspectRatioFrameLayout` **只会调整画面在它自己里面的位置**，
 * 它自身始终占满给它的矩形。于是外层的字幕层会按「整块矩形」定位，看 4:3 或
 * 宽银幕的片子时字幕就落进了黑边里。这里改成：由 [VideoFit] 算出画面**应该占的
 * 那块矩形**，`PlayerView` 就按这个尺寸摆放，黑边交给外层背景——字幕层和画面就精确重合。
 *
 * @param overlay 叠在**画面矩形**上的内容（本项目里是字幕层）。之所以做成一个
 *   插槽而不是让调用方在外层自己叠：外面那层是「整块可用区域」，看宽银幕时
 *   它比画面高得多，字幕会落到下面的黑边里。放在这个插槽里拿到的坐标系就是
 *   画面本身。
 *
 * `clipToBounds()` 是给 [AspectRatioMode.CROP] 用的：那个模式下 `PlayerView` 被撑满，
 * 画面按 `RESIZE_MODE_ZOOM` 放大后**会溢出**自己的边界，不裁的话它会盖到
 * 上下的控制条上去。
 */
@Composable
fun PlayerVideoSurface(
    player: Player?,
    mode: AspectRatioMode,
    videoSize: MspVideoSize,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        // 无界约束下算出来的尺寸没有意义（`Int.MAX_VALUE` 会让布局直接崩）。
        // 真出现这种父容器时退化成铺满——那正是「不知道尺寸时最合理的表现」。
        val bounded = widthPx != Constraints.Infinity && heightPx != Constraints.Infinity

        val target = if (bounded) {
            remember(mode, videoSize, widthPx, heightPx) {
                VideoFit.targetSize(mode, widthPx, heightPx, videoSize)
            }
        } else {
            null
        }

        Box(
            modifier = if (target == null) {
                Modifier.fillMaxSize()
            } else {
                with(density) { Modifier.size(target.width.toDp(), target.height.toDp()) }
            },
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(false)
                        // 内嵌字幕只由我们的字幕层画一份。不关掉这里，Media3 会把同一批
                        // cue 再画一遍，两份叠在一起；而且显示模式选「隐藏」也藏不住。
                        // `subtitleView` 是布局 `exo_player_view` 里的 `exo_subtitles`，
                        // 我们的构造函数参数不可能去掉它（Media3 1.11.1 的 `PlayerView`
                        // 只有 3 个 public 构造函数，带 layout 资源的那个是 private），
                        // 所以只能拿到它之后遮住。
                        subtitleView?.visibility = View.GONE
                    }
                },
                update = { view ->
                    // 必须先判断再赋值：`player` 的 setter 每次都会触发一次
                    // SurfaceView 的重新绑定，无条件赋值等于每次重组都闪一下黑屏。
                    if (view.player !== player) view.player = player
                    // `setResizeMode` 内部对同值有短路，所以这里不必自己比。
                    view.resizeMode = VideoFit.resizeModeOf(mode)
                    view.keepScreenOn = isPlaying
                },
                modifier = Modifier.fillMaxSize(),
            )

            overlay()
        }
    }
}
