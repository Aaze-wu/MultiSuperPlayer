package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "HintSettings"

/**
 * 可以被用户关掉的**说明类**提示。
 *
 * 这里刻意只列「怎么用」的提示，不含任何**警告**（授权失效、文件不见了、
 * 内容被截断）。警告说的是「现在有一件事不对」，关掉之后用户就再也看不到
 * 唯一的线索；说明说的是「这个功能可以这么用」，看过一遍就够了。
 *
 * 值会写进磁盘，所以**改名等于让用户已经关掉的提示重新冒出来**。
 */
object HintIds {

    /** 浏览页顶部的「这里列的是可以开始浏览的位置」。 */
    const val BROWSE_SOURCES = "browse_sources"

    /** 播放列表页的「长按条目可拖动排序」。 */
    const val PLAYLIST_DRAG = "playlist_drag"

    /** 播放队列面板底部的「按住右侧把手上下拖动」。 */
    const val PLAYER_QUEUE_DRAG = "player_queue_drag"

    /** 全部已知的提示。既用于校验写入，也用于设置页统计「关掉了几条」。 */
    val all: Set<String> = setOf(BROWSE_SOURCES, PLAYLIST_DRAG, PLAYER_QUEUE_DRAG)
}

/**
 * 记住「哪些说明类提示被用户关掉了」。
 *
 * 和 `ThemeSettingsRepository` 同一套写法（同一个 `msp_settings` 文件、
 * 同样的读盘失败兜底），因为它们共享一个 DataStore：第二个
 * `preferencesDataStore(name = "msp_settings")` 会在运行时报
 * 「multiple DataStores active for the same file」。
 *
 * 用 `stringSet` 而不是每个提示一个布尔键：新增提示时不用改存储结构，
 * 关掉/恢复也只是往集合里加减一个 id。
 */
class HintSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 已关闭的提示 id 集合。
     *
     * 读盘失败时返回空集（= 什么都没关过）：宁可多显示一条提示，也不要
     * 因为一次 IO 错误让所有说明永久消失——后者用户完全没法自己恢复。
     */
    val dismissed: Flow<Set<String>> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取提示设置失败，按「没有关过提示」处理" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { it.dismissedHints() }

    /** 关掉一条提示。未知 id 会被忽略（防止拼错后写进一份永远对不上的数据）。 */
    suspend fun dismiss(id: String) = edit { it.dismissHint(id) }

    /** 恢复全部提示（设置页的「恢复所有提示」）。 */
    suspend fun restoreAll() = edit { it.restoreHints() }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) {
            store.edit(block)
        }
    }
}

/**
 * 存储键。
 *
 * 改键名等于清空用户已经关掉的提示，所以它带版本前缀。
 */
internal object HintKeys {
    val Dismissed = stringSetPreferencesKey("hints.dismissed")
}

/** 纯函数，便于单测：从偏好里读出已关闭的集合（缺键 = 空集）。 */
internal fun Preferences.dismissedHints(): Set<String> =
    this[HintKeys.Dismissed].orEmpty()

/** 纯函数，便于单测：把一个 id 加进已关闭集合。未知 id 不写入。 */
internal fun MutablePreferences.dismissHint(id: String) {
    if (id !in HintIds.all) return
    this[HintKeys.Dismissed] = this[HintKeys.Dismissed].orEmpty() + id
}

/** 纯函数，便于单测：恢复全部提示（直接删键，不留空集合）。 */
internal fun MutablePreferences.restoreHints() {
    remove(HintKeys.Dismissed)
}
