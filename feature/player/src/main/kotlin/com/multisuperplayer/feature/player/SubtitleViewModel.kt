package com.multisuperplayer.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.settings.SubtitleBottomMargin
import com.multisuperplayer.core.data.settings.SubtitleLineSpacing
import com.multisuperplayer.core.data.settings.SubtitleOutline
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleStyle
import com.multisuperplayer.core.data.settings.SubtitleTextSize
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.data.subtitle.SubtitleExportWriter
import com.multisuperplayer.core.data.subtitle.SubtitleLoadResult
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.data.subtitle.SubtitleScan
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.bestAutoMatch
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import com.multisuperplayer.core.player.TrackSelectionController
import com.multisuperplayer.core.player.embeddedTextTracks
import com.multisuperplayer.core.player.firstSelectedTextTrack

import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.translate.TranslationCacheStore
import com.multisuperplayer.core.translate.TranslationEditsStore
import com.multisuperplayer.core.translate.TranslationRunner
import com.multisuperplayer.core.translate.buildExportedSubtitle
import com.multisuperplayer.core.translate.exportFileName
import com.multisuperplayer.core.translate.translatableIndices
import com.multisuperplayer.core.translate.translatedCount
import com.multisuperplayer.core.translate.translationMediaKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    /** 容器里的轨道、以及内嵌字幕行的来源。见 [TrackSelectionController]。 */
    private val tracks: TrackSelectionController,
    private val settingsRepository: SubtitleSettingsRepository,
    translationSettings: TranslationSettingsRepository,
    translationRunner: TranslationRunner,
    translationCache: TranslationCacheStore,
    translationEdits: TranslationEditsStore,
    private val exportWriter: SubtitleExportWriter,
) : ViewModel() {

    /**
     * 翻译的状态机。放在这里而不是另一个 ViewModel，是因为它需要的东西
     * （当前字幕文档、当前下标）正好是这个类唯一持有的那两样。
     */
    private val translation = SubtitleTranslationController(
        runner = translationRunner,
        settings = translationSettings,
        cache = translationCache,
        edits = translationEdits,
        scope = viewModelScope,
    )

    /** 翻译状态（设置、进度、失败）。 */
    val translationState: StateFlow<TranslationUiState> get() = translation.state

    /** 当前生效的译文，供逐句编辑界面读取。 */
    internal val translationTexts: StateFlow<TranslationTexts> get() = translation.texts

    /**
     * 自动翻译开关。
     *
     * 这里用 `Eagerly` 而不是 `WhileSubscribed`：面板关着的时候也要能预取，
     * 而 `WhileSubscribed` 在没人订阅时 `value` 会停在初始值上，
     * 于是「开了自动翻译但一直不生效」——这种 bug 从界面上看不出任何线索。
     */
    private val autoTranslate = translationSettings.settings
        .map { it.autoTranslate }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 上一次自动预取是停在哪一句上的（防止同一句反复重试把接口打爆）。 */
    private var lastAutoCueIndex = -1

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

    /**
     * 字幕时间轴微调（毫秒，正数 = 字幕晚出现）。
     *
     * 它不进加载管线（那个管线的输入是「哪条媒体 + 选了哪条字幕 + 第几次扫」）：
     * 调一格就让整条管线重跑的话，每点一下都要重扫目录。它只在 [state] 那一层合进来。
     */
    private val timelineOffset = MutableStateFlow(0L)

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
     * 加载结果 + 内嵌字幕。
     *
     * 内嵌字幕走**单独一层**而不是塞进 [loadState] 的管线：那边的输入是
     * 「哪条媒体 + 选了哪条字幕 + 第几次扫」，一旦把「读到了哪些行」算进去，
     * 每来一行字幕（几百毫秒一次）都会让整条管线重跑一遍——扫目录、读文件、
     * 解析全都重做。这里只是把同样的 cues 换进 document，没有 IO。
     *
     * 内嵌的 cues 是**流式**长出来的：刚开始没有，放着放着就有了。所以这里
     * 每一批都重新构造 document（而不是增量改）。构造本身是 O(n)，而 n 在一部
     * 两小时的片子里约两千；真正重的是解析，这里不碰。
     */
    private val resolvedLoadState: StateFlow<SubtitleLoadState> = combine(
        loadState,
        tracks.embeddedSubtitle,
    ) { load, embedded ->
        load.withEmbedded(
            // 「自动」时用内核**实际选中**的那条：它已经把语言、默认/强制标记
            // 都算过了（见 `bestEmbeddedTextTrack`），字幕层再算一遍就会出现
            // 两份规则、两个结果。
            autoTrack = tracks.tracks.value.firstSelectedTextTrack(),
            cues = embedded.cues,
            mediaUri = entry.value?.uri,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        SubtitleLoadState(),
    )

    /**
     * 显示给界面的状态 = 加载结果 + 用户偏好 + 译文。
     *
     * ## 为什么显示模式不参与上面的加载管线
     *
     * 它是**纯渲染**的开关：关掉字幕不该取消已经解析好的结果、再打开时也不该
     * 重新扫一遍目录（用户会觉得卡一下）。所以模式只在这一层合并进来，
     * 加载管线完全看不见它。
     *
     * ## 译文只在这一层套上去
     *
     * 加载管线里的 document 永远是**原文**那份。译文是叠加层：一旦写进加载结果，
     * 「重新扫描」就会把刚翻译好的几百行又变回没有译文（因为重新解析出来的
     * 文档里没有它们），而用户会以为是翻译白做了。
     *
     * `hasTranslation` 必须跟着重算：手动翻译完一份本来没译文的字幕后，
     * 若仍沿用加载时算出的 `false`，「仅译文」模式会一直降级成「仅原文」，
     * 用户看到的是「翻译成功了但字幕没变」。
     */
    val state: StateFlow<SubtitleUiState> = combine(
        resolvedLoadState,
        settingsRepository.settings,
        translation.texts,
        tracks.tracks,
        timelineOffset,
    ) { load, settings, texts, trackList, offsetMs ->
        val merged = load.document?.let { texts.appliedTo(it, load.translationToken()) }
        resolveSubtitleState(
            load = if (merged == null) load else load.copy(
                document = merged,
                hasTranslation = merged.hasTranslation(),
            ),
            displayMode = settings.displayMode,
            embeddedTracks = trackList.embeddedTextTracks(),
            timelineOffsetMs = offsetMs,
            style = settings.style,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        SubtitleUiState(),
    )

    init {
        // 字幕加载完成就把磁盘上的人工修正、以及上次翻好的缓存挂回来。
        // 不用 `state` 那条管线：那是给界面看的合成结果，而这里是个副作用。
        viewModelScope.launch {
            resolvedLoadState.collect { load ->
                if (load.phase != SubtitlePhase.READY) return@collect
                translation.bindDocument(
                    token = load.translationToken(),
                    mediaKey = translationMediaKey(entry.value?.uri),
                    document = load.document,
                )
            }
        }
    }

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
        // 译文同理，而且后果更重：按位置存的译文套到另一部片子上会“每句都在、全都错位”。
        translation.detach()
        // 时间轴微调同理：为上一部片子调出来的偏移量套到下一部上就是「字幕忽然全错」，
        // 而用户不会想到是这回事（面板上那个数字平时根本不看）。
        timelineOffset.value = 0L
        lastAutoCueIndex = -1
    }

    /** 用户从候选列表里选了一条。 */
    fun selectSource(source: SubtitleSource) {
        // 换字幕文件后下标含义全变了，旧译文必须先丢掉（新的那份会重新 bind）。
        translation.detach()
        lastAutoCueIndex = -1
        selection.value = SubtitleSelection.Source(source)
    }

    /**
     * 用户选了容器里的一条内嵌字幕轨。
     *
     * 字幕行是**播放内核**读出来给我们的，所以光在这里记一笔不够，还得让内核去选它
     * （否则面板切到了内嵌、屏幕上一个字也没有）。顺序上先通知内核再改选择：
     * 反过来的话 `state` 会先变成「挂着内嵌轨但 0 行」，看起来就像加载失败。
     */
    fun selectEmbeddedTrack(track: MspTrackInfo) {
        translation.detach()
        lastAutoCueIndex = -1
        tracks.selectTrack(MspTrackKind.TEXT, track.id)
        selection.value = SubtitleSelection.Embedded(track)
    }

    /** 恢复自动挑选。 */
    fun useAutoSelection() {
        // 内嵌轨那侧也要跟着回自动：只改这一层的话，内核仍旧停在上一次手选的轨上，
        // 而界面上「自动」已经被勾上——一个只改了半边状态的典型症状。
        tracks.useAutomaticTracks()
        selection.value = SubtitleSelection.Auto
    }

    /** 重新扫目录（用户刚把字幕文件拷进来、或者上次查询失败）。 */
    fun rescan() {
        revision.value += 1
    }

    /**
     * 字幕时间轴微调一格。[deltaMs] 为正 = 让字幕更晚出现。
     *
     * 夹在 ±[SUBTITLE_OFFSET_LIMIT_MS] 之内：这个面板是用来「纠偏几百毫秒」的，
     * 能让它调到分钟级的话，用户一旦误以为它是个搜索条就会把字幕拖到自己都找不回来，
     * 而问题出在别处（挂错了字幕文件）。
     */
    fun nudgeTimelineOffset(deltaMs: Long) {
        timelineOffset.value = (timelineOffset.value + deltaMs)
            .coerceIn(-SUBTITLE_OFFSET_LIMIT_MS, SUBTITLE_OFFSET_LIMIT_MS)
    }

    /** 把微调归零。 */
    fun resetTimelineOffset() {
        timelineOffset.value = 0L
    }

    // ------------------------------------------------------------------ 翻译

    /** 翻译全文。已有的译文不会重新花钱（会命中缓存）。 */
    fun translateAll() {
        translation.start(currentDocument() ?: return, pendingIndices = null)
    }

    /** 只翻「已经播过的部分」。拖到中间点一下就能看前面，不必等整部片。 */
    fun translateUpTo(positionMs: Long) {
        val document = currentDocument() ?: return
        translation.start(document, translatableUpTo(document, positionMs))
    }

    fun cancelTranslation() = translation.cancel()

    /** 只重试上次失败的行。整篇重翻会白花已经付过的钱。 */
    fun retryFailedTranslation() {
        translation.retryFailed(currentDocument() ?: return)
    }

    /** 人工改一句。空文本 = 把这一句的译文清掉。 */
    fun editTranslation(cueIndex: Int, text: String) {
        val cue = currentDocument()?.cues?.getOrNull(cueIndex) ?: return
        translation.applyEdit(cueIndex, cue.text, text)
    }

    /** 撤销人工修改，退回模型译文。 */
    fun revertTranslation(cueIndex: Int) {
        val cue = currentDocument()?.cues?.getOrNull(cueIndex) ?: return
        translation.revertEdit(cueIndex, cue.text)
    }

    /**
     * 播放进度变化。
     *
     * 只在「换到新的一句」时才可能发起预取：轮询式的判断不能每次都发请求，
     * 否则一次失败会让它每 200ms 重试一次，半分钟就把免费额度吃完。
     */
    fun onPositionChanged(positionMs: Long) {
        val document = currentDocument() ?: return
        val cueIndex = document.cues.indexOfFirst { positionMs in it.startMs until it.endMs }
        if (cueIndex < 0 || cueIndex == lastAutoCueIndex) return
        lastAutoCueIndex = cueIndex

        if (!autoTranslate.value || translation.isRunning) return
        val pending = translatableWindow(document, positionMs)
        if (pending.isEmpty()) return
        translation.start(document, pending)
    }

    /**
     * 当前字幕文档（带已生效的译文）。
     *
     * 翻译的输入必须是这份合并后的文档：不然「哪些行已经有译文」无从得知，
     * 已经翻过的行会被再请求一遍（命中缓存所以不花钱，但进度条会从 0 重新爬）。
     */
    private fun currentDocument(): SubtitleDocument? {
        val load = resolvedLoadState.value
        return translation.texts.value.appliedTo(load.document, load.translationToken())
    }

    // ------------------------------------------------------------------ 导出

    /** 导出结果的一句话。写完不自动消失，要用户自己关——他需要时间看清是哪一步失败了。 */
    private val mutableExportMessage = MutableStateFlow<MspText?>(null)
    val exportMessage: StateFlow<MspText?> = mutableExportMessage.asStateFlow()

    fun clearExportMessage() {
        mutableExportMessage.value = null
    }

    /** 建议的文件名（不含目录）。带上目标语言代码，免得同一目录里互相覆盖。 */
    fun suggestedExportName(format: SubtitleExportFormat, mode: SubtitleExportMode): String =
        exportFileName(
            // 内嵌字幕没有文件名（它就是媒体文件自己），用媒体名——沿用
            // 「字幕文件名派生自片名」那条约定，导出结果才能和片子放一起而不错位。
            sourceFileName = loadState.value.attached?.fileName
                ?: entry.value?.displayName
                ?: entry.value?.title
                ?: "",
            target = translationState.value.target,
            format = format,
            mode = mode,
        )

    /**
     * 写导出文件。
     *
     * 用**当前生效的译文**（人工修正压过模型译文），而不是重新去算一遍，
     * 否则用户刚改的那几句会在他自己导出的文件里消失。
     *
     * 也不重新调模型：导出的东西必须就是屏幕上正在显示的那份。
     */
    fun exportTo(uri: Uri, format: SubtitleExportFormat, mode: SubtitleExportMode) {
        val document = resolvedLoadState.value.document ?: return
        val text = buildExportedSubtitle(
            document = document,
            translations = translation.texts.value.effective,
            format = format,
            mode = mode,
        )
        val name = suggestedExportName(format, mode)

        viewModelScope.launch {
            val ok = exportWriter.write(uri, text)
            mutableExportMessage.value = if (ok) {
                MspText.Res(R.string.msp_player_export_done, name)
            } else {
                MspText.Res(R.string.msp_player_export_failed, name)
            }
        }
    }

    fun setDisplayMode(mode: SubtitleDisplayMode) {
        viewModelScope.launch { settingsRepository.setDisplayMode(mode) }
    }

    /** 关掉字幕 = 切到 [SubtitleDisplayMode.OFF]，不是「卸载这条字幕」。 */
    fun disableSubtitles() = setDisplayMode(SubtitleDisplayMode.OFF)

    // ---------------------------------------------------------------- 字幕样式
    //
    // 四个 setter 都直接写仓库并**不**回读任何东西：样式的唯一真相源是 DataStore，
    // 而 `state` 那条管线本来就订阅着它（见上面的 `combine`）。在这里再存一份
    // 「当前字号」就会出现两个真相源，其中一个只在改完之后才跟上。
    //
    // 这四个值和 `setDisplayMode` 一样是**全局**的：改字号影响的是之后每一部片子，
    // 而不是「这一部」。想成「字幕层的缩放」会让人以为下标（cue 时间轴）也得跟着变，
    // 那是不需要的。

    fun setTextSize(size: SubtitleTextSize) {
        viewModelScope.launch { settingsRepository.setTextSize(size) }
    }

    fun setLineSpacing(spacing: SubtitleLineSpacing) {
        viewModelScope.launch { settingsRepository.setLineSpacing(spacing) }
    }

    fun setOutline(outline: SubtitleOutline) {
        viewModelScope.launch { settingsRepository.setOutline(outline) }
    }

    fun setBottomMargin(margin: SubtitleBottomMargin) {
        viewModelScope.launch { settingsRepository.setBottomMargin(margin) }
    }

    /** 四个样式键在仓库里是**一次事务**写完的，所以不会出现「只恢复了三个」的中间态。 */
    fun resetStyle() {
        viewModelScope.launch { settingsRepository.resetStyle() }
    }

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

        when (val step = decideScanStep(scan, target.selection)) {
            is ScanStep.Proceed -> scanFound(target, step.candidates, autoSelected)
            is ScanStep.Stop -> emit(
                SubtitleLoadState(
                    phase = SubtitlePhase.READY,
                    autoSelected = autoSelected,
                    issue = step.issue,
                ),
            )
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
            // 内嵌轨不走文件加载：行是内核给的，没有「选哪份文件」这一步。
            is SubtitleSelection.Embedded -> null
        }

        val embedded = (target.selection as? SubtitleSelection.Embedded)?.track
        if (chosen == null) {
            emit(
                SubtitleLoadState(
                    phase = SubtitlePhase.READY,
                    autoSelected = autoSelected,
                    candidates = sources,
                    embeddedTrack = embedded,
                    issue = when {
                        // 内嵌轨此刻一行都还没有，因为播放头还没走到有台词的地方。
                        // 报「没找到字幕」就把「正常但还没开始」误报成了失败。
                        embedded != null -> null
                        sources.isEmpty() -> SubtitleIssue.NoSubtitles
                        else -> SubtitleIssue.NoMatch
                    },
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

        /** 时间轴微调的上下限（毫秒）。见 [nudgeTimelineOffset]。 */
        const val SUBTITLE_OFFSET_LIMIT_MS = 10_000L
    }
}

/** 用户对「用哪条字幕」的选择。换条目时回到 [Auto]。 */
internal sealed interface SubtitleSelection {
    data object Auto : SubtitleSelection

    data class Source(val source: SubtitleSource) : SubtitleSelection

    /**
     * 容器自带的一条字幕轨。
     *
     * 整条 [MspTrackInfo] 带着而不是只记 id：面板要显示它的语言/标签/格式，
     * 而轨道清单只在选的那一瞬才保证含有它（切媒体后会重建）。
     */
    data class Embedded(val track: MspTrackInfo) : SubtitleSelection
}

/** 目录扫描之后该干什么。 */
internal sealed interface ScanStep {
    /**
     * 可以挑了。[candidates] 是这次扫出来的候选，**可能为空**——
     * 手选的字幕不依赖目录，扫不出来时这里就是空的。
     */
    data class Proceed(val candidates: List<SubtitleSource>) : ScanStep

    /** 没法继续，按 [issue] 提示用户。 */
    data class Stop(val issue: SubtitleIssue) : ScanStep
}

/**
 * 扫描结果 + 当前选择 ⇒ 接下来干什么。
 *
 * 抽成顶层**纯函数**是因为这里有一条曾经写错过的规则：**手选的字幕与目录扫描无关**。
 * 写成 `when (scan) { 三个失败分支 -> emit + return }` 的话，「用户亲手点了一条字幕、
 * 而那个目录恰好扫不出来」会把加载整个吞掉。
 *
 * 而这个组合恰恰是最常见的：需要手动指定字幕的地方就是扫描最容易失败的地方——
 * `Download/` 根、`.nomedia` 目录、媒体库不索引的路径。那时界面只剩一句
 * 「目录不可用」，用户会以为自己挑的格式不对，而功能其实一次都没跑起来过。
 * （这些路径上的媒体来自文件浏览器，[SubtitleScan.NoDirectory] 是它们当时一定会
 * 拿到的结果。现在它们改走「直接列目录」了，但**规则本身不依赖于某个来源会失败**，
 * 仍然是这个纯函数。）
 *
 * 纯函数才能把这条钉在测试里——插在协程管线中间的那段 `if` 只有跑真机才验得到。
 */
internal fun decideScanStep(scan: SubtitleScan, selection: SubtitleSelection): ScanStep {
    // 扫到了：手选和自动走同一条路（候选里手选的那条会带单选框）。
    if (scan is SubtitleScan.Found) return ScanStep.Proceed(scan.sources)

    // 扫不到，但用户亲手指过一条 —— 照旧加载它，候选列表留空。
    // 不要把那条手选的字幕塞进 candidates：面板里「当前挂着的」来自 `attached`，
    // 凭空造一条候选会让「候选」这个列表的含义变成两种。
    //
    // 内嵌轨同样与目录无关：片源在别的目录、字幕在容器里，扫描失败不该把
    // 「看容器里的字幕」也一起堵死。
    if (selection is SubtitleSelection.Source || selection is SubtitleSelection.Embedded) {
        return ScanStep.Proceed(emptyList())
    }

    return ScanStep.Stop(
        when (scan) {
            SubtitleScan.NoDirectory -> SubtitleIssue.NoDirectory
            SubtitleScan.DirectoryInvisible -> SubtitleIssue.DirectoryInvisible
            is SubtitleScan.Failed -> SubtitleIssue.ScanFailed(scan.message)
            // 不可达（上面已返回），写出来是为了让 `when` 保持为表达式：
            // 将来给 `SubtitleScan` 加分支时编译器会在这里报错提醒。
            is SubtitleScan.Found -> error("Found 已在上面返回")
        },
    )
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
    data class ScanFailed(val message: MspText) : SubtitleIssue

    /** 目录里没有字幕文件。 */
    data object NoSubtitles : SubtitleIssue

    /** 有字幕文件，但没有一个对得上片名。 */
    data object NoMatch : SubtitleIssue

    /** 读/解析失败，[message] 是原始原因，原样透出。 */
    data class LoadFailed(val fileName: String, val message: MspText) : SubtitleIssue
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
    /** 当前挂着的内嵌字幕轨。null = 用的是外挂字幕（或者没挂）。 */
    val embeddedTrack: MspTrackInfo? = null,
) {
    /**
     * 这份译文属于哪份字幕。
     *
     * 外挂用 uri，内嵌用轨道 id：同名文件在两部片子里都存在，而 `"0"` 这种轨道 id
     * 更是每部片子都有——不加前缀就会让两部片子的译文互相套用。
     */
    internal fun translationToken(): String? =
        attached?.uri ?: embeddedTrack?.id?.let { "embedded:$it" }
}

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
    /** 已有译文的行数（含人工修正）。面板上显示「已译 120/308」用。 */
    val translatedCount: Int = 0,
    /** 可翻的行数（不算注释行和空行）。0 表示这份字幕没什么可翻的。 */
    val translatableCount: Int = 0,
    /** 容器里的字幕轨（只含能够在我们自己的字幕层里渲染的那些）。 */
    val embeddedTracks: List<MspTrackInfo> = emptyList(),
    /** 当前挂着的内嵌字幕轨。null = 用的是外挂字幕（或者没挂）。 */
    val embeddedTrack: MspTrackInfo? = null,
    /**
     * 字幕时间轴微调（毫秒）。正数 = 字幕比声音**晚**出现。
     *
     * 只影响「拿哪个时刻去查 cue」，不影响进度条、跳转、拖动手势：那些都是
     * 播放位置本身，微调不该把它们一起挪走（否则拖完手指会看到进度条跳一格）。
     *
     * 这是**本次播放的**状态，不落到设置里。片源之间的偏移量互不相等（同剧集不同
     * 压制组的偏移都不一样），持久化会让下一部片子静默地错上几百毫秒——而屏幕上
     * 没有任何东西提示「你以前调过」。换条目时归零（见 `SubtitleViewModel.bindEntry`）。
     */
    val timelineOffsetMs: Long = 0L,
    /**
     * 字幕外观（字号 / 行距 / 描边 / 底部距离）。
     *
     * 放在**状态**里而不是让渲染层自己去订阅设置，是因为 `SubtitleOverlay` 拿到的
     * 就是这一个对象。让它再依赖一个仓库就会出现两个数据源各自重组：一帧里可能
     * 一半用新字号、一半用旧字号，而屏幕上只表现为「改完字号那一下闪了一下」。
     */
    val style: SubtitleStyle = SubtitleStyle.DEFAULT,
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
    embeddedTracks: List<MspTrackInfo> = emptyList(),
    timelineOffsetMs: Long = 0L,
    style: SubtitleStyle = SubtitleStyle.DEFAULT,
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
        translatedCount = document?.cues?.translatedCount() ?: 0,
        translatableCount = document?.translatableIndices()?.size ?: 0,
        embeddedTracks = embeddedTracks,
        embeddedTrack = load.embeddedTrack,
        timelineOffsetMs = timelineOffsetMs,
        style = style,
    )
}

/**
 * 字幕层该拿哪个时刻去查 cue。
 *
 * `微调 > 0` 表示「字幕晚出现」，所以要把查询时刻往回推：t 时刻该显示的是
 * 原本 `t - 微调` 那一刻的台词。抽成一个函数（而不是在两处渲染里各写一遍减号）
 * 是为了让符号方向只有一个地方可以写错，并且能钉在单测里。
 */
internal fun subtitleCuePosition(positionMs: Long, timelineOffsetMs: Long): Long =
    positionMs - timelineOffsetMs

/**
 * 微调值的显示文本：`+0.5` / `-1.0` / `0`。
 *
 * 纯整数运算：用 `String.format("%.1f")` 会在小数点用逗号的地区变成 `+0,5`，
 * 而文案里已经写了「秒」，数字部分就不该再随语言变。
 */
internal fun formatSubtitleOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return "0"
    val sign = if (offsetMs > 0) "+" else "-"
    val abs = kotlin.math.abs(offsetMs)
    return "$sign${abs / 1000}.${(abs % 1000) / 100}"
}

/**
 * 这份字幕里有没有任何一条译文。
 *
 * 在**加载完成时**算一次存起来，而不是做成 `val hasTranslation get() = cues.any {...}`：
 * 后者会被每一帧的重组调用，而字幕动辄上千条——一个看不见的 O(n) 挂在渲染路径上。
 */
internal fun SubtitleDocument.hasTranslation(): Boolean =
    cues.any { !it.translation.isNullOrBlank() }

/**
 * 把容器里读到的字幕行换进加载结果。
 *
 * 内嵌字幕与文件字幕走的是**同一套状态**（[SubtitleLoadState]），而不是另开一条
 * 渲染路径：显示模式、双语、翻译、导出、样式全都以 `document` 为输入。
 * 另走一条路的代价是每一个新特性都要实现两遍，而且两遍会慢慢不一致。
 *
 * ## 两层来源
 *
 * - **手选**（[SubtitleSelection.Embedded]）：用户明确点了这条，就是它。
 * - **自动兑底**：用户在「自动」上、外挂一条也没挂上、而内核已经选了一条内嵌轨，
 *   才用它。外挂优先是刻意的——两个都自动挂上会让屏幕同时出现两份字幕，
 *   而它们的时间轴还可能不一样。
 */
internal fun SubtitleLoadState.withEmbedded(
    autoTrack: MspTrackInfo?,
    cues: List<SubtitleCue>,
    mediaUri: String?,
): SubtitleLoadState {
    val manual = embeddedTrack
    val chosen = manual ?: autoTrack?.takeIf {
        autoSelected && attached == null && document == null && phase == SubtitlePhase.READY
    } ?: return this

    // 一行都还没读到（播放头没走到有台词的地方）。手选的那条要把状态定下来，
    // 否则面板里看不到「已经切到内嵌轨了」；自动兑底那条则什么都不改——
    // 没读到东西时按兵不动，才能在外挂字幕稍后加载完成时不再变。
    if (cues.isEmpty() && manual == null) return this

    val document = embeddedDocumentOf(chosen, cues, mediaUri)
    return copy(
        document = document,
        embeddedTrack = chosen,
        attached = null,
        hasTranslation = document.hasTranslation(),
        // 自动兑底上内嵌轨之后，「这个目录里没有能用的字幕」就是过时的结论了：
        // 屏幕上明明有字幕，面板里却写着找不到，用户会去改字幕文件名。
        issue = null,
    )
}

/** 内嵌字幕轨 → [SubtitleDocument]。`cueCount` 跟着 cues 走：Media3 不给总数。 */
internal fun embeddedDocumentOf(
    track: MspTrackInfo,
    cues: List<SubtitleCue>,
    mediaUri: String?,
): SubtitleDocument = SubtitleDocument(
    track = SubtitleTrack(
        id = track.id,
        origin = SubtitleOrigin.EMBEDDED,
        // 用 subtitleFormat() 而不是 subtitleFormatOf(track.mimeType)：内嵌文本轨的
        // MIME 是 Media3 的 cue 包，真实格式在 `codecs` 里（见 MspTrackInfo）。
        format = track.subtitleFormat(),
        languageTag = track.language,
        label = track.label ?: track.language,
        // 内嵌轨的「位置」就是片源本身，没有任何单独的文件。
        sourceUri = mediaUri,
        embeddedTrackIndex = track.indexInGroup,
        isDefault = track.isDefault,
        isForced = track.isForced,
        cueCount = cues.size,
    ),
    cues = cues,
)
