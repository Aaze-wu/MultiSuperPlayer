package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.text.MspText

/**
 * 桌面歌词偏好。
 *
 * ## 为什么它和字幕偏好分开
 *
 * 字幕偏好的每一条都在回答「画在**画面里**的那行字长什么样」；这里回答的是
 * 「**另一个窗口**里的那两行字怎么摆」。两者的默认值、合法区间、甚至消费方
 * （一个是播放页的 Compose，一个是悬浮窗 Service）都不一样，合在一份设置里
 * 只会让「改字号」变成同时影响两个窗口的一脚踩两船。
 *
 * 共用的是**时间轴**：悬浮窗和字幕层查的是同一份 `SubtitleDocument`、
 * 同一个 `subtitleCuePosition` 换算（见 `feature:player` 的 `DesktopLyricsRules`）。
 * 那是数据，不是外观。
 *
 * ## 为什么档位是枚举
 *
 * 理由和 [SubtitleStyle] 完全一致：存浮点数的话「0.62 不透明度」既没有默认值、
 * 也没法解释，而且滑块一定没人敢拖。档位表可以加档（认不出来的值回退默认），
 * 但已发布的档位名不能改——那是写入用户设备的契约。
 */
data class DesktopLyricsSettings(
    /**
     * 用户想不想要桌面歌词。
     *
     * 这是**意图**，不是「现在有没有浮窗」：没给「显示在其他应用上层」权限、
     * 或者应用正在前台、或者根本没在放东西时，这个值仍然是 true，而浮窗不出现。
     * 把这两件事合成一个布尔的话，用户从系统设置里授权回来会发现开关自己关了。
     */
    val enabled: Boolean = false,
    val style: DesktopLyricsStyle = DesktopLyricsStyle.DEFAULT,
    /**
     * 锁住位置（防止误触拖动）。
     *
     * 它**只**禁拖动：播放/暂停与解锁按钮在锁定状态下仍然可点。做成「整窗不接收
     * 触摸」的话，锁上之后就再也解不开了——那个窗口盖在所有应用上面，用户除了
     * 杀进程没有别的出路。
     */
    val locked: Boolean = false,
    /**
     * 浮窗左上角在屏幕上的位置（像素）。
     *
     * null = 还没摆过，用「水平居中、屏幕偏下」的默认位置。用可空而不是
     * `-1` 之类的哨兵：`-1` 是一个合法坐标（屏幕左侧外一格），而默认位置要
     * 在**拿到屏幕尺寸之后**才算得出来，不能存在设置里。
     */
    val position: DesktopLyricsPosition = DesktopLyricsPosition.UNSET,
) {
    /** 有摆过位置没有。 */
    val hasPosition: Boolean get() = position.isSet
}

/**
 * 浮窗的默认位置：`null` 表示「还没摆过」。
 *
 * 两个坐标一起存、一起读：只存 x 的话，用户在竖屏摆好、转到横屏再回来，
 * y 会退回默认值——看起来像「位置自己跳了一下」。
 */
data class DesktopLyricsPosition(val x: Int?, val y: Int?) {

    val isSet: Boolean get() = x != null && y != null

    companion object {
        val UNSET = DesktopLyricsPosition(x = null, y = null)
    }
}

/** 桌面歌词的外观。和 [SubtitleStyle] 一样按「一组必须一起取默认值的参数」打包。 */
data class DesktopLyricsStyle(
    val fontSize: DesktopLyricsFontSize = DesktopLyricsFontSize.DEFAULT,
    val opacity: DesktopLyricsOpacity = DesktopLyricsOpacity.DEFAULT,
) {
    companion object {
        val DEFAULT = DesktopLyricsStyle()
    }
}

/**
 * 桌面歌词字号。
 *
 * 这里存的是**绝对 sp**，不是倍率——与 [SubtitleTextSize] 刻意相反。
 * 原因：字幕的基准字号来自应用主题（`titleMedium`），换主题/换语言都该跟着变；
 * 而悬浮窗画在**别人的应用上面**，它没有「当前主题」这回事，用一个倍率去乘
 * 谁的基准字号都说不通。`sp` 本身仍然跟随系统字体缩放，那才是这里要的。
 *
 * 18sp 起步就比系统正文大一档：浮窗通常离眼睛比手机屏幕远（放桌上、投屏边上）。
 */
enum class DesktopLyricsFontSize(val sp: Int, val label: MspText) {
    SMALL(14, MspText.Res(R.string.msp_desktop_lyrics_size_small)),

    MEDIUM(18, MspText.Res(R.string.msp_desktop_lyrics_size_medium)),

    LARGE(22, MspText.Res(R.string.msp_desktop_lyrics_size_large)),

    HUGE(28, MspText.Res(R.string.msp_desktop_lyrics_size_huge)),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** 认不出来的值回退到 [DEFAULT]，不抛异常（同 [SubtitleTextSize.fromKey]）。 */
        fun fromKey(key: String?): DesktopLyricsFontSize =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 浮窗背景的不透明度。
 *
 * 透明度是**背景**的，不是文字整体的：把 alpha 乘在整窗上会让字也跟着变淡，
 * 而「看不清字」正好是这个设置想解决的问题的反面。文字始终是纯白 + 阴影。
 *
 * 最低档也有 40%：再淡的话，浅色桌面背景上白字会直接看不见（浮窗没有画面可以
 * 借用对比度）。最高档是纯黑——想要完全不透底的用户可以选它。
 */
enum class DesktopLyricsOpacity(val percent: Int) {
    LIGHT(40),

    SOFT(60),

    STRONG(80),

    SOLID(100),
    ;

    /** 直接给 `Color.copy(alpha = ...)` 用的 0..1 系数。 */
    val alpha: Float get() = percent / 100f

    /**
     * 档位名就是百分数本身。
     *
     * 不给它写「淡 / 柔和 / 明显 / 不透明」那样的形容词：形容词在不同语言里
     * 长度差得远（四个字的中文档位名翻成英文会变成 `Semi-transparent`），
     * 而 `60%` 在任何语言下都是同一个意思，也正是用户想看到的那个数。
     */
    val label: MspText get() = MspText.Plain("$percent%")

    companion object {
        val DEFAULT = SOFT

        fun fromKey(key: String?): DesktopLyricsOpacity =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 把浮窗坐标夹进屏幕可见范围。
 *
 * 三个「必须夹」的场景，每一个都会让浮窗**跑到屏幕外**（而它没有标题栏可以拖回来）：
 *
 * 1. 用户在竖屏摆好位置，然后转成横屏：竖屏的 y 可能远大于横屏高度。
 * 2. 换设备/改分辨率/改显示大小（同一份设置被同步过来）。
 * 3. 浮窗变高（字号调大、开了双语），底部超出屏幕。
 *
 * 用纯函数而不是在 Service 里就地 `coerceIn`：这三种场景在 JVM 单测里都能钉住，
 * 而在真机上「浮窗跑到屏幕外」只能靠肉眼发现。
 *
 * @param x 期望的左边距（像素）。
 * @param y 期望的上边距（像素）。
 * @param windowWidth 浮窗宽度。
 * @param windowHeight 浮窗高度。
 * @param screenWidth 可用宽度（已扣掉系统栏）。
 * @param screenHeight 可用高度。
 */
fun clampDesktopLyricsPosition(
    x: Int,
    y: Int,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): DesktopLyricsPosition {
    // 浮窗比屏幕还宽/还高时（超大字号 + 小屏），至少让它贴着左上角，而不是
    // 算出「负数上界」再把坐标夹成负数。
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    return DesktopLyricsPosition(
        x = x.coerceIn(0, maxX),
        y = y.coerceIn(0, maxY),
    )
}
