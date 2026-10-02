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

    override suspend fun write(mediaId: String, positionMs: Long) = withContext(dispatchers.io) {
        val entry = ResumeEntry(positionMs = positionMs, savedAtMs = System.currentTimeMillis())
        store.edit { prefs ->
            prefs[resumeKey(mediaId)] = ResumeCodec.encode(entry)
            evictOverflow(prefs)
        }
        Unit
    }

    override suspend fun clear(mediaId: String) = withContext(dispatchers.io) {
        store.edit { prefs -> prefs.remove(resumeKey(mediaId)) }
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
        val decoded = LinkedHashMap<String, ResumeEntry>()
        val unreadable = mutableListOf<Preferences.Key<*>>()

        for ((key, value) in prefs.asMap()) {
            if (!key.name.startsWith(RESUME_KEY_PREFIX)) continue
            val mediaId = key.name.substring(RESUME_KEY_PREFIX.length)
            val entry = (value as? String)?.let(ResumeCodec::decode)
            if (mediaId.isEmpty() || entry == null) unreadable += key else decoded[mediaId] = entry
        }

        unreadable.forEach { key -> prefs.remove(key) }
        ResumeEviction.expired(decoded).forEach { mediaId -> prefs.remove(resumeKey(mediaId)) }
    }

    private fun resumeKey(mediaId: String): Preferences.Key<String> =
        stringPreferencesKey(RESUME_KEY_PREFIX + mediaId)
}
