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
 * 4. **GB18030 兜底**：GBK 的超集，覆盖 GB2312/GBK，几乎不会解码失败。
 *
 * ## 已知局限（不是疏漏，是判定过的取舍）
 *
 * Big5（台湾繁体）文件会走到第 4 步，被按 GB18030 解出**同样合法但完全错误**的汉字。
 * 两者都是汉字，靠「像不像汉字」区分不了，真正分开需要「常用字频」统计——
 * 那是另一个量级的工作量，而 Big5 字幕的占比远低于 GBK。
 *
 * 现状是把探测结果如实返回（[Decoded.guessed]），让上层能提示用户，
 * 而不是悄悄给出一堆看着像字、实际全错的文本。
 */
internal object SubtitleTextDecoding {

    private const val UTF_8_NAME = "UTF-8"
    private const val GB18030_NAME = "GB18030"

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

    private const val MIN_UTF_16_PAIRS = 4
    private const val ZERO: Byte = 0

    private const val BYTE_0: Byte = 0xEF.toByte()
    private const val BYTE_1: Byte = 0xBB.toByte()
    private const val BYTE_2: Byte = 0xBF.toByte()
    private const val BYTE_FE: Byte = 0xFE.toByte()
    private const val BYTE_FF: Byte = 0xFF.toByte()
}
