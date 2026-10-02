package com.multisuperplayer.core.translate

/**
 * 一条失败要说给用户听的话。
 *
 * @param message 发生了什么（一句话，带状态码/档位）。
 * @param hint 该做什么。**必须是可以照着做的一步**；给不出办法的失败就别猜。
 * @param raw 厂商原文。用户想自己解决时只有它能帮上忙，所以永远不要吞掉。
 */
data class FailureText(
    val message: String,
    val hint: String? = null,
    val raw: String? = null,
)

/**
 * 把失败分类翻成人话。
 *
 * ## 为什么放在 core 层（没有 Android 依赖）也要写文案
 *
 * 因为这里有两条**必须靠文案区分**的路径：
 * [TranslationFailure.BadResponse]（形状不对 ⇒ 换模型/缩小批次）和
 * [TranslationFailure.Truncated]（预算不够 ⇒ 调大 maxTokens）。两者的补救方向相反，
 * 共用一句「翻译失败」的话，用户就会用错药——反复重试撞同一堵墙。
 *
 * 所以这里是故意的例外：文案跟着分类走，就近维护，靠单测锁住两档**不会说同样的话**。
 *
 * ## 建议里必须点名「当前用的是什么」
 *
 * 「换个模型试试」在用户已经换了三次模型之后毫无意义。所有涉及服务商/模型的建议
 * 都会带上当前值（[providerName]/[model] 为空时不写，也不瞎编）。
 */
fun describeTranslationFailure(
    failure: TranslationFailure,
    providerName: String? = null,
    model: String? = null,
): FailureText = when (failure) {

    is TranslationFailure.NotConfigured -> FailureText(
        message = "翻译设置还没填完：缺${failure.missing}。",
        hint = "到「设置 → 字幕翻译」里补上再回来。",
    )

    is TranslationFailure.Unauthorized -> FailureText(
        message = "鉴权失败（HTTP ${failure.status}）。",
        hint = buildString {
            append("密钥可能填错了、过期了，或者和所选地区不匹配")
            append("（阿里云百炼的 key 与地域绑定，跨地域用会报 invalid_api_key）")
            append("。")
        }.withCurrent(providerName, model),
        raw = failure.detail,
    )

    is TranslationFailure.QuotaExceeded -> FailureText(
        message = "账户余额或额度不足（HTTP ${failure.status}）。",
        hint = "先去服务商后台充值；重试只会更快地把额度烧完。",
        raw = failure.detail,
    )

    is TranslationFailure.RateLimited -> FailureText(
        message = "请求太频繁（HTTP ${failure.status}）。",
        hint = failure.retryAfterSeconds
            ?.let { "服务商要求等 $it 秒。稍后重试即可。" }
            ?: "稍等一会儿再试，或者把批次调小一点。",
        raw = failure.detail,
    )

    is TranslationFailure.Rejected -> FailureText(
        message = "服务商拒绝了这次请求（HTTP ${failure.status}）。",
        hint = "多半是模型名不存在、或者该模型不支持这种请求格式。".withCurrent(providerName, model),
        raw = failure.detail,
    )

    is TranslationFailure.ServerError -> FailureText(
        message = "服务商内部错误（HTTP ${failure.status}）。",
        hint = "这不是你的设置问题，可以重试；连续出现就去服务商状态页看看。",
        raw = failure.detail,
    )

    // 网络档的 detail 已经是带网址/`adb reverse` 提示的人话（见 HttpFailureClassifier），
    // 这里再套一层自己的猜测只会覆盖掉更准的信息，所以直接原样透出。
    is TranslationFailure.Network -> FailureText(
        message = "连不上服务商。",
        hint = failure.detail,
    )

    is TranslationFailure.BadResponse -> FailureText(
        message = "返回内容的结构不对（不是额度问题）。",
        hint = buildString {
            append("重试会得到同样的结果，需要换一个更听指令的模型")
            append("，或把「每批行数」调小。")
        }.withCurrent(providerName, model),
        raw = failure.detail,
    )

    is TranslationFailure.EmptyCompletion -> FailureText(
        message = "模型这次一个字都没输出" +
            (failure.finishReason?.let { "（finish_reason=$it）" } ?: "") +
            "。",
        hint = buildString {
            append("这类情况几乎都是推理模式把输出预算吃光了")
            failure.reasoningTokens?.let { append("（本次思考用了 $it tokens）") }
            append("：先确认「关闭思考」的参数对这家服务商生效，再考虑调大 maxTokens。")
        }.withCurrent(providerName, model),
        raw = failure.detail,
    )

    is TranslationFailure.Truncated -> FailureText(
        message = "输出被截断了：预算不够装下这一批。" +
            (failure.finishReason?.let { "（finish_reason=$it）" } ?: ""),
        hint = "把 maxTokens（当前 ${failure.maxTokens}）调大，或把「每批行数」调小。" +
            failure.estimatedTokens?.let { "本次估算需要约 $it tokens。" }.orEmpty(),
        raw = failure.detail,
    )
}

private fun String.withCurrent(providerName: String?, model: String?): String {
    val parts = listOfNotNull(
        providerName?.takeIf { it.isNotBlank() }?.let { "服务商：$it" },
        model?.takeIf { it.isNotBlank() }?.let { "模型：$it" },
    )
    return if (parts.isEmpty()) this else "$this（当前${parts.joinToString("，")}）"
}
