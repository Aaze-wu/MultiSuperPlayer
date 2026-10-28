package com.multisuperplayer.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词自动滚动「滚到哪」的规则测试。
 *
 * 这些用例钉的是**行为**，不是具体像素：真实界面里视口高度、内边距、某一行的
 * 实测高度全都随屏幕和内容变，钉死数字只会变成「改了内边距就要改一遍测试」。
 * 因此断言集中在三件事上：
 *
 * 1. 放得进内容区 ⇒ 滚完以后这一行**整行**在内容区里（这是用户报的那个 bug 的判据）；
 * 2. 放不进 ⇒ 顶边贴着内容区上沿（不许为了「居中」把开头推出屏幕外）；
 * 3. 任何退化输入（视口 0、内边距比视口还大、行高还没量出来）都返回 0，
 *    也就是「这一帧什么都不做」——这类输入真的会在第一帧出现。
 *
 * 另外钉死**符号约定**：返回值是 `animateScrollBy` 的入参（正 = 往下滚），所以
 * 「滚完以后这一行的位置」= `itemOffset - moved`。写反了界面上不会报错，只会让
 * 前面几句被一路顶出视口（v1.4.7 发布前实测到的那个「开头几句没了」）。
 */
class LyricsScrollRulesTest {

    /** 常见竖屏歌词区：视口 0..500，上下各 24dp 内边距 ⇒ 内容区 24..476。 */
    private fun delta(
        itemOffset: Int,
        itemSize: Int,
        viewportStart: Int = 0,
        viewportEnd: Int = 500,
        beforeContentPadding: Int = 24,
        afterContentPadding: Int = 24,
    ): Int = LyricsScrollRules.anchorDelta(
        viewportStart = viewportStart,
        viewportEnd = viewportEnd,
        beforeContentPadding = beforeContentPadding,
        afterContentPadding = afterContentPadding,
        itemOffset = itemOffset,
        itemSize = itemSize,
    )

    @Test
    fun `长句的尾巴被视口切掉时滚完以后整行可见`() {
        // 一句三行的长歌词：高 180，实测位置在 300..480，而内容区只到 476
        // ⇒ 后半行压在视口外面。这正是「还有一段藏在下面」。
        val itemOffset = 300
        val itemSize = 180
        val moved = delta(itemOffset = itemOffset, itemSize = itemSize)

        // 内容区 24..476（高 452），居中位置 = 24 + (452 - 180) / 2 = 160。
        // 行要从 300 走到 160 ⇒ 往下滚 140（正数）。
        assertEquals(140, moved)
        val top = itemOffset - moved
        assertEquals(160, top)
        assertTrue("整行必须在内容区里：$top..${top + itemSize}", top >= 24)
        assertTrue(top + itemSize <= 476)
    }

    @Test
    fun `已经在中间的行不动`() {
        // 内容区 24..476 里 180dp 高的行，居中位置就是 160——已经在那儿了。
        assertEquals(0, delta(itemOffset = 160, itemSize = 180))
    }

    @Test
    fun `上面的行很长把当前行顶出视口时把它拉回内容区`() {
        // 前两句也折成好几行，当前行被顶到 520..640：整行都在视口下方，
        // 屏幕上只剩上文的尾巴。按索引锚定的老规则在这一档最惨。
        val itemOffset = 520
        val itemSize = 120
        val moved = delta(itemOffset = itemOffset, itemSize = itemSize)

        // 居中位置 = 24 + (452 - 120) / 2 = 190；行要从 520 走到 190 ⇒ 往下滚 330。
        assertEquals(330, moved)
        val top = itemOffset - moved
        assertEquals(190, top)
        assertTrue(top >= 24 && top + itemSize <= 476)
    }

    @Test
    fun `正数代表往下滚负数代表往回滚`() {
        // 符号约定就是 `animateScrollBy` 的方向：行在内容区下方 ⇒ 正数（往下滚，
        // 行跟着上移）；行在内容区上方 ⇒ 负数。这里写成两个方向的断言，
        // 因为写反了界面上只看得出「前几句不见了」，根本猜不到是符号问题。
        assertTrue("行在下方：${delta(itemOffset = 520, itemSize = 120)}", delta(itemOffset = 520, itemSize = 120) > 0)
        assertTrue("行在上方：${delta(itemOffset = -400, itemSize = 120)}", delta(itemOffset = -400, itemSize = 120) < 0)
    }

    @Test
    fun `比内容区还高的行顶边对齐而不是居中`() {
        // 一行高到整块放不下（500 > 内容区 452）。居中会把开头也切掉，
        // 用户从那句的中间开始读——比「看不到结尾」更糟。
        val moved = delta(itemOffset = 100, itemSize = 500)
        assertEquals(24, 100 - moved)
    }

    @Test
    fun `正好和内容区一样高的行按顶边对齐`() {
        assertEquals(24, 100 - delta(itemOffset = 100, itemSize = 452))
    }

    @Test
    fun `内边距算进内容区`() {
        // `compact` 档：上下内边距 8。内容区 8..392（高 384），
        // 100dp 高的行居中位置 = 8 + (384 - 100) / 2 = 150 ⇒ 往回滚 150。
        val moved = delta(
            itemOffset = 0,
            itemSize = 100,
            viewportEnd = 400,
            beforeContentPadding = 8,
            afterContentPadding = 8,
        )
        assertEquals(-150, moved)
    }

    @Test
    fun `视口还没量出来时不动`() {
        // 首帧 `viewportEndOffset` 可能还是 0；内边距一减就变成负数，
        // 这时算出来的位移会把列表滚到一个随机位置上。
        assertEquals(0, delta(itemOffset = 100, itemSize = 180, viewportEnd = 0))
        assertEquals(0, delta(itemOffset = 100, itemSize = 180, viewportEnd = -20))
    }

    @Test
    fun `内边距比视口还大时不动`() {
        // 视口 500 高，上下内边距各 300 ⇒ 内容区是负数。
        assertEquals(
            0,
            delta(itemOffset = 100, itemSize = 180, beforeContentPadding = 300, afterContentPadding = 300),
        )
    }

    @Test
    fun `行高还没量出来时不动`() {
        // `LazyListItemInfo.size` 为 0 只可能出现在「还没量」的那一帧。
        assertEquals(0, delta(itemOffset = 100, itemSize = 0))
        assertEquals(0, delta(itemOffset = 100, itemSize = -5))
    }

    @Test
    fun `同时平移视口和行位置时结果不变`() {
        // 规则算的是**相对位移**，调用方因此不用操心坐标系原点在哪
        // （`viewportStartOffset` 在带内边距的列表里并不总是 0）。
        val base = delta(itemOffset = 300, itemSize = 180)
        val shifted = delta(
            itemOffset = 300 + 137,
            itemSize = 180,
            viewportStart = 0 + 137,
            viewportEnd = 500 + 137,
        )
        assertEquals(base, shifted)
    }

    @Test
    fun `放得下的行滚完一定整行可见`() {
        // 真正在乎的不变量：只要放得进内容区，滚完以后上下都留得住。
        val bandStart = 24
        val bandEnd = 476
        for (size in listOf(40, 80, 120, 180, 240, 300, 451)) {
            for (offset in listOf(-400, 0, 100, 300, 520, 900)) {
                val top = offset - delta(itemOffset = offset, itemSize = size)
                assertTrue(
                    "size=$size offset=$offset ⇒ top=$top 越过了内容区上沿",
                    top >= bandStart,
                )
                assertTrue(
                    "size=$size offset=$offset ⇒ 底边 ${top + size} 越过了内容区下沿",
                    top + size <= bandEnd,
                )
            }
        }
    }
}
