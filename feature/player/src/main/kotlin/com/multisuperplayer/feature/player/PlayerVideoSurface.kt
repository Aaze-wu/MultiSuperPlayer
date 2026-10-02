package com.multisuperplayer.feature.player

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
 * 之所以还要用 `PlayerView` 而不是 `SurfaceView`：字幕渲染、画面比例、抗锯齿
 * 这些都在 `PlayerView` 里做好了，自己写一层等于重新踩一遍它的坑。
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
