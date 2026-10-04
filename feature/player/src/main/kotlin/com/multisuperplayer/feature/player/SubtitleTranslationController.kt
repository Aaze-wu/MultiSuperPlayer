package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.translate.CueFailure
import com.multisuperplayer.core.translate.FailureText
import com.multisuperplayer.core.translate.MissingConfigItem
import com.multisuperplayer.core.translate.TranslationCacheStore
import com.multisuperplayer.core.translate.TranslationEditsCodec
import com.multisuperplayer.core.translate.TranslationEditsStore
import com.multisuperplayer.core.translate.TranslationEvent
import com.multisuperplayer.core.translate.TranslationFailure
import com.multisuperplayer.core.translate.TranslationRunner
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.describeTranslationFailure
import com.multisuperplayer.core.translate.translatableIndices
import com.multisuperplayer.core.translate.withTranslations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 字幕翻译的运行时状态机。
 *
 * ## 为什么和字幕加载分开
 *
 * 加载是「读文件」，翻译是「花用户的钱跑网络请求」。后者的每一个动作都必须是
 * 用户可预期、可取消、可复算的，而且它的进度需要单独表达。混进加载管线的话，
 * 「切换字幕」这个本来无害的操作会开始取消正在花钱的任务。
 *
 * ## 三份译文，优先级写死
 *
 * 1. [TranslationTexts.manual]（用户手改的）——**永远压过**模型译文；
 * 2. [TranslationTexts.fromModel]（模型译的 / 上次缓存下来的）；
 * 3. 原文。
 *
 * 分开存两份而不是合成一份，是因为一个批次随时可能落下来。合成一份的话，
 * 「用户刚改完这一句，模型把这一句的旧译文又盖回去」会变成一个随机出现的 bug。
 *
 * ## 下标约定
 *
 * 所有译文都按 **`document.cues` 的下标**（不是 `SubtitleCue.index` 字段）存。
 * 引擎、分批器、缓存、人工修正四处的口径都是这个，字幕格式里的序号（尤其是 ASS）
 * 并不等于数组下标。
 */
internal class SubtitleTranslationController(
    private val runner: TranslationRunner,
    private val settings: TranslationSettingsRepository,
    private val cache: TranslationCacheStore,
    private val edits: TranslationEditsStore,
    private val scope: CoroutineScope,
) {

    private val mutableTexts = MutableStateFlow(TranslationTexts())

    /** 当前生效的译文（含人工修正）。 */
    val texts: StateFlow<TranslationTexts> = mutableTexts.asStateFlow()

    private val mutableProgress = MutableStateFlow(TranslationProgress())

    /** 有没有正在跑的翻译任务。 */
    val isRunning: Boolean get() = job?.isActive == true

    private var job: Job? = null

    /** 这一份字幕的原始人工修正（key = 「下标:原文摘要」）。保存时要用它合并，不能只写当前文档命中的那些。 */
    private var rawEdits: Map<String, String> = emptyMap()

    private var mediaKey: String = ""

    /** 进度 + 设置合成出的界面状态。 */
    val state: StateFlow<TranslationUiState> = combine(
        mutableProgress,
        mutableTexts,
        settings.settings,
    ) { progress, texts, config ->
        TranslationUiState(
            configured = config.ready,
            missing = config.missingItems,
            providerName = config.provider.displayName,
            model = config.model,
            target = config.target,
            autoTranslate = config.autoTranslate,
            apiKeyRequired = config.apiKeyRequired,
            running = progress.running,
            done = progress.done,
            total = progress.total,
            fromCache = progress.fromCache,
            requests = progress.requests,
            failures = progress.failures,
            failure = progress.failure,
            failureText = progress.failure?.let {
                describeTranslationFailure(it, config.provider.displayName, config.model)
            },
            editedCount = texts.manual.size,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TranslationUiState())

    /**
     * 换条目时调用：立刻丢掉上一部片子的译文与进度。
     *
     * 不丢的后果很隐蔽——新片子前几分钟会显示上一部的台词，而且每一句都"合法"。
     */
    fun detach() {
        cancel()
        mutableTexts.value = TranslationTexts()
        mutableProgress.value = TranslationProgress()
        rawEdits = emptyMap()
        mediaKey = ""
    }

    /**
     * 一份字幕加载完成时调用。
     *
     * 这里做两件事：把磁盘上的人工修正挂回来、把**缓存里已有**的译文直接显示出来。
     * 后者是「上次翻过的片子打开就有译文」的关键，不然用户会以为译文丢了又重新翻一遍
     * （虽然全命中缓存不花钱，但他不知道）。
     */
    suspend fun bindDocument(token: String?, mediaKey: String, document: SubtitleDocument?) {
        if (token == null || document == null) {
            detach()
            return
        }
        if (mutableTexts.value.token == token) return

        // 上一份字幕的任务已经没意义了（它带着旧文档的下标）。
        cancel()
        this.mediaKey = mediaKey

        rawEdits = edits.load(mediaKey)
        val manual = manualTranslations(document.cues, rawEdits)

        val config = settings.currentConfig()
        val cached = if (config.model.isBlank()) {
            emptyMap()
        } else {
            cache.lookupDocument(
                cues = document.cues,
                model = config.model,
                target = config.target,
                glossary = config.glossary,
            )
        }

        mutableTexts.value = TranslationTexts(token = token, fromModel = cached, manual = manual)
        mutableProgress.value = TranslationProgress()
    }

    /**
     * 开始翻译。
     *
     * @param pendingIndices 要翻的下标；null = 全部可翻的行。
     */
    fun start(document: SubtitleDocument, pendingIndices: List<Int>?) {
        if (isRunning) return
        val token = mutableTexts.value.token

        job = scope.launch {
            val config = settings.currentConfig()
            mutableProgress.value = TranslationProgress(running = true)

            runner.translate(document, pendingIndices, config).collect { event ->
                when (event) {
                    is TranslationEvent.Planned -> mutableProgress.update {
                        it.copy(total = event.total, fromCache = event.cached)
                    }

                    is TranslationEvent.Batch -> {
                        // 只在「还是这份字幕」的时候落译文。
                        if (mutableTexts.value.token == token) {
                            mutableTexts.update { it.copy(fromModel = it.fromModel + event.translations) }
                        }
                        mutableProgress.update {
                            it.copy(
                                done = event.done,
                                total = event.total,
                                fromCache = event.fromCache,
                                requests = event.requests,
                            )
                        }
                    }

                    // 一个批次失败不该毁掉整个任务：只累加失败，不动已翻好的部分。
                    is TranslationEvent.BatchFailed -> mutableProgress.update { progress ->
                        progress.copy(
                            failures = progress.failures +
                                event.cueIndices.map { CueFailure(it, event.failure) },
                        )
                    }

                    is TranslationEvent.Finished -> mutableProgress.update {
                        it.copy(
                            running = false,
                            done = event.done,
                            total = event.total,
                            fromCache = event.fromCache,
                            requests = event.requests,
                            failures = event.failures,
                        )
                    }

                    is TranslationEvent.Aborted -> mutableProgress.update {
                        it.copy(running = false, done = event.done, total = event.total, failure = event.failure)
                    }
                }
            }
        }
    }

    /** 取消。已经落盘的译文和进度保留——用户能看到「翻到哪儿了」。 */
    fun cancel() {
        job?.cancel()
        job = null
        mutableProgress.update { it.copy(running = false) }
    }

    /** 只重试上次失败的那些行。 */
    fun retryFailed(document: SubtitleDocument) {
        val indices = mutableProgress.value.failures.map { it.cueIndex }.filter { it >= 0 }.distinct()
        if (indices.isEmpty()) return
        mutableProgress.update { it.copy(failures = emptyList(), failure = null) }
        start(document, indices)
    }

    /**
     * 用户手改一句。
     *
     * 空文本 = 把这一句的译文**彻底清掉**（不是「恢复模型译文」，那是 [revertEdit]）。
     * 两种语义在界面上是两个不同的按钮，因为合并成一种就会出现
     * 「我只想撤销自己的修改，结果整句译文没了」。
     */
    fun applyEdit(cueIndex: Int, sourceText: String, text: String) {
        val trimmed = text.trim()
        mutableTexts.update {
            val manual = if (trimmed.isEmpty()) {
                it.manual - cueIndex
            } else {
                it.manual + (cueIndex to trimmed)
            }
            val fromModel = if (trimmed.isEmpty()) it.fromModel - cueIndex else it.fromModel
            it.copy(manual = manual, fromModel = fromModel)
        }

        val key = TranslationEditsCodec.key(cueIndex, sourceText)
        rawEdits = if (trimmed.isEmpty()) rawEdits - key else rawEdits + (key to trimmed)
        persistEdits()
    }

    /** 撤销人工修改，退回模型译文（如果模型译过）。 */
    fun revertEdit(cueIndex: Int, sourceText: String) {
        mutableTexts.update { it.copy(manual = it.manual - cueIndex) }
        rawEdits = rawEdits - TranslationEditsCodec.key(cueIndex, sourceText)
        persistEdits()
    }

    /**
     * 保存人工修正。
     *
     * 保存的是 [rawEdits]（含**对不上的旧条目**）而不是当前文档命中的那几条：
     * 同一部片子换了个字幕版本时，旧版本的修正会被暂时对不上，但它没坏——
     * 换回旧字幕就该重新生效。直接覆盖等于把它们删了。
     */
    private fun persistEdits() {
        if (mediaKey.isBlank()) return
        val snapshot = rawEdits
        val key = mediaKey
        scope.launch { edits.save(key, snapshot) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/**
 * 当前生效的译文。
 *
 * @param token 这份译文属于哪份字幕（用它的 uri）。**不带这个就会串台**：
 *   用户切到另一条字幕文件时，下标含义已经变了，旧的 Map 按位置套上去
 *   会得到「每一句都还在、但全都错位一行」的假译文。
 */
internal data class TranslationTexts(
    val token: String? = null,
    val fromModel: Map<Int, String> = emptyMap(),
    val manual: Map<Int, String> = emptyMap(),
) {
    /** 人工修正压过模型译文。 */
    val effective: Map<Int, String> get() = if (manual.isEmpty()) fromModel else fromModel + manual

    /**
     * 套到字幕上。token 对不上就原样返回——宁可显示原文，也不要显示别人的译文。
     */
    fun appliedTo(document: SubtitleDocument?, token: String?): SubtitleDocument? {
        if (document == null || this.token == null || this.token != token) return document
        val merged = effective
        if (merged.isEmpty()) return document
        return document.withTranslations(merged)
    }
}

/** 翻译任务的进度与失败。 */
internal data class TranslationProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val fromCache: Int = 0,
    val requests: Int = 0,
    val failures: List<CueFailure> = emptyList(),
    /** 中止整个任务的原因（配置/鉴权/余额）。为 null 表示没被中止。 */
    val failure: TranslationFailure? = null,
)

/** 字幕面板上「翻译」那一块要用到的全部状态。 */
data class TranslationUiState(
    /** 设置是否已经填够（地址/模型/密钥）。没填够时按钮要禁掉并说明缺什么。 */
    val configured: Boolean = false,
    val providerName: MspText = MspText.Plain(""),
    val model: String = "",
    val target: TranslationTarget = TranslationTarget.DEFAULT,
    val autoTranslate: Boolean = false,
    val apiKeyRequired: Boolean = true,
    /**
     * 具体缺哪几项（[configured] 为 true 时是空列表）。
     *
     * 只给一个布尔的话，面板上就只能写「需要服务地址、模型名和密钥」——
     * 而本地 Ollama 不需要密钥、预设好的厂商只剩密钥没填，
     * 于是这句「三选三」的清单反而把人指到了错的地方。
     */
    val missing: List<MissingConfigItem> = emptyList(),
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val fromCache: Int = 0,
    val requests: Int = 0,
    val failures: List<CueFailure> = emptyList(),
    /** 中止整个任务的原因。 */
    val failure: TranslationFailure? = null,
    /** [failure] 的人话版本（含建议）。 */
    val failureText: FailureText? = null,
    val editedCount: Int = 0,
) {
    val progress: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** 有任务在跑，或者跑过但被中止。 */
    val active: Boolean get() = running || failure != null

    val failedCount: Int get() = failures.size
}

/**
 * 把磁盘上的人工修正换算成「下标 → 译文」。
 *
 * 抽成纯函数是为了能锁住那个关键行为：**对不上的条目要自然失效**，
 * 而不是错位复用（见 [TranslationEditsCodec.key]）。
 */
internal fun manualTranslations(
    cues: List<SubtitleCue>,
    rawEdits: Map<String, String>,
): Map<Int, String> {
    if (rawEdits.isEmpty() || cues.isEmpty()) return emptyMap()
    return cues.indices
        .mapNotNull { index ->
            rawEdits[TranslationEditsCodec.key(index, cues[index].text)]?.let { index to it }
        }
        .toMap()
}

/**
 * 「翻译到当前位置」要翻哪些行。
 *
 * 只取已经播过的行：用户拖到 10 分钟处点一下，就该把前 10 分钟翻掉，
 * 而不是把整部片子都跑了（那是「翻译全文」按钮干的事）。
 *
 * 已经有译文的行跳过：它们再跑一遍只是白查缓存，但进度条会从 0 重新爬，
 * 用户会以为上一次的成果没了。
 */
internal fun translatableUpTo(document: SubtitleDocument, positionMs: Long): List<Int> =
    document.translatableIndices().filter { index ->
        val cue = document.cues.getOrNull(index) ?: return@filter false
        cue.startMs <= positionMs && cue.translation.isNullOrBlank()
    }

/**
 * 自动翻译要预取的行：从当前这句往后 [window] 句里**还没译文**的那些。
 *
 * 往前看几秒就够——字幕是顺序消费的，把整部片子提前翻掉既慢又贵，
 * 而用户可能看到一半就退出了。
 */
internal fun translatableWindow(
    document: SubtitleDocument,
    positionMs: Long,
    window: Int = AUTO_TRANSLATE_WINDOW,
): List<Int> {
    val cues = document.cues
    val current = cues.indexOfFirst { positionMs in it.startMs until it.endMs }
        .let { if (it >= 0) it else cues.indexOfFirst { it.startMs >= positionMs } }
    if (current < 0) return emptyList()

    return (current until minOf(cues.size, current + window))
        .filter { index ->
            val cue = cues[index]
            !cue.isComment && cue.text.isNotBlank() && cue.translation.isNullOrBlank()
        }
}

/** 自动预取的窗口大小。行数 ≈ 一两分钟的对白，够撑过一次网络往返。 */
internal const val AUTO_TRANSLATE_WINDOW = 24
