package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VadWindowFeeder] 的**唯一不变量**：交给 VAD 的每一次都是一个整窗。
 *
 * 为什么专门为它写一测组：破坏这条不变量**不会报错**，只会让 VAD 漏判中间的音频
 * （并句、丢开头），而症状是「字幕少了 / 时间轴不对」——离这段代码很远。
 * 实测数据见 [VadWindowFeeder] 的类注释（16384 点喂入 → 2 段，512 点喂入 → 6 段）。
 */
class VadWindowFeederTest {

    /** 收下每次喂进来的东西，并**当场拷一份**（真实调用方也是这么做不得的：数组是复用的）。 */
    private class Recorder {

        val fed = mutableListOf<FloatArray>()

        val sink: (FloatArray) -> Unit = { fed += it.copyOf() }

        /** 把喂进去的所有窗口（含 `finish` 补的零）串成一条，用来比对样本有没有丢/重。 */
        fun joined(): FloatArray = fed.fold(FloatArray(0)) { acc, next -> acc + next }
    }

    @Test
    fun `每个喂出去的窗口长度都等于 windowSize`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.accept(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f))
        feeder.accept(floatArrayOf(8f, 9f))
        feeder.finish()

        assertTrue(recorder.fed.isNotEmpty())
        // 这条是「VAD 只认整窗」的硬约束：喂一个 7 点的数组进去，
        // 原生侧会按自己的窗口切法处理它，等于又把音频漏掉一部分。
        recorder.fed.forEach { assertEquals(4, it.size) }
    }

    @Test
    fun `不足一窗不喂，攒够才喂`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.accept(floatArrayOf(1f, 2f, 3f))
        assertTrue(recorder.fed.isEmpty())
        assertTrue(feeder.hasPending)

        feeder.accept(floatArrayOf(4f))
        assertEquals(listOf(1f, 2f, 3f, 4f), recorder.fed[0].toList())
        assertFalse(feeder.hasPending)
    }

    @Test
    fun `跨多次 accept 累积：样本不丢、不重、顺序不变`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 5, feed = recorder.sink)

        // 故意用互质的长度，让「按块对齐」的写法露馅。
        val chunks = listOf(3, 7, 1, 0, 11, 2, 5)
        val expected = ArrayList<Float>()
        var next = 0f
        for (size in chunks) {
            val chunk = FloatArray(size) { next++ }
            expected += chunk.toList()
            feeder.accept(chunk)
        }
        feeder.finish()

        // 喂出去的 = 输入的样本 + 补到整窗的零（补的零只可能在最后）。
        val got = recorder.joined()
        assertEquals(0, got.size % 5)
        assertTrue("多出来的只能是补的零，实际多了 ${got.size - expected.size} 个", got.size - expected.size in 0 until 5)
        expected.forEachIndexed { index, value ->
            assertEquals("第 $index 个采样点变了", value, got[index], 0f)
        }
        for (index in expected.size until got.size) {
            assertEquals("补位不是零", 0f, got[index], 0f)
        }
    }

    @Test
    fun `finish 把余数补零凑成一窗`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.accept(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertEquals(1, recorder.fed.size)
        feeder.finish()

        assertEquals(2, recorder.fed.size)
        assertEquals(listOf(5f, 6f, 0f, 0f), recorder.fed[1].toList())
        assertFalse(feeder.hasPending)
        // 补的是零而不是上一窗的旧值：复用缓冲忘了清后半截的话，这里会是 3f、4f。
        assertEquals(0f, recorder.fed[1][3], 0f)
    }

    @Test
    fun `正好整窗时 finish 不补空窗`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.accept(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f))
        feeder.finish()

        assertEquals(2, recorder.fed.size)
        assertFalse(feeder.hasPending)
    }

    @Test
    fun `什么都没喂时 finish 不产生任何窗口`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.finish()

        assertTrue(recorder.fed.isEmpty())
    }

    @Test
    fun `空块被忽略`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(windowSize = 4, feed = recorder.sink)

        feeder.accept(FloatArray(0))
        feeder.accept(floatArrayOf(1f, 2f))

        assertTrue(recorder.fed.isEmpty())
        assertTrue(feeder.hasPending)
    }

    @Test
    fun `默认窗口就是 VAD 的窗口`() {
        val recorder = Recorder()
        val feeder = VadWindowFeeder(feed = recorder.sink)

        // 默认值接错（比如写成解码器的块长）不会有任何编译错误，
        // 只会在设备上表现为「字幕少了几条」，所以在这里钉死。
        feeder.accept(FloatArray(AsrVadTuning.WINDOW_SIZE))
        feeder.accept(floatArrayOf(1f))

        assertEquals(1, recorder.fed.size)
        assertEquals(AsrVadTuning.WINDOW_SIZE, recorder.fed[0].size)
    }

    @Test
    fun `窗口长度必须为正`() {
        assertThrows(IllegalArgumentException::class.java) { VadWindowFeeder(windowSize = 0) {} }
        assertThrows(IllegalArgumentException::class.java) { VadWindowFeeder(windowSize = -512) {} }
    }
}
