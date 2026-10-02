package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.describeMissingItems
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults

/**
 * 设置首页四个入口的副标题。
 *
 * 为什么要把它们抽成**纯函数**而不是直接写在 `Composable` 里：
 *
 * 1. 入口页的全部价值就在这几行字上。如果副标题写的是功能说明（「调整播放速度」），
 *    这一页就退化成了目录，用户点进去才知道自己是不是要改的东西——那还不如直接
 *    把所有设置铺开（这正是这次重写要去掉的形态）。副标题必须是**当前状态**，
 *    所以它值得被测试钉住。
 * 2. 它们是「当前状态 → 一句话」的映射，没有任何界面依赖，可以在 JVM 单测里
 *    直接喂各种脏数据（未设置、旧值不在档位表里、开关互相覆盖）检查输出。
 *
 * 返回 [MspText] 而不是 `String`：这些句子里混着主题名、语言名和「缺哪一项」，
 * 全都要跟着界面语言走；而取值（倍速、文件大小、日期范围）又和语言无关。
 * 把「哪一句 + 什么参数」写下来，解析留给 UI 那一层，纯函数就能继续在 JVM 里测。
 *
 * 片段之间用 [MspText.join] 拼，分隔符是 [SEPARATOR] 这条资源——**不在代码里拼 `String`**：
 * 那样测试就只能看到一整条拼好的句子，说不出「哪几段」。
 *
 * 全部是单行文本。`ListItem` 的 supporting 文本会在需要时折行，但两行以上就说明
 * 摘要写多了——入口页应该一眼扫完。
 */
internal object SettingsSummaries {

    /** 摘要片段之间的分隔符。译者可以换成「・」或者「, 」。 */
    private val SEPARATOR: MspText = MspText.Res(R.string.msp_settings_summary_sep)

    /** 拼一段摘要：`null` 的片段直接跳过（「没有这条尾巴」比「空字符串」更好读）。 */
    private fun join(vararg parts: MspText?): MspText =
        MspText.join(SEPARATOR, parts.filterNotNull())

    /**
     * 外观：`纯黑（OLED） · 青碧 · 封面取色`。
     *
     * @param systemColorSupported 本机是否支持系统取色（Android 12+）。**必须由调用方传入**：
     *   判断要用 `Build.VERSION.SDK_INT`，而它在 JVM 单测里恒为 0，写进纯函数就再也
     *   测不了「能取色」的那条分支了。
     *
     * 「系统取色未生效」这个尾巴是有意加的：开关开着但实际没生效时，用户看到的颜色
     * 是强调色，而他在这一行上完全无从知道为什么。说出来，再在「外观」页里解释原因。
     */
    fun appearance(theme: ThemeSettings, systemColorSupported: Boolean): MspText {
        val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
        val accent = MspAccent.fromId(theme.accentId)
        // 两个取色开关都是「有值才生效」的可空布尔，兜底值只从 MspThemeDefaults 来，
        // 不写字面量——曾经这里写的是 `?: true`，正是「点强调色没反应」的成因。
        val artworkColor = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK
        val dynamicWanted = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR
        // 纯黑模式下系统取色给的是一堆深灰，正好把「省像素」这件事毁掉，所以那条路不通。
        val dynamicBlockedByOled = baseTheme == MspBaseTheme.BLACK
        val dynamicActive = dynamicWanted && systemColorSupported && !dynamicBlockedByOled && !artworkColor

        // 覆盖优先级：封面取色 > 系统取色 > 强调色。同时说两个是自相矛盾的：实际生效的只有一个。
        val tail = when {
            artworkColor -> MspText.Res(R.string.msp_settings_summary_tail_artwork)
            dynamicActive -> MspText.Res(R.string.msp_settings_summary_tail_dynamic)
            dynamicWanted -> MspText.Res(R.string.msp_settings_summary_tail_dynamic_blocked)
            else -> null
        }
        return join(baseTheme.label, accent.label, tail)
    }

    /**
     * 播放：`1.0× · 长按 2.0× · 适应`，后面按需挂上几个「偏离默认」的状态。
     *
     * 前三个总是显示（哪怕都是默认值）：摘要的长度可预期，用户才知道自己每次都看到了
     * 同样的三件事；只把「非默认」的项显出来，会让这一行时有时无，看起来像坏了。
     * 后面的旗标相反——它们只在偏离默认时出现，因为「没有强制软解」不是一件需要提醒的事。
     */
    fun playback(playback: PlaybackSettings, softwareDecodingAvailable: Boolean): MspText {
        val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
        val boost = SpeedBoostOptions.normalize(playback.boostSpeed)
        val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
        val forceSoftware = playback.forceSoftwareDecoding ?: false
        val rememberPosition = playback.rememberPosition ?: true

        return join(
            MspText.Plain(PlaybackSpeedOptions.format(speed)),
            MspText.Res(R.string.msp_settings_summary_long_press, SpeedBoostOptions.format(boost)),
            aspect.label,
            // 「强制软解」只有在真的能生效时才说：本安装包不含 FFmpeg 时那个开关在
            // 「播放」页里是灰的，摘要却声称已开启，就是自相矛盾。
            if (forceSoftware && softwareDecodingAvailable) {
                MspText.Res(R.string.msp_settings_summary_tail_force_software)
            } else {
                null
            },
            if (softwareDecodingAvailable) null else MspText.Res(R.string.msp_settings_summary_tail_no_ffmpeg),
            if (rememberPosition) null else MspText.Res(R.string.msp_settings_summary_tail_no_position),
        )
    }

    /**
     * 字幕与翻译：`硅基流动 · deepseek-v3 · 译成中文`，没配好时直接说缺什么。
     *
     * 「缺什么」必须写出来。这一行是用户最常看的（从播放页的字幕面板翻不出来时，
     * 他只想确认这两件事），只写「未配置」等于让他点进三层页面自己找。
     *
     * 缺项清单来自 [TranslationSettings.missingItems]，和「测试连接」按钮同源——
     * 在这里再判一次就会出现「这一行说缺密钥、点进去却说齐了」这种自相矛盾。
     */
    fun translation(translation: TranslationSettings): MspText {
        val provider = translation.provider.displayName
        if (!translation.ready) {
            return join(
                provider,
                MspText.Res(
                    R.string.msp_settings_summary_translation_incomplete,
                    describeMissingItems(translation.missingItems),
                ),
            )
        }
        // 模型名是用户自己填的（与语言无关），但「没填」这件事得说出来。
        val model = translation.model.takeIf { it.isNotBlank() }
            ?.let(MspText::Plain)
            ?: MspText.Res(R.string.msp_settings_summary_model_unset)
        return join(
            provider,
            model,
            MspText.Res(R.string.msp_settings_summary_translate_to, translation.target.label),
        )
    }

    /** 关于：只放版本号。检查更新、开源许可、导出日志都在这一页里，不必再挤进副标题。 */
    fun about(buildInfo: AppBuildInfo): MspText = buildInfo.summaryText()

    /**
     * 关于页的日志概览：`3 个 · 1.2 MB`。
     *
     * 没有文件时明说，不显示「0 个 · 0 B」——那读起来像是统计坏了，
     * 而事实是「这个安装包还没产生过日志」，「导出日志」按钮也就是点得出东西的（见 AboutScreen）。
     */
    fun logFiles(summary: LogSummary): MspText {
        if (summary.isEmpty) return MspText.Res(R.string.msp_settings_summary_no_logs)
        return MspText.Res(
            R.string.msp_settings_summary_log_usage,
            summary.fileCount,
            TimeFormat.fileSize(summary.totalBytes),
        )
    }

    /**
     * 日志覆盖的日期范围：`2026-10-02 ~ 2026-10-05`。
     *
     * 只剩一天时**只显示一天**：写成 `2026-10-02 ~ 2026-10-02` 会被读成「这中间有几天」，
     * 用户就会怀疑日志丢了几天。
     *
     * 日期用 [MspText.Plain]：ISO 日期和 `~` 与界面语言无关，做成待翻译的文案只会
     * 让翻译者以为自己要动它。
     */
    fun logRange(summary: LogSummary): MspText {
        val oldest = summary.oldestDay ?: return MspText.Res(R.string.msp_settings_summary_no_range)
        val newest = summary.newestDay ?: return MspText.Plain(oldest)
        return MspText.Plain(if (oldest == newest) oldest else "$oldest ~ $newest")
    }
}
