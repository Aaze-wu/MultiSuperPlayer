package com.multisuperplayer.core.common.log

import android.util.Log

/**
 * 全项目唯一的日志出口。
 *
 * 为什么不直接用 [Log]：
 * - 需要按等级开闸（release 只留 [MspLogLevel.INFO] 及以上），一处开关管全部；
 * - 需要换落点（logcat / 文件），调用方一行都不用改；
 * - 需要统一 tag 前缀 `MSP/`，`adb logcat -s MSP:*` 就能只看自己家的日志。
 *
 * 模块内使用方式：
 *
 * ```
 * private const val TAG = "SubtitleParser"
 * MspLog.d(TAG) { "parsed ${cues.size} cues" }
 * ```
 *
 * 用 lambda 而不是字符串，避免 release 下白拼字符串。
 *
 * 装配见 [MspLogInitializer]：它在启动时把 [sink] 接到文件落地端。
 */
object MspLog {

    private const val GLOBAL_TAG_PREFIX = "MSP"

    /** 低于该等级的日志直接丢弃。默认 DEBUG，release 由 [MspLogInitializer] 抬高。 */
    @Volatile
    var minLevel: MspLogLevel = MspLogLevel.DEBUG

    /**
     * 附加落点（例如写文件），为 null 时只走 logcat。
     *
     * 接上来的实现必须是**非阻塞**的：调用发生在任意线程上，见 [LogSink] 的注释。
     */
    @Volatile
    var sink: LogSink? = null

    fun v(tag: String, message: () -> String) = log(MspLogLevel.VERBOSE, tag, null, message)

    fun d(tag: String, message: () -> String) = log(MspLogLevel.DEBUG, tag, null, message)

    fun i(tag: String, message: () -> String) = log(MspLogLevel.INFO, tag, null, message)

    fun w(tag: String, error: Throwable? = null, message: () -> String) =
        log(MspLogLevel.WARN, tag, error, message)

    fun e(tag: String, error: Throwable? = null, message: () -> String) =
        log(MspLogLevel.ERROR, tag, error, message)

    /**
     * 指定等级写一条。
     *
     * 给「等级是运行时数据」的地方用（崩溃处理器）。日常调用请用 `v/d/i/w/e`。
     */
    internal fun log(
        level: MspLogLevel,
        tag: String,
        error: Throwable?,
        message: () -> String,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val fullTag = "$GLOBAL_TAG_PREFIX/$tag"
        val text = runCatching(message).getOrElse { "日志构建失败: $it" }
        if (error == null) {
            Log.println(androidLevel(level), fullTag, text)
        } else {
            Log.println(androidLevel(level), fullTag, "$text\n${Log.getStackTraceString(error)}")
        }
        // sink 自己要负责吞异常；这里再包一层是因为「日志写坏了把宿主搞崩」不可接受。
        runCatching { sink?.write(level, fullTag, text, error) }
    }

    private fun androidLevel(level: MspLogLevel): Int = when (level) {
        MspLogLevel.VERBOSE -> Log.VERBOSE
        MspLogLevel.DEBUG -> Log.DEBUG
        MspLogLevel.INFO -> Log.INFO
        MspLogLevel.WARN -> Log.WARN
        MspLogLevel.ERROR -> Log.ERROR
    }
}
