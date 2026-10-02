package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.SubtitleDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject

private const val TAG = "TranslationEngine"

/** 引擎里的硬性上限。列在一起是为了让「为什么不能再大了」一眼可见。 */
object TranslationLimits {
    const val DEFAULT_MAX_TOKENS = 2048
    const val MIN_MAX_TOKENS = 256

    /**
     * `max_tokens` 的上限。
     * 一个 8 行的批次怎么都够，上限只是防「用户手填了一个 100000」把一次请求
     * 变成几分钟的等待（有些厂商按 max_tokens 预扣费）。
     */
    const val MAX_TOKENS_CEILING = 8192

    const val DEFAULT_TEMPERATURE = 0.3

    /** 一批最多试几次。超过就是死循环，用户只会看到进度条不动。 */
    const val DEFAULT_MAX_ATTEMPTS = 3
    const val MAX_ATTEMPTS_CEILING = 5

    const val MAX_BACKOFF_MS = 8_000L

    /** 服务商给的 Retry-After 最多信这么多秒；再多就自杀式等待了。 */
    const val MAX_RETRY_AFTER_WAIT_MS = 30_000L

    /** 拆批只拆一层。再往下拆，请求数会指数增长，成本远高于失败本身。 */
    const val MAX_SPLIT_DEPTH = 1
}

/**
 * 一次翻译任务的全部参数。
 *
 * 刻意做成「一个不可变对象」而不是「引擎的构造参数」：用户随时可能在设置里
 * 改模型/语言，而正在跑的任务必须用**开始那一刻**的快照，否则一批用旧模型、
 * 一批用新模型，缓存里就混进了两套结果而没人知道。
 */
data class TranslationConfig(
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val target: TranslationTarget,
    val glossary: Glossary = emptyMap(),
    val batchSize: Int = TranslationBatching.DEFAULT_BATCH_SIZE,
    val maxBatchChars: Int = TranslationBatching.DEFAULT_MAX_CHARS,
    val contextLines: Int = TranslationBatching.DEFAULT_CONTEXT_LINES,
    val maxTokens: Int = TranslationLimits.DEFAULT_MAX_TOKENS,
    val temperature: Double = TranslationLimits.DEFAULT_TEMPERATURE,
    val maxAttempts: Int = TranslationLimits.DEFAULT_MAX_ATTEMPTS,
    /** 带上 `response_format: {"type":"json_object"}`。个别中转不认这个字段会 400，那时关掉即可。 */
    val jsonMode: Boolean = true,
    /** 厂商特定参数（关思考等）的 JSON 文本。 */
    val extraBody: String = "",
)

/** 进度事件。UI 边收边合并，所以中途取消也不会白干。 */
sealed interface TranslationEvent {

    /** 计划完成。[cached] 条直接来自缓存（这些不会产生任何请求）。 */
    data class Planned(val total: Int, val batches: Int, val cached: Int) : TranslationEvent

    /** 某批拿到结果。 */
    data class Batch(
        val translations: Map<Int, String>,
        val done: Int,
        val total: Int,
        val fromCache: Int,
        val requests: Int,
    ) : TranslationEvent

    /** 某批最终失败（已经重试过）。任务继续，不中断剩下的批次。 */
    data class BatchFailed(val cueIndices: List<Int>, val failure: TranslationFailure) : TranslationEvent

    data class Finished(
        val done: Int,
        val total: Int,
        val failures: List<CueFailure>,
        val fromCache: Int,
        val requests: Int,
    ) : TranslationEvent

    /**
     * 整个任务提前结束。
     *
     * 只在「再跑下去也一定是同一个错」时发出：[TranslationFailure.abortsJob]。
     * 否则用户会看着进度条一路爬到 100% 然后得到一句失败——那比当场停下更糟。
     */
    data class Aborted(val failure: TranslationFailure, val done: Int, val total: Int) : TranslationEvent
}

data class CueFailure(val cueIndex: Int, val failure: TranslationFailure)

/**
 * 引擎的唯一门面：只暴露「跑一轮」。
 *
 * 有它才有一个可替换点——feature 层要在单测里注入「按脚本发事件」的假引擎，
 * 否则「进度合并、切歌时丢掉旧译文、人工修正压过模型译文」这些逻辑
 * 只能靠在模拟器上点一遍来验证，而它们恰好是最容易错的地方。
 * 真正的引擎只多一层接口，没有额外开销。
 */
fun interface TranslationRunner {
    /**
     * @param pendingIndices 要翻的行；null = 全部可翻的行。
     */
    fun translate(
        document: SubtitleDocument,
        pendingIndices: List<Int>?,
        config: TranslationConfig,
    ): Flow<TranslationEvent>

    /**
     * 翻全部可翻的行。
     *
     * 写成重载而不是 `pendingIndices: List<Int>? = null` 的默认值：
     * `fun interface` 的抽象方法**不允许**带默认值（SAM 转换与默认值语义冲突），
     * 接口与实现两处都会被编译器拦下。重载能同时保住「`TranslationRunner { }` 的
     * 简洁假实现」和「调用方少写一个 null」。
     */
    fun translate(
        document: SubtitleDocument,
        config: TranslationConfig,
    ): Flow<TranslationEvent> = translate(document, null, config)
}

/**
 * 翻译引擎。
 *
 * ## 它只负责「循环」，不负责「取参数」和「显示」
 *
 * 参数（模型/语言/术语表）由调用方查好塞进 [TranslationConfig]；
 * 结果通过 [TranslationEvent] 流出去，UI 边收边合并。这样一来引擎是
 * **纯编排逻辑**，可以在单测里用一个假客户端跑完「重试/拆批/加预算」全部分支。
 *
 * ## 一个批次的失败不会毁掉整个任务
 *
 * 除了 [TranslationFailure.abortsJob] 那几档，其余失败都只记在
 * [TranslationEvent.Finished.failures] 里。一集里有一句翻不出来，
 * 不该让另外 400 句白翻。
 */
class TranslationEngine internal constructor(
    private val client: ChatCompletionClient,
    private val cache: TranslationCacheStore,
    private val dispatchers: DispatcherProvider,
) : TranslationRunner {

    override fun translate(
        document: SubtitleDocument,
        pendingIndices: List<Int>?,
        config: TranslationConfig,
    ): Flow<TranslationEvent> = flow {
        run(this, document, pendingIndices, config)
    }.flowOn(dispatchers.io)

    // ------------------------------------------------------------------ 主流程

    private suspend fun run(
        emit: FlowCollector<TranslationEvent>,
        document: SubtitleDocument,
        pendingIndices: List<Int>?,
        config: TranslationConfig,
    ) {
        validateConfig(config)?.let {
            emit.emit(TranslationEvent.Aborted(it, 0, 0))
            return
        }

        val pending = pendingIndices ?: document.translatableIndices()
        val batches = planTranslationBatches(
            cues = document.cues,
            pendingIndices = pending,
            batchSize = config.batchSize,
            maxChars = config.maxBatchChars,
            contextLines = config.contextLines,
        )
        val total = batches.sumOf { it.cueIndices.size }
        if (total == 0) {
            emit.emit(TranslationEvent.Finished(0, 0, emptyList(), 0, 0))
            return
        }

        val protector = GlossaryProtector(config.glossary)
        val systemPrompt = buildSystemPrompt(config.target, config.glossary)
        val extraBody = parseExtraBody(config.extraBody)
        val fingerprint = config.glossary.fingerprint()
        // 同一句原文在一集里可能出现多次，哈希只算一次。
        val keyMemo = HashMap<String, String>()
        fun keyFor(source: String): String =
            keyMemo.getOrPut(source) {
                TranslationCacheCodec.key(config.model, config.target, fingerprint, source)
            }

        val cached = cache.lookup(batches.flatMap { it.texts }.distinct().map(::keyFor))
        val translations = LinkedHashMap<Int, String>()
        for (batch in batches) {
            batch.cueIndices.forEachIndexed { index, cueIndex ->
                cached[keyFor(batch.texts[index])]?.let { translations[cueIndex] = it }
            }
        }

        val fromCache = translations.size
        var requests = 0
        emit.emit(TranslationEvent.Planned(total, batches.size, fromCache))

        val failures = mutableListOf<CueFailure>()

        for (batch in batches) {
            val lines = batch.cueIndices
                .withIndex()
                .filter { (_, cueIndex) -> !translations.containsKey(cueIndex) }
                .map { PendingLine(it.value, batch.texts[it.index]) }
            if (lines.isEmpty()) continue

            val outcome = translateLines(
                lines = lines,
                contextBefore = batch.contextBefore,
                systemPrompt = systemPrompt,
                config = config,
                protector = protector,
                extraBody = extraBody,
                splitDepth = 0,
                budget = config.maxTokens,
            )
            requests += outcome.requests

            if (outcome.translations.isNotEmpty()) {
                translations.putAll(outcome.translations)
                cache.store(
                    outcome.translations.map { (cueIndex, text) ->
                        val source = document.cues.getOrNull(cueIndex)?.text?.trim().orEmpty()
                        CacheEntry(keyFor(source), source, text, System.currentTimeMillis())
                    },
                )
                emit.emit(
                    TranslationEvent.Batch(
                        translations = outcome.translations,
                        done = translations.size,
                        total = total,
                        fromCache = fromCache,
                        requests = requests,
                    ),
                )
            }

            outcome.failures.forEach { failure ->
                MspLog.w(TAG) { "批次失败 cue=${failure.cueIndex} ${failure.failure.logLine()}" }
                emit.emit(TranslationEvent.BatchFailed(listOf(failure.cueIndex), failure.failure))
            }
            failures += outcome.failures

            outcome.abort?.let {
                emit.emit(TranslationEvent.Aborted(it, translations.size, total))
                return
            }
        }

        emit.emit(
            TranslationEvent.Finished(
                done = translations.size,
                total = total,
                failures = failures,
                fromCache = fromCache,
                requests = requests,
            ),
        )
    }

    // ------------------------------------------------------------------ 单批

    private suspend fun translateLines(
        lines: List<PendingLine>,
        contextBefore: List<String>,
        systemPrompt: String,
        config: TranslationConfig,
        protector: GlossaryProtector,
        extraBody: JsonObject?,
        splitDepth: Int,
        budget: Int,
    ): BatchOutcome {
        var maxTokens = budget.coerceIn(
            TranslationLimits.MIN_MAX_TOKENS,
            TranslationLimits.MAX_TOKENS_CEILING,
        )
        val maxAttempts = config.maxAttempts.coerceIn(1, TranslationLimits.MAX_ATTEMPTS_CEILING)
        var attempt = 0
        var requests = 0
        var lastFailure: TranslationFailure? = null

        while (attempt < maxAttempts) {
            attempt++

            val protectedTexts = lines.map { protector.protect(it.text) }
            val request = ChatCompletionRequest(
                url = chatCompletionsUrl(config.baseUrl),
                apiKey = config.apiKey,
                model = config.model,
                systemPrompt = systemPrompt,
                userPrompt = buildUserPrompt(
                    batch = TranslationBatch(
                        cueIndices = lines.map { it.cueIndex },
                        texts = lines.map { it.text },
                        contextBefore = contextBefore,
                    ),
                    protectedTexts = protectedTexts,
                ),
                maxTokens = maxTokens,
                temperature = config.temperature,
                jsonMode = config.jsonMode,
                extraBody = extraBody,
            )
            requests++

            val parsed = when (val outcome = client.complete(request)) {
                is ChatCompletionOutcome.Err -> ParsedTranslations.Err(outcome.failure)

                is ChatCompletionOutcome.Ok -> emptyCompletionOf(outcome.response)
                    ?: parseTranslationPayload(
                        rawContent = outcome.response.content,
                        expectedCount = lines.size,
                        finishReason = outcome.response.finishReason,
                        maxTokens = maxTokens,
                    )
            }

            when (parsed) {
                is ParsedTranslations.Ok -> {
                    val result = LinkedHashMap<Int, String>()
                    lines.forEachIndexed { index, line ->
                        val text = protector.restore(parsed.texts[index]).trim()
                        // 译文为空 = 这一行没翻出来，宁可不写，也不要写空串
                        // （空串在界面上就是一片空白，用户以为字幕丢了）。
                        if (text.isNotEmpty()) result[line.cueIndex] = text
                    }
                    return BatchOutcome(
                        translations = result,
                        failures = lines.filter { it.cueIndex !in result }
                            .map { CueFailure(it.cueIndex, TranslationFailure.BadResponse("模型返回的该行为空")) },
                        requests = requests,
                        abort = null,
                    )
                }

                is ParsedTranslations.Err -> {
                    lastFailure = parsed.failure
                    MspLog.w(TAG) {
                        "批次失败（第 $attempt 次，batch=${lines.size}，max_tokens=$maxTokens）：" +
                            parsed.failure.logLine()
                    }

                    when (
                        val action = decideFailureAction(
                            failure = parsed.failure,
                            batchSize = lines.size,
                            canSplit = splitDepth < TranslationLimits.MAX_SPLIT_DEPTH,
                            canRaiseBudget = maxTokens < TranslationLimits.MAX_TOKENS_CEILING,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                        )
                    ) {
                        FailureAction.Abort ->
                            return BatchOutcome(emptyMap(), emptyList(), requests, parsed.failure)

                        FailureAction.GiveUp ->
                            return BatchOutcome(
                                translations = emptyMap(),
                                failures = lines.map { CueFailure(it.cueIndex, parsed.failure) },
                                requests = requests,
                                abort = null,
                            )

                        FailureAction.RaiseBudget -> {
                            // 加大预算**不等待**：等待解决不了"预算不够"。
                            // 而且这个更大的值会跟着走到后面的重试分支上去——
                            // 否则「先加大、再降温度重试」的第二步会把预算又改回去。
                            maxTokens = (maxTokens * 2)
                                .coerceAtMost(TranslationLimits.MAX_TOKENS_CEILING)
                            continue
                        }

                        is FailureAction.Retry -> delay(action.delayMs)

                        FailureAction.SplitBatch -> {
                            val half = lines.size / 2
                            val left = translateLines(
                                lines.subList(0, half), contextBefore, systemPrompt,
                                config, protector, extraBody, splitDepth + 1, maxTokens,
                            )
                            if (left.abort != null) return left
                            val right = translateLines(
                                lines.subList(half, lines.size), contextBefore, systemPrompt,
                                config, protector, extraBody, splitDepth + 1, maxTokens,
                            )
                            return BatchOutcome(
                                translations = left.translations + right.translations,
                                failures = left.failures + right.failures,
                                requests = requests + left.requests + right.requests,
                                abort = right.abort,
                            )
                        }
                    }
                }
            }
        }

        val failure = lastFailure ?: TranslationFailure.BadResponse("未知失败")
        return BatchOutcome(
            translations = emptyMap(),
            failures = lines.map { CueFailure(it.cueIndex, failure) },
            requests = requests,
            abort = null,
        )
    }

    companion object {
        /**
         * 配置不全时给出**具体是哪一项**，而不是一句「请检查设置」。
         *
         * 公开的原因：设置页要在按钮上提前显示「还差什么」，如果那里自己写一套判断，
         * 就会出现「设置页说可以翻译、点下去引擎说不满足条件」这种互相打脸的状态。
         *
         * 返回的是枚举而不是句子：文案要跟着界面语言走，而这里拿不到 `Resources`
         * （也拿不到用户选的语言）。渲染见 `describeMissingItem()`。
         */
        fun validateConfig(config: TranslationConfig): TranslationFailure? =
            missingConfigItems(
                baseUrl = config.baseUrl,
                model = config.model,
                batchSize = config.batchSize,
            ).firstOrNull()?.let(TranslationFailure::NotConfigured)

        /**
         * 「还差哪几项」的**唯一**判定处：设置页的清单、引擎的报错、播放页的提示
         * 都从这里取，免得出现「设置页说可以翻译、点下去引擎说不满足条件」这种互相打脸。
         *
         * 与 [validateConfig] 的差别只是**问的人不一样**：
         * - [apiKeyRequired] / [apiKeyStored]：引擎拿到的是一个已经组装好的
         *   [TranslationConfig]，密钥有没有由上层的 [ApiKeyStore] 说话；设置页在
         *   这里才知道这家服务商要不要密钥。
         * - [batchSize] 传 `null` 表示调用方根本没有这个概念（批大小目前还不是
         *   用户设置项），那就别替它报一个它改不了的缺项。
         */
        fun missingConfigItems(
            baseUrl: String,
            model: String,
            apiKeyRequired: Boolean = false,
            apiKeyStored: Boolean = false,
            batchSize: Int? = null,
        ): List<MissingConfigItem> = buildList {
            if (baseUrl.isBlank()) {
                add(MissingConfigItem.BASE_URL)
            } else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                add(MissingConfigItem.BASE_URL_SCHEME)
            }
            if (model.isBlank()) add(MissingConfigItem.MODEL)
            if (apiKeyRequired && !apiKeyStored) add(MissingConfigItem.API_KEY)
            if (batchSize != null && batchSize < TranslationBatching.MIN_BATCH_SIZE) {
                add(MissingConfigItem.BATCH_SIZE)
            }
        }
    }
}

internal data class PendingLine(val cueIndex: Int, val text: String)

/**
 * 「响应成功、但模型一个字都没输出」的判定。返回 null 表示内容非空、可以继续解析。
 *
 * [parseChatCompletionResponse] 里已经有同一段判断，这里**故意重复一遍**：
 * 那一层只有 [HttpChatClient] 会走，而空内容这件事是引擎自己的决策依据
 * （它要接 `RaiseBudget` 而不是 `BadResponse` 那条分支）。如果只有 HTTP 层判，
 * 那么换一个客户端实现——测试里的假客户端、将来可能加的流式客户端——
 * 空输出就会掉进「结构不对」的分支，于是重试同样的请求、同样的预算，
 * 每次都拿到同样的空，而给用户看的解释是「模型返回的格式不对」，方向完全相反。
 */
internal fun emptyCompletionOf(response: ChatCompletionResponse): ParsedTranslations.Err? {
    if (response.content.isNotBlank()) return null
    return ParsedTranslations.Err(
        TranslationFailure.EmptyCompletion(
            finishReason = response.finishReason,
            completionTokens = response.completionTokens,
            reasoningTokens = response.reasoningTokens,
            detail = "响应里 content 为空；finish_reason=${response.finishReason ?: "未给出"} " +
                "completion_tokens=${response.completionTokens} " +
                "reasoning_tokens=${response.reasoningTokens}",
        ),
    )
}

internal data class BatchOutcome(
    val translations: Map<Int, String>,
    val failures: List<CueFailure>,
    val requests: Int,
    val abort: TranslationFailure?,
)

/** 失败之后的动作。抽成纯函数是为了能直接给策略写断言，而不用搭一场假的 HTTP 会话。 */
internal sealed interface FailureAction {
    data class Retry(val delayMs: Long) : FailureAction
    data object RaiseBudget : FailureAction
    data object SplitBatch : FailureAction
    data object GiveUp : FailureAction
    data object Abort : FailureAction
}

/**
 * 「这一批挂了，接下来干什么」的决策表。
 *
 * ## 顺序不能乱，每一条都对应一类真实故障
 *
 * | 失败 | 处置 | 为什么不是别的 |
 * |---|---|---|
 * | 鉴权/余额/配置 | 中止 | 再跑 200 批还是同一个错 |
 * | 被截断 / 空内容 | 加大预算 | 这是**预算**问题；降温度或拆批都修不好它 |
 * | 条数对不上 | 拆批 | 8 行对不上，4 行往往就对上了；加大预算不会改善 |
 * | 限流/5xx/网络 | 退避重试 | 等一会儿真的会好 |
 * | 其余 | 原样再试一次 | 模型有随机性，同一提示词第二次常常就对了 |
 *
 * ⚠️ 「被截断」和「条数对不上」**必须走不同分支**。混在一起的话，
 * 用户永远在重试同一堵墙，而日志上看起来两种失败一模一样。
 */
internal fun decideFailureAction(
    failure: TranslationFailure,
    batchSize: Int,
    canSplit: Boolean,
    canRaiseBudget: Boolean,
    attempt: Int,
    maxAttempts: Int,
): FailureAction {
    if (failure.abortsJob) return FailureAction.Abort

    val budgetProblem = failure is TranslationFailure.Truncated ||
        failure is TranslationFailure.EmptyCompletion

    if (budgetProblem && canRaiseBudget) return FailureAction.RaiseBudget
    if (failure is TranslationFailure.BadResponse && canSplit && batchSize > 1) {
        return FailureAction.SplitBatch
    }
    if (budgetProblem && canSplit && batchSize > 1) return FailureAction.SplitBatch

    if (failure.retryableAsIs && attempt < maxAttempts) {
        return FailureAction.Retry(backoffMillis(failure, attempt))
    }
    if (attempt < maxAttempts) return FailureAction.Retry(backoffMillis(failure, attempt))

    return FailureAction.GiveUp
}

/**
 * 退避时长。限流时听服务商的 `Retry-After`，其余按 1s / 2s / 4s… 递增并封顶。
 *
 * 封顶很重要：没有上限的指数退避在一次失败里能静默卡住十几分钟，
 * 而界面上只有一条不动的进度条。
 */
internal fun backoffMillis(failure: TranslationFailure, attempt: Int): Long {
    if (failure is TranslationFailure.RateLimited) {
        failure.retryAfterSeconds?.let { seconds ->
            return (seconds * 1_000).coerceIn(0L, TranslationLimits.MAX_RETRY_AFTER_WAIT_MS)
        }
    }
    val exponent = (attempt - 1).coerceIn(0, 5)
    return (1_000L shl exponent).coerceAtMost(TranslationLimits.MAX_BACKOFF_MS)
}
