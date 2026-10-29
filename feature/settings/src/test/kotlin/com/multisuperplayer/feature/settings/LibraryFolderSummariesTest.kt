package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.data.settings.LibraryFolderSettings
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * 「媒体库文件夹」在设置首页那一行副标题。
 *
 * 这四种状态在界面上长得一模一样（都是一行小字），所以只能靠 id 级断言钉住：
 * 选错了分支不会崩、不会报错，只是**说了错的话**——而这一行恰恰是用户判断
 * 「我的媒体库为什么是空的」的唯一线索：
 *
 * - **系统不支持**（Android 9 及以下没有 `RELATIVE_PATH`）必须排在**最前面**。
 *   这类设备上勾选是不生效的，如果先命中「已选 1 个文件夹」，用户会照着一句假话
 *   去排查权限和目录，实际永远修不好；
 * - **读不出来**（DataStore 出错）与**一个都没勾**也不能混：前者要去重试，
 *   后者是用户自己选的，点进去勾一个就好；
 * - 「已选 N 个」要真的带上 N。
 *
 * 另外三语文案要一起加：这一行是新增键，最容易只在中文那份写、英文那份漏掉，
 * 而漏掉的那份在运行时会直接崩（缺资源），单测里提前一步发现更便宜。
 */
class LibraryFolderSummariesTest {

    // ------------------------------------------------------------ 四种状态各有一句

    @Test
    fun `不支持时说要的是系统版本，不是用户没勾`() {
        assertRes(
            R.string.msp_settings_library_folders_unsupported,
            LibraryFolderSummaries.entry(LibraryFolderSettings(folders = emptySet(), supported = false)),
        )
    }

    @Test
    fun `读不出来时说读不出来`() {
        assertRes(
            R.string.msp_settings_library_folders_unknown,
            LibraryFolderSummaries.entry(LibraryFolderSettings(folders = setOf("Movies"), unknown = true)),
        )
    }

    @Test
    fun `一个都没勾时说没勾`() {
        assertRes(
            R.string.msp_settings_library_folders_none,
            LibraryFolderSummaries.entry(LibraryFolderSettings(folders = emptySet())),
        )
    }

    @Test
    fun `勾了就报数量`() {
        val text = LibraryFolderSummaries.entry(
            LibraryFolderSettings(folders = setOf("Movies", "Music/Live")),
        )

        assertRes(R.string.msp_settings_library_folders_count, text)
        Assert.assertEquals("数量要真的带上", listOf<Any?>(2), (text as MspText.Res).args)
    }

    @Test
    fun `四种状态不会撞到同一句`() {
        val ids = listOf(
            LibraryFolderSummaries.entry(LibraryFolderSettings(supported = false)),
            LibraryFolderSummaries.entry(LibraryFolderSettings(unknown = true)),
            LibraryFolderSummaries.entry(LibraryFolderSettings(folders = emptySet())),
            LibraryFolderSummaries.entry(LibraryFolderSettings(folders = setOf("Movies"))),
        ).map { (it as MspText.Res).id }

        Assert.assertEquals("四种状态必须各有自己的文案", 4, ids.toSet().size)
    }

    @Test
    fun `系统不支持压过一切`() {
        // 不支持的同时还「读不出来」「勾了 3 个」「一个没勾」——都还该说系统不支持，
        // 因为那句才是用户能理解的原因，其余三种在这里都只是噪音。
        val cases = listOf(
            LibraryFolderSettings(folders = setOf("A", "B", "C"), supported = false),
            LibraryFolderSettings(folders = emptySet(), unknown = true, supported = false),
        )

        cases.forEach { settings ->
            assertRes(
                R.string.msp_settings_library_folders_unsupported,
                LibraryFolderSummaries.entry(settings),
            )
        }
    }

    // ------------------------------------------------------------ 三语都得有

    @Test
    fun `新增的两句话三种语言都写了`() {
        val keys = listOf(
            "msp_settings_library_folders_unsupported",
            "msp_settings_library_folders_unsupported_note",
        )

        LOCALES.forEach { locale ->
            val strings = settingsStrings(locale)
            keys.forEach { key ->
                Assert.assertTrue(
                    "$locale 缺少 $key（缺资源在运行时是崩溃，不是显示英文）",
                    strings.containsKey(key),
                )
                Assert.assertFalse("$locale 的 $key 是空的", strings.getValue(key).isBlank())
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun assertRes(expected: Int, actual: MspText) {
        Assert.assertEquals(idOf(actual), expected)
    }

    private fun idOf(text: MspText): Int = when (text) {
        is MspText.Res -> text.id
        is MspText.Plain -> error("这一行的文案都该来自资源，不该是硬编码的「${text.text}」")
    }

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
