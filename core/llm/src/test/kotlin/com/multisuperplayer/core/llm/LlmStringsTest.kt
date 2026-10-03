package com.multisuperplayer.core.llm

import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `core:llm` 的三套文案。
 *
 * 与 `PlayerStringsTest` / `SettingsSummariesTest` 里的同名用例是同一个理由：
 * 少一条键不会崩、不会报错，只会**静默回落到中文**（Android 的行为），
 * 或者是同一个占位符在两份文件里被改成不同类型。两种都只有切到那个语言、
 * 盯着那一屏看才会发现，所以这里把它变成一条会红的断言。
 *
 * 这里只读文件、不渲染：渲染成句子要 `MspText` + `Resources`，JVM 单测里拿不到。
 */
class LlmStringsTest {

    @Test
    fun `三种语言的关键字集合完全一致`() {
        val base = strings("values").keys

        Assert.assertEquals("英文少了键", base, strings("values-en").keys)
        Assert.assertEquals("繁体少了键", base, strings("values-b+zh+Hant").keys)
    }

    @Test
    fun `英文资源里不能残留中文`() {
        // 全角标点（`，`、`：`）也算：中文标点出现在英文句子里同样说明这句没翻。
        val offenders = strings("values-en")
            .filterValues { value -> value.any { it.isCjk() } }
            .keys

        Assert.assertEquals("这些键的英文文案里还有中日韩字符", emptySet<String>(), offenders)
    }

    @Test
    fun `分隔符必须保住两侧的空格`() {
        // aapt2 会把值的首尾空白裁掉，除非整段值被一对双引号包住。
        // 少了引号，设备上看到的是「Music·系统软解」——中文那份看不出来（全角空格不裁），
        // 只有英文/繁体出错，于是这个 bug 只在切到小语种时才现形。
        // 这里读的是 `unquote` 之后的值，所以少了引号就会被裁成 `·` 而失败。
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            Assert.assertEquals("$locale 的分隔符两侧要留空格", " · ", strings(locale)["msp_llm_detail_sep"])
        }
    }

    @Test
    fun `体积占位符是字符串型，不是整数型`() {
        // `LlmModelInfo.sizeText()` 传进来的是**格式化好的**体积（`328.7 MB`）。
        // 占位符写成 `%1$d` 会在真机上抛 IllegalFormatConversionException——
        // 也就是「打开设置页就崩」，而单测里只看文件是看不出来的。
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val template = strings(locale).getValue("msp_llm_model_size")

            assertTrue("$locale 的体积占位符必须是 %1\$s，实际是：$template", template.contains("%1\$s"))
        }
    }

    @Test
    fun `HTTP 状态码占位符是整数型`() {
        // 反过来的那一半：`Http(code)` 传的是 Int。写成 `%1$s` 不会崩，
        // 但会在界面上把错误码当成字符串拼接——真崩的那一种是写反方向
        // （Int 塞进 `%1$s` 是合法的，String 塞进 `%1$d` 才会抛）。
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val template = strings(locale).getValue("msp_llm_error_http")

            assertTrue("$locale 的状态码占位符必须是 %1\$d，实际是：$template", template.contains("%1\$d"))
        }
    }

    @Test
    fun `模型名字与说明里不写死体积`() {
        // 这是本文件顶部那条约定的落点：体积是「这个文件到底多大」的衍生品，
        // 写进文案就等于把一份从别处能算出来的数据手抄了一遍。上游换个量化版本，
        // 文案就成了假话，而且不会有人发现（界面上的体积由 `sizeText()` 现算）。
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val texts = strings(locale)

            listOf("msp_llm_model_qwen3_name", "msp_llm_model_qwen3_desc").forEach { key ->
                val value = texts.getValue(key)

                listOf("MB", "GB", "TB", "约 ", "about ").forEach { needle ->
                    Assert.assertFalse("$locale 的 $key 里不该出现「$needle」：$value", value.contains(needle))
                }
            }
        }
    }

    @Test
    fun `六档失败提示两两不同`() {
        // `LlmErrorsTest` 只钉住了「六档各有各的资源 id」。id 不同而**文本相同**
        // 是完全可能的（复制粘贴一处忘记改），那时六档提示就等于只有一句
        // 「模型操作失败」，而「下一步做什么」正是分档的全部意义。
        val keys = listOf(
            "msp_llm_error_network",
            "msp_llm_error_http",
            "msp_llm_error_source",
            "msp_llm_error_size",
            "msp_llm_error_hash",
            "msp_llm_error_write",
            "msp_llm_error_unknown",
        )

        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val texts = strings(locale)
            val values = keys.map { texts.getValue(it) }

            Assert.assertEquals("$locale 里有两条失败提示的文本一模一样", values.size, values.toSet().size)
        }
    }

    @Test
    fun `说明里必须点出本机运行的代价`() {
        // 用户是在「云端（快、准、要联网）」和「本机（不联网、慢、长句差）」之间选。
        // 说明里只说好处（不联网、不花钱）而不提译文质量的差别，等于让他在
        // 试过之后才知道——那时他已经下完 345 MB 了。
        val desc = strings("values").getValue("msp_llm_model_qwen3_desc")

        assertTrue("要提到不联网", desc.contains("不联网"))
        assertTrue("也要提到译文质量的差别", desc.contains("不如"))
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun strings(locale: String): Map<String, String> = readResource(
        File(repoRoot(), "core/llm/src/main/res/$locale/strings.xml"),
    )

    private fun readResource(file: File): Map<String, String> {
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }
    }

    /** 模拟 aapt2 对文本的处理：首尾空白会被裁掉，除非值被一对双引号包住。 */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1)
        } else {
            raw.trim()
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
}
