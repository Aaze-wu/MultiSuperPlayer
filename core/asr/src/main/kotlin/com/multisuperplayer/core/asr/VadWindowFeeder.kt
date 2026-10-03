package com.multisuperplayer.core.asr

/**
 * 把任意长度的音频块切成 VAD 的**一个个窗口**再喂。
 *
 * ## 为什么必须切（不切会静悄悄地少字幕）
 *
 * `sherpa-onnx` 的 `Vad.acceptWaveform()` 一次只应该收到**一个窗口**
 * （[AsrVadTuning.WINDOW_SIZE] 个采样点，16 kHz 下 512 点 = 32 ms）。
 * 一次给多了它**不报错**，只是这块音频里的大部分根本没被判决过——于是中间的
 * 静音看不见，好几句话被并成一段，而识别器对着一个十几秒的超长段只会吐出几个字。
 *
 * 实测（sherpa-onnx 1.13.8 + 仓库里这份 `silero_vad.onnx`，16 kHz 单声道，
 * 同一段 18.52 秒、6 句话、句间静音 1.0～1.3 秒的素材，喂入块长 → 段数）：
 *
 * | 每次 `acceptWaveform` 的量 | 段数 | 说明 |
 * | --- | --- | --- |
 * | 512（1 窗，32 ms） | **6** | 与「好听感」逐段对齐 |
 * | 4096（0.26 s） | 6 | 已经能看出边界变粗（起点晚 130 ms 左右） |
 * | 8192（0.51 s） | 3 | 开始并句 |
 * | 16384（1.02 s） | **2** | 这正是解码器块长，即线上实际发生的情况 |
 * | 32768（2.05 s） | 1 | |
 * | 整个文件一次 | 1 | 只剩末尾 0.32 秒 |
 *
 * 设备上的日志（`VAD 段：start=11360 采样=183648`）与上表 16384 那一行的
 * PC 复现（0.710–12.188 秒 = 11360/16000、183648/16000）**逐位一致**，
 * 所以这不是「模拟器上的偶然现象」。
 *
 * ## 不是「静音太短」的锅
 *
 * 换一段「1.32 秒真实语音 + 1.00 秒**纯数字零**」重复 6 次的素材：按 512 喂是 6 段，
 * 按 16384 喂是 1 段。数字零没有任何歧义，静音时长也远超 0.5 秒的门限——
 * 结论只能是**那块音频压根没被判决**，而不是判决得太迟钝。这也解释了为什么
 * 调小 `MIN_SILENCE_SECONDS` 救不回来。
 *
 * ## 用法
 *
 * ```kotlin
 * val feeder = VadWindowFeeder(AsrVadTuning.WINDOW_SIZE) { vad.acceptWaveform(it) }
 * feeder.accept(decoderChunk)   // 任意长度
 * feeder.finish()               // 补齐尾巴，必须在 vad.flush() 之前
 * ```
 *
 * [feed] 收到的数组**是同一个实例**（复用一块 [windowSize] 的缓冲，避免每 32 ms
 * 分配一次；两小时的片子是几十万次）。原生侧会在调用内同步拷走，所以复用是安全的，
 * 但**调用方不能把这个引用存下来**。
 */
internal class VadWindowFeeder(
    private val windowSize: Int = AsrVadTuning.WINDOW_SIZE,
    private val feed: (FloatArray) -> Unit,
) {

    init {
        require(windowSize > 0) { "窗口长度必须是正数：$windowSize" }
    }

    /** 复用缓冲。喂出去的永远是它本身，长度恒为 [windowSize]。 */
    private val window = FloatArray(windowSize)

    /** [window] 里已经攒了多少个采样点（0 表示没有半窗）。 */
    private var filled = 0

    /** 手上是否还攥着不足一窗的余数。只给测试和排查用。 */
    val hasPending: Boolean get() = filled > 0

    /** 喂一块任意长度的样本。不足一窗的余数留到下一次（或 [finish]）。 */
    fun accept(chunk: FloatArray) {
        var offset = 0

        // 先把上一次剩下的半窗补满。这一步不能省：余数直接丢掉的话，
        // 每块之间都会少 0～31 ms 的音频，而缺得最狠的地方恰好是块首
        // ——一句话的开头几个字最容易就这样没了。
        if (filled > 0) {
            val take = minOf(windowSize - filled, chunk.size)
            System.arraycopy(chunk, 0, window, filled, take)
            filled += take
            offset = take
            if (filled == windowSize) {
                feedWindow()
            }
        }

        // 整窗直接喂。
        while (chunk.size - offset >= windowSize) {
            System.arraycopy(chunk, offset, window, 0, windowSize)
            offset += windowSize
            feedWindow()
        }

        // 尾巴留到下一次。
        if (offset < chunk.size) {
            System.arraycopy(chunk, offset, window, 0, chunk.size - offset)
            filled = chunk.size - offset
        }
    }

    /**
     * 音频喂完了，把最后不足一窗的余数补零凑成一窗喂出去。
     *
     * 补零（而不是直接喂一个短数组）有两个原因：原生侧只认整窗；补的零是静音，
     * 正好让 VAD 把最后一段话收口——直接 `flush()` 的话最后那句的尾巴可能还没判定完。
     */
    fun finish() {
        if (filled == 0) {
            return
        }
        window.fill(0f, filled, windowSize)
        feedWindow()
    }

    private fun feedWindow() {
        filled = 0
        feed(window)
    }
}
