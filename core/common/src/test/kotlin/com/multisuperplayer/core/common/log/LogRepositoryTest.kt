package com.multisuperplayer.core.common.log

import com.multisuperplayer.core.common.log.LogRepository
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 日志的读取侧（列表、概览、导出报告）。
 *
 * 最要紧的一条是 **先 flush 再读**：不刷新的话队列里最后几十行还在内存里，
 * 用户导出的恰好是「没有刚发生的那件事」的那一份——而他要反馈的就是那件事。
 * 这个错误是**静默**的，报告看起来完整得很，所以必须用测试钉住顺序。
 */
class LogRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fixedMillis: Long = LocalDateTime.of(2026, 10, 2, 15, 30)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun repo(
        directory: File? = temp.root,
        flush: () -> Unit = {},
    ) = LogRepository(
        directoryProvider = { directory },
        flush = flush,
        now = { fixedMillis },
    )

    private fun writeLog(directory: File, day: String, content: String): File =
        File(directory, "msp-$day.log").apply { writeText(content) }

    // ------------------------------------------------------------------ 列表

    @Test
    fun `只认出日志文件并按时间排序`() {
        val directory = temp.newFolder()
        writeLog(directory, "2026-01-03", "c")
        writeLog(directory, "2026-01-01", "a")
        File(directory, "notes.txt").writeText("无关")
        File(directory, "msp-2026-01-02.log.bak").writeText("备份")

        val names = repo(directory).files().map { it.name }

        assertEquals(listOf("msp-2026-01-01.log", "msp-2026-01-03.log"), names)
    }

    @Test
    fun `目录拿不到时返回空而不是抛异常`() {
        // 目录由 MspLogInitializer 在启动时解析，Koin 注入的却是一个 lambda：
        // 两者之间有时间差，所以「还没有目录」是一个正常状态，不是错误。
        val repository = repo(directory = null)

        assertTrue(repository.files().isEmpty())
        assertEquals(LogSummary.Empty, repository.summary())
    }

    // ------------------------------------------------------------------ 概览

    @Test
    fun `概览统计数量 大小 与日期范围`() {
        val directory = temp.newFolder()
        writeLog(directory, "2026-01-01", "a".repeat(10))
        writeLog(directory, "2026-01-02", "b".repeat(20))
        writeLog(directory, "2026-01-03", "c".repeat(30))

        val summary = repo(directory).summary()

        assertEquals(3, summary.fileCount)
        assertEquals(60L, summary.totalBytes)
        assertEquals("2026-01-01", summary.oldestDay)
        assertEquals("2026-01-03", summary.newestDay)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `没有日志文件时概览是空的`() {
        val summary = repo(temp.newFolder()).summary()

        assertEquals(LogSummary.Empty, summary)
        assertTrue(summary.isEmpty)
    }

    // ------------------------------------------------------------------ 导出

    @Test
    fun `导出文件名带时间戳`() {
        assertEquals("msp-log-2026-10-02-1530.txt", repo().suggestedFileName())
    }

    @Test
    fun `导出文件名里不能有冒号`() {
        // Windows / 某些 exFAT 卷不接受文件名里的冒号，带上去这个文件根本写不出来。
        val name = repo().suggestedFileName()

        listOf(':', '*', '?', '"', '<', '>', '|', '\\', '/').forEach { illegal ->
            assertFalse("文件名里不该出现 $illegal：$name", name.contains(illegal))
        }
    }

    @Test
    fun `拼报告之前先 flush`() {
        // 反过来的写法（先列文件再 flush）会正好丢掉最后几十行，而且看不出来。
        val directory = temp.newFolder()
        val repository = repo(directory) {
            // flush 回调「产生」一个文件：只有在读之前调用了它，报告里才会有这一行。
            writeLog(directory, "2026-01-01", "刚刚发生的那件事\n")
        }

        val report = repository.buildReport(header = emptyList())

        assertTrue(report.text.contains("刚刚发生的那件事"))
        assertEquals(1, report.includedFiles)
    }

    @Test
    fun `拼报告时按文件分段并带上抬头`() {
        val directory = temp.newFolder()
        writeLog(directory, "2026-01-01", "第一天的内容\n")
        writeLog(directory, "2026-01-02", "第二天的内容\n")
        // 抬头现在是**已经渲染好的行**：`buildReport` 只负责拼字符串，不碰 `Resources`，
        // 所以它既能在 JVM 单测里跑，也不会把报告的语言钉死在日志产生的那一刻。
        val header = listOf("版本: 0.5.4 (50400)", "设备: Google Pixel 7")

        val report = repo(directory).buildReport(header = header)

        assertTrue(report.text.contains("版本: 0.5.4 (50400)"))
        assertTrue(report.text.contains("设备: Google Pixel 7"))
        assertTrue(report.text.contains("----- msp-2026-01-01.log -----"))
        assertTrue(report.text.contains("----- msp-2026-01-02.log -----"))
        assertTrue(report.text.contains("第一天的内容"))
        assertTrue(report.text.contains("第二天的内容"))
        assertEquals(2, report.includedFiles)
        assertTrue(report.failedFiles.isEmpty())
    }

    @Test
    fun `报告里写明导出时间`() {
        // 用户手上有好几份报告时，「哪一份是哪天导的」只能靠这一行。
        val report = repo(temp.newFolder()).buildReport(header = emptyList())

        assertTrue(report.text.contains("2026-10-02 15:30"))
    }

    @Test
    fun `一个日志文件都没有时报告里也要有说明`() {
        // 空报告不能是一片空白：用户会以为导出失败或者文件坏了，
        // 而实际原因只是「这个安装包还没产生过日志」。
        val report = repo(temp.newFolder()).buildReport(header = emptyList())

        assertTrue(report.text.contains("还没有产生任何日志"))
        assertEquals(0, report.includedFiles)
    }

    @Test
    fun `文件末尾没有换行时会补一个`() {
        // 不补的话下一个文件的分隔线会粘在这一行后面，
        // 用户看到的是一行「13:20:01 I T: 内容----- msp-2026-01-02.log -----」。
        val directory = temp.newFolder()
        writeLog(directory, "2026-01-01", "没有换行结尾的内容")
        writeLog(directory, "2026-01-02", "x\n")

        val report = repo(directory).buildReport(header = emptyList())

        assertTrue(report.text.contains("没有换行结尾的内容\n"))
    }

    // 注：「某个文件读不出来 ⇒ 记进 failedFiles」这一条没有单测：
    // 在 Windows / Linux 上都没办法可靠地把一个**普通文件**变成读不出来的状态
    // （POSIX 下用权限能行，Windows 下 Java 的句柄是共享的，锁不住）。
    // 那段代码只有三行（`runCatching { readText() }.getOrNull()`），
    // 这里不为了测试去改生产代码的形状。
}
