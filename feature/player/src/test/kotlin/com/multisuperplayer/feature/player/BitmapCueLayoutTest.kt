package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.IntRect
import com.multisuperplayer.core.player.BitmapCueSpec
import com.multisuperplayer.core.player.CueAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位图字幕的摆放算术。
 *
 * ## 为什么值得单测
 *
 * 这套公式是从 Media3 的 `SubtitlePainter.setupBitmapLayout()` 逐行抄来的
 * （见 [bitmapCueRect] 的 KDoc）。抄的东西最怕「抄的时候顺手优化了一下」：
 * 比如把 `width / 2` 写成 `width / 2f`、或者先把 `anchorX` 取整再相减、
 * 或者在 `bitmapHeight` 没写时按图片原始像素算高度。三种改法**都不会报错、
 * 都不会崩**，只会让字幕偏一点点——在模拟器上看一眼完全正常，
 * 只有把同一份 `.sup` 和别的播放器并排对比才看得出来。
 *
 * 所以这里钉的全是「差 1px」和「差一倍」这两类：
 *
 * - 奇数宽度下中点锚点减的是**整除**结果（101 → 减 50，不是 50.5）；
 * - `bitmapHeight` 没写时推的是**显示**高度（2× 分辨率的 PGS 图不会被画成两倍大）；
 * - 越界坐标**不裁剪**（裁剪会把片源的错变成播放器猜的错）。
 *
 * ## 刻意在测算术，不是在测「好看」
 *
 * 有几个用例的盒子尺寸（1001×1001）比真机奇怪，因为只有那几个数能让
 * 「整除」和「浮点除」真的分出两条结果来（见「中点锚点的宽度减半是整除」）。
 * 换一个漂亮的尺寸，两种实现给出的答案会恰好相同，测试就变成了永远绿。
 */
class BitmapCueLayoutTest {

    // ------------------------------------------------------------ 基本摆位

    @Test
    fun `起点锚点从比例位置往右下长`() {
        // PGS 最常见的写法：宽 `size` 是画面宽的几分之一，高 `bitmapHeight` 是画面高的几分之一。
        val rect = bitmapCueRect(
            spec = spec(position = 0.1f, line = 0.8f, size = 0.25f, bitmapHeight = 0.1f),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        // 1920×0.1 = 192，1080×0.8 = 864；宽 480、高 108。
        assertEquals(IntRect(192, 864, 672, 972), rect)
    }

    @Test
    fun `末端锚点从比例位置往左上长`() {
        // 「锚点」说的是**图的哪一条边**钉在那个比例位置上，不是图往哪边长。
        // 写反了字幕会整体平移一整块自己的宽度——右上角的台标会跑到屏幕中间。
        val rect = bitmapCueRect(
            spec = spec(
                position = 0.9f,
                positionAnchor = CueAnchor.END,
                line = 0.5f,
                lineAnchor = CueAnchor.END,
                size = 0.25f,
                bitmapHeight = 0.1f,
            ),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        // 1728−480 = 1248，540−108 = 432。
        assertEquals(IntRect(1248, 432, 1728, 540), rect)
    }

    @Test
    fun `中点锚点的宽度减半是整除`() {
        // 101 是奇数：整除减掉 50，浮点除会减掉 50.5。
        // 锚点 500.5 上两种算法的差正好落在舍入边界上，于是给出 451 和 450。
        // Media3 是 Java，`int / int` 就是整除——这里必须和它一样。
        val rect = bitmapCueRect(
            spec = spec(
                position = 0.5f,
                positionAnchor = CueAnchor.MIDDLE,
                line = 0.5f,
                lineAnchor = CueAnchor.MIDDLE,
                size = 0.101f,       // 1001 × 0.101 ≈ 101
                bitmapHeight = 0.5f, // 1001 × 0.5 = 500.5 → 四舍五入 501
            ),
            imageWidth = 100,
            imageHeight = 50,
            boxWidth = 1001,
            boxHeight = 1001,
        )

        assertEquals("LEFT 应当是 500.5 − 50 = 450.5 → 451", 451, rect.left)
        assertEquals("RIGHT 是 LEFT + 宽 = 451 + 101", 552, rect.right)
        assertEquals("TOP 应当是 500.5 − 250 = 250.5 → 251", 251, rect.top)
        assertEquals("BOTTOM 是 TOP + 高 = 251 + 501", 752, rect.bottom)
    }

    // ------------------------------------------------------------ 高度的两个来源

    @Test
    fun `容器没写高度时按图片宽高比推`() {
        // 推出来的是占画面高的**比例**，不是图片的像素高。
        // 1920×1080 的图按画面宽的一半摆开，高度就是画面高的 540/1080 = 一半。
        val rect = bitmapCueRect(
            spec = spec(position = 0.1f, line = 0.1f, size = 0.5f, bitmapHeight = null),
            imageWidth = 1920,
            imageHeight = 1080,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals(IntRect(192, 108, 1152, 648), rect)
    }

    @Test
    fun `容器写了高度就照它走 不被两倍图片带偏`() {
        // 这条是「2× 超采样」那个坑：PGS 为了清晰度把图存成 3840×2160，
        // 而 `bitmapHeight` 写的是 0.1（画面高的十分之一）。按比例推会推出 540，
        // 也就是把字幕画成两倍大——看起来像「这份字幕在别的播放器里正常、
        // 在这个播放器里巨大」，而公式本身没错，是**取了错误的那个来源**。
        val rect = bitmapCueRect(
            spec = spec(size = 0.5f, bitmapHeight = 0.1f),
            imageWidth = 3840,
            imageHeight = 2160,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals(IntRect(0, 0, 960, 108), rect)
    }

    // ------------------------------------------------------------ 退化输入

    @Test
    fun `容器没写宽度时退化成零矩形`() {
        // `size == null` 表示容器里没这一项，退化成 0 —— 和「写了 0」在画面上是一回事。
        // 宽度一变成 0，**按图片宽高比推**的那条路也跟着变成 0（0 乘任何数都是 0），
        // 所以这里连 `bitmapHeight` 也不写，走的就是推导那条路。
        val rect = bitmapCueRect(
            spec = spec(size = null),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals(IntRect.Zero, rect)
        assertEquals("零矩形要被渲染层跳过，不是画一条一像素的线", 0, rect.width)
    }

    @Test
    fun `宽度是零也不会把写死的高度拉回零`() {
        // 这条盯的是一个很容易「顺手优化」的地方：`bitmapHeight` 写死时高度**不经过宽度**
        // （Media3 也是这么写的：写了就用 `parentHeight * bitmapHeight`，没写才按图片宽高比推）。
        // 把两条路合成 `height = width * 图片宽高比`、或者加一句「宽度为 0 就整个清零」，
        // 「容器写了高度、没写宽度」的 cue 就会被无声吃掉。
        // 出来的只是个 0×216 的矩形、渲染层照样跳过，所以画面上看不出区别——
        // 但数据不再忠实于片源，而「抄得忠实」正是这套公式唯一的价值。
        val rect = bitmapCueRect(
            spec = spec(size = null, bitmapHeight = 0.2f),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals(IntRect(0, 0, 0, 216), rect)
        assertEquals("宽度为 0，高度照容器写的走", 216, rect.height)
    }

    @Test
    fun `图片尺寸拿不到时给零矩形`() {
        // 解码失败的位图正是 0×0。这里返回零矩形的意义不只是「不画」：
        // Media3 那边除下去是 `NaN`、`Math.round(NaN)` 得 0；Kotlin 的
        // `Float.roundToInt()` 在 `NaN` 上会**抛异常**，而这是逐帧路径。
        val spec = spec(size = 0.5f, bitmapHeight = 0.1f)

        assertEquals(
            IntRect.Zero,
            bitmapCueRect(spec, imageWidth = 0, imageHeight = 0, boxWidth = 1920, boxHeight = 1080),
        )
        assertEquals(
            IntRect.Zero,
            bitmapCueRect(spec, imageWidth = 0, imageHeight = 100, boxWidth = 1920, boxHeight = 1080),
        )
        assertEquals(
            IntRect.Zero,
            bitmapCueRect(spec, imageWidth = 200, imageHeight = -1, boxWidth = 1920, boxHeight = 1080),
        )
    }

    @Test
    fun `比例是 NaN 时不抛异常`() {
        // `Cue` 里的比例本来是容器值，但 `dimensionOrNull` 只认 `DIMEN_UNSET`，
        // 拦不住容器写进来的 `NaN`。这条路的异常会在绘制那一帧崩掉整个播放器，
        // 而正确的结果是「这一块摆不出来 → 零宽 → 渲染层跳过」。
        val nan = bitmapCueRect(
            spec = spec(position = Float.NaN, line = Float.NaN, size = 0.5f, bitmapHeight = 0.1f),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals("NaN 的锚点折成 0", 0, nan.left)
        assertEquals(0, nan.top)
        assertEquals("宽度本身没坏，坏的是位置", 960, nan.width)

        val nanSize = bitmapCueRect(
            spec = spec(size = Float.NaN, bitmapHeight = Float.NaN),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 1920,
            boxHeight = 1080,
        )

        assertEquals("NaN 的宽度折成 0", IntRect.Zero, nanSize)
    }

    @Test
    fun `越界坐标不做裁剪`() {
        // 裁剪会把「片源写了越界坐标」变成一个看起来正常的画面，掩盖问题；
        // 而且拉哪一边都是猜的。Media3 也不裁（画面外的部分由画布裁掉）。
        val rect = bitmapCueRect(
            spec = spec(position = 1.5f, line = 1.5f, size = 0.5f, bitmapHeight = 0.5f),
            imageWidth = 200,
            imageHeight = 100,
            boxWidth = 100,
            boxHeight = 100,
        )

        assertEquals(IntRect(150, 150, 200, 200), rect)
        assertTrue("整块都在画面外，但仍然原样报出来", rect.left > 100)
    }

    /** 除了要考的那几个，其余锚点默认都是「起点」——和 `BitmapCueSpec` 的默认值一致。 */
    private fun spec(
        position: Float = 0f,
        positionAnchor: CueAnchor = CueAnchor.START,
        line: Float = 0f,
        lineAnchor: CueAnchor = CueAnchor.START,
        size: Float? = null,
        bitmapHeight: Float? = null,
    ) = BitmapCueSpec(
        position = position,
        positionAnchor = positionAnchor,
        line = line,
        lineAnchor = lineAnchor,
        size = size,
        bitmapHeight = bitmapHeight,
    )
}
