package com.multisuperplayer.core.data.history

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.player.PlaybackPositionStore
import com.multisuperplayer.core.player.PlaybackRecord
import kotlinx.coroutines.withContext

/**
 * 「最近播放」页的数据来源。
 *
 * ## 为什么是一次性的 suspend 而不是 Flow
 *
 * 数据的两个来源变化频率和触发方式完全不同：媒体库是 `Flow`（扫描/权限变化会推），
 * 续播记录是**每次写入都会变**（播放中每秒写好几次）的存储。把两者拼成一个 Flow
 * 就等于让「最近播放」页在播放时每秒重组一次列表。
 *
 * 而这一页的语义本来就是**快照**：用户进入这一页时看到「我最近听了什么」，
 * 不需要在停留期间实时刷新。所以这里是「进来读一次」，由界面在合适的时机
 * （首次进入、从后台回到前台、播放结束后）调用。
 *
 * 也没有用缓存：一次读全表 + 一次字典查找，比起「缓存什么时候失效」这件事
 * 便宜得多，而缓存失效写错的表现是「最近播放里有已经不存在的歌」。
 *
 * ## 删除和清空为什么是「删记录」而不是「从列表里拿掉一行」
 *
 * 这一页的每一行都是从一条续播记录投影出来的（[RecentPlayRules.project]），
 * 而那条记录同时就是「下次从这个位置接着播」的依据。所以这里的 [remove] /
 * [clearAll] 删的是**记录本身**，顺带也就把续播位置一起删了。这不是副作用
 * 而是唯一说得通的设计：用户要的是「我从来没在这台设备上听过它」，留着一个
 * 看不见的位置只会在下次播放时冒出来（从中间开始播，而列表里又没有它）。
 *
 * 代价是界面文案必须把这件事说出来（「续播位置也会一起忘掉」），
 * 不能让用户以为只是把一行从屏幕上抹掉。
 *
 * ## 为什么还多一个 [resolver]
 *
 * 投影只认「媒体库里的条目」，而浏览页（内置文件浏览器 / 系统文件选择器）
 * 放的文件刻意不进媒体库——它们在媒体库里永远查不到，记录写下来了却一条都
 * 显示不出来。所以投影前把「库里没有的 id」拿去 [RecentMediaResolver] 问一次
 * 「文件还在不在」，在的拼成临时条目参与投影。
 *
 * 临时条目**只活在这一页的内存里**：不写进媒体库、也不改写任何记录（见那个类的注释）。
 */
class RecentPlayRepository(
    private val positionStore: PlaybackPositionStore,
    private val library: MediaLibraryRepository,
    private val resolver: RecentMediaResolver,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * 读出最近播放列表。
     *
     * 媒体库还没扫完（[MediaLibraryState.Loading] / 没权限）时返回空列表——
     * 这一页此时本来就无事可做，等界面下一次刷新即可。
     */
    suspend fun recent(limit: Int = RecentPlayRules.DEFAULT_LIMIT): List<RecentPlay> {
        val entries = (library.state.value as? MediaLibraryState.Ready)?.entries ?: return emptyList()
        val records = positionStore.readAll()
        val resolved = resolveMissing(records, entries, limit)
        return RecentPlayRules.project(records, entries + resolved, limit)
    }

    /**
     * 把媒体库里查不到的那几条记录拿去「库外」找一次。
     *
     * 放在 IO 线程上一次做完：每一条都可能是一次 `stat` 或一次跨进程 `query`，
     * 主线程上几个文件就能抳出一帧。找不到就跳过——**不删记录**，
     * 文件回来了它应该自己重新出现。
     */
    private suspend fun resolveMissing(
        records: List<PlaybackRecord>,
        entries: List<MediaEntry>,
        limit: Int,
    ): List<MediaEntry> {
        val missing = RecentPlayRules.unresolvedIds(records, entries, limit)
        if (missing.isEmpty()) return emptyList()
        return withContext(dispatchers.io) {
            missing.mapNotNull { id ->
                runCatching { resolver.resolve(id) }
                    // 解析不了就是「现在读不到」：跳过这一条，不要让整页崩掉。
                    .getOrNull()
            }
        }
    }

    /**
     * 忘掉一个媒体的记录（位置、时间、「播过」一起）。
     *
     * 参数是整行而不是媒体 id：调用方（最近播放页）手上本来就有这一行，
     * 而写成 `row` 之后「删这一条」这个意思不用再解释一遍——传 id 的写法
     * 会让调用点出现 `delete(row.entry.id)` 这种看起来像是「删 entry」的表达式。
     */
    suspend fun remove(row: RecentPlay) = positionStore.remove(row.entry.id)

    /** 忘掉全部记录。连当前列表里没显示出来的那些也一起（见 [PlaybackPositionStore.clearAll]）。 */
    suspend fun clearAll() = positionStore.clearAll()

    /**
     * 把一行原样写回，位置和时间戳都不变——「删除」旁边那个「撤销」。
     *
     * 不能改用 [PlaybackPositionStore.write]（它会盖上新时间戳）：这一页按时间戳
     * 倒序排，撤销之后那一行就会跳到最上面。见 [RecentPlayRules.recordOf]。
     */
    suspend fun restore(row: RecentPlay) = positionStore.restore(RecentPlayRules.recordOf(row))
}
