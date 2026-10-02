package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.TranslationBatching
import com.multisuperplayer.core.translate.TranslationConfig
import com.multisuperplayer.core.translate.TranslationLimits
import com.multisuperplayer.core.translate.TranslationService
import com.multisuperplayer.core.translate.TranslationServices
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.encodeGlossary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 翻译设置的读写。放在 `core:data` 而不是 `core:translate`：
 * `core:translate` 是纯逻辑（提示词、重试、缓存），一旦它开始依赖 Android 的
 * DataStore/Keystore，那 138 个单测就得连模拟器一起跑。
 *
 * ## 密钥为什么不在这里
 *
 * 设置流里只有「有没有密钥」这一个布尔，密钥本体在 [ApiKeyStore] 里加密存放。
 * 这样 `settings` 这个 Flow 可以随便在日志、调试 UI 里打印，不会漏 key。
 */
class TranslationSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
    private val apiKeys: ApiKeyStore,
) {

    private val appContext = context.applicationContext

    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败（磁盘满、文件损坏）时退回默认值并记一行日志。
     *
     * 这里**不能**往上抛：翻译设置是「锦上添花」的功能，它读不出来不该让播放页崩。
     * 但也不能一声不响——否则「设置项全部变回默认」这个现象没有任何线索。
     */
    val settings: Flow<TranslationSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取翻译设置失败，先用默认值" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toTranslationSettings() }

    /**
     * 切换服务商，并把预设的地址/模型一起带上。
     *
     * 只改 providerId 是不够的：用户从 DeepSeek 切到 Kimi，地址还指着 DeepSeek，
     * 那就是一个「设置看起来生效了、实际在问另一家」的状态。
     *
     * 预设值为空的服务商（自定义）**不覆盖**：那会把用户手填的地址抹成空串，
     * 而他什么都没做。
     */
    suspend fun setProvider(providerId: String) {
        val preset = TranslationServices.byId(providerId)
        val current = settings.first()
        val (baseUrl, model) = translationSwitchValues(preset, current)

        edit { prefs ->
            prefs[TranslationKeys.PROVIDER] = preset.id
            prefs[TranslationKeys.BASE_URL] = baseUrl
            prefs[TranslationKeys.MODEL] = model
        }
    }

    suspend fun setBaseUrl(baseUrl: String) = edit { it[TranslationKeys.BASE_URL] = baseUrl.trim() }

    suspend fun setModel(model: String) = edit { it[TranslationKeys.MODEL] = model.trim() }

    suspend fun setTarget(target: TranslationTarget) = edit { it[TranslationKeys.TARGET] = target.code }

    suspend fun setAutoTranslate(enabled: Boolean) = edit { it[TranslationKeys.AUTO_TRANSLATE] = enabled }

    /**
     * 整体覆盖术语表。
     *
     * 覆盖而不是合并：用户在界面上删掉一个术语，合并的写法会把旧的又留下来，
     * 表现为「删不掉」。
     */
    suspend fun setGlossary(glossary: Glossary) =
        edit { it[TranslationKeys.GLOSSARY] = encodeGlossary(glossary) }

    /** 密钥写入。[ApiKeyStore.put] 对空白输入是「不动」而不是「删除」。 */
    suspend fun setApiKey(providerId: String, apiKey: String): Boolean = apiKeys.put(providerId, apiKey)

    /** 删除密钥。界面上必须是一个独立的、写明后果的按钮。 */
    suspend fun clearApiKey(providerId: String) = apiKeys.clear(providerId)

    suspend fun apiKeyFor(providerId: String): String? = apiKeys.get(providerId)

    /**
     * 组装一份给引擎用的配置（内部会取出密钥）。
     *
     * 参数全部取默认值：翻译质量主要受提示词和术语表影响，暴露一堆采样参数只会让
     * 用户有机会把效果调坏。设置页也**不**提供这些旋钮。
     */
    suspend fun currentConfig(): TranslationConfig {
        val current = settings.first()
        val apiKey = apiKeys.get(current.providerId)
        return current.toConfig(apiKey)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) {
            store.edit(block)
        }
    }

    private companion object {
        const val TAG = "TranslationSettings"
    }
}

/**
 * 切换服务商时该写进存储的（地址, 模型）。
 *
 * 抽成纯函数是为了能单测：这段逻辑的两种分支都「不报错」，但结果天差地别——
 * 写错了要么让用户以为切了家其实没切，要么把他手填的地址抹掉。
 */
internal fun translationSwitchValues(
    preset: TranslationService,
    current: TranslationSettings,
): Pair<String, String> = if (preset.baseUrl.isNotBlank() && preset.model.isNotBlank()) {
    preset.baseUrl to preset.model
} else {
    // 自定义服务商没有任何预设值，保持用户已经填好的内容。
    current.baseUrl to current.model
}

/**
 * 设置 → 引擎配置。
 *
 * 模型名、地址都可能还是空的（用户没填完），这里不拦——校验统一在
 * [com.multisuperplayer.core.translate.TranslationEngine.validateConfig] 做，
 * 错误文案也只有那一处，界面上显示的和日志里记的不会互相矛盾。
 */
internal fun TranslationSettings.toConfig(apiKey: String?): TranslationConfig = TranslationConfig(
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    target = target,
    glossary = glossary,
    batchSize = TranslationBatching.DEFAULT_BATCH_SIZE,
    maxBatchChars = TranslationBatching.DEFAULT_MAX_CHARS,
    contextLines = TranslationBatching.DEFAULT_CONTEXT_LINES,
    maxTokens = TranslationLimits.DEFAULT_MAX_TOKENS,
    temperature = TranslationLimits.DEFAULT_TEMPERATURE,
    maxAttempts = TranslationLimits.DEFAULT_MAX_ATTEMPTS,
    jsonMode = true,
    // 关掉推理模式的参数随服务商走：漏了它就会「HTTP 200 + 空内容」，见 TranslationService。
    extraBody = provider.disableThinkingBody,
)
