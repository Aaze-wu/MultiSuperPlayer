package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.llm.LlmModelCatalog
import kotlinx.serialization.json.JsonObject

/**
 * 一个服务商的预设。全部字段都可以被用户覆盖——预设的职责只是
 * 「把用户从抄文档里解放出来」，不是限制他能填什么。
 *
 * @param id 持久化契约，改名等于清空用户已选的预设。
 * @param baseUrl OpenAI 兼容的 base，实际端点见 [chatCompletionsUrl]。
 *   **跑在设备上的服务商（[TranslationService.onDevice]）这里是空串**：它不出网，
 *   而空地址在远端那侧恰好是错误——所以别用「地址空不空」判本地还是远端，
 *   判据是 [TranslationService.onDevice]（数据层）/ 请求上的 `onDeviceModelId`（执行层）。
 * @param disableThinkingBody 关闭「推理模式」的额外请求体（JSON 文本，原样解析后并入请求体）。
 *   推理模型（deepseek 的 thinking、qwen 的 enable_thinking、glm 的新版默认开启思考）**会把
 *   思考 token 算进 `max_tokens`**，结果是 HTTP 200 + 空 content + `finish_reason: length`。
 *   翻译这种任务不需要推理，所以每个预设都带上自己那套关掉它的参数；用户也能在界面上改。
 * @param displayName 给用户看的名字。品牌名用 [MspText.Plain]（不该跟着语言变），
 *   带括注的用 [MspText.Res]（「（本地）」「（月之暗面）」要翻译）。
 * @param apiKeyHint 密钥输入框的占位提示。
 * @param note 给用户看的补充说明（地址怎么写、有哪些坑）。
 * @param onDevice 跑在**这台设备上**：没有地址、没有密钥，[model] 是设备端模型清单里的 id。
 *
 *   正因为它是一个**预设自带的事实**而不是「地址是不是空的」这种推导，
 *   下游几处（设置页要隐藏地址/密钥输入框、缺项判定要跳过地址、切换服务商时
 *   「预设值要不要覆盖存里的值」、「引擎配置要不要走本机」）才能共用同一个判据。
 *   用推导的写法，空地址一旦有什么新含义（比如某个中转真的要求空地址），
 *   这几处会**同时**错，而且都不报错。
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
    val onDevice: Boolean = false,
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

    /** 跑在设备上的那一家。预设、设置页的分类、请求上的 `onDeviceModelId` 都认这个 id。 */
    const val LOCAL_ID = "local"

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
        // 腾讯混元 HY-MT1.5-1.8B（Q4_K_M，1.1 GB）：翻译专用、33 种语言，
        // 而不是通用对话模型。
        //
        // 为什么拿它替掉原来的 `qwen3:8b`：两者体积差 5 倍，而字幕翻译用不上
        // 「世界知识」——要的只是逐行忠实与多语种。用 8B 的通用模型去干这件事，
        // 用户付出的是 5 倍显存、5 倍等待，换回来的没有一样是他需要的。
        //
        // ⚠️ 与设备上那条（`LlmModelCatalog.HY_MT2_18B_ID`）是**同一系列的不同版本**：
        // 这里是可以自己搭的 Ollama 路线（HY-MT1.5，1.1 GB Q4_K_M），
        // 那边是需下载到手机的 `.litertlm`（HY-MT2，1.82 GB int8）。
        // 不要因为版号相近就把两串模型名互相拷贝——格式不同，拷过去两边都用不了。
        model = "demonbyron/HY-MT1.5-1.8B",
        requiresApiKey = false,
        apiKeyHint = MspText.Res(R.string.msp_translate_svc_ollama_key_hint),
        // 混元翻译模型没有思考模式，乱塞一个字段只会让严格的网关报 400。
        disableThinkingBody = "",
        note = MspText.Res(R.string.msp_translate_svc_ollama_note),
    )

    /**
     * 跑在**设备上**的那一家。
     *
     * ## 为什么它也是一个「服务商」
     *
     * 用起来它和云端那几家一模一样：选一个模型、把提示词发出去、拿回 JSON。
     * 差别只在底下那一层（本机推理 vs HTTP），而那一层已经被
     * [SwitchingChatClient] 封在接口后面了——所以做成并列的第 N 项，用户不需要
     * 学一个新概念，设置页也不需要第二套流程。
     *
     * [baseUrl] 留空是**故意的**（不是漏填）：本地没有地址这个东西。
     * [model] 指向 `:core:llm` 模型清单里的默认条目，而不是在这里写死一个字符串：
     * 写死的话，清单换了默认模型之后，新装的用户会拿到一条已经不存在的 id，
     * 而失败信息说的是「模型没下载」——把他引到一个没有这条模型的下载页去。
     */
    val LOCAL = TranslationService(
        id = LOCAL_ID,
        displayName = MspText.Res(R.string.msp_translate_svc_local_name),
        baseUrl = "",
        model = LlmModelCatalog.DEFAULT_ID,
        requiresApiKey = false,
        apiKeyHint = MspText.Res(R.string.msp_translate_svc_local_key_hint),
        disableThinkingBody = "",
        note = MspText.Res(R.string.msp_translate_svc_local_note),
        onDevice = true,
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
        LOCAL,
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
