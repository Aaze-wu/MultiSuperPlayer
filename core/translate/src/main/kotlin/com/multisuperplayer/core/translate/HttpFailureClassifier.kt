package com.multisuperplayer.core.translate

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把 HTTP 状态码 + 响应体分类成 [TranslationFailure]。
 *
 * ## 为什么必须先读厂商原文（[extractProviderMessage]）再分类
 *
 * 因为我们自己的分类**一定会漏**：同一家厂商不同产品线返回的状态码都不统一。
 * 界面上给用户的是我们自己的中文话术，但那只能告诉他「往哪个方向查」；
 * 真正能让他自己解决的问题（key 填错了？欠费？跨地域？模型名不存在？）
 * 全在厂商原文里。所以 [TranslationFailure.detail] 一律是原文截取，不做复述。
 *
 * ## 429 有两种意思，必须分开
 *
 * 「打得太快」应当等待重试；「额度用完」重试只会更快烧完配额、而且永远不会成功。
 * 两者都是 429，只能靠响应体里的措辞区分（见 [looksLikeQuota]）。
 */
internal fun classifyHttpFailure(
    status: Int,
    retryAfterHeader: String?,
    body: String,
): TranslationFailure {
    val detail = extractProviderMessage(body)
    return when {
        status == 401 || status == 403 -> TranslationFailure.Unauthorized(status, detail)

        status == 429 && looksLikeQuota(body) -> TranslationFailure.QuotaExceeded(status, detail)

        status == 429 ->
            TranslationFailure.RateLimited(status, parseRetryAfterSeconds(retryAfterHeader), detail)

        status in 500..599 -> TranslationFailure.ServerError(status, detail)

        else -> TranslationFailure.Rejected(status, detail)
    }
}

/**
 * 从响应体里挖出厂商的错误说明。
 *
 * 挖不到就整段（截断）返回——**绝不返回空串**：一条「失败原因：」后面什么都没有的
 * 报错，比一条塞满 JSON 的报错难用得多。
 */
internal fun extractProviderMessage(body: String, maxChars: Int = 400): String {
    val trimmed = body.trim()
    // 响应体是空的就返回空串：**不要在这里造一句中文占位**。
    // 这句话是要显示给用户看的，而这一层不知道界面语言；
    // 界面拿到空串就知道「厂商什么都没说」，不显示原文块就行。
    if (trimmed.isEmpty()) return ""

    val root = TranslationJson.parseObjectOrNull(trimmed)
    val message = root?.let { obj ->
        val error = obj["error"]
        error?.rawStringOrNull()
            ?: error?.objectOrNull()?.firstString("message", "detail", "msg", "code", "type")
            ?: obj.firstString("message", "detail", "msg", "error_description", "code")
    }

    val text = message?.takeIf { it.isNotBlank() } ?: trimmed
    return if (text.length <= maxChars) text else text.take(maxChars) + "…"
}

/**
 * 响应体里出现这些词，就认为 429 是「额度/余额」而不是「打得太快」。
 *
 * **这些词一个都不能翻译**：它们匹配的是**服务商返回的响应体**，不是我们的界面文案。
 * 里面那几个中文词对应的是国内厂商（智谱/百炼等）返回的中文错误信息。
 */
private val QUOTA_MARKERS = listOf(
    "quota", "insufficient", "balance", "arrears", "billing", "credit",
    "欠费", "余额", "额度", "配额",
)

internal fun looksLikeQuota(body: String): Boolean {
    val lower = body.lowercase()
    return QUOTA_MARKERS.any { lower.contains(it) }
}

/**
 * `Retry-After` 只认秒数形式。
 *
 * HTTP-date 形式（`Wed, 21 Oct 2015 07:28:00 GMT`）也可以解析，但各家实现
 * 给的时区/时钟经常对不上，算出来的等待时间可能是负数或几小时，反而更糟。
 * 认不出来就返回 null，让调用方走自己的退避曲线。
 */
internal fun parseRetryAfterSeconds(header: String?): Long? {
    val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = text.toLongOrNull() ?: return null
    return seconds.coerceIn(0L, 120L)
}

/**
 * 网络异常 → 分类。
 *
 * 特地识别「明文被拦」：`cleartext traffic not permitted` 在界面上看起来
 * 就是一句普通的网络错误，但它的解法是**换地址或加白名单**，跟重试毫无关系。
 *
 * 所以这里留下系统那句原文（[TranslationFailure.Network.detail]），
 * 补的那一句则按档位记成枚举（[TranslationFailure.Network.note]）——
 * 文案本身在 `TranslationFailureText.kt` 里取资源，这里写死中文就会在英文界面里混出来。
 */
internal fun classifyNetworkException(error: IOException): TranslationFailure {
    val raw = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    val note = when {
        error is SocketTimeoutException -> NetworkNote.TIMEOUT
        error is UnknownHostException -> NetworkNote.UNRESOLVED_HOST
        raw.contains("CLEARTEXT", ignoreCase = true) -> NetworkNote.CLEARTEXT_BLOCKED
        else -> NetworkNote.NONE
    }
    return TranslationFailure.Network(raw, note)
}
