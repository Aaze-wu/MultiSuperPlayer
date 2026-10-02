package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 字幕偏好映射的单元测试。
 *
 * 覆盖「升级之后字幕设置悄悄没了」这类失败，以及**存进了认不出来的值**——
 * 后者不能抛异常：一个播放页打不开的花屏，用户不会联想到是字幕偏好出了问题。
 */
class SubtitleSettingsTest {

    @Test
    fun `全新安装时用默认显示模式`() {
        val settings = preferencesOf().toSubtitleSettings()

        assertEquals(SubtitleDisplayMode.DEFAULT, settings.displayMode)
        assertEquals(
            "默认必须是显示原文——默认关闭等于这个功能默认不存在",
            SubtitleDisplayMode.ORIGINAL_ONLY,
            settings.displayMode,
        )
    }

    @Test
    fun `四个模式都能原样存取`() {
        SubtitleDisplayMode.entries.forEach { mode ->
            val stored = preferencesOf(
                stringPreferencesKey("subtitle.display_mode") to mode.name,
            ).toSubtitleSettings()

            assertEquals(mode, stored.displayMode)
        }
    }

    @Test
    fun `认不出来的值回退到默认而不是抛异常`() {
        // 用户在旧版本里存过某个值、或者被别的版本写脏了。
        val settings = preferencesOf(
            stringPreferencesKey("subtitle.display_mode") to "SOMETHING_ELSE",
        ).toSubtitleSettings()

        assertEquals(SubtitleDisplayMode.DEFAULT, settings.displayMode)
    }

    @Test
    fun `空串也回退到默认`() {
        val settings = preferencesOf(
            stringPreferencesKey("subtitle.display_mode") to "",
        ).toSubtitleSettings()

        assertEquals(SubtitleDisplayMode.DEFAULT, settings.displayMode)
    }

    @Test
    fun `枚举名字按字面量锁住`() {
        // 这些字符串是写进用户设备的契约，改名等于把所有人的字幕设置清空
        // （旧值读不出来，会静静地回退到默认模式）。
        assertEquals("OFF", SubtitleDisplayMode.OFF.name)
        assertEquals("ORIGINAL_ONLY", SubtitleDisplayMode.ORIGINAL_ONLY.name)
        assertEquals("TRANSLATION_ONLY", SubtitleDisplayMode.TRANSLATION_ONLY.name)
        assertEquals("BILINGUAL", SubtitleDisplayMode.BILINGUAL.name)
    }

    @Test
    fun `字幕与主题共用一个设置文件但键不冲突`() {
        // 两者都落在 msp_settings 里，键名撞了会让「改字幕模式」把主题改掉，
        // 而症状是「切一下字幕，主题颜色变了」——离原因非常远。
        assertNotEquals(
            ThemeSettingsRepository.Keys.BASE_THEME.name,
            SubtitleSettingsRepository.Keys.DISPLAY_MODE.name,
        )
        assertNotEquals(
            ThemeSettingsRepository.Keys.ACCENT.name,
            SubtitleSettingsRepository.Keys.DISPLAY_MODE.name,
        )

        // 两个仓库的键必须落在同一个文件里才读得到，所以只断言「互不覆盖」。
        val both = preferencesOf(
            stringPreferencesKey("theme.base") to "black",
            stringPreferencesKey("subtitle.display_mode") to SubtitleDisplayMode.BILINGUAL.name,
        )
        assertEquals("black", both.toThemeSettings().baseThemeId)
        assertEquals(SubtitleDisplayMode.BILINGUAL, both.toSubtitleSettings().displayMode)
    }
}
