package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.llm.LlmModelCatalog
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

    // ===== 本地（设备上运行）=====

    private val localSourceKey = stringPreferencesKey("translation.local_model_source")

    @Test
    fun `本地这一家没有地址、不要密钥，而且是跑在设备上的`() {
        val local = TranslationServices.byId("local")

        assertEquals("local", local.id)
        assertEquals(TranslationServices.LOCAL_ID, local.id)
        assertEquals("设备上没有地址可填", "", local.baseUrl)
        assertFalse("本机跑不需要密钥", local.requiresApiKey)
        assertTrue("这一个布尔同时管着缺项判定、引擎分支和设置页的栏位", local.onDevice)
        // 本机没有「必填栏」这回事，模型 id 就是清单里的那一条
        assertEquals(LlmModelCatalog.DEFAULT_ID, local.model)
        // 关思考是靠厂商补丁在远端实现的（Kimi 那类）；本机由推理层固定关掉
        // （ThinkingConfig(enableThinking = false)），带上补丁反而没人认
        assertEquals("", local.disableThinkingBody)
    }

    @Test
    fun `本地不需要地址和密钥就算就绪`() {
        // 这是 `onDevice` 这个布尔唯一真正的作用：不报两个**用户找不到输入框**的缺项。
        // 漏了它的话，设置页会显示「还缺：服务地址、API 密钥」，而那两个栏位在本地
        // 这一路根本不显示——用户会以为自己漏装了什么。
        val settings = preferencesOf(providerKey to "local").toTranslationSettings()

        assertTrue(settings.onDevice)
        assertFalse(settings.apiKeyRequired)
        assertEquals(emptyList<MissingConfigItem>(), settings.missingItems)
        assertTrue(settings.ready)
    }

    @Test
    fun `本地这一路仍然要检查模型 id`() {
        // 地址与密钥可以不管，模型不能：空 id 会让引擎拿着一个空字符串去 load
        // （报出来的错会像「文件不存在」，而真正的原因是设置里没写）。
        val settings = preferencesOf(
            providerKey to "local",
            modelKey to "   ",
        ).toTranslationSettings()

        assertEquals(listOf(MissingConfigItem.MODEL), settings.missingItems)
        assertFalse(settings.ready)
    }

    @Test
    fun `就绪不等于模型已经下载好`() {
        // 模型文件在不在是**运行期**的事实（要看文件系统），不在设置层判。
        // 这里说「可以点了」，点下去没装模型时由引擎给出带模型名与下载指引的失败。
        // 两边都判的后果是设置页要等文件 IO，而两边判得不一致的后果更糟：
        // 按钮是灰的、却没有任何一句解释。
        val settings = preferencesOf(providerKey to "local").toTranslationSettings()

        assertTrue("设置层不管文件，所以这里必须是就绪", settings.ready)
    }

    @Test
    fun `切到本地时带上预设的模型，地址留空`() {
        val current = TranslationSettings(
            providerId = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
        )

        val (baseUrl, model) = translationSwitchValues(TranslationServices.byId("local"), current)

        // 继承上一家的 API 地址是最容易漏的一处：本地的缺项判定不看地址，
        // 所以它不会被任何检查拦下，只会在设置页里显示成一个不该存在的地址。
        assertEquals("本地不该继承上一家的 API 地址", "", baseUrl)
        assertEquals(LlmModelCatalog.DEFAULT_ID, model)
    }

    @Test
    fun `本地模型 id 透传到引擎配置上`() {
        val settings = preferencesOf(providerKey to "local").toTranslationSettings()

        val config = settings.toConfig(apiKey = null)

        // 引擎就是靠这个字段决定「这个请求要发给本机引擎」的
        assertTrue("模型没下载时引擎要能报出缺哪条模型", config.onDevice)
        assertEquals("", config.baseUrl)
        assertEquals(LlmModelCatalog.DEFAULT_ID, config.model)
    }

    @Test
    fun `本地下载源与翻译服务地址是两个键，互不影响`() {
        // 合成一个字段的后果是设置页把镜像站显示成服务商地址，
        // 而且切到本地方案时会顺手继承上一家的 API 地址——两处都错得很安静。
        val settings = preferencesOf(
            providerKey to "local",
            baseUrlKey to "https://api.example.com",
            modelKey to "m",
            localSourceKey to "https://mirror.example.com",
        ).toTranslationSettings()

        assertEquals("https://mirror.example.com", settings.localModelSource)
        assertEquals("https://api.example.com", settings.baseUrl)
    }

    @Test
    fun `没存过下载源时是空串，交给归一化回落到默认站`() {
        // 这里与 baseUrl 的规矩**故意不同**：baseUrl 的空串是「用户清掉了」，
        // 必须原样保留；下载源没有「必须留空」这个语义，空串就等于是默认站
        // （归一化在 LlmModelCatalog.normalizeLlmModelBaseUrl 里）。
        val settings = preferencesOf(providerKey to "local").toTranslationSettings()

        assertEquals("", settings.localModelSource)
    }

    @Test
    fun `远端服务商的下载源默认也是空串`() {
        val settings = preferencesOf().toTranslationSettings()

        assertEquals("", settings.localModelSource)
    }
}
