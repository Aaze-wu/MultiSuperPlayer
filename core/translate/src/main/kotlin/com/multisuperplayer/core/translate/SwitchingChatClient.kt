package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.llm.LlmFailureKind
import com.multisuperplayer.core.llm.LlmGenerationOutcome
import com.multisuperplayer.core.llm.LlmGenerationRequest
import com.multisuperplayer.core.llm.LlmTextGenerator

private const val TAG = "LocalChat"

/**
 * 约束解码用的 JSON Schema。
 *
 * ## 它和系统提示词里那段格式说明是**同一件事的两种说法**
 *
 * `buildSystemPrompt()` 里已经写了「形状固定为 `{"translations": [...]}`」——
 * 那段话在远端是**唯一**能起作用的手段（`response_format` 只保证「是合法 JSON」，
 * 字段名根本没发给模型，实测模型会自创键名）。而在设备上，LiteRT-LM 的约束解码
 * 是**真的**把这份 schema 编译成解码约束，所以本地这一路是「提示词说一遍 +
 * 解码器强制一遍」。两处必须一致，改一处就得改另一处（提示词那边还有
 * `TRANSLATION_PROMPT_VERSION` 要跟着 +1，因为缓存键跟着它走）。
 *
 * ## 为什么**要**钉住条数（这是真机换来的结论）
 *
 * 一开始这里故意不加 `minItems` / `maxItems`，理由是「条数由解析器检查，它的报错
 * 比原生侧的一句 grammar 错好懂」。0.6B 模型在真机上把这个理由推翻了：它**稳定地**
 * 把一整批译文并成**一个**字符串（数组长度 1，句与句之间用 `\n\n` 粘起来），于是
 * 解析器每次都报「找到了译文数组，但条数是 1，期望 8」——本地翻译一条也出不来。
 * 这不是「模型偶尔不听话」，是它对这种形状的默认理解，靠重试不会变好。
 *
 * 所以现在按调用方给的条数把数组长度钉死：约束解码在**解码期**就排除「只写一个
 * 元素」这条路，模型只能在 N 个元素里写 N 段。`expectedItems == null`（调用方
 * 不知道条数）时退回纯形状约束，不会比原来更差。
 *
 * ## 每个关键字都要真机验
 *
 * schema 里每多一个关键字，就多一个「约束解码器可能不认识」的东西，而失败的代价
 * 是整条本地路径都不能用（远端至少还能靠提示词兜住）。所以这两个关键字是**上机
 * 实测过**才留下的，不是照着 JSON Schema 文档加的。
 */
internal fun localTranslationSchema(expectedItems: Int?): String {
    val count = expectedItems?.takeIf { it > 0 }
    val bounds = if (count == null) "" else ", \"minItems\": $count, \"maxItems\": $count"
    return """
        {
          "type": "object",
          "properties": {
            "translations": {
              "type": "array",
              "items": { "type": "string" }$bounds
            }
          },
          "required": ["translations"]
        }
    """.trimIndent()
}

/**
 * 按请求决定**出网还是在本机跑**。
 *
 * ## 为什么判断点在「请求」而不是「设置」
 *
 * 引擎（[TranslationEngine]）是纯编排逻辑：它拿到一份 [TranslationConfig] 快照，
 * 然后把一批批请求发出去。让它在跑到一半时去读「用户现在选的是哪家服务商」，
 * 就会出现半途换服务商、缓存里混进两套结果的经典问题。所以本地/远端这件事
 * 必须是**请求自带的属性**（`onDeviceModelId`），在这一层做一次分发，
 * 引擎自己完全不需要知道有两条路。
 *
 * ## [listModels] 只有远端这一条路
 *
 * 设备上不存在「列模型」这个动作：本机有哪几条模型由模型清单（`:core:llm`）
 * 说话，设置页直接渲染那份清单。所以这里的签名上没有本地之分，
 * 而调用方（设置页的「拉取模型列表」）对本地服务商也不会提供这个按钮。
 */
internal class SwitchingChatClient(
    private val remote: ChatCompletionClient,
    private val generator: LlmTextGenerator,
) : ChatCompletionClient {

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionOutcome {
        val modelId = request.onDeviceModelId ?: return remote.complete(request)
        return localComplete(request, modelId)
    }

    override suspend fun listModels(baseUrl: String, apiKey: String?): ModelListOutcome =
        remote.listModels(baseUrl, apiKey)

    /**
     * 把一次「聊天请求」翻译成一次本机生成。
     *
     * 这里刻意**不复用**远端的 `jsonMode` 与 `extraBody`：那两个是给厂商的补丁
     * （`response_format`、关思考的参数），本机既不需要它们，也不知道该拿它们怎么办。
     * 思考已经由推理层固定关掉（`ThinkingConfig(enableThinking = false)`）。
     */
    private suspend fun localComplete(
        request: ChatCompletionRequest,
        modelId: String,
    ): ChatCompletionOutcome {
        val outcome = generator.generate(
            LlmGenerationRequest(
                modelId = modelId,
                systemPrompt = request.systemPrompt,
                userPrompt = request.userPrompt,
                maxOutputTokens = request.maxTokens,
                temperature = request.temperature,
                jsonSchema = localTranslationSchema(request.expectedItems),
            ),
        )

        return when (outcome) {
            is LlmGenerationOutcome.Ok -> {
                val result = outcome.result
                MspLog.i(TAG) {
                    "model=$modelId out=${result.outputTokens} truncated=${result.truncated} " +
                        "chars=${result.text.length}"
                }
                ChatCompletionOutcome.Ok(
                    ChatCompletionResponse(
                        content = result.text,
                        // 把引擎报的截断翻译成 `finish_reason`：解析层是靠这个（和括号是否
                        // 闭合）区分「被剪断」和「形状不对」的，而这两种的处置正好相反
                        // （加大预算 vs 换策略）。本机这边我们是拿**精确 token 数**判的，
                        // 比远端那套「估算长度 ≥ 上限的 90%」准。
                        finishReason = if (result.truncated) FINISH_LENGTH else FINISH_STOP,
                        completionTokens = result.outputTokens,
                        // 本机固定关掉了思考，没有 reasoning token 这回事。
                        // 传 null 而不是 0：0 会被当成「确实没有」，而 null 是「不适用」。
                        reasoningTokens = null,
                    ),
                )
            }

            is LlmGenerationOutcome.Failure -> {
                MspLog.w(TAG) { "model=$modelId failed kind=${outcome.kind} detail=${outcome.detail}" }
                ChatCompletionOutcome.Err(outcome.kind.toFailure(modelId, outcome.detail))
            }
        }
    }

    private companion object {
        const val FINISH_LENGTH = "length"
        const val FINISH_STOP = "stop"
    }
}

/**
 * 推理层的失败分档 → 翻译层的失败分档。
 *
 * 用 `when` 穷举枚举而不是 if 链：推理层将来加一档（比如「设备不支持这个模型」），
 * 这里会**编译报错**，逼着补一条映射；而漏掉一档的后果是它被归到某个
 * 「看起来像」的分支上——文案、重试策略、是否中止整个任务会同时错。
 */
private fun LlmFailureKind.toFailure(modelId: String, detail: String): TranslationFailure =
    when (this) {
        LlmFailureKind.MODEL_MISSING -> TranslationFailure.LocalModelMissing(modelId)
        LlmFailureKind.ENGINE_UNAVAILABLE -> TranslationFailure.LocalEngineUnavailable(detail)
        LlmFailureKind.GENERATION_FAILED -> TranslationFailure.LocalGenerationFailed(detail)
    }
