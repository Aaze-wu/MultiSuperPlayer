package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.IntSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.MspVideoSize
import kotlin.math.roundToInt

/**
 * 画面比例的计算。
 *
 * ## 为什么画面尺寸要自己算，而不是交给 `PlayerView`
 *
 * `PlayerView` 内部有一个 `AspectRatioFrameLayout`，它确实能按比例摆放画面。
 * 但它**只会改变「画面的黑边」**——它自己始终占满给它的那块矩形。于是字幕层
 * 叠在上面时，位置是相对**整块矩形**算的，而不是相对画面算的：看一部 4:3 的片子，
 * 字幕会掉进下面的黑边里；看宽银幕，字幕会压在画面上。这个偏差还会随比例模式变化。
 *
 * 所以这里的做法是：把画面应该占的那块矩形**算出来**，`PlayerView` 就直接按
 * 这个尺寸摆放。黑边由外面那层背景提供，字幕层和画面就精确重合了。
 *
 * ## 为什么“原始”和“适应”不是一回事
 *
 * 「适应」是**按比例缩放到能放进容器**；「原始」是**原样 1 像素对 1 像素**：
 * 低分辨率的片子在这块高分屏上会显示成一小块，而不是被放大到铺满。
 * 后者在另一些播放器里叫「100%」或者「原始大小」，是专门用来「看清楚这个文件
 * 到底有多少有效像素」的。两者在「片源分辨率恰好等于屏幕」时结果相同，
 * 但那只是巧合，不是它们是一个东西。
 */
object VideoFit {

    /**
     * 对应到 `PlayerView` 自己的缩放模式。
     *
     * 传入的矩形已经是「画面应该占多大」，所以：
     * - [AspectRatioMode.FIT] / [AspectRatioMode.ORIGINAL]：矩形本身就是按比例算的，
     *   用 `RESIZE_MODE_FIT` 正好严丝合缝（多一层缩放算子是恒等变换）；
     * - [AspectRatioMode.CROP]：矩形是整块区域，用 `RESIZE_MODE_ZOOM` 让画面铺满并裁掉溢出；
     * - [AspectRatioMode.STRETCH]：矩形是整块区域，用 `RESIZE_MODE_FILL` 强行拉伸。
     */
    fun resizeModeOf(mode: AspectRatioMode): Int = when (mode) {
        AspectRatioMode.FIT, AspectRatioMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        AspectRatioMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    /**
     * 画面应该占多大（像素）。
     *
     * @param containerWidthPx / [containerHeightPx] 可用区域。任何一边为 0（还没测量出来）
     *   就返回 [IntSize.Zero]，调用方据此退化成「铺满」——这时候算出来的任何尺寸都是
     *   无意义的，而返回一个 0 尺寸会让画面**整整消失一帧**，比铺满难看得多。
     * @param video 片源尺寸。无效（[MspVideoSize.isValid] 为假）时：
     *   「适应」和「原始」都退化成铺满——不知道比例意味着**任何**内接计算都可能和实际
     *   画面不一致，铺满至少让 `PlayerView` 自己按它拿到的信息去显示。
     */
    fun targetSize(
        mode: AspectRatioMode,
        containerWidthPx: Int,
        containerHeightPx: Int,
        video: MspVideoSize,
    ): IntSize {
        val width = containerWidthPx.coerceAtLeast(0)
        val height = containerHeightPx.coerceAtLeast(0)
        if (width == 0 || height == 0) return IntSize.Zero

        return when (mode) {
            AspectRatioMode.STRETCH, AspectRatioMode.CROP -> IntSize(width, height)

            AspectRatioMode.ORIGINAL -> {
                if (!video.isValid) {
                    IntSize(width, height)
                } else {
                    IntSize(
                        // 非方形像素也要算进去：DVD 的 720×576 是「anamorphic」，
                        // 不做这一步的话它会被显示成 1.25:1 而不是 16:9。
                        width = (video.widthPx * video.pixelWidthHeightRatio)
                            .roundToInt()
                            .coerceAtLeast(1),
                        height = video.heightPx.coerceAtLeast(1),
                    )
                }
            }

            AspectRatioMode.FIT -> {
                if (!video.isValid) IntSize(width, height) else fitInside(video.aspectRatio, width, height)
            }
        }
    }

    /**
     * 片源分辨率的文字形式，例如 `1920×1080`；拿不到时返回 null。
     *
     * 用在「原始」那一项旁边：选了它之后画面可能只占屏幕一小块，把源分辨率写出来，
     * 用户才知道**这是预期的**，而不是「播放器坏了」。
     */
    fun sourceLabel(video: MspVideoSize): String? = if (!video.isValid) {
        null
    } else {
        "${video.widthPx}×${video.heightPx}"
    }

    /** 按 [aspectRatio] 内接放大到 [width] × [height] 里最大的那个矩形。 */
    private fun fitInside(aspectRatio: Float, width: Int, height: Int): IntSize {
        val containerRatio = width.toFloat() / height
        return if (aspectRatio > containerRatio) {
            // 画面比容器「更宽」：宽度顶满，高度按比例缩。
            IntSize(width, (width / aspectRatio).roundToInt().coerceAtLeast(1))
        } else {
            IntSize((height * aspectRatio).roundToInt().coerceAtLeast(1), height)
        }
    }
}
