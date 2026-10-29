package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「媒体库文件夹」偏好：存储结构 → 领域模型，以及那条系统版本闸门。
 *
 * 测的是 [toLibraryFolderSettings] 与 [folderFilterSupported]，不是仓库本身
 * （仓库要一个真的 `Context` 和 DataStore 文件）。为什么不测仓库也不吃亏：
 * 这里覆盖的三件事全部**在界面上看不出来**，而错了的代价都很大——
 *
 * 1. 「没设置过」写成空集 ⇒ 与「用户全都不勾」再也分不开，将来做默认值就会
 *    把老用户的选择一起抹掉；
 * 2. 键名改了 ⇒ 不报错、不崩溃，只是所有人的选择静悄悄回到默认；
 * 3. Android 9 及以下没有 `RELATIVE_PATH` ⇒ 每条条目的相对路径都是 null，
 *    真按文件夹过滤的话媒体库**永远为空且无法自救**。所以那道闸门必须有用例钉住，
 *    而 `Build.VERSION.SDK_INT` 在 JVM 上恒为 0，只能靠注入的 sdkInt 去测边界。
 */
class LibraryFolderSettingsTest {

    /** Android 10，`RELATIVE_PATH` 从这个版本开始才有。 */
    private val api29 = 29

    /** Android 9，比 `RELATIVE_PATH` 早一个版本。 */
    private val api28 = 28

    // ------------------------------------------------------------ 系统版本闸门

    @Test
    fun `Android 10 及以后支持按文件夹过滤`() {
        assertTrue(folderFilterSupported(api29))
        assertTrue(folderFilterSupported(36))
    }

    @Test
    fun `Android 9 及以前不支持按文件夹过滤`() {
        assertFalse(folderFilterSupported(api28))
        // minSdk 是 26，26 与 27 同样得判成不支持。
        assertFalse(folderFilterSupported(26))
        // JVM 单测里 `Build.VERSION.SDK_INT` 就是 0，别让它碰巧跑成「支持」。
        assertFalse(folderFilterSupported(0))
    }

    // ------------------------------------------------------------ 缺键 = 没设置

    @Test
    fun `缺键时是 null 而不是空集`() {
        val settings = prefsOf(null).toLibraryFolderSettings(api29)

        assertNull(settings.folders)
        assertEquals(emptySet<String>(), settings.selected)
        // 逻辑上仍处于「过滤中」（空集 ⇒ 一条都不显示），只是还没人设置过。
        assertTrue(settings.filtering)
    }

    @Test
    fun `存过就原样读回来`() {
        val settings = prefsOf(setOf("Movies", "Music/Live")).toLibraryFolderSettings(api29)

        assertEquals(setOf("Movies", "Music/Live"), settings.folders)
        assertEquals(setOf("Movies", "Music/Live"), settings.selected)
    }

    @Test
    fun `空集与缺键行为相同但值不同`() {
        val settings = prefsOf(emptySet()).toLibraryFolderSettings(api29)

        // 值必须不同：「设置过、全都不勾」将来还要和「没设置过」区分开。
        assertEquals(emptySet<String>(), settings.folders)
        assertTrue(settings.filtering)
        // 行为相同：都不显示。
        assertFalse(settings.accepts("Movies/"))
        assertFalse(settings.accepts(""))
    }

    // ------------------------------------------------------------ 键名字面量

    @Test
    fun `持久化的键名是设备上的契约`() {
        // 改这个字面量不会编译失败，只会让所有已安装设备的选择失效。
        assertEquals("library.folders", LibraryFolderSettingsRepository.Keys.FOLDERS.name)
    }

    // ------------------------------------------------------------ 不支持时不过滤

    @Test
    fun `Android 9 上即便勾了文件夹也不过滤`() {
        // 关键：条目路径算不出来（null）时 MediaStore 侧一律给 null，
        // 按规则会被全部排除 —— 必须靠闸门拦住，而不是靠调用方细心。
        val settings = prefsOf(setOf("Movies")).toLibraryFolderSettings(api28)

        assertFalse(settings.supported)
        assertFalse(settings.filtering)
        assertTrue(settings.accepts("Music/"))
        assertTrue(settings.accepts(null))
        // 勾选状态照旧显示出来：系统版本是会变的（换机、升级），别把用户的选择丢掉。
        assertEquals(setOf("Movies"), settings.selected)
    }

    @Test
    fun `Android 9 上一个都没勾也照样显示全部`() {
        // 这是原始缺陷最坏的那条路径：空集 ⇒ 严格排除 ⇒ 空库，
        // 而用户唯一能做的「全不勾」并不能让它恢复。
        val settings = prefsOf(emptySet()).toLibraryFolderSettings(api28)

        assertFalse(settings.filtering)
        assertTrue(settings.accepts("Movies/"))
    }

    // ------------------------------------------------------------ 两把闸门各管各的

    @Test
    fun `读盘失败与系统不支持互不冒充`() {
        // 读盘失败：不过滤，但仍算「支持」——设置页不该在 Android 10+ 上说「此版本不支持」。
        val unknown = LibraryFolderSettings(unknown = true, supported = true)
        assertFalse(unknown.filtering)
        assertTrue(unknown.accepts("Movies/"))

        // 系统不支持：也没被抹成「读不出来」。
        val unsupported = LibraryFolderSettings(folders = setOf("Movies"), supported = false)
        assertFalse(unsupported.filtering)
        assertFalse(unsupported.unknown)
        assertTrue(unsupported.accepts("Music/"))
    }

    @Test
    fun `两个条件都成立时也只有一种结果`() {
        val settings = LibraryFolderSettings(unknown = true, supported = false)

        assertFalse(settings.filtering)
        assertTrue(settings.accepts(null))
        assertEquals(emptySet<String>(), settings.selected)
    }

    // ------------------------------------------------------------ 工具

    /** `folders` 为 null 就是「不带这个键」，也就是从没设置过。 */
    private fun prefsOf(folders: Set<String>?): Preferences {
        val prefs = mutablePreferencesOf()
        if (folders != null) {
            prefs[LibraryFolderSettingsRepository.Keys.FOLDERS] = folders
        }
        return prefs
    }
}
