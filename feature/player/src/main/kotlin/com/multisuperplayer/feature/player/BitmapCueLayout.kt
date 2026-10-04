package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.IntRect
import com.multisuperplayer.core.player.BitmapCueSpec
import com.multisuperplayer.core.player.CueAnchor
import kotlin.math.roundToInt

/**
 * 一张位图字幕该画在画面里的哪个矩形上。
 *
 * ## 这是照抄，不是发明
 *
 * 算法逐行对应 Media3 的 `SubtitlePainter.setupBitmapLayout()`：
 *
 * ```java
 * float anchorX = parentLeft + (parentWidth * cuePosition);
 * float anchorY = parentTop + (parentHeight * cueLine);
 * int width = Math.round(parentWidth * cueSize);
 * int height = cueBitmapHeight != Cue.DIMEN_UNSET
 *     ? Math.round(parentHeight * cueBitmapHeight)
 *     : Math.round(width * ((float) bitmap.getHeight() / bitmap.getWidth()));
 * int x = Math.round(cuePositionAnchor == END ? anchorX - width
 *     : cuePositionAnchor == MIDDLE ? (anchorX - (width / 2)) : anchorX);
 * ```
 *
 * 之所以要抄而不是「照比例摆一下」：位图字幕的坐标是**片源自己排好的版**，
 * 同一份 `.sup` 在别的播放器里长什么样由这套公式决定。差半个像素无所谓，
 * 差一个「锚点算在中心还是边缘」就是整块字幕横移半个屏——而那看起来像片源有问题。
 *
 * ## 三个必须原样保留的细节
 *
 * 1. **`anchorX` / `anchorY` 不取整**，只有最终结果取整。先取整再减会多一次舍入，
 *    在 4K 上够偏出几个像素。
 * 2. **`width / 2` 是整除**（Java 里两边都是 `int`）。`width = 101` 时减掉的是
 *    `50` 而不是 `50.5`。写成 `width / 2f` 会让「三分之一以上的居中字幕」
 *    在奇数宽度上差 1px——逐帧看是抖的。
 * 3. **`bitmapHeight` 没写时按图片自己的宽高比推**，不是「按图片原始像素」：
 *    推出来的是**显示**高度（占画面高的比例），所以 PGS 那种 2× 分辨率的图
 *    不会被画成两倍大。
 *
 * 矩形**不做**边界裁剪：Media3 也不裁（画面外的部分由画布裁）。越界意味着片源写的
 * 坐标越界，把它悄悄拉回画面里只会掩盖片源的问题，而且拉哪一边都是猜的。
 *
 * 四个比例值全部经 [roundOrZero]：**Java 的 `Math.round(NaN)` 返回 `0`，而 Kotlin 的
 * `Float.roundToInt()` 在 `NaN` 上抛异常**。也就是说这不是「多此一举的健壮性」，
 * 而是照抄 Java 语义——容器里写一个 `NaN`（`dimensionOrNull` 只认 `DIMEN_UNSET`，
 * 拦不住 `NaN`）在这里会变成崩溃而不是零矩形。
 *
 * @param imageWidth 图片自身的像素宽（用来推高度）。`<= 0` 时返回零矩形。
 * @param boxWidth 画面矩形的像素宽 = 位图坐标系的「1」。
 */
internal fun bitmapCueRect(
    spec: BitmapCueSpec,
    imageWidth: Int,
    imageHeight: Int,
    boxWidth: Int,
    boxHeight: Int,
): IntRect {
    // 宽或高为 0 的图片：除下去是 `NaN`，而 `roundToInt()` 在 `NaN` 上会**抛异常**
    // （`IllegalArgumentException: Cannot round NaN value`）。这不是理论风险：
    // 解码失败的位图正是 0×0，而字幕在 `onCues` 里是逐帧来的——一帧崩一次。
    if (imageWidth <= 0 || imageHeight <= 0) return IntRect.Zero

    val anchorX = boxWidth * spec.position
    val anchorY = boxHeight * spec.line

    // `size == null`（容器没写宽度）时按 0 算，与 Media3 一致：它那边拿到的是
    // `DIMEN_UNSET`，乘出来的也是一个约等于 0 的宽度，结果同样是零矩形、同样被跳过。
    val width = (boxWidth * (spec.size ?: 0f)).roundOrZero()
    val height = spec.bitmapHeight
        ?.let { (boxHeight * it).roundOrZero() }
        ?: (width * (imageHeight.toFloat() / imageWidth)).roundOrZero()

    val x = when (spec.positionAnchor) {
        CueAnchor.END -> anchorX - width
        CueAnchor.MIDDLE -> anchorX - (width / 2)
        CueAnchor.START -> anchorX
    }.roundOrZero()
    val y = when (spec.lineAnchor) {
        CueAnchor.END -> anchorY - height
        CueAnchor.MIDDLE -> anchorY - (height / 2)
        CueAnchor.START -> anchorY
    }.roundOrZero()

    return IntRect(left = x, top = y, right = x + width, bottom = y + height)
}

/**
 * `Math.round` 的 Kotlin 版，外加一个 `NaN` 兜底。
 *
 * `Float.roundToInt()` 在 `NaN` 上抛异常，而 Java 的 `Math.round` 返回 `0`。也就是说
 * 不兜底会让这条路上**多出一种崩溃**：Java 那边算出一个零矩形、什么都不画，
 * Kotlin 这边直接抛——而抛出点在最内层的绘制代码里，字幕层不该有这种死法。
 * 摆不出来就不摆（零矩形），画面照常。
 */
private fun Float.roundOrZero(): Int = if (isNaN()) 0 else roundToInt()
