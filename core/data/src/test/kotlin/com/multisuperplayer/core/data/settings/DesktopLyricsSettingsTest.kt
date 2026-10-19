package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面歌词偏好映射的单元测试。
 *
 * 两类失败值得钉住：
 *
 * 1. **键名漂移**——这些字面量是写进用户设备的契约，改名等于把所有人的桌面
 *    歌词设置清空。所以一律用字面量读写，而不是 `Keys.X.name`。
 * 2. **坐标只坏一半**——x/y 只有一个是有效值时必须按「没摆过」处理，否则浮窗
 *    会用缺一半的位置开窗，而它没有标题栏可以把用户拖回来。
 */
class DesktopLyricsSettingsTest {

    private val enabledKey = booleanPreferencesKey("desktop_lyrics.enabled")
    private val fontSizeKey = stringPreferencesKey("desktop_lyrics.font_size")
    private val opacityKey = stringPreferencesKey("desktop_lyrics.opacity")
    private val lockedKey = booleanPreferencesKey("desktop_lyrics.locked")
    private val positionXKey = intPreferencesKey("desktop_lyrics.position_x")
    private val positionYKey = intPreferencesKey("desktop_lyrics.position_y")

    @Test
    fun `全新安装时不显示桌面歌词`() {
        val settings = preferencesOf().toDesktopLyricsSettings()

        // 默认关闭：桌面歌词是一个盖在所有应用上面的窗口，用户没点过就不该出现。
        assertFalse(settings.enabled)
        assertFalse(settings.locked)
        assertEquals(DesktopLyricsStyle.DEFAULT, settings.style)
        assertFalse(settings.position.isSet)
        assertFalse(settings.hasPosition)
    }

    @Test
    fun `开关与锁定都能原样存取`() {
        assertTrue(preferencesOf(enabledKey to true).toDesktopLyricsSettings().enabled)
        assertTrue(preferencesOf(lockedKey to true).toDesktopLyricsSettings().locked)

        // 显式写 false 也要能读回 false（不是「键在但值是 false」被当成缺键）。
        assertFalse(preferencesOf(enabledKey to false).toDesktopLyricsSettings().enabled)
    }

    @Test
    fun `字号档位都能原样存取`() {
        DesktopLyricsFontSize.entries.forEach { size ->
            val stored = preferencesOf(fontSizeKey to size.name).toDesktopLyricsSettings()
            assertEquals(size, stored.style.fontSize)
        }
    }

    @Test
    fun `不透明度档位都能原样存取`() {
        DesktopLyricsOpacity.entries.forEach { opacity ->
            val stored = preferencesOf(opacityKey to opacity.name).toDesktopLyricsSettings()
            assertEquals(opacity, stored.style.opacity)
        }
    }

    @Test
    fun `认不出来的值回退到默认而不是抛异常`() {
        val settings = preferencesOf(
            fontSizeKey to "GIGANTIC",
            opacityKey to "",
        ).toDesktopLyricsSettings()

        assertEquals(DesktopLyricsFontSize.DEFAULT, settings.style.fontSize)
        assertEquals(DesktopLyricsOpacity.DEFAULT, settings.style.opacity)
    }

    @Test
    fun `两个坐标都在才算摆过位置`() {
        val both = preferencesOf(positionXKey to 42, positionYKey to 300)
            .toDesktopLyricsSettings()
        assertEquals(DesktopLyricsPosition(42, 300), both.position)
        assertTrue(both.hasPosition)

        // 只写了 x（旧版本、或者写盘被打断）：按「没摆过」处理。
        val onlyX = preferencesOf(positionXKey to 42).toDesktopLyricsSettings()
        assertEquals(DesktopLyricsPosition.UNSET, onlyX.position)
        assertFalse(onlyX.hasPosition)

        val onlyY = preferencesOf(positionYKey to 300).toDesktopLyricsSettings()
        assertFalse(onlyY.hasPosition)
    }

    @Test
    fun `坐标可以是零和负数以外的任何屏内位置`() {
        // 0 是合法坐标（贴屏幕左上角），不能被当成「没摆过」。
        val atOrigin = preferencesOf(positionXKey to 0, positionYKey to 0)
            .toDesktopLyricsSettings()
        assertEquals(DesktopLyricsPosition(0, 0), atOrigin.position)
        assertTrue(atOrigin.hasPosition)
    }

    @Test
    fun `枚举名字按字面量锁住`() {
        assertEquals("SMALL", DesktopLyricsFontSize.SMALL.name)
        assertEquals("MEDIUM", DesktopLyricsFontSize.MEDIUM.name)
        assertEquals("LARGE", DesktopLyricsFontSize.LARGE.name)
        assertEquals("HUGE", DesktopLyricsFontSize.HUGE.name)

        assertEquals("LIGHT", DesktopLyricsOpacity.LIGHT.name)
        assertEquals("SOFT", DesktopLyricsOpacity.SOFT.name)
        assertEquals("STRONG", DesktopLyricsOpacity.STRONG.name)
        assertEquals("SOLID", DesktopLyricsOpacity.SOLID.name)
    }

    @Test
    fun `不透明度百分比递增且首尾是 40 与 100`() {
        val percents = DesktopLyricsOpacity.entries.map { it.percent }

        assertEquals(percents.sorted(), percents)
        // 最低档也要能看清白字：再淡的话浅色桌面上字就消失了。
        assertTrue("最低档不能低于 40%", percents.first() >= 40)
        // 最高档必须是完全不透，那是「我不想要透底」的用户唯一能选到的答案。
        assertEquals(100, percents.last())
        assertEquals(1f, DesktopLyricsOpacity.SOLID.alpha, 0.0001f)
    }

    @Test
    fun `字号档位递增且与大屏阅读距离匹配`() {
        val sizes = DesktopLyricsFontSize.entries.map { it.sp }

        assertEquals(sizes.sorted(), sizes)
        assertEquals("档位不能重复，否则两个档位看起来一样", sizes.distinct(), sizes)
        assertTrue("最小档也不能小到读不出来", sizes.first() >= 12)
    }

    @Test
    fun `字幕与桌面歌词共用一个设置文件但键不冲突`() {
        // 两者都落在 msp_settings 里。键名撞了会让「调桌面歌词字号」把字幕字号
        // 改掉——症状离原因非常远（在播放页调了没反应，而字幕变了）。
        val subtitleKeys = listOf(
            SubtitleSettingsRepository.Keys.DISPLAY_MODE.name,
            SubtitleSettingsRepository.Keys.TEXT_SIZE.name,
            SubtitleSettingsRepository.Keys.LINE_SPACING.name,
            SubtitleSettingsRepository.Keys.OUTLINE.name,
            SubtitleSettingsRepository.Keys.BOTTOM_MARGIN.name,
        )
        val desktopKeys = listOf(
            DesktopLyricsSettingsRepository.Keys.ENABLED.name,
            DesktopLyricsSettingsRepository.Keys.FONT_SIZE.name,
            DesktopLyricsSettingsRepository.Keys.OPACITY.name,
            DesktopLyricsSettingsRepository.Keys.LOCKED.name,
            DesktopLyricsSettingsRepository.Keys.POSITION_X.name,
            DesktopLyricsSettingsRepository.Keys.POSITION_Y.name,
        )

        assertEquals(desktopKeys.distinct(), desktopKeys)
        assertTrue(
            "桌面歌词的键与字幕的键不能重名",
            desktopKeys.none { it in subtitleKeys },
        )
        assertTrue(
            "桌面歌词的键必须落在 desktop_lyrics. 前缀下",
            desktopKeys.all { it.startsWith("desktop_lyrics.") },
        )
    }

    @Test
    fun `桌面歌词与字幕的不同偏好能同时读出来`() {
        val both = preferencesOf(
            stringPreferencesKey("subtitle.text_size") to SubtitleTextSize.HUGE.name,
            fontSizeKey to DesktopLyricsFontSize.SMALL.name,
        )

        assertEquals(SubtitleTextSize.HUGE, both.toSubtitleSettings().style.textSize)
        assertEquals(DesktopLyricsFontSize.SMALL, both.toDesktopLyricsSettings().style.fontSize)
    }

    // ------------------------------------------------------------ 位置夹取

    @Test
    fun `屏幕内的坐标原样保留`() {
        val clamped = clampDesktopLyricsPosition(
            x = 100,
            y = 800,
            windowWidth = 900,
            windowHeight = 200,
            screenWidth = 1080,
            screenHeight = 2000,
        )

        assertEquals(DesktopLyricsPosition(100, 800), clamped)
    }

    @Test
    fun `竖屏摆好的位置在横屏里被夹回屏内`() {
        // 竖屏摆了 y=1500，转横屏后可用高度只有 900——不夹的话浮窗直接消失。
        val clamped = clampDesktopLyricsPosition(
            x = 0,
            y = 1500,
            windowWidth = 900,
            windowHeight = 200,
            screenWidth = 1920,
            screenHeight = 900,
        )

        assertEquals(DesktopLyricsPosition(0, 700), clamped)
    }

    @Test
    fun `负坐标被夹到贴边而不是留在屏幕外`() {
        val clamped = clampDesktopLyricsPosition(
            x = -120,
            y = -40,
            windowWidth = 900,
            windowHeight = 200,
            screenWidth = 1080,
            screenHeight = 2000,
        )

        assertEquals(DesktopLyricsPosition(0, 0), clamped)
    }

    @Test
    fun `浮窗比屏幕还大时贴着左上角`() {
        // 超大字号 + 小屏：maxX/maxY 会算成负数。此时不能把坐标夹成负数
        // （那会让浮窗左边被切掉一半），贴 0 才是唯一可见的答案。
        val clamped = clampDesktopLyricsPosition(
            x = 10,
            y = 10,
            windowWidth = 1200,
            windowHeight = 400,
            screenWidth = 1080,
            screenHeight = 300,
        )

        assertEquals(DesktopLyricsPosition(0, 0), clamped)
    }

    @Test
    fun `夹取之后两个坐标都在且可存回`() {
        val clamped = clampDesktopLyricsPosition(
            x = 5000,
            y = 5000,
            windowWidth = 900,
            windowHeight = 200,
            screenWidth = 1080,
            screenHeight = 2000,
        )

        assertTrue(clamped.isSet)
        assertEquals(1080 - 900, clamped.x)
        assertEquals(2000 - 200, clamped.y)
    }
}
