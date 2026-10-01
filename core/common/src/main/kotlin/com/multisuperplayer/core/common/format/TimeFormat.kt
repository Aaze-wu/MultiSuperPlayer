package com.multisuperplayer.core.common.format

/**
 * 时间 / 体积格式化。
 *
 * 全部为纯函数，便于单测；所有函数对负数、超大值都必须安全（播放器拿到的
 * 时长在未知时是 `C.TIME_UNSET`，即一个很大的负数，绝不能直接喂给 UI）。
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

    /** `1 小时 3 分` / `3 分 20 秒` / `8 秒`。用于「共 N 首 / 总时长」。 */
    fun humanDuration(ms: Long): String {
        if (ms <= 0) return "0 秒"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("$hours 小时 ")
            if (minutes > 0) append("$minutes 分 ")
            if (seconds > 0 || isEmpty()) append("$seconds 秒")
        }.trim()
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
}
