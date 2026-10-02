package com.multisuperplayer.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志清理策略。
 *
 * 这是唯一会**删用户数据**的地方，所以每个边界都要钉：删多了，用户反馈问题时
 * 日志正好缺了出事那天；删少了，磁盘一直被吃。
 *
 * 策略是纯计算（不碰文件系统），因此这里可以放心地喂各种诡异输入。
 */
class LogRetentionTest {

    private fun entries(vararg dayBytes: Pair<String, Long>): List<LogFileEntry> =
        dayBytes.map { (day, bytes) -> LogFileEntry("msp-$day.log", bytes) }

    /** 连续的若干天，每天 [bytesPerFile] 字节。 */
    private fun consecutiveDays(fromDay: Int, count: Int, bytesPerFile: Long = 1024L): List<LogFileEntry> =
        (fromDay until fromDay + count).map { day ->
            val padded = day.toString().padStart(2, '0')
            LogFileEntry("msp-2026-01-$padded.log", bytesPerFile)
        }

    @Test
    fun `没有任何文件时不删任何东西`() {
        assertTrue(LogRetention.plan(emptyList()).isEmpty())
    }

    @Test
    fun `没超过数量上限时一个都不删`() {
        val files = consecutiveDays(fromDay = 1, count = LogRetention.KEEP_FILES)

        assertTrue(LogRetention.plan(files, maxBytes = Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `超过数量上限时删最旧的`() {
        val files = consecutiveDays(fromDay = 1, count = 9)

        val doomed = LogRetention.plan(files, keepFiles = 7, maxBytes = Long.MAX_VALUE)

        assertEquals(listOf("msp-2026-01-01.log", "msp-2026-01-02.log"), doomed)
    }

    @Test
    fun `输入顺序不影响结果`() {
        // 目录列举的顺序由文件系统决定，在有些设备上是乱的。
        val shuffled = consecutiveDays(fromDay = 1, count = 9).shuffled()

        val doomed = LogRetention.plan(shuffled, keepFiles = 7, maxBytes = Long.MAX_VALUE).sorted()

        assertEquals(listOf("msp-2026-01-01.log", "msp-2026-01-02.log"), doomed)
    }

    @Test
    fun `字典序必须等于时间序`() {
        // 「最新」完全是按文件名的字符串比较算出来的，所以跨月跨年也不能错。
        val files = entries(
            "2026-09-30" to 1L,
            "2026-10-01" to 1L,
            "2026-12-31" to 1L,
            "2027-01-01" to 1L,
        )

        val doomed = LogRetention.plan(files, keepFiles = 2, maxBytes = Long.MAX_VALUE)

        assertEquals(listOf("msp-2026-09-30.log", "msp-2026-10-01.log"), doomed)
    }

    @Test
    fun `体积超限时从最旧的开始删`() {
        val files = entries(
            "2026-01-01" to 3_000_000L,
            "2026-01-02" to 3_000_000L,
            "2026-01-03" to 1_000L,
        )

        val doomed = LogRetention.plan(files, keepFiles = 7, maxBytes = 4_000_000L)

        assertEquals(listOf("msp-2026-01-01.log"), doomed)
    }

    @Test
    fun `体积刚好等于上限时一个都不删`() {
        // 边界：条件是「超过才算超」，写成 >= 会每天白删一个文件。
        val files = entries("2026-01-01" to 2_000_000L, "2026-01-02" to 2_000_000L)

        assertTrue(LogRetention.plan(files, keepFiles = 7, maxBytes = 4_000_000L).isEmpty())
    }

    @Test
    fun `最新的那个文件永远不会被删`() {
        // 单天的日志就已经超过总上限时，如果按体积把它也删掉，
        // 「导出日志」会得到一份空报告——而那正是要发给别人的那份。
        val files = entries("2026-01-01" to 50_000_000L, "2026-01-02" to 50_000_000L)

        val doomed = LogRetention.plan(files, keepFiles = 7, maxBytes = 4_000_000L)

        assertFalse(doomed.contains("msp-2026-01-02.log"))
        assertEquals(listOf("msp-2026-01-01.log"), doomed)
    }

    @Test
    fun `只有一个文件时无论多大都不删`() {
        val files = entries("2026-01-01" to 500_000_000L)

        assertTrue(LogRetention.plan(files, keepFiles = 7, maxBytes = 1_000L).isEmpty())
    }

    @Test
    fun `体积上限为零表示不限制, 不是全删`() {
        // 这个参数的来源是常量或配置，一个写错的值不该把用户的日志清空。
        val files = entries("2026-01-01" to 9_000_000L, "2026-01-02" to 9_000_000L)

        assertTrue(LogRetention.plan(files, keepFiles = 7, maxBytes = 0L).isEmpty())
        assertTrue(LogRetention.plan(files, keepFiles = 7, maxBytes = -1L).isEmpty())
    }

    @Test
    fun `数量与体积两条规则叠加`() {
        val files = consecutiveDays(fromDay = 1, count = 10, bytesPerFile = 1_000_000L)

        val doomed = LogRetention.plan(files, keepFiles = 5, maxBytes = 3_000_000L)

        // 数量规则先砍掉 01~05，剩下的 06~10 共 5MB 仍超上限 3MB，
        // 于是体积规则再砍掉最旧的 06、07。
        assertEquals(
            listOf(
                "msp-2026-01-01.log",
                "msp-2026-01-02.log",
                "msp-2026-01-03.log",
                "msp-2026-01-04.log",
                "msp-2026-01-05.log",
                "msp-2026-01-06.log",
                "msp-2026-01-07.log",
            ),
            doomed.sorted(),
        )
    }

    @Test
    fun `保留数量为零时只留最新那个`() {
        val files = consecutiveDays(fromDay = 1, count = 3)

        val doomed = LogRetention.plan(files, keepFiles = 0, maxBytes = 0L)

        assertEquals(listOf("msp-2026-01-01.log", "msp-2026-01-02.log"), doomed)
    }
}
