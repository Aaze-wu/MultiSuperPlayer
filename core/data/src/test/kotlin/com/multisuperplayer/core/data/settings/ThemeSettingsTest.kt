package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
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
        // 关键：false 不能被当成「没设置过」，否则用户关掉开关、重启之后
        // 它会自己变回打开——因为默认值是 true。
        val off = preferencesOf(booleanPreferencesKey("theme.dynamic_color") to false).toThemeSettings()
        assertEquals(false, off.useDynamicColor)

        val unset = preferencesOf().toThemeSettings()
        assertNull(unset.useDynamicColor)
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
