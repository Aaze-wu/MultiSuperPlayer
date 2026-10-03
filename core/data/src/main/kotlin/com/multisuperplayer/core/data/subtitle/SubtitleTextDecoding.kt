package com.multisuperplayer.core.data.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 字幕文件的字符编码探测。
 *
 * ## 为什么必须有这一步
 *
 * 中文字幕有相当一部分是 **GBK**（老发布组的产物、Windows 记事本「另存为 ANSI」
 * 的结果）。直接按 UTF-8 读会得到满屏「锟斤拷」，而用户看到的现象是
 * 「这个播放器的字幕是乱码」——他会以为是播放器的 bug，而不是文件编码的问题。
 *
 * ## 判定顺序，以及每一步的理由
 *
 * 1. **BOM**：有 BOM 就是权威答案，直接采信。这是四步里唯一不靠猜的一步。
 * 2. **无 BOM 的 UTF-16**：这一步必须排在严格 UTF-8 **之前**。因为 NUL 字节本身
 *    是合法 UTF-8，UTF-16 文本会被「成功」按 UTF-8 解出一堆夹着 `\u0000` 的字符
 *    ——所有防线都以为解码没问题，而解析器一条 cue 也读不出来。
 * 3. **严格 UTF-8**：用 [CodingErrorAction.REPORT] 解码，遇到非法字节序列即失败。
 *    这一步之所以可靠，是因为「一段像样的 GBK 文本恰好构成合法 UTF-8」的概率极低
 *    （要求每个双字节序列都落在 UTF-8 的合法区间里）；反过来判断则完全不可靠。
 * 4. **Shift-JIS / CP932**：日语字幕的主流编码。这一步必须排在 GB18030 **之前**——
 *    Shift-JIS 的字节序列几乎全部落在 GB18030 的合法范围里，一旦掉进兜底就会被
 *    解成「合法的乱码」（见 `decodeShiftJis`）。正因为「能不能解」本身不足以判定，
 *    这一步还要再比一次分。
 * 5. **GB18030 兜底**：GBK 的超集，覆盖 GB2312/GBK，几乎不会解码失败。
 *
 * ## 已知局限（不是疏漏，是判定过的取舍）
 *
 * **Big5（台湾繁体）**文件会走到第 5 步，被按 GB18030 解出**同样合法但完全错误**的汉字。
 * 两者都是汉字，靠「像不像汉字」区分不了，真正分开需要「常用字频」统计——
 * 那是另一个量级的工作量，而 Big5 字幕的占比远低于 GBK。
 *
 * **全汉字、没有假名的日语**（`東京大学医学部付属病院`）会在第 4 步比成同分，
 * 同样落到 GB18030（详见 [japaneseLikeness]）。理由与 Big5 那条一样。
 *
 * 现状是把探测结果如实返回（[Decoded.guessed]），让上层能提示用户，
 * 而不是悄悄给出一堆看着像字、实际全错的文本。
 */
internal object SubtitleTextDecoding {

    private const val UTF_8_NAME = "UTF-8"
    private const val GB18030_NAME = "GB18030"

    /**
     * 解出日语时报告给用户的字符集名。
     *
     * 不用 `Charset.name()`：本平台实际生效的那个名字可能是 `windows-31j`
     * （Java 的 MS932，比 JIS 标准多认 NEC/IBM 扩展字），而这个名字会直接进 UI
     * （「已按 windows-31j 解码」）。对用户来说只有「Shift-JIS」有意义，
     * 所以这里统一报一个固定名字，不把平台细节漏出去。
     */
    private const val SHIFT_JIS_NAME = "Shift-JIS"

    /**
     * 无 BOM 的 UTF-16 判定阈值：一半的字节对里至少 25% 有一个 0x00。
     *
     * 取 25% 而不是 50% 是因为字幕文件里必然混有少量非 ASCII（中文台词），
     * 那部分字节对不会出现 0x00；纯 ASCII 的 JSON/时间码则接近 50%。
     */
    private const val UTF_16_ZERO_RATIO = 4

    /**
     * @param text 解码后的文本（已去掉 BOM）。
     * @param charset 实际使用的字符集名，用于日志与用户提示。
     * @param guessed 是否是「UTF-8 不像，猜了一个」——为 true 时上层应当提示用户。
     */
    data class Decoded(
        val text: String,
        val charset: String,
        val guessed: Boolean,
    )

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded("", UTF_8_NAME, guessed = false)

        bomOf(bytes)?.let { bom ->
            val decoded = decodeRange(bytes, bom.length, bom.charset)
            if (decoded != null) return Decoded(decoded, bom.charset.name(), guessed = false)
            // BOM 声明是 UTF-16 但内容不是合法 UTF-16（文件被截断/改坏）。
            // 落到下面的常规路径，至少能试着救回来。
        }

        // 必须在严格 UTF-8 之前：UTF-16 文本是「合法」的 UTF-8（NUL 字节合法），
        // 放到后面就永远轮不到它。
        guessUtf16(bytes)?.let { charset ->
            decodeRange(bytes, 0, charset)?.let {
                return Decoded(it, charset.name(), guessed = true)
            }
        }

        strictDecodeUtf8(bytes)?.let { return Decoded(it, UTF_8_NAME, guessed = false) }

        val gb18030 = Charset.forName(GB18030_NAME)
        decodeShiftJis(bytes, gb18030)?.let { return it }

        return Decoded(String(bytes, gb18030), gb18030.name(), guessed = true)
    }

    private data class Bom(val charset: Charset, val length: Int)

    /**
     * 没有 BOM 的 UTF-16 探测。
     *
     * 依据：UTF-16 里只要出现 ASCII 字符（字幕的时间码、标签几乎全是 ASCII），
     * 就必然有一个字节是 `0x00`——小端在**奇数**位，大端在**偶数**位。
     * 数一数哪一侧的 0 多，就知道是哪一种，不需要 BOM。
     *
     * 对合法 UTF-8 / GBK 文本不会误判：两者的任何字节都不会是 `0x00`。
     */
    private fun guessUtf16(bytes: ByteArray): Charset? {
        val pairs = bytes.size / 2
        if (pairs < MIN_UTF_16_PAIRS) return null

        var evenZeros = 0
        var oddZeros = 0
        var index = 0
        while (index + 1 < bytes.size) {
            if (bytes[index] == ZERO) evenZeros++
            if (bytes[index + 1] == ZERO) oddZeros++
            index += 2
        }

        val threshold = pairs / UTF_16_ZERO_RATIO
        return when {
            oddZeros > threshold && evenZeros < threshold -> Charsets.UTF_16LE
            evenZeros > threshold && oddZeros < threshold -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun bomOf(bytes: ByteArray): Bom? = when {
        bytes.size >= 3 && bytes[0] == BYTE_0 && bytes[1] == BYTE_1 && bytes[2] == BYTE_2 ->
            Bom(Charsets.UTF_8, 3)

        // 注意 UTF-32LE 的 BOM 是 FF FE 00 00，会先匹配到下面这一条并被当成 UTF-16LE。
        // 字幕文件不存在 UTF-32（没人这么存），所以不为它增加分支。
        bytes.size >= 2 && bytes[0] == BYTE_FF && bytes[1] == BYTE_FE ->
            Bom(Charsets.UTF_16LE, 2)

        bytes.size >= 2 && bytes[0] == BYTE_FE && bytes[1] == BYTE_FF ->
            Bom(Charsets.UTF_16BE, 2)

        else -> null
    }

    private fun decodeRange(bytes: ByteArray, offset: Int, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }.getOrNull()

    private fun strictDecodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * 试解 Shift-JIS：**解成功不等于解对了**，还要看结果像不像日语。
     *
     * ## 为什么不能只判「解成功了」
     *
     * 两个方向的难度完全不对称：
     *
     * - Shift-JIS 的双字节序列（首字节 0x81–0x9F / 0xE0–0xFC，次字节 0x40–0x7E / 0x80–0xFC）
     *   几乎**全部**落在 GB18030 的合法范围里，所以任何 Shift-JIS 文件都能被 GB18030
     *   「成功」地解出一堆真汉字——这正是乱码的来源。实测 `こんにちは、世界。` 会变成
     *   `偙傫偵偪偼丄悽奅丅`：每个字都是合法汉字，看着完全不像出错。
     * - 反过来，GBK 的常用汉字首字节是 0xB0–0xF7，其中 0xB0–0xDF 在 Shift-JIS 里是
     *   **单字节**半角片假名，会把后面的字节全部错位，于是多半在严格解码这一步就失败。
     *
     * 所以「严格 Shift-JIS 能解」本身就是「这段字节可能不是 GBK」的强信号，但还不够：
     * 还要比一次分，拦住「GBK 文本恰好避开了那些字节、被 Shift-JIS 整段错位解出来」
     * 这种小概率情况（它的特征是成片的半角片假名，见 [japaneseLikeness] 的负分）。
     *
     * @param gb18030 兜底字符集，同时用作比分的参照——**平手时维持原有行为**。
     */
    private fun decodeShiftJis(bytes: ByteArray, gb18030: Charset): Decoded? {
        val fallbackScore = japaneseLikeness(String(bytes, gb18030))
        for (charset in shiftJisCandidates) {
            val text = decodeRange(bytes, 0, charset) ?: continue
            if (japaneseLikeness(text) > fallbackScore) {
                return Decoded(text, SHIFT_JIS_NAME, guessed = true)
            }
        }
        return null
    }

    /**
     * 这段文本「有多像日语」。**只用来在两个候选解码之间比大小**，绝对值没有意义。
     *
     * 三类信号：
     *
     * - 全角假名（+3）：只有日语有。GB18030 把 Shift-JIS 的假名字节解成生僻汉字，
     *   所以日语文件的 GB18030 候选里一个假名都不会有。
     * - 半角片假名（−5）：GBK 文本被按 Shift-JIS 读时的典型产物（见 `decodeShiftJis`），
     *   罚分要重到「一片半角片假名」逆不了局面。
     * - 汉字与中日标点（+1）：两个候选都有，只用来给「解出真字」加一点权重。
     *   私用区（−3）相反：那是 Shift-JIS 的用户定义区，正常字幕里不会出现。
     *
     * 注意 [HALFWIDTH_KATAKANA] 落在 [FULLWIDTH_FORMS] 里，`when` 的**顺序不能反**，
     * 否则半角片假名会先被 +1 接住，罚分永远轮不到。
     *
     * 打分区间和 [AsrTextNormalizer.isCjkLike] 是两件事，不要想着合并：那边判「要不要
     * 折叠空格」，汉字和假名都要算；这里判「更像日语还是更像中文」，假名必须比汉字重。
     *
     * ## 已知的模糊区
     *
     * **全汉字、没有假名的日语**（`東京大学医学部付属病院`）与同字节数的 GBK **同分**，
     * 于是落到 GB18030。两边解出来都是真汉字（一个是对的，一个是生僻字），
     * 区分它们得靠常用字频统计。实测 12 条真实日语里只有这一条会漏判，
     * 而真实日语字幕几乎不可能整篇没有假名，所以这里选「平手时保持原有行为」——
     * 宁可漏判一条，也不去改中文文件今天的行为。
     */
    private fun japaneseLikeness(text: String): Int {
        var score = 0
        for (character in text) {
            val code = character.code
            score += when {
                code in HIRAGANA || code in KATAKANA -> 3
                code in HALFWIDTH_KATAKANA -> -5
                code in CJK_IDEOGRAPHS || code in CJK_EXTENSION_A || code in CJK_COMPATIBILITY -> 1
                code in CJK_PUNCTUATION -> 1
                code in FULLWIDTH_FORMS -> 1
                code in PRIVATE_USE_AREA -> -3
                else -> 0
            }
        }
        return score
    }

    private const val MIN_UTF_16_PAIRS = 4
    private const val ZERO: Byte = 0

    /**
     * 按优先级排列的 Shift-JIS 变体。
     *
     * `windows-31j`（Java 的 MS932）是 `Shift_JIS` 的超集：多认 NEC 选定的第 13 区
     * 与 IBM 扩展字，真实世界的 Windows 生成日语字幕里那些字是存在的。先试它，
     * 失败了再退回教科书版。
     *
     * 用 `runCatching` 而不是直接 `Charset.forName`：两者都不在时（理论上限定的
     * 平台配置）应当继续走 GB18030 兜底，而不是让整个字幕加载抛异常。
     */
    private val shiftJisCandidates: List<Charset> = listOf("windows-31j", "Shift_JIS")
        .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }

    private val HIRAGANA = 0x3040..0x309F
    private val KATAKANA = 0x30A0..0x30FF
    private val HALFWIDTH_KATAKANA = 0xFF61..0xFF9F

    /** 与全角形式 [FULLWIDTH_FORMS] 的区间分开写：半角片假名要先被罚分接住。 */
    private val FULLWIDTH_FORMS = 0xFF01..0xFF60

    private val CJK_EXTENSION_A = 0x3400..0x4DBF
    private val CJK_IDEOGRAPHS = 0x4E00..0x9FFF
    private val CJK_COMPATIBILITY = 0xF900..0xFAFF
    private val CJK_PUNCTUATION = 0x3000..0x303F
    private val PRIVATE_USE_AREA = 0xE000..0xF8FF

    private const val BYTE_0: Byte = 0xEF.toByte()
    private const val BYTE_1: Byte = 0xBB.toByte()
    private const val BYTE_2: Byte = 0xBF.toByte()
    private const val BYTE_FE: Byte = 0xFE.toByte()
    private const val BYTE_FF: Byte = 0xFF.toByte()
}
