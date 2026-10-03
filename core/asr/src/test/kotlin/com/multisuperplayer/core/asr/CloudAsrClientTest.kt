package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 请求失败分类的测试。
 *
 * 这两件事（HTTP 状态码 → 哪一档失败、错误体 → 一句原因）都不碰网络，
 * 却是整条云端链路上**最容易搞错**的地方：错误体的形状由服务商自己决定
 * （JSON 对象 / 裸字符串 / HTML / 纯文本四种都实测存在），而状态码到
 * 「用户该做什么」的映射是这套设计的核心主张。
 */
class CloudAsrClientTest {

    // -------------------------------------------------------------- 状态码分类

    @Test
    fun `401 归到认证失败并带上服务商 id`() {
        val error = failureFor(401, null, "", request())
        assertTrue("实际是 $error", error is AsrException.CloudAuth)
        assertEquals("groq", (error as AsrException.CloudAuth).serviceId)
    }

    @Test
    fun `403 归到账户策略而不是认证`() {
        // 403 时密钥可能是对的。归到 401 会让用户去反复重填一个正确的密钥。
        val error = failureFor(
            status = 403,
            retryAfter = null,
            body = """{"error":{"message":"organization policy"}}""",
            request = request(),
        )
        assertTrue("实际是 $error", error is AsrException.CloudBlocked)
        assertEquals("organization policy", (error as AsrException.CloudBlocked).detail)
    }

    @Test
    fun `404 归到地址写错`() {
        val error = failureFor(404, null, "<html><title>404 Not Found</title></html>", request())
        assertTrue("实际是 $error", error is AsrException.CloudEndpoint)
        assertEquals(404, (error as AsrException.CloudEndpoint).code)
    }

    @Test
    fun `400 归到请求形态不对`() {
        val error = failureFor(400, null, """{"error":{"message":"verbose_json not supported"}}""", request())
        assertTrue("实际是 $error", error is AsrException.CloudRequest)
    }

    @Test
    fun `没有映射的 4xx 也归到请求形态不对`() {
        // 405/415/422 这些用户能做的动作和 400 一样（换模型或换预设），
        // 单独给每一档写文案只是让翻译表变长。
        listOf(405, 415, 422, 418).forEach { status ->
            val error = failureFor(status, null, "", request())
            assertTrue("$status 应该是 CloudRequest，实际是 $error", error is AsrException.CloudRequest)
        }
    }

    @Test
    fun `413 报的是这一个文件的实际大小`() {
        val audio = tempAudio(2_048L)
        try {
            val error = failureFor(413, null, "payload too large", request(audio = audio))
            assertTrue("实际是 $error", error is AsrException.CloudTooLarge)
            assertEquals(2_048L, (error as AsrException.CloudTooLarge).bytes)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `429 把 Retry-After 附在原因后面`() {
        val error = failureFor(429, "30", """{"error":{"message":"rate limited"}}""", request())
        assertTrue("实际是 $error", error is AsrException.CloudQuota)
        val detail = (error as AsrException.CloudQuota).detail
        assertTrue("要保住服务商自己写的原因：$detail", detail.contains("rate limited"))
        assertTrue("也要保住等多久：$detail", detail.contains("Retry-After: 30"))
    }

    @Test
    fun `429 没有 Retry-After 时只报原因`() {
        val error = failureFor(429, null, "slow down", request())
        assertEquals("slow down", (error as AsrException.CloudQuota).detail)
    }

    @Test
    fun `5xx 归到服务商侧故障并带状态码`() {
        listOf(500, 502, 503, 504).forEach { status ->
            val error = failureFor(status, null, "maintenance", request())
            assertTrue("$status 应该是 CloudServer，实际是 $error", error is AsrException.CloudServer)
            assertEquals(status, (error as AsrException.CloudServer).code)
        }
    }

    @Test
    fun `非 4xx 非 5xx 的怪异状态码也不会当成「你发错了」`() {
        // `instanceFollowRedirects` 已经跟过重定向了，还走到这里说明服务端在做
        // 我们理解不了的事。告诉用户「你的请求有问题」会让他去改一个没问题的东西。
        val error = failureFor(302, null, "", request())
        assertTrue("实际是 $error", error is AsrException.CloudServer)
    }

    // -------------------------------------------------------------- 错误体解析

    @Test
    fun `OpenAI 风格的嵌套 message`() {
        val body = """{"error":{"message":"Incorrect API key provided: sk-xxx","type":"invalid_request_error"}}"""
        assertEquals("Incorrect API key provided: sk-xxx", providerMessage(body))
    }

    @Test
    fun `error 字段直接是字符串`() {
        assertEquals("Invalid API key", providerMessage("""{"error":"Invalid API key"}"""))
    }

    @Test
    fun `裸 JSON 字符串`() {
        // 硅基流动的错误体在 OpenAPI 里就是 `type: string`，实际发出来是一个
        // JSON 字符串——不是对象，取 `error.message` 会得到 null。
        assertEquals("Invalid token", providerMessage("\"Invalid token\""))
    }

    @Test
    fun `FastAPI 风格的 detail 字段`() {
        assertEquals("Not Found", providerMessage("""{"detail":"Not Found"}"""))
    }

    @Test
    fun `HTML 错误页只取标题那一句`() {
        val body = "<html><head><title>404 Not Found</title></head><body><h1>404</h1></body></html>"
        val message = providerMessage(body)
        assertTrue("要拿到标题：$message", message.contains("404 Not Found"))
        assertTrue("不能把一整页标签丢给用户：$message", !message.contains("<h1>"))
    }

    @Test
    fun `纯文本错误体原样保留`() {
        // OpenAI 的 403 只返回 text/plain。
        val body = "Access to a personal API organization is blocked by the organization policy."
        assertEquals(body, providerMessage(body))
    }

    @Test
    fun `读不出原因的 JSON 返回空串`() {
        // 空串会被 `detailText` 换成「服务商没有说明原因」；把 `{"code":4001}` 平铺进
        // 文案只会让提示变难看，而日志里已经有完整原文。
        assertEquals("", providerMessage("""{"code":4001}"""))
        assertEquals("", providerMessage("{}"))
        assertEquals("", providerMessage("""[{"message":"one"},{"message":"two"}]"""))
        assertEquals("", providerMessage(""))
        assertEquals("", providerMessage("   "))
    }

    @Test
    fun `多行原因被压成一行`() {
        val body = "第 1 行\n第 2 行\r\n第 3 行"
        assertEquals("第 1 行 第 2 行 第 3 行", providerMessage(body))
    }

    @Test
    fun `过长的原因被截断`() {
        val message = providerMessage("y".repeat(1000))
        assertEquals(301, message.length)
        assertTrue(message.endsWith("…"))
    }

    // ------------------------------------------------------------ 地址先筛一遍

    @Test
    fun `地址没填完时在开连接之前就失败`() {
        // 这一条只在「自定义预设 + 地址没填完」时走到，而那是用户第一次进设置页的
        // 默认状态——100% 会遇到。空地址拿去开连接会抛 MalformedURLException
        // （IOException 的子类），最后报成「连不上识别服务」：用户去换网络，
        // 而实际该做的是把地址填完。
        listOf("", "   ", "example.com/v1", "ftp://example.com", "/v1").forEach { raw ->
            val error = runCatching { checkEndpointUsable(raw) }.exceptionOrNull()
            assertTrue("「$raw」应当在开连接前就被拦下，实际是 $error", error is AsrException.CloudAddress)
        }
    }

    @Test
    fun `协议头大小写与首尾空白都不影响判定`() {
        // URL("HTTPS://x") 是好的（协议名不区分大小写），而人是会打大写的；
        // 手打地址时前后带空格更是常态。
        listOf("https://api.openai.com/v1", "  https://api.openai.com/v1  ", "HTTPS://x", "http://10.0.2.2:11434/v1").forEach { raw ->
            checkEndpointUsable(raw)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun request(audio: File = File("nonexistent-audio.wav")): CloudAsrRequest = CloudAsrRequest(
        url = "https://example.com/v1/audio/transcriptions",
        apiKey = "k",
        model = "whisper-1",
        serviceId = "groq",
        wantsTimestamps = true,
        audio = audio,
    )

    private fun tempAudio(bytes: Long): File {
        val file = File.createTempFile("msp-cloud-asr", ".wav")
        file.writeBytes(ByteArray(bytes.toInt()) { 0xAA.toByte() })
        return file
    }
}
