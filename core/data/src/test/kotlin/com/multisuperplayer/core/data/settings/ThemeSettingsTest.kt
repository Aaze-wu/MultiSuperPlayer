package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主题偏好映射的单元测试。
 *
 * 覆盖的是「升级之后设置悄悄没了」这类失败：键名被改名、缺键被当成
 * `false`、空串被当成合法 id。
 */
class ThemeSettingsTest {

    @Test
    fun `全新安装时所有字段都是未设置`() {
        val settings = preferencesOf().toThemeSettings()

        assertNull(settings.baseThemeId)
        assertNull(settings.accentId)
        assertNull(settings.useDynamicColor)
        assertNull(settings.colorFromArtwork)
    }

    @Test
    fun `三个键各自映射到自己的字段`() {
        val settings = preferencesOf(
            stringPreferencesKey("theme.base") to "black",
            stringPreferencesKey("theme.accent") to "teal",
            booleanPreferencesKey("theme.dynamic_color") to true,
        ).toThemeSettings()

        assertEquals("black", settings.baseThemeId)
        assertEquals("teal", settings.accentId)
        assertEquals(true, settings.useDynamicColor)
    }

    @Test
    fun `持久化键名是写入用户设备的契约`() {
        // 这几个字面量一旦改了，已经安装的用户里旧文件里的值就读不出来了，
        // 表现为「升级后主题莫名其妙变回默认」。所以在这里按字面量锁死：
        // 改动会让这条测试变红，而不是让用户在升级后自己发现。
        val keys = preferencesOf(
            stringPreferencesKey("theme.base") to "dark",
            stringPreferencesKey("theme.accent") to "rose",
            booleanPreferencesKey("theme.dynamic_color") to false,
            booleanPreferencesKey("theme.color_from_artwork") to true,
        ).toThemeSettings()

        assertEquals("dark", keys.baseThemeId)
        assertEquals("rose", keys.accentId)
        assertEquals(false, keys.useDynamicColor)
        assertEquals(true, keys.colorFromArtwork)
    }

    @Test
    fun `封面取色与系统取色是两个独立的开关`() {
        // 它们优先级不同（封面 > 系统），但都只是独立的布尔值：
        // 打开封面取色不能顺手把系统取色的偏好改掉，否则用户关掉封面取色后
        // 发现自己原来的系统取色也没了。
        val artworkOnly = preferencesOf(
            booleanPreferencesKey("theme.color_from_artwork") to true,
        ).toThemeSettings()
        assertEquals(true, artworkOnly.colorFromArtwork)
        assertNull(artworkOnly.useDynamicColor)

        val dynamicOnly = preferencesOf(
            booleanPreferencesKey("theme.dynamic_color") to true,
        ).toThemeSettings()
        assertEquals(true, dynamicOnly.useDynamicColor)
        assertNull(dynamicOnly.colorFromArtwork)
    }

    @Test
    fun `显式关掉系统取色与从未设置过是两种状态`() {
        // 关键：显式值不能被当成「没设置过」，否则用户手动拨过开关、重启之后
        // 它会自己弹回默认值——两个方向都会失效，所以两种状态必须可分辨。
        val off = preferencesOf(booleanPreferencesKey("theme.dynamic_color") to false).toThemeSettings()
        assertEquals(false, off.useDynamicColor)

        val unset = preferencesOf().toThemeSettings()
        assertNull(unset.useDynamicColor)
    }

    @Test
    fun `选中强调色会同时关掉两个取色开关`() {
        // 这两个开关的优先级都在强调色之上：只要它们还开着，用户点强调色就等于
        // 什么都没发生（Android 12+ 上系统取色永远赢）。所以「选强调色」这个动作
        // 必须一并把它们关掉，而且要写在**同一个**事务里——分开写会让 Flow 先
        // 吐出一个「强调色已改、但系统取色还开着」的中间态，那一帧仍然是被盖住的旧色。
        //
        // 这里先摆上两个都打开的旧状态，再把「选强调色」应用上去：
        // 如果哪天有人把这三行拆开、或者漏写一个键，这条会红。
        val settings = emptyPreferences().toMutablePreferences().apply {
            this[booleanPreferencesKey("theme.dynamic_color")] = true
            this[booleanPreferencesKey("theme.color_from_artwork")] = true
            applyAccentSelection("amber")
        }.toThemeSettings()

        assertEquals("amber", settings.accentId)
        assertEquals(false, settings.useDynamicColor)
        assertEquals(false, settings.colorFromArtwork)
    }

    @Test
    fun `选中强调色不会动主题基底`() {
        // 多写一个键的代价是看不见的：用户只是换个颜色，主题基底却跟着回默认了。
        val settings = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("theme.base")] = "black"
            applyAccentSelection("rose")
        }.toThemeSettings()

        assertEquals("black", settings.baseThemeId)
        assertEquals("rose", settings.accentId)
    }

    @Test
    fun `空串视为未设置`() {
        val settings = preferencesOf(
            stringPreferencesKey("theme.base") to "",
            stringPreferencesKey("theme.accent") to "",
        ).toThemeSettings()

        assertNull(settings.baseThemeId)
        assertNull(settings.accentId)
    }

    @Test
    fun `只写了主题基底时强调色仍是未设置`() {
        // 逐字段独立回退：一个字段有值不能让另一个字段跟着「看起来有值」。
        val settings = preferencesOf(stringPreferencesKey("theme.base") to "light").toThemeSettings()

        assertEquals("light", settings.baseThemeId)
        assertNull(settings.accentId)
    }
}
