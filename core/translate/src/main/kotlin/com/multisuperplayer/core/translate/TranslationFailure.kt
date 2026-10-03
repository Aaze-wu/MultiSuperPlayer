package com.multisuperplayer.core.translate

/**
 * 「设置还差哪一项」的语义值。
 *
 * 这里刻意**不是一个字符串**：文案要跟着界面语言走，而这一层（core）不该知道
 * 界面语言。存储这一层的是同一个枚举，所以「设置页说可以翻译、点下去引擎说
 * 不满足条件」这种自相打脸的状态从类型上就不可能发生。
 *
 * 具体文案见 `describeMissingItem()`。
 */
enum class MissingConfigItem {
    /** 服务地址是空的。 */
    BASE_URL,

    /** 填了服务地址，但不是 http:// 或 https:// 开头。 */
    BASE_URL_SCHEME,

    /** 模型名是空的。 */
    MODEL,

    /** 该服务商需要密钥，但还没存过。 */
    API_KEY,

    /** 批次大小低于下限（引擎侧才会碰到）。 */
    BATCH_SIZE,
}

/**
 * 网络异常的补充说明。
 *
 * 三种情况**重试一百次也一样**，所以要分开说：超时是等，DNS 是地址填错，
 * 明文被拦是要 adb reverse。系统原始异常信息永远原样带出（[Network.detail]），
 * 这里只描述「该去改什么」。
 */
enum class NetworkNote {
    /** 没什么可补充的，把系统原文透出去就够了。 */
    NONE,

    /** 读超时。 */
    TIMEOUT,

    /** 域名解析不了。 */
    UNRESOLVED_HOST,

    /** 明文 HTTP 被系统安全策略拦下。 */
    CLEARTEXT_BLOCKED,
}

/**
 * 一次翻译请求失败的分类。
 *
 * ## 为什么每一档都要带 `detail`（厂商原文）
 *
 * 「鉴权失败」「余额不足」「限流」这三档，界面上给的是我们自己的话术，
 * 但**用户只有看到厂商原文才能自己去解决**（是 key 填错了？还是欠费？还是
 * 跨地域 key？）。所以 detail 一律是原样截取的响应体或异常信息，不做复述。
 *
 * ## 为什么这一层不写任何界面文案
 *
 * 和 `SubtitleIssue` 同一个理由：字符串属于 UI 层，core 层只负责分类。
 * 分类是**可以单测的**，文案不是。所以「缺什么」「哪种网络问题」都用枚举，
 * 文案在 `TranslationFailureText.kt` 里取资源。
 */
sealed interface TranslationFailure {

    /** 配置不全（缺 baseUrl / 模型名，或需要 key 却没填）。[missing] 是要用户去补的那一项。 */
    data class NotConfigured(val missing: MissingConfigItem) : TranslationFailure

    /** 401 / 403：key 错、没权限、跨地域。**不重试**。 */
    data class Unauthorized(val status: Int, val detail: String) : TranslationFailure

    /** 429 且响应体提到余额/额度。**不重试**——重试只会更快烧完。 */
    data class QuotaExceeded(val status: Int, val detail: String) : TranslationFailure

    /** 429 但只是打得太快。可以等一会儿重试。 */
    data class RateLimited(
        val status: Int,
        val retryAfterSeconds: Long?,
        val detail: String,
    ) : TranslationFailure

    /** 400 / 404 / 422：请求被拒（模型名不存在、参数不被支持）。**不重试**。 */
    data class Rejected(val status: Int, val detail: String) : TranslationFailure

    /** 5xx：厂商侧的问题，可以重试。 */
    data class ServerError(val status: Int, val detail: String) : TranslationFailure

    /** 连不上/超时/明文被拦。可以重试。[note] 说明该去改什么，[detail] 是系统原文。 */
    data class Network(val detail: String, val note: NetworkNote = NetworkNote.NONE) :
        TranslationFailure

    /**
     * 拿到了 200，但**内容形状不对**（不是 JSON、数组长度对不上…）。
     *
     * 这一档和 [Truncated] 必须分得开：形状不对要改提示词或缩小批次，
     * 被截断要加大预算。混成一档的话，重试永远撞同一堵墙。
     */
    data class BadResponse(val detail: String) : TranslationFailure

    /**
     * 200 但 `content` 为空。
     *
     * 十有八九是推理模型的思考 token 把 `max_tokens` 吃光了
     * （`reasoning_tokens == completion_tokens`、`finish_reason: length`）。
     * 所以它**不是**「解析失败」，而是预算问题：要么关掉思考，要么加大 max_tokens。
     * [finishReason] 原样带出来，方便用户/我们判断到底是哪一种。
     */
    data class EmptyCompletion(
        val finishReason: String?,
        val completionTokens: Int?,
        val reasoningTokens: Int?,
        val detail: String,
    ) : TranslationFailure

    /** 200 且内容非空，但 JSON 括号没闭合 / `finish_reason == length`。预算不够。 */
    data class Truncated(
        val finishReason: String?,
        val estimatedTokens: Int?,
        val maxTokens: Int,
        val detail: String,
    ) : TranslationFailure

    /**
     * 设备上那条模型还没下载好（或文件不完整）。[model] 是清单里的 id。
     *
     * ## 为什么要单独一档，而不蹭 [NotConfigured]
     *
     * 「设置里没填」和「文件没下载」在界面上是两句完全不同的话：前者让人去填输入框，
     * 后者让人去点下载。而且它们连**在哪一层被发现**都不一样：缺设置是纯数据的判断，
     * 缺文件得去看文件系统（设置页那里根本不查——它专门有一块「本地模型」）。
     *
     * **不重试**：重试一万次还是同一个文件。
     */
    data class LocalModelMissing(val model: String) : TranslationFailure

    /**
     * 设备上的推理引擎起不来：原生库没装上、初始化失败、内存不够、设备不支持。
     *
     * 与 [LocalModelMissing] 分开的理由是**下一步相反**：那一档下载就能解决，
     * 这一档重新下载多少次都没用（去检查设备/内存/重装）。
     */
    data class LocalEngineUnavailable(val detail: String) : TranslationFailure

    /**
     * 引擎可用，但这一次生成挂了。**可以原样重试**。
     *
     * 和 [ServerError] 一样属于「本次不行，再试可能行」那一类；与它的差别只在
     * 文案（本机 vs 厂商），但正因为文案不同才不能合并——把本机的失败说成
     * 「服务器返回 500」会让用户去刷新服务商的页面，而问题在他的手机上。
     */
    data class LocalGenerationFailed(val detail: String) : TranslationFailure
}

/**
 * 这一档值得「原样重试」吗？
 *
 * 限流/服务器错误/网络抖动可以；鉴权和余额不行（重试只是浪费时间和额度）；
 * 形状问题也不能原样重试——必须换策略（拆批/改提示词/加预算），由引擎决定。
 */
val TranslationFailure.retryableAsIs: Boolean
    get() = when (this) {
        is TranslationFailure.RateLimited,
        is TranslationFailure.ServerError,
        is TranslationFailure.Network,
        // 本机生成失败与「服务端 5xx」同类：可能只是这一次不行（重试前会重建引擎）。
        is TranslationFailure.LocalGenerationFailed,
        -> true

        else -> false
    }

/**
 * 这一档要不要**中止整个任务**？
 *
 * 配置/鉴权/余额都是「再翻 200 批也还是失败」，继续下去只是把同一句话
 * 失败 200 次，用户看到的进度条还会一直往前爬——那是最糟的体验。
 */
val TranslationFailure.abortsJob: Boolean
    get() = when (this) {
        is TranslationFailure.NotConfigured,
        is TranslationFailure.Unauthorized,
        is TranslationFailure.QuotaExceeded,
        is TranslationFailure.Rejected,
        // 模型没下载 / 引擎起不来：后面每一批都会在同一个地方失败。
        is TranslationFailure.LocalModelMissing,
        is TranslationFailure.LocalEngineUnavailable,
        -> true

        else -> false
    }

/** 给日志用的一行摘要（不含界面文案）。 */
internal fun TranslationFailure.logLine(): String = when (this) {
    is TranslationFailure.NotConfigured -> "not_configured missing=${missing.name}"
    is TranslationFailure.Unauthorized -> "unauthorized status=$status detail=$detail"
    is TranslationFailure.QuotaExceeded -> "quota_exceeded status=$status detail=$detail"
    is TranslationFailure.RateLimited ->
        "rate_limited status=$status retryAfter=$retryAfterSeconds detail=$detail"
    is TranslationFailure.Rejected -> "rejected status=$status detail=$detail"
    is TranslationFailure.ServerError -> "server_error status=$status detail=$detail"
    is TranslationFailure.Network -> "network note=${note.name} detail=$detail"
    is TranslationFailure.BadResponse -> "bad_response detail=$detail"
    is TranslationFailure.EmptyCompletion ->
        "empty_completion finish=$finishReason completion=$completionTokens " +
            "reasoning=$reasoningTokens detail=$detail"
    is TranslationFailure.Truncated ->
        "truncated finish=$finishReason estimated=$estimatedTokens max=$maxTokens detail=$detail"
    is TranslationFailure.LocalModelMissing -> "local_model_missing model=$model"
    is TranslationFailure.LocalEngineUnavailable -> "local_engine_unavailable detail=$detail"
    is TranslationFailure.LocalGenerationFailed -> "local_generation_failed detail=$detail"
}
