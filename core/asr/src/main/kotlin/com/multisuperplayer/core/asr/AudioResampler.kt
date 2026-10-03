package com.multisuperplayer.core.asr

import kotlin.math.floor

/**
 * 把任意采样率的多声道 PCM 变成 16 kHz 单声道浮点样本。
 *
 * 这两步都是**纯数学**，所以单独放在这里：它们能脱离 Android 的
 * `MediaExtractor`/`MediaCodec` 做单测，而恰恰是「算错了不会有任何报错、
 * 只会让识别结果变差」的地方——那种 bug 只能靠测试发现。
 */

/**
 * 线性插值重采样器，**跨块连续**。
 *
 * ## 为什么用线性插值就够
 *
 * 教科书会推荐 sinc/多相滤波，理由是抗混叠。但对语音识别来说：
 * ① 采样率是**降低**（44.1k→16k），线性插值确实会让 8 kHz 以上的内容折叠回来，
 * 而人声的可懂度主要在 300–3400 Hz，折叠进来的高频在梅尔滤波器组里占的权重很小；
 * ② 这些模型是在 16 kHz 数据上训的，而那份训练数据本身也是用各种工具降采样的，
 * 模型对这点频谱差异不敏感。
 * 换来的好处是**每个输出样本只有一次乘加**——一条两小时的片子有 1.15 亿个输出样本，
 * 差别是「几秒」和「几十秒」。
 *
 * ## 跨块连续是必须的
 *
 * 解码器每次给一块（几千个样本）。如果每块各自从头插值，每块的**边界**都会算错一点，
 * 一条两小时的片子有 2 万多个块，边界处的错位会累积成听感上的「咔哒」，
 * 而 VAD 恰好对短促的宽带噪声最敏感——结果是切句位置全部偏移几个字。
 * 所以这里保留「下一块要从上一块的最后一个样本开始插值」的状态。
 */
internal class StreamingLinearResampler(fromRate: Int, toRate: Int) {

    init {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive: $fromRate -> $toRate" }
    }

    /** 每个输出样本在输入域上要走多远。 */
    private val step: Double = fromRate.toDouble() / toRate.toDouble()

    /** 下一个输出样本在**当前块**坐标系里的位置；取值范围 `[-1, size)`。 */
    private var position: Double = 0.0

    /** 上一块的最后一个样本，用于块边界的插值。 */
    private var previous: Float = 0f

    /** 输入采样率和输出采样率相同时直接拷贝——省掉一亿次插值（也避免浮点误差）。 */
    private val isIdentity: Boolean = fromRate == toRate

    /**
     * 处理一块单声道样本，返回 16 kHz 的样本（可能比输入多，也可能少）。
     */
    fun process(input: FloatArray, length: Int = input.size): FloatArray {
        if (length <= 0) return EMPTY_SAMPLES
        if (isIdentity) return input.copyOf(length)

        // 估算容量：输出个数不会超过「跳过的输入样本数的倒数」再加起点带来的一个。
        // 这里仍然允许扩容——估算错了只会多分配一次，不会静默丢掉音频。
        var out = FloatArray((length / step).toInt() + 2)
        var count = 0
        while (true) {
            // 必须用 floor 而不是 toInt()：position 落在 (-1, 0) 时表示「在上一块的
            // 最后一个样本和本块第一个样本之间」，而 toInt() 会把它截成 0，
            // 于是变成用 input[0]、input[1] 往回外推——算出来的样本是错的，
            // 而且是每块边界都错一点，听不出、只表现为识别质量下降。
            val index = floor(position).toInt()
            // index 只可能是 -1（用上一块的尾巴）或 >= 0；index + 1 必须落在块内，
            // 否则这个输出样本要等下一块才能算。
            if (index + 1 >= length) break
            val fraction = (position - index).toFloat()
            val left = if (index < 0) previous else input[index]
            val right = input[index + 1]
            if (count == out.size) out = out.copyOf(out.size * 2)
            out[count++] = left + (right - left) * fraction
            position += step
        }
        previous = input[length - 1]
        position -= length
        return if (count == out.size) out else out.copyOf(count)
    }

    private companion object {
        val EMPTY_SAMPLES = FloatArray(0)
    }
}

/**
 * 交错的多声道 PCM 字节 → 单声道浮点样本。
 *
 * - 16 bit 小端：`[-32768, 32767]` 映射到 `[-1, 1)`。
 * - 单精度浮点（`KEY_PCM_ENCODING = ENCODING_PCM_FLOAT`）：**必须区分**。
 *   两者都是 4 字节/样本，猜错的结果不是「声音小一点」而是彻底的噪声。
 *   FLAC 的 24 bit 音轨就会被解成浮点。
 * - 多声道取平均：这是立体声降单声道的标准做法。不做「只取左声道」——
 *   有些片子的旁白只混在右声道，只取一边会得到半条静音的音频。
 */
internal fun downmixToMonoFloat(
    bytes: ByteArray,
    length: Int,
    channelCount: Int,
    isFloat: Boolean,
): FloatArray {
    if (length <= 0 || channelCount <= 0) return FloatArray(0)
    val bytesPerSample = if (isFloat) 4 else 2
    val frameBytes = bytesPerSample * channelCount
    val frameCount = length / frameBytes
    if (frameCount <= 0) return FloatArray(0)

    val out = FloatArray(frameCount)
    for (frame in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channelCount) {
            val offset = frame * frameBytes + channel * bytesPerSample
            sum += if (isFloat) readFloat(bytes, offset) else readShort(bytes, offset) / 32768f
        }
        out[frame] = sum / channelCount
    }
    return out
}

private fun readShort(bytes: ByteArray, offset: Int): Int {
    // 小端：低字节在前。先拼成 Int 再 toShort() 取低 16 位，符号自然正确。
    val raw = (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
    return raw.toShort().toInt()
}

private fun readFloat(bytes: ByteArray, offset: Int): Float {
    val bits = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
}
