package com.multisuperplayer.core.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 模块内共用的 JSON 解析器与取值助手。
 *
 * ## 为什么不用 `@Serializable`
 *
 * 请求/响应都在「边界」上，字段名由**厂商**决定而不是我们：同一个概念
 * 可能叫 `content` / `text` / `message`，还可能整段缺失。用 `@Serializable` 的
 * 强类型映射会因为一个缺字段就把整个响应判死，而我们要的是「尽量取值、取不到再说」。
 * 所以这里全部走 [JsonElement] 手动游走，并且每个取值都返回可空。
 */
internal object TranslationJson {

    /**
     * `isLenient` + `allowTrailingComma`：模型偶尔会在 JSON 里留尾逗号或用单引号，
     * 这两种情况让 `parseToJsonElement` 直接抛异常，白白浪费一次已经付过钱的调用。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        explicitNulls = false
    }

    fun parseElement(text: String): JsonElement = json.parseToJsonElement(text.trim())

    fun parseObject(text: String): JsonObject = parseElement(text) as JsonObject

    fun parseObjectOrNull(text: String): JsonObject? =
        runCatching { parseObject(text) }.getOrNull()

    fun parseElementOrNull(text: String): JsonElement? =
        runCatching { parseElement(text) }.getOrNull()
}

/** 取一个对象字段，不是对象就返回 null。 */
internal fun JsonElement?.objectOrNull(): JsonObject? =
    (this as? JsonObject)

/** 取一个数组字段，不是数组就返回 null。 */
internal fun JsonElement?.arrayOrNull(): JsonArray? =
    (this as? JsonArray)

/** 取字符串。非字符串的原样转成字面量（数字/布尔也能拿到 `"3"` / `"true"`）。 */
internal fun JsonElement?.stringOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

/** 取字符串，但**只认真正的 string 类型**——避免把 `123` 当标题。 */
internal fun JsonElement?.rawStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** 按优先级取第一个**真正的字符串**字段。同一概念在不同厂商下叫法常常不同。 */
internal fun JsonObject.firstString(vararg keys: String): String? {
    for (key in keys) {
        this[key].rawStringOrNull()?.let { return it }
    }
    return null
}

/** 把元素打印成简短形态，用于错误信息（不能让一条错误信息本身把日志撑爆）。 */
internal fun JsonElement?.preview(maxChars: Int = 120): String {
    val text = this?.toString().orEmpty()
    return if (text.length <= maxChars) text else text.take(maxChars) + "…"
}

internal fun JsonArray.strings(): List<String> = mapNotNull { it.rawStringOrNull() }

/** 一个对象里「看起来像一条译文」的字段名，按可信度排序。 */
internal val TRANSLATION_KEYS = listOf(
    "translations", "translation", "lines", "line", "text", "texts",
    "result", "results", "data", "output", "content", "items",
)

/** 对象里「看起来是元信息」的字段名——用来判断这一层不是我们要的那层。 */
internal val METADATA_KEYS = listOf(
    "index", "id", "start", "end", "time", "style", "speaker",
)
