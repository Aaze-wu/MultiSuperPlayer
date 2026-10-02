package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 单测里的调度器一律指向测试调度器。
 *
 * 这一点对 [TranslationEngine] 不只是"更快"：引擎的退避重试里有 `delay()`，
 * 如果跑在真 `Dispatchers.IO` 上，一个「重试两次」的用例就要真的等 3 秒，
 * 而且断言会跟真实时钟赛跑。走虚拟时间之后，`delay(5000)` 是瞬间完成的，
 * 于是"退避曲线对不对"变成可以断言的事实。
 */
internal class TestDispatchers(scheduler: TestCoroutineScheduler) : DispatcherProvider {
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    override val main get() = dispatcher
    override val default get() = dispatcher
    override val io get() = dispatcher
}

/** 造一份字幕。文本用 `t0`/`t1`… 方便直接对照下标。 */
internal fun docOf(
    vararg texts: String,
    commentIndices: Set<Int> = emptySet(),
): SubtitleDocument = SubtitleDocument(
    track = SubtitleTrack(id = "track-1"),
    cues = texts.mapIndexed { index, text ->
        SubtitleCue(
            index = index,
            startMs = index * 1_000L,
            endMs = index * 1_000L + 900L,
            text = text,
            isComment = index in commentIndices,
        )
    },
)

/** 每行文本都是 `t<下标>`。 */
internal fun doc(n: Int, commentIndices: Set<Int> = emptySet()): SubtitleDocument =
    docOf(*Array(n) { "t$it" }, commentIndices = commentIndices)

/**
 * 标准成功响应体。
 *
 * **必须用 JSON 序列化器拼，不能手写字符串。** 这里原来是
 * `joinToString(prefix = """{"translations":[""" …)`，那串原始字符串末尾的引号数量
 * 正好踩到 Kotlin 原始字符串的**终止规则**：多出来的引号被当成终止符，
 * 拼出来的是 `{"translations:[…`（冒号后少一个引号）= **永远解析失败的 JSON**。
 *
 * 而「夹具永远返回坏 JSON」的表现是：引擎一路 BadResponse，重试 7 次、结果 0 条。
 * 那看起来像引擎的重试/拆批逻辑坏了，于是十几个用例的排查方向全被引到了假的一侧。
 * 夹具比真模型更不听话、更爱出错是可以的（那才叫逼真），但**不能比真模型更假**。
 * 所以这里改走序列化器，顺便让空数组就是空数组——
 * 旧写法在 0 条时会吐出 `{"translations":[""]}`，凭空多出一条空译文。
 */
internal fun translationsJson(vararg texts: String): String =
    JsonObject(
        mapOf("translations" to JsonArray(texts.map { JsonPrimitive(it) })),
    ).toString()

internal fun okResponse(
    content: String,
    finishReason: String? = "stop",
    completionTokens: Int? = 10,
    reasoningTokens: Int? = null,
) = ChatCompletionOutcome.Ok(
    ChatCompletionResponse(content, finishReason, completionTokens, reasoningTokens),
)

/**
 * 可编排的假客户端。
 *
 * [respond] 拿到的是「第几次请求」，而不是「这一批有多少行」——按次数编排是**故意的**，
 * 这样测试写的是"第一次失败、第二次成功"这种**真实存在的时序**。
 */
internal class RecordingChatClient(
    private val respond: (ChatCompletionRequest, Int) -> ChatCompletionOutcome,
) : ChatCompletionClient {

    val requests = mutableListOf<ChatCompletionRequest>()

    var modelList: List<String>? = null

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionOutcome {
        requests += request
        return respond(request, requests.size)
    }

    override suspend fun listModels(baseUrl: String, apiKey: String?): ModelListOutcome {
        val models = modelList ?: return ModelListOutcome.Err(TranslationFailure.Network("没有配置"))
        return ModelListOutcome.Ok(models)
    }
}

/**
 * 读出请求里这一批有几行。
 *
 * 注意 `userPrompt` 不是纯 JSON（前面还有一句「lines 共 N 行」），
 * 所以必须先扫出顶层的 JSON 值——直接 `parseObject` 会解析失败，
 * 而失败时若返回 0 会让「按批次大小分流响应」的假客户端永远走错分支。
 */
internal fun lineCountOf(request: ChatCompletionRequest): Int {
    val element = scanTopLevelJsonValues(request.userPrompt).firstOrNull() ?: return -1
    return element.objectOrNull()?.get("lines")?.arrayOrNull()?.size ?: -1
}

/** 读出请求里这一批的实际行文本（同样要先扫出顶层 JSON 值）。 */
internal fun lineTextsOf(request: ChatCompletionRequest): List<String> {
    val element = scanTopLevelJsonValues(request.userPrompt).firstOrNull() ?: return emptyList()
    return element.objectOrNull()?.get("lines")?.arrayOrNull()?.strings().orEmpty()
}

internal fun configOf(
    baseUrl: String = "https://api.deepseek.com",
    apiKey: String? = "sk-test",
    model: String = "deepseek-flash",
    target: TranslationTarget = TranslationTarget.SIMPLIFIED_CHINESE,
    glossary: Glossary = emptyMap(),
    batchSize: Int = 8,
    maxTokens: Int = TranslationLimits.DEFAULT_MAX_TOKENS,
    maxAttempts: Int = TranslationLimits.DEFAULT_MAX_ATTEMPTS,
) = TranslationConfig(
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    target = target,
    glossary = glossary,
    batchSize = batchSize,
    maxTokens = maxTokens,
    maxAttempts = maxAttempts,
)

internal fun tempDir(): File = File(
    System.getProperty("java.io.tmpdir"),
    "msp-translate-test-${System.nanoTime()}-${(1..99999).random()}",
).apply { mkdirs() }
