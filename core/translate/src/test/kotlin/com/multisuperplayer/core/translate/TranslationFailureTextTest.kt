package com.multisuperplayer.core.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 失败文案的单元测试。
 *
 * 这里锁的是**两条方向相反的补救建议不能混**：
 * 形状不对 ⇒ 换模型/缩小批次；预算不够 ⇒ 调大 maxTokens。
 * 文案一旦合并，用户就会用错药，而且从界面上看不出自己用错了。
 */
class TranslationFailureTextTest {

    private fun allFailures(): List<TranslationFailure> = listOf(
        TranslationFailure.NotConfigured("服务地址"),
        TranslationFailure.Unauthorized(401, "invalid_api_key"),
        TranslationFailure.QuotaExceeded(429, "insufficient balance"),
        TranslationFailure.RateLimited(429, 30, "too many requests"),
        TranslationFailure.Rejected(404, "model not found"),
        TranslationFailure.ServerError(503, "upstream error"),
        TranslationFailure.Network("连接超时（api.deepseek.com）"),
        TranslationFailure.BadResponse("缺少 translations 字段"),
        TranslationFailure.EmptyCompletion("length", 2048, 2048, "content 为空"),
        TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"),
    )

    @Test
    fun `每一档都有话说而且都带下一步`() {
        allFailures().forEach { failure ->
            val text = describeTranslationFailure(failure)

            assertTrue("${failure::class.simpleName} 没有消息", text.message.isNotBlank())
            assertTrue("${failure::class.simpleName} 没有建议", !text.hint.isNullOrBlank())
        }
    }

    @Test
    fun `形状不对与预算不够必须给出不同的建议`() {
        val bad = describeTranslationFailure(TranslationFailure.BadResponse("缺少 translations 字段"))
        val truncated = describeTranslationFailure(TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"))

        assertFalse("两档的结论不能长得一样", bad.message == truncated.message)
        assertFalse("两档的补救方向相反，建议不能一样", bad.hint == truncated.hint)
        assertTrue("形状不对不该建议加大预算", bad.hint!!.contains("模型"))
        assertTrue("预算不够要说清 maxTokens", truncated.hint!!.contains("maxTokens"))
        assertTrue("预算不够要给出当前值", truncated.hint!!.contains("2048"))
    }

    @Test
    fun `空输出要指出是推理预算被吃掉`() {
        val text = describeTranslationFailure(
            TranslationFailure.EmptyCompletion("length", 2048, 2048, "content 为空"),
        )

        assertTrue(text.message.contains("finish_reason=length"))
        assertTrue(text.hint!!.contains("2048"))
    }

    @Test
    fun `建议里点名当前服务商和模型`() {
        val text = describeTranslationFailure(
            TranslationFailure.Rejected(404, "model not found"),
            providerName = "Kimi（月之暗面）",
            model = "kimi-k2.6",
        )

        assertTrue("换模型之前得先知道现在用的是哪个", text.hint!!.contains("Kimi（月之暗面）"))
        assertTrue(text.hint!!.contains("kimi-k2.6"))
    }

    @Test
    fun `没填服务商和模型时不要编一个`() {
        val text = describeTranslationFailure(TranslationFailure.Unauthorized(401, "x"))

        assertFalse(text.hint!!.contains("当前"))
        assertEquals("x", text.raw)
    }

    @Test
    fun `空白的服务商名字也当没填`() {
        val text = describeTranslationFailure(
            TranslationFailure.Rejected(404, "model not found"),
            providerName = "   ",
            model = "",
        )

        assertFalse(text.hint!!.contains("当前服务商："))
        assertFalse(text.hint!!.contains("当前模型："))
    }

    @Test
    fun `厂商原文永远保留`() {
        val text = describeTranslationFailure(TranslationFailure.Unauthorized(401, "invalid_api_key"))

        assertEquals("invalid_api_key", text.raw)
        assertTrue(
            "用户只有看到厂商原文才能自己解决",
            text.message.contains("401"),
        )
    }

    @Test
    fun `网络档把分类器给出的提示原样透出`() {
        val detail = "连不上 https://api.deepseek.com：请检查网络，或确认 adb reverse 是否还在"
        val text = describeTranslationFailure(TranslationFailure.Network(detail))

        assertEquals(detail, text.hint)
        assertNull("网络档没有额外的厂商原文", text.raw)
    }

    @Test
    fun `限流档带上服务商要求的等待时间`() {
        val text = describeTranslationFailure(TranslationFailure.RateLimited(429, 30, "too many requests"))

        assertTrue(text.hint!!.contains("30"))
    }

    @Test
    fun `限流档没给等待时间也能给建议`() {
        val text = describeTranslationFailure(TranslationFailure.RateLimited(429, null, "too many requests"))

        assertTrue(text.hint!!.contains("稍"))
    }
}
