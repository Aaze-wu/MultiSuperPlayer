package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.SubtitleDocument

/**
 * 已解析的字幕文档缓存，按访问顺序淘汰。
 *
 * ## 为什么键里必须带上体积
 *
 * 这个缓存原本只认 uri，于是「同一个 uri、内容变了」永远拿到旧解析结果。
 * 实测撞到的场景：语音识别重新生成同一首歌的字幕——生成的字幕 uri 是按媒体算出来的
 * 合成 uri（见 [GeneratedSubtitleStore.uriFor]），重跑不变——日志里已经写着
 * 「生成完成：6 条」，面板上却一直显示上一版留下的「2 条」，杀进程重开才对。
 * 磁盘上是新字幕、界面是旧字幕，同一件事两个说法。
 *
 * 「重新扫描字幕」也是同一个坑，而且更讽刺：用户点那个按钮往往**正因为文件被改过**，
 * 而缓存恰好让这次重扫读不到新内容——按钮的语义被缓存吃掉了。
 *
 * 所以键 = uri + 体积。体积变了就当没有缓存（重新读 + 解析，几毫秒的事）。
 * 只改一个字符、体积恰好不变的编辑仍会漏网，但那种编辑不会经过这个应用的手；
 * 而「文件被这个应用自己重写」的那条路径体积必然变（条数变了，字节数就变了）。
 *
 * ## 拿不到体积就干脆不缓存
 *
 * 体积 `<= 0` 意味着「不知道这份文件多大」（拿不到 stat、字段缺失）。没有校验依据的
 * 缓存等于把「文件被换了」变成永久的错觉，所以宁可不缓存：不缓存最多慢一点，缓存错了
 * 是界面在说假话。
 *
 * ## 线程安全
 *
 * 这里不加锁，保持成一个纯数据结构（测试不必管并发）。互斥由调用方
 * （[SubtitleRepository]）那一把 `Mutex` 负责。
 */
internal class ParsedSubtitleCache(private val maxEntries: Int = MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "缓存上限必须为正，否则每一次放进都立刻被淘汰" }
    }

    /**
     * 缓存键。
     *
     * 用 `data class` 而不是拼字符串：uri 里出现 `#` 之类分隔符时，拼串会让
     * 两条不同的键撞成一个（`a#1` + 体积 `23` 与 `a` + 体积 `1#23`），
     * 这种碰撞只在个别文件名上出现，排查起来毫无线索。
     */
    private data class Key(val uri: String, val sizeBytes: Long)

    /** `accessOrder = true`：读一次就挪到队尾，淘汰最久没用过的。 */
    private val entries = object : LinkedHashMap<Key, SubtitleDocument>(
        INITIAL_CAPACITY,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, SubtitleDocument>,
        ): Boolean = size > maxEntries
    }

    /** 命中要求「同 uri **且**同体积」，否则当作没有缓存。 */
    fun get(source: SubtitleSource): SubtitleDocument? = keyOf(source)?.let { entries[it] }

    fun put(source: SubtitleSource, document: SubtitleDocument) {
        val key = keyOf(source) ?: return

        // 同一个 uri 只留**当前体积**那一份。体积进了键，旧键不会自己消失；
        // 而文件被写回旧体积（重新生成又变回句数相同的一版）时，那个旧键
        // 会把人送回更早的解析结果——体积撞对了、内容早已不同。
        entries.keys.filter { it.uri == key.uri && it != key }.forEach { entries.remove(it) }
        entries[key] = document
    }

    /** 只对「体积可测」的来源缓存，见类注释最后一段。 */
    private fun keyOf(source: SubtitleSource): Key? =
        source.sizeBytes.takeIf { it > 0L }?.let { Key(source.uri, it) }

    internal companion object {
        /**
         * 单条媒体的候选字幕通常只有几个，上限存在的意义是「切走再切回来别重新解析」，
         * 不是当数据库用。
         */
        const val MAX_ENTRIES = 6

        const val INITIAL_CAPACITY = 8
    }
}
