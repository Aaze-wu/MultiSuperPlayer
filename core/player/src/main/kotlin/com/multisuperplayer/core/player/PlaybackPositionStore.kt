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
     * 传 0 是**合法值**，意思是「记住这一条，但下次从头播」——它出现在两种场合：
     * 文件已经看完（留着 99% 的位置下次会跳到结尾，像文件坏了），以及位置太短不
     * 值得当作续播点。两者都**不是**删除：[read] 会如实返回 0，而 [readAll] 里
     * 这条记录依然存在，因为「最近播放」要的是「播放过」这个事实，不是续播位置。
     */
    suspend fun write(mediaId: String, positionMs: Long)

    /**
     * 记下「这一条刚刚被播过」，但**不动**续播位置。
     *
     * 为什么不能靠 [write] 兼职：这个动作必须保住已有的位置。用户在昨天看到 40 分钟
     * 的那条上随手点开看了 3 秒就退出——此时 [write] 会把 40 分钟改成 3 秒（正是
     * 内核里那条「第三种情况什么都不做」要防的事），而什么都不写又会让这个文件
     * 完全不出现在「最近播放」里。两件事只能分开表达。
     *
     * 没有任何记录时按 [write]`(mediaId, 0)` 处理：位置本来就无从记起。
     */
    suspend fun markPlayed(mediaId: String)

    /**
     * 把**已有**记录的位置归零；没有记录时什么都不做。
     *
     * 和 [write]`(mediaId, 0)` 的区别就是「会不会多出一条记录」，而这一条差别在
     * 设备上看得见：播完了必须让下次从头播，但如果这一条以前从没播过，写 0 会顺手
     * 造出一条播放记录——于是关掉「记录最近播放」之后，一个从头看到尾的短片还是
     * 会出现在「最近播放」里。「清掉续播点」和「记一条播过」是两件事。
     *
     * （之前是 [markPlayed] 的注释里那句 `write(mediaId, 0)`，两者不能互相兼职：
     * 那个必须保住已有位置，这个必须覆盖成 0。）
     */
    suspend fun resetPosition(mediaId: String)

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
            override suspend fun markPlayed(mediaId: String) = Unit
            override suspend fun resetPosition(mediaId: String) = Unit
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
 * - 快看完不记（并且要**归零**）：看到 99% 时退出，下次进来直接跳到结尾，
 *   用户会以为文件坏了。所以这时该做的是把位置写成 0（记录留着，「最近播放」里
 *   仍然是播过的一条），而不是记下 99%。
 * - 未知时长（直播、还没解析出来）：只能按「够不够长」判，不能按时长比例判，
 *   否则 `0 * 0.95 = 0`，任何位置都「已看完」，续播永远不生效。
 */
object ResumePolicy {

    /**
     * 落盘时该做哪一件事。
     *
     * 三种结果而不是一个布尔：**「不记续播位置」和「什么都不做」是两件事**。
     * 前者只说明这个位置不值得当续播点，但这一条媒体刚刚确实被播过，「最近播放」
     * 正需要知道这件事。把两者挤成一个 false，症状就是「短片和看了一眼的长片
     * 永远不出现在最近播放里」。
     */
    enum class Action {
        /** 位置值得记：写成当前位置。 */
        REMEMBER,

        /** 看完了：位置归零（下次从头播），记录本身保留。 */
        RESTART_FROM_HEAD,

        /** 位置太短，不值得覆盖续播位置，但「刚播过」要记下来。 */
        MARK_PLAYED,
    }

    /**
     * 至少要看过这么久才值得记住。
     *
     * 15 秒不是随手取的：它同时要大于「误触打开」的时长、小于「用户真的在看」的
     * 时长。10 秒附近是短视频的常见长度，把阈值定在那里会让「随手预览一下」和
     * 「真的在看」分不开。
     */
    const val MIN_POSITION_MS = 15_000L

    /**
     * 播到这个比例之后视为「看完了」，下次从头开始（位置归零，**记录留着**）。
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

    /**
     * 落盘时该做哪一件事（见 [Action]）。
     *
     * 单独一个函数而不是让内核自己写 if/else：这三条分支就是**上面那些边界**本身，
     * 而它们写反之后从界面上看不出来（只是「有时候会/不会续播」「最近播放里少一条」）。
     * 抽成纯函数才能在 JVM 里把每个边界都钉住。
     *
     * 顺序不能换：先判「够长吗」，再判「看完了吗」。反过来的话，一个 10 秒的片段
     * 播到第 10 秒会先撞上「已看完」——归零和「太短不记」的结果都是 0，看不出区别，
     * 但只要哪天两者的行为不一样（比如「太短」改成不写），这个顺序就会变成 bug。
     *
     * @param durationMs 总时长，未知时传 0。
     */
    fun persistAction(positionMs: Long, durationMs: Long): Action = when {
        shouldRemember(positionMs, durationMs) -> Action.REMEMBER
        durationMs > 0L && positionMs >= durationMs * COMPLETION_RATIO -> Action.RESTART_FROM_HEAD
        else -> Action.MARK_PLAYED
    }

    /**
     * 真正要交给 [PlaybackPositionStore] 的那一步。
     *
     * 比 [Action] 多这一层，是因为内核手里还有**用户的两个开关**，而它们否掉的
     * 东西并不一样（实测过的坑都记在下面，因为两个都很容易“看不出错”）：
     *
     * - 「记录最近播放」关掉后，[Action.RESTART_FROM_HEAD] 还是会把位置归零——
     *   这是必要的（不归零下次就从片尾开始播，像文件坏了），但 `write(id, 0)`
     *   顺手就**新建了一条记录**，于是「关掉历史」之后一个从头看到尾的短片
     *   仍然会冒进列表里，和开关的承诺直接矛盾。所以要分开：[ZERO] 允许新建，
     *   [ZERO_IF_RECORDED] 只清已有记录。
     * - 「记住播放位置」关掉时不再写任何位置，但「播过」这件事仍然按另一个开关记
     *   （[Persist.MARK_PLAYED] 不会动位置，所以不会把昨天的 40 分钟改成 3 秒）。
     *   这条路径必须留着：「每次从头播、但看得见看过什么」正是靠它。
     *
     * 两个开关互相独立，四种组合都有意义——所以这里是**表格**而不是两个 if。
     */
    enum class Persist {
        /** 写当前位置。 */
        POSITION,

        /** 位置归零；没有记录时也新建一条（用于「播完了」且允许记录新条目）。 */
        ZERO,

        /** 位置归零，但**只在已经有记录时**——「播完了」且不允许记录新条目。 */
        ZERO_IF_RECORDED,

        /** 只记「播过」，位置一个字节不动。 */
        MARK_PLAYED,

        /** 这一次什么都不写（连时间戳都不动）。 */
        NOTHING,
    }

    /**
     * 把 [Action] 和用户的两个开关合成「内核该调哪个方法」。
     *
     * @param rememberPosition 「记住播放位置」开关（关掉后不再写位置，但
     *   「播过」仍然按另一个开关记）。
     * @param recordRecentPlays 「记录最近播放」开关。关掉后**不再为了「最近播放」而写任何东西**：
     *   既不新建那种只表示「播过」的空条目，也不再刷新已有条目的时间戳（时间戳就是
     *   「你什么时候看的」，开关关掉之后不该再记它）。但**续播自己的写入不受影响**——
     *   两者共用同一份存储，而用户关掉的是「列表」，不是「接着播」。所以看过一段的
     *   内容仍然会留下续播记录（顺带也会出现在列表里），这不是漏写，是那份存储的性质；
     *   「记住播放位置」开着的时候，续播的位置和「最近播放」本来就是同一份数据。
     */
    fun persistStep(
        positionMs: Long,
        durationMs: Long,
        rememberPosition: Boolean,
        recordRecentPlays: Boolean,
    ): Persist = when {
        !rememberPosition -> if (recordRecentPlays) Persist.MARK_PLAYED else Persist.NOTHING
        else -> when (persistAction(positionMs, durationMs)) {
            Action.REMEMBER -> Persist.POSITION
            Action.RESTART_FROM_HEAD ->
                if (recordRecentPlays) Persist.ZERO else Persist.ZERO_IF_RECORDED

            Action.MARK_PLAYED -> if (recordRecentPlays) Persist.MARK_PLAYED else Persist.NOTHING
        }
    }
}
