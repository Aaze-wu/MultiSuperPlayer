package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonObject

/**
 * 一个服务商的预设。全部字段都可以被用户覆盖——预设的职责只是
 * 「把用户从抄文档里解放出来」，不是限制他能填什么。
 *
 * @param id 持久化契约，改名等于清空用户已选的预设。
 * @param baseUrl OpenAI 兼容的 base，实际端点见 [chatCompletionsUrl]。
 * @param disableThinkingBody 关闭「推理模式」的额外请求体（JSON 文本，原样解析后并入请求体）。
 *   推理模型（deepseek 的 thinking、qwen 的 enable_thinking、glm 的新版默认开启思考）**会把
 *   思考 token 算进 `max_tokens`**，结果是 HTTP 200 + 空 content + `finish_reason: length`。
 *   翻译这种任务不需要推理，所以每个预设都带上自己那套关掉它的参数；用户也能在界面上改。
 * @param note 给用户看的补充说明（地址怎么写、有哪些坑）。
 */
data class TranslationService(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val requiresApiKey: Boolean,
    val apiKeyHint: String,
    val disableThinkingBody: String = "",
    val note: String = "",
)

/**
 * 内置服务商预设。
 *
 * ## 这些值是哪来的
 *
 * baseUrl 与模型名**逐条对过官方文档**，不是凭记忆写的（凭记忆写的地址会 404，
 * 而 404 在界面上看起来和「模型名写错了」一模一样）。改这里的任何一个字段前，
 * 请先去官方文档确认，尤其是模型名——厂商换代号比换地址频繁得多。
 *
 * ## 为什么默认是 DeepSeek
 *
 * 便宜、中文好、且 `deepseek-flash` 明确支持关闭思考。
 */
object TranslationServices {

    const val CUSTOM_ID = "custom"

    val DEEPSEEK = TranslationService(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        requiresApiKey = true,
        apiKeyHint = "sk-…",
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = "官方文档 api-docs.deepseek.com。base 只写域名即可，代码会补 /chat/completions。",
    )

    val DASHSCOPE = TranslationService(
        id = "dashscope",
        displayName = "阿里云百炼（通义千问）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        requiresApiKey = true,
        apiKeyHint = "sk-…",
        disableThinkingBody = """{"enable_thinking":false}""",
        note = "百炼的 key 与地域绑定：跨地域用会报 401 invalid_api_key。" +
            "开了工作空间的用户，地址要换成 https://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1。",
    )

    val ZHIPU = TranslationService(
        id = "zhipu",
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3",
        requiresApiKey = true,
        apiKeyHint = "xxxxx.xxxxxxxx",
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = "base 末尾的 /v4 不能省。",
    )

    val MOONSHOT = TranslationService(
        id = "moonshot",
        displayName = "Kimi（月之暗面）",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k2.6",
        requiresApiKey = true,
        apiKeyHint = "sk-…",
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = "官方文档写明 thinking **默认开启**，所以一定要带着关闭参数，否则长字幕会白花一大笔 token。",
    )

    val OLLAMA = TranslationService(
        id = "ollama",
        displayName = "Ollama（本地）",
        // 模拟器里 10.0.2.2 就是宿主机；真机请看下面 note。
        baseUrl = "http://10.0.2.2:11434/v1",
        model = "qwen3:8b",
        requiresApiKey = false,
        apiKeyHint = "本地服务随便填，留空也行",
        disableThinkingBody = "",
        note = "模拟器用 10.0.2.2 指宿主机。真机推荐 `adb reverse tcp:11434 tcp:11434`，" +
            "然后把地址填成 http://localhost:11434/v1——局域网明文 HTTP 会被系统安全策略拦掉，" +
            "只有 localhost / 10.0.2.2 在放行名单里。",
    )

    val OPENAI = TranslationService(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        requiresApiKey = true,
        apiKeyHint = "sk-…",
        disableThinkingBody = "",
        note = "国内网络通常需要自建代理，可以把 baseUrl 改成自己的中转地址。",
    )

    val CUSTOM = TranslationService(
        id = CUSTOM_ID,
        displayName = "自定义（任何 OpenAI 兼容服务）",
        baseUrl = "",
        model = "",
        requiresApiKey = false,
        apiKeyHint = "按服务商要求填",
        disableThinkingBody = "",
        note = "只要支持 POST {baseUrl}/chat/completions 就能用：" +
            "OpenAI、DeepSeek、通义、GLM、Kimi、Ollama、vLLM、LM Studio、one-api 中转都可以。",
    )

    /** 界面里的排列顺序。自定义放最后。 */
    val all: List<TranslationService> = listOf(
        DEEPSEEK,
        DASHSCOPE,
        ZHIPU,
        MOONSHOT,
        OLLAMA,
        OPENAI,
        CUSTOM,
    )

    val DEFAULT_SERVICE: TranslationService = DEEPSEEK

    /** 认不出来就回自定义——不要静默回默认厂商，否则用户会以为自己的配置生效了。 */
    fun byId(id: String?): TranslationService =
        all.firstOrNull { it.id == id } ?: CUSTOM
}

/**
 * 把用户填的 base 拼成完整的 chat/completions 端点。
 *
 * 容忍三种常见填法：
 * - `https://api.deepseek.com`          → `…/chat/completions`
 * - `https://api.deepseek.com/`         → 同上（尾部斜杠不算错）
 * - `https://api.deepseek.com/chat/completions`（有人直接把端点抄进来）→ **原样返回**，
 *   否则会拼成 `…/chat/completions/chat/completions`，而那个 404 会让人怀疑模型名。
 */
fun chatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

/** 拉取模型列表的端点（`GET {base}/models`），用于「拉取模型列表」按钮。 */
fun modelsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    // 用户可能把 /chat/completions 也填进来了，拉模型列表时要退回去。
    val root = trimmed.removeSuffix("/chat/completions")
    return "$root/models"
}

/** 把 [TranslationService.disableThinkingBody] 解析成对象；解析失败就返回 null（宁可多发一次不带该参数的请求，也不要崩）。 */
internal fun parseExtraBody(json: String): JsonObject? =
    json.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { TranslationJson.parseObject(text) }.getOrNull()
    }
