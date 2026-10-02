package com.multisuperplayer.core.common.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志文本的时间格式。
 *
 * 单独一个小对象，是因为三个地方都要用（写行、命名文件、导出报告的标题），
 * 三处各自 `SimpleDateFormat(...)` 迟早会出现「文件叫 2026-10-02，行里写着 2026-10-03」
 * 这种只差一个时区/格式参数的低级不一致。
 *
 * 内部固定 [Locale.US]：`HH:mm:ss.SSS` 在泰国/阿拉伯语等区域会变成佛历年份或
 * 阿拉伯数字，日志是给排障看的，不能随系统语言变样。
 */
object LogTime {

    /** 日志文件名的日期段：`msp-2026-10-02.log`。字典序 == 时间序，因此排序可以直接按文件名。 */
    const val DAY_PATTERN: String = "yyyy-MM-dd"

    private const val TIME_PATTERN = "HH:mm:ss.SSS"
    private const val STAMP_PATTERN = "yyyy-MM-dd-HHmm"

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat(TIME_PATTERN, Locale.US) }
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat(DAY_PATTERN, Locale.US) }
    private val stampFormat = ThreadLocal.withInitial { SimpleDateFormat(STAMP_PATTERN, Locale.US) }

    /** 行首的时间戳，例如 `14:22:03.481`。 */
    fun timeOfDay(millis: Long): String = timeFormat.get()!!.format(Date(millis))

    /** 日期段，例如 `2026-10-02`。 */
    fun dayKey(millis: Long): String = dayFormat.get()!!.format(Date(millis))

    /**
     * 文件名用的紧凑时间戳，例如 `2026-10-02-1530`。
     *
     * **不含冒号**：这个字符串会被拼进导出文件名，而冒号在 FAT/exFAT 上是非法字符
     * （被替换掉的话，用户看到的文件名和日志里写着的就对不上了）。
     */
    fun stamp(millis: Long): String = stampFormat.get()!!.format(Date(millis))
}
