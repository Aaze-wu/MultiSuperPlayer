package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.text.MspText
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
 * @param displayName 给用户看的名字。品牌名用 [MspText.Plain]（不该跟着语言变），
 *   带括注的用 [MspText.Res]（「（本地）」「（月之暗面）」要翻译）。
 * @param apiKeyHint 密钥输入框的占位提示。
 * @param note 给用户看的补充说明（地址怎么写、有哪些坑）。
 */
data class TranslationService(
    val id: String,
    val displayName: MspText,
    val baseUrl: String,
    val model: String,
    val requiresApiKey: Boolean,
    val apiKeyHint: MspText,
    val disableThinkingBody: String = "",
    val note: MspText,
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
 *
 * ## 为什么名字/说明是资源而不是字符串
 *
 * 这些字全都会显示在设置页上，写死中文等于「切到英文也还有一堆中文」。
 */
object TranslationServices {

    const val CUSTOM_ID = "custom"

    /** 常见密钥前缀，与语言无关，不需要翻译。 */
    private val SK_HINT = MspText.Plain("sk-…")

    val DEEPSEEK = TranslationService(
        id = "deepseek",
        displayName = MspText.Plain("DeepSeek"),
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = MspText.Res(R.string.msp_translate_svc_deepseek_note),
    )

    val DASHSCOPE = TranslationService(
        id = "dashscope",
        displayName = MspText.Res(R.string.msp_translate_svc_dashscope_name),
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        disableThinkingBody = """{"enable_thinking":false}""",
        note = MspText.Res(R.string.msp_translate_svc_dashscope_note),
    )

    val ZHIPU = TranslationService(
        id = "zhipu",
        displayName = MspText.Res(R.string.msp_translate_svc_zhipu_name),
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3",
        requiresApiKey = true,
        apiKeyHint = MspText.Plain("xxxxx.xxxxxxxx"),
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = MspText.Res(R.string.msp_translate_svc_zhipu_note),
    )

    val MOONSHOT = TranslationService(
        id = "moonshot",
        displayName = MspText.Res(R.string.msp_translate_svc_moonshot_name),
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k2.6",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        disableThinkingBody = """{"thinking":{"type":"disabled"}}""",
        note = MspText.Res(R.string.msp_translate_svc_moonshot_note),
    )

    val OLLAMA = TranslationService(
        id = "ollama",
        displayName = MspText.Res(R.string.msp_translate_svc_ollama_name),
        // 模拟器里 10.0.2.2 就是宿主机；真机请看下面 note。
        baseUrl = "http://10.0.2.2:11434/v1",
        model = "qwen3:8b",
        requiresApiKey = false,
        apiKeyHint = MspText.Res(R.string.msp_translate_svc_ollama_key_hint),
        disableThinkingBody = "",
        note = MspText.Res(R.string.msp_translate_svc_ollama_note),
    )

    val OPENAI = TranslationService(
        id = "openai",
        displayName = MspText.Plain("OpenAI"),
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        disableThinkingBody = "",
        note = MspText.Res(R.string.msp_translate_svc_openai_note),
    )

    val CUSTOM = TranslationService(
        id = CUSTOM_ID,
        displayName = MspText.Res(R.string.msp_translate_svc_custom_name),
        baseUrl = "",
        model = "",
        requiresApiKey = false,
        apiKeyHint = MspText.Res(R.string.msp_translate_svc_custom_key_hint),
        disableThinkingBody = "",
        note = MspText.Res(R.string.msp_translate_svc_custom_note),
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
