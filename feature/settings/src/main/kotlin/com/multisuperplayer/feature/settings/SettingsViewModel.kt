package com.multisuperplayer.feature.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.permissions.AppPermissions
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import com.multisuperplayer.core.data.power.KeepAliveAccess
import com.multisuperplayer.core.data.power.KeepAliveState
import com.multisuperplayer.core.data.settings.AppLanguage
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import com.multisuperplayer.core.data.settings.CustomAccent
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
import com.multisuperplayer.core.llm.LlmModelCatalog
import com.multisuperplayer.core.llm.LlmModelInfo
import com.multisuperplayer.core.llm.LlmModelLocator
import com.multisuperplayer.core.llm.LlmModelStatus
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SoftwareDecoderSupport
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.ConnectivityResult
import com.multisuperplayer.core.translate.FailureText
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.ModelListResult
import com.multisuperplayer.core.translate.TranslationCacheStats
import com.multisuperplayer.core.translate.TranslationCacheStore
import com.multisuperplayer.core.translate.TranslationProbe
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.describeTranslationFailure
import com.multisuperplayer.core.ui.theme.ArtworkAccent
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.customAccentColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
 * 「清空翻译缓存」那一行的全部状态。
 *
 * 三个字段一起更新，理由和 [AsrEntryState] 一样：分开成两个 Flow 会在清空完成的那几帧
 * 里拼出「还剩 128 条」+「清空成功」这种自相矛盾的组合。
 */
data class TranslationCacheState(
    val stats: TranslationCacheStats = TranslationCacheStats(entries = 0, bytes = 0L),
    val busy: Boolean = false,
    /**
     * 上一次清空是否失败。
     *
     * 成功时**不留任何提示**：条数自己变成 0 了，「已清空」这三个字没有额外信息。
     * 失败则相反——界面上除此之外没有任何迹象（按钮又能点、数字又没变），
     * 不说就等於静默失败。
     */
    val failed: Boolean = false,
)

/**
 * 「字幕与翻译」页上本地模型那一行要的两个数。与 [AsrEntryState] 同构，理由也一样：
 * 「用哪条模型」与「它在不在磁盘上」总是一起显示（`Qwen3 0.6B（本地） · 未下载`），
 * 分成两个 Flow 会在换完模型的那几帧里拼出「新模型名 + 上一条的状态」。
 */
data class LocalModelEntryState(
    val settings: TranslationSettings = TranslationSettings(),
    val status: LlmModelStatus = LlmModelStatus.Absent,
) {

    /**
     * 设置里存的是模型 id（它同时是目录名），显示出来是错的。
     * 认不出的 id 由 [LlmModelCatalog.byId] 兜底成默认模型。
     */
    val model: LlmModelInfo get() = LlmModelCatalog.byId(settings.model)
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
    private val permissions: AppPermissions,
    /**
     * 「后台保活」那一行要读的档位（在不在电池优化白名单里）。
     *
     * 判定全在 `KeepAliveRules` 里（纯函数、有单测），这里只拿结论——
     * 入口页不做判定，否则「什么算不受限」就有两处说法。
     * 构造零成本（一次 `getSystemService`），所以放进这个
     * **应用启动时就会被创建**的 ViewModel 里没有代价。
     */
    private val keepAlive: KeepAliveAccess,
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
     * 本地翻译模型的位置。
     *
     * 「字幕与翻译」页要显示「模型下了没有」（以后入口页那一行的摘要也会用到）。
     * **不需要** `LlmModelInstaller`：下载只发生在「本地模型」子页里
     * （`LocalModelSettingsViewModel`），这一页不碰网络、不写文件，只 stat 一下长度。
     *
     * 构造零成本（里面就一个 `File`），所以放在这个**应用启动时就会被创建**的
     * ViewModel 里没有代价。
     */
    private val localModelLocator: LlmModelLocator,
    /**
     * 译文缓存。
     *
     * 「字幕与翻译」页上多了一行「清空翻译缓存」：它是**磁盘占用**的唯一出口
     * （缓存全局共享、没有别的入口能碰到它）。这里只用得上两项能力：
     * 读一下多大（[TranslationCacheStore.stats]）、删掉它（[TranslationCacheStore.clear]）。
     * 注意它**不碰** `translation_edits/`——那是用户手动改过的译文。
     */
    private val translationCache: TranslationCacheStore,
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
     * 四项权限的当前状态。
     *
     * 入口页那一行的副标题只用得上媒体那一项（理由见 `PermissionSummaries.entry`），
     * 但这里给的是**整份**状态：权限页和入口页共用这一个 ViewModel，
     * 它们必须是同一个结论（入口说未开启、进去却是已开启，是用户最难信任的一种界面）。
     *
     * 权限是**系统里的状态**，没有回调：用户去系统设置里改完再回来，进程还活着，
     * 我们收不到任何通知。所以界面必须在外层 `ON_RESUME` 时调 [refreshPermissions]
     * 重新问一次。
     *
     * 构造时同步读一次（都是本地查询，不碰盘）。初值不能省：这一行的副标题就是
     * 当前状态，先显示「未允许」再跳成「已允许」会让用户以为刚才自己看错了。
     */
    private val permissionStateFlow = MutableStateFlow(permissions.snapshot(activity = null))
    val permissionState: StateFlow<PermissionSnapshot> = permissionStateFlow.asStateFlow()

    /**
     * 重新问一次系统。
     *
     * [activity] 用来读 `shouldShowRequestPermissionRationale`（只有 `Activity` 有），
     * 它决定「被拒过一次」和「已被永久拒绝」要不要分开说。传 `null` 只少这一层分辨，
     * 「已允许 / 未允许」的结论不受影响。
     */
    fun refreshPermissions(activity: Activity?) {
        permissionStateFlow.value = permissions.snapshot(activity)
    }

    /**
     * 「后台保活」那一行的档位。
     *
     * 和 [permissionState] 完全同一类状态：白名单是**系统里的状态**，
     * 用户在系统设置页里改完再回来，进程还活着，收不到任何通知。
     * 所以界面必须在外层 `ON_RESUME` 时调 [refreshKeepAlive] 重新问一次；
     * 少了这一次，用户申请完回来会看到入口页还说「未加入」——
     * 他会怀疑自己刚才那一步没生效。
     */
    private val keepAliveStateFlow = MutableStateFlow(keepAlive.state())
    val keepAliveState: StateFlow<KeepAliveState> = keepAliveStateFlow.asStateFlow()

    /** 重新问一次系统。理由见 [keepAliveState]。 */
    fun refreshKeepAlive() {
        keepAliveStateFlow.value = keepAlive.state()
    }

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

    /**
     * 自定义强调色推导出来的四个种子色。null = 用户没自定义过（用预设）。
     *
     * 界面**不**读它：[AppearanceSettingsScreen] 拿的是
     * [ThemeSettings.customAccent] 那组滑块位置，预览由自己算（要预览的正是
     * 「还没保存的那一版」）。真正需要种子色的是 `MspTheme`，而它在整棵树的
     * 最外层，由 App 那一层读这个 Flow 传下去。
     *
     * 推导只发生在写入之后（松手一次算一次），所以 `Eagerly` + `map` 足够；
     * 拖动过程中的那些中间值根本不落盘。
     */
    val customAccentSeeds: StateFlow<ArtworkAccent?> = themeSettings.settings
        .map { settings ->
            settings.customAccent?.let { accent ->
                customAccentColors(accent.hueDegrees, accent.saturation, accent.lightness)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 保存自定义强调色（滑块松手时调用）。
     *
     * 与 [selectAccent] 一样会关掉两个取色开关，理由完全相同：优先级更高的东西
     * 还开着的话，用户拖完滑块松手之后颜色不会变。
     */
    fun selectCustomAccent(accent: CustomAccent) = persist("自定义强调色=${accent.hueDegrees}") {
        themeSettings.selectCustomAccent(accent)
    }

    /** 「回到预设强调色」：只删自定义色这一个键，预设 id 与两个取色开关都不动。 */
    fun clearCustomAccent() = persist("自定义强调色=清除") {
        themeSettings.clearCustomAccent()
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

    /**
     * 允许不受信任的 https 证书。
     *
     * 这里**不做二次确认弹窗**：打开它的用户有两个来源——自建服务器的拥有者，
     * 或者刚刚被一条「证书不被信任」的错误提示指过来的人。两种人都已经知道自己在干什么，
     * 而弹窗对他俩都只是多一次点击。该说的风险写在那一行自己的帮助里和打开后的提示条上，
     * 那是**看得见**的；弹窗是看完就忘的。
     *
     * 只写仓库、不碰正在跑的控制器：控制器那边的收集器（`AppPlaybackViewModel`）
     * 会自己把它同步过去。这里是设置页，播不播是另一个页面的事。
     */
    fun setTrustUntrustedCertificates(enabled: Boolean) = persist("允许不受信任的证书=$enabled") {
        playbackSettingsRepository.setTrustUntrustedCertificates(enabled)
    }

    /** 播放时屏幕常亮（默认开，见 [PlaybackSettings.keepScreenOnWhilePlaying]）。 */
    fun setKeepScreenOnWhilePlaying(enabled: Boolean) = persist("播放时禁止熄屏=$enabled") {
        playbackSettingsRepository.setKeepScreenOnWhilePlaying(enabled)
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
     * 与 [refreshPermissions] 同一个理由，只是更弱一点：模型也**可能是在播放页的字幕面板里**
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

    // ------------------------------------------------------------------ 本地翻译模型

    private val localModelEntryState = MutableStateFlow(LocalModelEntryState())

    /** 「字幕与翻译」页本地模型那一行要的两个数。见 [LocalModelEntryState]。 */
    val localModelEntry: StateFlow<LocalModelEntryState> = localModelEntryState.asStateFlow()

    init {
        viewModelScope.launch {
            translationSettings.settings.collect { settings ->
                localModelEntryState.value = LocalModelEntryState(
                    settings = settings,
                    status = readLocalModelStatus(settings.model),
                )
            }
        }
    }

    /**
     * 重新读一次模型文件的磁盘状态。
     *
     * 与 [refreshAsrStatus] 同一个理由：模型是在「本地模型」子页里下载/删除的，
     * 那一页有自己的 ViewModel，回到这一页时没有任何回调会通知这里。
     * 界面在外层 `ON_RESUME` 时调一次。
     */
    fun refreshLocalModelStatus() {
        viewModelScope.launch {
            localModelEntryState.update { it.copy(status = readLocalModelStatus(it.settings.model)) }
        }
    }

    /**
     * 只 stat 文件长度，不校验哈希（见 `LlmModelLocator.statusOf`）。
     * 与 [readAsrStatus] 一样走 IO 线程：那是几次系统调用，而入口页是冷启动后的第一屏。
     */
    private suspend fun readLocalModelStatus(modelId: String): LlmModelStatus =
        withContext(dispatchers.io) {
            localModelLocator.statusOf(LlmModelCatalog.byId(modelId))
        }

    // ------------------------------------------------------------------ 译文缓存

    private val translationCacheState = MutableStateFlow(TranslationCacheState())

    /** 「清空翻译缓存」那一行的状态。见 [TranslationCacheState]。 */
    val cacheState: StateFlow<TranslationCacheState> = translationCacheState.asStateFlow()

    /**
     * 重新读一次缓存的规模。
     *
     * 初值故意是「空空如也」而不是「正在读」：这一行无论读到什么都只是一句话，
     * 而先是空白、再跳出一个数字，比直接显示「还没有缓存」更像抽了一下。
     * 真正的读盘发生在这里，由界面在进入这一页（`ON_RESUME`）时调——
     * **不能放在构造里**：这个 ViewModel 是应用启动时就被创建的（入口页也要用），
     * 在冷启动第一帧上多一次读盘是白付的代价。
     */
    fun refreshCacheStats() {
        viewModelScope.launch {
            val stats = translationCache.stats()
            // 顺手把 failed 清掉：离开这一页再回来时，上一次的失败已经不代表现在的状态了。
            translationCacheState.update { it.copy(stats = stats, failed = false) }
        }
    }

    /**
     * 清空译文缓存。
     *
     * 重入保护：清空期间**按钮仍然可以点**（[SettingActionButtonRow] 没有禁用态，
     * 而把按钮撤掉又会让状态文字那一行自己跳一下），第二次点击会去删一个正在被删的
     * 东西——删不掉就报「清空失败」，而实际上第一次是成功的。所以这里直接忽略重复调用。
     *
     * 结果读回来而不是自己拼：删除成功与否只有磁盘知道，而 [TranslationCacheState.failed]
     * 要的就是这份「磁盘说的」而不是「我们以为的」。
     */
    fun clearTranslationCache() {
        viewModelScope.launch {
            if (translationCacheState.value.busy) return@launch
            translationCacheState.update { it.copy(busy = true, failed = false) }
            val removed = translationCache.clear()
            translationCacheState.update {
                it.copy(stats = translationCache.stats(), busy = false, failed = !removed)
            }
        }
    }

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
