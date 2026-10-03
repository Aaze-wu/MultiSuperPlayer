package com.multisuperplayer.core.asr

/**
 * 识别引擎要求的采样率。
 *
 * 16 kHz 单声道是**两条模型和 VAD 的共同前置条件**，不是可以调的参数：模型是按
 * 16 kHz 的梅尔谱训练的，喂 44.1 kHz 进去不会报错，只会识别出一串毫无意义的字。
 * 所以整条链路（解码 → 重采样 → VAD → 识别）都按这个值收敛，
 * 而 PcmExtractor 负责把任意源的采样率降到它。
 */
const val ASR_SAMPLE_RATE: Int = 16_000

/**
 * VAD 的判决参数。
 *
 * 切句这件事**完全交给 sherpa 的 VAD**，不再自己写一套分段规则——两套规则一定会
 * 打架（比如「我这边静音 0.5 s 就算断句」和「引擎自己 0.8 s 才断」同时生效的结果，
 * 是断句位置由两者中的**小**者决定，而那些句子是引擎认为不完整的）。
 *
 * 这几个值都是**秒**，对应 [com.k2fsa.sherpa.onnx.SileroVadModelConfig] 的同名字段。
 */
object AsrVadTuning {

    /** 判「是语音」的概率门限。0.5 是 silero 官方默认值。 */
    const val THRESHOLD: Float = 0.5f

    /**
     * 多长的静音才算一句话说完了（秒）。
     *
     * 用 sherpa 的默认值 0.5 s，没有往下调：调到 0.3 s 会把「这个是 / 那个」中间的
     * 自然停顿切成两条，而识别器拿到半句话时错误率明显上升——字幕的条数变多了，
     * 每一条都更不准，是双输。往上调到 1 s 又会让「一句话」跨过中间的插话。
     *
     * 注意它的**音质代价**：0.5 s 的停顿被保留在段内时，识别器会把它当普通静音处理，
     * 不会产生多余的标点——这一点实测确认过。
     */
    const val MIN_SILENCE_SECONDS: Float = 0.5f

    /** 短于这个时长的「语音」当噪声丢掉（秒）。默认值 0.25 s，比一个字的时长还短。 */
    const val MIN_SPEECH_SECONDS: Float = 0.25f

    /**
     * 一段语音最长多长，超过就被强制切开（秒）。
     *
     * sherpa 的默认值是 5 s，这对**字幕**来说太碎：一口气说完的长句会被切成好几条，
     * 阅读时眼睛要反复找回上一句。但也不能无限长——一条字幕显示 20 s 是没法读的，
     * 而且字幕要在屏幕底部占位置。
     *
     * 取 8 s：中文正常语速约 4～5 字/秒，8 s ≈ 35 字，两行放得下；
     * 而超过 8 s 还找不到 0.5 s 停顿的情况很少见（那样的人通常是在播报）。
     *
     * 代价要说清楚：强制切分是**在音频中间硬切**，切点处的那个字可能被两边各读一半，
     * 出现错字。这是「按时间轴生成字幕」这件事本身的代价，不是实现缺陷。
     */
    const val MAX_SPEECH_SECONDS: Float = 8f

    /**
     * silero v5 在 16 kHz 下的输入窗口，512 点（= 32 ms）。
     *
     * 必须和模型对得上：喂 256 或 1024 都不会报错，只是检测结果变成噪声
     * （要么整段判成人声、要么整段判成静音）。所以它只在一处定义，
     * `SherpaEngine` 直接用它，不另写一个 512。
     *
     * 它还有**第二个**用途：`acceptWaveform()` 每次只能喂一个窗口，喂多了 VAD 会
     * 漏判这块里的大部分音频（并句、丢开头）。切块由 [VadWindowFeeder] 负责，
     * 那里有实测数据。换句话说这个常量既描述模型，也描述调用方式，两件事不能分开看。
     */
    const val WINDOW_SIZE: Int = VAD_WINDOW_SIZE
}

/** 采样点区间。VAD 的输出单位，也是 [AsrSegmentBuilder] 的输入输出单位。 */
data class AsrRange(val startSample: Int, val endSample: Int) {
    val lengthSamples: Int get() = endSample - startSample
}

/** 识别出来的一段话：时间轴 + 原始文本（还没清洗）。 */
data class AsrSegment(val startMs: Long, val endMs: Long, val text: String)

/**
 * VAD 报出的一段区间 + 这段识别出来的文本。
 *
 * **为什么文本要跟着区间一起进来**，而不是「先整理区间、回来再按下标配文本」：
 * 整理过程**会丢掉一些区间**（被前一段包住的、夹完变成零长的）。只传区间的话，
 * 丢一条就会让它后面**所有**字幕的文本整体串位。串位后的字幕看着完全正常，
 * 只是对不上口型——不是崩溃，是「总觉得哪里不对」，很难查。
 */
internal class AsrDetected(val range: AsrRange, val text: String)

/**
 * 把 VAD 吐出来的语音区间整理成能当字幕用的时间轴。
 *
 * 这一步**不做切句**（切句是 VAD 的活，见 [AsrVadTuning]），只做三件事：
 *
 * 1. **外扩余量**：VAD 按 32 ms 的窗口判决，报出的边界**不会正好落在起音上**——
 *    实测起点偏早 30～60 ms、末尾偏晚 0～500 ms（数据见 [PAD_MS]）。
 *    再各放 [PAD_MS] 的余量，字幕才不会「比声音晚半拍出来」，说完之后也不会立刻消失。
 * 2. **不许重叠**：两个字幕同时挂在屏幕上是明确的缺陷。外扩时只往静音里长——
 *    左边界不越过上一段的**语音**结束点，右边界不吃掉下一段的**语音**起点；
 *    如果两段之间连 [PAD_MS] × 2 的静音都没有，就在两段语音的中点分界。
 * 3. **夹进总长**：不得为负，不得超过音频总采样点数（否则字幕会超出片尾）。
 */
internal object AsrSegmentBuilder {

    /**
     * 前后各外扩多少毫秒。
     *
     * 160 ms 是「一个字的起音」量级：中文里声母到韵母的过渡大约在这个尺度。
     * 实测（日语/中文 6 句素材，按 [VadWindowFeeder] 的规矩喂）：VAD 报出的起点落在
     * 真实起音**之前** 30～60 ms（判决有几帧拖尾），后端落在语音结束后 0～500 ms
     * （取决于后面那段静音何时攒够 `MIN_SILENCE_SECONDS` 的门限）。前后各放 160 ms
     * 之后，听感上字幕和声音是同时到的；再大就会「话说完了字幕还挂着」。
     */
    const val PAD_MS: Long = 160

    fun build(detected: List<AsrDetected>, totalSamples: Int, sampleRate: Int): List<AsrSegment> {
        if (detected.isEmpty() || totalSamples <= 0 || sampleRate <= 0) return emptyList()

        // 先夹、再排序、再把重叠的输入抹平：正常 VAD 不会给出重叠区间，
        // 但这里的三步都是纯数学，不依赖上游的「正常」。
        val ordered = ArrayList<AsrDetected>(detected.size)
        for (item in detected.sortedBy { it.range.startSample }) {
            val start = item.range.startSample.coerceIn(0, totalSamples)
            val end = item.range.endSample.coerceIn(0, totalSamples)
            val floor = ordered.lastOrNull()?.range?.endSample ?: 0
            when {
                end > start && start >= floor -> ordered += AsrDetected(AsrRange(start, end), item.text)
                end > floor && floor > start -> ordered += AsrDetected(AsrRange(floor, end), item.text)
            }
        }
        if (ordered.isEmpty()) return emptyList()

        val padSamples = PAD_MS * sampleRate / 1000L
        val bounds = ordered.map {
            (it.range.startSample.toLong() - padSamples) to (it.range.endSample.toLong() + padSamples)
        }.toMutableList()

        // 相邻两段离得太近时，在语音中点分界（而不是让两边各自外扩、重叠在一起）。
        for (index in 0 until bounds.size - 1) {
            val (startA, endA) = bounds[index]
            val (startB, endB) = bounds[index + 1]
            if (endA > startB) {
                val mid = (ordered[index].range.endSample.toLong() + ordered[index + 1].range.startSample.toLong()) / 2
                bounds[index] = startA to mid
                bounds[index + 1] = mid to endB
            }
        }

        val limit = totalSamples.toLong()
        return bounds.mapIndexed { index, (start, end) ->
            AsrSegment(
                startMs = start.coerceIn(0L, limit) * 1000L / sampleRate,
                endMs = end.coerceIn(0L, limit) * 1000L / sampleRate,
                text = ordered[index].text,
            )
        }.filter { it.endMs > it.startMs }
    }
}
