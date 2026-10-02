package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "PlaybackSettings"

/**
 * 播放偏好的读写。
 *
 * 与 [ThemeSettingsRepository] / [SubtitleSettingsRepository] 共用 `msp_settings`
 * 这一个文件——声明在 [mspSettingsStore] 里，那个文件同时说明了为什么这里只能有一份
 * （再写一个 `preferencesDataStore(name = "msp_settings")` 会在启动几秒后抛
 * 「There are multiple DataStores active for the same file」，而且编译期完全看不出来）。
 */
class PlaybackSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败不能让界面崩掉，也不能卡在「永远没有值」。
     *
     * 只吞 [IOException]（磁盘读不出来，比如刚恢复出厂），回退到「全空」，
     * 于是所有字段都是 null = 走内核自己的默认值。其它异常照旧抛出：
     * 那些是自己代码写错（比如键类型不对），静默吞掉只会让 bug 更难找。
     */
    val settings: Flow<PlaybackSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取播放设置失败，回退到默认值" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toPlaybackSettings() }

    suspend fun setForceSoftwareDecoding(enabled: Boolean) = edit {
        it[Keys.FORCE_SOFTWARE_DECODING] = enabled
    }

    suspend fun setAspectRatioMode(mode: AspectRatioMode) = edit {
        it[Keys.ASPECT_RATIO_MODE] = mode.id
    }

    suspend fun setSpeed(speed: Float) = edit {
        it[Keys.SPEED] = speed
    }

    suspend fun setRememberPosition(enabled: Boolean) = edit {
        it[Keys.REMEMBER_POSITION] = enabled
    }

    suspend fun setBoostSpeed(speed: Float) = edit {
        it[Keys.BOOST_SPEED] = speed
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    internal object Keys {
        /**
         * ⚠️ 这是写在**用户设备上的契约**：改名等于把所有用户的这一项设置清空。
         *
         * 前缀 `playback.` 是分组，不是命名空间，不需要和类名一致——
         * 但**需要和已有键不重名**，所以单测里按字面量锁住了它。
         */
        val FORCE_SOFTWARE_DECODING = booleanPreferencesKey("playback.force_software_decoding")

        val ASPECT_RATIO_MODE = stringPreferencesKey("playback.aspect_ratio_mode")

        val SPEED = floatPreferencesKey("playback.speed")

        val REMEMBER_POSITION = booleanPreferencesKey("playback.remember_position")

        /** 长按画面时的临时倍速（见 [PlaybackSettings.boostSpeed]）。 */
        val BOOST_SPEED = floatPreferencesKey("playback.boost_speed")
    }
}

/**
 * 从「一堆键值」还原成一个设置对象。
 *
 * 刻意做成顶层函数而不是 `PlaybackSettingsRepository` 的私有方法：
 * 仓库需要一个 `Context` + 一个真实的 DataStore 文件才能构造，在 JVM 单测里
 * 起不来；而这个映射才是真正会写错的那部分（键名写错、默认值写反、类型不对）。
 */
internal fun Preferences.toPlaybackSettings(): PlaybackSettings = PlaybackSettings(
    forceSoftwareDecoding = this[PlaybackSettingsRepository.Keys.FORCE_SOFTWARE_DECODING],
    // 认不出来的字符串会落到 [AspectRatioMode.fromId] 里的 FIT，也就是默认值；
    // 「键不存在」保持 null。两者对消费者等价，但 null 保留了「没设置过」这个信息。
    aspectRatioMode = this[PlaybackSettingsRepository.Keys.ASPECT_RATIO_MODE]
        ?.let(AspectRatioMode::fromId),
    speed = this[PlaybackSettingsRepository.Keys.SPEED],
    rememberPosition = this[PlaybackSettingsRepository.Keys.REMEMBER_POSITION],
    boostSpeed = this[PlaybackSettingsRepository.Keys.BOOST_SPEED],
)
