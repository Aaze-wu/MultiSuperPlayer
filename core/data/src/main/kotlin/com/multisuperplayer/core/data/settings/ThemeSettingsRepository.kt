package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private const val TAG = "ThemeSettings"

/**
 * 主题偏好的读写。
 *
 * 与 [SubtitleSettingsRepository] 共用 `msp_settings` 这一个文件——
 * 声明在 [mspSettingsStore] 里，那个文件同时说明了为什么这里只能有一份。
 *
 * 写入不走内存缓存：DataStore 自己就是「写盘成功才更新 Flow」的语义，
 * 多加一层缓存反而会出现「界面显示了但没落盘」。
 */
class ThemeSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败**不能**让整个界面崩掉，也不能卡在「永远没有值」。
     *
     * 只吞 [IOException]（磁盘读不出来，比如刚恢复出厂/文件被删）并回退到「全空」
     * ——也就是全部用 UI 层默认值。其他异常照旧抛出，否则真正的 bug 会被伪装成
     * 「用户的设置莫名其妙没了」。
     */
    val settings: Flow<ThemeSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取主题偏好失败，回退到默认主题" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toThemeSettings() }

    suspend fun setBaseTheme(id: String) = edit { it[Keys.BASE_THEME] = id }

    suspend fun setAccent(id: String) = edit { it[Keys.ACCENT] = id }

    suspend fun setUseDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setColorFromArtwork(enabled: Boolean) = edit { it[Keys.COLOR_FROM_ARTWORK] = enabled }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的设置清空**（旧文件里的键
     * 读不出来，会静静地回退到默认主题，用户会觉得「升级之后主题变了」）。
     * 所以它们不是实现细节，单测里按字面量锁住。
     */
    internal object Keys {
        val BASE_THEME = stringPreferencesKey("theme.base")
        val ACCENT = stringPreferencesKey("theme.accent")
        val DYNAMIC_COLOR = booleanPreferencesKey("theme.dynamic_color")
        val COLOR_FROM_ARTWORK = booleanPreferencesKey("theme.color_from_artwork")
    }
}

/**
 * 把存储层的数据结构映射成领域模型。
 *
 * 单独抽成顶层函数是为了可测：[ThemeSettingsRepository] 的构造要一个 `Context`
 * 和一个真的 DataStore 文件，测不了；而映射本身（哪三个键、缺键怎么办
 * 、空串怎么办）恰恰是最容易写错的地方。
 */
internal fun Preferences.toThemeSettings(): ThemeSettings = ThemeSettings(
    // 空串当「未设置」：DataStore 里存进过空串的话，让它落到 UI 层默认值，
    // 而不是拿去查一个必然查不到的 id。
    baseThemeId = this[ThemeSettingsRepository.Keys.BASE_THEME]?.takeIf { it.isNotBlank() },
    accentId = this[ThemeSettingsRepository.Keys.ACCENT]?.takeIf { it.isNotBlank() },
    useDynamicColor = this[ThemeSettingsRepository.Keys.DYNAMIC_COLOR],
    colorFromArtwork = this[ThemeSettingsRepository.Keys.COLOR_FROM_ARTWORK],
)
