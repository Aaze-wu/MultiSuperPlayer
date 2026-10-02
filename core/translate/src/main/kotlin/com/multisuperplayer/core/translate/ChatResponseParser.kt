package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 解析 chat/completions 响应。纯函数。
 *
 * ## 这里为什么要单独把「空内容」和「形状不对」分开
 *
 * 因为它们的**处置方式正好相反**，混成一档等于永远修不好：
 * - `content` 为空 ⇒ 预算不够（推理模型的思考 token 把 `max_tokens` 吃光了，
 *   典型证据是 `reasoning_tokens == completion_tokens` + `finish_reason: length`）。
 *   处置是**加大预算**或关掉思考。
 * - 返回了内容但结构不对 ⇒ 提示词/模型能力问题。处置是**降低温度/拆小批次**，
 *   加大预算只会更贵、更慢，结果一模一样。
 *
 * 顺带一提：这种空内容响应是 **HTTP 200**，看起来完全成功。所以必须在交给
 * 后面的 JSON 解析器之前拦住它，否则报出来的错会变成「解析失败」，
 * 把所有人引到错误的方向上去查。
 */
internal fun parseChatCompletionResponse(body: String, maxTokens: Int): ChatCompletionOutcome {
    val element = TranslationJson.parseElementOrNull(body)
        ?: return ChatCompletionOutcome.Err(
            TranslationFailure.BadResponse("响应不是合法 JSON：" + body.trim().take(300)),
        )
    val root = element as? JsonObject
        ?: return ChatCompletionOutcome.Err(
            TranslationFailure.BadResponse("响应顶层不是对象：" + element.preview()),
        )

    // 有些网关把错误包在 HTTP 200 里返回，只检查状态码会漏掉。
    if (root["error"] != null) {
        return ChatCompletionOutcome.Err(
            TranslationFailure.BadResponse("HTTP 200，但响应体里带 error：" + extractProviderMessage(body)),
        )
    }

    val choices = root["choices"] as? JsonArray
    val choice = choices?.firstOrNull() as? JsonObject
    val finishReason = choice?.get("finish_reason").stringOrNull()

    val content = contentOf(choice)
    val usage = root["usage"] as? JsonObject
    val completionTokens = usage?.get("completion_tokens").intOrNull()
    val reasoningTokens = (usage?.get("completion_tokens_details") as? JsonObject)
        ?.get("reasoning_tokens").intOrNull()

    if (content.isBlank()) {
        return ChatCompletionOutcome.Err(
            TranslationFailure.EmptyCompletion(
                finishReason = finishReason,
                completionTokens = completionTokens,
                reasoningTokens = reasoningTokens,
                detail = "响应里 content 为空；finish_reason=$finishReason " +
                    "completion_tokens=$completionTokens reasoning_tokens=$reasoningTokens " +
                    "max_tokens=$maxTokens 响应前 200 字：" + body.trim().take(200),
            ),
        )
    }

    return ChatCompletionOutcome.Ok(
        ChatCompletionResponse(
            content = content,
            finishReason = finishReason,
            completionTokens = completionTokens,
            reasoningTokens = reasoningTokens,
        ),
    )
}

/**
 * 从 `choices[0]` 里取正文。
 *
 * 刻意**不**在 `message.content` 为空时退回 `reasoning_content`：
 * 那里面装的是模型的自言自语，把它当译文会让缓存里存进一堆
 * 「我们需要把这句话翻译成中文……」——比报错难查一百倍。
 */
private fun contentOf(choice: JsonObject?): String {
    if (choice == null) return ""
    val message = choice["message"] as? JsonObject
    if (message != null) {
        message["content"].rawStringOrNull()?.let { return it }
        // 少数实现把 content 做成 [{type:"text", text:"…"}] 的分片数组。
        (message["content"] as? JsonArray)?.let { parts ->
            val joined = parts.joinToString("") { part ->
                part.objectOrNull()?.firstString("text", "content").orEmpty()
            }
            if (joined.isNotBlank()) return joined
        }
    }
    // 老式 text completions 形状，个别中转还在用。
    return choice["text"].rawStringOrNull().orEmpty()
}

/**
 * 解析 `GET {base}/models`。纯函数。
 *
 * 支持三种常见形状：OpenAI 的 `{"data":[{"id":…}]}`、Ollama 原生 `{"models":[{"name":…}]}`、
 * 以及最朴素的 `["a","b"]`。凡是能把名字取出来就行。
 */
internal fun parseModelList(body: String): ModelListOutcome {
    val element = TranslationJson.parseElementOrNull(body)
        ?: return ModelListOutcome.Err(
            TranslationFailure.BadResponse("模型列表不是合法 JSON：" + body.trim().take(200)),
        )

    val ids: List<String> = when (element) {
        is JsonArray -> element.mapNotNull { modelNameOf(it) }

        is JsonObject -> {
            val array = (element["data"] as? JsonArray) ?: (element["models"] as? JsonArray)
            array?.mapNotNull { modelNameOf(it) }.orEmpty()
        }

        else -> emptyList()
    }

    return if (ids.isEmpty()) {
        ModelListOutcome.Err(
            TranslationFailure.BadResponse(
                "响应里没有模型列表（服务商可能不支持 /models）：" + element.preview(),
            ),
        )
    } else {
        ModelListOutcome.Ok(ids.distinct().sorted())
    }
}

private fun modelNameOf(element: kotlinx.serialization.json.JsonElement): String? =
    element.rawStringOrNull() ?: element.objectOrNull()?.firstString("id", "name", "model")

/**
 * 粗估一段文本的 token 数。**中英文必须分开算**。
 *
 * 只按「字数 / 4」估会对中文严重低估（大约差 4 倍），后果是
 * 「估算长度 ≥ max_tokens 的 90%」这条截断判据永远不成立，
 * 于是中文请求被截断时会被归成「形状错误」，处置方向完全反了。
 */
internal fun estimateTokens(text: String): Int {
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.code >= 0x2E80) cjk++ else other++
    }
    return cjk + (other + 3) / 4 + 1
}
