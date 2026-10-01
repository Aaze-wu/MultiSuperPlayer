package com.multisuperplayer.core.common.log

import android.util.Log

/**
 * 全项目唯一的日志出口。
 *
 * 为什么不直接用 [Log]：后续要接「崩溃前 N 行日志」到问题反馈、以及把日志落盘，
 * 统一入口才能一处改动全局生效。模块内使用方式：
 *
 * ```
 * private const val TAG = "SubtitleParser"
 * MspLog.d(TAG) { "parsed ${cues.size} cues" }
 * ```
 *
 * 用 lambda 而不是字符串，避免 release 下白拼字符串。
 */
object MspLog {

    private const val GLOBAL_TAG_PREFIX = "MSP"

    /** 低于该等级的日志直接丢弃。默认 DEBUG，release 由 [MspLogInitializer] 抬高。 */
    @Volatile
    var minLevel: Int = Log.DEBUG

    /** 附加落点（例如写文件），为 null 时只走 logcat。 */
    @Volatile
    var sink: ((level: Int, tag: String, message: String, error: Throwable?) -> Unit)? = null

    fun v(tag: String, message: () -> String) = log(Log.VERBOSE, tag, null, message)

    fun d(tag: String, message: () -> String) = log(Log.DEBUG, tag, null, message)

    fun i(tag: String, message: () -> String) = log(Log.INFO, tag, null, message)

    fun w(tag: String, error: Throwable? = null, message: () -> String) =
        log(Log.WARN, tag, error, message)

    fun e(tag: String, error: Throwable? = null, message: () -> String) =
        log(Log.ERROR, tag, error, message)

    private inline fun log(
        level: Int,
        tag: String,
        error: Throwable?,
        message: () -> String,
    ) {
        if (level < minLevel) return
        val fullTag = "$GLOBAL_TAG_PREFIX/$tag"
        val text = runCatching(message).getOrElse { "日志构建失败: $it" }
        if (error == null) {
            Log.println(level, fullTag, text)
        } else {
            Log.println(level, fullTag, "$text\n${Log.getStackTraceString(error)}")
        }
        sink?.invoke(level, fullTag, text, error)
    }
}
