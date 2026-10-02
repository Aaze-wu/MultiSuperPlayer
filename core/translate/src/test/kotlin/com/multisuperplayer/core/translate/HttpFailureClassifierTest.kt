package com.multisuperplayer.core.translate

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP 错误分类。
 *
 * 401 / 429(额度) / 429(太快) / 5xx 的**处置方式完全不同**：
 * 换 key、去充值、等一会儿、重试。合并成一档就等于永远只会「重试」，
 * 而重试一个欠费的账号会一直失败到天亮。
 */
class HttpFailureClassifierTest {

    @Test
    fun `鉴权失败`() {
        val failure = classifyHttpFailure(401, null, """{"error":{"message":"invalid_api_key"}}""")
        val unauthorized = failure as TranslationFailure.Unauthorized
        assertEquals(401, unauthorized.status)
        assertTrue(unauthorized.detail.contains("invalid_api_key"))
        assertTrue(failure.abortsJob)
        assertTrue(!failure.retryableAsIs)

        assertTrue(classifyHttpFailure(403, null, "") is TranslationFailure.Unauthorized)
    }

    @Test
    fun `429 里的额度用尽与打得太快要分开`() {
        val quota = classifyHttpFailure(429, null, """{"error":{"message":"insufficient balance"}}""")
        assertTrue(quota is TranslationFailure.QuotaExceeded)
        assertTrue(quota.abortsJob)

        val chinese = classifyHttpFailure(429, null, """{"error":{"message":"账户余额不足"}}""")
        assertTrue(chinese is TranslationFailure.QuotaExceeded)

        val rate = classifyHttpFailure(429, "17", """{"error":{"message":"too many requests"}}""")
        val limited = rate as TranslationFailure.RateLimited
        assertEquals(17L, limited.retryAfterSeconds)
        assertTrue(rate.retryableAsIs)
        assertTrue(!rate.abortsJob)
    }

    @Test
    fun `服务端错误可重试`() {
        for (status in listOf(500, 502, 503, 599)) {
            val failure = classifyHttpFailure(status, null, "oops")
            assertTrue(failure is TranslationFailure.ServerError, "HTTP $status 应归为服务端错误")
            assertTrue(failure.retryableAsIs)
        }
    }

    @Test
    fun `参数类错误不重试`() {
        for (status in listOf(400, 404, 422)) {
            val failure = classifyHttpFailure(status, null, """{"error":{"message":"model not found"}}""")
            assertTrue(failure is TranslationFailure.Rejected, "HTTP $status 应归为拒绝")
            assertTrue(!failure.retryableAsIs)
            // 参数错误每一批都会撞上同一个墙：重试没用，**继续跑也没用**。
            // 「不重试」和「不中止」是两件事，这里两件都要成立。
            assertTrue(failure.abortsJob, "HTTP $status 属于「再跑一百批还是同一个错」")
        }
    }

    @Test
    fun `错误说明绝不返回空`() {
        assertEquals("x", extractProviderMessage("""{"error":{"message":"x"}}"""))
        assertEquals("y", extractProviderMessage("""{"error":"y"}"""))
        assertEquals("z", extractProviderMessage("""{"message":"z"}"""))
        assertEquals("w", extractProviderMessage("""{"detail":"w"}"""))
        // 非 JSON 也要有话说：一句「失败原因：」后面什么都没有，比塞满 JSON 还难用。
        assertEquals("Bad Gateway", extractProviderMessage("Bad Gateway"))
    }

    @Test
    fun `响应体是空的就返回空串而不是造一句中文`() {
        // 这一层不知道界面语言，造占位句就等於在英文界面里招供出中文。
        // 界面拿到空串就知道「厂商什么都没说」，不显示原文块。
        assertEquals("", extractProviderMessage("   "))
        assertEquals("", extractProviderMessage(""))
    }

    @Test
    fun `过长的错误说明被截断`() {
        val long = "a".repeat(1000)
        val text = extractProviderMessage("""{"error":{"message":"$long"}}""", maxChars = 100)
        assertEquals(101, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `Retry-After 只认秒数且被夹住`() {
        assertNull(parseRetryAfterSeconds(null))
        assertNull(parseRetryAfterSeconds("   "))
        // HTTP-date 形式各家时区对不上，算出来可能是负数或几小时，宁可不认。
        assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseRetryAfterSeconds("12.5"))
        assertEquals(0L, parseRetryAfterSeconds("-5"))
        assertEquals(30L, parseRetryAfterSeconds("30"))
        assertEquals(120L, parseRetryAfterSeconds("9999"))
    }

    @Test
    fun `网络异常带上对应的处置档位`() {
        // 三种情况分别对应三个「重试一百次也不好」的场景，所以档位必须不一样：
        // 超时是等，DNS 是地址填错，明文被拦是要 adb reverse。
        // 断言档位而不是文案：文案在 TranslationFailureTextTest 里读资源文件验证。
        val timeout = assertIs<TranslationFailure.Network>(
            classifyNetworkException(SocketTimeoutException("Read timed out")),
        )
        assertEquals("SocketTimeoutException: Read timed out", timeout.detail)
        assertEquals(NetworkNote.TIMEOUT, timeout.note)
        assertTrue(timeout.retryableAsIs)

        val dns = assertIs<TranslationFailure.Network>(
            classifyNetworkException(UnknownHostException("api.deepseek.com")),
        )
        assertEquals(NetworkNote.UNRESOLVED_HOST, dns.note)

        // 明文被拦看起来像普通网络错误，但重试一百次也不好。
        val cleartext = assertIs<TranslationFailure.Network>(
            classifyNetworkException(IOException("CLEARTEXT communication to 192.168.1.5 not permitted")),
        )
        assertEquals(NetworkNote.CLEARTEXT_BLOCKED, cleartext.note)

        // 普通网络错误只带系统原文，不要瞎猜原因。
        val plain = assertIs<TranslationFailure.Network>(
            classifyNetworkException(IOException("Connection reset")),
        )
        assertEquals("IOException: Connection reset", plain.detail)
        assertEquals(NetworkNote.NONE, plain.note)
    }

    @Test
    fun `每种失败都有一句日志文案`() {
        // logLine() 是排查时唯一能看到的东西，空串会让日志出现「失败：」这种哑行。
        val all = listOf(
            TranslationFailure.NotConfigured(MissingConfigItem.BASE_URL),
            TranslationFailure.Unauthorized(401, "x"),
            TranslationFailure.QuotaExceeded(429, "x"),
            TranslationFailure.RateLimited(429, 3L, "x"),
            TranslationFailure.Rejected(400, "x"),
            TranslationFailure.ServerError(500, "x"),
            TranslationFailure.Network("x", NetworkNote.TIMEOUT),
            TranslationFailure.BadResponse("x"),
            TranslationFailure.EmptyCompletion("length", 10, 10, "x"),
            TranslationFailure.Truncated("length", 100, 50, "x"),
        )
        all.forEach { assertTrue(it.logLine().isNotBlank(), "${it::class.simpleName} 的 logLine 为空") }
    }
}
