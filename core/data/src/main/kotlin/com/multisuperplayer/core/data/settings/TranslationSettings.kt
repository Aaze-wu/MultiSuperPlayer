package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.MissingConfigItem
import com.multisuperplayer.core.translate.TranslationEngine
import com.multisuperplayer.core.translate.TranslationService
import com.multisuperplayer.core.translate.TranslationServices
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.decodeGlossary

/**
 * 翻译相关的用户设置。
 *
 * ## 为什么地址/模型要**存起来**而不是每次现算
 *
 * 预设只是「省得用户抄文档」的初值。用户一旦把 DeepSeek 的地址改成自己的中转、
 * 或者把模型换成另一个便宜的，那就不该再被预设覆盖回去。所以选服务商时会把
 * 预设值**写进**存储（见 [TranslationSettingsRepository.setProvider]），之后读到的
 * 永远是用户的那份。
 *
 * ## `null` 与空串是两件事
 *
 * - 存储里**没有**这个键 ⇒ 从没设置过 ⇒ 用预设值（`?:` 的左边）；
 * - 存储里是**空串** ⇒ 用户把它清掉了 ⇒ 保持空。
 *
 * 两者混为一谈的后果是：用户在设置页把地址清空保存，退出去再进来又变回预设地址，
 * 看起来像「设置没生效」，而他又不知道自己重新填一遍就能用。
 */
data class TranslationSettings(
    val providerId: String = DEFAULT_PROVIDER_ID,
    val baseUrl: String = "",
    val model: String = "",
    val target: TranslationTarget = TranslationTarget.DEFAULT,
    val autoTranslate: Boolean = false,
    val glossary: Glossary = emptyMap(),
    /**
     * 当前服务商是否已经存过密钥。
     *
     * 密钥本身不进这里——它加密后单独存（[ApiKeyStore]），设置流里只留一个布尔，
     * 免得密钥跟着 `data class` 的 `toString()` 跑去日志里。
     */
    val apiKeyStored: Boolean = false,
    /**
     * 本地模型的**下载源**（镜像站）。
     *
     * 与 [baseUrl] 是两件事：那个是翻译服务的地址，这个是「去哪儿下 345 MB 的模型文件」。
     * 合成一个字段的后果是设置页把镜像站显示成服务商地址，而且切到本地方案时会
     * 顺手继承上一家的 API 地址——两处都错得很安静。
     *
     * 空串 = 用内置的默认镜像站；具体回落在 `LlmModelCatalog.normalizeLlmModelBaseUrl`
     * 里（归一化之后为空也回默认站）。所以这里「键不存在」与「用户清空」的**结果**
     * 一样，不需要像 [baseUrl] 那样区分——下载源本来就没有「必须留空」这个语义。
     */
    val localModelSource: String = "",
) {

    /** 当前服务商的预设。认不出来的 id 会落到「自定义」（见 [TranslationServices.byId]）。 */
    val provider: TranslationService get() = TranslationServices.byId(providerId)

    /** 密钥这栏对当前服务商是否必需（本地 Ollama 不需要）。 */
    val apiKeyRequired: Boolean get() = provider.requiresApiKey

    /**
     * 当前服务商是不是跑在**这台设备上**（而不是某个远端 API）。
     *
     * 转发 [TranslationService.onDevice] 而不是在这里再算一遍（比如「地址为空」）：
     * 这个判据同时管着「缺项判定要不要看地址」「引擎要不要走本机」「设置页要不要
     * 显示地址与密钥输入框」，四处算得不一样就是四种局部错乱，而且都不报错。
     *
     * **注意它与 [ready] 不是一件事**：本地模型下载了没有属于运行期的事实
     * （要看文件系统），不在设置层判——这里说「可以点了」，点下去如果模型没装，
     * 引擎会给出带模型名与下载指引的失败（`LocalModelMissing`）。
     */
    val onDevice: Boolean get() = provider.onDevice

    /**
     * 还差哪几项才能开始翻译。空列表表示已经填够。
     *
     * 判定直接交给 [TranslationEngine.missingConfigItems]——两处各写一遍条件，
     * 迟早会出现「设置页说可以翻译、点下去引擎说不满足条件」这种互相打脸的状态。
     * 这里只出**枚举**，不出句子：文案要跟着界面语言走（见 `describeMissingItem()`）。
     *
     * 光有一个 `ready = false` 是不够的：三个界面（设置页汇总行、翻译设置页、
     * 播放页字幕面板）都需要具体说「还差 API 密钥」——一句「请检查设置」
     * 等于把找问题的事推回给用户。
     */
    val missingItems: List<MissingConfigItem>
        get() = TranslationEngine.missingConfigItems(
            baseUrl = baseUrl,
            model = model,
            apiKeyRequired = apiKeyRequired,
            apiKeyStored = apiKeyStored,
            // 批大小还不是用户设置项（仓库里写死默认值），这里不报这个缺项。
            batchSize = null,
            // 设备上的服务商没有地址与密钥，别报两个用户找不到输入框的缺项。
            onDevice = onDevice,
        )

    /**
     * 是否已经填够、可以开始翻译。
     *
     * 界面上「能不能点」和引擎里「能不能跑」用同一套标准，否则会出现按钮亮着、
     * 点下去立刻失败的状态。这里只做「能不能点」的预判，真正的错误文案仍然由引擎给出。
     */
    val ready: Boolean get() = missingItems.isEmpty()

    companion object {
        /** 全新安装时的服务商，和 [TranslationServices.DEFAULT_SERVICE] 保持一致。 */
        const val DEFAULT_PROVIDER_ID: String = "deepseek"
    }
}

/**
 * 从 DataStore 里还原设置。
 *
 * 不认识的值一律回退（服务商 id、目标语言），绝不抛异常：设置页打不开没法用，
 * 用户不会想到是「上次存的值脏了」。
 */
internal fun Preferences.toTranslationSettings(): TranslationSettings {
    // 「从没选过」用默认厂商（新用户打开设置页要看到一份能用的预设，而不是两个空格子）；
    // 「存过一个我们认不出来的值」落到自定义——这时把用户悄悄接到别家 API 上更糟。
    val storedId = this[TranslationKeys.PROVIDER]
    val provider = if (storedId == null) {
        TranslationServices.DEFAULT_SERVICE
    } else {
        TranslationServices.byId(storedId)
    }

    return TranslationSettings(
        providerId = provider.id,
        // 键不存在才用预设；空串是用户的显式选择，要原样保留。
        baseUrl = this[TranslationKeys.BASE_URL] ?: provider.baseUrl,
        model = this[TranslationKeys.MODEL] ?: provider.model,
        target = TranslationTarget.fromCode(this[TranslationKeys.TARGET]),
        autoTranslate = this[TranslationKeys.AUTO_TRANSLATE] ?: false,
        glossary = decodeGlossary(this[TranslationKeys.GLOSSARY]),
        apiKeyStored = this[apiKeyPreferenceKey(provider.id)] != null,
        localModelSource = this[TranslationKeys.LOCAL_MODEL_SOURCE].orEmpty(),
    )
}

/** 翻译设置用到的键。集中放一处，避免「写 A 读 B」这种静默失效。 */
internal object TranslationKeys {
    val PROVIDER = stringPreferencesKey("translation.provider")
    val BASE_URL = stringPreferencesKey("translation.base_url")
    val MODEL = stringPreferencesKey("translation.model")
    val TARGET = stringPreferencesKey("translation.target_language")
    val AUTO_TRANSLATE = booleanPreferencesKey("translation.auto_translate")
    val GLOSSARY = stringPreferencesKey("translation.glossary")

    /** 本地模型的下载源。**不是** [BASE_URL]**：那个是翻译服务的地址，这个是模型文件的镜像站。 */
    val LOCAL_MODEL_SOURCE = stringPreferencesKey("translation.local_model_source")
}
