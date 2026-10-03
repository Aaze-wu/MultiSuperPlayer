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

    // ---------------------------------------------------------------- 字幕样式

    @Test
    fun `全新安装时用默认字幕样式`() {
        val settings = preferencesOf().toSubtitleSettings()

        assertEquals(SubtitleStyle.DEFAULT, settings.style)
        // 默认值必须和 v0.5.15 写死的渲染参数一致，否则升级之后所有人的字幕
        // 会自己变样。这里只钉**档位**，具体数字由 feature:player 的
        // SubtitleStyleRulesTest 钉（两处都需要：一个是「存什么」，一个是「画成什么」）。
        assertEquals(SubtitleTextSize.NORMAL, settings.style.textSize)
        assertEquals(SubtitleLineSpacing.NORMAL, settings.style.lineSpacing)
        assertEquals(SubtitleOutline.NONE, settings.style.outline)
        assertEquals(SubtitleBottomMargin.NEAR, settings.style.bottomMargin)
    }

    @Test
    fun `四组样式档位都能原样存取`() {
        // 用字面量键名读写：这是写入用户设备的**契约**，改成 Keys.X.name
        // 就只能证明「我读得出来我自己写的东西」。
        SubtitleTextSize.entries.forEach { size ->
            val stored = preferencesOf(stringPreferencesKey("subtitle.text_size") to size.name)
                .toSubtitleSettings()
            assertEquals(size, stored.style.textSize)
        }
        SubtitleLineSpacing.entries.forEach { spacing ->
            val stored = preferencesOf(stringPreferencesKey("subtitle.line_spacing") to spacing.name)
                .toSubtitleSettings()
            assertEquals(spacing, stored.style.lineSpacing)
        }
        SubtitleOutline.entries.forEach { outline ->
            val stored = preferencesOf(stringPreferencesKey("subtitle.outline") to outline.name)
                .toSubtitleSettings()
            assertEquals(outline, stored.style.outline)
        }
        SubtitleBottomMargin.entries.forEach { margin ->
            val stored = preferencesOf(stringPreferencesKey("subtitle.bottom_margin") to margin.name)
                .toSubtitleSettings()
            assertEquals(margin, stored.style.bottomMargin)
        }
    }

    @Test
    fun `四个样式键同时存进去也互不干扰`() {
        val stored = preferencesOf(
            stringPreferencesKey("subtitle.text_size") to SubtitleTextSize.HUGE.name,
            stringPreferencesKey("subtitle.line_spacing") to SubtitleLineSpacing.TIGHT.name,
            stringPreferencesKey("subtitle.outline") to SubtitleOutline.THICK.name,
            stringPreferencesKey("subtitle.bottom_margin") to SubtitleBottomMargin.HIGH.name,
            stringPreferencesKey("subtitle.display_mode") to SubtitleDisplayMode.OFF.name,
        ).toSubtitleSettings()

        assertEquals(SubtitleDisplayMode.OFF, stored.displayMode)
        assertEquals(SubtitleTextSize.HUGE, stored.style.textSize)
        assertEquals(SubtitleLineSpacing.TIGHT, stored.style.lineSpacing)
        assertEquals(SubtitleOutline.THICK, stored.style.outline)
        assertEquals(SubtitleBottomMargin.HIGH, stored.style.bottomMargin)
    }

    @Test
    fun `只存了一个样式键时另外三个用各自的默认值`() {
        // 恢复出厂、或者从没有样式字段的旧版本升上来，都可能只看到一部分键。
        // 缺键必须回退到**该档位的**默认，不能整块 style 都不要（那会让另外三个
        // 已经存好的值也一起变回默认）。
        val stored = preferencesOf(
            stringPreferencesKey("subtitle.text_size") to SubtitleTextSize.LARGE.name,
        ).toSubtitleSettings()

        assertEquals(SubtitleTextSize.LARGE, stored.style.textSize)
        assertEquals(SubtitleLineSpacing.DEFAULT, stored.style.lineSpacing)
        assertEquals(SubtitleOutline.DEFAULT, stored.style.outline)
        assertEquals(SubtitleBottomMargin.DEFAULT, stored.style.bottomMargin)
    }

    @Test
    fun `认不出来的样式值回退到默认而不是抛异常`() {
        listOf("", "SOMETHING_ELSE", "1.25", "small").forEach { garbage ->
            val stored = preferencesOf(
                stringPreferencesKey("subtitle.text_size") to garbage,
                stringPreferencesKey("subtitle.line_spacing") to garbage,
                stringPreferencesKey("subtitle.outline") to garbage,
                stringPreferencesKey("subtitle.bottom_margin") to garbage,
            ).toSubtitleSettings()

            assertEquals("垃圾值 = $garbage", SubtitleStyle.DEFAULT, stored.style)
        }
    }

    @Test
    fun `样式键名按字面量锁住`() {
        // 改名 = 所有用户的字幕样式被静默重置。这一条是防止「顺手重命名」。
        assertEquals("subtitle.text_size", SubtitleSettingsRepository.Keys.TEXT_SIZE.name)
        assertEquals("subtitle.line_spacing", SubtitleSettingsRepository.Keys.LINE_SPACING.name)
        assertEquals("subtitle.outline", SubtitleSettingsRepository.Keys.OUTLINE.name)
        assertEquals("subtitle.bottom_margin", SubtitleSettingsRepository.Keys.BOTTOM_MARGIN.name)
    }

    @Test
    fun `样式枚举名按字面量锁住`() {
        // 存的是枚举名而不是序号：往档位表中间插一档时，存序号会让所有人的字号
        // 跳一格，而屏幕上只表现为「字幕好像变大了」，没人会联想到版本升级。
        assertEquals("SMALL", SubtitleTextSize.SMALL.name)
        assertEquals("NORMAL", SubtitleTextSize.NORMAL.name)
        assertEquals("LARGE", SubtitleTextSize.LARGE.name)
        assertEquals("HUGE", SubtitleTextSize.HUGE.name)
        assertEquals("TIGHT", SubtitleLineSpacing.TIGHT.name)
        assertEquals("NORMAL", SubtitleLineSpacing.NORMAL.name)
        assertEquals("LOOSE", SubtitleLineSpacing.LOOSE.name)
        assertEquals("NONE", SubtitleOutline.NONE.name)
        assertEquals("THIN", SubtitleOutline.THIN.name)
        assertEquals("NORMAL", SubtitleOutline.NORMAL.name)
        assertEquals("THICK", SubtitleOutline.THICK.name)
        assertEquals("EDGE", SubtitleBottomMargin.EDGE.name)
        assertEquals("NEAR", SubtitleBottomMargin.NEAR.name)
        assertEquals("RAISED", SubtitleBottomMargin.RAISED.name)
        assertEquals("HIGH", SubtitleBottomMargin.HIGH.name)
    }

    @Test
    fun `每个样式枚举的默认档位都是文档写的那一个`() {
        // 「恢复默认样式」写下去的就是这四个 DEFAULT，所以它们也属于持久化契约。
        assertEquals(SubtitleTextSize.NORMAL, SubtitleTextSize.DEFAULT)
        assertEquals(SubtitleLineSpacing.NORMAL, SubtitleLineSpacing.DEFAULT)
        // 默认不开描边：老用户已有一层柔和阴影，默认加一圈硬边等于换了一副样子。
        assertEquals(SubtitleOutline.NONE, SubtitleOutline.DEFAULT)
        // 12dp = v0.5.15 的位置。
        assertEquals(SubtitleBottomMargin.NEAR, SubtitleBottomMargin.DEFAULT)
        assertEquals(12, SubtitleBottomMargin.NEAR.dp)
    }

    @Test
    fun `样式键与主题键不冲突`() {
        // 同一个文件里前缀都是 subtitle.，主题是 theme.，这里再钉一次「不是靠前缀
        // 猜出来的」——而是逐个键名比对。
        val subtitleKeys = listOf(
            SubtitleSettingsRepository.Keys.DISPLAY_MODE,
            SubtitleSettingsRepository.Keys.TEXT_SIZE,
            SubtitleSettingsRepository.Keys.LINE_SPACING,
            SubtitleSettingsRepository.Keys.OUTLINE,
            SubtitleSettingsRepository.Keys.BOTTOM_MARGIN,
        ).map { it.name }

        assertEquals(subtitleKeys.size, subtitleKeys.toSet().size)
        assertEquals(subtitleKeys.size, subtitleKeys.count { it.startsWith("subtitle.") })
    }
}
