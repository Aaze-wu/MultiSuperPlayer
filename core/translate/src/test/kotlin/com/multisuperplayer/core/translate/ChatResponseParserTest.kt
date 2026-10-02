package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * chat/completions 的**响应**解析（不是译文解析，见 [TranslationResponseParserTest]）。
 */
class ChatResponseParserTest {

    @Test
    fun `finish reason 映射`() {
        assertEquals(ChatFinish.ABSENT, chatFinish(null))
        assertEquals(ChatFinish.ABSENT, chatFinish("  "))
        assertEquals(ChatFinish.STOP, chatFinish("stop"))
        assertEquals(ChatFinish.STOP, chatFinish("STOP"))
        assertEquals(ChatFinish.STOP, chatFinish("end_turn"))
        assertEquals(ChatFinish.LENGTH, chatFinish("length"))
        assertEquals(ChatFinish.LENGTH, chatFinish("max_tokens"))
        assertEquals(ChatFinish.LENGTH, chatFinish("max_output_tokens"))
        assertEquals(ChatFinish.OTHER, chatFinish("content_filter"))
    }

    @Test
    fun `正常响应取到正文与用量`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":30,"completion_tokens":12,
                      "completion_tokens_details":{"reasoning_tokens":5}}}
        """.trimIndent()
        val outcome = parseChatCompletionResponse(body, 2048)

        val response = (outcome as ChatCompletionOutcome.Ok).response
        assertEquals("你好", response.content)
        assertEquals("stop", response.finishReason)
        assertEquals(12, response.completionTokens)
        assertEquals(5, response.reasoningTokens)
    }

    @Test
    fun `思考吃光预算时报预算错而不是解析错`() {
        // 这是 HTTP 200，看起来完全成功。报成「解析失败」的话，
        // 所有人都会去查提示词，而真正的解法是加大 max_tokens 或关掉思考。
        val body = """
            {"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}],
             "usage":{"completion_tokens":2048,"completion_tokens_details":{"reasoning_tokens":2048}}}
        """.trimIndent()
        val failure = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Err).failure

        val empty = failure as TranslationFailure.EmptyCompletion
        assertEquals("length", empty.finishReason)
        assertEquals(2048, empty.completionTokens)
        assertEquals(2048, empty.reasoningTokens)
        assertTrue(empty.detail.contains("max_tokens=2048"))
        assertFalse(failure.retryableAsIs)
        assertFalse(failure.abortsJob)
    }

    @Test
    fun `只有思考和没有正文也不能拿思考当译文`() {
        val body = """{"choices":[{"message":{"content":null,"reasoning_content":"我们需要把这句话翻译成中文…"}}]}"""
        val failure = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Err).failure
        assertTrue(failure is TranslationFailure.EmptyCompletion)
    }

    @Test
    fun `缺 usage 明细不崩`() {
        val body = """{"choices":[{"message":{"content":"a"},"finish_reason":"stop"}],"usage":{"completion_tokens":3}}"""
        val response = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Ok).response
        assertNull(response.reasoningTokens)
        assertEquals(3, response.completionTokens)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `非 JSON 响应报形状错并带上原文片段`() {
        val failure = (parseChatCompletionResponse("<html>502 Bad Gateway</html>", 2048) as ChatCompletionOutcome.Err).failure
        val bad = failure as TranslationFailure.BadResponse
        assertTrue(bad.detail.contains("<html>"))
    }

    @Test
    fun `顶层不是对象也是形状错`() {
        val failure = (parseChatCompletionResponse("""[1,2,3]""", 2048) as ChatCompletionOutcome.Err).failure
        assertTrue(failure is TranslationFailure.BadResponse)
    }

    @Test
    fun `HTTP 200 里包着 error 也要拦下来`() {
        val body = """{"error":{"message":"Model not found: gpt-99","type":"invalid_request_error"}}"""
        val failure = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Err).failure
        assertTrue((failure as TranslationFailure.BadResponse).detail.contains("Model not found: gpt-99"))
    }

    @Test
    fun `分片数组形式的正文会拼起来`() {
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"前半"},{"type":"text","text":"后半"}]}}]}"""
        val response = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Ok).response
        assertEquals("前半后半", response.content)
    }

    @Test
    fun `老式 text completions 形状也能读`() {
        val body = """{"choices":[{"text":"老格式","finish_reason":"stop"}]}"""
        val response = (parseChatCompletionResponse(body, 2048) as ChatCompletionOutcome.Ok).response
        assertEquals("老格式", response.content)
    }

    @Test
    fun `没有 choices 也算空内容`() {
        val failure = (parseChatCompletionResponse("""{"choices":[]}""", 2048) as ChatCompletionOutcome.Err).failure
        assertTrue(failure is TranslationFailure.EmptyCompletion)
    }

    // ------------------------------------------------------------ 模型列表

    @Test
    fun `三种模型列表形状都能读`() {
        val openAi = (parseModelList("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""") as ModelListOutcome.Ok).models
        assertEquals(listOf("a", "b"), openAi)

        val ollama = (parseModelList("""{"models":[{"name":"qwen3:8b"}]}""") as ModelListOutcome.Ok).models
        assertEquals(listOf("qwen3:8b"), ollama)

        val bare = (parseModelList("""["m2","m1"]""") as ModelListOutcome.Ok).models
        assertEquals(listOf("m1", "m2"), bare)
    }

    @Test
    fun `读不到模型列表时报错而不是空列表`() {
        // 空列表在界面上表现为「下拉框点开什么都没有」，用户会以为是自己填错了地址。
        val empty = parseModelList("""{"data":[]}""")
        assertTrue(empty is ModelListOutcome.Err)
        assertTrue((empty as ModelListOutcome.Err).failure is TranslationFailure.BadResponse)

        assertTrue(parseModelList("不是 JSON") is ModelListOutcome.Err)
        assertTrue(parseModelList("""{"object":"list"}""") is ModelListOutcome.Err)
    }

    // ------------------------------------------------------------ token 估算

    @Test
    fun `中文按字数算而不是按四分之一算`() {
        assertTrue(estimateTokens("中".repeat(200)) > estimateTokens("a".repeat(200)) * 3)
        assertEquals(201, estimateTokens("中".repeat(200)))
        assertEquals(51, estimateTokens("a".repeat(200)))
        assertEquals(1, estimateTokens(""))
    }
}
