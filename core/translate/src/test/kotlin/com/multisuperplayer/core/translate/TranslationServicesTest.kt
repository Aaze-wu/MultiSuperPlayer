package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 服务商预设与端点拼接。
 *
 * 这里断言的是"用户填了三种常见形状的地址，都不会拼出 404"——
 * 因为 404 在界面上看起来和"模型名写错了"一模一样，用户根本无从下手。
 */
class TranslationServicesTest {

    @Test
    fun `只写域名会补上 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com"),
        )
    }

    @Test
    fun `尾部斜杠不算错`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com/"),
        )
    }

    @Test
    fun `用户把完整端点抄进来时不重复拼接`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com/chat/completions"),
        )
    }

    @Test
    fun `带路径的 base 只在末尾追加`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            chatCompletionsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            chatCompletionsUrl(TranslationServices.ZHIPU.baseUrl),
        )
    }

    @Test
    fun `模型列表端点会退回 base 再去掉 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/models",
            modelsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/models",
            modelsUrl("https://api.deepseek.com/chat/completions"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            modelsUrl(TranslationServices.ZHIPU.baseUrl),
        )
    }

    @Test
    fun `未知 id 回退到自定义而不是默认厂商`() {
        // 静默回默认厂商会让用户以为自己的配置生效了：界面显示 DeepSeek，
        // 实际请求发到 DeepSeek，而用户填的中转地址被无视。
        assertEquals(TranslationServices.CUSTOM, TranslationServices.byId("没见过的"))
        assertEquals(TranslationServices.CUSTOM, TranslationServices.byId(null))
        assertEquals(TranslationServices.DEEPSEEK, TranslationServices.byId("deepseek"))
        assertEquals(TranslationServices.OLLAMA, TranslationServices.byId("ollama"))
    }

    @Test
    fun `预设 id 唯一且自定义排在最后`() {
        val ids = TranslationServices.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(TranslationServices.CUSTOM_ID, ids.last())
    }

    @Test
    fun `每个预设的思考开关都是合法 JSON 对象`() {
        // 拼错了不会报错，只会静默地"关不掉思考"，然后长字幕白烧 token。
        TranslationServices.all.forEach { service ->
            if (service.disableThinkingBody.isBlank()) return@forEach
            val parsed = parseExtraBody(service.disableThinkingBody)
            assertNotNull(parsed, "预设 ${service.id} 的 disableThinkingBody 不是合法 JSON 对象")
            assertTrue(parsed.isNotEmpty())
        }
    }

    @Test
    fun `需要 key 的预设都有非空地址和模型`() {
        TranslationServices.all
            .filter { it.requiresApiKey }
            .forEach {
                assertTrue(it.baseUrl.startsWith("https://"), "预设 ${it.id} 的地址应是 https")
                assertTrue(it.model.isNotBlank(), "预设 ${it.id} 缺模型名")
            }
    }

    @Test
    fun `关思考参数解析不出来时返回 null 而不是抛异常`() {
        assertNull(parseExtraBody(""))
        assertNull(parseExtraBody("   "))
        assertNull(parseExtraBody("不是 JSON"))
        assertNull(parseExtraBody("[1,2,3]"))
        assertNotNull(parseExtraBody("""{"thinking":{"type":"disabled"}}"""))
    }

    @Test
    fun `关思考参数会原样并入请求体并能覆盖默认值`() {
        val request = ChatCompletionRequest(
            url = chatCompletionsUrl(TranslationServices.DEEPSEEK.baseUrl),
            apiKey = "sk",
            model = "deepseek-flash",
            systemPrompt = "s",
            userPrompt = "u",
            maxTokens = 2048,
            temperature = 0.3,
            extraBody = parseExtraBody(TranslationServices.DEEPSEEK.disableThinkingBody),
        )
        val body = buildChatRequestJson(request)
        val thinking = body["thinking"]?.toString()?.replace(Regex("\\s"), "")
        assertEquals("""{"type":"disabled"}""", thinking)
        // 模型名不允许被 extraBody 顶掉（用户填错位置时最危险）。
        assertEquals("\"deepseek-flash\"", body["model"].toString())
    }

    @Test
    fun `关闭 json 模式时不带 response_format`() {
        val request = ChatCompletionRequest(
            url = "https://x/chat/completions",
            apiKey = null,
            model = "m",
            systemPrompt = "s",
            userPrompt = "u",
            maxTokens = 100,
            temperature = 0.3,
            jsonMode = true,
        )
        assertTrue(buildChatRequestJson(request).containsKey("response_format"))
        assertFalse(buildChatRequestJson(request.copy(jsonMode = false)).containsKey("response_format"))
    }
}
