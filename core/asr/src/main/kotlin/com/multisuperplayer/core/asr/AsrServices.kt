package com.multisuperplayer.core.asr

import com.multisuperplayer.core.model.text.MspText

/**
 * 一个云端识别服务商的预设。
 *
 * 与翻译那边（`core:translate` 的 `TranslationServices`）是同一个套路：预设的职责
 * 只是「把用户从抄文档里解放出来」，不是限制他能填什么，所以每个字段都能被覆盖。
 *
 * ## 「OpenAI 兼容」是个营销词，不保证同一形态
 *
 * 这条注释是这份表存在的主要理由。实测过四家：
 *
 * | 服务商 | 端点 | 形态 |
 * |---|---|---|
 * | OpenAI | `https://api.openai.com/v1/audio/transcriptions` | multipart，字段齐全 |
 * | Groq | `https://api.groq.com/openai/v1/audio/transcriptions` | multipart，**base 多一段 `/openai`** |
 * | 硅基流动 | `https://api.siliconflow.com/v1/audio/transcriptions` | multipart，**但只收 `file` + `model`**，也只回 `text` |
 * | 阿里云 Qwen-ASR | `…/compatible-mode/v1/chat/completions` | **不是同一个形态**（音频当消息内容） |
 *
 * 所以下面两个假设都是错的，而且错了都不报错、只表现为「时间轴整块一条」或 400：
 * ①「同一路径 ⇒ 同一能力」；②「base URL 只差主机名」。
 *
 * @param id 持久化契约，改名等于清空用户已选的预设。
 * @param baseUrl OpenAI 兼容的 base，实际端点见 [transcriptionsUrl]。
 *   这里是**逐条对过官方文档/SDK 源码**的值：凭记忆写的地址会 404，而 404 在界面上
 *   和「模型名写错了」看起来一模一样。改之前请重新确认。
 * @param supportsSegments 这个服务商的 `/audio/transcriptions` 是否收
 *   `response_format=verbose_json` + `timestamp_granularities[]=segment`。
 *
 *   **它是预设自带的事实，不是从别的东西推导出来的**，理由和翻译那边的
 *   `TranslationService.onDevice` 一样：
 *   界面要据此说明「这家能出时间轴吗」、请求要据此决定发不发那两个字段、
 *   解析要据此决定拿不到 `segments` 时是正常回退还是异常。
 *   `false` 时多发的字段可能直接换回一个 400——那不是「优雅降级」，是硬失败。
 * @param displayName 给用户看的名字。品牌名用 [MspText.Plain]（不该跟着语言变）。
 * @param note 给用户看的补充说明（地址怎么写、有哪些坑、隐私口径）。
 */
data class AsrService(
    val id: String,
    val displayName: MspText,
    val baseUrl: String,
    val model: String,
    val requiresApiKey: Boolean,
    val apiKeyHint: MspText,
    val note: MspText,
    val supportsSegments: Boolean,
)

/**
 * 内置的云端识别服务商预设。
 *
 * ## 为什么是这三家
 *
 * 三家都走**同一个端点形态**（multipart `POST /audio/transcriptions`），也就是
 * 一套请求/响应/错误解析就能覆盖。阿里云 Qwen-ASR 虽然也自称「OpenAI 兼容」，
 * 但它走 `chat/completions`、音频当消息内容，需要**第二套**请求体构造和响应解析，
 * 所以这一版不带它（见 README 的已知限制）。
 *
 * ## 为什么给「自定义」留位置
 *
 * 「OpenAI 兼容」的转写端点在国内有大量中转和自建部署，地址与模型名都只有用户知道。
 * 没有自定义的话，用户唯一的办法是等我们收录——而他手上的服务今天就能用。
 */
object AsrServices {

    const val CUSTOM_ID = "custom"

    private val SK_HINT = MspText.Plain("sk-…")

    /**
     * OpenAI。
     *
     * `whisper-1` 是**唯一**在这个端点上稳定支持 `verbose_json` 的模型
     * （`gpt-4o-transcribe` 只支持 `json` / `text`）。默认取它的理由就是时间轴：
     * 换一个字幕没有时间轴的模型，产出的东西是这个功能的退化形态。
     */
    val OPENAI = AsrService(
        id = "openai",
        displayName = MspText.Plain("OpenAI"),
        baseUrl = "https://api.openai.com/v1",
        model = "whisper-1",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        note = MspText.Res(R.string.msp_asr_svc_openai_note),
        supportsSegments = true,
    )

    /**
     * Groq。
     *
     * ⚠️ base URL 里那一段 `/openai` 不是笔误：官方 SDK 打的是
     * `/openai/v1/audio/transcriptions`（相对 base `https://api.groq.com`）。
     * 写成 `https://api.groq.com/v1` 会 404。
     *
     * 模型名只有两个取值（官方 SDK 里是 `Literal` 枚举）：`whisper-large-v3` /
     * `whisper-large-v3-turbo`。默认取 turbo：字幕生成要跑满整条音轨，
     * 而它支持 `verbose_json` + `timestamp_granularities`，时间轴与 v3 同源。
     */
    val GROQ = AsrService(
        id = "groq",
        displayName = MspText.Plain("Groq"),
        baseUrl = "https://api.groq.com/openai/v1",
        model = "whisper-large-v3-turbo",
        requiresApiKey = true,
        apiKeyHint = MspText.Plain("gsk_…"),
        note = MspText.Res(R.string.msp_asr_svc_groq_note),
        supportsSegments = true,
    )

    /**
     * 硅基流动。
     *
     * ⚠️ 两处与「OpenAI 兼容」的直觉不符，都来自它的 OpenAPI 文档：
     * ① 主机是 `api.siliconflow.com`（**`.com`**；`docs.siliconflow.cn` 上那个路径 404）；
     * ② 请求体的属性**只有 `file` 与 `model`**，没有 `response_format` /
     *    `timestamp_granularities` / `language` / `prompt`，响应体**只有 `text`**。
     *    所以 [supportsSegments] 是 `false`：不要求时间轴，拿回来的是**整块一条**。
     *
     * 这正是「按时间轴生成字幕」在部分服务商上做不到时应该长的样子：不假装有，
     * 也不因此拒绝这家——它便宜、国内直连，对「只要一份文字稿」是完全够用的。
     */
    val SILICONFLOW = AsrService(
        id = "siliconflow",
        displayName = MspText.Res(R.string.msp_asr_svc_siliconflow_name),
        baseUrl = "https://api.siliconflow.com/v1",
        model = "FunAudioLLM/SenseVoiceSmall",
        requiresApiKey = true,
        apiKeyHint = SK_HINT,
        note = MspText.Res(R.string.msp_asr_svc_siliconflow_note),
        supportsSegments = false,
    )

    /**
     * 自定义（中转站 / 自建 / 其它兼容服务）。
     *
     * [supportsSegments] 取 `true`：这个端点的默认期望是「和 OpenAI 一样全」，
     * 而**多要一次时间轴**失败时会换回一个 400（错误文案里会点出
     * 「可能不支持 verbose_json」，见 `AsrException.CloudRequest`）。反过来
     * （默认不要时间轴）的代价更重：自定义用户永远拿不到时间轴，而且看不出为什么。
     *
     * `baseUrl` / `model` 都留空：这两个值只有用户知道，我们填任何一个都是猜。
     *
     * [requiresApiKey] 取 `false`（与翻译那边的 `TranslationServices.CUSTOM` 一致）：
     * 自建/中转站很可能**不带鉴权**（局域网里的部署、one-api 的免密钥渠道），
     * 强制要求填密钥就把它们直接堵死了——用户在界面上一看「必须填密钥」就走了。
     * 而真需要密钥却没填时，服务端会回 401，那条文案（`AsrException.CloudAuth`）
     * 照样准确。所以「要不要密钥」交给 **服务端** 回答，不在这里猜。
     *
     * 顺带一个后果：预设里那三家真的需要密钥，所以发请求前那道检查
     * （`assembleJob`）只会在它们身上触发——省掉一次白传 10 MB 的 401。
     */
    val CUSTOM = AsrService(
        id = CUSTOM_ID,
        displayName = MspText.Res(R.string.msp_asr_svc_custom_name),
        baseUrl = "",
        model = "",
        requiresApiKey = false,
        apiKeyHint = MspText.Res(R.string.msp_asr_svc_custom_key_hint),
        note = MspText.Res(R.string.msp_asr_svc_custom_note),
        supportsSegments = true,
    )

    /** 界面里的排列顺序。自定义放最后。 */
    val all: List<AsrService> = listOf(OPENAI, GROQ, SILICONFLOW, CUSTOM)

    /**
     * 没选过时用哪一家。
     *
     * 与翻译那边默认 DeepSeek 不同，这里默认 **SiliconFlow**：云端识别默认要能
     * 「选完就能用」，而它的主机在国内直连、密钥申请门槛最低。代价是它的
     * [AsrService.supportsSegments] 为 `false`（时间轴整块一条）——所以设置页必须
     * 把这件事写在明处，而不是让用户生成完才发现字幕只有一条。
     */
    val DEFAULT_SERVICE: AsrService = SILICONFLOW

    /**
     * 认不出来就回自定义——不要静默回默认厂商。
     *
     * 理由同 `TranslationServices.byId`：静默回落会表现为「用户配的地址还在框里，
     * 但请求发去了另一家」，而失败信息指向的是那另一家。
     */
    fun byId(id: String?): AsrService = all.firstOrNull { it.id == id } ?: CUSTOM
}

/**
 * 把用户填的 base 拼成 `/audio/transcriptions` 端点。
 *
 * 容忍两种常见填法：
 * - `https://api.openai.com/v1` → `…/v1/audio/transcriptions`
 * - `…/v1/audio/transcriptions`（有人直接把端点抄进来）→ **原样返回**，
 *   否则会拼成 `…/audio/transcriptions/audio/transcriptions`。
 *
 * 和翻译那边的 `chatCompletionsUrl` 一样**不自动补 `/v1`**：
 * 补了的话，把 `https://api.openai.com` 这种少了版本段的地址变成「看起来能用」，
 * 而用户按自己的记忆填一个错域名时也一路绿灯。设置页会把最终地址显示出来
 * （见 [AsrService.note] 那一栏旁边的提示），让 404 能被自己看出来。
 *
 * 注意 Groq 的 base 里带 `/openai` 一段：这里不做任何「规范化」，
 * 因为任何重写都会把某一家的正确地址改成错的。
 */
fun transcriptionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/audio/transcriptions")) trimmed else "$trimmed/audio/transcriptions"
}

/**
 * 这个字符串能不能直接拿去开一个 HTTP 连接。
 *
 * 「空串算不算合法」这件事**必须由调用方决定**，所以这个函数只回答最基本的问题：
 * 有没有 http/https 协议头。两种相反的口径都真实存在：
 * - 下载源那一栏（`feature:settings` 的 `looksLikeHttpUrl`）：空 = 用默认镜像站，**合法**；
 * - 云端识别的地址（[AsrService.baseUrl] 为空的自定义预设）：空 = 没得可连，**不合法**。
 *
 * 把「空算合法」写进来就会让云端拿着一串空地址去开连接：那会抛
 * `MalformedURLException`（`IOException` 的子类），最后报成「连不上识别服务」，
 * 而用户该做的是把地址填完。所以极性不在这一层。
 *
 * 忽略大小写：`URL("HTTPS://x")` 是好的（协议名不区分大小写），而人有大写过。
 */
fun isHttpAddress(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}
