package com.multisuperplayer.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.browser.StorageAccess
import com.multisuperplayer.core.data.settings.AppLanguage
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import com.multisuperplayer.core.data.settings.LocaleSettingsRepository
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleBottomMargin
import com.multisuperplayer.core.data.settings.SubtitleLineSpacing
import com.multisuperplayer.core.data.settings.SubtitleOutline
import com.multisuperplayer.core.data.settings.SubtitleSettings
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleTextSize
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SoftwareDecoderSupport
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.ConnectivityResult
import com.multisuperplayer.core.translate.FailureText
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.ModelListResult
import com.multisuperplayer.core.translate.TranslationProbe
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.describeTranslationFailure
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsViewModel"

/**
 * 「测试连接」的界面状态。
 *
 * 成功时把模型真正翻出来的那一句带回来，而不是一句「连接成功」：
 * 只报连接成功的话，「密钥对、地址对、模型名写错」这种最常见的配置事故会得到
 * 一个绿色的对钩，而用户回到播放页依旧什么都翻不出来。
 */
data class ConnectionTestState(
    val running: Boolean = false,
    val sample: String? = null,
    val translated: String? = null,
    val failure: FailureText? = null,
) {
    val hasResult: Boolean get() = translated != null || failure != null
}

/** 「拉取模型列表」的界面状态。失败只是拉不到列表，**不代表配置不对**（很多自建服务不实现这个端点）。 */
data class ModelListState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val failure: FailureText? = null,
)

/**
 * 入口页「语音识别」那一行的两个数。
 *
 * 放在同一个 data class 里、一次更新，是因为它们总是一起显示：分开成两个 Flow
 * 会在换完模型的那几帧里拼出「中文离线 · 已下载」——而那个「已下载」说的是**上一条**模型。
 */
data class AsrEntryState(
    val settings: AsrSettings = AsrSettings(),
    val status: AsrModelStatus = AsrModelStatus.Absent,
) {
    val model: AsrModelInfo get() = settings.model
}

/**
 * 设置页的状态。
 *
 * 这里**只装载当前内核已经会读的设置项**。一个没人读的开关，用户拨它只会得到
 * 一个「看起来生效了但什么都没发生」的界面，比没有这个开关更糟。
 *
 * v0.5.15 之前这类注释里举的例子是「字幕字号」——它在 v0.5.16 有了消费者
 * （渲染层按 [SubtitleSettings.style] 排版字幕），所以现在长在这里。
 */
class SettingsViewModel(
    private val themeSettings: ThemeSettingsRepository,
    private val translationSettings: TranslationSettingsRepository,
    private val subtitleStyleSettings: SubtitleSettingsRepository,
    private val playbackSettingsRepository: PlaybackSettingsRepository,
    private val localeSettings: LocaleSettingsRepository,
    private val softwareDecoders: SoftwareDecoderSupport,
    private val probe: TranslationProbe,
    private val storage: StorageAccess,
    private val dispatchers: DispatcherProvider,
    /**
     * 语音识别的两条依赖。
     *
     * 入口页那一行要显示「用哪条模型、下了没有」，所以这里需要设置存储和文件定位器。
     * **不需要** [com.multisuperplayer.core.asr.AsrModelInstaller]：下载只发生在
     * 「语音识别」子页里（`AsrSettingsViewModel`），入口页不碰网络。
     */
    private val asrSettingsRepository: AsrSettingsRepository,
    private val asrModelLocator: AsrModelLocator,
    /**
     * 构建信息。设置入口页的「关于」那一行要显示版本号。
     *
     * 由 `:app` 的 `appModule` 提供（`BuildConfig` 是每个模块各自生成的，
     * 只有 `:app` 那一份带着 git 信息）。它是纯数据、构造零成本，
     * 所以放在这个**在应用启动时就会被创建**的 ViewModel 里没有代价。
     * 反例是设备信息采集（要查 WindowManager），那个只在关于页自己的
     * ViewModel 里做，见 `AboutViewModel`。
     */
    val buildInfo: AppBuildInfo = AppBuildInfo.Unknown,
) : ViewModel() {

    /**
     * 有没有「所有文件访问」。这是一个**系统设置项**，没有回调：用户去系统设置里
     * 开完再回来，进程还活着，我们收不到任何通知。所以界面必须在外层
     * `ON_RESUME` 时调 [refreshFileAccess] 重新问一次。
     *
     * 构造时同步读一次（`Environment.isExternalStorageManager()` 是本地查询，不碰盘）。
     * 初值不能省：这一行的副标题就是当前状态，先显示「未开启」再跳成「已开启」
     * 会让用户以为刚才自己看错了。
     */
    private val fileAccessState = MutableStateFlow(storage.hasAllFilesAccess())
    val fileAccessGranted: StateFlow<Boolean> = fileAccessState.asStateFlow()

    /**
     * 本机有没有这个概念。API < 30 上系统设置里根本没有这一页，界面要把这一行置灰
     * （见 `SettingsScreen`），**不能**给一个点了没反应的入口。
     */
    val fileAccessSupported: Boolean = storage.supported()

    fun refreshFileAccess() {
        fileAccessState.value = storage.hasAllFilesAccess()
    }

    /**
     * 去系统设置页。先试本应用的那一页，系统没有时才退到总开关列表——
     * 这个筛选在 `StorageAccess.preferredSettingsIntent()` 里，因为它需要
     * `PackageManager`，不属于 ViewModel 的职责。
     */
    fun fileAccessIntent(): Intent = storage.preferredSettingsIntent()

    /**
     * 用 `Eagerly` 而不是 `WhileSubscribed`：主题要在界面出现之前就位，
     * 否则会先闪一下默认主题再跳到用户选的主题。
     *
     * 初值用 `ThemeSettings()`（全空）而不是去读盘等第一帧：全空恰好就是
     * 「全部用 UI 层默认值」，和「用户没设置过」等价，所以第一帧不会跳。
     */
    val theme: StateFlow<ThemeSettings> = themeSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())

    fun selectBaseTheme(theme: MspBaseTheme) = persist("主题基底=${theme.id}") {
        themeSettings.setBaseTheme(theme.id)
    }

    /**
     * 选强调色。
     *
     * 它同时会关掉「封面取色」和「跟随系统取色」——那两个的优先级都在强调色
     * 之上，不关掉的话，在 Android 12+ 上点这一行就是零反馈。
     * 三个键在同一个事务里写，详见 [ThemeSettingsRepository.selectAccent]。
     */
    fun selectAccent(accent: MspAccent) = persist("强调色=${accent.id}") {
        themeSettings.selectAccent(accent.id)
    }

    fun setDynamicColor(enabled: Boolean) = persist("系统取色=$enabled") {
        themeSettings.setUseDynamicColor(enabled)
    }

    fun setColorFromArtwork(enabled: Boolean) = persist("封面取色=$enabled") {
        themeSettings.setColorFromArtwork(enabled)
    }

    // ------------------------------------------------------------------ 语言

    /**
     * 当前语言。
     *
     * 初值用 [AppLanguage.DEFAULT] = 跟随系统，而不是去读盘等第一帧：
     * 「跟随系统」恰好就是「用户没设置过」这个状态的名字，所以首帧不会跳。
     *
     * 真正生效时用的**不是**这个 Flow：界面文案靠 Context 上的 Configuration，
     * 而这个 Flow 只负责把单选框勾在正确的那一项上。
     *
     * 它必须是一个会**继续发值**的 Flow，不能是「读一次就完」的快照。33 以上切语言
     * 由系统重建 Activity，而 `ViewModel` 在重建中是存活的：快照型 Flow 只在
     * `ViewModel` 第一次创建时读过一次，之后界面全变、单选框却还停在旧选择上。
     * 由 `AppLocaleStore` 维护的进程内缓存把这个补给补上了（写入和
     * `attachBaseContext` 都会推进新值）。
     */
    val language: StateFlow<AppLanguage> = localeSettings.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.DEFAULT)

    /**
     * 切语言。
     *
     * 写入之后**界面要自己重建**（低于 Android 13），否则 `Resources` 已经换成新语言
     * 而屏幕上那一屏还是旧的——症状是「点了没反应」，而重启之后又是对的。
     * 重建由 UI 层做（`Activity.recreate()` 是 Activity 的事情），
     * 需不需要重建读 [LocaleSettingsRepository.requiresManualRecreate]。
     */
    fun setLanguage(language: AppLanguage) = persist("语言=${language.tag}") {
        localeSettings.setLanguage(language)
    }

    /** 33 以上由系统重建应用，界面不要再自己 `recreate()`（那会闪两下）。 */
    val localeRequiresManualRecreate: Boolean get() = localeSettings.requiresManualRecreate

    // ------------------------------------------------------------------ 播放内核

    /**
     * 播放偏好的持久化值。
     *
     * `Eagerly` + 全空初值，理由同主题：首帧不能先闪一个「关」，
     * 否则用户会看到开关自己弹一下。
     */
    val playback: StateFlow<PlaybackSettings> = playbackSettingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())

    /**
     * 本安装包里到底有没有 FFmpeg。
     *
     * 界面**必须**用到它：[NextlibSoftwareDecoderSupport] 在 CPU 架构不受支持时
     * 会如实报「不可用」，而那时 `setForceSoftwareDecoding` 是个空动作。
     * 不把这件事说出来，用户拨开关只会得到一个「看起来生效了但什么都没发生」的界面
     * ——正是这个 ViewModel 的类注释里点名要避免的东西。
     */
    val softwareDecodingAvailable: Boolean = softwareDecoders.available

    fun setForceSoftwareDecoding(enabled: Boolean) = persist("强制软件解码=$enabled") {
        playbackSettingsRepository.setForceSoftwareDecoding(enabled)
    }

    /**
     * 默认画面比例。
     *
     * 这里改的是**下一部片子**用什么比例。正在播的那一部不受影响：
     * 播放页的面板改的是「这一部」，两者不应该互相干扰（见 `PlaybackSettings.aspectRatioMode`）。
     * 所以走 [PlaybackSettingsRepository] 而不是去碰正在跑的控制器。
     */
    fun setAspectRatioMode(mode: AspectRatioMode) = persist("默认画面比例=${mode.id}") {
        playbackSettingsRepository.setAspectRatioMode(mode)
    }

    /**
     * 默认倍速。同样只影响之后新打开的文件。
     *
     * 值先夹到档位表里（[PlaybackSpeedOptions.nearestPreset]）：写进去的应该是一个
     * 真实存在的档位，否则设置页将来改档位表时，这里会留下一个界面上根本选不中的值。
     */
    fun setSpeed(speed: Float) = persist("默认倍速") {
        playbackSettingsRepository.setSpeed(PlaybackSpeedOptions.nearestPreset(speed))
    }

    /**
     * 长按画面时的临时倍速。
     *
     * 和 [setSpeed] 一样先把值夹进档位表：[SpeedBoostOptions.normalize] 做的是
     * 「按档位表收敛」，顺手也把 null / NaN / 小于等于 0 都收成默认值——设置页
     * 只会传真实档位，但这个方法将来可能被别的入口（恢复出厂、迁移旧设置）调到，
     * 夹一次比在这里相信调用方便宜。
     */
    fun setBoostSpeed(speed: Float) = persist("长按倍速") {
        playbackSettingsRepository.setBoostSpeed(SpeedBoostOptions.normalize(speed))
    }

    fun setRememberPosition(enabled: Boolean) = persist("记住播放位置=$enabled") {
        playbackSettingsRepository.setRememberPosition(enabled)
    }

    fun setRecordRecentPlays(enabled: Boolean) = persist("记录最近播放=$enabled") {
        playbackSettingsRepository.setRecordRecentPlays(enabled)
    }

    // ------------------------------------------------------------------ 字幕翻译

    /**
     * 用 `Eagerly`：设置页一打开就要显示「当前服务商/模型」和「还差什么」，
     * 用 `WhileSubscribed` 的话首帧是空的，会先闪一个「未配置」。
     */
    val translation: StateFlow<TranslationSettings> = translationSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TranslationSettings())

    private val mutableConnectionTest = MutableStateFlow(ConnectionTestState())

    val connectionTest: StateFlow<ConnectionTestState> = mutableConnectionTest.asStateFlow()

    private val mutableModelList = MutableStateFlow(ModelListState())

    val modelList: StateFlow<ModelListState> = mutableModelList.asStateFlow()

    fun selectProvider(providerId: String) {
        // 换服务商会让地址/模型/密钥归属全变，上一次的探测结论也就作废了。
        // 不清掉的话，用户在 A 家测出「成功」，切到 B 家仍看到那个绿对钩。
        mutableConnectionTest.value = ConnectionTestState()
        mutableModelList.value = ModelListState()
        persist("服务商=$providerId") { translationSettings.setProvider(providerId) }
    }

    fun setBaseUrl(baseUrl: String) = persist("翻译地址") { translationSettings.setBaseUrl(baseUrl) }

    fun setModel(model: String) = persist("翻译模型=$model") { translationSettings.setModel(model) }

    fun setTarget(target: TranslationTarget) = persist("目标语言=${target.code}") {
        translationSettings.setTarget(target)
    }

    fun setAutoTranslate(enabled: Boolean) = persist("自动翻译=$enabled") {
        translationSettings.setAutoTranslate(enabled)
    }

    fun setGlossary(glossary: Glossary) = persist("术语表（${glossary.size} 条）") {
        translationSettings.setGlossary(glossary)
    }

    // ------------------------------------------------------------------ 字幕样式

    /**
     * 字幕外观。和翻译设置同属「字幕与翻译」那一页。
     *
     * `Eagerly` + 全默认初值，理由同主题/播放：首帧不能先闪一个错的档位，
     * 否则用户会看到「标准」跳成自己选的那个。
     *
     * 这里改的和播放页字幕面板里改的是**同一个值**（同一个仓库、同一份全局设置）。
     * 两边都只是入口：面板是「一边看一边改」，这里是「先把偏好配好」。
     */
    val subtitle: StateFlow<SubtitleSettings> = subtitleStyleSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleSettings())

    /**
     * 四个档位。默认值用枚举自己的 `DEFAULT` 时不要在这里写死字符串——
     * 写进日志的是枚举名，跟杆位对不上的话日志会误导人。
     */
    fun setSubtitleTextSize(size: SubtitleTextSize) = persist("字幕字号=${size.name}") {
        subtitleStyleSettings.setTextSize(size)
    }

    fun setSubtitleLineSpacing(spacing: SubtitleLineSpacing) = persist("字幕行距=${spacing.name}") {
        subtitleStyleSettings.setLineSpacing(spacing)
    }

    fun setSubtitleOutline(outline: SubtitleOutline) = persist("字幕描边=${outline.name}") {
        subtitleStyleSettings.setOutline(outline)
    }

    fun setSubtitleBottomMargin(margin: SubtitleBottomMargin) = persist("字幕底部距离=${margin.name}") {
        subtitleStyleSettings.setBottomMargin(margin)
    }

    /**
     * 恢复默认样式。
     *
     * 四个键必须**一次事务**写完（[SubtitleSettingsRepository.resetStyle]），
     * 不能在这里连调四个 setter：中途失败会留下「一半默认一半自定义」的样式，
     * 而那个组合在界面上没有任何对应的档位显示。
     */
    fun resetSubtitleStyle() = persist("恢复默认字幕样式") {
        subtitleStyleSettings.resetStyle()
    }

    /**
     * 保存密钥。
     *
     * 空输入**不调用**仓库：它的语义是「不动」，界面上也不该出现一个
     * 「按下去什么都不会发生」的按钮。所以这里直接把空值挡在门外，
     * 并用返回值告诉界面「到底改成没改」。
     */
    fun saveApiKey(raw: String, onDone: (Boolean) -> Unit = {}) {
        val providerId = translation.value.providerId
        // 回到主线程再回调：`onDone` 那头是 Compose 状态，别在 IO 线程上写它。
        viewModelScope.launch {
            val stored = withContext(dispatchers.io) {
                runCatching { translationSettings.setApiKey(providerId, raw) }
                    .onFailure { error -> MspLog.w(TAG, error) { "保存密钥失败" } }
                    .getOrDefault(false)
            }
            // 密钥写完，之前的探测结论必须作废：用户刚刚改的正是被探测的东西。
            if (stored) mutableConnectionTest.value = ConnectionTestState()
            onDone(stored)
        }
    }

    fun clearApiKey() {
        val providerId = translation.value.providerId
        mutableConnectionTest.value = ConnectionTestState()
        persist("清除密钥") { translationSettings.clearApiKey(providerId) }
    }

    /**
     * 测试连接。
     *
     * 走的是**真实的翻译流程**（一篇一行字幕），不是 `GET /models`：
     * 后者只能证明地址和密钥没问题，对「模型名对不对」「这家厂商收不收这个请求形状」
     * 「推理模式有没有把输出预算吃光」一句话都不说。
     */
    fun testConnection() {
        if (mutableConnectionTest.value.running) return
        mutableConnectionTest.value = ConnectionTestState(running = true)
        viewModelScope.launch {
            val config = runCatching { translationSettings.currentConfig() }.getOrNull()
            if (config == null) {
                mutableConnectionTest.value = ConnectionTestState(
                    failure = FailureText(message = MspText.Res(R.string.msp_settings_read_failed)),
                )
                return@launch
            }

            val providerName = translation.value.provider.displayName
            mutableConnectionTest.value = when (val result = probe.test(config)) {
                is ConnectivityResult.Ok -> ConnectionTestState(
                    sample = result.sample,
                    translated = result.translated,
                )

                is ConnectivityResult.Failed -> ConnectionTestState(
                    failure = describeTranslationFailure(result.failure, providerName, config.model),
                )
            }
        }
    }

    fun fetchModels() {
        if (mutableModelList.value.loading) return
        mutableModelList.value = ModelListState(loading = true)
        viewModelScope.launch {
            val current = translation.value
            val apiKey = runCatching { translationSettings.apiKeyFor(current.providerId) }.getOrNull()
            mutableModelList.value = when (val result = probe.listModels(current.baseUrl, apiKey)) {
                is ModelListResult.Ok -> ModelListState(models = result.models)

                is ModelListResult.Failed -> ModelListState(
                    // 拉不到列表 ≠ 配置错，文案里要写清楚，否则用户会去改一个本来没错的地址。
                    failure = describeTranslationFailure(
                        result.failure,
                        current.provider.displayName,
                        current.model,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 语音识别

    private val asrEntryState = MutableStateFlow(AsrEntryState())

    /** 入口页「语音识别」那一行要的两个数。见 [AsrEntryState]。 */
    val asrEntry: StateFlow<AsrEntryState> = asrEntryState.asStateFlow()

    init {
        viewModelScope.launch {
            asrSettingsRepository.settings.collect { settings ->
                // 设置一变就重读一次磁盘。两件事（用的哪条 / 那条在不在）分开更新，
                // 会在入口页上拼出一条自相矛盾的摘要（新模型名 + 旧模型的状态）。
                asrEntryState.value = AsrEntryState(settings, readAsrStatus(settings.model))
            }
        }
    }

    /**
     * 重新读一次模型在磁盘上的状态。
     *
     * 与 [refreshFileAccess] 同一个理由，只是更弱一点：模型也**可能是在播放页的字幕面板里**
     * 下完的，那条流程完全发生在这个 ViewModel 之外，没有任何回调会通知这里。
     * 界面在外层 `ON_RESUME` 时调一次。
     */
    fun refreshAsrStatus() {
        viewModelScope.launch {
            asrEntryState.update { it.copy(status = readAsrStatus(it.settings.model)) }
        }
    }

    /**
     * 只 stat 文件长度，不校验哈希（见 `AsrModelLocator.statusOf`）。
     * 那也是几次系统调用，所以走 IO 线程——入口页是应用冷启动后第一屏，
     * 在主线程上 stat 一个 190 MB 的目录是要还的。
     */
    private suspend fun readAsrStatus(model: AsrModelInfo): AsrModelStatus =
        withContext(dispatchers.io) { asrModelLocator.statusOf(model) }

    /**
     * 写盘失败不能只吞掉——那会表现为「点了没反应」，而且**下次启动又变回去**，
     * 用户完全无从判断是没点到还是没存上。所以至少要留一条日志。
     *
     * 不弹 Toast/Snackbar：主题偏好属于低风险设置，为它打断操作不值得；
     * 真出问题（磁盘满、文件损坏）日志里能查到。
     */
    private fun persist(what: String, block: suspend () -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { block() }
                .onFailure { error ->
                    MspLog.w(TAG, error) { "保存设置失败（$what）" }
                }
        }
    }
}
