package com.multisuperplayer.core.common.log

/**
 * 日志的落地端。
 *
 * 与 `android.util.Log` 并列存在（不是替代）：logcat 在开发时最方便，
 * 文件在「用户反馈问题」时才拿得到。两者同时开，互不影响。
 *
 * 实现**必须是非阻塞的**：调用它的是任意线程上的任意一条日志，写盘/压缩这种
 * 动作要自己丢到后台，绝不能在这里同步做。
 *
 * 也**必须吞掉自己的异常**：日志设施崩了不能把宿主崩了，否则「加日志」这件事
 * 本身成了新的故障源。实现里请自己 `runCatching`。
 */
fun interface LogSink {
    fun write(level: MspLogLevel, tag: String, message: String, error: Throwable?)
}
