package com.multisuperplayer.core.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重采样与降混的测试。
 *
 * 这两步是**纯数学**，也正是「算错了不会有任何报错、只会让识别结果变差」的地方：
 * 采样率降错、块边界的相位差一点、16 bit 被当成浮点读——三者都不会崩，
 * 只会让字幕变得莫名其妙。所以它们必须在这里被钉住。
 */
class AudioResamplerTest {

    private fun ramp(size: Int): FloatArray = FloatArray(size) { it.toFloat() }

    // ------------------------------------------------------------ 重采样

    @Test
    fun `采样率相同时直接拷贝`() {
        val input = ramp(10)
        val output = StreamingLinearResampler(16_000, 16_000).process(input)

        assertArrayEquals(input, output, 0f)
        // 拷贝而不是同一个引用：调用方复用缓冲区时不该被改到
        assertTrue("应该是新数组", output !== input)
    }

    @Test
    fun `48k 降到 16k 是每三个输入一个输出`() {
        val output = StreamingLinearResampler(48_000, 16_000).process(ramp(48))

        assertEquals(16, output.size)
        assertEquals(0f, output.first(), 0f)
    }

    @Test
    fun `非法采样率直接抛异常`() {
        // 采样率是 0 或负数时，`step` 会变成 0 或负数，循环里会算出无穷多个输出样本
        assertThrows(IllegalArgumentException::class.java) { StreamingLinearResampler(0, 16_000) }
        assertThrows(IllegalArgumentException::class.java) { StreamingLinearResampler(16_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { StreamingLinearResampler(-44_100, 16_000) }
    }

    @Test
    fun `长度为 0 返回空表`() {
        val resampler = StreamingLinearResampler(48_000, 16_000)
        assertEquals(0, resampler.process(FloatArray(0)).size)
        assertEquals(0, resampler.process(ramp(10), length = 0).size)
    }

    @Test
    fun `只用前 length 个样本`() {
        val resampler = StreamingLinearResampler(16_000, 16_000)
        val output = resampler.process(ramp(10), length = 4)

        assertEquals(4, output.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), output, 0f)
    }

    @Test
    fun `跨块连续：分成两块的结果与一次处理逐样本相同`() {
        // 解码器每次给一块几千个样本。如果每块各自从头插值，每块的边界都会错一点，
        // 一条两小时的片子有两万多个块，累积起来就是切句位置整体偏移几个字。
        val input = ramp(300)
        val single = StreamingLinearResampler(48_000, 16_000).process(input)

        val chunked = StreamingLinearResampler(48_000, 16_000)
        val first = chunked.process(input, length = 100)
        val second = chunked.process(input.copyOfRange(100, 300))

        assertEquals(single.size, first.size + second.size)
        assertArrayEquals(single, first + second, 1e-6f)
    }

    @Test
    fun `跨块连续：按解码器块长切也不丢样本、不走样`() {
        // 44100 -> 16000 正好是 16000 个输出样本（step = 2.75625，比例是无理循环）
        val input = ramp(44_100)
        val single = StreamingLinearResampler(44_100, 16_000).process(input)

        val chunked = StreamingLinearResampler(44_100, 16_000)
        val collected = ArrayList<Float>(single.size)
        var offset = 0
        while (offset < input.size) {
            val length = minOf(1_024, input.size - offset)
            collected.addAll(chunked.process(input.copyOfRange(offset, offset + length)).asList())
            offset += length
        }

        assertEquals("分块处理不该丢掉任何输出样本", single.size, collected.size)
        assertEquals(16_000, single.size)
        assertArrayEquals(single, collected.toFloatArray(), 1e-6f)
    }

    @Test
    fun `输出样本数与采样率之比相称`() {
        val output = StreamingLinearResampler(44_100, 16_000).process(ramp(44_100))

        // 16000 ± 1：最后一个输出样本要等「下一个输入样本」才能插值出来，
        // 所以块尾最多差一个
        assertTrue("输出 ${output.size} 个样本，和 44100/16000 不成比例", output.size in 15_999..16_001)
    }

    // ------------------------------------------------------------ 降混

    /** 小端 16 bit。 */
    private fun pcm16(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** 小端 32 bit 浮点。 */
    private fun pcmFloat(vararg values: Float): ByteArray {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            val bits = java.lang.Float.floatToIntBits(value)
            for (byte in 0 until 4) {
                bytes[index * 4 + byte] = ((bits shr (byte * 8)) and 0xFF).toByte()
            }
        }
        return bytes
    }

    @Test
    fun `16 位单声道按 32768 归一化，负号保留`() {
        val bytes = pcm16(0, 16_384, 32_767, -16_384, -32_768)
        val output = downmixToMonoFloat(bytes, bytes.size, channelCount = 1, isFloat = false)

        assertEquals(5, output.size)
        assertEquals(0f, output[0], 1e-6f)
        assertEquals(0.5f, output[1], 1e-6f)
        assertEquals(32_767f / 32_768f, output[2], 1e-6f)
        assertEquals(-0.5f, output[3], 1e-6f)
        // 负数必须先转成有符号 short：忘了这一步的话 -32768 会变成 +32768
        assertEquals(-1f, output[4], 1e-6f)
    }

    @Test
    fun `按小端读字节`() {
        // [0x00, 0x40] 小端是 0x4000 = 16384。按大端读会得到 0x0040 = 64，
        // 只表现为「声音很小、识别率下降」
        val output = downmixToMonoFloat(byteArrayOf(0x00, 0x40), 2, channelCount = 1, isFloat = false)

        assertEquals(16384f / 32768f, output.single(), 1e-6f)
    }

    @Test
    fun `多声道取平均`() {
        // 立体声：只取左声道的话，旁白只混在右声道的片子会变成半条静音
        val stereo = pcm16(16_384, 0, -32_768, 32_767)
        val output = downmixToMonoFloat(stereo, stereo.size, channelCount = 2, isFloat = false)

        assertEquals(2, output.size)
        assertEquals(0.25f, output[0], 1e-6f)
        assertEquals((-1f + 32_767f / 32_768f) / 2f, output[1], 1e-6f)
    }

    @Test
    fun `浮点 PCM 与 16 位 PCM 不共用一条路径`() {
        // 两者都是 4 字节/样本（浮点是 1 声道 4 字节、16 位是 2 声道 4 字节），
        // 猜错的后果不是「声音小一点」，而是彻底的噪声
        val floatBytes = pcmFloat(0.9f)
        val asFloat = downmixToMonoFloat(floatBytes, floatBytes.size, channelCount = 1, isFloat = true)
        assertEquals(0.9f, asFloat.single(), 1e-6f)

        // 同一串字节按 16 位立体声解释，得到的是完全不同的数
        val asPcm16 = downmixToMonoFloat(floatBytes, floatBytes.size, channelCount = 2, isFloat = false)
        assertTrue("浮点被当成 16 位读时必须看出区别", kotlin.math.abs(asPcm16.single() - 0.9f) > 0.01f)
    }

    @Test
    fun `末尾不足一帧的字节被忽略`() {
        // 3 个字节的 16 位单声道：只有第 1 帧是完整的
        val bytes = pcm16(16_384) + byteArrayOf(0x7F)
        val output = downmixToMonoFloat(bytes, bytes.size, channelCount = 1, isFloat = false)

        assertEquals(1, output.size)
        assertEquals(0.5f, output.single(), 1e-6f)
    }

    @Test
    fun `只看前 length 个字节`() {
        val bytes = pcm16(16_384, 16_384, 16_384)
        val output = downmixToMonoFloat(bytes, length = 4, channelCount = 2, isFloat = false)

        assertEquals(1, output.size)
    }

    @Test
    fun `声道数为 0 或长度为 0 时返回空表`() {
        val bytes = pcm16(1, 2)
        assertEquals(0, downmixToMonoFloat(bytes, bytes.size, channelCount = 0, isFloat = false).size)
        assertEquals(0, downmixToMonoFloat(bytes, length = 0, channelCount = 2, isFloat = false).size)
        assertEquals(0, downmixToMonoFloat(ByteArray(0), length = 0, channelCount = 1, isFloat = true).size)
    }
}
