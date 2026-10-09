package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「删除语音识别缓存」那一行的三句话。
 *
 * 三句话各自都有一种「看着对」的写法，而且都不会崩、不会有日志：
 *
 * - 失败只说「清空失败」⇒ 用户以为清掉了，而字幕还在、磁盘也没释放；
 * - 空缓存时还说「已缓存 0 份 · 0 B」⇒ 一句废话，还看不出是「没缓存」还是「没读出来」；
 * - 确认句不写「模型不会被删掉」⇒ 刚下完 190 MB 模型的人不敢按这个按钮，
 *   而按下的邻居就是「删除模型」。
 *
 * 断言方式与 `TranslationCacheTextTest` 一致：纯 JVM 单测拿不到 `Resources`，
 * 所以分支选择断言资源 id、参数把整棵树摊平后再断言、文案本身读 `values/strings.xml`。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」，
 * 与 `kotlin.test` 那个相反（写反了会掉到 `assertEquals(Double, Double, tolerance)` 重载上）。
 */
class GeneratedSubtitleCacheTextTest {

    @Test
    fun `没有缓存时不报「已缓存 0 份」`() {
        val empty = GeneratedSubtitleCacheStats(entries = 0, bytes = 0L)

        assertTrue("0 份必须走空状态那一句", empty.isEmpty)
        assertEquals(
            "空状态是一句独立的话，不是一个填了 0 的统计句",
            MspText.Res(R.string.msp_asr_cache_empty),
            empty.describe(),
        )
    }

    @Test
    fun `有缓存时份数与体积都要出现在句子里`() {
        val bytes = 2_400_000L
        val stats = GeneratedSubtitleCacheStats(entries = 42, bytes = bytes)

        assertTrue("有缓存就不能再说「还没有缓存」", !stats.isEmpty)
        val describe = stats.describe()
        assertTrue("份数必须说出来", describe.flat().values.contains(42))
        assertTrue("体积必须说出来", describe.flat().values.contains(TimeFormat.fileSize(bytes)))
        assertNotEquals(
            "有缓存和没缓存不能是同一句话",
            MspText.Res(R.string.msp_asr_cache_empty),
            describe,
        )
    }

    @Test
    fun `清空失败必须报出还剩多少`() {
        // 失败时那两句话用的还是「剩下的量」：这个类在失败之后被重新统计过一次，
        // 所以界面拿到的是「还剩几份、还占多少」——也就是用户真正要看的东西。
        val stats = GeneratedSubtitleCacheStats(entries = 7, bytes = 3_100_000L)
        val failed = stats.describeClearFailure()

        assertNotEquals("失败与正常统计不能长得一样，否则用户看不出失败了", stats.describe(), failed)
        assertTrue("失败时也要说份数", failed.flat().values.contains(7))
        assertTrue("失败时也要说体积", failed.flat().values.contains(TimeFormat.fileSize(3_100_000L)))
    }

    @Test
    fun `确认句与统计句不是同一句`() {
        val stats = GeneratedSubtitleCacheStats(entries = 5, bytes = 4_096L)

        assertNotEquals(
            "确认句要讲代价与不会删的东西，不能只是把统计句搬进对话框",
            stats.describe(),
            stats.describeClearConfirmation(),
        )
        assertTrue(
            "确认句里也要有数量，否则确认对话框只是让人多点一下",
            stats.describeClearConfirmation().flat().values.contains(5),
        )
    }

    @Test
    fun `确认句必须交代模型不会被删掉`() {
        // 这一条只能读文案本身：它是给用户看的承诺，而承诺漏了字没有任何编译期信号。
        // 「模型」在三种语言里各是一个词，所以逐语言对各自的词下手。
        val modelWord = mapOf(
            "values" to "模型",
            "values-en" to "model",
            "values-b+zh+Hant" to "模型",
        )

        modelWord.forEach { (dir, word) ->
            val text = readResource(dir).getValue("msp_asr_cache_clear_confirm")
            assertTrue(
                "$dir：确认句里必须说明识别模型不会被删掉（缺了它，刚下完模型的人不敢按）",
                text.contains(word),
            )
        }
    }

    @Test
    fun `确认句必须交代要重新跑一遍识别`() {
        // 「删掉之后会怎样」是用户按下去会后悔的那个点，三种语言都要说。
        val rerunWord = mapOf(
            "values" to "重新",
            "values-en" to "again",
            "values-b+zh+Hant" to "重新",
        )

        rerunWord.forEach { (dir, word) ->
            val text = readResource(dir).getValue("msp_asr_cache_clear_confirm")
            assertTrue("$dir：确认句要说明删掉之后得重新跑一遍识别", text.contains(word))
        }
    }

    // ===== 下面是给上面那些断言用的工具（与 TranslationCacheTextTest 同款） =====

    /** 一棵 [MspText] 摊平后的样子：用到了哪些资源 + 传了哪些值参数。 */
    private class FlatTree(val ids: List<Int>, val values: List<Any?>)

    /**
     * 把 [MspText] 摊平。
     *
     * id 和值参数分开装：混在一个列表里就只能靠「id 都是大整数」这种巧合去过滤。
     */
    private fun MspText.flat(): FlatTree {
        val ids = mutableListOf<Int>()
        val values = mutableListOf<Any?>()

        fun walk(node: MspText) {
            when (node) {
                is MspText.Plain -> values += node.text
                is MspText.Res -> {
                    ids += node.id
                    node.args.forEach { arg -> if (arg is MspText) walk(arg) else values += arg }
                }
            }
        }

        walk(this)
        return FlatTree(ids, values)
    }

    /** 读某个语言的 `strings.xml`：键 → 文本（首尾空白按 aapt2 的规则处理）。 */
    private fun readResource(dir: String): Map<String, String> {
        val file = File(repoRoot(), "core/data/src/main/res/$dir/strings.xml")
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
}
