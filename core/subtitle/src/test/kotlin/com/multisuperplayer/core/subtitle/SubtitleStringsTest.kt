package com.multisuperplayer.core.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住 `core:subtitle` 的三语言字幕文案。
 *
 * ## 为什么这个模块突然有了资源
 *
 * 这里以前**一条文案都没有**：解析告警是硬编码的中文 `String`，理由是「这个模块
 * 不该带资源」。代价是切到英文界面时，字幕列表里的告警仍然是中文，而且**没有任何
 * 机制能让译者看见它**——它不在任何 `strings.xml` 里。（已记在 README 的已知限制里。）
 *
 * 现在告警是一条 `MspText.Res(id, args)`，所以这个模块必须有自己的一份
 * `strings.xml`，也就必须有这个守卫测试。项目里每个带资源的模块都有一个，
 * 形状一样（见 `LlmStringsTest`、`PlayerStringsTest`）。
 *
 * ## 这里钉的不是「翻译得好不好」
 *
 * 翻译质量没法自动化。能自动化的、也真的会出错的是这三件：
 * 1. 某个语言**漏了一条键**（新增一条告警时最容易发生）；
 * 2. 英文文件里**残留中文**（复制 `values` 过去改了一半）；
 * 3. 同一条键在三种语言里**占位符不一样**（中文写 `%1$d`、英文写 `%1$s`）——
 *    这一条最阴：编译期完全看不出来，只在**用户切到那种语言**时抛
 *    `IllegalFormatConversionException`，而开发者一直用中文，永远碰不到。
 */
class SubtitleStringsTest {

    private val locales = listOf("values", "values-en", "values-b+zh+Hant")

    @Test
    fun `三种语言的键必须完全一致`() {
        val reference = strings("values").keys

        assertTrue("core:subtitle 现在应当有字幕文案，实际是空的", reference.isNotEmpty())

        locales.forEach { locale ->
            val keys = strings(locale).keys

            assertEquals(
                "$locale 与 values 的键不一致；缺：${(reference - keys).sorted()}；多：${(keys - reference).sorted()}",
                reference.sorted(),
                keys.sorted(),
            )
        }
    }

    @Test
    fun `英文文案里不能残留中文`() {
        // 允许出现的只有资源名和参数，没有正文。中日韩统一表意文字一段就够判定。
        strings("values-en").forEach { (key, value) ->
            assertFalse("values-en 的 $key 里残留了中文：$value", value.any { it.isCjk() })
        }
    }

    @Test
    fun `繁体文案必须是真翻译而不是英文副本`() {
        // 反过来的那一半：「英文没有中文」只能证明没照抄 `values`，
        // 证明不了繁体那份没照抄英文（照抄 en 同样满足上一条）。
        strings("values-b+zh+Hant").forEach { (key, value) ->
            assertTrue("values-b+zh+Hant 的 $key 看起来不是中文：$value", value.any { it.isCjk() })
        }
    }

    @Test
    fun `同一条键的占位符必须在三种语言里一致`() {
        // 比的是**索引到类型的映射**，不是出现顺序。英文把「%2$d 行」放在句子开头
        // 是合法的（Java 格式化支持显式索引）而且读起来更顺，把它当成不一致
        // 只会逼着译者照搬中文语序。真正会出事的是**类型**：中文写 `%1$d`、
        // 英文写 `%1$s` 时，`Resources.getString` 会在用户切到那种语言的那一刻
        // 抛 `IllegalFormatConversionException`，而开发者一直用中文，永远碰不到。
        val byLocale = locales.associateWith { strings(it) }

        byLocale.getValue("values").forEach { (key, value) ->
            val expected = placeholders(value)

            byLocale.forEach { (locale, texts) ->
                assertEquals(
                    "$locale 的 $key 占位符与 values 不一致：$expected vs ${placeholders(texts.getValue(key))}",
                    expected,
                    placeholders(texts.getValue(key)),
                )
            }
        }
    }

    @Test
    fun `键名都带字幕前缀`() {
        // Android 的资源是**全局按名字合并**的：两个模块各有一条同名键，
        // 谁赢取决于合并顺序，而这种冲突没有任何警告。前缀是唯一廉价的防线，
        // 顺便让「这条键属于哪个模块」在 grep 时一眼可见。
        strings("values").keys.forEach { key ->
            assertTrue("键名 $key 应当以 msp_subtitle_ 开头", key.startsWith("msp_subtitle_"))
        }
    }

    private fun strings(locale: String): Map<String, String> = readResource(
        File(repoRoot(), "core/subtitle/src/main/res/$locale/strings.xml"),
    )

    private fun readResource(file: File): Map<String, String> {
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 抽出 `%1$s` 这种位置化占位符，归一到「索引 → 类型」（顺序无关）。 */
    private fun placeholders(value: String): List<String> =
        Regex("""%(\d+)\$([a-zA-Z])""").findAll(value)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .sortedBy { it.first }
            .map { (index, type) -> "%$index\$$type" }
            .toList()

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'

    /** 从测试的工作目录往上找仓库根（认得 `settings.gradle.kts`）。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）")
    }
}
