package com.multisuperplayer.core.data.favorites

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "FavoriteStore"

/**
 * 收藏存的 DataStore 文件。
 *
 * 单独一个文件（`msp_favorites`），不和 `msp_playlists` 合并：两者都是「一次写几 KB」，
 * 合在一起意味着收藏一次要把所有播放列表重写一遍，反过来也一样。分开之后
 * 任何一边坏了都不会连累另一边——收藏读不出来时播放列表仍然好的。
 */
internal val Context.mspFavoriteStore: DataStore<Preferences> by preferencesDataStore(name = "msp_favorites")

/**
 * 收藏的持久化。
 *
 * ## 为什么所有收藏放在**一个**键里
 *
 * 收藏列表天然是「全都要」：收藏页显示全部，各个页面的按钮只需要知道
 * 「这一条在不在里面」。拆成「一条一个键」的话，判断「有没有收藏」就得把
 * 所有键读出来拼一次，而写入时也没有任何好处（用户不会只改其中一条的顺序）。
 *
 * 代价是一次写入 = 整个列表重新编码。上限 [FavoriteRules.MAX_FAVORITES] 是 200，
 * 一次最多几十 KB，而且用户不会一秒点十次收藏。
 *
 * ## 公开性的理由
 *
 * 它被 [com.multisuperplayer.core.data.di.dataModule] 注册成单例、由界面层的
 * ViewModel 直接注入，所以类型必须是 public。
 *
 * ## 返回值
 *
 * 加入 / 移除都返回**实际改动了几条**，而不是 Unit 或 Boolean：
 * 界面要说「已收藏 3 项」「2 项已在上限之外没加进去」，而这个数字只有这里知道
 * （去重和上限都在 [FavoriteRules] 里）。返回布尔的话，调用方还得再自己数一遍，
 * 而它数的那份列表和真正写进去的那份不是同一个快照。
 */
class FavoriteStore(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspFavoriteStore

    /**
     * 全部收藏，**新的在前**。
     *
     * 读不出来的记录被跳过（见 [FavoriteCodec.decode]），而不是让整个 Flow 抛异常。
     * 一条坏记录不该让整页收藏消失。
     */
    val favorites: Flow<List<Favorite>> = store.data.map { prefs ->
        FavoriteCodec.decode(prefs[itemsKey])
    }

    /** 加入一个文件。返回 `true` 表示真的加进去了（已存在 / 已达上限都是 `false`）。 */
    suspend fun addFile(entry: MediaEntry): Boolean = addFiles(listOf(entry)) > 0

    /**
     * 批量加入文件，返回**实际加入**了几条。
     *
     * 顺序：传入的清单按原顺序逐条插到最前面，所以显示顺序是**清单倒序**。
     * 用户看到的是「刚收藏的那批排在最上面」，而不是「这批里最后点的那一条
     * 排在下面」——后者会让人以为第一条失败了。
     */
    suspend fun addFiles(entries: List<MediaEntry>): Int {
        if (entries.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val incoming = entries.map { FavoriteRules.fileOf(it, now) }
        val result = update { current -> FavoriteRules.withAddedAll(current, incoming) }
        return result.added
    }

    /** 收藏一个文件夹（当前浏览位置）。返回 `true` 表示真的加进去了。 */
    suspend fun addFolder(trail: BrowserTrail): Boolean {
        val favorite = FavoriteRules.folderOf(
            trail = trail,
            addedAtMs = System.currentTimeMillis(),
            resolve = ::resolveText,
        )
        return update { current -> FavoriteRules.withAdded(current, favorite) }.added > 0
    }

    /** 移除一条（按 [Favorite.key]）。 */
    suspend fun remove(key: String) {
        update { current -> FavoriteRules.withRemoved(current, setOf(key)) }
    }

    /** 批量移除，返回实际移除了几条。 */
    suspend fun removeAll(keys: Collection<String>): Int {
        if (keys.isEmpty()) return 0
        return update { current -> FavoriteRules.withRemoved(current, keys.toSet()) }.removed
    }

    /** 移除若干个文件（媒体库/浏览页多选里的「取消收藏」）。返回实际移除了几条。 */
    suspend fun removeFiles(entries: List<MediaEntry>): Int =
        removeAll(FavoriteRules.fileKeysOf(entries))

    /** 移除一个文件夹（浏览页顶栏的“取消收藏当前目录”）。 */
    suspend fun removeFolder(trail: BrowserTrail): Boolean {
        val key = FavoriteRules.folderKey(trail.kind, trail.current.ref)
        return removeAll(listOf(key)) > 0
    }

    /**
     * 解析 [MspText] 成当前语言的文本。
     *
     * `getString(id, *args)` 在参数为空时会抛异常（Android 的老坑），
     * 所以按参数是否为空分成两支，而不是一律展开 `args.toTypedArray()`。
     * 收藏的面包屑大多是 `Plain`，但来源根那一段确实是 `Res`（「内部存储」）。
     */
    private fun resolveText(text: MspText): String {
        val resources = appContext.resources
        return when (text) {
            is MspText.Plain -> text.text
            is MspText.Res -> if (text.args.isEmpty()) {
                resources.getString(text.id)
            } else {
                resources.getString(text.id, *text.args.toTypedArray())
            }
        }
    }

    /**
     * 读改写。只吞 [IOException]（文件坏了）：收藏失败是可接受的，
     * 「点一下收藏就崩」不是。其它异常照旧抛——那说明代码写错了。
     */
    private suspend fun update(transform: (List<Favorite>) -> List<Favorite>): Change =
        withContext(dispatchers.io) {
            var change = Change(added = 0, removed = 0)
            try {
                store.edit { prefs ->
                    val before = FavoriteCodec.decode(prefs[itemsKey])
                    val after = transform(before)
                    change = Change(
                        added = (after.size - before.size).coerceAtLeast(0),
                        removed = (before.size - after.size).coerceAtLeast(0),
                    )
                    // 同一个实例 = 什么都没变（已存在 / 已满 / 没有匹配的键），
                    // 那就不写。写一次会触发整条 Flow，界面白闪一下。
                    if (after !== before) prefs[itemsKey] = FavoriteCodec.encode(after)
                }
            } catch (error: IOException) {
                MspLog.w(TAG, error) { "更新收藏失败" }
            }
            change
        }

    private val itemsKey: Preferences.Key<String> = stringPreferencesKey(FAVORITES_KEY)

    private data class Change(val added: Int, val removed: Int)

    private companion object {
        const val FAVORITES_KEY = "items"
    }
}
