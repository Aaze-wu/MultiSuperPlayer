package com.multisuperplayer.core.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用 LiteRT-LM 在设备上跑 `.litertlm` 模型。
 *
 * ## 只用 CPU
 *
 * `Backend.CPU()` 是唯一在**所有**设备上都能工作的后端：GPU/NPU 需要 OpenCL 或厂商
 * 驱动，而 manifest 里要多声明 `<uses-native-library>`（声明了却没有对应硬件时，
 * 加载原生库就会失败——一个「在别人手机上闪退」的经典坑）。用户明确选了 CPU 后端。
 *
 * ## 引擎的寿命＝进程的寿命
 *
 * `Engine.initialize()` 官方说法是最长 10 秒（要 mmap 345 MB 权重、初始化 KV cache）。
 * 每次翻译都新建一个，用户第二次点翻译时会以为功能卡住了。所以引擎**懒加载后常驻**，
 * 只有两种情况会关掉它：
 *
 * 1. 换模型；
 * 2. 上层显式调用 [release]——**删除模型文件之前必须调**。原因不是内存，
 *    而是 LiteRT 会把模型文件 mmap 进来：文件被 unlink 之后 inode 仍然活着，
 *    直到引擎关闭才真正释放。用户删掉 345 MB、界面显示「已删除」，可存储空间
 *    一两分钟都不回来——这种「删了但没空出来」的账，用户只会记在应用头上。
 *
 * ## 一次只有一个生成
 *
 * [lock] 把「初始化 + 一次生成」整段串起来。两个原生生成并发跑不是「慢一点」，
 * 而是崩在 native 里、拿不到任何 Kotlin 栈。代价是第二个请求要排队——这正是
 * 我们想要的：翻译是一批一批来的，排队比崩掉好。
 *
 * ## 关闭思考
 *
 * Qwen3 默认会先输出一段思考过程再给答案。对翻译这种「照着格式把话说一遍」的
 * 任务，思考是纯浪费：它会把 `maxOutputTokens` 吃掉一大半（截断 → 失败），
 * 还拖慢几倍。所以显式 `ThinkingConfig(enableThinking = false)`。
 */
class LiteRtLmTextGenerator(
    private val locator: LlmModelLocator,
    /** 引擎的工作目录（编译缓存等）。由调用方给，见 DI 里的 `cacheDir/llm`。 */
    private val cacheDir: File,
    private val dispatchers: DispatcherProvider,
) : LlmTextGenerator {

    private val lock = Mutex()

    /** 当前常驻的引擎；null 表示还没建或已经释放。**只能在 [lock] 里读写**。 */
    private var engine: Engine? = null

    /** 常驻引擎加载的是哪条模型。换模型时要先关掉旧的。 */
    private var engineModelId: String? = null

    override suspend fun generate(request: LlmGenerationRequest): LlmGenerationOutcome =
        withContext(dispatchers.io) {
            val model = LlmModelCatalog.find(request.modelId)
            // 清单里没有这个 id：多半是升版本后旧设置里留了一条已经下架的模型，
            // 按「模型没下载」报，用户的下一步（去设置页挑一条并下载）正好是对的。
            ?: return@withContext missing(request.modelId)

            if (!locator.isReady(model)) return@withContext missing(model.id)

            lock.withLock {
                val engine = try {
                    engineFor(model)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 起不来就别留着这个半成品引擎：留着的话后面每一次都失败，
                    // 而错误信息会变成「生成失败」，指向错误的方向。
                    resetEngine()
                    MspLog.e(TAG, e) { "cannot initialize engine for ${model.id}" }
                    // 必须真的返回：写成「try 的值是 Failure」会让整个 try 表达式的
                    // 类型退化成 Any，编译能在下一行报一个看不懂的「需要 Engine 却是 Any」。
                    return@withLock LlmGenerationOutcome.Failure(
                        kind = LlmFailureKind.ENGINE_UNAVAILABLE,
                        detail = "${e::class.java.simpleName}: ${e.message.orEmpty()}".trim(),
                    )
                }

                runGeneration(engine, request)
            }
        }

    /**
     * 释放常驻引擎。删除模型文件之前调用（见类注释里 mmap 那段）。
     * 幂等，可以在任何时候调用；下一次 [generate] 会重新初始化。
     */
    override suspend fun release() = lock.withLock { resetEngine() }

    /**
     * 真正跑一次。**只能在持有 [lock] 时调用**。
     *
     * `@OptIn(ExperimentalApi::class)` 只是为了 [Conversation.getBenchmarkInfo]。注意它的
     * 风险**不是**「被改掉时编译报错」——实测在 0.17.1 上它**每次调用都抛异常**
     * （native 侧要求 `EngineSettings` 里设 `BenchmarkParams`，而 Kotlin 侧的
     * `EngineConfig` 根本没暴露这个开关）。所以这次调用是**保险式**的：拿得到就用，
     * 拿不到就当没有，绝不因此把一次已经生成完的结果判成失败。
     */
    @OptIn(ExperimentalApi::class)
    private suspend fun runGeneration(
        engine: Engine,
        request: LlmGenerationRequest,
    ): LlmGenerationOutcome {
        val conversation = try {
            engine.createConversation(conversationConfig(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return generationFailure(e)
        }

        try {
            val message = conversation.sendMessage(
                text = request.userPrompt,
                // 上限按请求给，不写进 ConversationConfig：它和「这一批要输出多少行」
                // 绑在一起，而加大预算重试时**只改这一个数**，别的地方不该跟着动。
                maxOutputToken = request.maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                responseFormat = request.jsonSchema?.let { ResponseFormat.json(it) },
            )

            val text = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }

            val benchmark = try {
                conversation.getBenchmarkInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 实测 0.17.1 上**必然**走到这里：`Failed to get benchmark info:
                // INTERNAL: Benchmark is not enabled.`。最坏的地方是它发生在 200 之后——
                // 文本已经生成完了，却因为读不到统计数字而整批报「生成失败」，
                // 日志里看起来像模型不行。所以降级成「读不到就没有」，**不**当失败。
                MspLog.d(TAG) { "benchmark info unavailable: ${e.message.orEmpty()}" }
                null
            }
            val outputTokens = benchmark?.lastDecodeTokenCount ?: 0
            val promptTokens = benchmark?.lastPrefillTokenCount ?: 0
            // 「撞到上限」只能用引擎给的精确数字判断，不数字符；拿不到数字时
            // **不能**猜成「截断了」——那会把每一次成功都变成假失败。截断还有
            // 解析层那条独立证据（`TranslationResponseParser`：JSON 括号没闭合
            // **且** 估算长度接近 maxTokens），它在远端那条路上本来就存在。
            val truncated = benchmark != null && outputTokens >= request.maxOutputTokens
            MspLog.i(TAG) {
                "generate ok: prompt=$promptTokens output=$outputTokens " +
                    "truncated=$truncated chars=${text.length}"
            }
            return LlmGenerationOutcome.Ok(
                LlmGenerationResult(
                    text = text,
                    outputTokens = outputTokens.takeIf { it > 0 },
                    promptTokens = promptTokens.takeIf { it > 0 },
                    truncated = truncated,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 一次生成的失败可能来自 native 侧已经坏掉的内部状态。丢掉引擎重建，
            // 让「原样重试」真的有机会成功——留着一个坏引擎重试，每一次都会失败，
            // 而界面上看起来是「这条模型不行」。
            resetEngine()
            return generationFailure(e)
        } finally {
            // 关不掉也不能把已经拿到的结果变成失败：conversation 是个句柄，
            // 泄漏一个句柄的代价远小于丢掉一次已经算完的 30 秒。
            runCatching { conversation.close() }
        }
    }

    private fun generationFailure(e: Throwable): LlmGenerationOutcome {
        MspLog.w(TAG, e) { "generation failed" }
        return LlmGenerationOutcome.Failure(
            kind = LlmFailureKind.GENERATION_FAILED,
            detail = "${e::class.java.simpleName}: ${e.message.orEmpty()}".trim(),
        )
    }

    private fun missing(modelId: String): LlmGenerationOutcome {
        MspLog.w(TAG) { "model $modelId is not ready" }
        return LlmGenerationOutcome.Failure(
            kind = LlmFailureKind.MODEL_MISSING,
            detail = "model $modelId is not installed",
        )
    }

    /** 取常驻引擎，必要时新建。**只能在持有 [lock] 时调用**。 */
    private fun engineFor(model: LlmModelInfo): Engine {
        engine?.let { current ->
            if (engineModelId == model.id && current.isInitialized()) return current
            resetEngine()
        }

        val modelPath = locator.fileOf(model).absolutePath
        MspLog.i(TAG) { "initializing engine for ${model.id} ($modelPath)" }
        // 原生侧的日志默认很吵（加载模型时会刷屏），压到 ERROR。
        // 这是**静态**设置，与引擎实例无关，所以每次初始化都设一次没有坏处。
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir.apply { mkdirs() }.absolutePath,
            ),
        )
        created.initialize()
        engine = created
        engineModelId = model.id
        MspLog.i(TAG) { "engine ready for ${model.id}" }
        return created
    }

    private fun resetEngine() {
        engine?.let { current ->
            runCatching { current.close() }
                .onFailure { MspLog.w(TAG, it) { "cannot close engine" } }
        }
        engine = null
        engineModelId = null
    }

    private fun conversationConfig(request: LlmGenerationRequest) = ConversationConfig(
        systemInstruction = Contents.of(request.systemPrompt),
        samplerConfig = SamplerConfig(
            topK = TOP_K,
            topP = TOP_P,
            // 温度来自请求（翻译配置里的 0.3），**不用**模型自带的推荐值（Qwen3 是 0.6）。
            // 翻译要的是「同一句话每次都译成同一句」；采样面放宽会让重试变成碰运气。
            temperature = request.temperature,
        ),
        // 约束解码要两个开关一起给：这里开启「允许格式约束」，每次 sendMessage 时
        // 传的 ResponseFormat 才是**具体那个** schema。少了这里，schema 会被丢掉，
        // 模型就开始自创键名——远端接口上我们实测过这件事（`response_format`
        // 只保证语法是 JSON，不保证字段名）。这两种失败在日志里长得一模一样。
        enableResponseFormat = request.jsonSchema != null,
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    private companion object {
        const val TAG = "LiteRtLm"

        /**
         * Qwen3 官方推荐的采样参数（模型卡里的 `generation_config`）：`top_k = 20`、`top_p = 0.95`。
         *
         * 温度不在这里——它必须跟着翻译配置走（见 [conversationConfig]）。
         * 这两个值对 0.6B 的翻译任务影响很小，写死是为了少一处「可调但没人会调」的设置。
         */
        const val TOP_K = 20
        const val TOP_P = 0.95
    }
}
