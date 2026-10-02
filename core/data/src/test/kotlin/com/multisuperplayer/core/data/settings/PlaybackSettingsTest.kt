package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放偏好映射的单元测试。
 *
 * 覆盖的是「升级之后设置悄悄丢了 / 悄悄被打开了」这类失败：键名被改名、
 * 「关掉」和「没设置过」被混成同一个值。
 */
class PlaybackSettingsTest {

    @Test
    fun `全新安装时是没设置过而不是关着`() {
        val settings = preferencesOf().toPlaybackSettings()

        // 必须是 null：null 表示「让内核自己选」，具体选什么由内核的默认值决定。
        // 如果这里返回 false，数据层就等于替内核决定了一次默认值；将来内核把默认
        // 策略改成「自动」之外的东西时，这个副本不会跟着变，症状是
        // 「全新安装的用户和升级上来的用户行为不一样」。
        assertNull(settings.forceSoftwareDecoding)
    }

    @Test
    fun `持久化键名是写入用户设备的契约`() {
        // 这个字面量一旦改了，已安装用户的旧值就读不出来，表现为
        // 「升级后软件解码开关自己关掉了」。所以按字面量锁死：
        // 改动会让这条测试变红，而不是等用户在升级后自己发现。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.force_software_decoding") to true,
        ).toPlaybackSettings()

        assertEquals(true, settings.forceSoftwareDecoding)
    }

    @Test
    fun `关掉和没设置过必须是两个不同的值`() {
        // 用户手动关掉（false）之后，不该退回「没设置过」——两者看起来都是
        // 内核行为一样（默认就是 false），但只要哪天默认值变成 true，
        // 这个区别就会决定「能不能真的关掉」。这是典型的「三态挤成两态」的坑。
        val off = preferencesOf(
            booleanPreferencesKey("playback.force_software_decoding") to false,
        ).toPlaybackSettings()

        assertNotNull(off.forceSoftwareDecoding)
        assertEquals(false, off.forceSoftwareDecoding)
    }

    @Test
    fun `别的分组的键不会漏进播放设置`() {
        // 主题 / 字幕 / 翻译的键都和播放设置挤在同一个 DataStore 文件里
        // （见 mspSettingsStore 的说明）。前缀撞车不会报错，只会让一个分组读到
        // 另一个分组的值，表现为「改主题把播放设置也改了」这种毫无头绪的现象。
        val settings = preferencesOf(
            stringPreferencesKey("theme.base") to "black",
            booleanPreferencesKey("theme.dynamic_color") to true,
            booleanPreferencesKey("theme.color_from_artwork") to true,
            stringPreferencesKey("subtitle.display_mode") to "BILINGUAL",
            stringPreferencesKey("translation.provider") to "openai",
            booleanPreferencesKey("translation.auto_translate") to true,
        ).toPlaybackSettings()

        assertNull(settings.forceSoftwareDecoding)
    }

    @Test
    fun `其他开关的默认值不会被误当成软件解码`() {
        // `false` 在这里是**有意写进去的值**，不是「缺键」。
        // 两者在 Preferences 里长得完全一样（都是布尔），只能靠键名区分——
        // 所以这条测试是「键名写对了」的第二重保险。
        val settings = preferencesOf(
            booleanPreferencesKey("theme.color_from_artwork") to false,
            booleanPreferencesKey("translation.auto_translate") to false,
        ).toPlaybackSettings()

        assertNull(settings.forceSoftwareDecoding)
    }
}
