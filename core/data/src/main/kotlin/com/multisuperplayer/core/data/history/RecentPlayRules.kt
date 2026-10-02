package com.multisuperplayer.core.data.history

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.player.PlaybackRecord

/**
 * 「续播记录 × 当前媒体库」→ 「最近播放列表」的纯投影规则。
 *
 * 单独拿出来是因为它**只在特定数据组合下才正确**，而每一种组合都要能单测：
 * 记录显示不出东西的时候（权限没给、文件被删、SAF 授权失效），
 * 界面上都表现为「最近播放是空的」——一个所有失败都长得一样的界面，
 * 只能靠这一层的单测来区分。
 */
internal object RecentPlayRules {

    /** 一页最多显示多少条。取 50：比这个数字更早的记录对用户已经不可见了。 */
    const val DEFAULT_LIMIT = 50

    /**
     * 投影。
     *
     * @param records 续播记录（顺序无所谓，这里会重排）。
     * @param entries **当前**媒体库里的条目，用来把 id 换成元数据。
     * @param limit 取前几条；**必须为正数**，0 或负数表示「一条都不要」而不是
     *   「不限」。把 0 解释成不限会让「空列表」和「全部」这两种相反的意图
     *   共用一个输入，而调用方很容易传进来一个没算出来的 0。
     */
    fun project(
        records: List<PlaybackRecord>,
        entries: List<MediaEntry>,
        limit: Int = DEFAULT_LIMIT,
    ): List<RecentPlay> {
        if (limit <= 0) return emptyList()
        if (records.isEmpty() || entries.isEmpty()) return emptyList()

        val byId = entries.associateBy(MediaEntry::id)
        return records
            .asSequence()
            // 位置为 0 的记录没有意义（写入方不应该写，但存储里可能留着了）。
            .filter { it.positionMs > 0L }
            // 查不到元数据的记录**跳过但不删**：现在查不到可能只是权限没给，
            // 用户把权限补上之后它应该自己回来。删掉就再也回不来了。
            .mapNotNull { record -> byId[record.mediaId]?.let { RecentPlay(it, record.positionMs, record.savedAtMs) } }
            .sortedWith(compareByDescending<RecentPlay> { it.playedAtMs }.thenBy { it.entry.id })
            .take(limit)
            .toList()
    }
}
