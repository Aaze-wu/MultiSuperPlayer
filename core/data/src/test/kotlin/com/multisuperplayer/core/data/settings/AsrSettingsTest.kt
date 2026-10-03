package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrServices
import com.multisuperplayer.core.asr.DEFAULT_MODEL_BASE_URL
import com.multisuperplayer.core.asr.normalizeModelBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音识别偏好的测试。
 *
 * 测的是「存储结构 → 领域模型」这一步（[toAsrSettings]）：仓库本身要 `Context`
 * 和真实的 DataStore 文件，而最容易写错的恰好是这一步——「没设置」和「设置成默认值」
 * 是两件事，用一个 `null` 表达错就会变成「我选的模型莫名其妙变回去了」。
 *
 * 另外把写进用户设备的**键名字面量**钉死：改名不会报错，只会静静地让所有人的选择
 * 回落到默认模型。
 */
class AsrSettingsTest {

    /** 直接造存储结构：不必有真实的 DataStore 文件，键对不对才是被测的东西。 */
    private fun prefsOf(vararg pairs: Pair<String, String>): Preferences {
        val prefs = mutablePreferencesOf()
        pairs.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        return prefs
    }

    private fun prefsWithModel(id: String) = prefsOf(AsrSettingsRepository.Keys.MODEL_ID.name to id)

    private fun prefsWithBaseUrl(url: String) = prefsOf(AsrSettingsRepository.Keys.BASE_URL.name to url)

    private fun prefsWithCloud(vararg pairs: Pair<String, String>): Preferences = prefsOf(
        AsrSettingsRepository.Keys.ROUTE.name to AsrRoute.CLOUD.id,
        *pairs,
    )

    // ------------------------------------------------------------ 缺键 = 没设置

    @Test
    fun `缺键时两个都是 null，模型与下载源都走默认`() {
        val settings = prefsOf().toAsrSettings()

        assertNull(settings.storedModelId)
        assertNull(settings.storedBaseUrl)
        assertEquals(AsrModelCatalog.DEFAULT_ID, settings.model.id)
        assertEquals(DEFAULT_MODEL_BASE_URL, settings.baseUrl)
        assertTrue(settings.usesDefaultSource)
    }

    @Test
    fun `有值时原样读出来`() {
        val settings = prefsOf(
            AsrSettingsRepository.Keys.MODEL_ID.name to AsrModelCatalog.ZIPFORMER_ID,
            AsrSettingsRepository.Keys.BASE_URL.name to "https://example.com",
        ).toAsrSettings()

        assertEquals(AsrModelCatalog.ZIPFORMER_ID, settings.storedModelId)
        assertEquals("https://example.com", settings.storedBaseUrl)
        assertEquals(AsrModelCatalog.ZIPFORMER_ID, settings.model.id)
        assertEquals("https://example.com", settings.baseUrl)
        assertFalse(settings.usesDefaultSource)
    }

    // ------------------------------------------------------------ 模型 id

    @Test
    fun `存了清单里没有的模型 id，回落到默认模型但原值保留`() {
        // 老版本留下的值 / 手工改过的配置。回落是给用户用的（不能因为一个坏值就崩），
        // 但 storedModelId 保留原样——排查「为什么选的模型不对」时要能看出盘上是什么
        val settings = prefsWithModel("zipformer-old-name").toAsrSettings()

        assertEquals("zipformer-old-name", settings.storedModelId)
        assertEquals(AsrModelCatalog.DEFAULT_ID, settings.model.id)
    }

    @Test
    fun `模型 id 前后带空白也能认出来`() {
        assertEquals(AsrModelCatalog.ZIPFORMER_ID, prefsWithModel("  ${AsrModelCatalog.ZIPFORMER_ID}  ").toAsrSettings().model.id)
    }

    @Test
    fun `模型 id 是空串时走默认模型`() {
        val settings = prefsWithModel("").toAsrSettings()

        assertEquals(AsrModelCatalog.DEFAULT_ID, settings.model.id)
        assertEquals("", settings.storedModelId)
    }

    // ------------------------------------------------------------ 下载源

    @Test
    fun `下载源是空串或纯空白时等于没设置`() {
        // 这是「填空白 = 恢复默认」那条规则的读取侧：只要盘上出现空串，行为就必须
        // 和缺键完全一致，否则界面显示空输入框、实际却在下另一个源
        listOf("", "   ", "\t").forEach { raw ->
            val settings = prefsWithBaseUrl(raw).toAsrSettings()

            assertEquals(DEFAULT_MODEL_BASE_URL, settings.baseUrl)
            assertTrue("「$raw」应当算没设置", settings.usesDefaultSource)
        }
    }

    @Test
    fun `下载源读取时归一化：去首尾空白与末尾斜杠`() {
        assertEquals(DEFAULT_MODEL_BASE_URL, prefsWithBaseUrl("  $DEFAULT_MODEL_BASE_URL  ").toAsrSettings().baseUrl)
        assertEquals("https://example.com/hf", prefsWithBaseUrl("https://example.com/hf/").toAsrSettings().baseUrl)
        assertEquals("https://example.com/hf", prefsWithBaseUrl("https://example.com/hf///").toAsrSettings().baseUrl)
        // 归一化和 AsrModelCatalog 是同一个函数：两处各写一遍迟早会不一致
        assertEquals(
            normalizeModelBaseUrl("https://example.com/hf/"),
            prefsWithBaseUrl("https://example.com/hf/").toAsrSettings().baseUrl,
        )
    }

    @Test
    fun `下载源只填了斜杠时走默认`() {
        // "/" 会被 trimEnd 成空串——只写一个斜杠的人想要的显然不是「从根开始下」
        assertEquals(DEFAULT_MODEL_BASE_URL, prefsWithBaseUrl("/").toAsrSettings().baseUrl)
    }

    // ------------------------------------------------------------ 识别路线

    @Test
    fun `缺键时路线是本机`() {
        val settings = prefsOf().toAsrSettings()

        assertNull(settings.storedRouteId)
        assertEquals(AsrRoute.ON_DEVICE, settings.route)
        assertFalse("什么都没选过就不该上传任何东西", settings.usesCloud)
    }

    @Test
    fun `存了路线就按它走，认不出来的值回本机`() {
        assertEquals(
            AsrRoute.CLOUD,
            prefsOf(AsrSettingsRepository.Keys.ROUTE.name to "cloud").toAsrSettings().route,
        )
        // 坏值落到「不上传」的那一边——理由见 AsrRoute.byId
        listOf("", "   ", "local", "CLOUD").forEach { raw ->
            assertEquals(
                "「$raw」应当回本机",
                AsrRoute.ON_DEVICE,
                prefsOf(AsrSettingsRepository.Keys.ROUTE.name to raw).toAsrSettings().route,
            )
        }
    }

    // ------------------------------------------------------------ 云端服务商

    @Test
    fun `没选过服务商时用默认那家，不是自定义`() {
        // 「没选过」和「选了个不认得的」必须分开：前者要「选完就能用」，
        // 后者要「地址在你手上，我不猜」。两者都回自定义的话，用户第一次
        // 打开云端设置就看到一个空地址框，不知道该填什么
        val settings = prefsWithCloud().toAsrSettings()

        assertNull(settings.storedCloudServiceId)
        assertEquals(AsrServices.DEFAULT_SERVICE.id, settings.cloudService.id)
        assertEquals(AsrServices.DEFAULT_SERVICE.baseUrl, settings.cloudBaseUrl)
        assertEquals(AsrServices.DEFAULT_SERVICE.model, settings.cloudModel)
        assertTrue("默认那家自带地址，不该是「还没填」", settings.cloudAddressLooksValid)
    }

    @Test
    fun `存了不认得的服务商 id 就回自定义，不会静默换厂商`() {
        // 这是整个云端配置里最危险的一条：静默回落到默认厂商会表现为
        // 「我配的中转站地址还在框里，但请求发去了别家」，而失败信息指向别家
        val settings = prefsWithCloud(
            AsrSettingsRepository.Keys.CLOUD_SERVICE_ID.name to "openai-looking-typo",
            AsrSettingsRepository.Keys.CLOUD_BASE_URL.name to "https://my-proxy.example.com/v1",
        ).toAsrSettings()

        assertEquals(AsrServices.CUSTOM_ID, settings.cloudService.id)
        // 地址是用户填的那一条（没被预设覆盖），但服务商是「自定义」——
        // 于是 supportsSegments / 是否需要密钥 都不再假设成某一家
        assertEquals("https://my-proxy.example.com/v1", settings.cloudBaseUrl)
        assertFalse("自定义不强制密钥：自建服务很可能不校验", settings.cloudNeedsApiKey)
    }

    // ------------------------------------------------------------ 云端地址与模型

    @Test
    fun `云端地址与模型各自覆盖，空白回落到预设`() {
        val siliconflow = AsrServices.SILICONFLOW
        val service = AsrSettingsRepository.Keys.CLOUD_SERVICE_ID.name to siliconflow.id

        // 只换模型：地址仍然跟着预设
        val modelOnly = prefsWithCloud(
            service,
            AsrSettingsRepository.Keys.CLOUD_MODEL.name to "Qwen/Qwen3-Omni",
        ).toAsrSettings()
        assertEquals("Qwen/Qwen3-Omni", modelOnly.cloudModel)
        assertEquals(siliconflow.baseUrl, modelOnly.cloudBaseUrl)

        // 只换地址：模型仍然跟着预设
        val urlOnly = prefsWithCloud(
            service,
            AsrSettingsRepository.Keys.CLOUD_BASE_URL.name to "https://my-proxy.example.com/v1",
        ).toAsrSettings()
        assertEquals("https://my-proxy.example.com/v1", urlOnly.cloudBaseUrl)
        assertEquals(siliconflow.model, urlOnly.cloudModel)

        // 空白 = 没填（与 asr.base_url 同一套语义）
        listOf("", "   ", "\t").forEach { raw ->
            val blank = prefsWithCloud(
                service,
                AsrSettingsRepository.Keys.CLOUD_BASE_URL.name to raw,
                AsrSettingsRepository.Keys.CLOUD_MODEL.name to raw,
            ).toAsrSettings()
            assertEquals("「$raw」应当算没填", siliconflow.baseUrl, blank.cloudBaseUrl)
            assertEquals("「$raw」应当算没填", siliconflow.model, blank.cloudModel)
        }
    }

    @Test
    fun `自定义加空地址是「还没填」，不是「不能用」`() {
        val settings = prefsWithCloud(
            AsrSettingsRepository.Keys.CLOUD_SERVICE_ID.name to AsrServices.CUSTOM_ID,
        ).toAsrSettings()

        assertEquals("", settings.cloudBaseUrl)
        assertFalse(settings.cloudAddressLooksValid)
        // 空地址不抛异常、也不回落别家：界面据此把按钮变灰 + 说明要去填地址
        assertFalse(settings.cloudNeedsApiKey)
    }

    @Test
    fun `云端密钥的 owner id 带 asr 前缀`() {
        // ApiKeyStore 的键名前缀是 translation.，不带 asr- 的话
        // 「识别 → OpenAI」与「翻译 → OpenAI」会共用同一把钥匙：
        // 在一边改密钥会静默改掉另一边
        val owner = prefsWithCloud().toAsrSettings().cloudApiKeyOwner

        assertEquals("asr-${AsrServices.DEFAULT_SERVICE.id}", owner)
        assertFalse("不能直接用服务商 id：那会和翻译那边撞键", owner == AsrServices.DEFAULT_SERVICE.id)
    }

    // ------------------------------------------------------------ 「密钥存过没有」

    /** 盘上那个 id 会被写入口归一化，所以这里也按字面量给 id（不是给 `AsrService`）。 */
    private fun prefsWithCloudKey(serviceId: String, keyOwner: String): Preferences = prefsOf(
        AsrSettingsRepository.Keys.ROUTE.name to AsrRoute.CLOUD.id,
        AsrSettingsRepository.Keys.CLOUD_SERVICE_ID.name to serviceId,
        // 用 `apiKeyPreferenceKey` 拼而不是抄字面量：`translation.` 那段前缀是
        // ApiKeyStore 的契约（它自己有断言锁着），这里要锁的是 `asr-` 那一段。
        apiKeyPreferenceKey(keyOwner).name to "sk-something",
    )

    @Test
    fun `密钥在不在，问的是归一化之后那家的钥匙`() {
        // 认得出来的 id：原样就是 owner
        assertTrue(prefsWithCloudKey("groq", "asr-groq").toAsrSettings().cloudApiKeyStored)
        // 认不出来的 id（旧版本 / 手改过的配置）会被归一化成「自定义」，
        // 真正发请求时拿的是 `asr-custom` 这把钥匙——这里必须算出同一个答案。
        // 两处用不同 id 的表现是「密钥明明填过，设置页说没填」，而设置页说没填时
        // 识别的按钮就是灰的。
        assertTrue(prefsWithCloudKey("groq-old-name", "asr-custom").toAsrSettings().cloudApiKeyStored)
        assertFalse(prefsWithCloudKey("groq-old-name", "asr-groq-old-name").toAsrSettings().cloudApiKeyStored)
    }

    @Test
    fun `密钥是按家分开算的，别家的钥匙不算数`() {
        // 给 Groq 存过钥匙，当前选的是硅基流动 ⇒ 是「没填」。
        // 这个布尔值直接决定识别按钮亮不亮，串家的后果是「点下去 401」。
        val settings = prefsWithCloudKey(AsrServices.DEFAULT_SERVICE.id, "asr-groq").toAsrSettings()

        assertFalse(settings.cloudApiKeyStored)
    }

    @Test
    fun `没存过密钥时是 false，而不是 null 或抛异常`() {
        // 首次进入云端路线（还没粘过钥匙）就是这条路径；默认值必须是安全的「没有」
        assertFalse(prefsWithCloud().toAsrSettings().cloudApiKeyStored)
        assertFalse(prefsOf().toAsrSettings().cloudApiKeyStored)
    }

    // ------------------------------------------------------------ 键名契约

    @Test
    fun `键名按字面量锁住`() {
        // ⚠️ 改名不会编译失败、不会崩，只会把所有人的选择清空（旧键读不出来）。
        // 这条断言的存在意义就是让「顺手重命名」在评审时红一次
        assertEquals("asr.model_id", AsrSettingsRepository.Keys.MODEL_ID.name)
        assertEquals("asr.base_url", AsrSettingsRepository.Keys.BASE_URL.name)
        assertEquals("asr.route", AsrSettingsRepository.Keys.ROUTE.name)
        assertEquals("asr.cloud_service_id", AsrSettingsRepository.Keys.CLOUD_SERVICE_ID.name)
        assertEquals("asr.cloud_base_url", AsrSettingsRepository.Keys.CLOUD_BASE_URL.name)
        assertEquals("asr.cloud_model", AsrSettingsRepository.Keys.CLOUD_MODEL.name)
    }
}
