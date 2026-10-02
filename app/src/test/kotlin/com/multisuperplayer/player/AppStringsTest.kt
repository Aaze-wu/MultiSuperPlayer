package com.multisuperplayer.player

import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:app` 自己的三套文案。
 *
 * 这个模块里的文案几乎全是**迷你播放器**的：上一首 / 播放 / 暂停 / 下一首。
 * 这里的键有一个隐蔽点——`android.nonTransitiveRClass=true` 之后，
 * `:app` 引不到别的模块的 `R.string`（`feature:player` 里明明有
 * `msp_player_previous`），所以每加一个按钮都要在**本模块**再写一遍。
 * 而「又写一遍」意味着它同样可能只写进两套语言，第三套静默回落成中文。
 *
 * 少一条不会崩、不会报错，只有把语言切过去盯着看才会发现；所以这里用
 * **比对键名集合**把它变成一条会红的断言。
 */
class AppStringsTest {

    @Test
    fun `迷你播放器的四个按钮三种语言都在`() {
        // 单独再点一遍这四个键（集合比对已经覆盖，但它的报错是「少了某个键」，
        // 不会告诉你少的是哪个按钮）。迷你播放器的按钮**没有图标文字的兜底**：
        // 一个没有 contentDescription 的图标按钮，读屏用户听到的是「按钮」两个字。
        val expected = setOf(
            "msp_mini_player_play",
            "msp_mini_player_pause",
            "msp_mini_player_previous",
            "msp_mini_player_next",
        )

        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val keys = strings(locale).keys
            Assert.assertEquals("$locale 少了迷你播放器的键", expected, expected.intersect(keys))
        }
    }

    @Test
    fun `三种语言的键集合完全一致（除刻意只留基线的）`() {
        val base = strings("values").keys - baselineOnly

        Assert.assertEquals("英文少了键", base, strings("values-en").keys)
        Assert.assertEquals("繁体少了键", base, strings("values-b+zh+Hant").keys)
    }

    @Test
    fun `英文资源里不能残留中日韩字符`() {
        // 全角标点也算：中文标点出现在英文句子里同样说明这句没翻。
        val offenders = strings("values-en")
            .filterValues { value -> value.any { it.isCjk() } }
            .keys

        Assert.assertEquals("这些键的英文文案里还有中日韩字符", emptySet<String>(), offenders)
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun strings(locale: String): Map<String, String> = readResource(
        File(repoRoot(), "app/src/main/res/$locale/strings.xml"),
    )

    private fun readResource(file: File): Map<String, String> {
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
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
        /**
         * 故意只存在于基线语言里的键。
         *
         * `app_name` 三种语言都是同一个词，多两份副本除了会跟着改错之外没有作用
         * （见 `values/strings.xml` 里的注释）。这里是一个**有名字的**例外，
         * 不是「少的那条就算了」——想加新例外就得改这一行，顺手要想一遍。
         */
        val baselineOnly: Set<String> = setOf("app_name")
    }
}
