package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.data.library.FolderNode
import com.multisuperplayer.core.data.library.LibraryFolderRules
import com.multisuperplayer.core.data.settings.LibraryFolderSettings
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * 文件夹树的**折叠**（`hasChildFolder` / `visibleFolderRows` / `defaultCollapsedPaths`）。
 *
 * 这一组逻辑全在 [LibraryFolderSettingsUiState] 和几个 `internal` 纯函数里，而不是写在
 * composable 里——原因是本模块**没有** `compose-ui-test` 依赖，界面一行都测不了；
 * 而折叠恰恰是「算错了也照常渲染、只是少了/多了几行」的那类 bug，靠看截图很难发现
 * （目录一多，谁都不会去数第 7 行底下少了两个子文件夹）。
 *
 * 四条必须钉住的语义：
 *
 * 1. **折叠状态和勾选状态无关。** 收起来的子树里的文件该进库还是进库——「看不见」
 *    绝不能被读成「不选」。所以 [visibleFolderRows] 只过滤显示，不动 [FolderRow.checked]。
 * 2. **收起来的文件夹自己不消失**，只遮住它的子孙。否则点一下箭头那一行就没了，
 *    再也点不回来。
 * 3. **根目录永远可见**，且默认**展开**（它是 `""`，是每一行的祖先；把它收起来等于
 *    屏幕上只剩一行）。
 * 4. **默认只展开需要的那些**：顶层收起来，但「已经勾选的文件夹」那条链要展开，
 *    否则用户勾过的东西一眼看去像是没勾。
 */
class LibraryFolderRowsTest {

    // ------------------------------------------------------------ hasChildren

    @Test
    fun `根目录有子文件夹时算有孩子`() {
        // 回归用例：不能用 `covers` 实现——`covers("", "")` 为真但 `covers("", "Music")`
        // 被**故意**定义为假（卷根只覆盖自己），于是根这一行永远拿不到箭头，
        // 而根恰恰是最常被收起来的那一行。
        Assert.assertTrue(hasChildFolder("", paths))
        Assert.assertFalse(LibraryFolderRules.covers("", "Music"))
    }

    @Test
    fun `叶子行没有孩子`() {
        Assert.assertFalse(hasChildFolder("AB", paths))
        Assert.assertFalse(hasChildFolder("Movies", paths))
        Assert.assertFalse(hasChildFolder("Music/Live", paths))
    }

    @Test
    fun `中间层有孩子`() {
        Assert.assertTrue(hasChildFolder("Music", paths))
    }

    @Test
    fun `前缀相同但不是父子不算孩子`() {
        // "A" vs "AB"：字符串前缀成立、路径前缀不成立。漏了那个 '/' 会让 "AB"
        // 显示在 "A" 底下（或者在 "A" 上画一个按了没反应的箭头）。
        Assert.assertFalse(hasChildFolder("A", listOf("A", "AB")))
        Assert.assertTrue(hasChildFolder("A", listOf("A", "A/B")))
    }

    // ------------------------------------------------------------ visibleRows

    @Test
    fun `什么都没收时一行不少`() {
        Assert.assertEquals(rows, visibleFolderRows(rows, emptySet()))
    }

    @Test
    fun `收起一个父文件夹只遮住子孙`() {
        val visible = visibleFolderRows(rows, setOf("Music")).map { it.path }

        // 顺序照 `folderNodes` 的字符串排序（`"Movies" < "Music"`，逐字符比到第二个字母），
        // 这里不重排成「看起来顺眼」的样子——界面画的就是这个顺序。
        Assert.assertEquals(listOf("", "AB", "Movies", "Music"), visible)
    }

    @Test
    fun `收起来的文件夹自己还在`() {
        // 这一条是「点一下箭头整行消失、再也点不回来」的回归。
        Assert.assertTrue(visibleFolderRows(rows, setOf("Music")).any { it.path == "Music" })
    }

    @Test
    fun `根永远可见`() {
        // 把所有能收的都收起来，根也必须留着——它是唯一的入口。
        val all = paths.toSet()
        val visible = visibleFolderRows(rows, all).map { it.path }

        Assert.assertEquals(listOf(""), visible)
    }

    @Test
    fun `收起叶子行没反应`() {
        // 叶子行界面上根本没有箭头，但状态里混进一条也不该出问题（比如树变了）。
        Assert.assertEquals(rows, visibleFolderRows(rows, setOf("Movies")))
        Assert.assertEquals(rows, visibleFolderRows(rows, setOf("Music/Live")))
    }

    @Test
    fun `收两层与收上面那层结果一样`() {
        val nested = visibleFolderRows(rows, setOf("Music", "Music/Live")).map { it.path }
        val outer = visibleFolderRows(rows, setOf("Music")).map { it.path }

        Assert.assertEquals(outer, nested)
    }

    @Test
    fun `收起来的子树里勾着的行只是看不见，勾还在`() {
        // 「收起来」≠「不选」：这是整个功能最容易被误实现的语义。
        val checkedRows = buildFolderRows(tree, LibraryFolderSettings(folders = setOf("Music")))
        val state = LibraryFolderSettingsUiState(rows = checkedRows, collapsed = setOf("Music"))

        Assert.assertEquals(listOf("", "AB", "Movies", "Music"), state.visibleRows.map { it.path })
        Assert.assertTrue(
            "收起之后勾必须原样保留，否则展开回来会看到勾没了",
            checkedRows.filter { it.path.startsWith("Music/Live") }.all { it.checked },
        )
    }

    // ------------------------------------------------------------ 默认收起哪些

    @Test
    fun `默认只展开顶层`() {
        val collapsed = defaultCollapsedPaths(tree, selected = emptySet())

        Assert.assertTrue("Music 有子文件夹，默认该收起来", "Music" in collapsed)
        Assert.assertFalse("根默认展开", "" in collapsed)
        Assert.assertFalse("叶子行不需要收", "Music/Live" in collapsed)
        Assert.assertFalse("叶子行不需要收", "Movies" in collapsed)
    }

    @Test
    fun `已勾选的文件夹那条链会展开`() {
        // 勾了 Music/Live 却看到 Music 收着，会以为白勾了。
        val collapsed = defaultCollapsedPaths(tree, selected = setOf("Music/Live"))

        Assert.assertFalse("Music 是 Music/Live 的祖先，必须展开", "Music" in collapsed)
    }

    @Test
    fun `勾了别处不影响 Music 收起`() {
        val collapsed = defaultCollapsedPaths(tree, selected = setOf("Movies"))

        Assert.assertTrue("Movies 是叶子，展开它不需要动 Music", "Music" in collapsed)
    }

    @Test
    fun `空树没有可收的`() {
        Assert.assertTrue(defaultCollapsedPaths(emptyList(), selected = emptySet()).isEmpty())
    }

    @Test
    fun `勾了根目录也不会把根收起来`() {
        Assert.assertFalse("" in defaultCollapsedPaths(tree, selected = setOf("")))
    }

    // ------------------------------------------------------------ buildFolderRows

    @Test
    fun `勾父目录时子孙都显示成勾`() {
        val rows = buildFolderRows(tree, LibraryFolderSettings(folders = setOf("Music")))

        Assert.assertTrue(
            "勾选是包含子文件夹的，子孙行必须也显示勾",
            rows.filter { it.path == "Music" || it.path.startsWith("Music/") }.all { it.checked },
        )
        Assert.assertFalse(
            "没被覆盖的分支不该显示勾",
            rows.first { it.path == "Movies" }.checked,
        )
    }

    @Test
    fun `行的层级与名字来自节点`() {
        val rows = buildFolderRows(tree, LibraryFolderSettings())

        Assert.assertEquals(0, rows.first { it.path.isEmpty() }.depth)
        Assert.assertEquals(2, rows.first { it.path == "Music/Live" }.depth)
        Assert.assertEquals("Live", rows.first { it.path == "Music/Live" }.name)
        Assert.assertEquals("", rows.first { it.path.isEmpty() }.name)
    }

    // ------------------------------------------------------------ 三语都得有

    @Test
    fun `箭头两句话三种语言都写了`() {
        val keys = listOf(
            "msp_settings_library_folder_expand",
            "msp_settings_library_folder_collapse",
        )

        LOCALES.forEach { locale ->
            val strings = settingsStrings(locale)
            keys.forEach { key ->
                Assert.assertTrue(
                    "$locale 缺少 $key（缺资源在运行时是崩溃，不是显示英文）",
                    strings.containsKey(key),
                )
                Assert.assertFalse("$locale 的 $key 是空的", strings.getValue(key).isBlank())
                val value = strings.getValue(key)
                Assert.assertEquals(
                    "$locale 的 $key 首尾不能有空白（aapt2 会裁掉，界面看起来像漏字）",
                    value.trim(),
                    value,
                )
            }
        }
    }

    // ------------------------------------------------------------ 工具

    /** 一棵够用的树：`"" / AB / Music / Music/Live / Music/Pop / Movies`。 */
    private val tree: List<FolderNode> =
        LibraryFolderRules.folderNodes(listOf("Music/Live/", "Music/Pop/", "Movies/", "AB/"))

    private val paths: List<String> = tree.map { it.path }

    private val rows: List<FolderRow> = buildFolderRows(tree, LibraryFolderSettings())

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun settingsStrings(locale: String): Map<String, String> {
        val file = File(repoRoot(), "feature/settings/src/main/res/$locale/strings.xml")
        Assert.assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
    }

    /** 从测试的工作目录往上找仓库根（认得 `settings.gradle.kts`）。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）")
    }

    private companion object {
        val LOCALES = listOf("values", "values-en", "values-b+zh+Hant")
    }
}
