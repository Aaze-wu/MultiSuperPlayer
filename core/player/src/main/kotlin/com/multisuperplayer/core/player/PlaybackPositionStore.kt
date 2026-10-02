package com.multisuperplayer.core.player

/**
 * 一条续播记录的裸数据。
 *
 * 只有这三个字段——**没有标题、没有封面、没有时长**。这是存储层故意保持的最小形状：
 * 写入方是内核（它只知道「播到哪」），把展示用元数据也塞进来会让内核不得不去读媒体库。
 * 需要列表展示时由 `:core:data` 拿 [mediaId] 回媒体库连接元数据。
 */
data class PlaybackRecord(
    val mediaId: String,
    val positionMs: Long,
    /** 最后一次写入的时间（不是文件的修改时间）。「最近播放」按它排序。 */
    val savedAtMs: Long,
)

/**
 * 续播位置的存取。
 *
 * 定义在 `:core:player` 而不是 `:core:data`，是因为**内核是写入方**：只有它知道
 * 「现在播到哪、播完了没有」。让内核去依赖 `:core:data`（以及它背后的 DataStore、
 * AndroidKeyStore、媒体库扫描）会把播放内核绑死在存储实现上，而内核本该能单独
 * 换掉（见 `PlaybackController` 的类注释）。
 *
 * 于是接口留在这里、实现留在 `:core:data`，由 DI 接起来——和
 * [SoftwareDecoderSupport] 完全一样的处理方式。
 *
 * ## 线程约定
 *
 * 所有方法都可能做磁盘 IO，因此都是 `suspend`，**不承诺在主线程返回**。
 * 内核调用时只负责把结果用上去，不要假设自己还在主线程上。
 */
interface PlaybackPositionStore {

    /** 读出上次播到的位置；没有记录、或记录已失效时返回 null。 */
    suspend fun read(mediaId: String): Long?

    /**
     * 读出全部记录，按**最后写入时间从新到旧**排序。
     *
     * 「最近播放」页要用它。放在接口上而不是让界面自己遍历：实现里本来就要在
     * 每次写入后读全表做淘汰（见 [ResumeEviction]），所以这个能力是现成的，
     * 另开一个接口/另存一份数据只会多一份需要保持同步的真相。
     */
    suspend fun readAll(): List<PlaybackRecord>

    /**
     * 记下位置。
     *
     * 传 0 或负数**不会**被解释成「清除」——那是另一种意图，用 [clear] 表示。
     * 「空值等于删除」这种约定一旦出现，调用方少传一个参数就会静静删掉用户的数据。
     */
    suspend fun write(mediaId: String, positionMs: Long)

    /** 忘掉这一条（用户点了「从头开始」、或者文件已经看完）。 */
    suspend fun clear(mediaId: String)

    companion object {
        /**
         * 不存任何东西的实现。
         *
         * 作为 [PlaybackController] 的默认参数用它，而不是让参数可空：内核里多一个
         * null 就多一处解包，而「不记进度」本来就是一个完整的、合理的行为
         * （用户在设置里就是这么选的）。
         */
        val None: PlaybackPositionStore = object : PlaybackPositionStore {
            override suspend fun read(mediaId: String): Long? = null
            override suspend fun readAll(): List<PlaybackRecord> = emptyList()
            override suspend fun write(mediaId: String, positionMs: Long) = Unit
            override suspend fun clear(mediaId: String) = Unit
        }
    }
}

/**
 * 「这条位置值不值得记」的规则。
 *
 * 单独一个纯对象，因为它有三个**很容易写反**的边界，而每一个写反之后
 * 从界面上都看不出来（只是「有时候会/不会续播」）：
 *
 * - 开头几秒不记：用户点开一条片子看了一眼（3 秒）就退出，下次进来还得重新看开头。
 *   如果记了，下次会从 3 秒开始——看起来像「播放器坏了，总跳过开头」。
 * - 快看完不记（并且要**忘掉**）：看到 99% 时退出，下次进来直接跳到结尾，
 *   用户会以为文件坏了。所以这时该做的是**清除记录**，而不是记下 99%。
 * - 未知时长（直播、还没解析出来）：只能按「够不够长」判，不能按时长比例判，
 *   否则 `0 * 0.95 = 0`，任何位置都「已看完」，续播永远不生效。
 */
object ResumePolicy {

    /**
     * 至少要看过这么久才值得记住。
     *
     * 15 秒不是随手取的：它同时要大于「误触打开」的时长、小于「用户真的在看」的
     * 时长。10 秒附近是短视频的常见长度，把阈值定在那里会让「随手预览一下」和
     * 「真的在看」分不开。
     */
    const val MIN_POSITION_MS = 15_000L

    /**
     * 播到这个比例之后视为「看完了」，下次从头开始（并清掉记录）。
     *
     * 0.95 而不是 1.0：片尾字幕、下一个的自动切换都会让「最后 5%」被反复经过，
     * 要求「必须播到最后一毫秒」等于永不触发，「看完自动清掉」这个功能就是死的。
     */
    const val COMPLETION_RATIO = 0.95f

    /**
     * 该不该记住这个位置。
     *
     * @param durationMs 总时长，未知时传 0。
     */
    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_MS) return false
        if (durationMs <= 0L) return true
        return positionMs < durationMs * COMPLETION_RATIO
    }
}
