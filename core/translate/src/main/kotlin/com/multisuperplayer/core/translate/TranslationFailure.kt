package com.multisuperplayer.core.translate

/**
 * 一次翻译请求失败的分类。
 *
 * ## 为什么每一档都要带 [detail]（厂商原文）
 *
 * 「鉴权失败」「余额不足」「限流」这三档，界面上给的是我们自己的中文话术，
 * 但**用户只有看到厂商原文才能自己去解决**（是 key 填错了？还是欠费？还是
 * 跨地域 key？）。所以 [detail] 一律是原样截取的响应体或异常信息，不做复述。
 *
 * ## 为什么这一层不写任何界面文案
 *
 * 和 `SubtitleIssue` 同一个理由：字符串属于 UI 层，core 层只负责分类。
 * 分类是**可以单测的**，文案不是。
 */
sealed interface TranslationFailure {

    /** 配置不全（缺 baseUrl / 模型名，或需要 key 却没填）。[missing] 是要用户去补的那一项。 */
    data class NotConfigured(val missing: String) : TranslationFailure

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

    /** 连不上/超时/明文被拦。可以重试。 */
    data class Network(val detail: String) : TranslationFailure

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
        -> true

        else -> false
    }

/** 给日志用的一行摘要（不含界面文案）。 */
internal fun TranslationFailure.logLine(): String = when (this) {
    is TranslationFailure.NotConfigured -> "not_configured missing=$missing"
    is TranslationFailure.Unauthorized -> "unauthorized status=$status detail=$detail"
    is TranslationFailure.QuotaExceeded -> "quota_exceeded status=$status detail=$detail"
    is TranslationFailure.RateLimited ->
        "rate_limited status=$status retryAfter=$retryAfterSeconds detail=$detail"
    is TranslationFailure.Rejected -> "rejected status=$status detail=$detail"
    is TranslationFailure.ServerError -> "server_error status=$status detail=$detail"
    is TranslationFailure.Network -> "network detail=$detail"
    is TranslationFailure.BadResponse -> "bad_response detail=$detail"
    is TranslationFailure.EmptyCompletion ->
        "empty_completion finish=$finishReason completion=$completionTokens " +
            "reasoning=$reasoningTokens detail=$detail"
    is TranslationFailure.Truncated ->
        "truncated finish=$finishReason estimated=$estimatedTokens max=$maxTokens detail=$detail"
}
