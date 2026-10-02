package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置首页那四行副标题。
 *
 * 这一页的全部价值就是「一眼看出当前是什么状态」，所以这些测试的重点**不是**正常值，
 * 而是各种脏数据：从未设置过（`null`）、认不出来的 id（降级安装 / 手改过配置）、
 * 两个开关互相覆盖、档位表里没有的值。这些情况在真机上都能发生，而写错任何一条，
 * 这一行就会理直气壮地骗人。
 */
class SettingsSummariesTest {

    // ------------------------------------------------------------------ 外观

    @Test
    fun `外观 - 从未设置过时显示默认主题与默认强调色`() {
        assertEquals(
            "跟随系统 · 靛蓝",
            SettingsSummaries.appearance(ThemeSettings(), systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 认不出来的 id 回退到默认值而不是崩掉`() {
        val theme = ThemeSettings(baseThemeId = "no-such-theme", accentId = "rose")

        // 「rose」是存在的（绯红），「no-such-theme」不存在 ⇒ 只有基底回退。
        assertEquals(
            "跟随系统 · 绯红",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 显式选过的基底与强调色都要显示出来`() {
        val theme = ThemeSettings(baseThemeId = "black", accentId = "teal")

        assertEquals(
            "纯黑（OLED） · 青碧",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 封面取色开启时说出来`() {
        val theme = ThemeSettings(colorFromArtwork = true)

        assertEquals(
            "跟随系统 · 靛蓝 · 封面取色",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 封面取色赢过系统取色, 两个都开也只说一个`() {
        val theme = ThemeSettings(useDynamicColor = true, colorFromArtwork = true)
        val text = SettingsSummaries.appearance(theme, systemColorSupported = true)

        // 同时说「封面取色」和「系统取色」是自相矛盾的：实际生效的只有一个。
        assertEquals("跟随系统 · 靛蓝 · 封面取色", text)
    }

    @Test
    fun `外观 - 系统取色真的生效时才这么说`() {
        val theme = ThemeSettings(useDynamicColor = true)

        assertEquals(
            "跟随系统 · 靛蓝 · 系统取色",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 系统不支持时明确说未生效`() {
        val theme = ThemeSettings(useDynamicColor = true)

        // 开关开着、颜色却是强调色 —— 不说出来的话，用户只能怀疑这个开关坏了。
        assertEquals(
            "跟随系统 · 靛蓝 · 系统取色未生效",
            SettingsSummaries.appearance(theme, systemColorSupported = false),
        )
    }

    @Test
    fun `外观 - 纯黑模式下系统取色未生效`() {
        val theme = ThemeSettings(baseThemeId = "black", useDynamicColor = true)

        // 即使系统支持（Android 12+）也要走这条分支：纯黑模式下系统取色会给出深灰，
        // 正好把「省像素」这件事毁掉，所以那条路是自己关掉的。
        assertEquals(
            "纯黑（OLED） · 靛蓝 · 系统取色未生效",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 没打开系统取色时不提未生效`() {
        val theme = ThemeSettings(baseThemeId = "dark")

        // 用户自己就没想要系统取色，说「未生效」是在报告一个不存在的问题。
        assertEquals(
            "深色 · 靛蓝",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    // ------------------------------------------------------------------ 播放

    @Test
    fun `播放 - 全是默认值也显示同样三项`() {
        // 三项总是显示是有意的：只显示「非默认项」会让这一行时有时无，看起来像坏了。
        assertEquals(
            "1× · 长按 2× · 适应",
            SettingsSummaries.playback(PlaybackSettings(), softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 普通倍速如实显示, 长按倍速按档位归一`() {
        // 普通倍速是连续值（1.37× 真的是 1.37×，如实显示）；
        // 但长按倍速是**档位**：写进去时会夹进档位表，播放时也会再 normalize 一次
        // （见 PlayerViewModel），所以 2.5 实际执行的是 2×。
        // 这里必须显示 2×，否则这一行承诺的「长按 2.5×」和真实行为不一样。
        val settings = PlaybackSettings(speed = 1.37f, boostSpeed = 2.5f)

        assertEquals(
            "1.37× · 长按 2× · 适应",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 记不住的位置要变成不记位置`() {
        val settings = PlaybackSettings(rememberPosition = false)

        assertEquals(
            "1× · 长按 2× · 适应 · 不记位置",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 强制软解开启且可用时说出来`() {
        val settings = PlaybackSettings(forceSoftwareDecoding = true, aspectRatioMode = AspectRatioMode.CROP)

        assertEquals(
            "1× · 长按 2× · 裁剪 · 强制软解",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 本包没有 FFmpeg 时不说强制软解, 只说没有 FFmpeg`() {
        val settings = PlaybackSettings(forceSoftwareDecoding = true)

        // 这一条是关键：开关在「播放」页里是灰的、根本没生效，
        // 摘要却写「强制软解」就是自相矛盾。
        assertEquals(
            "1× · 长按 2× · 适应 · 本包无 FFmpeg",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = false),
        )
    }

    @Test
    fun `播放 - 没开强制软解但本包没有 FFmpeg 时也要说`() {
        // 「没有 FFmpeg」是安装包的事实，和开关无关：用户反馈「这个格式放不了」时，
        // 这一行是第一个需要被排除的原因。
        assertEquals(
            "1× · 长按 2× · 适应 · 本包无 FFmpeg",
            SettingsSummaries.playback(PlaybackSettings(), softwareDecodingAvailable = false),
        )
    }

    @Test
    fun `播放 - 长按倍速为 null 时用默认值而不是显示零`() {
        val settings = PlaybackSettings(boostSpeed = null)

        assertEquals(
            "1× · 长按 2× · 适应",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    // ------------------------------------------------------------------ 翻译

    @Test
    fun `翻译 - 什么都没填时逐项列出缺什么`() {
        // 「还不够」必须写清楚差什么：这一行是用户在播放页翻不出字幕时唯一会看的地方。
        assertEquals(
            "DeepSeek · 还不能翻译（缺：服务地址、模型名、API 密钥）",
            SettingsSummaries.translation(TranslationSettings()),
        )
    }

    @Test
    fun `翻译 - 填够了才显示模型和目标语言`() {
        val settings = TranslationSettings(
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-v3",
            apiKeyStored = true,
        )

        assertEquals("DeepSeek · deepseek-v3 · 译成简体中文", SettingsSummaries.translation(settings))
    }

    @Test
    fun `翻译 - 地址写错时说的是地址, 不是一句笼统的未配置`() {
        val settings = TranslationSettings(
            baseUrl = "api.deepseek.com",
            model = "deepseek-v3",
            apiKeyStored = true,
        )

        assertEquals(
            "DeepSeek · 还不能翻译（缺：服务地址（需要以 http:// 或 https:// 开头））",
            SettingsSummaries.translation(settings),
        )
    }

    @Test
    fun `翻译 - 不需要密钥的服务商不该把密钥列进缺失项`() {
        val settings = TranslationSettings(
            providerId = "ollama",
            baseUrl = "http://127.0.0.1:11434",
            model = "qwen2.5",
        )

        assertEquals("Ollama（本地） · qwen2.5 · 译成简体中文", SettingsSummaries.translation(settings))
    }

    // ------------------------------------------------------------------ 关于

    @Test
    fun `关于 - 有版本号时显示版本号`() {
        assertEquals("0.5.4 (50400)", SettingsSummaries.about(buildInfo()))
    }

    @Test
    fun `关于 - 构建脚本没注入版本号时显示未知而不是空白`() {
        // 空白行看起来像「这一页还没做完」，而「未知」说明是构建脚本的问题。
        assertEquals("未知", SettingsSummaries.about(AppBuildInfo.Unknown))
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun `日志 - 列出文件数量与总体积`() {
        val summary = LogSummary(
            fileCount = 3,
            totalBytes = 1024L * 1024L,
            oldestDay = "2026-10-01",
            newestDay = "2026-10-03",
        )

        val text = SettingsSummaries.logFiles(summary)

        // 体积那一段交给 TimeFormat 自己格式化（两位有效数字、单位换算），
        // 这里只钉住「数量 · 体积」这个形状：少了分隔号整行会变成一坨数字。
        assertTrue(text.startsWith("3 个 · "))
        assertTrue(text.endsWith("MB"))
    }

    @Test
    fun `日志 - 一个文件都没有时明说而不是显示零`() {
        // 「0 个 · 0 B」看起来像统计出错；事实是「这个安装包还没产生过日志」，
        // 而这一点直接决定了用户要不要去点「导出日志」。
        val text = SettingsSummaries.logFiles(LogSummary.Empty)

        assertEquals("还没有任何日志文件", text)
        assertFalse(text.contains("0 个"))
    }

    @Test
    fun `日志 - 只有一天时只显示一天`() {
        // 写成 `2026-10-02 ~ 2026-10-02` 会被读成「这中间还有几天」，
        // 用户会以为日志丢过。
        val summary = LogSummary(1, 2048L, "2026-10-02", "2026-10-02")

        assertEquals("2026-10-02", SettingsSummaries.logRange(summary))
    }

    @Test
    fun `日志 - 跨天时显示区间`() {
        val summary = LogSummary(4, 2048L, "2026-10-02", "2026-10-05")

        assertEquals("2026-10-02 ~ 2026-10-05", SettingsSummaries.logRange(summary))
    }

    @Test
    fun `日志 - 日期读不到时给个占位符而不是空白`() {
        // 空白行在关于页里读起来像「还没加载完」，而事实上永远不会加载出来了。
        assertEquals("—", SettingsSummaries.logRange(LogSummary(2, 2048L, null, null)))
        // 只丢了一头时，有的那头仍然要说出来。
        assertEquals("2026-10-02", SettingsSummaries.logRange(LogSummary(2, 2048L, "2026-10-02", null)))
    }

    private fun buildInfo() = AppBuildInfo(
        versionName = "0.5.4",
        versionCode = 50400,
        gitCommit = "09b06a6",
        gitTag = "v0.5.3",
        gitDirty = false,
        buildTimeText = "2026-10-02 15:30 +08:00",
    )
}
