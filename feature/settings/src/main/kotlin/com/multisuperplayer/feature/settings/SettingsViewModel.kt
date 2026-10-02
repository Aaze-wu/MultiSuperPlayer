package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
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
 * 设置页的状态。
 *
 * 这里**只装载当前内核已经会读的设置项**。像「字幕字号」这类还没有消费者的项，
 * 等对应的内核做实了再加——写一个没人读的开关，用户拨它只会得到一个
 * 「看起来生效了但什么都没发生」的界面。
 */
class SettingsViewModel(
    private val themeSettings: ThemeSettingsRepository,
    private val translationSettings: TranslationSettingsRepository,
    private val playbackSettingsRepository: PlaybackSettingsRepository,
    private val softwareDecoders: SoftwareDecoderSupport,
    private val probe: TranslationProbe,
    private val dispatchers: DispatcherProvider,
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
                    failure = FailureText(message = "读取设置失败，请重试。"),
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
