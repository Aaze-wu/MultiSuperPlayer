package com.multisuperplayer.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.multisuperplayer.core.player.EmbeddedBitmapCue

/**
 * 位图字幕层（PGS / VobSub / DVB）。
 *
 * ## 它必须铺满**画面矩形**，不能铺满屏幕
 *
 * 位图的坐标是画面比例（0 = 左/上边缘，1 = 右/下边缘），所以这一层的坐标系
 * 必须**正好**是画面那一块。看 4:3 或宽银幕的片子时，`PlayerVideoSurface`
 * 的内层 Box 就是画面矩形、外面还有黑边，两层坐标系差着一条黑边的高度——
 * 铺错一层的话字幕会整体下移（而且片子比例越极端偏得越多）。
 *
 * 所以这个 Composable 只应该被放进 `PlayerVideoSurface` 的 `overlay` 插槽里
 * （那正是画面矩形），不要放到 `SubtitleOverlay` 那种带边距的层里。
 *
 * ## 为什么用 `Canvas` 而不是 N 个 `Image`
 *
 * 一张 PGS 字幕图的显示尺寸要算出来才能用 `Modifier.size`，而算出来的是**像素**——
 * 过 `dp` 再转回去会引入一次密度舍入，位置和尺寸各错一点点。`DrawScope` 里
 * `dstOffset` / `dstSize` 直接就是像素，和布局公式同一套单位，中间没有换算。
 *
 * 顺带一个好处：`Canvas` 不参与命中测试，也不会吃掉手势——字幕层压在
 * 画面手势层上面，能吃掉触摸的话整片就拖不动了。
 *
 * ## 和文本字幕的关系
 *
 * 两者**互斥**：`Cue` 里 `text` 和 `bitmap` 不可能同时有值，内核给的也不会
 * 混着来。所以这一层和 `SubtitleOverlay` 同时存在不冲突，也不需要谁先谁后的判断。
 */
@Composable
internal fun BitmapSubtitleLayer(
    cues: List<EmbeddedBitmapCue>,
    modifier: Modifier = Modifier,
) {
    // 没有位图时**一个节点都不发**：这一层是叠在画面上的全屏节点，
    // 发一个空节点出去等于每次重组都多一层无用的布局参与（而且它还压在
    // 手势层上面）。返回值不是 Composable 也无所谓——调用方只当它是个可选装饰。
    if (cues.isEmpty()) return

    // 位图 → `ImageBitmap` 的包装按 cue 列表缓存：`asImageBitmap()` 每次调用都
    // 新建一个包装对象，而画一幅静态图可能连续几十帧走同一批 cue。列表相等
    // （同一批 Bitmap、同一批 spec）就复用，字幕换一张时才重建。
    val images = remember(cues) { cues.map { it.image.asImageBitmap() } }

    Canvas(modifier = modifier) {
        cues.forEachIndexed { index, cue ->
            val rect = bitmapCueRect(
                spec = cue.spec,
                imageWidth = cue.image.width,
                imageHeight = cue.image.height,
                boxWidth = size.width.toInt(),
                boxHeight = size.height.toInt(),
            )
            // 摆不出来的图直接跳过：宽或高为 0 的矩形在 `drawImage` 里要么什么都不画、
            // 要么是一堆无意义的调用。跳过而不是兜底摆到某个位置——「摆不出来」是
            // 片源写了个 0，硬摆只会让用户以为这个位置是片源指定的。
            if (rect.width <= 0 || rect.height <= 0) return@forEachIndexed

            drawImage(
                image = images[index],
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(cue.image.width, cue.image.height),
                dstOffset = IntOffset(rect.left, rect.top),
                dstSize = IntSize(rect.width, rect.height),
                // 和 Media3 的 `bitmapPaint.setFilterBitmap(true)` 对齐：
                // PGS 的图通常是 1920×1080 而画面只有几百像素宽，双线性缩放
                // 是「能不能看」的差别（最近邻会让字幕边缘全是锯齿）。
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
