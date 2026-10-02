package com.multisuperplayer.core.common.log

import com.multisuperplayer.core.common.format.TimeFormat
import java.io.File

/** 日志目录的概览，给「关于」页显示「导出会得到什么」。 */
data class LogSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val oldestDay: String?,
    val newestDay: String?,
) {
    val isEmpty: Boolean get() = fileCount == 0

    companion object {
        val Empty = LogSummary(0, 0L, null, null)
    }
}

/**
 * 一份拼好的日志报告。
 *
 * [failedFiles] 单独列出来而不是直接抛异常：日志文件损坏/权限异常时，
 * 「导出 2 个、跳过 1 个」比「点了没反应」有用得多；反过来，把失败悄悄吞掉也不行
 * ——用户会拿着一份不完整的日志说「就这些了」，而缺的恰好是崩掉的那一天。
 */
data class LogReport(
    val fileName: String,
    val text: String,
    val includedFiles: Int,
    val failedFiles: List<String>,
)

/**
 * 日志文件的读取侧：列出、概览、拼成一份可导出的报告。
 *
 * 写入侧是 [FileLogSink]，两者共用 [FileLogSink.isLogFileName] 判据，
 * 所以「清理时认得、导出时也要认得」这件事不会各写一套。
 *
 * [directoryProvider] 用 lambda 而不是直接传目录：Koin 在第一次注入时构造它，
 * 那一刻 `MspLogInitializer.install` 可能还没跑完（顺序见那里的注释），
 * 传 lambda 就把「什么时候解析目录」这件事从构造时机里解耦出来了。
 */
class LogRepository(
    private val directoryProvider: () -> File?,
    private val flush: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 全部日志文件，**按时间从旧到新**。
     *
     * 直接按文件名排序：文件名里就是 `yyyy-MM-dd`，字典序等于时间序，
     * 因此不需要读文件的修改时间（那个会被备份/拷贝工具改掉）。
     */
    fun files(): List<File> {
        val directory = directoryProvider() ?: return emptyList()
        return directory.listFiles().orEmpty()
            .filter { it.isFile && FileLogSink.isLogFileName(it.name) }
            .sortedBy { it.name }
    }

    fun summary(): LogSummary {
        val files = files()
        if (files.isEmpty()) return LogSummary.Empty
        return LogSummary(
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            oldestDay = dayOf(files.first().name),
            newestDay = dayOf(files.last().name),
        )
    }

    /** 导出用的文件名，例如 `msp-log-2026-10-02-1530.txt`。 */
    fun suggestedFileName(millis: Long = now()): String = "msp-log-${LogTime.stamp(millis)}.txt"

    /**
     * 拼报告。
     *
     * **先 [flush] 再读**：不刷新的话队列里最后几十行还在内存里，
     * 用户导出的恰好是「没有刚发生的那件事」的那一份。这个动作放在这里而不是交给调用方，
     * 是因为「忘了刷」的代价是静默的——导出一份看似完整的报告，谁都不会发现少了什么。
     *
     * 会做文件 IO，请**在 IO 线程上调用**。
     *
     * [header] 是**已经解析好的**一行行文本（由调用方用
     * [com.multisuperplayer.core.common.info.InfoRow.render] 拼好）。这样做的原因有两个：
     * 一是这里能保持不碰 `Resources`（报告拼接的单测因此不需要任何 Android 环境），
     * 二是抬头要跟着界面语言走，而语言只有在 UI 边界才知道。
     * 报告本身的骨架（`===== … =====`、`导出时间:` 那几行）**刻意不翻译**：
     * 它是排查问题时给开发者看的，混进译文反而会让按关键字搜索失效。
     */
    fun buildReport(header: List<String>, millis: Long = now()): LogReport {
        runCatching { flush() }

        val files = files()
        val failed = mutableListOf<String>()
        val builder = StringBuilder()

        builder.append("===== MultiSuperPlayer 日志报告 =====").append('\n')
        builder.append("导出时间: ").append(TimeFormat.dateTime(millis)).append('\n')
        header.forEach { line -> builder.append(line).append('\n') }
        builder.append("日志文件: ").append(files.size).append(" 个，共 ")
            .append(TimeFormat.fileSize(files.sumOf { it.length() })).append('\n')

        var included = 0
        files.forEach { file ->
            builder.append('\n').append("----- ").append(file.name).append(" -----").append('\n')
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (content == null) {
                failed += file.name
                builder.append("（该文件读取失败，已跳过）").append('\n')
            } else {
                included++
                builder.append(content)
                if (!content.endsWith('\n')) builder.append('\n')
            }
        }

        if (files.isEmpty()) {
            builder.append('\n').append("（还没有产生任何日志）").append('\n')
        }

        return LogReport(
            fileName = suggestedFileName(millis),
            text = builder.toString(),
            includedFiles = included,
            failedFiles = failed,
        )
    }

    private fun dayOf(fileName: String): String =
        fileName.removePrefix(FileLogSink.FILE_PREFIX).removeSuffix(FileLogSink.FILE_SUFFIX)
}
