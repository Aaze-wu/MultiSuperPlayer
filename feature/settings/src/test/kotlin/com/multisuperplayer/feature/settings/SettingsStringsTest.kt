package com.multisuperplayer.feature.settings

import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置模块的三套文案。
 *
 * 为什么设置模块也需要这一份检查：这个模块的文案**全都是界面本身**——
 * 每一行设置的名字、说明、每一句帮助文字都在这儿，没有任何别的地方兜底。
 * 少一条的后果和播放模块一样安静：
 *
 * - 英文/繁体里缺的那条**静默回落到中文**，切过去就是一句中文夹在英文里；
 * - 或者在某些控件上变成一个空行（标题是空串，行高还在）。
 *
 * 两种都不会崩、不会报错，只有把三种语言都切一遍肉眼盯着看才发现。这里把它
 * 变成一条会红的断言。
 *
 * 只读文件、不渲染：文案好不好读不在这里管（那要靠人读），这里只管三件事——
 * **在不在、是不是真翻了、首尾空白有没有被 aapt2 吃掉**。
 */
class SettingsStringsTest {

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
    fun `首尾有空白的时候必须用引号包住`() {
        // aapt2 会把 `<string>` 值的首尾空白裁掉。想让「` · `」这样的分隔符活下来，
        // 唯一写法是整个值用一对双引号包住。漏掉引号的结果是设备上少一个空格，
        // 而 XML 原文看起来完全正常——所以在 XML 层面查，不靠肉眼。
        //
        // 注意只查 ASCII 空白：全角空格（U+3000）aapt2 不裁，那种写法本来就没问题，
        // 判进来的话会让中文那份报一个假错误。
        val offenders = mutableListOf<String>()
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            rawValues(locale).forEach { (key, raw) ->
                val hasSpace = raw.firstOrNull()?.isAsciiSpace() == true ||
                    raw.lastOrNull()?.isAsciiSpace() == true
                val quoted = raw.length >= 2 && raw.first() == '"' && raw.last() == '"'
                if (hasSpace && !quoted) offenders += "$locale/$key"
            }
        }

        Assert.assertEquals("这些键的首尾空白会被 aapt2 裁掉", emptyList<String>(), offenders)
    }

    private fun Char.isAsciiSpace(): Boolean = this == ' ' || this == '\t' || this == '\n'

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本（已按 aapt2 的规则处理过）。 */
    private fun strings(locale: String): Map<String, String> =
        rawValues(locale).mapValues { (_, raw) -> unquote(raw) }

    /** 本模块某一语言的 `strings.xml`：键 → XML 里的原文（**不做**任何处理）。 */
    private fun rawValues(locale: String): Map<String, String> =
        readResource(File(repoRoot(), "feature/settings/src/main/res/$locale/strings.xml"))

    private fun readResource(file: File): Map<String, String> {
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * 模拟 aapt2 对文本的处理：首尾空白会被裁掉，除非值被一对双引号包住
     * （Android 文档里的「保留空白」写法），那时被裁掉的是引号本身。
     *
     * 少了这一步，测试读到的是 XML 原文，而设备上看到的是被裁过的版本。
     */
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
