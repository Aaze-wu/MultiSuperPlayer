package com.multisuperplayer.core.common.log

/**
 * 日志等级。
 *
 * **刻意不用 `android.util.Log` 的那几个 int 常量**：
 *
 * 1. 那几个常量是 Java 的 `static final int`，单元测试里（`unitTests.isReturnDefaultValues = true`）
 *    一旦没被内联就全是 `0`，于是「等级过滤」这种核心逻辑在测试里会静默失效、
 *    进而把整条链路测成假的。这里用自己的枚举，比较的是 `ordinal`，与 Android 无关。
 * 2. 落盘/导出这条链路上「按等级过滤」和「写等级字母」都不该依赖 Android 运行时，
 *    否则 [LogLine] 这种纯函数就没法在 JVM 上测。
 *
 * 从低到高排列，`ordinal` 即优先级；[MspLogLevel.INFO] 以上的顺序不可改动。
 */
enum class MspLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** 写进日志文件的那一个字母，对齐 logcat 的习惯（V/D/I/W/E）。 */
    val letter: Char
        get() = name[0]

    companion object {
        /**
         * 从字母还原等级。
         *
         * 给「读日志导出文件」这一侧用：导出的是纯文本，回读时要能判断某一行是什么等级。
         * 认不出来时返回 `null` 而不是猜一个——把一行解析成错误的等级比解析失败更难查。
         */
        fun fromLetter(letter: Char): MspLogLevel? =
            entries.firstOrNull { it.letter == letter.uppercaseChar() }
    }
}
