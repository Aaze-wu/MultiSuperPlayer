package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
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
 * 全部返回单行文本。`ListItem` 的 supporting 文本会在需要时折行，但两行以上就说明
 * 摘要写多了——入口页应该一眼扫完。
 */
internal object SettingsSummaries {

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
    fun appearance(theme: ThemeSettings, systemColorSupported: Boolean): String {
        val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
        val accent = MspAccent.fromId(theme.accentId)
        // 两个取色开关都是「有值才生效」的可空布尔，兜底值只从 MspThemeDefaults 来，
        // 不写字面量——曾经这里写的是 `?: true`，正是「点强调色没反应」的成因。
        val artworkColor = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK
        val dynamicWanted = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR
        // 纯黑模式下系统取色给的是一堆深灰，正好把「省像素」这件事毁掉，所以那条路不通。
        val dynamicBlockedByOled = baseTheme == MspBaseTheme.BLACK
        val dynamicActive = dynamicWanted && systemColorSupported && !dynamicBlockedByOled && !artworkColor

        return buildString {
            append(baseTheme.displayName)
            append(" · ")
            append(accent.displayName)
            when {
                artworkColor -> append(" · 封面取色")
                // 覆盖优先级：封面取色 > 系统取色 > 强调色。
                dynamicActive -> append(" · 系统取色")
                dynamicWanted -> append(" · 系统取色未生效")
            }
        }
    }

    /**
     * 播放：`1.0× · 长按 2.0× · 适应`，后面按需挂上几个「偏离默认」的状态。
     *
     * 前三个总是显示（哪怕都是默认值）：摘要的长度可预期，用户才知道自己每次都看到了
     * 同样的三件事；只把「非默认」的项显出来，会让这一行时有时无，看起来像坏了。
     * 后面的旗标相反——它们只在偏离默认时出现，因为「没有强制软解」不是一件需要提醒的事。
     */
    fun playback(playback: PlaybackSettings, softwareDecodingAvailable: Boolean): String {
        val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
        val boost = SpeedBoostOptions.normalize(playback.boostSpeed)
        val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
        val forceSoftware = playback.forceSoftwareDecoding ?: false
        val rememberPosition = playback.rememberPosition ?: true

        return buildString {
            append(PlaybackSpeedOptions.format(speed))
            append(" · 长按 ").append(SpeedBoostOptions.format(boost))
            append(" · ").append(aspect.label)
            // 「强制软解」只有在真的能生效时才说：本安装包不含 FFmpeg 时那个开关在
            // 「播放」页里是灰的，摘要却声称已开启，就是自相矛盾。
            if (forceSoftware && softwareDecodingAvailable) append(" · 强制软解")
            if (!softwareDecodingAvailable) append(" · 本包无 FFmpeg")
            if (!rememberPosition) append(" · 不记位置")
        }
    }

    /**
     * 字幕与翻译：`硅基流动 · deepseek-v3 · 译成中文`，没配好时直接说缺什么。
     *
     * 「缺什么」必须写出来。这一行是用户最常看的（从播放页的字幕面板翻不出来时，
     * 他只想确认这两件事），只写「未配置」等于让他点进三层页面自己找。
     */
    fun translation(translation: TranslationSettings): String {
        val provider = translation.provider.displayName
        val model = translation.model.ifBlank { "未填模型名" }
        val target = translation.target.label
        return if (translation.ready) {
            "$provider · $model · 译成$target"
        } else {
            "$provider · 还不能翻译（缺：${translation.missingItems.joinToString("、")}）"
        }
    }

    /** 关于：只放版本号。检查更新、开源许可、导出日志都在这一页里，不必再挤进副标题。 */
    fun about(buildInfo: AppBuildInfo): String = buildInfo.summaryText()

    /**
     * 关于页的日志概览：`3 个 · 1.2 MB`。
     *
     * 没有文件时明说，不显示「0 个 · 0 B」——那读起来像是统计坏了，
     * 而事实是「这个安装包还没产生过日志」，「导出日志」按钮也就是点得出东西的（见 AboutScreen）。
     */
    fun logFiles(summary: LogSummary): String {
        if (summary.isEmpty) return "还没有任何日志文件"
        return "${summary.fileCount} 个 · ${TimeFormat.fileSize(summary.totalBytes)}"
    }

    /**
     * 日志覆盖的日期范围：`2026-10-02 ~ 2026-10-05`。
     *
     * 只剩一天时**只显示一天**：写成 `2026-10-02 ~ 2026-10-02` 会被读成「这中间有几天」，
     * 用户就会怀疑日志丢了几天。
     */
    fun logRange(summary: LogSummary): String {
        val oldest = summary.oldestDay ?: return "—"
        val newest = summary.newestDay ?: return oldest
        return if (oldest == newest) oldest else "$oldest ~ $newest"
    }
}
