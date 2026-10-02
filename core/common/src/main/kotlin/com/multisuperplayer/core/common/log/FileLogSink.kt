package com.multisuperplayer.core.common.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 把日志写到文件里。
 *
 * 设计要点（每一条都是踩过的坑）：
 *
 * - **目录自己选**，不在这里判断能不能写：调用方（[MspLogInitializer]）传的是
 *   `getExternalFilesDir("logs")`，也就是 `Android/data/<包名>/files/logs`。
 *   这条路径**不需要任何存储权限**，文件管理器里也看得到——本项目清单里
 *   既没有 `WRITE_EXTERNAL_STORAGE` 也没有 `MANAGE_EXTERNAL_STORAGE`，
 *   想写到「下载」目录反而要申请权限或走 SAF，对一个日志文件不值得。
 * - **永远不阻塞调用线程**：写盘丢给一条单独的低优先级 daemon 线程，
 *   由它独占 [writer] / [currentDay]，因此这两个字段不需要加锁。
 * - **永远不往外抛**：日志设施把宿主崩了，比没有日志糟糕得多。
 *   文件打不开就置 [broken]，之后静默丢弃，logcat 那条通路不受影响。
 * - **每条都 flush**：崩溃现场的最后几行正是最值钱的几行，
 *   缓冲 8KB 会让它们在进程被杀时一起消失。这里不是高吞吐场景，flush 的开销可以忽略。
 * - **清理只在开新文件时做一次**，不是每行都去列目录。
 */
class FileLogSink(
    private val directory: File,
    private val keepFiles: Int = LogRetention.KEEP_FILES,
    private val maxBytes: Long = LogRetention.MAX_TOTAL_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : LogSink {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    // ↓ 只在 worker 线程上访问，所以不需要 volatile / 加锁
    private var writer: BufferedWriter? = null
    private var currentDay: String? = null
    private var broken = false

    override fun write(level: MspLogLevel, tag: String, message: String, error: Throwable?) {
        enqueue { append(level, tag, message, error) }
    }

    /**
     * 等到此前排队的日志都落盘。
     *
     * 导出日志之前**必须**调一次，否则队列里最后几十行还没写下去，
     * 用户导出的报告恰好缺掉「刚发生的那件事」——而他要反馈的就是那件事。
     *
     * 崩溃处理器也走它：崩溃时只 `write()` 就等于把任务丢进队列直接返回，
     * 进程很可能在 worker 真正执行之前就没了。
     */
    fun flush() {
        awaitWorker()
    }

    /** 关掉文件、停掉线程。幂等，且不会抛。 */
    fun close() {
        runCatching {
            worker.execute { runCatching { closeWriter() } }
            worker.shutdown()
            worker.awaitTermination(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------------ 内部

    private inline fun enqueue(crossinline block: () -> Unit) {
        runCatching { worker.execute { runCatching(block) } }
    }

    /** 往队列里塞一个空任务再等它跑完，即「前面的都干完了」。 */
    private fun awaitWorker() {
        runCatching { worker.submit { }.get(BARRIER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun append(level: MspLogLevel, tag: String, message: String, error: Throwable?) {
        if (broken) return
        val line = LogLine.format(LogTime.timeOfDay(clock()), level, tag, message, error)
        val sink = runCatching { openForToday() }.getOrElse {
            broken = true
            return
        } ?: return
        runCatching {
            sink.write(line)
            sink.newLine()
            sink.flush()
        }.onFailure { broken = true }
    }

    /** 确保当天的文件是打开着的；跨天时换文件并顺手清理旧文件。 */
    private fun openForToday(): BufferedWriter? {
        val day = LogTime.dayKey(clock())
        writer?.let { if (currentDay == day) return it }
        closeWriter()
        directory.mkdirs()
        if (!directory.isDirectory) return null
        val file = File(directory, fileNameFor(day))
        val fresh = !file.exists()
        val stream = BufferedWriter(FileWriter(file, /* append = */ true))
        writer = stream
        currentDay = day
        if (fresh) prune()
        return stream
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        currentDay = null
    }

    /**
     * 删掉超出保留策略的文件。
     *
     * 只认 [FILE_PREFIX] + [LogTime.DAY_PATTERN] 形状的文件名，别的东西
     * （用户自己丢进来的、以后新增的其它导出文件）一律当没看见。
     */
    private fun prune() {
        runCatching {
            val entries = directory.listFiles().orEmpty()
                .filter { it.isFile && isLogFileName(it.name) }
                .map { LogFileEntry(it.name, it.length()) }
            LogRetention.plan(entries, keepFiles, maxBytes).forEach { name ->
                runCatching { File(directory, name).delete() }
            }
        }
    }

    companion object {
        /** 日志文件名前缀，改成别的就再也清理不掉了。 */
        const val FILE_PREFIX: String = "msp-"

        /** 日志文件名后缀。 */
        const val FILE_SUFFIX: String = ".log"

        private const val THREAD_NAME = "msp-log"
        private const val BARRIER_TIMEOUT_MILLIS = 3_000L
        private const val CLOSE_TIMEOUT_MILLIS = 3_000L

        fun fileNameFor(dayKey: String): String = "$FILE_PREFIX$dayKey$FILE_SUFFIX"

        /** 严格匹配 `msp-yyyy-MM-dd.log`（长度也卡死，避免把 `msp-1.log.bak` 之类算进来）。 */
        fun isLogFileName(name: String): Boolean {
            val expectedLength = FILE_PREFIX.length + LogTime.DAY_PATTERN.length + FILE_SUFFIX.length
            if (name.length != expectedLength) return false
            if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return false
            val day = name.substring(FILE_PREFIX.length, name.length - FILE_SUFFIX.length)
            return day.length == LogTime.DAY_PATTERN.length &&
                day.indices.all { index ->
                    val char = day[index]
                    if (LogTime.DAY_PATTERN[index] == '-') char == '-' else char.isDigit()
                }
        }
    }
}
