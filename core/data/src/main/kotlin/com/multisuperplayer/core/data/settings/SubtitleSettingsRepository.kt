package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "SubtitleSettings"

/**
 * 字幕偏好的读写。
 *
 * 与 [ThemeSettingsRepository] 共用 `msp_settings` 这一个文件——见
 * [mspSettingsStore] 的说明，这里**绝不能**再写一遍 `preferencesDataStore`。
 */
class SubtitleSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败只吞 [IOException]（文件被删、磁盘读不出来）并回退到默认值。
     * 其他异常照旧抛出——否则真正的 bug 会伪装成「我的设置莫名其妙没了」。
     */
    val settings: Flow<SubtitleSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取字幕偏好失败，回退到默认显示模式" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toSubtitleSettings() }

    suspend fun setDisplayMode(mode: SubtitleDisplayMode) = edit { prefs ->
        prefs[Keys.DISPLAY_MODE] = mode.name
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的字幕设置清空**（旧文件里的键
     * 读不出来，会静静地回退到默认模式）。所以按字面量在测试里锁住。
     */
    internal object Keys {
        val DISPLAY_MODE = stringPreferencesKey("subtitle.display_mode")
    }
}

/**
 * 存储结构 → 领域模型。
 *
 * 抽成顶层函数是为了可测：仓库的构造需要 `Context` 和真实的 DataStore 文件，
 * 而「键名是什么、缺键怎么办、存了垃圾字符串怎么办」恰恰是最容易写错的地方。
 */
internal fun Preferences.toSubtitleSettings(): SubtitleSettings = SubtitleSettings(
    displayMode = SubtitleDisplayMode.fromKey(
        this[SubtitleSettingsRepository.Keys.DISPLAY_MODE],
    ),
)
