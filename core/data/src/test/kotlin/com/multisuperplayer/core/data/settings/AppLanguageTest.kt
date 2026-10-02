package com.multisuperplayer.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 语言设置与「资源目录布局」的一致性。
 *
 * 后两个用例会去仓库里翻真实的目录和 `locales_config.xml`。看起来不像单测该干的事，
 * 但这里要锁的是**两份手写清单之间的对齐**（枚举 vs. 目录），而这两份清单一定
 * 会因为「加了语言忘了建目录」而分叉——分叉的症状是用户选了那一项之后界面**没变**，
 * 一句报错都没有。放在编译器够不着的地方，就只能靠测试盯着。
 */
class AppLanguageTest {

    // ---------------------------------------------------------------------
    // 纯逻辑
    // ---------------------------------------------------------------------

    @Test
    fun `每个语言的标签都能原样还原`() {
        // 标签是要写进存储、并且将来可能被删掉再重新加回来的东西，
        // 一来一回不一致就等于「用户选了之后重启又变回去」。
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun `标签互不重复`() {
        val tags = AppLanguage.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `未知标签一律回落到跟随系统`() {
        // 老版本留下的值、手工改过的配置文件、将来被删掉的语言，都会走到这里。
        // 任何一种都不值得让应用起不来，所以这里是「宽容」，不是「严格」。
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("   "))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("ja"))
        // 只有语言、没有文字：「zh」不是一个支持项。中文两个变体各自有自己的标签，
        // 不能靠「前缀是 zh 就算中文」来兜——那会让繁体设备的用户拿到简体。
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zh"))
        // 三段标签同理：不认识的就不认。
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zh-Hant-TW"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("EN"))
    }

    @Test
    fun `标签两侧的空白会被忽略`() {
        // 从 XML / 手工配置文件里读出来的值很容易带空白，而带空白和不带空白
        // 只差一个字符，排查时几乎看不出来。
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(" en "))
    }

    @Test
    fun `默认值是跟随系统`() {
        // 不做「按语言猜」。猜错了并且猜错的那一项还被记进设置，用户得先发现
        // 哪里不对才能改回来——而「跟随系统」猜错的代价是零。
        assertEquals(AppLanguage.SYSTEM, AppLanguage.DEFAULT)
    }

    @Test
    fun `只有跟随系统依赖翻译, 其余都是自称`() {
        // 语言名要用自己的语言写自己：界面是英文时「简体中文」那一行也要写简体中文，
        // 否则一个只会中文的用户在英文界面里就找不到自己的语言了——而这正是他
        // 进这个设置页的唯一原因。
        assertEquals(null, AppLanguage.SYSTEM.endonym)
        AppLanguage.concrete.forEach { language ->
            assertNotNull("${language.name} 缺少自称", language.endonym)
            assertTrue("${language.name} 的自称是空的", language.endonym!!.isNotBlank())
        }
    }

    @Test
    fun `兜底语言住在 values 目录里`() {
        // 兜底语言没有 values-xx 目录。如果它被改成有目录了（比如从 values/ 挪到
        // values-b+zh+Hans），那么「系统语言没人支持」时就会掉进英文——一个
        // 只影响小语种用户的静默行为变化。
        assertEquals("values", AppLanguage.resourceDirName(AppLanguage.FALLBACK))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.FALLBACK)
    }

    @Test
    fun `例外语言各有不同的目录`() {
        val dirs = AppLanguage.translated.map { AppLanguage.resourceDirName(it) }
        assertEquals(dirs.size, dirs.toSet().size)
        assertTrue("兜底语言不该出现在 translated 里", AppLanguage.FALLBACK !in AppLanguage.translated)
        assertTrue("跟随系统不是一种语言", AppLanguage.SYSTEM !in AppLanguage.translated)
    }

    // ---------------------------------------------------------------------
    // 仓库里的真实文件
    // ---------------------------------------------------------------------

    @Test
    fun `每个有文案的模块都要把例外语言翻译齐`() {
        val modulesWithStrings = modulesWithStringsFile()
        assertTrue(
            "一个带 values/strings.xml 的模块都没找到，说明仓库根目录定位错了——" +
                "这个用例会因此变成空转，所以这里直接失败而不是跳过",
            modulesWithStrings.isNotEmpty(),
        )

        val missing = mutableListOf<String>()
        modulesWithStrings.forEach { module ->
            AppLanguage.translated.forEach { language ->
                val dir = File(module, "src/main/res/${AppLanguage.resourceDirName(language)}/strings.xml")
                if (!dir.isFile) missing += module.name + " → " + AppLanguage.resourceDirName(language)
            }
        }
        assertEquals("以下模块缺少译文（用户选了该语言后这些页面会显示中文）", emptyList<String>(), missing.sorted())
    }

    @Test
    fun `locales_config 与语言列表完全一致`() {
        val file = File(repoRoot(), "app/src/main/res/xml/locales_config.xml")
        assertTrue("找不到 ${file.path}", file.isFile)

        // 只取 android:name="..." 的值，不引 XML 解析器：这个文件结构固定，
        // 而多引一个解析器只会多一处可能出错的地方。
        val declared = Regex("""android:name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toList()

        // 系统那个列表要的是**全部**支持的语言（含兜底的简体中文），
        // 少一项就会在系统设置里缺一个选项，而应用内还能选——两边不一致。
        assertEquals(AppLanguage.concrete.map { it.tag }.sorted(), declared.sorted())
    }

    @Test
    fun `首尾带空白的文案必须用双引号包住`() {
        // aapt2 会把 <string> 值的首尾空白裁掉，**除非**整段值被一对双引号包住
        // （Android 文档里「保留空白」的写法）。这件事只在实际渲染时才看得出来：
        // `> · <` 编进 APK 就变成 `·`，于是「Music · 系统软解」显示成「Music·系统软解」。
        //
        // 更隐蔽的是中文那一份当时用的是全角空格：全角空格不是 ASCII 空白，aapt2 不裁，
        // 所以只有英文/繁体出错——一个只在小语种下出现、看中文截图永远发现不了的 bug。
        //
        // 这个用例锁的是**写法**，不是渲染结果：单测里拿不到 aapt2 处理过的资源，
        // 只能保证「要空格就写引号」这条规矩被遵守。
        val offenders = mutableListOf<String>()

        stringsFiles().forEach { file ->
            STRING_ENTRY.findAll(file.readText()).forEach { match ->
                val raw = match.groupValues[2]
                if (raw.isNotEmpty() && raw != raw.trim()) {
                    val quoted = raw.length >= 2 && raw.first() == '"' && raw.last() == '"'
                    if (!quoted) {
                        val label = file.relativeTo(repoRoot()).invariantSeparatorsPath
                        offenders += "$label:${match.groupValues[1]} = [$raw]"
                    }
                }
            }
        }

        assertEquals(
            "以下文案的首尾空白会被 aapt2 裁掉，请写成 \" · \" 这样把整段值包在双引号里：",
            emptyList<String>(),
            offenders.sorted(),
        )
    }

    // ---------------------------------------------------------------------

    /**
     * 仓库根目录。
     *
     * Gradle 跑单测时工作目录是**模块目录**（`core/data`），所以这里要往上找到
     * 带 `settings.gradle.kts` 的那一层。找不到就直接抛：一个静默返回 null 的
     * 实现会让上面两个用例变成永远通过的空转。
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 往上找不到 settings.gradle.kts")
    }

    /** 所有带 `values/strings.xml` 的模块目录。 */
    private fun modulesWithStringsFile(): List<File> =
        repoRoot()
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.name == "strings.xml" }
            .mapNotNull { moduleOf(it) }
            .distinct()
            .toList()

    /** 所有 `src/main/res/<限定符>/strings.xml`，即真正的文案来源（不含测试资源）。 */
    private fun stringsFiles(): List<File> =
        repoRoot()
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.name == "strings.xml" && moduleOf(it) != null }
            .toList()

    /**
     * 从 `.../<模块>/src/main/res/<限定符>/strings.xml` 里取出 `<模块>` 目录。
     *
     * 刻意不用「数 `parentFile` 的层数」：`values → res → main → src → 模块` 正好五层，
     * 数错一层不会报错，只会让这个用例悄悄找不到任何模块（第一版就是这么写的，
     * 于是它每次都以「一个模块都没找到」失败）。改成从下往上找到名为 `src` 的那一
     * 层再取上一级，最后**回读一遍拼出来的路径**来确认。
     */
    private fun moduleOf(stringsXml: File): File? {
        val qualifierDir = stringsXml.parentFile ?: return null
        var dir: File? = qualifierDir
        while (dir != null && dir.name != "src") dir = dir.parentFile
        val module = dir?.parentFile ?: return null
        val expected = File(module, "src/main/res/${qualifierDir.name}/strings.xml")
        return if (expected.isFile) module else null
    }

    private companion object {
        /**
         * 一条 `<string>` 的原始文本。
         *
         * 允许 `name` 后面还有别的属性（`translatable="false"` 之类）。值用非贪婪匹配到
         * `</string>`，并打开 DOT_MATCHES_ALL——文案里确实有换行（`msp_translate_network_cleartext`
         * 就是多行的），不开这个开关那些条目会被整体跳过，于是最该检查的长文案恰好不被检查。
         *
         * 不做实体反转义：这里只关心空白，而实体（`&lt;` 等）里没有空白。
         */
        val STRING_ENTRY =
            Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
