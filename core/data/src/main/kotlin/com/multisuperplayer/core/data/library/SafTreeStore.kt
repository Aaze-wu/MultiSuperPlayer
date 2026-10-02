package com.multisuperplayer.core.data.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.storage.FieldTextCodec
import com.multisuperplayer.core.model.SafTreeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "SafTreeStore"

/**
 * 已授权的 SAF 目录清单，文件 `msp_saf`。
 *
 * ## 为什么单独一个文件
 *
 * `msp_settings` 的注释里立的规矩是「设置文件的数量由用户可见的设置分组决定」。
 * 这份清单确实算用户设置，但它有两条和 [com.multisuperplayer.core.data.settings.mspSettingsStore]
 * 不一样的性子：
 *
 * 1. **它可能被系统在背后改掉**——授权会被收回，而清单不会自动知道。所以它的读取
 *    路径上必然要做「校验 + 清理」，那套逻辑不该混进设置文件（一次失败就是
 *    「主题、字幕、翻译配置全丢」）。
 * 2. **键名是变长的 uri**，而设置文件是固定几十个键。
 *
 * ## 一个键一棵树
 *
 * 键名 `tree.<树 uri>`，值 `加入时间|<展示名>`。用 uri 当键让「重复添加同一棵树」
 * 天然幂等（系统选择器允许用户把同一个目录授权两次），不需要额外的去重逻辑。
 */
internal val Context.mspSafStore: DataStore<Preferences> by preferencesDataStore(name = "msp_saf")

/** 见 [mspSafStore]。 */
internal const val SAF_TREE_KEY_PREFIX = "tree."

/** 一棵树的展示名解析规则（纯函数，可单测）。 */
internal object SafTreeLabelRules {

    /**
     * 从树 uri 的 documentId 里解出目录名。
     *
     * `DocumentsContract.getTreeDocumentId(uri)` 给的是 `primary:Music/Album`
     * 这种形式，取最后一段就是用户在选择器里看到的那一层目录名。
     *
     * 三种返回：
     * - 普通目录 → 最后一段（`Album`）；
     * - **卷根**（`primary:`，也就是用户授权了整块内置存储）→ `null`，
     *   因为这里没有「目录名」可言，`""` 和 `"primary:"` 都不适合显示给用户；
     * - 非 `ExternalStorageProvider`（云盘等）→ `null`，它们的 documentId
     *   形式完全自定义，硬解只会解出一串 id。
     */
    fun labelOf(treeDocumentId: String): String? {
        val separator = treeDocumentId.indexOf(':')
        val volume = if (separator < 0) "" else treeDocumentId.substring(0, separator)
        val path = if (separator < 0) treeDocumentId else treeDocumentId.substring(separator + 1)
        val trimmed = path.trimEnd('/')
        // `substringAfterLast` 的兜底值**必须是整串**：单测第一轮就抓到了这个——
        // `labelOf("primary:Music")` 返回 null，于是浏览页里所有一级目录
        // （最常用的那种）都显示成一串 uri。
        val last = trimmed.substringAfterLast('/', trimmed)
        if (last.isNotBlank()) return last
        // 卷根：`primary` 之外（SD 卡）的卷名本身就是有信息量的名字，用它；
        // `primary` 是内置存储，界面会换成「内置存储」，这里返回 null。
        return volume.takeIf { it.isNotBlank() && it != "primary" }
    }
}

/**
 * 授权清单的读写。
 *
 * `public` 的理由和 [SafTreeScanner] 一样：[MediaLibraryRepository] 把它当公开
 * 构造参数接进去。真正需要对外的是 [trees]（「浏览」页要列出来）。
 */
class SafTreeStore(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSafStore

    /** 已授权的树，按加入时间从早到晚。 */
    val trees: Flow<List<SafTreeInfo>> = store.data.map { prefs -> decodeAll(prefs.asMap()) }

    suspend fun add(treeUri: String, label: String?) = withContext(dispatchers.io) {
        store.edit { prefs ->
            prefs[treeKey(treeUri)] = FieldTextCodec.join(
                listOf(System.currentTimeMillis().toString(), label.orEmpty()),
            )
        }
        Unit
    }

    suspend fun remove(treeUri: String) = withContext(dispatchers.io) {
        store.edit { prefs -> prefs.remove(treeKey(treeUri)) }
        Unit
    }

    /**
     * 扫描前要的那份 uri 列表。
     *
     * 读失败返回 **null**，和「用户没有授权任何目录」（空清单）区分对待：
     * 前者意味着「这一次读不出来」，上游必须沿用上一次的扫描结果，否则一次
     * 瞬时 IO 失败就会把用户授权的目录从媒体库里**整批清掉**（在用户看来是
     * 「我的文件突然都不见了」）；后者才是真的没授权。
     */
    suspend fun currentTreeUris(): List<String>? = withContext(dispatchers.io) {
        val prefs = try {
            store.data.first()
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "读取 SAF 授权清单失败" }
            return@withContext null
        }
        decodeAll(prefs.asMap()).map { info -> info.uri }
    }

    private fun decodeAll(map: Map<Preferences.Key<*>, Any>): List<SafTreeInfo> =
        map.mapNotNull { (key, value) ->
            if (!key.name.startsWith(SAF_TREE_KEY_PREFIX)) return@mapNotNull null
            val uri = key.name.substring(SAF_TREE_KEY_PREFIX.length).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val fields = FieldTextCodec.split(raw)
            val addedAt = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val label = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
            SafTreeInfo(uri = uri, label = label, addedAtMs = addedAt)
        }.sortedWith(compareBy({ it.addedAtMs }, { it.uri }))

    private fun treeKey(treeUri: String): Preferences.Key<String> =
        stringPreferencesKey(SAF_TREE_KEY_PREFIX + treeUri)
}
