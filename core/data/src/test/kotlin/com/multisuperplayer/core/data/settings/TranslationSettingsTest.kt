package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.translate.MissingConfigItem
import com.multisuperplayer.core.translate.TranslationConfig
import com.multisuperplayer.core.translate.TranslationEngine
import com.multisuperplayer.core.translate.TranslationFailure
import com.multisuperplayer.core.translate.TranslationServices
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.encodeGlossary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

/**
 * 翻译设置映射的单元测试。
 *
 * 这里盯的是两类**不会报错**的坏结果：
 * 1. 用户清空一项、却被预设值悄悄填回去（看起来像设置没生效）；
 * 2. 一个脏掉的值把整份设置带崩（设置页打不开）。
 */
class TranslationSettingsTest {

    private val providerKey = stringPreferencesKey("translation.provider")
    private val baseUrlKey = stringPreferencesKey("translation.base_url")
    private val modelKey = stringPreferencesKey("translation.model")
    private val targetKey = stringPreferencesKey("translation.target_language")
    private val autoKey = booleanPreferencesKey("translation.auto_translate")
    private val glossaryKey = stringPreferencesKey("translation.glossary")

    private fun apiKeyKey(providerId: String) = stringPreferencesKey("translation.api_key.$providerId")

    @Test
    fun `全新安装时用默认服务商的预设`() {
        val settings = preferencesOf().toTranslationSettings()

        assertEquals(TranslationSettings.DEFAULT_PROVIDER_ID, settings.providerId)
        assertEquals(TranslationServices.DEFAULT_SERVICE.baseUrl, settings.baseUrl)
        assertEquals(TranslationServices.DEFAULT_SERVICE.model, settings.model)
        assertEquals(TranslationTarget.DEFAULT, settings.target)
        assertFalse(settings.autoTranslate)
        assertTrue(settings.glossary.isEmpty())
        assertFalse("没填过密钥就不能算配置好了", settings.apiKeyStored)
    }

    @Test
    fun `键存在但为空串时保留空值而不是回填预设`() {
        // 用户在设置页把地址清掉了。回填预设会让他以为「清不掉」。
        val settings = preferencesOf(
            providerKey to "deepseek",
            baseUrlKey to "",
            modelKey to "",
        ).toTranslationSettings()

        assertEquals("", settings.baseUrl)
        assertEquals("", settings.model)
        assertFalse("地址是空的就不该说可以翻译", settings.ready)
    }

    @Test
    fun `认不出的服务商 id 落到自定义而不是默认厂商`() {
        val settings = preferencesOf(providerKey to "某个已下线的服务商").toTranslationSettings()

        assertEquals(TranslationServices.CUSTOM_ID, settings.providerId)
        assertEquals("", settings.baseUrl)
        assertEquals("", settings.model)
    }

    @Test
    fun `写脏的空串 id 也落到自定义`() {
        // 关键区别：「键不存在」= 从没用过 ⇒ 给默认预设；「键存在但是空」= 写脏了 ⇒ 不要瞎猜。
        val settings = preferencesOf(providerKey to "").toTranslationSettings()

        assertEquals(TranslationServices.CUSTOM_ID, settings.providerId)
    }

    @Test
    fun `每个预设的 id 都能原样读回并带上自己的地址模型`() {
        TranslationServices.all.forEach { preset ->
            val settings = preferencesOf(providerKey to preset.id).toTranslationSettings()

            assertEquals(preset.id, settings.providerId)
            assertEquals(preset.baseUrl, settings.baseUrl)
            assertEquals(preset.model, settings.model)
        }
    }

    @Test
    fun `目标语言认不出来时回退默认而不是抛异常`() {
        val settings = preferencesOf(
            targetKey to "klingon",
        ).toTranslationSettings()

        assertEquals(TranslationTarget.DEFAULT, settings.target)
    }

    @Test
    fun `目标语言按 code 存取`() {
        val settings = preferencesOf(
            targetKey to TranslationTarget.JAPANESE.code,
        ).toTranslationSettings()

        assertEquals(TranslationTarget.JAPANESE, settings.target)
    }

    @Test
    fun `术语表存进去再读出来不丢内容`() {
        val glossary = mapOf("Guild" to "公会", "桐人" to "")

        val settings = preferencesOf(
            glossaryKey to encodeGlossary(glossary),
        ).toTranslationSettings()

        assertEquals(glossary, settings.glossary)
    }

    @Test
    fun `术语表字符串坏掉时当成空表而不是崩`() {
        val settings = preferencesOf(glossaryKey to "{不是 json").toTranslationSettings()

        assertTrue(settings.glossary.isEmpty())
    }

    @Test
    fun `密钥只在当前服务商那一栏被记录`() {
        val settings = preferencesOf(
            providerKey to "moonshot",
            apiKeyKey("deepseek") to "iv:aa",
        ).toTranslationSettings()

        assertFalse("别的服务商的密钥不算自己的", settings.apiKeyStored)
    }

    @Test
    fun `本服务商存过密钥就算已设置`() {
        val settings = preferencesOf(
            providerKey to "moonshot",
            apiKeyKey("moonshot") to "iv:aa",
        ).toTranslationSettings()

        assertTrue(settings.apiKeyStored)
    }

    @Test
    fun `本地服务不需要密钥也能算就绪`() {
        val settings = preferencesOf(
            providerKey to TranslationServices.OLLAMA.id,
        ).toTranslationSettings()

        assertTrue("Ollama 不需要 key，这里不该要求它有", settings.ready)
    }

    @Test
    fun `地址不是 http 开头时不算就绪`() {
        val settings = preferencesOf(
            providerKey to "deepseek",
            baseUrlKey to "api.deepseek.com",
        ).toTranslationSettings()

        assertFalse(settings.ready)
    }

    @Test
    fun `需要密钥的服务商没密钥时不算就绪`() {
        val settings = preferencesOf(
            providerKey to "deepseek",
            apiKeyKey("deepseek") to "iv:aa",
        ).toTranslationSettings()

        assertTrue(settings.ready)
        assertFalse(settings.copy(apiKeyStored = false).ready)
    }

    @Test
    fun `就绪和缺项清单必须是同一件事`() {
        val base = preferencesOf(providerKey to "deepseek").toTranslationSettings()

        // 逐项列举：每加一个条件就要同时出现在两边，否则会出现
        // 「按钮亮着但清单说还差一项」，用户会以为清单在骗他。
        val cases = listOf(
            base,
            base.copy(baseUrl = ""),
            base.copy(baseUrl = "api.deepseek.com"),
            base.copy(model = ""),
            base.copy(apiKeyStored = true),
            base.copy(providerId = TranslationServices.OLLAMA.id),
            base.copy(providerId = "custom", baseUrl = "http://10.0.2.2:11434/v1", model = "qwen3:8b"),
            base.copy(apiKeyStored = true, target = TranslationTarget.ENGLISH),
        )

        cases.forEach { settings ->
            assertEquals(
                "「有没有就绪」和「还差哪几项」不一致：$settings",
                settings.ready,
                settings.missingItems.isEmpty(),
            )
        }
    }

    @Test
    fun `缺项清单说的是缺哪一项而不是泛泛的一句`() {
        val settings = preferencesOf(providerKey to "deepseek").toTranslationSettings()

        assertTrue("预设已经有地址和模型，只该缺密钥", settings.missingItems.contains(MissingConfigItem.API_KEY))
        assertEquals(1, settings.missingItems.size)
    }

    @Test
    fun `本服务商不需要密钥时清单里不该出现密钥`() {
        val settings = preferencesOf(
            providerKey to TranslationServices.OLLAMA.id,
        ).toTranslationSettings()

        assertFalse("Ollama 不需要密钥", settings.missingItems.contains(MissingConfigItem.API_KEY))
    }

    @Test
    fun `地址缺协议头时清单要说清楚需要什么`() {
        val settings = preferencesOf(
            providerKey to "deepseek",
            baseUrlKey to "api.deepseek.com",
            apiKeyKey("deepseek") to "iv:aa",
        ).toTranslationSettings()

        // 写「服务地址」不够——用户的地址明明填了东西。
        assertEquals(listOf(MissingConfigItem.BASE_URL_SCHEME), settings.missingItems)
    }

    @Test
    fun `清单里不能出现用户改不了的项`() {
        // 批大小目前不是用户设置项（仓库里写死默认值），报出这一项只会把人
        // 指到一个不存在的开关上去。引擎自己会校验它，但那是它自己的事。
        val settings = preferencesOf(providerKey to "deepseek").toTranslationSettings()

        assertFalse(
            "批大小还不是用户能改的东西",
            settings.missingItems.contains(MissingConfigItem.BATCH_SIZE),
        )
    }

    @Test
    fun `缺项判定与引擎同源`() {
        // 两处各写一遍条件，就会出现「设置页说可以翻译、点下去引擎说不满足条件」。
        val settings = preferencesOf(
            providerKey to "deepseek",
            baseUrlKey to "api.deepseek.com",
            apiKeyKey("deepseek") to "iv:aa",
        ).toTranslationSettings()

        val engine = TranslationEngine.validateConfig(
            TranslationConfig(
                baseUrl = settings.baseUrl,
                apiKey = "k",
                model = settings.model,
                target = settings.target,
            ),
        )

        assertEquals(
            "引擎该说缺的是同一项",
            MissingConfigItem.BASE_URL_SCHEME,
            assertIs<TranslationFailure.NotConfigured>(engine).missing,
        )
    }

    @Test
    fun `切换服务商时带上预设的地址和模型`() {
        val current = preferencesOf().toTranslationSettings()

        val (baseUrl, model) = translationSwitchValues(TranslationServices.MOONSHOT, current)

        assertEquals(TranslationServices.MOONSHOT.baseUrl, baseUrl)
        assertEquals(TranslationServices.MOONSHOT.model, model)
    }

    @Test
    fun `切到自定义时保留用户已经填好的地址和模型`() {
        val current = TranslationSettings(
            providerId = "custom",
            baseUrl = "https://my-proxy.example.com/v1",
            model = "my-model",
        )

        val (baseUrl, model) = translationSwitchValues(TranslationServices.CUSTOM, current)

        assertEquals("自定义没有预设值，覆盖等于把用户填的抹掉", "https://my-proxy.example.com/v1", baseUrl)
        assertEquals("my-model", model)
    }

    @Test
    fun `引擎配置带上关思考参数和默认采样`() {
        val settings = preferencesOf(
            providerKey to TranslationServices.MOONSHOT.id,
        ).toTranslationSettings()

        val config = settings.toConfig(apiKey = "sk-test")

        assertEquals(TranslationServices.MOONSHOT.baseUrl, config.baseUrl)
        assertEquals("sk-test", config.apiKey)
        assertEquals(
            "Kimi 的思考模式默认开启，落下了会白花钱",
            TranslationServices.MOONSHOT.disableThinkingBody,
            config.extraBody,
        )
        assertEquals(TranslationTarget.SIMPLIFIED_CHINESE, config.target)
    }

    @Test
    fun `引擎配置里的密钥可以为空`() {
        val settings = preferencesOf(providerKey to TranslationServices.OLLAMA.id).toTranslationSettings()

        val config = settings.toConfig(apiKey = null)

        assertEquals(null, config.apiKey)
    }
}
