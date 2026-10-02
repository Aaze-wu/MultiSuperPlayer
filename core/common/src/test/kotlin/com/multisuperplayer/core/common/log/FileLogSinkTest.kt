package com.multisuperplayer.core.common.log

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 日志落盘。
 *
 * [FileLogSink] 的契约里有三条是「错了看不出来」的，所以都单独钉：
 *
 * - **永远不往外抛**：日志设施把宿主崩了比没有日志更糟；
 * - **每条都 flush**：崩溃现场最后几行正是最值钱的几行；
 * - **清理只在开新文件时做一次**，且认不出名字的东西一律不碰。
 */
class FileLogSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 用**当天正午**而不是零点：零点在夏令时切换日可能不存在，正午永远安全。 */
    private fun noonOf(month: Int, day: Int): Long = LocalDate.of(2026, month, day)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun logFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { FileLogSink.isLogFileName(it.name) }.sortedBy { it.name }

    private fun textOf(file: File): String = file.readText(Charsets.UTF_8)

    // ------------------------------------------------------------------ 写入

    @Test
    fun `写进去的日志会落盘`() {
        val directory = temp.newFolder()
        val sink = FileLogSink(directory, clock = { noonOf(1, 2) })

        sink.write(MspLogLevel.INFO, "SettingsViewModel", "强调色=teal", null)
        sink.flush()

        val files = logFiles(directory)
        assertEquals(1, files.size)
        assertTrue(textOf(files.first()).contains("强调色=teal"))
        sink.close()
    }

    @Test
    fun `同一天的两条写在同一个文件里且保持顺序`() {
        val directory = temp.newFolder()
        val sink = FileLogSink(directory, clock = { noonOf(1, 2) })

        sink.write(MspLogLevel.INFO, "T", "第一条", null)
        sink.write(MspLogLevel.WARN, "T", "第二条", null)
        sink.flush()

        val files = logFiles(directory)
        assertEquals(1, files.size)
        val text = textOf(files.first())
        assertTrue(text.indexOf("第一条") < text.indexOf("第二条"))
        sink.close()
    }

    @Test
    fun `跨天换文件, 旧的那份不动`() {
        val directory = temp.newFolder()
        var now = noonOf(1, 2)
        val sink = FileLogSink(directory, clock = { now })

        sink.write(MspLogLevel.INFO, "T", "第一天", null)
        sink.flush()
        now = noonOf(1, 3)
        sink.write(MspLogLevel.INFO, "T", "第二天", null)
        sink.flush()

        val files = logFiles(directory)
        assertEquals(2, files.size)
        assertTrue(textOf(files[0]).contains("第一天"))
        assertFalse(textOf(files[0]).contains("第二天"))
        assertTrue(textOf(files[1]).contains("第二天"))
        sink.close()
    }

    @Test
    fun `目录不存在时会自己建出来`() {
        val directory = File(temp.root, "logs/nested/deep")
        val sink = FileLogSink(directory, clock = { noonOf(1, 2) })

        sink.write(MspLogLevel.INFO, "T", "x", null)
        sink.flush()

        assertTrue(directory.isDirectory)
        assertEquals(1, logFiles(directory).size)
        sink.close()
    }

    // ------------------------------------------------------------------ 清理

    @Test
    fun `开新文件时清理超限的旧文件`() {
        val directory = temp.newFolder()
        // 先摆 8 个「过去」的日志，每个 1KB。
        (1..8).forEach { day ->
            val padded = day.toString().padStart(2, '0')
            File(directory, "msp-2026-01-$padded.log").writeText("x".repeat(1024))
        }

        val sink = FileLogSink(directory, keepFiles = 7, maxBytes = Long.MAX_VALUE, clock = { noonOf(2, 1) })
        sink.write(MspLogLevel.INFO, "T", "今天", null)
        sink.flush()

        // 9 个文件 ⇒ 只留最新 7 个 ⇒ 最旧的两天被删掉。
        val names = logFiles(directory).map { it.name }
        assertEquals(7, names.size)
        assertFalse(names.contains("msp-2026-01-01.log"))
        assertFalse(names.contains("msp-2026-01-02.log"))
        assertTrue(names.contains("msp-2026-01-03.log"))
        sink.close()
    }

    @Test
    fun `清理时不动名字不认识的文件`() {
        val directory = temp.newFolder()
        // 用户自己丢进来的东西、以及将来可能新增的其它导出文件，一律不碰。
        File(directory, "msp-log-2026-01-01-1200.txt").writeText("导出的报告")
        File(directory, "notes.txt").writeText("手记")
        (1..8).forEach { day ->
            val padded = day.toString().padStart(2, '0')
            File(directory, "msp-2026-01-$padded.log").writeText("x".repeat(1024))
        }

        val sink = FileLogSink(directory, keepFiles = 7, maxBytes = Long.MAX_VALUE, clock = { noonOf(2, 1) })
        sink.write(MspLogLevel.INFO, "T", "今天", null)
        sink.flush()

        assertTrue(File(directory, "msp-log-2026-01-01-1200.txt").exists())
        assertTrue(File(directory, "notes.txt").exists())
        sink.close()
    }

    // ------------------------------------------------------------------ 不抛异常

    @Test
    fun `目录位置其实是个文件时也不抛异常`() {
        val notADirectory = temp.newFile()
        val sink = FileLogSink(notADirectory, clock = { noonOf(1, 2) })

        // 这里**故意不做断言**：要验的就是它不抛。日志设施把自己崩了，
        // 比没有日志糟糕得多——那样连「日志坏了」这件事都记不下来。
        sink.write(MspLogLevel.INFO, "T", "x", null)
        sink.flush()
        sink.close()
    }

    @Test
    fun `close 之后再写不抛异常`() {
        val sink = FileLogSink(temp.newFolder(), clock = { noonOf(1, 2) })
        sink.close()

        // 关闭顺序不可控（Activity 销毁、进程退出回调、崩溃处理器都会碰它）。
        sink.write(MspLogLevel.INFO, "T", "x", null)
        sink.flush()
    }

    @Test
    fun `close 可以重复调用`() {
        val sink = FileLogSink(temp.newFolder(), clock = { noonOf(1, 2) })

        sink.close()
        sink.close()
        sink.close()
    }

    @Test
    fun `写着的时候直接关掉不会丢已经 flush 过的内容`() {
        val directory = temp.newFolder()
        val sink = FileLogSink(directory, clock = { noonOf(1, 2) })

        sink.write(MspLogLevel.ERROR, "T", "崩溃前最后一行", null)
        sink.flush()
        sink.close()

        assertTrue(textOf(logFiles(directory).first()).contains("崩溃前最后一行"))
    }

    // ------------------------------------------------------------------ 文件名判据

    @Test
    fun `只认自己写出来的文件名`() {
        assertTrue(FileLogSink.isLogFileName("msp-2026-01-01.log"))
        assertTrue(FileLogSink.isLogFileName(FileLogSink.fileNameFor("2026-12-31")))

        // 下面这些如果被认成日志文件，清理时就会误删用户的东西，
        // 或者在导出时读进一堆无关内容。
        val rejected = listOf(
            "msp-2026-1-1.log", // 位数不够
            "msp-2026-01-011.log", // 多一位
            "msp-2026-01-01.logs", // 后缀多了个字母
            "msp-2026-01-01.LOG", // 大小写不同
            "msp-2026-01-01.log.bak", // 备份文件
            "2026-01-01.log", // 没有前缀
            "msp-xxxx-01-01.log", // 不是数字
            "msp-2026-01-0a.log", // 有个字母混进来
            "msp-log-2026-01-01-1200.txt", // 导出的报告
            "",
        )
        rejected.forEach { name ->
            assertFalse("不该被认成日志文件：$name", FileLogSink.isLogFileName(name))
        }
    }
}
