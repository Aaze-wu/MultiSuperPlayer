package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "DesktopLyricsSettings"

/**
 * 桌面歌词偏好的读写。
 *
 * 与其它设置仓库共用 `msp_settings` 这一个文件——见 [mspSettingsStore] 的说明，
 * 这里**绝不能**再写一遍 `preferencesDataStore`。
 *
 * ## 谁在写它
 *
 * 两个入口都会写：设置页的「播放设置」和播放页的「桌面歌词」面板。两者写的是
 * 同一份偏好，所以都走这个仓库、都通过 DataStore 的 Flow 观察结果——不要在
 * 一处直接改内存状态，那会让另一处看不到变化。
 */
class DesktopLyricsSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败只吞 [IOException]（文件被删、磁盘读不出来）并回退到默认值。
     * 其他异常照旧抛出——否则真正的 bug 会伪装成「我的设置莫名其妙没了」。
     */
    val settings: Flow<DesktopLyricsSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取桌面歌词偏好失败，回退到默认值" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toDesktopLyricsSettings() }

    suspend fun setEnabled(enabled: Boolean) = edit { prefs ->
        prefs[Keys.ENABLED] = enabled
    }

    suspend fun setFontSize(size: DesktopLyricsFontSize) = edit { prefs ->
        prefs[Keys.FONT_SIZE] = size.name
    }

    suspend fun setOpacity(opacity: DesktopLyricsOpacity) = edit { prefs ->
        prefs[Keys.OPACITY] = opacity.name
    }

    suspend fun setLocked(locked: Boolean) = edit { prefs ->
        prefs[Keys.LOCKED] = locked
    }

    /**
     * 记下浮窗位置。
     *
     * 只在**松手**时写（拖动过程中不写盘）：一次拖动会经过几百个位置，
     * 每个都写一次 DataStore 等于把用户的手势变成几百次磁盘事务。
     */
    suspend fun setPosition(position: DesktopLyricsPosition) = edit { prefs ->
        val x = position.x
        val y = position.y
        if (x == null || y == null) {
            prefs.remove(Keys.POSITION_X)
            prefs.remove(Keys.POSITION_Y)
        } else {
            prefs[Keys.POSITION_X] = x
            prefs[Keys.POSITION_Y] = y
        }
    }

    /** 丢掉摆过的位置，下次用默认位置（水平居中、屏幕偏下）。 */
    suspend fun resetPosition() = setPosition(DesktopLyricsPosition.UNSET)

    /**
     * 恢复默认外观（字号 + 背景不透明度）。
     *
     * 两个键**一次事务**写完，理由同 `SubtitleSettingsRepository.resetStyle`：
     * 分开写的话，中间失败会留下「一半默认一半自定义」的外观，而用户看到的只是
     * 「恢复默认之后好像没恢复干净」。
     */
    suspend fun resetAppearance() = edit { prefs ->
        prefs[Keys.FONT_SIZE] = DesktopLyricsFontSize.DEFAULT.name
        prefs[Keys.OPACITY] = DesktopLyricsOpacity.DEFAULT.name
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的桌面歌词设置清空**。所以按
     * 字面量在测试里锁住。
     *
     * 前缀用 `desktop_lyrics.` 而不是挂在 `subtitle.` 下面：这两组设置虽然相关，
     * 但一个是「画面里的字幕」、一个是「另一个窗口」，将来清理其中一组时，
     * 前缀就是那条分界线。
     */
    internal object Keys {
        val ENABLED = booleanPreferencesKey("desktop_lyrics.enabled")

        val FONT_SIZE = stringPreferencesKey("desktop_lyrics.font_size")

        val OPACITY = stringPreferencesKey("desktop_lyrics.opacity")

        val LOCKED = booleanPreferencesKey("desktop_lyrics.locked")

        val POSITION_X = intPreferencesKey("desktop_lyrics.position_x")

        val POSITION_Y = intPreferencesKey("desktop_lyrics.position_y")
    }
}

/**
 * 存储结构 → 领域模型。
 *
 * 抽成顶层函数是为了可测：仓库的构造需要 `Context` 和真实的 DataStore 文件，
 * 而「键名是什么、缺键怎么办、存了垃圾字符串怎么办、坐标只有一个怎么办」
 * 恰恰是最容易写错的地方。
 */
internal fun Preferences.toDesktopLyricsSettings(): DesktopLyricsSettings {
    val x = this[DesktopLyricsSettingsRepository.Keys.POSITION_X]
    val y = this[DesktopLyricsSettingsRepository.Keys.POSITION_Y]
    return DesktopLyricsSettings(
        enabled = this[DesktopLyricsSettingsRepository.Keys.ENABLED] ?: false,
        style = DesktopLyricsStyle(
            fontSize = DesktopLyricsFontSize.fromKey(
                this[DesktopLyricsSettingsRepository.Keys.FONT_SIZE],
            ),
            opacity = DesktopLyricsOpacity.fromKey(
                this[DesktopLyricsSettingsRepository.Keys.OPACITY],
            ),
        ),
        locked = this[DesktopLyricsSettingsRepository.Keys.LOCKED] ?: false,
        // 两个坐标都在才算「摆过」：只有一个时（旧版本写过、或者写盘被打断）
        // 按「没摆过」处理，否则会用一个缺了一半的位置去开窗。
        position = if (x != null && y != null) {
            DesktopLyricsPosition(x, y)
        } else {
            DesktopLyricsPosition.UNSET
        },
    )
}
