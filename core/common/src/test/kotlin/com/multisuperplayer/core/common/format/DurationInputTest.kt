package com.multisuperplayer.core.common.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「时 / 分」两格输入的解析。
 *
 * 这一组的价值在于**这里没有界面**：五句解析结果各自对应界面上的一句话，
 * 而哪一句该出现完全由这里决定。所以这里盯的是「哪种输入落到哪一种结果」，
 * 不是「能不能解析出数字」。每条断言后面那件事的共同点是——说错话之后
 * 用户会照着错的话去改（填了字母却被告知「最短 15 分钟」，他会去把数字改大）。
 *
 * 上下界是**调用方给的**（睡眠定时 1 分钟 ~ 24 小时，更新冷却 15 分钟 ~ 7 天），
 * 所以这里两边都测：下界和上界各用一个「刚好」和一个「差一点」。
 */
class DurationInputTest {

    /** 和睡眠定时同一组的上下界：1 分钟 ~ 24 小时。 */
    private fun parse(hours: String, minutes: String): DurationInput =
        DurationInputParser.parse(hours, minutes, minMinutes = 1, maxMinutes = 24 * 60)

    // ------------------------------------------------------------ 空白

    @Test
    fun `两格都空是「还没填」而不是「零分钟」`() {
        // 对话框刚打开就是这个状态。塌成「太短」的话，一上来先摆一句红字；
        // 塌成「0 分钟」的话，确定键会在用户什么都没做时变成可用的。
        assertEquals(DurationInput.Blank, parse("", ""))
    }

    @Test
    fun `只有空白也算没填`() {
        // 粘贴或输入法补出来的空格不该把「还没填」变成「填错了」。
        assertEquals(DurationInput.Blank, parse("  ", " "))
    }

    // ------------------------------------------------------------ 合法输入

    @Test
    fun `只填一格时另一格按零算`() {
        // 只填分钟是最常见的用法：手机上「30」两下就打完了，
        // 没人愿意为了凑格式再打一个「0」。
        assertEquals(DurationInput.Valid(30), parse("", "30"))
        assertEquals(DurationInput.Valid(120), parse("2", ""))
        assertEquals(DurationInput.Valid(90), parse("1", "30"))
    }

    @Test
    fun `全角数字会被折成半角`() {
        // 中文输入法全角状态下打出来的 `１２３` 和半角数字长得几乎一样，
        // 不归一的话用户会对着一个明明填了数字的框读「只填数字」。
        assertEquals(DurationInput.Valid(120), parse("２", ""))
        assertEquals(DurationInput.Valid(90), parse("１", "３０"))
    }

    @Test
    fun `首尾空白不影响结果`() {
        assertEquals(DurationInput.Valid(90), parse(" 1 ", " 30 "))
    }

    // ------------------------------------------------------------ 不是数字

    @Test
    fun `字母和小数点都按「不是数字」报`() {
        // `3.5` 是「想填三小时半」的写法，但它不是一个数：说成「太长」或
        // 「太短」都会把用户带到错的方向（去改大小，而不是改写法）。
        assertEquals(DurationInput.NotANumber, parse("1 小时", "0"))
        assertEquals(DurationInput.NotANumber, parse("", "3.5"))
        assertEquals(DurationInput.NotANumber, parse("3", "5.5"))
    }

    @Test
    fun `负号按「不是数字」报而不是按「太短」报`() {
        // 负号只可能来自粘贴（两格用的是数字键盘），而它进总额里会算出一个
        // 说不上错的数：`-1 小时 90 分` = 正的 30 分钟。只按总额判会把这个
        // 填错的输入当合法值收下，而 `-1 小时 30 分` 报「太短」又会让人以为
        // 自己填的是 1 小时——那个负号从头到尾没被提到。
        assertEquals(DurationInput.NotANumber, parse("-1", "30"))
        assertEquals(DurationInput.NotANumber, parse("-1", "90"))
        assertEquals(DurationInput.NotANumber, parse("1", "-30"))
    }

    @Test
    fun `溢出成长整型装不下的数字按「不是数字」报`() {
        // 输入框限了长度，但两格的初值、粘贴、以及将来别的调用方都不一定限。
        // `toLongOrNull()` 对溢出返回 null，这一条钉的就是它没有被换成 `toLong()`。
        assertEquals(DurationInput.NotANumber, parse("99999999999999999999", ""))
    }

    // ------------------------------------------------------------ 上下界

    @Test
    fun `上下界都是闭区间`() {
        assertEquals(DurationInput.Valid(1), parse("", "1"))
        assertEquals(DurationInput.Valid(24 * 60), parse("24", "0"))

        assertEquals(DurationInput.TooShort, parse("", "0"))
        assertEquals(DurationInput.TooLong, parse("24", "1"))
    }

    @Test
    fun `两格的分钟数是相加的而不是分别判界`() {
        // 两格拼出来的是**一个**分钟数：分别判界的话，`0 小时 1500 分`
        // 和 `25 小时` 会被判成两件不同的事，而它们其实是同一个值。
        assertEquals(
            DurationInput.Valid(1500),
            DurationInputParser.parse("0", "1500", minMinutes = 1, maxMinutes = 48 * 60),
        )
        assertEquals(DurationInput.TooLong, parse("0", "1500"))
    }

    @Test
    fun `小时那一格很大时报「太长」而不是溢出成负数`() {
        // `36 小时` 那种小数字测不出这条：`4 亿小时 × 60` 才会绕过 Int 边界，
        // 而在 Int 里算的结果是个负数 ⇒「太长」变成「太短」，
        // 用户对着一个明显填得太大的框读「最短 1 分钟」。
        assertEquals(
            DurationInput.TooLong,
            DurationInputParser.parse("36000000", "0", minMinutes = 1, maxMinutes = Int.MAX_VALUE),
        )
    }
}
