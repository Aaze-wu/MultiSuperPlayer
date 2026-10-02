package com.multisuperplayer.core.translate

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 设置页「测试连接」的行为。
 *
 * 这里要锁住的核心是**「通了」的标准**：必须是拿回了一句真实译文。
 * 一个只看 HTTP 状态码的测试会对「模型名写错」和「推理模式吃光预算」
 * 两种情况都报成功，而这两种恰好是真实使用中翻不出来的主因。
 */
class TranslationProbeTest {

    private fun engine(client: RecordingChatClient, scheduler: TestCoroutineScheduler): TranslationEngine {
        val cache = TranslationCacheStore(File(tempDir(), "cache.jsonl"), TestDispatchers(scheduler))
        return TranslationEngine(client, cache, TestDispatchers(scheduler))
    }

    private fun okClient(): RecordingChatClient = RecordingChatClient { request, _ ->
        okResponse(translationsJson(*Array(lineCountOf(request)) { "快捷的棕色狐狸跳过了懒狗。" }))
    }

    @Test
    fun `测试成功时带回模型真正翻译出来的那一句`() = runTest {
        val client = okClient()
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val result = probe.test(configOf())

        assertTrue(result.ok)
        val ok = assertIs<ConnectivityResult.Ok>(result)
        assertEquals(TranslationProbe.SAMPLE_TEXT, ok.sample)
        assertEquals("快捷的棕色狐狸跳过了懒狗。", ok.translated)
    }

    @Test
    fun `测试连接只请求一次，不做退避重试`() = runTest {
        val client = RecordingChatClient { _, _ ->
            ChatCompletionOutcome.Err(TranslationFailure.RateLimited(429, null, "忙"))
        }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val result = probe.test(configOf())

        assertIs<ConnectivityResult.Failed>(result)
        // 探针的价值是「立刻告诉我哪里不对」。跟三轮退避的话用户要等半分钟。
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `限流时把重新尝试的等待时间透出来`() = runTest {
        val client = RecordingChatClient { _, _ ->
            ChatCompletionOutcome.Err(TranslationFailure.RateLimited(429, 30L, "忙"))
        }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val failure = assertIs<ConnectivityResult.Failed>(probe.test(configOf())).failure

        val limited = assertIs<TranslationFailure.RateLimited>(failure)
        assertEquals(30L, limited.retryAfterSeconds)
    }

    @Test
    fun `空输出报的是预算不够，不是解析失败`() = runTest {
        // 推理模式把 max_tokens 吃光：HTTP 200，content 为空。
        val client = RecordingChatClient { _, _ ->
            okResponse("", finishReason = "length", completionTokens = 2048, reasoningTokens = 2048)
        }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val failure = assertIs<ConnectivityResult.Failed>(probe.test(configOf())).failure

        val empty = assertIs<TranslationFailure.EmptyCompletion>(failure)
        assertEquals("length", empty.finishReason)
        assertEquals(2048, empty.reasoningTokens)
    }

    @Test
    fun `配置没填完时连请求都不发`() = runTest {
        val client = RecordingChatClient { _, _ -> error("配置不全时不该发请求") }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        // 注意不是 apiKey：本地 Ollama 就不需要密钥，它不该被当成必填项。
        val failure = assertIs<ConnectivityResult.Failed>(probe.test(configOf(baseUrl = "  "))).failure

        assertIs<TranslationFailure.NotConfigured>(failure)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `模型名是空的也算没配置好`() = runTest {
        val client = RecordingChatClient { _, _ -> error("配置不全时不该发请求") }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val failure = assertIs<ConnectivityResult.Failed>(probe.test(configOf(model = "  "))).failure

        assertIs<TranslationFailure.NotConfigured>(failure)
    }

    @Test
    fun `拉模型列表直接把厂商的名字带回来`() = runTest {
        val client = okClient().apply { modelList = listOf("qwen-plus", "qwen-turbo") }
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val result = probe.listModels("https://dashscope.aliyuncs.com/compatible-mode/v1", "sk")

        assertEquals(listOf("qwen-plus", "qwen-turbo"), assertIs<ModelListResult.Ok>(result).models)
    }

    @Test
    fun `拉模型列表失败时把原因原样带回来`() = runTest {
        // 很多自建/代理服务没实现 /models，404 不代表地址和密钥错了。
        val client = okClient()
        val probe = TranslationProbe(engine(client, testScheduler), client)

        val result = probe.listModels("http://10.0.2.2:11434/v1", null)

        val failure = assertIs<ModelListResult.Failed>(result).failure
        assertIs<TranslationFailure.Network>(failure)
    }
}
