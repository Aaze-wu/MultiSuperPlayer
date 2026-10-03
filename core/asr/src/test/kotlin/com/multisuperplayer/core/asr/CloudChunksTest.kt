package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切片规划的测试。
 *
 * 切块切错了**不会报错**：最后一块如果算漏了，那几十秒的字幕会凭空消失，
 * 而且用户看到的是一份「完整」的字幕文件。所以这里把三种边界都钉死。
 */
class CloudChunksTest {

    @Test
    fun `比一块还短时只有一块`() {
        assertEquals(listOf(CloudChunk(0L, 12_000L)), cloudChunks(totalMs = 12_000L, chunkMs = 300_000L))
    }

    @Test
    fun `正好整除时不产生多出来的空块`() {
        // 多一个 (600000, 600000) 的空块会让服务商收到一个 44 字节的 WAV：
        // 一个多余的错误，以及一次多余的配额消耗。
        assertEquals(
            listOf(CloudChunk(0L, 300_000L), CloudChunk(300_000L, 600_000L)),
            cloudChunks(totalMs = 600_000L, chunkMs = 300_000L),
        )
    }

    @Test
    fun `最后一块按实际剩余长度收尾`() {
        val chunks = cloudChunks(totalMs = 700_000L, chunkMs = 300_000L)
        assertEquals(
            listOf(CloudChunk(0L, 300_000L), CloudChunk(300_000L, 600_000L), CloudChunk(600_000L, 700_000L)),
            chunks,
        )
        assertEquals("块之间不能有空隙也不能重叠", 700_000L, chunks.sumOf { it.endMs - it.startMs })
    }

    @Test
    fun `没有时长时切不出块`() {
        // 走到这里说明调用方该先抛 UnknownDuration（见 CloudAsrTranscriber）。
        // 返回空列表而不是「一整块 0..0」：后者会变成一个空上传。
        assertEquals(emptyList<CloudChunk>(), cloudChunks(totalMs = 0L, chunkMs = 300_000L))
        assertEquals(emptyList<CloudChunk>(), cloudChunks(totalMs = -1L, chunkMs = 300_000L))
    }

    @Test
    fun `块长为零是编程错误`() {
        val error = runCatching { cloudChunks(totalMs = 1_000L, chunkMs = 0L) }.exceptionOrNull()
        assertTrue("实际是 $error", error is IllegalArgumentException)
    }

    @Test
    fun `时长撒谎时宁可报错也不死循环`() {
        // 容器报一个 `Long.MAX_VALUE` 的时长：如果要按它切块，那就是 3e13 块。
        // `start + chunkMs` 溢出成负数之后 `while (start < totalMs)` 永远不会结束——
        // 表现是**程序卡死**而不是一个错误提示。
        val error = runCatching { cloudChunks(totalMs = Long.MAX_VALUE, chunkMs = 300_000L) }.exceptionOrNull()
        assertTrue("实际是 $error", error is IllegalArgumentException)
    }

    @Test
    fun `一天的录音仍然是合法输入`() {
        // 上界不是业务判断，只是防死循环：24 小时以内必须正常切。
        val oneDay = 24L * 60L * 60L * 1000L
        assertEquals(288, cloudChunks(totalMs = oneDay, chunkMs = 300_000L).size)
        assertEquals(oneDay, cloudChunks(totalMs = oneDay, chunkMs = 300_000L).last().endMs)
    }
}
