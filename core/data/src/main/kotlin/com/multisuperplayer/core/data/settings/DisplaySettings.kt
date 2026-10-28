package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private const val TAG = "DisplaySettings"

/**
 * 列表外观相关的偏好。
 *
 * 为什么单独一个类，而不是塞进 [ThemeSettingsRepository]：那一类装的全是
 * **配色**（主题基底、强调色、两个取色开关），而这里装的是「文字怎么显示」。
 * 两者唯一的共同点是都归「外观设置」那一页管——页面归属不是合并的理由，
 * 上一次把「记录最近播放」「屏幕常亮」放进播放设置就已经说明白了：
 * 分组按**语义**分，不按界面分。将来再加「列表密度」「显示文件后缀」都落在这一份里。
 *
 * 字段全是可空的：`null` = 用户没设置过，**默认值住在 UI 层**
 * （见 `TitleScrollRules.DEFAULT_ENABLED`）。这样改默认值只影响没设置过的人，
 * 而不是把所有人已经做出的选择一起翻掉。
 */
data class DisplaySettings(
    /**
     * 长标题（文件名）要不要滚动显示。
     *
     * 只有「当前播放/选中」的那一条会滚，其余仍是单行省略——规则在
     * `TitleScrollRules` 里，这里只负责存用户的选择。
     */
    val scrollLongTitles: Boolean? = null,
    /**
     * 被用户点名「这一页所有长文件名都滚」的**页面 id**。
     *
     * `null` = 从没设过（一页都没点名）；空集 = 设过但把最后一个也取消了。
     * 两者**行为完全相同**，保留区别是因为「设置过」本身将来还有用，
     * 而把它归一成一种又要一次迁移。
     *
     * 存的是**字符串 id 而不是枚举**：`core:data` 不认识界面上的页面概念，
     * 也不该认识（这里连 `core:ui` 都没依赖）。id 与 `TitleScrollPage` 的对应关系
     * 由 UI 层用 `TitleScrollPage.known(...)` 解析，**认不出来的 id 直接丢掉**——
     * 降级安装后仓里会留着新版写下的 id，那时宁可少滚一页也不能让设置页崩掉。
     */
    val alwaysScrollPages: Set<String>? = null,
)

/**
 * 列表外观偏好的读写。
 *
 * 与其余设置仓库共用 `msp_settings` 这一个文件——声明在 [mspSettingsStore] 里，
 * 那个文件同时说明了为什么这里只能有一份。
 */
class DisplaySettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败只吞 [IOException]（读不出来就当「没设置过」= 全用 UI 层默认值），
     * 其他异常照旧抛出，否则真正的 bug 会被伪装成「用户的设置莫名其妙没了」。
     */
    val settings: Flow<DisplaySettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取列表显示偏好失败，回退到默认显示" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toDisplaySettings() }

    /**
     * 开关长标题滚动。
     *
     * 只写自己的键：它和主题、播放设置没有任何需要一起改的东西，
     * 所以不需要共用事务（对比 [ThemeSettingsRepository.selectAccent] 那三连写）。
     */
    suspend fun setScrollLongTitles(enabled: Boolean) = edit { it[Keys.SCROLL_LONG_TITLES] = enabled }

    /**
     * 打开/关闭某一页的「全量滚动」。
     *
     * 读-改-写**必须在同一个 `edit` 事务里完成**：界面上用户可能连着点两页，
     * 若两次都在事务外先读一份旧集合、算完再写回，后写的那次会把前一次吞掉
     * （而现象只是「我明明勾了它却不滚」，不会有任何报错）。`store.edit` 的块
     * 拿到的是当下的磁盘状态，天然串行。
     *
     * 取消最后一个页面时写入**空集**而不是把键删掉：「空集」与「缺键」行为相同，
     * 但后者会把「用户设置过」这件事一并抹掉。
     */
    suspend fun setAlwaysScrollPage(pageId: String, enabled: Boolean) = edit { prefs ->
        val current = prefs[Keys.ALWAYS_SCROLL_PAGES].orEmpty()
        prefs[Keys.ALWAYS_SCROLL_PAGES] = if (enabled) current + pageId else current - pageId
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的这项设置清空**（旧键读不出来，
     * 会静静地回退到默认值）。所以它们不是实现细节，单测里按字面量锁住。
     */
    internal object Keys {
        val SCROLL_LONG_TITLES = booleanPreferencesKey("display.scroll_long_titles")

        /**
         * 逐页「全量滚动」的页面 id 集合。
         *
         * 元素是 `TitleScrollPage.id` 的字面量（`"library"` 这种）。新增页面只需
         * 往集合里多一个 id，**不需要新键**，也不需要迁移。
         */
        val ALWAYS_SCROLL_PAGES = stringSetPreferencesKey("display.always_scroll_pages")
    }
}

/**
 * 把存储层的数据结构映射成领域模型。
 *
 * 抽成顶层函数是为了可测：[DisplaySettingsRepository] 的构造要一个 `Context`
 * 和一个真的 DataStore 文件，测不了；而「缺键时是 null 还是 false」恰恰是
 * 最容易写错、又最难在界面上看出来的一处（误把缺键映射成 false，等于让
 * 所有老用户升级后这项功能默认关闭）。
 */
internal fun Preferences.toDisplaySettings(): DisplaySettings = DisplaySettings(
    // 缺键 ⇒ null ⇒ UI 层默认（开）。**不能**写 `?: false`。
    scrollLongTitles = this[DisplaySettingsRepository.Keys.SCROLL_LONG_TITLES],
    // 同样不能写 `?: emptySet()`：「一页都没点名」与「用户把勾全取消了」在界面上
    // 是两件事（后者要能看见自己取消过）。而 `isEmpty()` 两种情况下都是 true，
    // 所以判定那边不受影响。
    alwaysScrollPages = this[DisplaySettingsRepository.Keys.ALWAYS_SCROLL_PAGES]?.toSet(),
)
