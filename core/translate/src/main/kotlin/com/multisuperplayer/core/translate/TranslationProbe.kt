package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack

/**
 * 「测试连接」的结果。
 *
 * ## 为什么成功时要把模型的原话带回来
 *
 * 只报「连接成功」的测试会骗人：密钥对、地址对、但模型名写了 `gpt-4o-mini-typo`
 * 时，很多厂商照样返回 200（只是内容为空或结构不对），或者压根不接受这个模型名。
 * 把模型自己吐出来的那一行摆给用户看，他才能判断「它真的在按我的目标语言翻译」，
 * 而不是「HTTP 通了」。少这一句，测试结果和真实体验之间就会有一条谁也看不见的缝。
 */
sealed interface ConnectivityResult {
    val ok: Boolean

    /** 通了，而且拿回了一句真实译文。 */
    data class Ok(
        val translated: String,
        /** 用来试探的原文，界面上和译文并排显示。 */
        val sample: String,
    ) : ConnectivityResult {
        override val ok: Boolean get() = true
    }

    /** 没通。[failure] 交给 [describeTranslationFailure] 变成人话。 */
    data class Failed(val failure: TranslationFailure) : ConnectivityResult {
        override val ok: Boolean get() = false
    }
}

/** 「拉取模型列表」的结果。 */
sealed interface ModelListResult {
    data class Ok(val models: List<String>) : ModelListResult
    data class Failed(val failure: TranslationFailure) : ModelListResult
}

/**
 * 设置页用的探针：测试连接、拉模型列表。
 *
 * ## 为什么「测试连接」要跑真正的翻译管线，而不是发一个 `GET /models`
 *
 * `GET /models` 只能证明**地址和密钥**没问题，它对下面三件事一个字都不说：
 * 模型名对不对、这家厂商收不收这个请求形状、推理模式有没有把输出预算吃光。
 * 而这三种恰好是实际使用中翻不出来的绝大多数原因——于是「连接成功」之后
 * 用户回到字幕页依然一行都翻不出来，他会认为整个功能是坏的。
 *
 * 所以这里直接跑一次**单行的真实翻译**（复用 [TranslationRunner]，
 * `maxAttempts = 1` 避免为了一个探针等三轮退避），拿回来的失败原因
 * 就是真实使用时会看到的那一个，一字不差。
 */
class TranslationProbe internal constructor(
    private val runner: TranslationRunner,
    private val client: ChatCompletionClient,
) {

    /** 用固定的英文短句试探。刻意用一句话而不是随机串：好让用户一眼看出译文对不对。 */
    suspend fun test(config: TranslationConfig): ConnectivityResult {
        TranslationEngine.validateConfig(config)?.let { return ConnectivityResult.Failed(it) }

        val document = SubtitleDocumentProbe.make(SAMPLE_TEXT)
        var translated: String? = null
        var failure: TranslationFailure? = null

        // 单次尝试：探针的用处是「立刻告诉我哪里不对」。
        // 让它跟着引擎退避三轮的话，用户会等半分钟才看到一句「限流」。
        runner.translate(document, config.copy(maxAttempts = 1)).collect { event ->
            when (event) {
                is TranslationEvent.Batch -> translated = event.translations[0]
                is TranslationEvent.Finished -> if (translated == null) {
                    // 批成功了但没给出这一行的译文 —— 也算没通，要有明确原因，
                    // 不能落到「返回了空字符串」这种看起来成功的结果上。
                    failure = event.failures.firstOrNull()?.failure
                }

                is TranslationEvent.Aborted -> failure = event.failure
                else -> Unit
            }
        }

        val text = translated
        return when {
            !text.isNullOrBlank() -> ConnectivityResult.Ok(translated = text, sample = SAMPLE_TEXT)
            failure != null -> ConnectivityResult.Failed(failure!!)
            else -> ConnectivityResult.Failed(
                TranslationFailure.BadResponse("模型没有返回可用的译文。"),
            )
        }
    }

    /**
     * 拉模型列表。
     *
     * 这是**方便**而不是**必须**：很多自建/代理服务不实现这个端点（404），
     * 所以失败时界面只能说「拉不到，自己填」，不能因此判定配置错了。
     */
    suspend fun listModels(baseUrl: String, apiKey: String?): ModelListResult =
        when (val outcome = client.listModels(baseUrl, apiKey)) {
            is ModelListOutcome.Ok -> ModelListResult.Ok(outcome.models)
            is ModelListOutcome.Err -> ModelListResult.Failed(outcome.failure)
        }

    companion object {
        /** 试探用的原文。选一句最普通的英文，好让「有没有真的翻译」一眼可辨。 */
        const val SAMPLE_TEXT = "The quick brown fox jumps over the lazy dog."
    }
}

/**
 * 造一条只有一行的字幕给探针用。
 *
 * 放在 `internal object` 而不是直接写在 [TranslationProbe.test] 里：
 * 这样探测用的输入是**唯一一份**，将来调试探文本时不会漏改另一处。
 */
private object SubtitleDocumentProbe {
    fun make(text: String): SubtitleDocument = SubtitleDocument(
        track = SubtitleTrack(id = "translation-probe"),
        cues = listOf(
            SubtitleCue(index = 0, startMs = 0L, endMs = 1_000L, text = text),
        ),
    )
}
