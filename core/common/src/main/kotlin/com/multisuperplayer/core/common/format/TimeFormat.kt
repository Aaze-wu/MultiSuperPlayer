package com.multisuperplayer.core.common.format

import com.multisuperplayer.core.common.R
import com.multisuperplayer.core.common.text.MspText

/**
 * 时间 / 体积格式化。
 *
 * 全部为纯函数，便于单测；所有函数对负数、超大值都必须安全（播放器拿到的
 * 时长在未知时是 `C.TIME_UNSET`，即一个很大的负数，绝不能直接喂给 UI）。
 *
 * 只有 [humanDuration] 返回 [MspText]（「1 小时 3 分」带单位词，要翻译）；
 * 其余几个的输出全由数字、冒号和 `B/KB/MB` 组成，与语言无关，改动它们只会
 * 给译者增加噪音，所以保持 `String`。
 */
object TimeFormat {

    /** 播放器未知时长/位置的哨兵值（与 `androidx.media3.common.C.TIME_UNSET` 一致）。 */
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1

    /** `02:31`；超过 1 小时为 `1:02:31`。未知/负数 → `--:--`。 */
    fun clock(ms: Long): String {
        if (ms < 0 || ms == TIME_UNSET) return "--:--"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** `02:31.45`，歌词时间轴/字幕调试用。 */
    fun clockWithCentis(ms: Long): String {
        if (ms < 0 || ms == TIME_UNSET) return "--:--.--"
        val totalSeconds = ms / 1000
        val centis = (ms % 1000) / 10
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d.%02d".format(hours, minutes, seconds, centis)
        } else {
            "%02d:%02d.%02d".format(minutes, seconds, centis)
        }
    }

    /**
     * `1 小时 3 分` / `3 分 20 秒` / `8 秒`。用于「共 N 首 / 总时长」。
     *
     * 返回 [MspText] 而不是 `String`：单位词要翻译，而这里又必须保持纯函数。
     *
     * 三种单位各自「有没有值」组合出 7 种写法，所以资源里是 7 条而不是
     * 3 条 + 拼接：英文 `1 h 3 min 20 s` 与中文语序一致，但换成别的语言
     * （例如需要 `und`、或需要把秒写在最前）就只能靠整条文案，靠拼接是拼不出来的。
     */
    fun humanDuration(ms: Long): MspText {
        if (ms <= 0) return MspText.Res(R.string.msp_duration_seconds, 0)
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours <= 0 && minutes <= 0 -> MspText.Res(R.string.msp_duration_seconds, seconds)
            hours <= 0 && seconds <= 0 -> MspText.Res(R.string.msp_duration_minutes, minutes)
            hours <= 0 -> MspText.Res(R.string.msp_duration_minutes_seconds, minutes, seconds)
            minutes <= 0 && seconds <= 0 -> MspText.Res(R.string.msp_duration_hours, hours)
            minutes <= 0 -> MspText.Res(R.string.msp_duration_hours_seconds, hours, seconds)
            seconds <= 0 -> MspText.Res(R.string.msp_duration_hours_minutes, hours, minutes)
            else -> MspText.Res(R.string.msp_duration_full, hours, minutes, seconds)
        }
    }

    /** `1.2 GB` / `340 MB`。 */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            "%.1f %s".format(value, units[unitIndex])
        }
    }

    /**
     * `2026-10-02 15:30`（本地时区）。
     *
     * 刻意不写秒：它出现在「构建时间」和「日志文件时间」上，读的人只需要知道大概，
     * 而秒会让同一件事在两个地方显示出不同的值，反而引起「是不是不同版本」的怀疑。
     *
     * 非法值（`<= 0`，例如构建脚本没能取到时间戳）返回 `--`，不返回 1970 年——
     * 一个假的日期比一个明显的占位符更容易误导。
     */
    fun dateTime(ms: Long): String {
        if (ms <= 0) return "--"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(ms))
    }
}
