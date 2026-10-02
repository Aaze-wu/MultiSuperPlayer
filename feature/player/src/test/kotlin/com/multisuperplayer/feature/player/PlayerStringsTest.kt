package com.multisuperplayer.feature.player

import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放模块的三套文案。
 *
 * 为什么播放模块单独要这一份检查：这一页是全应用按钮最密的地方（收起、字幕与歌词、
 * 全屏、倍速、比例、A-B、锁定…），而这些按钮**几乎全靠文字**表达自己。
 * 于是「英文/繁体里少一条」的后果不是排版上少一行，而是：
 *
 * - 少的那条会**静默回落到中文**（Android 的行为），英文界面里突然冒出一句中文；
 * - 或者干脆显示成一个空按钮（原生控件那一侧），用户看到一个没有字的圆点。
 *
 * 两种都不会崩、都不会报错，只有切换语言肉眼盯着看才会发现。所以这里用**比对键名集合**
 * 的方式把它变成一条会红的断言。
 *
 * 这里只读文件、不渲染：渲染成句子是界面层的事（`MspText` + `stringResource`），
 * JVM 单测里拿不到 `Resources`。文案**好不好读**不在这里管，这里只管「在不在」。
 */
class PlayerStringsTest {

    @Test
    fun `三种语言的关键字集合完全一致`() {
        val base = strings("values").keys

        Assert.assertEquals("英文少了键", base, strings("values-en").keys)
        Assert.assertEquals("繁体少了键", base, strings("values-b+zh+Hant").keys)
    }

    @Test
    fun `英文资源里不能残留中文`() {
        // 「界面能切英文」这件事最容易在这里破功：漏翻一两句，切过去才发现。
        // 全角标点（`，`、`：`）也算：中文标点出现在英文句子里同样说明这句没翻。
        val offenders = strings("values-en")
            .filterValues { value -> value.any { it.isCjk() } }
            .keys

        Assert.assertEquals("这些键的英文文案里还有中日韩字符", emptySet<String>(), offenders)
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun strings(locale: String): Map<String, String> = readResource(
        File(repoRoot(), "feature/player/src/main/res/$locale/strings.xml"),
    )

    private fun readResource(file: File): Map<String, String> {
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }
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
