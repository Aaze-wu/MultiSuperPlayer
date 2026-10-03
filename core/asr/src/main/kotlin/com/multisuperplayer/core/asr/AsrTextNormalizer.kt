package com.multisuperplayer.core.asr

/**
 * 把识别器吐出来的原始文本整理成能直接当字幕显示的文本。
 *
 * 这里只做**机械的**清理，不做任何「改写」：不补标点、不纠错、不删语气词。
 * 那些是模型该干的事，在这里用规则模拟只会让结果变得不可预测
 * （比如自作聪明地把「嗯」删掉，而它其实是台词的一部分）。
 */
internal object AsrTextNormalizer {

    /**
     * 识别器在「这段音频里没有可识别的语音」时返回的占位符。
     *
     * 必须当成**空**处理：直接拿去显示的话，静音段落会变成一条写着 `<unk>` 的字幕。
     * 大小写都算（不同模型的大小写习惯不一样）。
     */
    private val PLACEHOLDERS = setOf("<unk>", "<blank>", "<s>", "</s>", "(unk)", "[unk]", "unk")

    /**
     * 一条字幕**不该**以这些字符开头。
     *
     * 强制切分会把句子从中间切开，于是后半句的起头带着原来的标点（「，今天天气不错」）。
     * 字幕的行业惯例是行首不放标点，所以这里删掉——只删行首，行尾的句号是正常内容。
     *
     * 引号与括号**不删**：「他说「你好」」被切开后，后半句以「开头是合理的。
     */
    private const val LEADING_PUNCTUATION = "，。、！？；：,.!?;:…·"

    /**
     * sentencepiece 的词首标记。
     *
     * sherpa 的 `getText()` 已经把它还原成空格了，这里是保险：万一某个模型/版本漏出来了，
     * 屏幕上就会出现「今天▁天气」这种字符。它本身在中文和英文里都不可能出现，
     * 所以替换成空格是安全的。
     */
    private const val SENTENCEPIECE_WORD_START = '\u2581'

    /**
     * 清洗。返回的文本已经 trim，且不含多余空白。
     *
     * ## 为什么必须处理「汉字之间的空格」
     *
     * 不同模型的输出习惯不一样：zipformer 系列是**按 token 输出**的，每个汉字/词之间
     * 都带空格（`我 今天 下午 有 一个 meeting`），paraformer 多数情况下不插空格。
     * 只按一种写，另一种的字幕里就会满屏飘着空格（中文正字法里没有词间空格）。
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val tokens = raw.split(WHITESPACE)
            .map { it.replace(SENTENCEPIECE_WORD_START, ' ') }
            .filter { it.isNotBlank() && it.lowercase() !in PLACEHOLDERS }
        if (tokens.isEmpty()) return ""
        return collapseCjkSpaces(tokens.joinToString(" "))
            .trim()
            .trimStart(*LEADING_PUNCTUATION.toCharArray())
            .trim()
    }

    /**
     * 清洗之后**还有没有值得做成一条字幕的内容**。
     *
     * 只判断「有没有字母或数字」：「！」「…」这种纯标点的结果做不成字幕，
     * 而它又恰好是识别器在噪声上最常见的输出。汉字属于 `isLetter()`（Unicode 类别 Lo），
     * 所以这个判断同时覆盖中文和英文。
     */
    fun isMeaningful(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /**
     * 丢掉「上一个字符和下一个字符都是中日韩字符」的空格。
     *
     * 用码点而不是 `Char` 遍历：扩展 B 区（U+20000 以上的罕见人名用字）是代理对，
     * 按 `Char` 看会得到两个「非汉字」的半代理，于是那些人名中间会留下一个空格——
     * 一个只在罕见姓氏上出现的、看起来像「数据脏」的 bug。
     */
    private fun collapseCjkSpaces(text: String): String {
        val out = StringBuilder(text.length)
        var previous = -1
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == SPACE && previous >= 0 && index < text.length) {
                val next = text.codePointAt(index)
                if (isCjkLike(previous) && isCjkLike(next)) continue
            }
            out.appendCodePoint(codePoint)
            previous = codePoint
        }
        return out.toString()
    }

    /** 汉字、中文标点、全角标点——它们之间都不该有空格。 */
    private fun isCjkLike(codePoint: Int): Boolean = when (codePoint) {
        in 0x3400..0x4DBF -> true      // 扩展 A
        in 0x4E00..0x9FFF -> true      // 基本区
        in 0xF900..0xFAFF -> true      // 兼容汉字
        in 0x20000..0x2FA1F -> true    // 扩展 B～F 与兼容补充
        in 0x3000..0x303F -> true      // 中文标点（、。「」《》等）
        in 0xFF00..0xFFEF -> true      // 全角/半角形式（，！？（）：；等）
        else -> false
    }

    private const val SPACE = ' '.code
    private val WHITESPACE = Regex("\\s+")
}
