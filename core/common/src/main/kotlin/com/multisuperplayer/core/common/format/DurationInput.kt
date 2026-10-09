package com.multisuperplayer.core.common.format

/**
 * 「自定义时长」那两格输入框的初值（小时 + 分钟）。
 *
 * 拆成两格而不是让用户填一个「总共多少分钟」：想设两小时的人要么在脑子里乘
 * 60，要么盯着那一个框犹豫自己填的到底是分钟还是小时。
 *
 * [hoursText] / [minutesText] 是给输入框的初值：**值为 0 的那一格返回空串**。
 * 一份「0 小时 30 分」的初值会让人以为自己填错过什么。这条规矩写在数据类上
 * 而不是每个对话框各写一遍——两个对话框（睡眠定时、自动检查间隔）是同一个
 * 动作的两种实例，各写一遍就总有一天只改其中一个。
 */
data class DurationDraft(val hours: Int, val minutes: Int) {

    fun hoursText(): String = hours.takeIf { it > 0 }?.toString().orEmpty()

    fun minutesText(): String = minutes.takeIf { it > 0 }?.toString().orEmpty()
}

/**
 * 「时 / 分」两格输入的解析结果。
 *
 * 分成五种而不是「分钟数 or null」：这几种情况要给出**不同的话**。全都塌成
 * `null` 的话，「填了个字母」和「填了 9999 小时」会得到同一句提示，
 * 而这两件事的下一步动作完全相反——一个是改字，一个是改小。
 *
 * 所有判断都在纯函数里、不碰界面：界面只负责把 [NotANumber]、[TooShort]、
 * [TooLong] 各自翻译成一句话（那三句话是资源，进不了 JVM 单测）。
 */
sealed interface DurationInput {

    /** 解析成功。[minutes] 已落在调用方给的上下界之内。 */
    data class Valid(val minutes: Int) : DurationInput

    /**
     * 两格都空着。
     *
     * **这不是出错**：对话框刚打开、用户还没动手时就是这个状态，
     * 一上来先摆一句红字等于在骂人。它只是「还不能点确定」。
     */
    data object Blank : DurationInput

    /** 有一格填的不是数字。负数也算（见 [DurationInputParser.parse]）。 */
    data object NotANumber : DurationInput

    /** 解析出来比下界还小（不含负数——那是 [NotANumber]）。 */
    data object TooShort : DurationInput

    /** 超过了上界。 */
    data object TooLong : DurationInput
}

/**
 * 把「小时 + 分钟」两格的原文解析成一个分钟数。
 *
 * ## 为什么住在 `core:common` 而不是各自实现一遍
 *
 * 这个解析里有**两个看不太出来但一定会踩的坑**，它们各自被单测抓过一次：
 *
 * - `"".toLongOrNull()` 返回的是 `null`（不是 0）。直接用它会让「只填分钟」
 *   这种最常规的用法掉进「只填数字」那句提示里——一个和真实原因完全无关的说法，
 *   而且它看着还挺合理，所以会查很久（见 [String.toCountOrNull]）；
 * - 中文输入法的全角状态下打出来的 `１２３` 和半角数字**长得几乎一样**，
 *   不归一的话用户会对着一个明明填了「１０」的框读「只填数字」（见
 *   [String.normalizedNumber]）。
 *
 * 睡眠定时和自动检查间隔是同一件事的两种参数（都让用户填「几小时几分钟」），
 * 让它们各写一遍等于把这两个坑各留一份。上下界由调用方给，因为
 * 「多长算合理」是各自领域的知识（睡前定时最长 24 小时，更新冷却最长 7 天）。
 *
 * 上下界是**闭区间**：等于下界/上界都算合法。
 *
 * @param minMinutes 允许的最小分钟数。填出来比它小（含 0）报 [DurationInput.TooShort]。
 * @param maxMinutes 允许的最大分钟数。填出来比它大报 [DurationInput.TooLong]。
 */
object DurationInputParser {

    /**
     * 解析两格输入。
     *
     * 两格都允许空着，空 = 0：只填分钟是最常见的用法，硬要求「小时那格填 0」
     * 只会让人多打一个字。
     *
     * 全角数字（`１２３`）会先折成半角，见 [String.normalizedNumber]。
     */
    fun parse(
        hoursText: String,
        minutesText: String,
        minMinutes: Int,
        maxMinutes: Int,
    ): DurationInput {
        val hours = hoursText.normalizedNumber()
        val minutes = minutesText.normalizedNumber()
        if (hours.isEmpty() && minutes.isEmpty()) return DurationInput.Blank

        val hoursValue = hours.toCountOrNull() ?: return DurationInput.NotANumber
        val minutesValue = minutes.toCountOrNull() ?: return DurationInput.NotANumber

        // 两格里出现负数一律按「不是数字」报，而不是让它进总额里算。
        // `-1 小时 90 分` 的总额是 30 分钟——一个完全合法的值，而用户明明
        // 填了个负号；连 `-1 小时 30 分` 报「太短」也是在误导（它会让人以为
        // 自己填的是 1 小时，而那个负号从头到尾没被提到）。两格用的是数字
        // 键盘，负号只可能来自粘贴，所以「不是数字」恰好是那个场景下对的话。
        if (hoursValue < 0 || minutesValue < 0) return DurationInput.NotANumber

        // 在 Long 里算：输入框已经限了长度，但 `小时 * 60` 在 Int 里照样可能溢出，
        // 而溢出成一个负数会让它掉进「太短」那句提示里——一个和真实原因
        // 完全无关的说法。
        val total = hoursValue * 60L + minutesValue
        return when {
            total < minMinutes -> DurationInput.TooShort
            total > maxMinutes -> DurationInput.TooLong
            else -> DurationInput.Valid(total.toInt())
        }
    }
}

/**
 * 去掉首尾空白，并把全角数字折成半角。
 *
 * 只做这两件事，不做「顺手把中文数字也认了」：`十二` 这种输入一旦被接受，
 * 就得开始考虑 `十二点五`、`半`、`一刻钟`…那条路没有尽头，而它带来的收益
 * 远小于「用户以为应用听懂了中文」。
 */
private fun String.normalizedNumber(): String = trim().map { ch ->
    if (ch in '\uFF10'..'\uFF19') '0' + (ch - '\uFF10') else ch
}.joinToString("")

/**
 * 把一格输入读成数字，**空格子读成 0**。
 *
 * `toLongOrNull()` 对空串返回的是 `null`（不是 0），直接用它会让「只填分钟」
 * 这种最常规的用法掉进「只填数字」那句提示里——一个和真实原因完全无关的说法，
 * 而且它看着还挺合理，所以会查很久。两格都空的情况在调用处已经先被
 * [DurationInput.Blank] 拦掉了，所以走到这里时空确实只表示 0。
 */
private fun String.toCountOrNull(): Long? = if (isEmpty()) 0L else toLongOrNull()
