package com.multisuperplayer.core.translate

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 引擎的编排行为。
 *
 * 这里全部用**假客户端**跑：重试、拆批、加预算这些分支在真实网络下几乎无法复现，
 * 而它们正是最需要断言的部分。虚拟时钟（[TestDispatchers]）让退避等待变成瞬间。
 */
class TranslationEngineTest {

    // ------------------------------------------------------------ 缓存

    @Test
    fun `缓存命中的行不发请求`() = runTest {
        val cache = TranslationCacheStore(File(tempDir(), "cache.jsonl"), TestDispatchers(testScheduler))
        fun engineWith(client: RecordingChatClient) =
            TranslationEngine(client, cache, TestDispatchers(testScheduler))

        val first = RecordingChatClient { request, _ ->
            okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
        }
        val events = engineWith(first).translate(doc(2), config = configOf(batchSize = 2)).toList()

        assertEquals(1, first.requests.size)
        val finished = events.filterIsInstance<TranslationEvent.Finished>().single()
        assertEquals(2, finished.done)
        assertEquals(0, finished.fromCache)

        // 再翻一次同样的内容：应该一条请求都不发。
        val second = RecordingChatClient { _, _ -> error("缓存命中时不该发请求") }
        val again = engineWith(second).translate(doc(2), config = configOf(batchSize = 2)).toList()

        assertTrue(second.requests.isEmpty())
        assertEquals(2, again.filterIsInstance<TranslationEvent.Planned>().single().cached)
        val againFinished = again.filterIsInstance<TranslationEvent.Finished>().single()
        assertEquals(2, againFinished.done)
        assertEquals(2, againFinished.fromCache)
        assertEquals(0, againFinished.requests)
        assertTrue(again.none { it is TranslationEvent.Batch }, "全部命中时不该有批次事件")
    }

    @Test
    fun `换了模型就不会命中旧缓存`() = runTest {
        val cache = TranslationCacheStore(File(tempDir(), "cache.jsonl"), TestDispatchers(testScheduler))
        fun client() = RecordingChatClient { request, _ ->
            okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
        }

        val a = client()
        TranslationEngine(a, cache, TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, model = "deepseek-flash")).toList()

        val b = client()
        val events = TranslationEngine(b, cache, TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, model = "qwen-plus")).toList()

        assertEquals(1, b.requests.size, "换模型必须重新翻")
        assertEquals(0, events.filterIsInstance<TranslationEvent.Planned>().single().cached)
    }

    // ------------------------------------------------------------ 中止

    @Test
    fun `配置不全时立刻中止且不发请求`() = runTest {
        val client = RecordingChatClient { _, _ -> error("配置不全时不该发请求") }
        val engine = TranslationEngine(client, cache(), TestDispatchers(testScheduler))

        val events = engine.translate(doc(2), config = configOf(baseUrl = "")).toList()

        assertEquals(1, events.size)
        val aborted = assertIs<TranslationEvent.Aborted>(events.single())
        assertEquals(MissingConfigItem.BASE_URL, assertIs<TranslationFailure.NotConfigured>(aborted.failure).missing)
        assertEquals(0, aborted.total)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `模型名没填时指出是哪一项`() = runTest {
        val client = RecordingChatClient { _, _ -> error("不该发请求") }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(model = "  ")).toList()

        assertEquals(
            MissingConfigItem.MODEL,
            assertIs<TranslationFailure.NotConfigured>(
                assertIs<TranslationEvent.Aborted>(events.single()).failure,
            ).missing,
        )
    }

    @Test
    fun `鉴权失败当场中止 而不是重试三次`() = runTest {
        val client = RecordingChatClient { _, _ ->
            ChatCompletionOutcome.Err(TranslationFailure.Unauthorized(401, "invalid api key"))
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, maxAttempts = 3)).toList()

        assertIs<TranslationFailure.Unauthorized>(assertIs<TranslationEvent.Aborted>(events.last()).failure)
        // 再跑一百批还是同一个错：那只会让用户看着进度条白等。
        assertEquals(1, client.requests.size)
        assertTrue(events.none { it is TranslationEvent.Finished })
    }

    // ------------------------------------------------------------ 重试 / 加预算 / 拆批

    @Test
    fun `5xx 退避重试 第二次成功`() = runTest {
        val client = RecordingChatClient { request, ordinal ->
            if (ordinal == 1) ChatCompletionOutcome.Err(TranslationFailure.ServerError(502, "bad gateway"))
            else okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, maxAttempts = 3)).toList()

        assertEquals(2, client.requests.size)
        assertEquals(2, assertIs<TranslationEvent.Finished>(events.last()).done)
        assertTrue(events.none { it is TranslationEvent.BatchFailed })
        // 走的是虚拟时钟：真等一下的话这条断言会失败，而不是"测试变慢"。
        assertTrue(testScheduler.currentTime >= 1_000L, "应当退避了 1 秒")
    }

    @Test
    fun `被截断时加大预算 并把新预算带进后面的重试`() = runTest {
        val budgets = mutableListOf<Int>()
        val client = RecordingChatClient { request, ordinal ->
            budgets += request.maxTokens
            when (ordinal) {
                1 -> ChatCompletionOutcome.Err(
                    TranslationFailure.Truncated(
                        finishReason = "length",
                        estimatedTokens = 3_000,
                        maxTokens = request.maxTokens,
                        detail = "截断",
                    ),
                )

                2 -> ChatCompletionOutcome.Err(TranslationFailure.ServerError(502, "bad gateway"))
                else -> okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
            }
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, maxTokens = 4_000, maxAttempts = 3))
            .toList()

        // 4000 →(截断⇒加大预算)→ 8000 →(5xx⇒退避重试)→ 8000
        // 第三步要是回到 4000，就等于"加大的预算被后面的重试分支改回去了"，
        // 于是每轮都撞同一堵墙，日志上看还像是同一个错误。
        assertEquals(listOf(4_000, 8_000, 8_000), budgets)
        assertEquals(2, assertIs<TranslationEvent.Finished>(events.last()).done)
    }

    @Test
    fun `条数对不上时拆成两半 而不是重试同一堵墙`() = runTest {
        val counts = mutableListOf<Int>()
        val client = RecordingChatClient { request, _ ->
            val lines = lineCountOf(request)
            counts += lines
            if (lines > 2) {
                ChatCompletionOutcome.Err(TranslationFailure.BadResponse("条数对不上"))
            } else {
                okResponse(translationsJson(*Array(lines) { "译$it" }))
            }
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(4), config = configOf(batchSize = 4, maxAttempts = 1)).toList()

        assertEquals(listOf(4, 2, 2), counts)
        val finished = assertIs<TranslationEvent.Finished>(events.last())
        assertEquals(4, finished.done)
        assertTrue(finished.failures.isEmpty())
    }

    @Test
    fun `一批失败不影响其他批`() = runTest {
        val client = RecordingChatClient { request, _ ->
            if (lineTextsOf(request).singleOrNull() == "t1") {
                ChatCompletionOutcome.Err(TranslationFailure.ServerError(503, "unavailable"))
            } else {
                okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
            }
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(3), config = configOf(batchSize = 1, maxAttempts = 1)).toList()

        assertEquals(listOf(1), events.filterIsInstance<TranslationEvent.BatchFailed>().single().cueIndices)
        assertEquals(2, events.filterIsInstance<TranslationEvent.Batch>().size)
        val finished = assertIs<TranslationEvent.Finished>(events.last())
        // 一集里有一句翻不出来，不该让另外 400 句白翻。
        assertEquals(2, finished.done)
        assertEquals(listOf(1), finished.failures.map { it.cueIndex })
    }

    // ------------------------------------------------------------ 结果合并

    @Test
    fun `模型给空串时不写入 而是记成这一行失败`() = runTest {
        val client = RecordingChatClient { _, _ -> okResponse(translationsJson("甲", "")) }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(2), config = configOf(batchSize = 2, maxAttempts = 1)).toList()

        // 空串在界面上就是一片空白，用户会以为字幕丢了。
        assertEquals(mapOf(0 to "甲"), events.filterIsInstance<TranslationEvent.Batch>().single().translations)
        assertEquals(
            listOf(1),
            events.filterIsInstance<TranslationEvent.BatchFailed>().single().cueIndices,
        )
        val finished = assertIs<TranslationEvent.Finished>(events.last())
        assertEquals(1, finished.done)
        assertEquals(2, finished.total)
    }

    @Test
    fun `术语表占位符在写回前会还原`() = runTest {
        // 假客户端"原样回显"发过去的文本，于是断言的就是占位符能不能被还原。
        val client = RecordingChatClient { request, _ ->
            okResponse(translationsJson(*lineTextsOf(request).toTypedArray()))
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(
                docOf("桐人"),
                config = configOf(batchSize = 1, glossary = mapOf("桐人" to "")),
            )
            .toList()

        val sent = lineTextsOf(client.requests.single()).single()
        assertTrue(sent.contains(PLACEHOLDER_OPEN), "应当发的是占位符：$sent")
        assertTrue(!sent.contains("桐人"), "不该把不翻译的词当普通文本发出去")
        assertEquals(
            mapOf(0 to "桐人"),
            events.filterIsInstance<TranslationEvent.Batch>().single().translations,
        )
    }

    @Test
    fun `注释行不计入进度分母`() = runTest {
        val client = RecordingChatClient { request, _ ->
            okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(4, commentIndices = setOf(1, 3)), config = configOf(batchSize = 8)).toList()

        // 分母算成 4 的话，进度条永远到不了 100%。
        assertEquals(2, events.filterIsInstance<TranslationEvent.Planned>().single().total)
        assertEquals(2, assertIs<TranslationEvent.Finished>(events.last()).done)
    }

    @Test
    fun `只翻指定的几行`() = runTest {
        val client = RecordingChatClient { request, _ ->
            okResponse(translationsJson(*Array(lineCountOf(request)) { "译$it" }))
        }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(4), pendingIndices = listOf(2), config = configOf(batchSize = 8)).toList()

        assertEquals(listOf("t2"), lineTextsOf(client.requests.single()))
        assertEquals(1, assertIs<TranslationEvent.Finished>(events.last()).done)
    }

    @Test
    fun `全是注释或空行时直接完成`() = runTest {
        val client = RecordingChatClient { _, _ -> error("不该发请求") }
        val events = TranslationEngine(client, cache(), TestDispatchers(testScheduler))
            .translate(doc(3, commentIndices = setOf(0, 1, 2)), config = configOf()).toList()

        assertTrue(client.requests.isEmpty())
        assertEquals(TranslationEvent.Finished(0, 0, emptyList(), 0, 0), events.single())
    }

    // ------------------------------------------------------------ 决策表本身

    @Test
    fun `失败后的处置表`() {
        with(TranslationLimits) {
            // 再跑下去一定是同一个错 ⇒ 中止
            assertEquals(FailureAction.Abort, act(TranslationFailure.Unauthorized(401, "x")))
            assertEquals(FailureAction.Abort, act(TranslationFailure.NotConfigured(MissingConfigItem.MODEL)))
            assertEquals(FailureAction.Abort, act(TranslationFailure.QuotaExceeded(429, "欠费")))

            // 预算问题 ⇒ 加大预算（降温度、拆批都修不好它）
            assertEquals(FailureAction.RaiseBudget, act(truncated()))
            assertEquals(
                FailureAction.RaiseBudget,
                act(TranslationFailure.EmptyCompletion(finishReason = "length", completionTokens = 2048, reasoningTokens = 2048, detail = "空")),
            )
            // 已经顶到上限时只能拆
            assertEquals(
                FailureAction.SplitBatch,
                act(truncated(), canRaiseBudget = false),
            )

            // 条数对不上 ⇒ 拆批（8 行对不上，4 行往往就对上了）
            assertEquals(FailureAction.SplitBatch, act(TranslationFailure.BadResponse("条数对不上")))
            // 只有一行时拆不了，只能原样重试
            assertIs<FailureAction.Retry>(act(TranslationFailure.BadResponse("条数对不上"), batchSize = 1))

            assertIs<FailureAction.Retry>(act(TranslationFailure.ServerError(502, "x")))
            assertIs<FailureAction.Retry>(act(TranslationFailure.RateLimited(429, 5L, "x")))
            assertIs<FailureAction.Retry>(act(TranslationFailure.Network("超时")))

            assertEquals(FailureAction.GiveUp, act(TranslationFailure.ServerError(502, "x"), attempt = 3))
            assertEquals(FailureAction.GiveUp, act(TranslationFailure.BadResponse("x"), canSplit = false, attempt = 3))
        }
    }

    @Test
    fun `退避曲线封顶`() {
        assertEquals(1_000L, backoffMillis(TranslationFailure.ServerError(500, "x"), 1))
        assertEquals(2_000L, backoffMillis(TranslationFailure.ServerError(500, "x"), 2))
        // 没有上限的指数退避能在一次失败里静默卡住十几分钟。
        assertEquals(
            TranslationLimits.MAX_BACKOFF_MS,
            backoffMillis(TranslationFailure.ServerError(500, "x"), 8),
        )
        // 限流时听服务商的 Retry-After，但最多信 30 秒。
        assertEquals(5_000L, backoffMillis(TranslationFailure.RateLimited(429, 5L, "x"), 1))
        assertEquals(
            TranslationLimits.MAX_RETRY_AFTER_WAIT_MS,
            backoffMillis(TranslationFailure.RateLimited(429, 999L, "x"), 1),
        )
        // 拿不到 Retry-After 就退回指数退避：第 2 次 = 2 秒（不是 1 秒）。
        assertEquals(2_000L, backoffMillis(TranslationFailure.RateLimited(429, null, "x"), 2))
    }

    /**
     * 缓存也必须挂在**这一个测试的**时钟上。
     *
     * 它内部是 `withContext(dispatchers.io)`；要是给了另一个 scheduler，
     * `runTest` 不会去推进它，用例就会一直挂在第一次 `lookup` 上。
     */
    private fun TestScope.cache() = TranslationCacheStore(
        File(tempDir(), "cache.jsonl"),
        TestDispatchers(testScheduler),
    )

    private fun truncated() = TranslationFailure.Truncated(
        finishReason = "length",
        estimatedTokens = 9_000,
        maxTokens = 2_048,
        detail = "截断",
    )

    private fun act(
        failure: TranslationFailure,
        batchSize: Int = 8,
        canSplit: Boolean = true,
        canRaiseBudget: Boolean = true,
        attempt: Int = 1,
        maxAttempts: Int = 3,
    ): FailureAction = decideFailureAction(
        failure = failure,
        batchSize = batchSize,
        canSplit = canSplit,
        canRaiseBudget = canRaiseBudget,
        attempt = attempt,
        maxAttempts = maxAttempts,
    )
}
