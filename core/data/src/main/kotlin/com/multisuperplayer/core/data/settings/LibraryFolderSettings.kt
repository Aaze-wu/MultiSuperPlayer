package com.multisuperplayer.core.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.LibraryFolderRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "LibraryFolderSettings"

/**
 * 「按文件夹过滤」在这台设备上到底能不能用。
 *
 * 依据是 `MediaStore.RELATIVE_PATH` 这一列——它**从 API 29（Android 10）才有**，
 * 而本应用的 minSdk 是 26。26-28 上 `MediaStoreScanner` 读不到这一列 ⇒ 每一条
 * `MediaEntry.relativePath` 都是 `null` ⇒ [LibraryFolderRules.accepts] 把它们全部
 * 排除 ⇒ 一旦勾了文件夹，媒体库就**永远是空的**，而用户手上唯一的自救办法
 * （全都不勾）也只会让它继续空着。
 *
 * 所以低于 29 时干脆**不过滤**（见 [LibraryFolderSettings.supported]），并在设置页
 * 把原因写出来。「什么都不显示且不解释」比「不生效但说清楚」坏得多。
 *
 * 单独抽成一个纯函数只为可测：JVM 单测里 `Build.VERSION.SDK_INT` 恒为 0
 * （`MediaStoreScanner` 里有同一处注释），判定留着 [Build.VERSION.SDK_INT] 直接读
 * 的话，这条边界根本没法用真实版本号去验。
 */
internal fun folderFilterSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

/**
 * 「媒体库只显示哪些文件夹」这条偏好。
 *
 * 字段不多，但**四态**，而且这几态的区分是这个功能能不能用的关键：
 * - [folders] 为 `null` ⇒ 用户从没设置过 ⇒ 按默认「都不显示」处理；
 * - [folders] 为空集 ⇒ 用户把勾全取消了 ⇒ 同样都不显示（两个状态行为相同，
 *   保留区别是因为「设置过」将来还有用，而归一又要一次迁移）；
 * - [unknown] ⇒ **偏好当前不可知**（还没从磁盘读出来，或读失败了），这时绝不能
 *   当成「用户什么都没选」——那会因为一次磁盘错误把整个媒体库清空，而用户看不出
 *   发生了什么。这种情况一律**不过滤**（宁可多显示，不能莫名其妙少显示）；
 * - [supported] 为 false ⇒ **这台设备上这个功能没法生效**（见 [folderFilterSupported]）。
 *   与 [unknown] 分开是因为两者要做的事完全不同：[unknown] 是异常、值得记日志、
 *   但愿它自己好；[supported] 是平台的硬事实，永远不会变，界面必须明确说出来。
 *   效果一样——**不过滤**。
 *
 * 元素是「相对卷根的文件夹路径」，不带结尾斜杠（`""` = 卷根，`"Music"`、
 * `"Music/Live"` = 子文件夹），规范化与判定全在 [LibraryFolderRules] 里。
 * 存字符串而不是 documentId，是因为 MediaStore 的条目根本没有 documentId，
 * 而相对路径是 MediaStore 与 SAF 两侧都能算出来的**唯一**共同语言。
 */
data class LibraryFolderSettings(
    val folders: Set<String>? = null,
    val unknown: Boolean = false,
    val supported: Boolean = true,
) {
    /**
     * 要不要用 [folders] 去过滤条目。
     *
     * 偏好不可知（[unknown]）或系统不支持（[supported]）时都不过滤。
     * 这两个条件写在这**一个**属性里，而不是让调用方各判一次：媒体库的过滤、
     * 界面上的空状态提示都以它为准，散开就迟早有一处忘了判。
     */
    val filtering: Boolean get() = !unknown && supported

    /**
     * 这条条目该不该进媒体库。
     *
     * 判定本身交给 [LibraryFolderRules.accepts]（那里解释了空集、卷根、
     * 以及「算不出相对路径」的条目为什么一律排除）；这里只负责不可知 / 不支持时的例外。
     */
    fun accepts(relativePath: String?): Boolean =
        if (!filtering) true else LibraryFolderRules.accepts(relativePath, folders.orEmpty())

    /** 界面上「已选中」的路径集合。偏好不可知时为空（界面照实显示没勾上）。 */
    val selected: Set<String> get() = folders.orEmpty()
}

/**
 * 媒体库文件夹偏好的读写。
 *
 * 与其余设置仓库共用 `msp_settings` 这一个文件——声明在 [mspSettingsStore] 里，
 * 那个文件同时说明了为什么这里只能有一份。
 */
class LibraryFolderSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
    // 只在建仓库的时候能测到（单测里 `Build.VERSION.SDK_INT` 是 0），所以从构造
    // 参数进来，判定交给纯函数。生产代码不传，走默认值。
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败只吞 [IOException]，并且**不是**退化成「空集」
     * （见 [LibraryFolderSettings.unknown]）；其他异常照旧抛出。
     */
    val settings: Flow<LibraryFolderSettings> = store.data
        .map { prefs -> prefs.toLibraryFolderSettings(sdkInt) }
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取媒体库文件夹偏好失败，本次不过滤媒体库" }
                // supported 也要带上：读盘失败与系统不支持是两件事，别把前者顺手
                // 抹成后者（那会让设置页在 Android 10+ 上说「此版本不支持」）。
                emit(
                    LibraryFolderSettings(
                        unknown = true,
                        supported = folderFilterSupported(sdkInt),
                    ),
                )
            } else {
                throw error
            }
        }

    /**
     * 勾选/取消勾选一个文件夹。
     *
     * 读-改-写**必须在同一个 `edit` 事务里完成**（和 `DisplaySettingsRepository.
     * setAlwaysScrollPage` 同一个理由）：连点两行时，若在事务外先读旧集合再写回，
     * 后写的那次会吞掉前一次，而现象只是「我明明勾了它」，没有任何报错。
     *
     * 复杂的部分（勾父带子、取消被祖先覆盖的子文件夹时把祖先展开）全在
     * [LibraryFolderRules.toggle] 里，这里只是把当前值和文件夹清单交给它。
     *
     * @param allFolders 界面上存在的**全部**文件夹路径。展开祖先需要它，
     *   传空集合会让「取消勾选子文件夹」退化成「整支关掉」。
     */
    suspend fun toggleFolder(path: String, allFolders: Collection<String>) = edit { prefs ->
        val current = prefs[Keys.FOLDERS].orEmpty()
        prefs[Keys.FOLDERS] = LibraryFolderRules.toggle(current, path, allFolders)
    }

    /** 全选/全不选。[folders] 会先折成最小形式再落盘。 */
    suspend fun setFolders(folders: Set<String>) = edit { prefs ->
        prefs[Keys.FOLDERS] = LibraryFolderRules.minimize(folders)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的这项设置清空**
     * （旧键读不出来 ⇒ 回退到默认「都不显示」⇒ 媒体库突然空了）。
     * 所以它们不是实现细节，单测里按字面量锁住。
     */
    internal object Keys {
        val FOLDERS = stringSetPreferencesKey("library.folders")
    }
}

/**
 * 把存储层的数据结构映射成领域模型。
 *
 * 抽成顶层函数是为了可测：[LibraryFolderSettingsRepository] 的构造要一个 `Context`
 * 和一个真的 DataStore 文件，测不了；而「缺键时是 null 还是空集」恰恰是最容易写错、
 * 又最难在界面上看出来的一处。
 *
 * @param sdkInt 系统版本。生产代码不传；单测里必须传，因为 JVM 上
 *   `Build.VERSION.SDK_INT` 恒为 0（那会永远算成「不支持」）。
 */
internal fun Preferences.toLibraryFolderSettings(
    sdkInt: Int = Build.VERSION.SDK_INT,
): LibraryFolderSettings =
    LibraryFolderSettings(
        // 缺键 ⇒ null ⇒ 默认「都不显示」。**不能**写 `?: emptySet()`：
        // 那会让「没设置过」和「用户全取消了」再也分不开。
        folders = this[LibraryFolderSettingsRepository.Keys.FOLDERS]?.toSet(),
        supported = folderFilterSupported(sdkInt),
    )
