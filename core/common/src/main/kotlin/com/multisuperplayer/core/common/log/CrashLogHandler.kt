package com.multisuperplayer.core.common.log

import android.os.Process as AndroidProcess
import kotlin.system.exitProcess

/**
 * 崩溃处理器：**先落盘，再交给系统**。
 *
 * Android 默认的崩溃处理会把堆栈打到 logcat，但用户手机上的 logcat 是拿不到的；
 * 落盘的那一份才是「用户能导出发给你」的那份。所以这里必须：
 *
 * 1. 用 [MspLog.e] 记一条（等级是 ERROR，任何等级开闸都拦不住它）；
 * 2. **同步等它写下去**（[flush]）。只 `write()` 的话任务还在队列里，进程就没了，
 *    于是日志文件恰好缺掉唯一你真正需要的那一段——这是这套设施最容易白做的地方。
 *
 * [delegate] 是安装本处理器之前的那个处理器（通常是系统默认的，负责弹「应用已停止」
 * 并结束进程）。**必须转发给它**，否则异常被我们吞掉，进程会带着一个坏掉的状态继续跑，
 * 表现为「应用无缘无故卡住/黑屏」而不是「崩溃」——后者至少用户知道要重开。
 */
class CrashLogHandler(
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val flush: () -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 处理器自己绝不能抛：这里抛出去就没人兜底了，用户会看不到任何提示。
        runCatching {
            MspLog.e(TAG, throwable) { "未捕获异常（线程 ${thread.name}）" }
            flush()
        }
        val target = delegate
        if (target != null) {
            target.uncaughtException(thread, throwable)
        } else {
            // 连系统默认处理器都没拿到（理论上不会），只能自己收场，别再让进程带病运行。
            //
            // 注：这里必须用别名 `AndroidProcess`——`Process` 会被 Kotlin 解析成
            // `java.lang.Process`（自动导入），那个类没有 killProcess/myPid。
            runCatching { AndroidProcess.killProcess(AndroidProcess.myPid()) }
            exitProcess(EXIT_CODE_CRASH)
        }
    }

    companion object {
        private const val TAG = "Crash"

        /** 与 `Runtime`/ART 崩溃退出码保持一致的约定值（非 0，便于外部脚本识别）。 */
        private const val EXIT_CODE_CRASH = 10
    }
}
