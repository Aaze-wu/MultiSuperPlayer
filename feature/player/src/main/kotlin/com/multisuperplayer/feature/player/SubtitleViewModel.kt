package com.multisuperplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.subtitle.SubtitleLoadResult
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.data.subtitle.SubtitleScan
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.bestAutoMatch
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 字幕轨的加载与选择。
 *
 * ## 为什么和 [PlayerViewModel] 分开
 *
 * 字幕加载是**异步、会失败、会被切歌打断**的：扫目录要查 MediaStore，读文件要开
 * 输入流，解析要跑正则。这些塞进 [PlayerViewModel] 的话，那个「刻意什么都不做、
 * 只转发播放内核状态」的类就会变成唯一一个有状态的地方，而它的 KDoc 里
 * 明确写着「这里不存可变状态」是为了避免两份真相。
 *
 * 分开之后这里可以合法地持有可变状态：它管的东西（挂哪条字幕）**只有**播放页关心，
 * 不像「现在在播什么」那样必须和内核保持一致。
 *
 * ## 显式绑定当前条目，而不是去订阅播放内核
 *
 * [bindEntry] 由播放页在条目变化时调用。反过来（这里去订阅 `PlaybackController`）
 * 会多一条隐式依赖：这个 ViewModel 就没法在不启动播放器的前提下测试/预览了，
 * 而它其实是一个纯函数式的状态机。
 *
 * `flatMapLatest` 至今仍是 `@ExperimentalCoroutinesApi`（`mapLatest` 已经转正，它没有），
 * 这里 opt-in 而不是自己写 `transformLatest`+`collect`：它的语义正是
 * 「切歌时取消上一条的加载」，自己写一遗只会重复一遍同样的逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleViewModel(
    private val repository: SubtitleRepository,
    private val settingsRepository: SubtitleSettingsRepository,
) : ViewModel() {

    private val entry = MutableStateFlow<MediaEntry?>(null)

    /** 用户手选的候选；换条目时清空，回到自动挑选。 */
    private val selection = MutableStateFlow<SubtitleSelection>(SubtitleSelection.Auto)

    /**
     * 「重新扫描」按钮。
     *
     * 用一个自增计数而不是把扫描写成一次性函数：扫描结果必须流回同一条状态管线，
     * 否则「重扫之后界面还是旧的」——两条更新路径会各自写一次状态，谁后写谁赢。
     */
    private val revision = MutableStateFlow(0)

    /** 加载结果。只依赖「哪条媒体 + 选了哪条字幕 + 第几次扫」。 */
    private val loadState: StateFlow<SubtitleLoadState> = combine(
        entry,
        selection,
        revision,
    ) { entry, selection, revision ->
        LoadTarget(entry, selection, revision)
    }
        .flatMapLatest { target -> loadFlow(target) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            SubtitleLoadState(),
        )

    /**
     * 显示给界面的状态 = 加载结果 + 用户偏好。
     *
     * ## 为什么显示模式不参与上面的加载管线
     *
     * 它是**纯渲染**的开关：关掉字幕不该取消已经解析好的结果、再打开时也不该
     * 重新扫一遍目录（用户会觉得卡一下）。所以模式只在这一层合并进来，
     * 加载管线完全看不见它。
     */
    val state: StateFlow<SubtitleUiState> = combine(
        loadState,
        settingsRepository.settings,
    ) { load, settings ->
        resolveSubtitleState(load, settings.displayMode)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        SubtitleUiState(),
    )

    /** 播放页在「当前条目」变化时调用。条目为 null 表示队列空了。 */
    fun bindEntry(mediaEntry: MediaEntry?) {
        if (this.entry.value?.id == mediaEntry?.id) {
            // 同一条媒体（比如队列刷新后重新构造了对象）不要重扫：
            // MediaEntry 是 data class，重新扫描会产生一批内容相同的新实例，
            // 白跑一次查询还会让已挂上的字幕闪一下。
            this.entry.value = mediaEntry
            return
        }
        this.entry.value = mediaEntry
        // 手动选择只对「那条媒体」有效。留着它会让用户在下一首歌上
        // 看到「明明没选过却挂着一条字幕」，而且看不出是哪来的。
        selection.value = SubtitleSelection.Auto
    }

    /** 用户从候选列表里选了一条。 */
    fun selectSource(source: SubtitleSource) {
        selection.value = SubtitleSelection.Source(source)
    }

    /** 恢复自动挑选。 */
    fun useAutoSelection() {
        selection.value = SubtitleSelection.Auto
    }

    /** 重新扫目录（用户刚把字幕文件拷进来、或者上次查询失败）。 */
    fun rescan() {
        revision.value += 1
    }

    fun setDisplayMode(mode: SubtitleDisplayMode) {
        viewModelScope.launch { settingsRepository.setDisplayMode(mode) }
    }

    /** 关掉字幕 = 切到 [SubtitleDisplayMode.OFF]，不是「卸载这条字幕」。 */
    fun disableSubtitles() = setDisplayMode(SubtitleDisplayMode.OFF)

    /**
     * 加载管线：扫目录 → 挑一条 → 读+解析。
     *
     * 每一步都先 emit 一个「进行中」的状态再去干活，这样界面能立刻显示
     * 扫描/加载指示，而不是在几百毫秒的空白里让用户以为没有字幕。
     *
     * `flatMapLatest` 保证切歌时上一条的加载被取消——这是「切歌打断加载」这个
     * 需求唯一的实现方式：取消是结构化的，不需要任何标志位。
     */
    private fun loadFlow(target: LoadTarget) = flow {
        val entry = target.entry
        if (entry == null) {
            emit(SubtitleLoadState())
            return@flow
        }

        // 一进管线就定下来：后面每次 emit 都要带上它，否则面板上会出现
        // 「自动选择」和某条候选同时打勾——两个看起来一样的设置。
        val autoSelected = target.selection is SubtitleSelection.Auto

        emit(SubtitleLoadState(phase = SubtitlePhase.SCANNING, autoSelected = autoSelected))

        val scan = repository.scan(entry)
        when (scan) {
            SubtitleScan.NoDirectory -> {
                emit(
                    SubtitleLoadState(
                        phase = SubtitlePhase.READY,
                        autoSelected = autoSelected,
                        issue = SubtitleIssue.NoDirectory,
                    ),
                )
                return@flow
            }

            SubtitleScan.DirectoryInvisible -> {
                emit(
                    SubtitleLoadState(
                        phase = SubtitlePhase.READY,
                        autoSelected = autoSelected,
                        issue = SubtitleIssue.DirectoryInvisible,
                    ),
                )
                return@flow
            }

            is SubtitleScan.Failed -> {
                emit(
                    SubtitleLoadState(
                        phase = SubtitlePhase.READY,
                        autoSelected = autoSelected,
                        issue = SubtitleIssue.ScanFailed(scan.message),
                    ),
                )
                return@flow
            }

            is SubtitleScan.Found -> scanFound(target, scan.sources, autoSelected)
        }
    }

    private suspend fun FlowCollector<SubtitleLoadState>.scanFound(
        target: LoadTarget,
        sources: List<SubtitleSource>,
        autoSelected: Boolean,
    ) {
        val chosen = when (val current = target.selection) {
            SubtitleSelection.Auto -> sources.bestAutoMatch()
            // 手选的照用，哪怕它已经不在候选列表里（重扫时目录暂时读不到之类）。
            // 用户明确点过的东西不该被自动逻辑推翻。
            is SubtitleSelection.Source -> current.source
        }

        if (chosen == null) {
            emit(
                SubtitleLoadState(
                    phase = SubtitlePhase.READY,
                    autoSelected = autoSelected,
                    candidates = sources,
                    issue = if (sources.isEmpty()) SubtitleIssue.NoSubtitles else SubtitleIssue.NoMatch,
                ),
            )
            return
        }

        // 候选先落到状态里：用户能在解析完成前就打开列表看到有哪些语言版本。
        emit(
            SubtitleLoadState(
                phase = SubtitlePhase.LOADING,
                autoSelected = autoSelected,
                candidates = sources,
                attached = chosen,
            ),
        )

        when (val result = repository.load(chosen)) {
            is SubtitleLoadResult.Loaded -> emit(
                SubtitleLoadState(
                    phase = SubtitlePhase.READY,
                    autoSelected = autoSelected,
                    candidates = sources,
                    attached = chosen,
                    document = result.document,
                    hasTranslation = result.document.hasTranslation(),
                ),
            )

            is SubtitleLoadResult.Failed -> emit(
                SubtitleLoadState(
                    phase = SubtitlePhase.READY,
                    autoSelected = autoSelected,
                    candidates = sources,
                    attached = chosen,
                    issue = SubtitleIssue.LoadFailed(result.fileName, result.message),
                ),
            )
        }
    }

    private data class LoadTarget(
        val entry: MediaEntry?,
        val selection: SubtitleSelection,
        val revision: Int,
    )

    private companion object {
        /** 与 [PlayerViewModel] 同一个理由：配置变化时不重扫、不重新解析。 */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/** 用户对「用哪条字幕」的选择。换条目时回到 [Auto]。 */
internal sealed interface SubtitleSelection {
    data object Auto : SubtitleSelection

    data class Source(val source: SubtitleSource) : SubtitleSelection
}

/** 加载阶段。 */
enum class SubtitlePhase {
    /** 还没有条目。 */
    IDLE,

    /** 正在查字幕目录。 */
    SCANNING,

    /** 已经决定了用哪条，正在读+解析。 */
    LOADING,

    /** 本轮结束（成功或失败），状态里的其他字段才是结论。 */
    READY,
}

/**
 * 需要用户知道的问题。
 *
 * 刻意**不带**用户可见文案：文案要跟着语言走，而这里是状态。
 * 界面层负责把每个分支映射成一条字符串资源，这样每加一个分支编译器都会
 * 提醒你补一句提示语（`when` 穷尽性），不会出现「什么都没显示」的静默失败。
 */
sealed interface SubtitleIssue {
    /** 拿不到所在目录（Android 9 及以下没有 `relativePath`）。 */
    data object NoDirectory : SubtitleIssue

    /** 目录在 MediaStore 里不可见，多半是存储权限。 */
    data object DirectoryInvisible : SubtitleIssue

    /** 查询本身失败。 */
    data class ScanFailed(val message: String) : SubtitleIssue

    /** 目录里没有字幕文件。 */
    data object NoSubtitles : SubtitleIssue

    /** 有字幕文件，但没有一个对得上片名。 */
    data object NoMatch : SubtitleIssue

    /** 读/解析失败，[message] 是原始原因，原样透出。 */
    data class LoadFailed(val fileName: String, val message: String) : SubtitleIssue
}

/** 加载结果，不含用户偏好。 */
data class SubtitleLoadState(
    val phase: SubtitlePhase = SubtitlePhase.IDLE,
    val attached: SubtitleSource? = null,
    val document: SubtitleDocument? = null,
    val candidates: List<SubtitleSource> = emptyList(),
    val issue: SubtitleIssue? = null,
    /** 这份字幕里有没有译文。加载完成时算一次存起来，不要在渲染路径上重算。 */
    val hasTranslation: Boolean = false,
    /** 当前用的是「自动挑选」还是用户手选的那一条。只影响选择面板的单选框。 */
    val autoSelected: Boolean = true,
)

/** 界面可见的字幕状态。 */
data class SubtitleUiState(
    /** 用户选的模式。 */
    val displayMode: SubtitleDisplayMode = SubtitleDisplayMode.DEFAULT,
    /**
     * 实际生效的模式。
     *
     * 与 [displayMode] 不同只有一种情况：用户选了「仅译文」但这条字幕压根没有译文。
     * 那时降级为「仅原文」——**绝不能显示空白**，用户会以为字幕加载失败，
     * 然后去折腾一个其实没坏的东西。
     */
    val effectiveMode: SubtitleDisplayMode = SubtitleDisplayMode.DEFAULT,
    val phase: SubtitlePhase = SubtitlePhase.IDLE,
    val attached: SubtitleSource? = null,
    val document: SubtitleDocument? = null,
    val candidates: List<SubtitleSource> = emptyList(),
    val issue: SubtitleIssue? = null,
    /** 解析时的非致命问题（跳过坏行、编码是猜的…），在字幕列表里展示。 */
    val warnings: List<String> = emptyList(),
    /** 用户选了「仅译文」但没译文——界面要解释一句，否则看起来像降级失败了。 */
    val translationUnavailable: Boolean = false,
    /** 当前用的是自动挑选还是手选的那一条，供选择面板画单选框。 */
    val autoSelected: Boolean = true,
) {
    val isLoading: Boolean get() = phase == SubtitlePhase.SCANNING || phase == SubtitlePhase.LOADING

    /** 屏幕上有东西可画吗。 */
    val isRendering: Boolean
        get() = effectiveMode != SubtitleDisplayMode.OFF &&
            document?.isEmpty == false

    val cueCount: Int get() = document?.cues?.size ?: 0
}

/**
 * 把加载结果 + 偏好合成界面状态。
 *
 * 抽成顶层**纯函数**是因为这里有一条不能错的规则：**任何情况下都不显示空白字幕**。
 * 纯函数才能把它钉在测试里，而不是只能靠在模拟器上试一次「选了仅译文会不会白屏」。
 */
internal fun resolveSubtitleState(
    load: SubtitleLoadState,
    displayMode: SubtitleDisplayMode,
): SubtitleUiState {
    val document = load.document
    val fallbackNeeded = displayMode == SubtitleDisplayMode.TRANSLATION_ONLY &&
        document != null &&
        !load.hasTranslation

    return SubtitleUiState(
        displayMode = displayMode,
        effectiveMode = if (fallbackNeeded) SubtitleDisplayMode.ORIGINAL_ONLY else displayMode,
        phase = load.phase,
        attached = load.attached,
        document = document,
        candidates = load.candidates,
        issue = load.issue,
        warnings = document?.warnings.orEmpty(),
        translationUnavailable = fallbackNeeded,
        autoSelected = load.autoSelected,
    )
}

/**
 * 这份字幕里有没有任何一条译文。
 *
 * 在**加载完成时**算一次存起来，而不是做成 `val hasTranslation get() = cues.any {...}`：
 * 后者会被每一帧的重组调用，而字幕动辄上千条——一个看不见的 O(n) 挂在渲染路径上。
 */
internal fun SubtitleDocument.hasTranslation(): Boolean =
    cues.any { !it.translation.isNullOrBlank() }
