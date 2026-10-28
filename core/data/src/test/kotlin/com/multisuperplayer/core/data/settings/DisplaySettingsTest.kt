package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 列表显示偏好映射的单元测试。
 *
 * 这里要挡住的是两类「升级之后才发现」的失败：
 * 键名被改名（旧值读不出来 ⇒ 所有人回到默认），以及
 * 「用户关掉」被映射成「没设置过」（⇒ 用户怎么关都关不掉，
 * 或者默认值一改就把他关掉的东西又打开了）。
 */
class DisplaySettingsTest {

    @Test
    fun `全新安装时是没设置过而不是关着`() {
        // 必须是 null。写成 `?: false` 的话，数据层就替 UI 层决定了默认值，
        // 而这个功能的默认值是**开**——两者一撞，症状是「全新安装的人
        // 打开列表，长名字一动不动，而设置里的开关画着『关』」。
        assertNull(preferencesOf().toDisplaySettings().scrollLongTitles)
    }

    @Test
    fun `持久化键名是写入用户设备的契约`() {
        // 字面量一旦改了，已安装用户的旧值就读不出来（回到默认），
        // 表现为「升级后滚动开关自己打开了/关掉了」。按字面量锁死。
        val on = preferencesOf(
            booleanPreferencesKey("display.scroll_long_titles") to true,
        ).toDisplaySettings()

        assertEquals(true, on.scrollLongTitles)
    }

    @Test
    fun `关掉和没设置过必须是两个不同的值`() {
        // 默认值是「开」，所以「用户关掉」必须是显式的 false 并且能被读回来；
        // 一旦这里返回 null，用户关掉之后又会退回默认的「开」——
        // 一个关不掉的开关比没有开关更气人。
        val off = preferencesOf(
            booleanPreferencesKey("display.scroll_long_titles") to false,
        ).toDisplaySettings()

        assertEquals(false, off.scrollLongTitles)
    }

    @Test
    fun `别的分组的键不会漏进列表显示`() {
        // 所有分组挤在同一个 DataStore 文件里（见 mspSettingsStore 的说明）。
        // 前缀撞车不会报错，只会让两组读到彼此的值——表现为
        // 「改主题把列表显示也改了」这类毫无头绪的现象。
        val settings = preferencesOf(
            stringPreferencesKey("theme.base") to "black",
            booleanPreferencesKey("theme.dynamic_color") to true,
            booleanPreferencesKey("theme.color_from_artwork") to false,
            stringPreferencesKey("subtitle.display_mode") to "BILINGUAL",
            booleanPreferencesKey("playback.record_recent_plays") to true,
        ).toDisplaySettings()

        assertNull(settings.scrollLongTitles)
        assertNull(settings.alwaysScrollPages)
    }

    @Test
    fun `逐页设置没设置过时是 null 而不是空集`() {
        // 「一页都没点名」和「用户把勾全取消了」在界面上是两件事；
        // 写 `?: emptySet()` 会把前者伪造成后者，而判定那边
        // `isEmpty()` 两种都是 true，所以这个差别一旦丢了就再也找不回来。
        assertNull(preferencesOf().toDisplaySettings().alwaysScrollPages)
    }

    @Test
    fun `逐页设置的键名也是写入用户设备的契约`() {
        // 与上面那个 boolean 键同理：改名 ⇒ 所有人点名过的页面全部静默失效，
        // 表现是「我明明勾了浏览页，它就是不滚」。按字面量锁死。
        val settings = preferencesOf(
            stringSetPreferencesKey("display.always_scroll_pages") to setOf("library", "browse"),
        ).toDisplaySettings()

        assertEquals(setOf("library", "browse"), settings.alwaysScrollPages)
    }

    @Test
    fun `逐页设置与总开关是各自独立的键`() {
        // 两者写在同一个 data class 里，但意义完全不同：总开关关掉之后逐页勾选
        // 只是置灰（勾痕保留）。若哪天有人把它们合并成一个字段，
        // 「关掉总开关就丢掉逐页选择」会是一个用户无法理解的副作用。
        val settings = preferencesOf(
            booleanPreferencesKey("display.scroll_long_titles") to false,
            stringSetPreferencesKey("display.always_scroll_pages") to setOf("queue"),
        ).toDisplaySettings()

        assertEquals(false, settings.scrollLongTitles)
        assertEquals(setOf("queue"), settings.alwaysScrollPages)
    }
}
