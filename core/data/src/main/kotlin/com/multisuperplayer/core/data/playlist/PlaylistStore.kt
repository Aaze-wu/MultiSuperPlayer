package com.multisuperplayer.core.data.playlist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private const val TAG = "PlaylistStore"

/**
 * 播放列表存的 DataStore 文件。和 `msp_settings` / `msp_positions` 分开的理由：
 * 它既不是用户设置也不是高频小写入，写一次就是几 KB 的自由文本，
 * 混进任何一个现有文件都会让那个文件的写入变慢。
 */
internal val Context.mspPlaylistStore: DataStore<Preferences> by preferencesDataStore(name = "msp_playlists")

/** 一个播放列表一个键，键名 = [PLAYLIST_KEY_PREFIX] + 播放列表 id。 */
internal const val PLAYLIST_KEY_PREFIX = "list."

/**
 * 播放列表**本身**的顺序。
 *
 * 值是一个用逗号连起来的 id 序列（「先哪个、后哪个」）。
 *
 * ## 为什么单独一个键，而不是给每条播放列表加个「第几位」
 *
 * 顺序是**所有条目之间**的关系，不是某一条自己的属性。给每条存一个序号意味着
 * 每次拖动都要改一大片记录（或者还要处理「两条序号撞了」），而且新建、删除时
 * 也得跟着重排——漏掉一处不报错，只是顺序悄悄变。存成一个 id 序列则永远只有
 * 一个写入点（用户拖动），读取时按 [PlaylistRules.applyOrder] 合并，
 * 对不上（删了的 / 新加的）都是无害的。
 *
 * 键名**不带** [PLAYLIST_KEY_PREFIX]，所以不会被 `PlaylistStore.decodeAll`
 * 当成一条播放列表读进来。
 */
private const val ORDER_KEY = "order"

/**
 * 播放列表的持久化。
 *
 * ## 一次读全表
 *
 * 播放列表天然是「全都要」的使用方式：列表页要显示全部（含条目数），
 * 详情页要某一条。数量上限 [PlaylistRules.MAX_PLAYLISTS] 是 100，
 * 一次读全表比维护增量索引简单得多，而且不会有「索引和内容不一致」这种状态。
 *
 * ## 公开性的理由
 *
 * 这个类被 [com.multisuperplayer.core.data.di.dataModule] 注册成单例、
 * 由界面层的 ViewModel 直接注入，所以类型必须是 public。
 */
class PlaylistStore(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspPlaylistStore

    /**
     * 全部播放列表，按用户排的顺序。
     *
     * 顺序有两层：
     * - 用户拖动过 → 按那份拖动结果（`order` 键里的 id 序列）；
     * - 没拖过（或后来新建的）→ 退化成**创建时间从早到晚**，也就是拖动之前的
     *   行为。按创建顺序而不是名字：用户自己建的东西位置应该稳定，
     *   按名字排序的话重命名会让它在列表里跳位置。
     *
     * 读不出来的记录会被**跳过**（见 [PlaylistCodec.decode]），
     * 而不是让整个 Flow 抛异常。
     */
    val playlists: Flow<List<Playlist>> = store.data.map { prefs -> decodeAll(prefs) }

    /** 创建一个播放列表，返回它的 id。名字按 [PlaylistRules.sanitizeName] 清洗。 */
    suspend fun create(name: String): String = withContext(dispatchers.io) {
        val playlist = Playlist(
            id = newId(),
            name = PlaylistRules.sanitizeName(name),
            createdAtMs = System.currentTimeMillis(),
            items = emptyList(),
        )
        store.edit { prefs ->
            val existing = decodeAll(prefs)
            if (existing.size >= PlaylistRules.MAX_PLAYLISTS) {
                MspLog.w(TAG) { "播放列表数量已达上限 ${PlaylistRules.MAX_PLAYLISTS}，不再新建" }
                return@edit
            }
            prefs[playlistKey(playlist.id)] = PlaylistCodec.encode(playlist)
        }
        MspLog.d(TAG) { "新建播放列表：${playlist.name}" }
        playlist.id
    }

    suspend fun rename(id: String, name: String) = withContext(dispatchers.io) {
        update(id) { it.copy(name = PlaylistRules.sanitizeName(name)) }
    }

    suspend fun remove(id: String) = withContext(dispatchers.io) {
        store.edit { prefs -> prefs.remove(playlistKey(id)) }
        MspLog.d(TAG) { "删除播放列表 $id" }
    }

    /**
     * 加入条目，返回**实际加入了几条**。
     *
     * 返回数量而不是 Unit：调用方要告诉用户「3 个已存在，跳过了」，
     * 而这个信息只有这里知道（去重在 [PlaylistRules.withAdded] 里做）。
     */
    suspend fun addItems(id: String, entries: List<MediaEntry>): Int = withContext(dispatchers.io) {
        if (entries.isEmpty()) return@withContext 0
        var added = 0
        update(id) { playlist ->
            val items = PlaylistRules.withAdded(playlist.items, entries.map { PlaylistItem.of(it) })
            added = items.size - playlist.items.size
            playlist.copy(items = items)
        }
        added
    }

    suspend fun removeItems(id: String, mediaIds: Collection<String>) = withContext(dispatchers.io) {
        if (mediaIds.isEmpty()) return@withContext
        update(id) { playlist -> playlist.copy(items = PlaylistRules.withRemoved(playlist.items, mediaIds)) }
    }

    /** 拖动排序。越界时什么都不做（见 [PlaylistRules.move]）。 */
    suspend fun moveItem(id: String, from: Int, to: Int) = withContext(dispatchers.io) {
        update(id) { playlist -> playlist.copy(items = PlaylistRules.move(playlist.items, from, to)) }
    }

    /**
     * 拖动播放列表**本身**排序。
     *
     * 落盘的是**整个 id 序列**，而不是「这一条的新位置」：顺序描述的是所有条目之间
     * 的相对关系，只改其中一条的位置等于说「其余的都往后挪」，而那部分信息不在
     * 任何单独一条记录里。写一个几十字节的字符串，比给 100 条播放列表各加一个
     * 「第几位」字段（还得保证它们不冲突）便宜得多。
     *
     * 越界或原地不动时什么都不写（[PlaylistRules.move] 会原样返回同一个实例）。
     */
    suspend fun move(from: Int, to: Int) = withContext(dispatchers.io) {
        try {
            store.edit { prefs ->
                val ids = decodeAll(prefs).map { it.id }
                val moved = PlaylistRules.move(ids, from, to)
                if (moved === ids) return@edit
                prefs[orderKey] = PlaylistRules.encodeOrder(moved)
            }
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "更新播放列表顺序失败" }
        }
    }

    /** 读一条（不订阅）。找不到返回 null。 */
    suspend fun playlist(id: String): Playlist? = withContext(dispatchers.io) {
        PlaylistCodec.decode(prefsSnapshot()[playlistKey(id)])
    }

    // -------------------------------------------------------------- 内部

    /**
     * 读改写。
     *
     * 只吞 [java.io.IOException]（文件坏了）：这是用户主动点的操作，
     * 异常直接抛出去会让「加入播放列表」这一个动作把整个界面打崩，
     * 而失败的代价只是没加进去。其它异常照旧抛——那是自己代码写错。
     */
    private suspend fun update(id: String, transform: (Playlist) -> Playlist) {
        try {
            store.edit { prefs ->
                val current = PlaylistCodec.decode(prefs[playlistKey(id)]) ?: return@edit
                prefs[playlistKey(id)] = PlaylistCodec.encode(transform(current))
            }
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "更新播放列表 $id 失败" }
        }
    }

    private suspend fun prefsSnapshot(): Preferences = try {
        store.data.first()
    } catch (error: IOException) {
        MspLog.w(TAG, error) { "读取播放列表失败，按没有处理" }
        emptyPreferences()
    }

    private fun decodeAll(prefs: Preferences): List<Playlist> {
        val playlists = prefs.asMap()
            .filterKeys { it.name.startsWith(PLAYLIST_KEY_PREFIX) }
            .values
            .mapNotNull { value -> PlaylistCodec.decode(value as? String) }
        return PlaylistRules.applyOrder(playlists, PlaylistRules.decodeOrder(prefs[orderKey]))
    }

    private fun playlistKey(id: String): Preferences.Key<String> = stringPreferencesKey(PLAYLIST_KEY_PREFIX + id)

    private val orderKey: Preferences.Key<String> = stringPreferencesKey(ORDER_KEY)

    /**
     * 播放列表 id。
     *
     * 不能只用时间戳：连续点两下「新建」可能落在同一毫秒里，那样第二个会**覆盖**
     * 第一个（键相同），而界面上只是「新建了但没出现」。加一段随机后缀把这种情况
     * 的概率压到不需要考虑。
     */
    private fun newId(): String = "pl-" + UUID.randomUUID().toString().replace("-", "").take(12)
}
