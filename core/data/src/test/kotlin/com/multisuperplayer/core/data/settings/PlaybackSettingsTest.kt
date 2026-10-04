package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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

    @Test
    fun `长按倍速的键名也是写入用户设备的契约`() {
        // 和软件解码开关同理：改了字面量，已安装用户的值就读不出来，
        // 表现为「升级后长按倍速自己变回 2×」。数据层**不做收敛**
        // （档位表在 core:player 的 SpeedBoostOptions 里，收敛只发生一次），
        // 所以这里存进去什么就该读出来什么——包括一个不在档位上的值。
        val settings = preferencesOf(
            floatPreferencesKey("playback.boost_speed") to 3f,
        ).toPlaybackSettings()

        assertEquals(3f, settings.boostSpeed!!, 0f)
    }

    @Test
    fun `没设置过长按倍速时是 null 而不是 0`() {
        // 0 会被 `coerceIn` 之类的东西悄悄放过，然后在界面上显示成
        // 「按住 = 0×」——按下去像卡住。null 才是「没设置过」，由
        // SpeedBoostOptions 统一给出默认值。
        val empty = preferencesOf().toPlaybackSettings()

        assertNull(empty.boostSpeed)

        // 但「写过 0」是**另一个**事实：数据层如实读出来，不替上层判断
        // 这个值合不合理（上层有一次性的收敛，见 SpeedBoostOptions）。
        val zero = preferencesOf(
            floatPreferencesKey("playback.boost_speed") to 0f,
        ).toPlaybackSettings()

        assertNotNull(zero.boostSpeed)
        assertEquals(0f, zero.boostSpeed!!, 0f)
    }

    @Test
    fun `长按倍速不会被别的键带出来`() {
        // 和 speed 挨着放的两个浮点键，写错了不会报错，只会让「默认倍速」
        // 把「长按倍速」的值顶掉。
        val settings = preferencesOf(
            floatPreferencesKey("playback.speed") to 1.5f,
        ).toPlaybackSettings()

        assertNull(settings.boostSpeed)
        assertEquals(1.5f, settings.speed!!, 0f)
    }

    @Test
    fun `记录最近播放的键名是写入用户设备的契约`() {
        // 同上的理由：改了字面量，已安装用户「关掉记录」的选择就读不出来，
        // 表现为「升级之后又开始记播放历史了」——这种隐私相关的回退最容易被当成 bug。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.record_recent_plays") to false,
        ).toPlaybackSettings()

        assertEquals(false, settings.recordRecentPlays)
    }

    @Test
    fun `没设置过记录最近播放时是 null`() {
        // 这里的 null 有具体含义：**默认记**（内核按 `!= false` 判）。
        // 换成 false 就等于「全新安装的用户不记播放历史」，而设置页显示的却是
        // 开关打开——一个自相矛盾的界面，而且没有人会去查。
        assertNull(preferencesOf().toPlaybackSettings().recordRecentPlays)
    }

    @Test
    fun `记录最近播放不会被记住位置带出来`() {
        // 两个键挨着放、名字也像，写错一个不会报错，只会让「关掉播放历史」
        // 顺手把「接着播」也关掉（或者反过来）。它们必须是两个独立的键，
        // 因为用户会想要「每次都从头播、但看得见看过什么」。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.remember_position") to false,
        ).toPlaybackSettings()

        assertEquals(false, settings.rememberPosition)
        assertNull(settings.recordRecentPlays)
    }

    @Test
    fun `记住位置不会被记录最近播放带出来`() {
        // 反方向再钉一遍，免得将来只修一边。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.record_recent_plays") to false,
        ).toPlaybackSettings()

        assertEquals(false, settings.recordRecentPlays)
        assertNull(settings.rememberPosition)
    }

    @Test
    fun `均衡器的两个键名是写入用户设备的契约`() {
        // 同上的理由：改了字面量，已安装用户「关掉均衡器」的选择和调好的曲线
        // 都读不出来，表现为「升级之后声音忽然变回原样了」——用户不会把一个
        // 音效设置的丢失和「升级」联系起来，只会觉得播放器变难听了。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.equalizer_enabled") to true,
            stringPreferencesKey("playback.equalizer_band_gains") to "60:6.0,230:4.0",
        ).toPlaybackSettings()

        assertEquals(true, settings.equalizerEnabled)
        assertEquals("60:6.0,230:4.0", settings.equalizerBandGains)
    }

    @Test
    fun `没设置过均衡器时开关是 null 而不是 false`() {
        // null = 「让上层按默认值来」（默认关）。写成 false 就等于数据层替上层
        // 决定了默认值；哪天默认改成开，这个副本不会跟着变，症状是
        // 「全新安装的用户和升级上来的用户行为不一样」。
        val empty = preferencesOf().toPlaybackSettings()

        assertNull(empty.equalizerEnabled)
        assertNull(empty.equalizerBandGains)
    }

    @Test
    fun `关掉均衡器不等于把曲线也删掉`() {
        // 两个键必须分开：用户会「先关掉听两句对比」，然后还想把曲线开回来。
        // 共用一个键（或者关掉时顺手把曲线清空）的实现在这里就会变成
        // 「关一次，调好的设置就没了」。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.equalizer_enabled") to false,
            stringPreferencesKey("playback.equalizer_band_gains") to "60:7.0,230:4.0",
        ).toPlaybackSettings()

        assertEquals(false, settings.equalizerEnabled)
        assertEquals("60:7.0,230:4.0", settings.equalizerBandGains)
    }

    @Test
    fun `均衡器曲线不会被别的字符串键带出来`() {
        // 播放设置里有三个字符串键（画面比例、均衡器曲线，加上主题/字幕/翻译的），
        // 前缀看着都沾边。读错一个不会报错，只会让均衡器面板一打开就显示一条
        // 莫名其妙的曲线——而「BILINGUAL」这种值解析出来恰好是空，看起来像
        // 「我的设置丢了」。
        val settings = preferencesOf(
            stringPreferencesKey("playback.aspect_ratio_mode") to "crop",
            stringPreferencesKey("subtitle.display_mode") to "BILINGUAL",
            stringPreferencesKey("theme.base") to "black",
        ).toPlaybackSettings()

        assertNull(settings.equalizerBandGains)
        assertNull(settings.equalizerEnabled)
    }

    @Test
    fun `均衡器开关不会被别的布尔键带出来`() {
        val settings = preferencesOf(
            booleanPreferencesKey("playback.record_recent_plays") to true,
            booleanPreferencesKey("translation.auto_translate") to true,
        ).toPlaybackSettings()

        assertNull(settings.equalizerEnabled)
    }

    @Test
    fun `信任不受信任证书的键名是写入用户设备的契约`() {
        // 这一条比别的键更要紧：如果键名改了，已安装用户**打开过**的开关会读不出来，
        // 表现为「升级后自建服务器又播不了了」——用户会以为升级把功能弄坏了。
        // 反过来如果哪天有人把这个键名复用成别的语义（比如域名白名单），
        // 这些用户的选择就会被默默换成另一种意思，同样只有这条测试能拦住。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.trust_untrusted_certificates") to true,
        ).toPlaybackSettings()

        assertEquals(true, settings.trustUntrustedCertificates)
    }

    @Test
    fun `没设置过信任证书时是 null 而不是关着`() {
        // null = 「没设置过」，由内核的默认值（关）决定行为。写成 false 就是把
        // 默认值搬进了数据层；而这个开关的默认值是不能漂移的那一个——
        // 哪天有人在数据层把默认值改成 true，等于给所有用户默认关掉证书校验。
        assertNull(preferencesOf().toPlaybackSettings().trustUntrustedCertificates)
    }

    @Test
    fun `信任证书不会被别的布尔键带出来`() {
        // 播放设置里布尔键已经有一把（软件解码、记住位置、记录历史、均衡器），
        // 前缀全都以 `playback.` 开头，写错一个不会报错，只会让「开一个开关
        // 把另一个也开上」——而这里多开的那一个会静默降低安全性。
        val settings = preferencesOf(
            booleanPreferencesKey("playback.force_software_decoding") to true,
            booleanPreferencesKey("playback.equalizer_enabled") to true,
            booleanPreferencesKey("theme.dynamic_color") to true,
        ).toPlaybackSettings()

        assertNull(settings.trustUntrustedCertificates)
    }
}
