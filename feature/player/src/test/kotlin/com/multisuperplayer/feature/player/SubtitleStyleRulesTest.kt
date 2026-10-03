package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.SubtitleBottomMargin
import com.multisuperplayer.core.data.settings.SubtitleLineSpacing
import com.multisuperplayer.core.data.settings.SubtitleOutline
import com.multisuperplayer.core.data.settings.SubtitleStyle
import com.multisuperplayer.core.data.settings.SubtitleTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕样式换算的单元测试。
 *
 * 这里**只能**测数字：字幕真正画出来是什么样是 Compose 的事，JVM 单测跑不了。
 * 所以「档位 → 数字」这一步被完整地抽进 [subtitleGeometry]，这个文件就是它的规定。
 *
 * 两条最要紧的规则：
 * - 默认样式必须和 v0.5.15 里写死的样式**一字不差**（16sp / 24sp / 无描边 / 12dp），
 *   否则升级之后所有人的字幕会自己变样——一次没人要求的外观变化。
 * - 任何输入（含 `NaN`）都不许把 `NaN` 传到渲染层。
 */
class SubtitleStyleRulesTest {

    // ------------------------------------------------------------ 基线

    @Test
    fun `默认样式换算出来的就是 v0_5_15 写死的数字`() {
        // titleMedium 是 16sp/24sp、bodyLarge 是 16sp/24sp（M3 的两套字级行高比都是 1.5）。
        val geometry = subtitleGeometry(SubtitleStyle.DEFAULT, 16f, 24f)

        assertEquals(16f, geometry.fontSizeSp, DELTA)
        assertEquals(24f, geometry.lineHeightSp, DELTA)
        // 默认**没有**描边：v0.5.15 只有一层柔和阴影。默认开描边等于把所有老用户的
        // 字幕一起变样，那不是这个功能该做的事。
        assertEquals(0f, geometry.strokeWidthDp, DELTA)
        assertEquals(12, geometry.bottomPaddingDp)
    }

    @Test
    fun `基准行高比被保留下来`() {
        // 行高比 1.5（24/16）在换算后仍然是 1.5，所以行距档位改的是行间空隙，
        // 不是单行文字的高度。
        val geometry = subtitleGeometry(SubtitleStyle.DEFAULT, 16f, 24f)

        assertEquals(1.5f, geometry.lineHeightSp / geometry.fontSizeSp, DELTA)
    }

    @Test
    fun `别的基准字号同样按倍数取`() {
        // 用户把系统字体调大 ⇒ 主题给的字号变大 ⇒ 字幕跟着变大（而不是永远 16sp）。
        val geometry = subtitleGeometry(SubtitleStyle.DEFAULT, 20f, 30f)

        assertEquals(20f, geometry.fontSizeSp, DELTA)
        assertEquals(30f, geometry.lineHeightSp, DELTA)
    }

    // ------------------------------------------------------------ 字号

    @Test
    fun `字号倍率同时放大字号和行高`() {
        val geometry = subtitleGeometry(
            SubtitleStyle.DEFAULT.copy(textSize = SubtitleTextSize.HUGE),
            16f,
            24f,
        )

        assertEquals(24f, geometry.fontSizeSp, DELTA)
        // 行高必须跟着放大：只放大字号会让上下两行的字叠在一起，
        // 那看起来像渲染坏了，而不是「字变大了」。
        assertEquals(36f, geometry.lineHeightSp, DELTA)
    }

    @Test
    fun `四个字号档位都是单调递增的`() {
        val sizes = SubtitleTextSize.entries.map {
            subtitleGeometry(SubtitleStyle.DEFAULT.copy(textSize = it), 16f, 24f).fontSizeSp
        }

        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes.size, sizes.toSet().size)
    }

    @Test
    fun `字号小不会小于基准的一半`() {
        // 「看不清」是这个功能存在的理由，任何档位都不该比基准小太多，
        // 否则用户点一圈回来发现最清楚的还是标准档。
        val smallest = SubtitleTextSize.entries.minOf { it.scale }

        assertTrue("最小档位倍率 = $smallest", smallest >= 0.75f)
    }

    // ------------------------------------------------------------ 行距

    @Test
    fun `行距只改行高不改字号`() {
        val normal = subtitleGeometry(SubtitleStyle.DEFAULT, 16f, 24f)
        val loose = subtitleGeometry(
            SubtitleStyle.DEFAULT.copy(lineSpacing = SubtitleLineSpacing.LOOSE),
            16f,
            24f,
        )

        assertEquals(normal.fontSizeSp, loose.fontSizeSp, DELTA)
        assertTrue(loose.lineHeightSp > normal.lineHeightSp)

        val tight = subtitleGeometry(
            SubtitleStyle.DEFAULT.copy(lineSpacing = SubtitleLineSpacing.TIGHT),
            16f,
            24f,
        )
        assertTrue(tight.lineHeightSp < normal.lineHeightSp)
    }

    // ------------------------------------------------------------ 描边

    @Test
    fun `描边给 Stroke 的是可见宽度的两倍`() {
        val geometry = subtitleGeometry(
            SubtitleStyle.DEFAULT.copy(outline = SubtitleOutline.THICK),
            16f,
            24f,
        )

        // Stroke 以字形轮廓为中线，向内的一半会被上层填色盖住 ⇒ 给 2N 才得到 N 的可见外扩。
        assertEquals(SubtitleOutline.THICK.widthDp * 2f, geometry.strokeWidthDp, DELTA)
        assertTrue("描边粗档的可见宽度要真的看得见", SubtitleOutline.THICK.widthDp >= 2f)
    }

    @Test
    fun `描边四档宽度严格递增且无描边是零`() {
        val none = subtitleGeometry(
            SubtitleStyle.DEFAULT.copy(outline = SubtitleOutline.NONE),
            16f,
            24f,
        )
        assertEquals(0f, none.strokeWidthDp, DELTA)

        val widths = SubtitleOutline.entries.map { it.widthDp }
        assertEquals(widths.sorted(), widths)
        assertEquals(widths.size, widths.toSet().size)
    }

    // ------------------------------------------------------------ 底部距离

    @Test
    fun `底部距离四档就是各自的 dp`() {
        SubtitleBottomMargin.entries.forEach { margin ->
            val geometry = subtitleGeometry(
                SubtitleStyle.DEFAULT.copy(bottomMargin = margin),
                16f,
                24f,
            )

            assertEquals(margin.dp, geometry.bottomPaddingDp)
        }

        val values = SubtitleBottomMargin.entries.map { it.dp }
        assertEquals(values.sorted(), values)
        assertEquals(values.size, values.toSet().size)
    }

    // ------------------------------------------------------------ 坏输入

    @Test
    fun `基准行高是 NaN 时不产生 NaN`() {
        // `TextUnit.Unspecified.value` 就是 NaN——主题里没显式给行高时，
        // MaterialTheme.typography 的 lineHeight 可能落到这个值上。
        // 一旦漏出去，Compose 会照着 NaN 排版：字幕整块消失，而且不报错。
        val geometry = subtitleGeometry(SubtitleStyle.DEFAULT, 16f, Float.NaN)

        assertFinite(geometry)
        assertEquals(16f, geometry.fontSizeSp, DELTA)
        // 回退到「基准字号 × 1.5」，也就是 titleMedium 本来的行高比。
        assertEquals(24f, geometry.lineHeightSp, DELTA)
    }

    @Test
    fun `基准字号是 NaN 或零时回退到兜底字号`() {
        listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY).forEach { bad ->
            val geometry = subtitleGeometry(SubtitleStyle.DEFAULT, bad, 24f)

            assertFinite(geometry)
            assertEquals("基准字号 = $bad", 16f, geometry.fontSizeSp, DELTA)
        }
    }

    @Test
    fun `大写档位也不会算出 NaN 或零`() {
        val worst = subtitleGeometry(
            SubtitleStyle(
                textSize = SubtitleTextSize.HUGE,
                lineSpacing = SubtitleLineSpacing.LOOSE,
                outline = SubtitleOutline.THICK,
                bottomMargin = SubtitleBottomMargin.HIGH,
            ),
            baseFontSizeSp = Float.NaN,
            baseLineHeightSp = Float.NaN,
        )

        assertFinite(worst)
        assertEquals(24f, worst.fontSizeSp, DELTA)
        assertEquals(24f * 1.5f * 1.3f, worst.lineHeightSp, DELTA)
        assertEquals(SubtitleOutline.THICK.widthDp * 2f, worst.strokeWidthDp, DELTA)
        assertEquals(SubtitleBottomMargin.HIGH.dp, worst.bottomPaddingDp)
    }

    private fun assertFinite(geometry: SubtitleGeometry) {
        assertTrue("fontSize 是 ${geometry.fontSizeSp}", geometry.fontSizeSp.isFinite())
        assertTrue("lineHeight 是 ${geometry.lineHeightSp}", geometry.lineHeightSp.isFinite())
        assertTrue("strokeWidth 是 ${geometry.strokeWidthDp}", geometry.strokeWidthDp.isFinite())
        assertTrue("fontSize 必须大于零", geometry.fontSizeSp > 0f)
        assertTrue("lineHeight 必须大于零", geometry.lineHeightSp > 0f)
    }

    private companion object {
        const val DELTA = 0.001f
    }
}
