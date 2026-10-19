package com.multisuperplayer.feature.library

import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.data.favorites.Favorite
import com.multisuperplayer.core.data.favorites.FavoriteRules
import com.multisuperplayer.core.data.favorites.FavoriteStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 「把这个文件 / 这个目录收藏起来，或者取消收藏」。
 *
 * 媒体库页、最近播放页、浏览页三处都要做同一件事，而且**必须答得一致**的有三件事：
 *
 * 1. **一键到底是收藏还是取消** —— 判断依据是「选中的这些是不是**全部**已经收藏」
 *    （`FavoriteRules.allFavorited`）：全收藏了就整体取消，否则把还没收藏的加进去。
 *    如果按「有没有任意一条已收藏」来切，多选里混着两种状态时按钮会变成「取消收藏」，
 *    而用户点下去期待的是「把这些收藏起来」。
 * 2. **到底加进去了几条** —— 去重和上限都在存储层（`FavoriteRules.withAdded`），
 *    所以只有写完才知道，调用方照着 `added < requested` 报「跳过几项」。
 * 3. **满了怎么办** —— 满的时候存储层静默丢弃（不抛异常、不半途写入），
 *    所以这里要能说出「已达上限」，否则用户看到的是「已收藏 0 项」。
 *
 * 抄第二份的代价不是多几十行，而是这三条以后只会在一处被修——`PlaylistEditing`
 * 当初就是为同样的理由抽出来的。
 *
 * 类是 `internal`：它只服务于本模块的 ViewModel，而 ViewModel 的构造参数仍然是
 * 各自的依赖（`FavoriteStore`），不是「一个内部助手类」——Koin 的 `viewModelOf`
 * 解析的是那些公开依赖。
 */
internal class FavoriteEditing(private val store: FavoriteStore) {

    /** 全部收藏（新的在前）。界面用它判断按钮该显示「收藏」还是「取消收藏」。 */
    val favorites: Flow<List<Favorite>> get() = store.favorites

    /**
     * 切换一批文件的收藏状态，返回要说给用户的那句话（空清单返回 `null`，
     * 调用方不必为「什么都没选」编一句话）。
     *
     * 读一次当前快照再决定方向：`favorites.first()` 拿到的是 DataStore 里的现值，
     * 而界面手上那份 `StateFlow` 可能落后一帧（刚收藏完立刻再点）。以磁盘为准，
     * 连点两次的结果就是「收藏 → 取消」，而不是两次都「收藏」。
     */
    suspend fun toggleFiles(entries: List<MediaEntry>): MspText? {
        if (entries.isEmpty()) return null
        val current = store.favorites.first()
        return if (FavoriteRules.allFavorited(current, entries)) {
            favoriteRemovedText(store.removeFiles(entries))
        } else {
            favoriteAddedText(store.addFiles(entries), entries.size)
        }
    }

    /** 切换当前浏览位置的收藏状态，返回要说给用户的那句话。 */
    suspend fun toggleFolder(trail: BrowserTrail): MspText? {
        val current = store.favorites.first()
        val ref = trail.current.ref
        return if (FavoriteRules.containsFolder(current, trail.kind, ref)) {
            val removed = store.removeFolder(trail)
            if (removed) {
                MspText.Res(R.string.msp_library_favorites_folder_removed)
            } else {
                favoriteRemovedText(0)
            }
        } else {
            val added = store.addFolder(trail)
            if (added) {
                MspText.Res(R.string.msp_library_favorites_folder_added)
            } else {
                favoriteFullText()
            }
        }
    }
}

/**
 * 「已收藏 N 项」/「已收藏 N 项，另有 M 项已在收藏里或已达上限」。
 *
 * 只说前半句的话，用户对同一批条目点两次会以为真的收藏了两遍；而「跳过」的
 * 两种原因（已收藏 / 已满）在这一层分不出来——存储层只回报数量。
 * 所以文案把两种可能都写出来，而不是猜一个。
 */
internal fun favoriteAddedText(added: Int, requested: Int): MspText {
    val skipped = requested - added
    return if (skipped > 0) {
        MspText.Res(R.string.msp_library_favorites_added_skipped, added, skipped)
    } else {
        MspText.Res(R.string.msp_library_favorites_added, added)
    }
}

/** 「已取消收藏 N 项」。 */
internal fun favoriteRemovedText(removed: Int): MspText =
    MspText.Res(R.string.msp_library_favorites_removed, removed)

/** 「收藏已满（最多 N 项）」。 */
internal fun favoriteFullText(): MspText =
    MspText.Res(R.string.msp_library_favorites_full, FavoriteRules.MAX_FAVORITES)
