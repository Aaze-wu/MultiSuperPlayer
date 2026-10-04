package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.subtitle.ParseResult
import com.multisuperplayer.core.subtitle.SrtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「读字幕时凑出来的告警」。
 *
 * ## 为什么这条规则值得单独一个测试
 *
 * 它此前一行测试都没有，因为它藏在 `SubtitleRepository.buildDocument` 里——
 * 而那个方法要 `Context` / `ContentResolver` 才能走进去，单测根本到不了。
 * 于是这条规则的实际状态是「写了，但从来没人验证过它会不会触发」。
 * 现在它被摘成不碰任何 Android API 的 [subtitleWarnings]，这里就能直接调。
 *
 * ## 为什么失败的样子最难发现
 *
 * 编码是猜的时候，界面上**除了乱码什么都没有**：Big5 字幕被按 GB18030 解出来
 * 的汉字是「字形合法、内容全错」的，看起来只是「这播放器的字体有问题」。
 * 少掉的恰恰是唯一能解释这件事的那句话，所以它错了也不会有人报 bug。
 */
class SubtitleWarningsTest {

    @Test
    fun `编码是猜的就必须说出来`() {
        val warnings = subtitleWarnings(
            parsed = parseSrt(),
            decoded = decoded(charset = "GB18030", guessed = true),
        )

        assertEquals(
            listOf<MspText>(MspText.Res(R.string.msp_subtitle_warn_charset_guessed, "GB18030")),
            warnings,
        )
    }

    @Test
    fun `UTF-8 文件不加编码告警`() {
        // 反过来的那一半：普通 UTF-8 文件上挂一句「文件不是 UTF-8」比不提示更糟。
        val warnings = subtitleWarnings(
            parsed = parseSrt(),
            decoded = decoded(charset = "UTF-8", guessed = false),
        )

        assertEquals(emptyList<MspText>(), warnings)
    }

    @Test
    fun `解析器自己的告警一条都不能丢`() {
        // 编码那条是**追加**在解析告警后面的，不是替换。用真实的解析器产出告警，
        // 而不是手搓一个 `ParseResult`：手搓版本永远不会变，测试也就永远绿，
        // 而真正的风险恰恰是「以后有人在前面的链路里把 parsed.warnings 丢掉了」。
        val parsed = parseSrt(
            """
            1
            00:00:05,000 --> 00:00:01,000
            倒着走的时间码
            """.trimIndent(),
        )

        assertTrue("这个输入本身就该给出一条解析告警，实际：${parsed.warnings}", parsed.warnings.isNotEmpty())

        val warnings = subtitleWarnings(parsed, decoded(charset = "GB18030", guessed = true))

        assertEquals(
            "解析器的告警应当原样在前，编码那条追加在最后",
            parsed.warnings + MspText.Res(R.string.msp_subtitle_warn_charset_guessed, "GB18030"),
            warnings,
        )
    }

    private fun parseSrt(content: String = ""): ParseResult = SrtParser().parse(content)

    private fun decoded(charset: String, guessed: Boolean) = SubtitleTextDecoding.Decoded(
        text = "",
        charset = charset,
        guessed = guessed,
    )
}
