package com.multisuperplayer.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入播放列表的**界面文案**守卫。
 *
 * 导入这条链上真正会「静默出错」的地方几乎都在数据层（`PlaylistImporterTest`
 * 守着），而界面层只剩文案——但文案在这里恰好是三条容易漏的东西：
 *
 * 1. **键只写了一种语言**。资源缺失时 Android 不会报错，它会把**键名本身**
 *    显示出来（`msp_import_error_permission`），用户看到的是一串英文下划线。
 *    更糟的是漏在 `values-en` 里：中文界面完全正常，只有英文用户看到键名。
 * 2. **占位符在翻译时被吃掉或改了类型**。`%1$d` 被译成 `%1$s` 之后，
 *    `getString(id, count)` 会在**运行时**抛 `IllegalFormatConversionException`；
 *    整个占位符被删掉则数字干脆不出现——「本次导入共有 个同名列表需要处理」。
 *    两者都不是编译期错误。
 * 3. **合并 / 新建被翻成同一句话**。对话框上两个按钮写着同一个词，
 *    用户按哪个都是猜；而选错会直接把文件里的条目并进他现有的列表。
 *
 * 断言方式沿用 `TranslationCacheTextTest`：纯 JVM 单测拿不到 `Resources`，
 * 所以文案本身读 `strings.xml` 的文本，按**键**比对而不是按位置。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」。
 */
class PlaylistImportStringsTest {

    /**
     * 这个功能引入的键。写死在这里而不是从 `R.string` 反射出来：
     * 漏一个键正是要抓的错，靠反射就永远抓不到。
     */
    private val importKeys = listOf(
        "msp_playlists_import",
        "msp_import_default_name",
        "msp_import_ask_title",
        "msp_import_ask_desc",
        "msp_import_ask_remaining",
        "msp_import_merge",
        "msp_import_new",
        "msp_import_apply_all",
        "msp_import_error_permission",
        "msp_import_error_not_found",
        "msp_import_error_read",
        "msp_import_error_save",
    )

    private val locales = listOf(DIR_ZH, DIR_EN, DIR_HANT)

    // ===== 键集 =====

    @Test
    fun `三种语言里这十二个键一个都不少`() {
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            importKeys.forEach { key ->
                assertTrue("$dir 里缺少 $key（缺失时界面会直接把键名显示给用户）", strings.containsKey(key))
            }
        }
    }

    @Test
    fun `英文资源里没有残留中文`() {
        // 只查 values-en 里的 CJK：繁中**本来就该**是汉字，把它也查一遍会直接把
        // 一整份正确的资源判成错的——这种守卫写错了比没写更坏。
        val cjk = Regex("[\\u4e00-\\u9fff]")

        importKeys.forEach { key ->
            val value = stringsOf(DIR_EN).getValue(key)
            assertEquals(
                "values-en 里的 $key 还留着中文，英文用户会看到半句中文",
                emptyList<String>(),
                cjk.findAll(value).map { it.value }.toList(),
            )
        }
    }

    // ===== 占位符 =====

    @Test
    fun `带参数的句子在三种语言里占位符完全一致`() {
        // `%1$d` 被译成 `%1$s` 会在运行时抛 IllegalFormatConversionException，
        // 而整个占位符被删掉只是「数字不见了」——后者更难发现，所以这里比的是集合，
        // 少一个也多一个都算错。
        listOf("msp_import_ask_desc", "msp_import_ask_remaining", "msp_import_error_read").forEach { key ->
            val expected = placeholdersOf(stringsOf(DIR_ZH), key)
            assertTrue("$key 在中文里就没有占位符了，这条断言会失去意义", expected.isNotEmpty())
            locales.forEach { dir ->
                assertEquals(
                    "$dir 里的 $key 占位符与中文对不上",
                    expected.toSortedSet(),
                    placeholdersOf(stringsOf(dir), key).toSortedSet(),
                )
            }
        }
    }

    @Test
    fun `剩下的都这样处理只有勾选框用到它`() {
        // 这条文案是「全部套用」那个勾选框的标签。它只在**还有别的同名列表**时出现
        // （`ImportChoiceDialog` 里按 `remaining > 1` 判断），所以它自带「剩下的」这个前提——
        // 一旦被改写成中性的「套用所有」，只有一个冲突时弹出的勾选框就会让人困惑。
        val tail = mapOf(
            DIR_ZH to "剩下",
            DIR_EN to "rest",
            DIR_HANT to "剩下",
        )

        tail.forEach { (dir, word) ->
            val value = stringsOf(dir).getValue("msp_import_apply_all")
            assertTrue("$dir 的 msp_import_apply_all 必须点明只影响「剩下的」那些", value.contains(word))
        }
    }

    // ===== 分支之间必须分得开 =====

    @Test
    fun `合并与新建不是同一句话`() {
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里「合并」「新建」写成了同一句话，用户在对话框上只能猜",
                strings.getValue("msp_import_new"),
                strings.getValue("msp_import_merge"),
            )
        }
    }

    @Test
    fun `询问句里必须同时解释合并和新建各自做什么`() {
        // 两个按钮上只有「合并」「新建」四个字，代价与后果全在这句描述里。
        // 少解释一句，用户就只能靠猜——而猜错的代价是往他现有的列表里灌进外部条目。
        val words = mapOf(
            DIR_ZH to listOf("合并", "新建"),
            DIR_EN to listOf("Merge", "New"),
            DIR_HANT to listOf("合併", "新建"),
        )

        words.forEach { (dir, list) ->
            val value = stringsOf(dir).getValue("msp_import_ask_desc")
            list.forEach { word ->
                assertTrue("$dir 的 msp_import_ask_desc 里没有解释「$word」会做什么", value.contains(word))
            }
        }
    }

    @Test
    fun `四条失败文案互不相同`() {
        // 权限、文件不在、读失败、写失败是四种要做的事完全不同的情况：
        // 重新选文件 / 去找文件 / 换个文件 / 清空间。合并任意两句，
        // 用户就会去做错的那件事。
        val failures = listOf(
            "msp_import_error_permission",
            "msp_import_error_not_found",
            "msp_import_error_read",
            "msp_import_error_save",
        )

        locales.forEach { dir ->
            val strings = stringsOf(dir)
            failures.forEachIndexed { index, key ->
                failures.drop(index + 1).forEach { other ->
                    assertNotEquals(
                        "$dir 里 $key 和 $other 是同一句话",
                        strings.getValue(other),
                        strings.getValue(key),
                    )
                }
            }
        }
    }

    @Test
    fun `标题与描述不是同一句`() {
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里对话框的标题和正文写成了同一句",
                strings.getValue("msp_import_ask_title"),
                strings.getValue("msp_import_ask_desc"),
            )
        }
    }

    // ===== 兜底名字 =====

    @Test
    fun `兜底名字在三种语言里都非空`() {
        // 文件名洗完之后什么都不剩（比如整份文件叫 `20261004.csv`）时用它兜底。
        // 空串会让导入进来一个**没有名字**的列表，而列表页对空名字只有一句
        // 「未命名」的占位——用户没法把它和别的无名列表分开。
        importKeys.forEach { key ->
            locales.forEach { dir ->
                assertTrue("$dir 的 $key 是空的", stringsOf(dir).getValue(key).isNotBlank())
            }
        }
    }

    // ===== 工具 =====

    /** 取某个句子里用到的占位符，形如 `%1$d`；保留类型字母，`$s` 与 `$d` 不能互换。 */
    private fun placeholdersOf(strings: Map<String, String>, key: String): List<String> =
        Regex("""%(\d+)\$([sd])""").findAll(strings.getValue(key))
            .map { "${it.groupValues[1]}${it.groupValues[2]}" }
            .toList()

    /** 读某个语言的 `strings.xml`：键 → 文本。 */
    private fun stringsOf(dir: String): Map<String, String> = cache.getOrPut(dir) {
        val file = File(repoRoot(), "feature/library/src/main/res/$dir/strings.xml")
        assertTrue("找不到资源文件 $file", file.isFile)
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
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
        const val DIR_ZH = "values"
        const val DIR_EN = "values-en"
        const val DIR_HANT = "values-b+zh+Hant"

        /** 三个语言文件都要读两遍以上（键集、占位符、分支…），读一次就够。 */
        val cache = HashMap<String, Map<String, String>>()
    }
}
