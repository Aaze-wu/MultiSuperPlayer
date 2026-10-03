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

    suspend fun setTextSize(size: SubtitleTextSize) = edit { prefs ->
        prefs[Keys.TEXT_SIZE] = size.name
    }

    suspend fun setLineSpacing(spacing: SubtitleLineSpacing) = edit { prefs ->
        prefs[Keys.LINE_SPACING] = spacing.name
    }

    suspend fun setOutline(outline: SubtitleOutline) = edit { prefs ->
        prefs[Keys.OUTLINE] = outline.name
    }

    suspend fun setBottomMargin(margin: SubtitleBottomMargin) = edit { prefs ->
        prefs[Keys.BOTTOM_MARGIN] = margin.name
    }

    /**
     * 恢复出厂字幕样式。
     *
     * 四个键**一次事务**写完，而不是让界面连调四次上面那几个方法：连调四次的话，
     * 中间任何一次失败（磁盘满、进程被杀）都会留下一个「一半默认一半自定义」的样式，
     * 而用户看到的只是「恢复默认之后好像没恢复干净」——一个没人会去查的错误报告。
     */
    suspend fun resetStyle() = edit { prefs ->
        prefs[Keys.TEXT_SIZE] = SubtitleTextSize.DEFAULT.name
        prefs[Keys.LINE_SPACING] = SubtitleLineSpacing.DEFAULT.name
        prefs[Keys.OUTLINE] = SubtitleOutline.DEFAULT.name
        prefs[Keys.BOTTOM_MARGIN] = SubtitleBottomMargin.DEFAULT.name
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的字幕设置清空**（旧文件里的键
     * 读不出来，会静静地回退到默认模式）。所以按字面量在测试里锁住。
     *
     * 样式那四个键和 [DISPLAY_MODE] 一样存**枚举名**（不是序号）：存序号的话，
     * 将来往档位表中间插一个档位，所有人的字号都会跳一格——而屏幕上只是「字幕
     * 好像变大了」，没人会联想到版本升级。
     */
    internal object Keys {
        val DISPLAY_MODE = stringPreferencesKey("subtitle.display_mode")

        val TEXT_SIZE = stringPreferencesKey("subtitle.text_size")

        val LINE_SPACING = stringPreferencesKey("subtitle.line_spacing")

        val OUTLINE = stringPreferencesKey("subtitle.outline")

        val BOTTOM_MARGIN = stringPreferencesKey("subtitle.bottom_margin")
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
    style = SubtitleStyle(
        textSize = SubtitleTextSize.fromKey(this[SubtitleSettingsRepository.Keys.TEXT_SIZE]),
        lineSpacing = SubtitleLineSpacing.fromKey(this[SubtitleSettingsRepository.Keys.LINE_SPACING]),
        outline = SubtitleOutline.fromKey(this[SubtitleSettingsRepository.Keys.OUTLINE]),
        bottomMargin = SubtitleBottomMargin.fromKey(this[SubtitleSettingsRepository.Keys.BOTTOM_MARGIN]),
    ),
)
