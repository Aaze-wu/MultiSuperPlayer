package com.multisuperplayer.core.data.history

/**
 * 一条续播记录。
 *
 * 除了位置还存了**保存时间**：淘汰要按「最近还在看的」排序，没时间戳就只能靠
 * 键的字典序或者插入顺序，而两者都和历史实际使用无关。
 *
 * 时间用 `System.currentTimeMillis()` 的绝对时刻，不用 `elapsedRealtime()`：
 * 后者在设备重启后归零，跨重启比较会得出「所有旧记录都是未来」这种结论。
 */
internal data class ResumeEntry(
    val positionMs: Long,
    val savedAtMs: Long,
)

/**
 * 续播记录的编解码。
 *
 * ## 为什么是一条 `"位置:时间"` 而不是 JSON
 *
 * 值只有一个用途：`位置 + 保存时间`。用 JSON 需要手写 `JsonObject`（`:core:data`
 * 没装 kotlin-serialization 编译器插件，全项目的约定就是手写 Json——见
 * `core:translate` 的 `TranslationJson`），为了两个数字引入一整棵 `JsonElement`
 * 树、还要处理解析失败的各种形状，得不偿失。
 *
 * 但这个格式必须**自己解析、自己兜底**：DataStore 里的值是可以被任何东西改坏的
 * （降级安装、外部工具、磁盘损坏），坏值必须变成 null 而不是抛
 * `NumberFormatException`——那个异常会发生在「用户点开一个视频」的路径上，
 * 表现是应用闪退。
 */
internal object ResumeCodec {

    private const val SEPARATOR = ':'

    fun encode(entry: ResumeEntry): String = "${entry.positionMs}$SEPARATOR${entry.savedAtMs}"

    /**
     * 解不出来一律返回 null（当「没有记录」处理），不抛异常。
     *
     * 用 [String.indexOf] 找第一个分隔符而不是 `split(':')`：后者在值里出现多个
     * 冒号时会返回 3 个以上的片段，而 `split(...)[1]` 在一个空串上会直接抛
     * `IndexOutOfBoundsException`——正是上面说的那种「闪退于用户点开视频时」。
     */
    fun decode(raw: String?): ResumeEntry? {
        if (raw == null) return null
        val separator = raw.indexOf(SEPARATOR)
        if (separator <= 0 || separator == raw.lastIndex) return null

        val position = raw.substring(0, separator).toLongOrNull() ?: return null
        val savedAt = raw.substring(separator + 1).toLongOrNull() ?: return null
        // 负位置没有任何意义（播到 -5 秒）。放它进去会让 seekTo 收到一个负值，
        // 内核会夹到 0，于是「续播」变成「每次从头开始」而没有任何提示。
        if (position < 0L) return null
        return ResumeEntry(positionMs = position, savedAtMs = savedAt)
    }
}

/**
 * 淘汰规则。
 *
 * 续播记录是**按媒体条目无界增长**的：每看一部片子多一条。不淘汰的话，
 * 一个把播放器当日常工具用几年的设备，这个文件会一直涨，而每次写入都要重写
 * 整个文件——于是「播放位置保存」从 O(1) 慢慢变成 O(用户看过的所有片子)。
 *
 * 淘汰的依据是**保存时间**而不是「媒体 id 的大小」或者「插入顺序」：
 * 「最近还在看的」才是用户会回头用的那些，正好和「最近保存的」重合。
 */
internal object ResumeEviction {

    /**
     * 最多留多少条。
     *
     * 200 的依据：一条记录在文件里约占 60 字节（键名 + 值 + protobuf 开销），
     * 200 条约 12KB——重写一次是可以忽略的开销，而 200 部片子已经远超
     * 「用户在这个播放器上还会回头续看的范围」。
     */
    const val MAX_ENTRIES = 200

    /**
     * 算出该删哪些。
     *
     * 排序带有第二关键字（媒体 id），是为了让「同一毫秒保存的几条」有确定的
     * 顺序：不稳定排序会让淘汰结果随实现变化，而这是没法写单测的。
     *
     * @param entries 当前所有有效记录（媒体 id → 记录）。
     * @return 需要删掉的媒体 id；没超上限时是空表。
     */
    fun expired(entries: Map<String, ResumeEntry>, max: Int = MAX_ENTRIES): List<String> {
        if (max < 0) return entries.keys.toList()
        val overflow = entries.size - max
        if (overflow <= 0) return emptyList()
        return entries.entries
            .sortedWith(compareBy({ it.value.savedAtMs }, { it.key }))
            .take(overflow)
            .map { it.key }
    }
}
