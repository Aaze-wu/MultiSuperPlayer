package com.multisuperplayer.core.common.log

/**
 * 单条日志的纯文本格式。
 *
 * 这一层刻意做成**纯函数**（时间戳由调用方传字符串进来）：落盘之后用户会把日志
 * 贴出来，格式一旦变了，「按行首时间戳切分」的解析就会静默错位。它必须能被单测钉住。
 *
 * 形状：
 * ```
 * 14:22:03.481 I SettingsViewModel: 强调色=teal
 * 14:22:03.482 E PlayerViewModel: 打开失败
 *      java.io.FileNotFoundException: ...
 * ```
 *
 * 约定：**条目一定从第 0 列开始**，续行（消息里的换行、堆栈）统一缩进 4 个空格。
 * 这样「行首是 HH:mm:ss.SSS 的才是新条目」这条解析规则才成立。
 */
object LogLine {

    private const val CONTINUATION_INDENT = "    "

    fun format(
        time: String,
        level: MspLogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    ): String = buildString {
        append(time)
        append(' ')
        append(level.letter)
        append(' ')
        append(tag)
        append(": ")
        append(indent(message))
        if (error != null) {
            val stack = stackTraceOf(error)
            if (stack.isNotEmpty()) {
                append('\n')
                // 堆栈的**第一行也要缩进**：上面那条约定是「条目从第 0 列开始」，
                // 而 `indent` 只给换行之后的文本加缩进，直接用会把
                // `java.lang.IllegalStateException: ...` 顶到第 0 列，
                // 看起来就跟新条目一模一样（KDoc 里的形状一直是缩进的）。
                append(CONTINUATION_INDENT)
                append(indent(stack))
            }
        }
    }

    /**
     * 堆栈文本。
     *
     * 自己拼 `Throwable.printStackTrace` 到 `StringWriter`，而不是用
     * `android.util.Log.getStackTraceString`：后者对 `RuntimeException` 返回**空串**
     * （它的实现里专门把 `RuntimeException` 排除了），而 `IllegalStateException`
     * 之类恰恰是最常见的崩溃类型——用它会得到一条「有错误但没有原因」的日志。
     *
     * `cause` 链要一起带上：只打最外层的话，「被谁包装的」这条最重要的线索就没了。
     */
    fun stackTraceOf(error: Throwable): String {
        val writer = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    /** 统一换行符并缩进续行，让每个条目在文本里都是「一块」而不是「一行」。 */
    private fun indent(text: String): String =
        text.replace("\r\n", "\n").replace("\n", "\n$CONTINUATION_INDENT")
}
