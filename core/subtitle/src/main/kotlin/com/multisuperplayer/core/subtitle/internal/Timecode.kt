package com.multisuperplayer.core.subtitle.internal

/**
 * 时间码解析。
 *
 * 字幕界的写法远比标准多，必须宽容：
 * - SRT：`00:00:01,000`（毫秒用逗号）
 * - VTT：`00:00:01.000`
 * - LRC：`[00:12.34]`、`[00:12]`、`[00:12:34]`（少数用冒号当小数分隔）
 * - ASS：`0:00:01.00`（**小时位只有一位**，且要求两位小数）
 * - SRT 允许省略小时：`00:01,000`
 * - 小数位有 1~3 位的各种写法
 */
internal object Timecode {

    private const val MAX_REASONABLE_MS: Long = 100L * 24 * 60 * 60 * 1000 // 100 天

    /** `[hh:]mm:ss[.,:]fff`，小时与分钟都允许省略前导零。 */
    private val FULL = Regex("""^(?:(\d{1,4}):)?(\d{1,4}):(\d{1,2})[.,:](\d{1,3})$""")

    /** 纯秒数：`12.5s`、`12.5`、`1.5`。 */
    private val SECONDS = Regex("""^(\d+(?:\.\d+)?)$""")

    /**
     * 解析时间码，失败返回 null。
     *
     * 小数位的解释：`1` → 100ms，`12` → 120ms，`123` → 123ms
     * （即按「十分之一/百分之一/千分之一秒」处理，而不是当整数毫秒）。
     */
    fun toMillis(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        FULL.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
            val minutes = match.groupValues[2].toLongOrNull() ?: return null
            val seconds = match.groupValues[3].toLongOrNull() ?: return null
            val fraction = match.groupValues[4]
            val millis = when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100)
                2 -> fraction.toLongOrNull()?.times(10)
                else -> fraction.take(3).toLongOrNull()
            } ?: return null
            // 秒数超过 59 说明这个「秒」字段其实不是秒（例如 5 位数字），判为解析失败。
            if (seconds > 59) return null
            val total = hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
            return total.takeIf { it in 0..MAX_REASONABLE_MS }
        }

        SECONDS.matchEntire(text)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val total = (value * 1000.0).toLong()
            return total.takeIf { it in 0..MAX_REASONABLE_MS }
        }

        return null
    }

    /**
     * 解析「开始 --> 结束」行，返回两段时间码。
     * VTT 的 cue 设置（`align:center position:50%`）与 SRT 的坐标（`X1:.. Y1:..`）
     * 都跟在结束时间码后面，所以只取前两个可解析的时间码。
     */
    fun parseRange(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size < 2) return null
        val start = firstTimecode(parts[0]) ?: return null
        val end = firstTimecode(parts[1]) ?: return null
        return start to end
    }

    /** 从一段文本里取出第一个能被解析的时间码（可能是 `12.5s` / `100ms`）。 */
    fun firstTimecode(chunk: String): Long? {
        val trimmed = chunk.trim()
        for (token in trimmed.split(' ', '\t', '\u00A0')) {
            val candidate = token.trim()
            if (candidate.isEmpty()) continue
            parseClockToken(candidate)?.let { return it }
        }
        return null
    }

    /**
     * 解析单个时间表达式，同时支持 TTML 的 `1.5s` / `100ms` / `2h` / `3m` /
     * `1f`（帧，按 25fps 估算）/ `300t`（tick，按默认 tickRate 10 000 估算）写法。
     *
     * 注意 `f` 与 `t` 都只是**估算**：帧率和 tickRate 是每个 TTML 文档自己的属性，
     * 只有 [com.multisuperplayer.core.subtitle.TtmlParser] 拿得到，它会在
     * 调用这里之前先用文档里声明的值换算。这里的分支只服务于「不知道文档属性」
     * 的兜底场景（例如 SRT/VTT 里混进了 `300t`）。
     */
    fun parseClockToken(token: String): Long? {
        toMillis(token)?.let { return it }

        val lower = token.lowercase()
        val unitValue = lower.dropLastWhile { it.isLetter() }
        val unit = lower.removePrefix(unitValue)
        val number = unitValue.toDoubleOrNull() ?: return null
        val millis = when (unit) {
            "h" -> number * 3_600_000
            "m" -> number * 60_000
            "s" -> number * 1_000
            "ms" -> number
            "f" -> number * (1_000.0 / DEFAULT_FRAME_RATE) // 默认帧率
            // tickRate 的含义是「每秒多少 tick」，所以要 /tickRate 得到**秒**，
            // 再 ×1000 得到毫秒。只写 number / tickRate 会直接得到秒却被当成
            // 毫秒返回，于是 `300t`（应为 30ms）变成 0ms、整条字幕轴塌到开头。
            "t" -> number * (1_000.0 / DEFAULT_TICK_RATE)
            else -> return null
        }
        return millis.toLong().takeIf { it in 0..MAX_REASONABLE_MS }
    }

    /** TTML 的默认帧率（`ttp:frameRate` 缺省值）。 */
    const val DEFAULT_FRAME_RATE = 25.0

    /** TTML 的默认 tick 频率（`ttp:tickRate` 缺省值），单位 tick/秒。 */
    const val DEFAULT_TICK_RATE = 10_000.0
}
