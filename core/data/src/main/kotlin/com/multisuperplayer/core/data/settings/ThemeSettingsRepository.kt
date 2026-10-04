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

    /**
     * 选中一个强调色。
     *
     * 这个动作同时会把两个「取色」开关关掉，因为它们的优先级都在强调色之上
     * ——只要它们还开着，用户点强调色就等于什么都没发生。
     *
     * 注意这与「打开封面取色」是**不对称**的，并且是故意的：
     * 打开封面取色只是「我想试试封面取色」，不该顺手改掉用户之前选的强调色
     * （所以那个开关仍然只写自己的键）；而点一个具体的强调色是一个明确的
     * 「我要这个颜色」，此时上面盖着它的东西必须让位。
     *
     * 三处写入必须在**同一个** edit 事务里：分开写会让 Flow 先吐出一个
     * 「强调色已改但系统取色还开着」的中间态，那一帧的配色仍然是被盖住的旧色。
     */
    suspend fun selectAccent(id: String) = edit { it.applyAccentSelection(id) }

    /**
     * 选中一个自定义强调色（用户在滑块上松手时调用）。
     *
     * 与 [selectAccent] 完全对称：一个具体的颜色被选中时，盖在它上面的两个
     * 取色开关必须让位，否则用户拖了滑块却发现颜色没变。
     *
     * 它**不会**动 [ThemeSettings.accentId]：那是「如果关掉自定义色，回到哪一个
     * 预设」，属于另一个问题。[clearCustomAccent] 正是靠它还留着才能回到原位。
     */
    suspend fun selectCustomAccent(accent: CustomAccent) =
        edit { it.applyCustomAccentSelection(accent) }

    /**
     * 「回到预设强调色」：只删掉自定义色这一个键，两个取色开关**不动**。
     *
     * 用户此刻看到的是自定义色，也就是说两个取色开关本来就已经是关的
     * （是 [selectCustomAccent] 关掉的），所以这里没有需要一并处理的状态；
     * 而如果他去关掉自定义色之前先手动打开了封面取色，那个开关是他的明确选择，
     * 不该被这个动作顺手翻掉。
     */
    suspend fun clearCustomAccent() = edit { it.remove(Keys.CUSTOM_ACCENT) }

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
        val CUSTOM_ACCENT = stringPreferencesKey("theme.custom_accent")
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
    // 解不出来当「没设置过」。这里**不能**用 `takeIf { it.isNotBlank() }` 那种写法：
    // 自定义色的载体是「三个数」，空串与半截数据对一个滑块来说同样是坏数据，
    // 该处理的只有「能不能解析成三个数」这一件事（见 CustomAccentCodec）。
    customAccent = CustomAccentCodec.decode(this[ThemeSettingsRepository.Keys.CUSTOM_ACCENT]),
)

/**
 * 「选中强调色」对存储的全部影响，抽成纯函数是为了能测。
 *
 * [ThemeSettingsRepository] 的构造要一个 `Context` 和一个真的 DataStore 文件，
 * 而这里要锁的恰恰是「一共写了哪几个键」——这件事写错了只会表现为
 * 「点了强调色没反应」，单看代码是看不出来的。
 *
 * 见 [ThemeSettingsRepository.selectAccent] 里「为什么要把两个取色开关一并关掉」。
 */
internal fun MutablePreferences.applyAccentSelection(id: String) {
    this[ThemeSettingsRepository.Keys.ACCENT] = id
    this[ThemeSettingsRepository.Keys.DYNAMIC_COLOR] = false
    this[ThemeSettingsRepository.Keys.COLOR_FROM_ARTWORK] = false
    // 选中一个预设 = 明确表示「不用自定义色了」。**必须真的删掉这个键**，
    // 只在 UI 层「忽略它」是不行的：MspTheme 的优先级是「自定义 > 预设」，
    // 留着一个自定义色意味着用户点了预设却什么都没变，而那正是最难查的
    // 「点了没反应」。
    remove(ThemeSettingsRepository.Keys.CUSTOM_ACCENT)
}

/**
 * 「选中自定义强调色」对存储的全部影响，与 [applyAccentSelection] 一一对应。
 *
 * 两个取色开关的处置与选预设完全一致，理由也一样：只要它们还开着，用户拖完滑块
 * 松手之后颜色不会变。
 *
 * 三处写入在同一次调用里完成（`edit {}` 本身就是一个事务），不会出现
 * 「自定义色已存、封面取色还开着」的中间帧。
 */
internal fun MutablePreferences.applyCustomAccentSelection(accent: CustomAccent) {
    this[ThemeSettingsRepository.Keys.CUSTOM_ACCENT] = CustomAccentCodec.encode(accent)
    this[ThemeSettingsRepository.Keys.DYNAMIC_COLOR] = false
    this[ThemeSettingsRepository.Keys.COLOR_FROM_ARTWORK] = false
}
