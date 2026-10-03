package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.asr.AsrModelCatalog
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

    // ------------------------------------------------------------ 键名契约

    @Test
    fun `键名按字面量锁住`() {
        // ⚠️ 改名不会编译失败、不会崩，只会把所有人的选择清空（旧键读不出来）。
        // 这条断言的存在意义就是让「顺手重命名」在评审时红一次
        assertEquals("asr.model_id", AsrSettingsRepository.Keys.MODEL_ID.name)
        assertEquals("asr.base_url", AsrSettingsRepository.Keys.BASE_URL.name)
    }
}
