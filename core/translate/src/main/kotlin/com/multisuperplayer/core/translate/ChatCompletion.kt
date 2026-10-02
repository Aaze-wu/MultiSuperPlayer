package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一次 chat/completions 请求所需的全部信息（已解析好 URL，URL 拼接见 [chatCompletionsUrl]）。 */
internal data class ChatCompletionRequest(
    val url: String,
    val apiKey: String?,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val maxTokens: Int,
    val temperature: Double,
    /** 是否带上 `response_format: {"type":"json_object"}`。见 [ChatCompletionRequest.jsonMode]。 */
    val jsonMode: Boolean = true,
    /** 厂商特定参数（关思考等），最后并入请求体，可以覆盖上面的默认值。 */
    val extraBody: JsonObject? = null,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 120_000
    }
}

/** `finish_reason` 的分类。各家措辞不完全一致，所以集中在这里映射一次。 */
internal enum class ChatFinish { STOP, LENGTH, OTHER, ABSENT }

internal fun chatFinish(reason: String?): ChatFinish = when (reason?.trim()?.lowercase()) {
    null, "" -> ChatFinish.ABSENT
    "stop", "end_turn", "eos", "stop_sequence" -> ChatFinish.STOP
    "length", "max_tokens", "max_output_tokens" -> ChatFinish.LENGTH
    else -> ChatFinish.OTHER
}

internal data class ChatCompletionResponse(
    val content: String,
    val finishReason: String?,
    val completionTokens: Int?,
    /**
     * 推理 token。**这是诊断「HTTP 200 但 content 为空」的关键证据**：
     * `reasoningTokens == completionTokens` 就说明预算全被思考吃掉了。
     */
    val reasoningTokens: Int?,
)

internal sealed interface ChatCompletionOutcome {
    data class Ok(val response: ChatCompletionResponse) : ChatCompletionOutcome
    data class Err(val failure: TranslationFailure) : ChatCompletionOutcome
}

internal sealed interface ModelListOutcome {
    data class Ok(val models: List<String>) : ModelListOutcome
    data class Err(val failure: TranslationFailure) : ModelListOutcome
}

/**
 * 唯一的 HTTP 出网口。
 *
 * 抽成接口只为一件事：让「重试/拆批/加预算」这些策略能在单测里被验证。
 * 真实现是 [HttpChatClient]（`HttpURLConnection`，不引任何 HTTP 库）。
 */
internal interface ChatCompletionClient {
    /** 调用方负责切到 IO 线程（[TranslationEngine] 用 `flowOn` 保证）。 */
    suspend fun complete(request: ChatCompletionRequest): ChatCompletionOutcome

    /** 拉模型列表。拿到的是厂商自己的模型名，比自己猜名字靠谱。 */
    suspend fun listModels(baseUrl: String, apiKey: String?): ModelListOutcome
}

/**
 * 组装请求体。**纯函数**，所以可以断言「发出去的请求里确实带上了关思考参数」——
 * 这类「参数没发出去」的 bug 从响应是看不出来的（响应只会表现成空 content）。
 */
internal fun buildChatRequestJson(request: ChatCompletionRequest): JsonObject {
    val body = linkedMapOf<String, JsonElement>(
        "model" to JsonPrimitive(request.model),
        "messages" to JsonArray(
            listOf(
                chatMessage("system", request.systemPrompt),
                chatMessage("user", request.userPrompt),
            ),
        ),
        "temperature" to JsonPrimitive(request.temperature),
        "max_tokens" to JsonPrimitive(request.maxTokens),
        // 显式关掉流式：流式下响应体是 SSE，解析方式完全不同。
        "stream" to JsonPrimitive(false),
    )

    if (request.jsonMode) {
        body["response_format"] = JsonObject(mapOf("type" to JsonPrimitive("json_object")))
    }

    // 厂商特定参数放最后 ⇒ 允许用户覆盖上面的默认值（比如自己填一个更大的 max_tokens）。
    request.extraBody?.forEach { (key, value) -> body[key] = value }
    // 但模型名与消息不能被覆盖：那一定是用户填错了地方。
    body["model"] = JsonPrimitive(request.model)

    return JsonObject(body)
}

private fun chatMessage(role: String, content: String): JsonObject = JsonObject(
    mapOf(
        "role" to JsonPrimitive(role),
        "content" to JsonPrimitive(content),
    ),
)
