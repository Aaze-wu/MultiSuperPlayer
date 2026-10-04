package com.multisuperplayer.core.data.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "RemoteUrlStore"

/**
 * 网络地址的历史，文件 `msp_remote`。
 *
 * ## 为什么单独一个文件
 *
 * `msp_settings` 立过的规矩是「设置文件的数量由用户可见的设置分组决定」。这份历史
 * 确实算用户设置，但它有两条和设置文件不一样的性子（和 `msp_saf` 的授权清单同源）：
 *
 * 1. **键名是变长的地址**，而设置文件是固定几十个键；
 * 2. **条数由我们裁剪**（[RemoteUrlRules.HISTORY_LIMIT]）。把它塞进设置文件意味着
 *    每次加一条地址都要把整份设置重写一遍，而设置里装着主题、字幕、翻译配置——
 *    那些东西一次写坏的代价远大于「多一个文件」。
 *
 * ## 一个键一条地址
 *
 * 键名 `url.<地址>`，值是加入时间（毫秒，`String`）。用地址当键让「重复添加同一地址」
 * 天然幂等——只需要把时间戳刷新一下，那条就回到了最前面，不必先查有没有。
 *
 * ## 为什么值不是 JSON
 *
 * `org.json` 在 `android.jar` 里只有空壳，`unitTests.isReturnDefaultValues = true`
 * 会让它**静默返回 null**，也就是说用 JSON 存的东西在单测里永远解析不出来
 * （`FieldTextCodec` 的注释里记着这个坑）。这里只需要一个数字，直接存字符串最省事。
 */
internal val Context.mspRemoteStore: DataStore<Preferences> by preferencesDataStore(name = "msp_remote")

/** 见 [mspRemoteStore]。 */
internal const val REMOTE_URL_KEY_PREFIX = "url."

/**
 * 网络地址历史的读写。
 *
 * `public` 的理由和 `SafTreeStore` 一样：`feature:library` 的网络地址页把它当构造参数接进去。
 */
class RemoteUrlStore(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspRemoteStore

    /** 最近的地址，最新的在前。 */
    val history: Flow<List<String>> = store.data.map { prefs ->
        RemoteUrlRules.arrangeHistory(decodeAll(prefs.asMap()))
    }

    /**
     * 记下一条地址（已有的挪到最前面）。
     *
     * 裁剪和写入在同一次 `edit` 里完成：分成两次写的话，中间那一刻存储里可能
     * 同时有「新地址」和「本该被裁掉的旧地址」，而界面正好可能在这一刻读到它。
     */
    suspend fun add(url: String) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        write("记下网络地址失败：$url") {
            store.edit { prefs ->
                val known = decodeAll(prefs.asMap())
                // 先算出「留下谁」再动 map：`asMap()` 在 edit 里是**可变引用**，
                // 边删边遍历会漏项。
                val keep = (listOf(url) + RemoteUrlRules.arrangeHistory(known))
                    .distinct()
                    .take(RemoteUrlRules.HISTORY_LIMIT)
                    .toSet()
                known.forEach { (knownUrl, _) -> if (knownUrl !in keep) prefs.remove(urlKey(knownUrl)) }
                prefs[urlKey(url)] = now.toString()
            }
        }
    }

    suspend fun remove(url: String) {
        write("移除网络地址失败：$url") {
            store.edit { prefs -> prefs.remove(urlKey(url)) }
        }
    }

    /**
     * 落盘，失败只记日志。
     *
     * 不往上抛是故意的：调用方是 `viewModelScope.launch { store.add(url) }`，
     * 抛出去就是一个**未捕获异常**——应用会在「用户播了一条网络地址」这一步崩掉。
     * 而这里写坏的只是一个「最近用过」的便利列表：地址已经拿去播了，
     * 少留一条历史记录远远不到该让应用退出的程度。要查的话日志里有原因。
     */
    private suspend fun write(what: String, block: suspend () -> Unit) {
        val result = runCatching { withContext(dispatchers.io) { block() } }
        result.exceptionOrNull()?.let { error -> MspLog.w(TAG, error) { what } }
    }

    /**
     * 读出 `地址 → 加入时间`。
     *
     * 时间戳读不出来时用 `0L`（排到最后）而不是丢掉这条：地址是用户输进来的、
     * 时间是我们顺手记的簿记，簿记坏掉不该让用户的地址消失。
     */
    private fun decodeAll(map: Map<Preferences.Key<*>, Any>): List<Pair<String, Long>> =
        map.mapNotNull { (key, value) ->
            if (!key.name.startsWith(REMOTE_URL_KEY_PREFIX)) return@mapNotNull null
            val url = key.name.substring(REMOTE_URL_KEY_PREFIX.length).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            url to ((value as? String)?.toLongOrNull() ?: 0L)
        }

    private fun urlKey(url: String): Preferences.Key<String> =
        stringPreferencesKey(REMOTE_URL_KEY_PREFIX + url)
}
