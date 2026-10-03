package com.multisuperplayer.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInstaller
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.describeAsrFailure
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.ApiKeyStore
import com.multisuperplayer.core.data.settings.AsrJob
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.settings.SubtitleBottomMargin
import com.multisuperplayer.core.data.settings.SubtitleLineSpacing
import com.multisuperplayer.core.data.settings.SubtitleOutline
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleStyle
import com.multisuperplayer.core.data.settings.SubtitleTextSize
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.data.settings.assembleJob
import com.multisuperplayer.core.data.subtitle.AsrSubtitleGenerator
import com.multisuperplayer.core.data.subtitle.SubtitleExportWriter
import com.multisuperplayer.core.data.subtitle.SubtitleLoadResult
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.data.subtitle.SubtitleScan
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.bestAutoMatch
import com.multisuperplayer.core.data.subtitle.freshVersionOf
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.player.EmbeddedPreReadState
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import com.multisuperplayer.core.player.TrackSelectionController
import com.multisuperplayer.core.player.cuesOrNull
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    /** 语音识别的偏好（走哪条路、用哪条模型/服务商、从哪个源下载）。 */
    private val asrSettings: AsrSettingsRepository,
    /** 云端识别要的密钥。键名带 `asr-` 前缀，不会与翻译那边共用同一把钥匙。 */
    private val apiKeys: ApiKeyStore,
    /** 模型装好了没有。按钮文案（「生成字幕」/「下载模型并生成」）与「先下再识别」都要它。 */
    private val asrModels: AsrModelLocator,
    private val asrInstaller: AsrModelInstaller,
    /** 识别 → 序列化 → 落盘，见 core:data 的 `AsrSubtitleGenerator`。 */
    private val subtitleGenerator: AsrSubtitleGenerator,
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
     * 生成字幕（语音识别）的状态。
     *
     * 独立于 [state] 那条管线：识别进度每 250 ms 报一次，混进 [SubtitleUiState]
     * 会让整条合成管线（含字幕文档）每 250 ms 重算一遍，而界面每 250 ms 重建一次
     * 上千行的候选列表。
     */
    private val _asrState = MutableStateFlow<AsrUiState>(
        // 设置还没读到时先按「本机 + 未安装」显示。这个初值只活到第一次读到设置，
        // 而按钮按下去时的实际判断在 generateSubtitles 里重做一遍，不依赖它。
        AsrUiState.Idle.OnDevice(model = AsrModelCatalog.byId(null), installed = false),
    )

    /** 生成字幕的状态，供选择面板显示进度 / 失败。 */
    val asrState: StateFlow<AsrUiState> = _asrState.asStateFlow()

    /** 正在跑的那一次生成。取消它 = 用户按了「停止」。 */
    private var generationJob: Job? = null

    /** 最近一次读到的 ASR 设置。取消之后要把状态放回 Idle，需要它。 */
    private var lastAsrSettings: AsrSettings? = null

    /**
     * 字幕时间轴微调（毫秒，正数 = 字幕晚出现）。
     *
     * 它不进加载管线（那个管线的输入是「哪条媒体 + 选了哪条字幕 + 第几次扫」）：
     * 调一格就让整条管线重跑的话，每点一下都要重扫目录。它只在 [state] 那一层合进来。
     */
    private val timelineOffset = MutableStateFlow(0L)

    /**
     * 字幕速率（千分比，`1000` = 原速）。
     *
     * 和 [timelineOffset] 是**两件事**：偏移是整体平移（全片每一句都早/晚同样多），
     * 速率是**比例缩放**（越到后面偏得越多）。片源和字幕的帧率对不上时只有速率能修——
     * 那时用偏移把开头调准就一定会把结尾调错。
     *
     * 用整数千分比而不是 `Float`：一是和 [formatSubtitleOffset] 同一个理由
     * （浮点在显示和相等判断上都会咬人），二是「1.02×」这种值本来就只有三位
     * 有效数字，`Float` 只是在给二进制误差留位置。
     */
    private val subtitleRate = MutableStateFlow(SUBTITLE_RATE_BASE_PERMILLE)

    /** [state] 那一层的两个「纠偏」参数。合成一个小对象，`combine` 才只占一格。 */
    private data class SyncTuning(val offsetMs: Long, val ratePermille: Int)

    /** 偏移 + 速率。两者都是「本次播放」的状态，都不落设置。 */
    private val syncTuning: Flow<SyncTuning> =
        combine(timelineOffset, subtitleRate) { offsetMs, ratePermille ->
            SyncTuning(offsetMs, ratePermille)
        }

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
     *
     * ## 轨道清单也必须是一路输入，不能只读 `.value`
     *
     * 「内核已经选了一条文本轨、但第一句台词还没到」那一段里 `embeddedSubtitle`
     * 一次都不会发（cues 还是空的），只有 `tracks.tracks` 会发。要是这里只订阅 cues、
     * 顺手在回调里读一次 `tracks.tracks.value`，认领就要等到第一句台词到达才发生——
     * 而那正好是面板自相矛盾的那几秒（见 [withEmbedded] 的 KDoc）。
     */
    private val resolvedLoadState: StateFlow<SubtitleLoadState> = combine(
        loadState,
        tracks.embeddedSubtitle,
        tracks.tracks,
        tracks.embeddedPreRead,
    ) { load, embedded, trackList, preRead ->
        load.withEmbedded(
            // 「自动」时用内核**实际选中**的那条，而不是自己再挑一遍。
            //
            // 挑轨这件事一共只有两个地方会做，而且顺序固定：先是内核
            // （`init` 里设的 `setPreferredTextLanguages` + `setSelectUndeterminedTextLanguage`），
            // 内核一条都没选时才是 `autoSelectTextTrack` 那套保守规则。
            // 字幕层再算第三遍就会出现三份规则、三个结果。
            autoTrack = trackList.firstSelectedTextTrack(),
            cues = embedded.cues,
            mediaUri = entry.value?.uri,
            // 整轨预读有了结果就整表替换流式那批（见 withEmbedded 的 KDoc）。
            preRead = preRead,
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
        syncTuning,
    ) { load, settings, texts, trackList, tuning ->
        val merged = load.document?.let { texts.appliedTo(it, load.translationToken()) }
        resolveSubtitleState(
            load = if (merged == null) load else load.copy(
                document = merged,
                hasTranslation = merged.hasTranslation(),
            ),
            displayMode = settings.displayMode,
            embeddedTracks = trackList.embeddedTextTracks(),
            timelineOffsetMs = tuning.offsetMs,
            subtitleRatePermille = tuning.ratePermille,
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

        // 用哪条模型、装好了没有：面板上按钮的文案要它。跑着的时候不覆盖状态，
        // 否则会把进度抹成「未安装」。
        viewModelScope.launch {
            asrSettings.settings.collect { settings ->
                lastAsrSettings = settings
                if (_asrState.value is AsrUiState.Idle) {
                    _asrState.value = idleAsrState()
                }
            }
        }
    }

    /**
     * 「没在跑」的状态。模型没装时界面据此把按钮换成「下载模型并生成（体积）」。
     *
     * 云端那条路不看模型：它没有「要下多少」这回事，但要把**服务商名字**交给界面，
     * 因为那段隐私提示不能只写「会上传」，得写上传给谁。
     */
    private fun idleAsrState(): AsrUiState.Idle {
        // 设置还没读到（第一帧）：按本机显示。不能拿 `lastAsrSettings` 的缺省值当
        // 真相——它可能已经把云端配好了，只是还没读上来。
        val settings = lastAsrSettings
            ?: return AsrUiState.Idle.OnDevice(model = AsrModelCatalog.byId(null), installed = false)
        return when (settings.route) {
            AsrRoute.ON_DEVICE ->
                AsrUiState.Idle.OnDevice(settings.model, installed = asrModels.isReady(settings.model))

            AsrRoute.CLOUD ->
                AsrUiState.Idle.Cloud(settings.cloudService, addressReady = settings.cloudAddressLooksValid)
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
        // 速率同理，而且更隐蔽：`1.04×` 是「这一份字幕的帧率和片子对不上」这件事
        // 被记成了一个数字，换一部片子后它没有任何意义，只会让字幕慢慢飘走。
        subtitleRate.value = SUBTITLE_RATE_BASE_PERMILLE
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

    // ------------------------------------------------------- 生成字幕（语音识别）

    /**
     * 生成字幕：模型没下好就先下，然后识别、落盘。
     *
     * ## 为什么下载也在这里做
     *
     * 面板上就一个按钮。要用户先去设置页把模型下好再回来，等于告诉他「这个功能现在
     * 不能用，但你不知道该去哪」。代价是必须在按下之前把体积写清楚（按钮文案带模型
     * 大小），否则就是在用户没同意的情况下用流量下几十上百 MB。
     *
     * 云端那条路不适用上面这段（没有模型可下），它的代价换成另一条：**整段音频离开
     * 这台设备**。所以隐私提示必须在按下去之前就出现在按钮旁边（见 [AsrSection]），
     * 而不是等传完了再说，也不是等用户自己去设置页发现。
     *
     * ## 为什么生成完不直接切到新字幕
     *
     * 自动挑选会按排序规则决定（匹配分、来源优先级，见 `SubtitleSource`）：已经有
     * 一份对得上的外挂字幕时，把一个刚生成的字幕顶上去就是把用户手里更好的东西换走。
     * 这里只让重扫把新字幕带进候选列表，选不选是排序规则和用户的事。
     */
    fun generateSubtitles() {
        // 识别要几分钟，连点两下就会跑两次（第二次还会把第一次的进度覆盖掉）。
        if (generationJob?.isActive == true) return
        val media = entry.value ?: return

        generationJob = viewModelScope.launch {
            // 进度回调可能在取消之后才被执行到（取消是在下载/解码循环里被发现的），
            // 用这个作用域判一下，免得进度盖掉已经放回去的 Idle。
            val scope = this
            try {
                val settings = lastAsrSettings
                    ?: asrSettings.settings.first().also { lastAsrSettings = it }
                // 先把「这一次用哪条路」冻成参数（见 AsrJob 的 KDoc）：中途改设置
                // 不能把同一份结果交给两个引擎算。密钥没填时也会在这里就失败。
                val job = assembleJob(settings, apiKeys)

                // 下模型只对本机有意义：云端没有模型可下，也就不该让用户看到
                // 「正在下载 78.1 MB」。
                if (job is AsrJob.OnDevice && !asrModels.isReady(job.model)) {
                    _asrState.value = AsrUiState.Downloading(job.model)
                    asrInstaller.install(job.model, settings.baseUrl) { progress ->
                        if (scope.isActive) _asrState.value = AsrUiState.Downloading(job.model, progress)
                    }
                }

                _asrState.value = AsrUiState.Transcribing()
                val generated = subtitleGenerator.generate(media.uri, job) { progress ->
                    if (scope.isActive) _asrState.value = AsrUiState.Transcribing(progress)
                }
                MspLog.i(TAG) { "生成字幕完成：${generated.cueCount} 条" }
                // 重扫：新字幕随后会出现在候选列表里（不一定被自动挂上，理由见上）。
                revision.value += 1
                _asrState.value = idleAsrState()
            } catch (cancelled: CancellationException) {
                // 取消不是失败：翻成「识别失败」会让用户以为白等了，而其实是他自己停的。
                throw cancelled
            } catch (failure: Throwable) {
                MspLog.w(TAG, failure) { "生成字幕失败" }
                _asrState.value = AsrUiState.Failed(failure.describeAsrFailure())
            }
        }
    }

    /**
     * 停止正在跑的那一次生成（下模型或识别）。
     *
     * 旧的字幕不会被动：中途停下只损失算力，不该让用户连原来那份也没了。
     */
    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _asrState.value = idleAsrState()
    }

    /** 关掉失败提示（它不自动消失，用户可能正盯着进度条那条位置看）。 */
    fun dismissAsrFailure() {
        if (_asrState.value is AsrUiState.Failed) _asrState.value = idleAsrState()
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

    /**
     * 字幕速率调一档（千分比）。正数 = 让字幕更快地「跟上」，即把时间轴向后拉。
     *
     * 夹在 [SUBTITLE_RATE_BASE_PERMILLE] ± [SUBTITLE_RATE_LIMIT_PERMILLE] 之间，
     * 理由同 [nudgeTimelineOffset]：这个旋钮是用来修「差了一点点」的，不是用来放幻灯片的。
     */
    fun nudgeSubtitleRate(deltaPermille: Int) {
        setSubtitleRate(subtitleRate.value + deltaPermille)
    }

    /**
     * 直接跳到某个速率（固定档位用）。
     *
     * 档位里本来就含 [SUBTITLE_RATE_BASE_PERMILLE]（`1.00×`），所以**没有**单独的
     * 「归零」入口：同一段里出现两个叫归零的按钮，会让人以为按它会连时间轴偏移一起清掉。
     */
    fun setSubtitleRate(permille: Int) {
        subtitleRate.value = clampSubtitleRate(permille)
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
            //
            // 但**元数据要用这一次扫描的**：手选定下的是「哪一条文件」，不是那份文件
            // 当时的体积。旧对象带着旧体积，而体积参与解析缓存的命中判定
            // （见 ParsedSubtitleCache），沿用旧对象就等于「手选过的那条字幕以后
            // 永远读不到新内容」——重新生成、外部改文件，界面都还是第一次那份解析结果。
            is SubtitleSelection.Source -> sources.freshVersionOf(current.source)
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

/** 日志标签。生成字幕跑在设备上，只能靠日志确认「存下了没有」。 */
private const val TAG = "SubtitleViewModel"

/** 原速（千分比）。`1_000` = `1.00×`。 */
internal const val SUBTITLE_RATE_BASE_PERMILLE = 1_000

/**
 * 速率的上下限（相对原速的千分比）：`0.90× ~ 1.10×`。
 *
 * 和偏移的 ±10 秒同一个理由：它修的是「字幕和片源差了一点点」。放到 2× 那种幅度上，
 * 用户一旦挂错了字幕（比如拿了另一集的那份），会把整片拖成幻灯片，
 * 而看不出真正的问题在挂错了文件。
 *
 * ±10% 刚好盖住真正会发生的那几种错配：23.976↔25 帧（4.27%）、24↔25（4.17%）、
 * PAL 加速（4%）。再大就不是帧率问题，而是挂错了。
 */
internal const val SUBTITLE_RATE_LIMIT_PERMILLE = 100

/**
 * 速率的固定档位（千分比）。一次点选，不用连按。
 *
 * 只给「整百分点」而不给 0.1% 级：档位是用来**一眼选**的（4% 那几种帧率错配都是
 * 整百分点），而 0.1% 级的贴合交给步长按钮。
 */
internal val SUBTITLE_RATE_PRESETS_PERMILLE = listOf(960, 980, 1_000, 1_020, 1_040)

/**
 * 速率的累加步长（千分比）。±1% 拉近，±0.1% 贴合。
 *
 * 两档而不是一档，理由同 `SUBTITLE_SYNC_STEPS`：只留一档必然有一半人用不顺——
 * 嫌粗的人会以为这个功能没用（按一下跳太多），嫌细的人要按十几次。
 */
internal val SUBTITLE_RATE_STEPS_PERMILLE = listOf(-10, -1, 1, 10)

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
    /**
     * 内嵌字幕的**整轨预读**状态（`core:player` 的 `EmbeddedPreReadState`）。
     *
     * 只有内嵌这条路上才有意义，别的一律是 `Off`。放进这里而不是让界面层自己
     * 再订阅一个流：预读的结果直接决定 [document] 里装的是**整表**还是流式的那几行，
     * 两者必须同步就能看到（分开订阅会出现「面板还在说正在预读、字幕已经按整表
     * 画出来了」这种自相矛盾的一帧）。
     */
    val embeddedPreRead: EmbeddedPreReadState = EmbeddedPreReadState.Off,
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
     * 内嵌字幕的整轨预读状态，供面板说「正在预读字幕」/「预读失败」。
     *
     * 面板上那一行小字（`embeddedStatusDetails`）靠它才能说清「现在什么都没有」
     * 到底是「正在读」还是「读不了」——而这两件事用户的动作完全不同：
     * 前者等，后者去检查字幕。
     */
    val embeddedPreRead: EmbeddedPreReadState = EmbeddedPreReadState.Off,
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
     * 字幕速率（千分比，`1000` = 原速）。
     *
     * 与 [timelineOffsetMs] 是两件事：那个是整体平移（全片每句都早/晚同样多），
     * 这个是**比例**缩放（越到后面偏得越多）。片源与字幕的帧率对不上时只有它能修。
     *
     * 同样是**本次播放的**状态，不落设置、换条目归零（见 `SubtitleViewModel.bindEntry`）：
     * 把「这份字幕是 23.976 帧压出来的」这件事记成一个全局数字，
     * 下一部片子会静默地一直飘。
     */
    val subtitleRatePermille: Int = SUBTITLE_RATE_BASE_PERMILLE,
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
    subtitleRatePermille: Int = SUBTITLE_RATE_BASE_PERMILLE,
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
        embeddedPreRead = load.embeddedPreRead,
        timelineOffsetMs = timelineOffsetMs,
        subtitleRatePermille = subtitleRatePermille,
        style = style,
    )
}

/**
 * 字幕层该拿哪个时刻去查 cue。
 *
 * ```
 * t' = t × 速率 − 偏移
 * ```
 *
 * `偏移 > 0` 表示「字幕晚出现」，所以要把查询时刻往回推：t 时刻该显示的是
 * 原本 `t - 偏移` 那一刻的台词。速率是**比例**缩放，方向词和偏移一致
 * （字幕越到后面越早出现就调小，越晚出现就调大），但只能修「越到后面越偏」——
 * 全片固定早/晚若干秒那种用偏移，速率调不出来。
 *
 * ## 为什么先缩放再平移
 *
 * 反过来（先减偏移再乘速率）会把用户已经调好的偏移量一起放大：调成 +2 秒之后
 * 再动速率，那个 2 秒会变成 2.08 秒——两个旋钮互相干扰，用户只能来回试。
 * 先缩放再平移的话「偏移就是偏移」，与速率无关。这也正是 nextlib 里
 * `syncSpeedMultiplier` 的算法（它的 `getOffsetAdjustedPositionUs` 就是
 * `pos * mult - offsetMs * 1000`）。
 *
 * 抽成一个函数（而不是在两处渲染里各写一遍减号）是为了让符号方向只有一个地方
 * 可以写错，并且能钉在单测里；整数运算且只在最后截断，
 * 所以「几十小时的位置 × 1.1」也不会溢出。
 */
internal fun subtitleCuePosition(
    positionMs: Long,
    timelineOffsetMs: Long,
    ratePermille: Int = SUBTITLE_RATE_BASE_PERMILLE,
): Long = positionMs * ratePermille / SUBTITLE_RATE_BASE_PERMILLE - timelineOffsetMs

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
 * 把速率钳进允许区间（[SUBTITLE_RATE_BASE_PERMILLE] ± [SUBTITLE_RATE_LIMIT_PERMILLE]）。
 *
 * 抽成纯函数是为了能钉住边界。这个区间就是「别把这个旋钮当搜索条」那条护栏：
 * 一首 4 分钟的歌配上 `2.0×` 会让整屏台词全挤在一起，用户看到的是「字幕坏了」
 * 而不是「我把速率调歪了」。
 */
internal fun clampSubtitleRate(permille: Int): Int = permille.coerceIn(
    SUBTITLE_RATE_BASE_PERMILLE - SUBTITLE_RATE_LIMIT_PERMILLE,
    SUBTITLE_RATE_BASE_PERMILLE + SUBTITLE_RATE_LIMIT_PERMILLE,
)

/**
 * 速率的显示文本：`1.02` / `0.96` / `1.001`（`×` 那半截在资源里）。
 *
 * 纯整数运算，理由同 [formatSubtitleOffset]：`String.format("%.2f")` 在小数点用
 * 逗号的地区会变成 `1,02`，而速率不是一个人写给另一个人看的字段。
 *
 * 精度跟着值走：整百分点（`1020`）显示两位，细调出来的（`1001`）才显示第三位。
 * 固定两位的话「+0.1%」这一步看起来什么都没发生——按钮按了、数字没变。
 */
internal fun formatSubtitleRate(permille: Int): String {
    val whole = permille / SUBTITLE_RATE_BASE_PERMILLE
    val frac = permille % SUBTITLE_RATE_BASE_PERMILLE
    val twoDigits = frac % 10 == 0
    val fracText = if (twoDigits) frac / 10 else frac
    val width = if (twoDigits) 2 else 3
    return "$whole.${fracText.toString().padStart(width, '0')}"
}

/**
 * 步长按钮的文本：`+1` / `-0.1`（`%` 那半截在资源里）。
 *
 * 步长是**千分比**，而按钮上要写**百分比**：`10‰ = 1%`。两套单位混用正是最容易
 * 写错的地方，所以换算只在这里做一次。
 */
internal fun formatSubtitleRateStep(deltaPermille: Int): String {
    val sign = if (deltaPermille >= 0) "+" else "-"
    val abs = kotlin.math.abs(deltaPermille)
    val whole = abs / 10
    val frac = abs % 10
    return if (frac == 0) "$sign$whole" else "$sign$whole.$frac"
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
 *
 * ## 认领发生在**第一句台词之前**（这里曾经判错过）
 *
 * 内嵌轨是边播边读的：容器里那几条轨在轨道清单解析出来那一刻就知道了，而第一句
 * 台词要等播放头走到有字幕的地方才到。这里原来写的是「一行都没读到就按兵不动」，
 * 理由是「免得外挂字幕稍后加载完成时状态先变一下」。那个理由站不住：上面那个
 * `takeIf` 已经要求 `attached == null && document == null`——外挂那一路一旦有结果
 * 就再也走不到这里，不存在「先认了内嵌、外挂后来又要顶掉它」的竞争。
 *
 * 而按兵不动的代价是**实测过的一场自相矛盾**（`multi.mkv`，40 秒片段、字幕从 23 秒起，
 * 中间约 **4.8 秒**）：扫描算出来的「文件夹里没有和片名同名的字幕」还挂在面板上，
 * 旁边「片源自带的字幕」分区里已经列着两条可选轨，而内核**已经选了其中一条**
 * 在等第一句台词——面板上没有任何地方说出这件事，用户会去改字幕文件名。
 *
 * 所以现在无条件认领。认领之后面板从头到尾只说一件事：挂的是哪条轨
 * （[embeddedTrack]），「这个目录里没有能用的字幕」是过时结论（[issue] 清掉），
 * 「还没读到台词」由 `document == null` 自己表达（界面那一层把它说成一句话）。
 *
 * 反过来说，`document == null` 是这条路径上**合法**的中间态，不是失败：
 * [SubtitleLoadState] 里 `document != null` 就等价于「至少读到一行」。手选那条
 * 也一样（原来手选时会造一个空文档，「已经挂上但一句都没有」和「挂上了一份空字幕」
 * 就成了两个看起来一样、实际不一样的状态）。
 */
internal fun SubtitleLoadState.withEmbedded(
    autoTrack: MspTrackInfo?,
    cues: List<SubtitleCue>,
    mediaUri: String?,
    preRead: EmbeddedPreReadState = EmbeddedPreReadState.Off,
): SubtitleLoadState {
    val manual = embeddedTrack
    val chosen = manual ?: autoTrack?.takeIf {
        autoSelected && attached == null && document == null && phase == SubtitlePhase.READY
    } ?: return this

    // ## 整轨预读有结果就用它整表替换流式那批
    //
    // 流式表（[cues]）只装「已经播过的段落」，而且最后一条 cue 的结束时间是
    // `OPEN_CUE_END_MS`（= `Long.MAX_VALUE`，因为它只能等到下一批回调才知道自己什么时候结束）。
    // 速率是按**表**换算查询位置的（见 `subtitleCuePosition`），拿一份越播越少、
    // 末尾还悬在无穷远的表去换算，表现就是**调快没反应、调慢才有反应**——
    // 整轨预读就是为这件事做的。
    //
    // preRead 没有结果时照旧用流式：它是「整表」而不是「唯一的路」，
    // 预读失败/不适用（网络流、位图字幕）时不能演成没有字幕。
    val effectiveCues = preRead.cuesOrNull ?: cues

    // 还没读到任何一行：先把**轨**认下来，文档等第一句台词到了再造。
    val document = if (effectiveCues.isEmpty()) null else embeddedDocumentOf(chosen, effectiveCues, mediaUri)
    return copy(
        document = document,
        embeddedTrack = chosen,
        embeddedPreRead = preRead,
        attached = null,
        hasTranslation = document?.hasTranslation() ?: false,
        // 挂了内嵌轨之后，「这个目录里没有能用的字幕」就是过时的结论了：屏幕上明明
        // 有字幕，面板里却写着找不到，用户会去改字幕文件名。
        //
        // 这句清空必须对**两种来源**都生效。它原来写在 `copy(...)` 里，而上面那句
        // 「没读到就 return this」让手选那条路根本执行不到这里——于是手选内嵌轨时，
        // 扫描算出的 `NoMatch` / `NoSubtitles` 会一直挂在面板上不下来。
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
