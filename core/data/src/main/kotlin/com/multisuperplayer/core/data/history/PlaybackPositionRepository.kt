package com.multisuperplayer.core.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.player.PlaybackPositionStore
import com.multisuperplayer.core.player.PlaybackRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "ResumePosition"

/**
 * 续播位置的持久化实现。见 [PlaybackPositionStore] 的接口注释。
 *
 * 存在 `:core:data` 是因为它要做磁盘 IO、要一个真实的 DataStore 文件；接口和
 * 调用方（内核）都在 `:core:player`，两边由 DI 接起来。
 */
class PlaybackPositionRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) : PlaybackPositionStore {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspPositionsStore

    /**
     * 读不出来就按「没有记录」处理。
     *
     * 只吞 [IOException]（文件坏了、被外部清掉了），因为这条路径在「用户点开一个
     * 视频」上：任何异常穿透出去就是「点一下视频闪退」，而丢一个续播位置的代价
     * 只是从头播。其它异常照旧抛——那是自己代码写错，静默吞掉只会让 bug 更难找。
     */
    override suspend fun read(mediaId: String): Long? = withContext(dispatchers.io) {
        val prefs = try {
            store.data.first()
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "读取续播位置失败，按没有记录处理" }
            return@withContext null
        }
        ResumeCodec.decode(prefs[resumeKey(mediaId)])?.positionMs
    }

    override suspend fun readAll(): List<PlaybackRecord> = withContext(dispatchers.io) {
        val prefs = try {
            store.data.first()
        } catch (error: IOException) {
            // 同 read()：读不出来按「什么都没有」处理，不让它穿透到界面。
            MspLog.w(TAG, error) { "读取续播位置失败，按没有记录处理" }
            return@withContext emptyList()
        }
        decodeAll(prefs.asMap())
            .map { (mediaId, entry) -> PlaybackRecord(mediaId, entry.positionMs, entry.savedAtMs) }
            .sortedWith(compareByDescending<PlaybackRecord> { it.savedAtMs }.thenBy { it.mediaId })
    }

    override suspend fun write(mediaId: String, positionMs: Long) = withContext(dispatchers.io) {
        val entry = ResumeEntry(positionMs = positionMs, savedAtMs = System.currentTimeMillis())
        store.edit { prefs ->
            prefs[resumeKey(mediaId)] = ResumeCodec.encode(entry)
            evictOverflow(prefs)
        }
        Unit
    }

    override suspend fun markPlayed(mediaId: String) = withContext(dispatchers.io) {
        store.edit { prefs ->
            val key = resumeKey(mediaId)
            // 保住已有的位置（没有就按 0），只把时间戳推到现在。
            // 位置 0 是合法值：读出来是 0 -> 内核从头播，最近播放里照旧显示这一条。
            val position = ResumeCodec.decode(prefs[key])?.positionMs ?: 0L
            prefs[key] = ResumeCodec.encode(
                ResumeEntry(positionMs = position, savedAtMs = System.currentTimeMillis()),
            )
            evictOverflow(prefs)
        }
        Unit
    }

    override suspend fun resetPosition(mediaId: String) = withContext(dispatchers.io) {
        store.edit { prefs ->
            val key = resumeKey(mediaId)
            val existing = ResumeCodec.decode(prefs[key])
            // 没有记录就什么都不做——**不能**在这里补一条 0，否则「播完一个从没播过的
            // 短片」会凭空多出一条播放记录，把「记录最近播放」的开关架空。
            //
            // 时间戳也原样保留。这一条只在「记录最近播放」关掉时才会走到（开着时归零走
            // [write]），而那个开关关掉之后就不该再有任何东西记下「你什么时候看的它」——
            // 顺手把 savedAt 推到今天的话，「最近播放」的排序仍然会在后台偷偷变化。
            if (existing != null) {
                prefs[key] = ResumeCodec.encode(
                    ResumeEntry(positionMs = 0L, savedAtMs = existing.savedAtMs),
                )
                evictOverflow(prefs)
            }
        }
        Unit
    }

    /**
     * 把这个媒体整条忘掉（位置、时间、「播过」一起）。
     *
     * 不判存在性：`edit` 里删一个不存在的键是空操作，而「删一个已经不存在的
     * 记录」和「删一个存在的」在用户那里是同一件事（他要的是「它不在列表里了」）。
     * 这里**不能**顺手写一条 0 来代替删除——那是 [resetPosition] 的语义，
     * 在界面上会是「删了它却还在」。
     */
    override suspend fun remove(mediaId: String) = withContext(dispatchers.io) {
        store.edit { prefs -> prefs.remove(resumeKey(mediaId)) }
        Unit
    }

    /**
     * 清空全部续播记录。
     *
     * 删的是存储里**所有**以 `resume.` 开头的键，包括 [decodeAll] 读不出来、
     * 因而从来没显示给用户的那些脏值——详见接口注释。判定统一走 [ResumeKeys]，
     * 否则「列表里显示什么」和「清空删什么」会各算各的，出现的症状是
     * 「清空之后列表空了，下次拿到权限又冒出来一条」。
     */
    override suspend fun clearAll() = withContext(dispatchers.io) {
        store.edit { prefs ->
            prefs.asMap().keys
                .filter { key -> ResumeKeys.isResumeKey(key.name) }
                .forEach { key -> prefs.remove(key) }
        }
        Unit
    }

    /**
     * 原样写回一条记录（撤销删除用）。
     *
     * 刻意**不**让 [evictOverflow] 参与：它可能删掉一条和这次撤销无关的旧记录，
     * 那就成了「按撤销，结果别的一条消失了」。不淘汰在这里是安全的——这
     * 种记录刚刚才在表里存在过（它是因为删除才离开的），写回去最多把表恢复到
     * 删除前的大小，而那个大小本来就满足上限。
     */
    override suspend fun restore(record: PlaybackRecord) = withContext(dispatchers.io) {
        // 空 id 的键（`"resume."`）会被 [ResumeKeys.mediaIdOf] 当坏值丢掉，
        // 存进去只会变成一条谁都读不出来的脏记录。
        if (record.mediaId.isEmpty()) return@withContext
        store.edit { prefs ->
            prefs[resumeKey(record.mediaId)] = ResumeCodec.encode(
                ResumeEntry(positionMs = record.positionMs, savedAtMs = record.savedAtMs),
            )
        }
        Unit
    }

    /**
     * 顺手淘汰。
     *
     * 放在写入之后而不是单独一个后台任务：淘汰只可能由一个更大的写入触发，
     * 单独跑一个扫描任务反而要在「一个媒体正在播、位置每分钟更新 12 次」的
     * 时候反复遍历整张表。
     *
     * 同时清掉**读不出来的**记录——它们对 [read] 来说不存在，却会占着名额参与
     * 淘汰排序，而且永远不会自己消失。
     */
    private fun evictOverflow(prefs: MutablePreferences) {
        val decoded = decodeAll(prefs.asMap())
        ResumeEviction.expired(decoded).forEach { mediaId -> prefs.remove(resumeKey(mediaId)) }
        unreadableKeys(prefs).forEach { key -> prefs.remove(key) }
    }

    /**
     * 能读懂的记录，保持插入顺序。
     *
     * 读 [evictOverflow] 和 [readAll] 都从这里走：两边对「什么算一条记录」的
     * 判定必须**完全一致**，各写一遍的话「淘汰时认为读得出来、列给界面时又认为
     * 读不出来」这种分歧迟早会出现，而它只会表现成「最近播放里少了一条」。
     */
    private fun decodeAll(values: Map<Preferences.Key<*>, Any>): Map<String, ResumeEntry> {
        val decoded = LinkedHashMap<String, ResumeEntry>()
        for ((key, value) in values) {
            val mediaId = ResumeKeys.mediaIdOf(key.name) ?: continue
            val entry = (value as? String)?.let(ResumeCodec::decode) ?: continue
            decoded[mediaId] = entry
        }
        return decoded
    }

    /**
     * 读不出来的记录对应的键。
     *
     * 它们对 [read] / [readAll] 来说不存在，却会占着名额参与淘汰排序，
     * 而且永远不会自己消失——只能在这里顺手清掉。
     */
    private fun unreadableKeys(prefs: MutablePreferences): List<Preferences.Key<*>> {
        val known = decodeAll(prefs.asMap()).keys
        return prefs.asMap().keys.filter { key ->
            val mediaId = ResumeKeys.mediaIdOf(key.name)
            // 空 id（`resume.`）也算脏值：`known` 里不可能有它。
            mediaId == null || mediaId !in known
        }
    }

    private fun resumeKey(mediaId: String): Preferences.Key<String> =
        stringPreferencesKey(RESUME_KEY_PREFIX + mediaId)
}
