package com.multisuperplayer.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志行格式。
 *
 * 这一层的输出会被用户**贴出来**，所以它必须稳定：一旦条目不再从第 0 列开始，
 * 「行首是 `HH:mm:ss.SSS` 的才是新条目」这条解析规则就会静默错位
 * ——所有续行会被当成独立条目，堆栈的每一帧都变成一条日志。
 */
class LogLineTest {

    @Test
    fun `一条普通日志的形状`() {
        assertEquals(
            "14:22:03.481 I SettingsViewModel: 强调色=teal",
            LogLine.format("14:22:03.481", MspLogLevel.INFO, "SettingsViewModel", "强调色=teal"),
        )
    }

    @Test
    fun `等级的字母要区分开`() {
        // 五个等级用五个不同字母：日志文件里没有颜色、没有图标，
        // 字母是唯一的区分手段，两个等级共用一个字母就再也筛不出来了。
        val letters = MspLogLevel.entries.map { level ->
            LogLine.format("00:00:00.000", level, "T", "m").substring(13, 14)
        }
        assertEquals(letters.size, letters.toSet().size)
    }

    @Test
    fun `消息里的换行要缩进四个空格`() {
        val line = LogLine.format("14:22:03.481", MspLogLevel.DEBUG, "T", "第一行\n第二行")

        assertEquals("14:22:03.481 D T: 第一行\n    第二行", line)
    }

    @Test
    fun `Windows 换行也要归一化`() {
        // 日志文本可能是从别处拼进来的（异常消息里带 CRLF）。留着 \r 的话，
        // 用户粘贴出来是一堆看不见的控制符，行尾还会多一个方块。
        val line = LogLine.format("14:22:03.481", MspLogLevel.DEBUG, "T", "第一行\r\n第二行")

        assertEquals("14:22:03.481 D T: 第一行\n    第二行", line)
        assertFalse(line.contains('\r'))
    }

    @Test
    fun `带异常时第一条仍是消息本身`() {
        val line = LogLine.format(
            time = "14:22:03.482",
            level = MspLogLevel.ERROR,
            tag = "PlayerViewModel",
            message = "打开失败",
            error = IllegalStateException("坏掉了"),
        )
        val lines = line.split('\n')

        assertEquals("14:22:03.482 E PlayerViewModel: 打开失败", lines.first())
        assertTrue(lines.size > 1)
    }

    @Test
    fun `堆栈的第一行也要缩进`() {
        // 一个只在缩进上区分的约定：`indent()` 只给「换行之后」的文本加缩进，
        // 所以堆栈的第一行曾经被顶到第 0 列 —— 和「条目从第 0 列开始」撞个正着，
        // 解析时那一行会被当成一条新日志（而且它看起来确实像一条）。
        val line = LogLine.format(
            time = "14:22:03.482",
            level = MspLogLevel.ERROR,
            tag = "T",
            message = "打开失败",
            error = IllegalStateException("坏掉了"),
        )

        val second = line.split('\n')[1]
        assertTrue(
            "异常那一行必须缩进：$second",
            second.startsWith("    java.lang.IllegalStateException"),
        )
    }

    @Test
    fun `堆栈的每一行都缩进, 不会出现第二个条目`() {
        val error = IllegalStateException("坏掉了")
        val line = LogLine.format("14:22:03.482", MspLogLevel.ERROR, "T", "打开失败", error)

        line.split('\n').drop(1).forEach { continuation ->
            assertTrue(
                "续行必须缩进，否则会被当成新条目：$continuation",
                continuation.startsWith("    "),
            )
        }
    }

    @Test
    fun `非 RuntimeException 也要打出堆栈`() {
        // android.util.Log.getStackTraceString 对 RuntimeException 返回空串，
        // 而 IllegalStateException 正是最常见的崩溃类型。这里自己拼堆栈就是为了它。
        val text = LogLine.stackTraceOf(IllegalStateException("坏掉了"))

        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("坏掉了"))
    }

    @Test
    fun `cause 链要一起带上`() {
        // 只打最外层的话，「被谁包装的」这条最重要的线索就没了。
        val error = IllegalStateException("外层", java.io.FileNotFoundException("内层"))

        val text = LogLine.stackTraceOf(error)

        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("java.io.FileNotFoundException"))
    }

    @Test
    fun `堆栈末尾不留空行`() {
        val text = LogLine.stackTraceOf(RuntimeException("x"))

        assertEquals(text.trimEnd(), text)
    }
}
