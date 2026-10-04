package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.llm.LlmModelCatalog

/**
 * 一条失败要说给用户听的话。
 *
 * @param message 发生了什么（一句话，带状态码/档位）。
 * @param hint 该做什么。**必须是可以照着做的一步**；给不出办法的失败就别猜。
 * @param raw 厂商原文。用户想自己解决时只有它能帮上忙，所以永远不要吞掉；
 *   厂商什么都没说时是 `null`（那时界面上不该出现一个空的「原文」块）。
 */
data class FailureText(
    val message: MspText,
    val hint: MspText? = null,
    val raw: String? = null,
)

/**
 * 把失败分类翻成人话。
 *
 * ## 为什么放在 core 层（没有 Android 依赖）也要决定文案
 *
 * 因为这里有两条**必须靠文案区分**的路径：
 * [TranslationFailure.BadResponse]（形状不对 ⇒ 换模型/缩小批次）和
 * [TranslationFailure.Truncated]（预算不够 ⇒ 调大 maxTokens）。两者的补救方向相反，
 * 共用一句「翻译失败」的话，用户就会用错药——反复重试撞同一堵墙。
 *
 * 所以这里是故意的例外：文案跟着分类走，就近维护，靠单测锁住两档**不会说同样的话**。
 * 但文案本身不在这里，而是返回 [MspText]（「哪一条 + 什么参数」），
 * 由界面在渲染时按当前语言取资源（见 `MspTextCompose.string()` / [MspText.resolve]）。
 * 这样这里依然可以被 JVM 单测钉住语义，不需要 `Resources`。
 *
 * ## 建议里必须点名「当前用的是什么」
 *
 * 「换个模型试试」在用户已经换了三次模型之后毫无意义。所有涉及服务商/模型的建议
 * 都会带上当前值（[providerName]/[model] 为空时不写，也不瞎编）。
 *
 * [providerName] 收 [MspText] 而不是 `String`，因为它来自
 * [TranslationService.displayName]（品牌名 + 可能需要翻译的括注）；
 * [model] 是用户填的模型标识（`kimi-k2.6`），与语言无关，所以保持 `String`。
 */
fun describeTranslationFailure(
    failure: TranslationFailure,
    providerName: MspText? = null,
    model: String? = null,
): FailureText = when (failure) {

    is TranslationFailure.NotConfigured -> FailureText(
        message = MspText.Res(
            R.string.msp_translate_fail_not_configured,
            describeMissingItem(failure.missing),
        ),
        hint = MspText.Res(R.string.msp_translate_fail_not_configured_hint),
    )

    is TranslationFailure.Unauthorized -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_unauthorized, failure.status),
        hint = MspText.Res(R.string.msp_translate_fail_unauthorized_hint)
            .withCurrent(providerName, model),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.QuotaExceeded -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_quota, failure.status),
        hint = MspText.Res(R.string.msp_translate_fail_quota_hint),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.RateLimited -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_rate_limited, failure.status),
        hint = failure.retryAfterSeconds
            ?.let { MspText.Res(R.string.msp_translate_fail_rate_limited_wait, it) }
            ?: MspText.Res(R.string.msp_translate_fail_rate_limited_hint),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.Rejected -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_rejected, failure.status),
        hint = MspText.Res(R.string.msp_translate_fail_rejected_hint)
            .withCurrent(providerName, model),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.ServerError -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_server, failure.status),
        hint = MspText.Res(R.string.msp_translate_fail_server_hint),
        raw = failure.detail.rawOrNull(),
    )

    // 网络档的 detail 是系统原文（`UnknownHostException: …`），不能丢；
    // 该去改什么则由 [TranslationFailure.Network.note] 单独说明。
    // 两段拼在一起也交给资源，是因为「中英文之间要不要空格」不一样。
    is TranslationFailure.Network -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_network),
        hint = when (val note = failure.note.text()) {
            null -> MspText.plainOrUnknown(failure.detail)
            else -> MspText.Res(
                R.string.msp_translate_network_detail,
                MspText.Plain(failure.detail),
                note,
            )
        },
    )

    is TranslationFailure.BadResponse -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_bad_response),
        hint = MspText.Res(R.string.msp_translate_fail_bad_response_hint)
            .withCurrent(providerName, model),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.EmptyCompletion -> FailureText(
        message = MspText.Res(
            R.string.msp_translate_fail_empty,
            failure.finishReason?.let {
                MspText.Res(R.string.msp_translate_fail_reason, it)
            } ?: MspText.Plain(""),
        ),
        hint = MspText.Res(
            R.string.msp_translate_fail_empty_hint,
            failure.reasoningTokens?.let {
                MspText.Res(R.string.msp_translate_fail_reasoning_tokens, it)
            } ?: MspText.Plain(""),
        ).withCurrent(providerName, model),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.Truncated -> FailureText(
        message = MspText.Res(
            R.string.msp_translate_fail_truncated,
            failure.finishReason?.let {
                MspText.Res(R.string.msp_translate_fail_reason, it)
            } ?: MspText.Plain(""),
        ),
        hint = MspText.Res(
            R.string.msp_translate_fail_truncated_hint,
            failure.maxTokens,
            failure.estimatedTokens?.let {
                MspText.Res(R.string.msp_translate_fail_truncated_estimate, it)
            } ?: MspText.Plain(""),
        ),
        raw = failure.detail.rawOrNull(),
    )

    // 本地模型名在**显文案这一步**才解析：数据层存的是 id（一会儿要当文件名用），
    // 而用户认识的是「Qwen3 0.6B（本地）」那个名字。两者都不是对方，也不该互相冒充：
    // 把 id 显给用户看会让他去搜索一个搜不到的型号；把名字存进数据里则一会儿就
    // 和实际文件名对不上了。
    is TranslationFailure.LocalModelMissing -> FailureText(
        message = MspText.Res(
            R.string.msp_translate_fail_local_model,
            LlmModelCatalog.byId(failure.model).name,
        ),
        hint = MspText.Res(R.string.msp_translate_fail_local_model_hint),
    )

    is TranslationFailure.LocalEngineUnavailable -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_local_engine),
        hint = MspText.Res(R.string.msp_translate_fail_local_engine_hint),
        raw = failure.detail.rawOrNull(),
    )

    is TranslationFailure.LocalGenerationFailed -> FailureText(
        message = MspText.Res(R.string.msp_translate_fail_local_generation),
        hint = MspText.Res(R.string.msp_translate_fail_local_generation_hint)
            .withCurrent(providerName, model),
        raw = failure.detail.rawOrNull(),
    )
}

/**
 * 「缺的是哪一项」。
 *
 * 设置页的清单和引擎的报错共用这一份文案——两处各写一遍的后果是
 * 「设置页说可以翻译、点下去引擎说条件不满足」。
 */
fun describeMissingItem(item: MissingConfigItem): MspText = MspText.Res(
    when (item) {
        MissingConfigItem.BASE_URL -> R.string.msp_translate_missing_base_url
        MissingConfigItem.BASE_URL_SCHEME -> R.string.msp_translate_missing_base_url_scheme
        MissingConfigItem.MODEL -> R.string.msp_translate_missing_model
        MissingConfigItem.API_KEY -> R.string.msp_translate_missing_api_key
        MissingConfigItem.BATCH_SIZE -> R.string.msp_translate_missing_batch_size
    },
)

/**
 * 把缺项清单拼成一句话。
 *
 * 分隔符跟着语言走（中文「、」，英文「, 」），所以不能用 `joinToString`。
 * 空清单返回空文本——调用方据此判断「其实不缺」。
 */
fun describeMissingItems(items: List<MissingConfigItem>): MspText =
    items.map(::describeMissingItem).reduceOrNull { acc, item ->
        MspText.Res(R.string.msp_translate_join_list, acc, item)
    } ?: MspText.Plain("")

/** [TranslationFailure.Network.note] 对应的补充说明；[NetworkNote.NONE] 没有话说。 */
private fun NetworkNote.text(): MspText? = when (this) {
    NetworkNote.NONE -> null
    NetworkNote.TIMEOUT -> MspText.Res(R.string.msp_translate_network_timeout)
    NetworkNote.UNRESOLVED_HOST -> MspText.Res(R.string.msp_translate_network_unresolved)
    NetworkNote.CLEARTEXT_BLOCKED -> MspText.Res(R.string.msp_translate_network_cleartext)
}

/**
 * 在建议后面点名「当前的服务商/模型」。
 *
 * 只有真的填了才写：一个「服务商：」后面什么都没有的括号，比不写更糟。
 */
private fun MspText.withCurrent(providerName: MspText?, model: String?): MspText {
    val parts = listOfNotNull(
        providerName?.takeIf { it.isProvided() }
            ?.let { MspText.Res(R.string.msp_translate_current_provider, it) },
        model?.takeIf { it.isNotBlank() }
            ?.let { MspText.Res(R.string.msp_translate_current_model, MspText.Plain(it)) },
    )
    if (parts.isEmpty()) return this
    val joined = parts.reduce { acc, part ->
        MspText.Res(R.string.msp_translate_join_comma, acc, part)
    }
    return MspText.Res(R.string.msp_translate_current_of, this, joined)
}

/**
 * 空白的 [MspText.Plain] 等于「没填」。
 *
 * [MspText.Res] 一律算填了：它要么是我们自己写的资源，要么是一段压根不该为空的文本，
 * 而为了判断它是否空白去取 `Resources`，会把这一层重新绑回 Android。
 */
private fun MspText.isProvided(): Boolean = when (this) {
    is MspText.Plain -> text.isNotBlank()
    is MspText.Res -> true
}

/** 厂商什么都没说时不要把空串当成「有原文」——界面靠 `null` 决定不显示原文块。 */
private fun String.rawOrNull(): String? = takeIf { it.isNotBlank() }
