package com.multisuperplayer.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 正则字面量里的**裸花括号**会让应用在真机上直接崩，而单测全绿。
 *
 * ## 起因（v1.2.1 真机验证时抓到的那次闪退）
 *
 * ```kotlin
 * // 错：闭合的花括号没有转义（字符类里那两个也没转义）
 * private val decorationGroup = Regex("""...|\{[^{}]*}|...""")
 * // 对
 * private val decorationGroup = Regex("""...|\{[^\{\}]*\}|...""")
 * ```
 *
 * JVM 的 `Pattern` 把落单的 `}` 当字面量，**Android 的正则引擎（ICU）不当**，它抛
 * `PatternSyntaxException`。而这一句写在类的**静态初始化**里，于是第一次用到那个类就是
 * `ExceptionInInitializerError`：
 *
 * ```
 * FATAL EXCEPTION: main
 * java.lang.ExceptionInInitializerError
 *   at ...SubtitleFileLocator.scanDirectory(SubtitleFileLocator.kt:84)
 * Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 39
 *   at ...SubtitleFileNaming.<clinit>(SubtitleFileNaming.kt:129)
 * ```
 *
 * 症状是「一播放就闪退」，而**离出错的那一行很远的调用点**（`scanDirectory`）才是栈顶，
 * 顺着栈往下还要跨两层 `ExceptionInInitializerError` 才看到真话。
 *
 * ## 为什么单测看不见
 *
 * 单测跑在 JVM 上，那个 `}` 在 JVM 上是合法的——**这条守护覆盖的是两个引擎的语法差异，
 * 而单测只跑其中一个**。要发现它只有两条路：把每个正则交给 ICU 编一遍（要引 icu4j 依赖，
 * 十几 MB，只为一个花括号不值），或者**锁写法**：不许出现「不是量词的花括号」。
 * 这里走第二条，和 `AppLanguageTest` 里那两个「锁写法」的用例同一个路子
 * （aapt2 的空白裁剪、撇号，同样是「单测看不见、真机上才炸」那一类）。
 *
 * 判据是**先把「合法的花括号」摘掉，再找剩下的裸花括号**，合法的有两种：
 * 量词（`\d{1,3}` 用裸花括号是正道）与**转义过的字面量**（`\{`、`\}`）。
 * 少了后面这条会把已经改对的正则也判成违规——第一版就踩了这个坑，报出的正是
 * 刚改好的那一行 `\{[^\{\}]*\}`。
 *
 * ## 已知的覆盖边界
 *
 * 只查 `src/main` 下的 Kotlin 源（测试里的正则错了不会让应用崩），且只查
 * **原始字符串**写法（`Regex("""...""")`）——本仓库的复杂正则一律用原始字符串，
 * 普通字符串那一类还要先过 Kotlin 自己的转义才能得到正则文本，判据不直观；
 * 它们目前都是 `\\s+`、`[^>]*` 这种不含花括号的短模式。
 */
class KotlinRegexBracesTest {

    @Test
    fun `正则里的花括号只能是量词`() {
        val sources = mainKotlinSources()
        assertTrue(
            "一个 Kotlin 源文件都没扫到，说明仓库根目录定位错了——" +
                "这个用例会因此变成永远通过的空转，所以这里直接失败",
            sources.isNotEmpty(),
        )

        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            RAW_PATTERN.findAll(file.readText()).forEach { match ->
                val pattern = match.groupValues[1]
                val rest = QUANTIFIER.replace(ESCAPED_BRACE.replace(pattern, ""), "")
                if (rest.contains('{') || rest.contains('}')) {
                    val label = file.relativeTo(repoRoot()).invariantSeparatorsPath
                    offenders += "$label：${pattern.replace("\n", " ").trim().take(80)}"
                }
            }
        }

        assertEquals(
            "以下正则里有不是量词的花括号。JVM 不介意、Android 会在**类初始化**时抛 " +
                "PatternSyntaxException（症状是闪退，栈顶指向离它很远的调用点）：请写成 \\{ 与 \\}：",
            emptyList<String>(),
            offenders.sorted(),
        )
    }

    @Test
    fun `这个用例真的能扫到正则`() {
        // 上面那条用例「没找到违规」和「根本没读到正则」是**同一个结论**（都是全绿），
        // 所以这里反向钉一条：确认提取规则本身还认得本仓库的写法。
        // 提取规则一旦失灵（比如有人把 `Regex(` 换成别的构造方式），上一条就会静默失效。
        val patterns = mainKotlinSources().flatMap { file ->
            RAW_PATTERN.findAll(file.readText()).map { it.groupValues[1] }.toList()
        }
        assertTrue(
            "扫到的正则字面量少于 20 条（实际 ${patterns.size} 条），提取规则可能已经不匹配本仓库的写法了",
            patterns.size >= 20,
        )
    }

    /** 仓库根目录：Gradle 跑单测时工作目录是模块目录（`core/data`），要往上找到带 `settings.gradle.kts` 的那层。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 往上找不到 settings.gradle.kts")
    }

    /** 全部模块的 `src/main` 下的 Kotlin 源文件（跳过构建产物与 git 目录）。 */
    private fun mainKotlinSources(): List<File> =
        repoRoot()
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()

    private companion object {
        /** `Regex("""…""")` 里的那段模式文本。非贪婪到下一个 `"""`，跨行也用 `[\s\S]` 而不是 `.`。 */
        val RAW_PATTERN = Regex("Regex\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"")

        /** 合法的量词：`{2}`、`{2,}`、`{1,3}`。摘掉它们之后，剩下的花括号都是字面量。 */
        val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")

        /** 转义过的字面量花括号：`\{`、`\}`。这两个是**正解**，不是违规。 */
        val ESCAPED_BRACE = Regex("""\\[\{\}]""")
    }
}
