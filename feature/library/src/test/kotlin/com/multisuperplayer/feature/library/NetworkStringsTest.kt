package com.multisuperplayer.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网络地址」页的**界面文案**守卫。
 *
 * 这一页真正会算错的东西都在 `core:data` 里（`RemoteUrlRulesTest` 守着），
 * 界面层剩下的是文案——而这里有三条只有文案才能撑住的东西：
 *
 * 1. **键只写了一种语言**。资源缺失时 Android 不报错，它把**键名本身**显示出来
 *    （`msp_network_history`）。漏在 `values-en` 里最坏：中文界面完全正常，
 *    只有英文用户看到一串下划线。
 * 2. **「还没写完」与「写错了」被写成同一句**。这两句共用输入框下面**同一个位置**
 *    （`supportingText`），说的是同一件事的两面：前者是「继续敲」，后者是「改」。
 *    合并成一句之后，用户刚敲到 `h` 就被判成错，而真正敲错时又看不出哪里错。
 *    单测能抓到的正是「两句话是不是同一句」——这一页没有别的东西能抓到它。
 * 3. **提示与错误没有说出真实的判据**。用户填 `192.168.1.5/video.mp4`（省略了协议）
 *    是**合法**的（会被补成 `http://192.168.1.5/video.mp4`），而 `TestClip.mp4`
 *    不是。文案里只写「以 http:// 或 https:// 开头」就把前者也说成了错，
 *    用户于是会把一条本来能放的地址改掉；只写「地址无效」又等于让他猜。
 *    这两个坑都只能用断言把「协议可以省」这半句钉在文案里（见
 *    `提示要说明协议可以省略`）。
 *
 * 断言方式沿用 `PlaylistImportStringsTest`：纯 JVM 单测拿不到 `Resources`，
 * 所以直接读 `strings.xml` 的文本，按**键**比对而不是按位置。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」。
 */
class NetworkStringsTest {

    /**
     * 这一页引入的键。写死在这里而不是从 `R.string` 反射出来：
     * 漏一个键正是要抓的错，靠反射就永远抓不到。
     */
    private val networkKeys = listOf(
        "msp_network_title",
        "msp_network_source_desc",
        "msp_network_input_label",
        "msp_network_input_hint",
        "msp_network_invalid",
        "msp_network_play",
        "msp_network_history",
        "msp_network_empty_title",
        "msp_network_empty_desc",
        "msp_network_remove",
    )

    private val locales = listOf(DIR_ZH, DIR_EN, DIR_HANT)

    // ===== 键集与完整性 =====

    @Test
    fun `三种语言里这十个键一个都不少`() {
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            networkKeys.forEach { key ->
                assertTrue("$dir 里缺少 $key（缺失时界面会直接把键名显示给用户）", strings.containsKey(key))
            }
        }
    }

    @Test
    fun `十个键在三种语言里都不为空`() {
        // 空串在界面上是「什么都没有」：图标按钮没有朗读文本、错误提示行看起来像没渲染出来。
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            networkKeys.forEach { key ->
                assertTrue("$dir 的 $key 是空的", strings.getValue(key).isNotBlank())
            }
        }
    }

    @Test
    fun `英文资源里没有残留中文`() {
        // 只查 values-en 里的 CJK：繁中**本来就该**是汉字，把它也查一遍会直接把
        // 一整份正确的资源判成错的——这种守卫写错了比没写更坏。
        val cjk = Regex("[\\u4e00-\\u9fff]")

        networkKeys.forEach { key ->
            val value = stringsOf(DIR_EN).getValue(key)
            assertEquals(
                "values-en 里的 $key 还留着中文，英文用户会看到半句中文",
                emptyList<String>(),
                cjk.findAll(value).map { it.value }.toList(),
            )
        }
    }

    @Test
    fun `繁体不是把简体照抄过来`() {
        // 这十个键里繁体确实与简体逐字相同的只有「播放」这一类，所以只要
        // **存在**一处不同就说明这一份是认真写过的。
        // 反过来（十个键全都一字不差）就是照抄，而照抄出来的繁体在
        // 「网络 / 網络 / 網路」这种最显眼的词上就已经错了。
        val zh = stringsOf(DIR_ZH)
        val hant = stringsOf(DIR_HANT)

        val identical = networkKeys.filter { zh.getValue(it) == hant.getValue(it) }
        assertTrue(
            "values-b+zh+Hant 里的这十个键与简体逐字相同（$identical），像是照抄过来的",
            identical.size < networkKeys.size,
        )
    }

    // ===== 输入框下面的两句话 =====

    @Test
    fun `提示与错误不是同一句话`() {
        // 两句共用输入框下面同一个位置：提示是「继续敲」，错误是「改」。
        // 写成同一句之后，用户刚敲到第一个字符就会被判成错。
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里输入提示与错误文案写成了同一句，用户分不出「还没写完」和「写错了」",
                strings.getValue("msp_network_invalid"),
                strings.getValue("msp_network_input_hint"),
            )
        }
    }

    @Test
    fun `提示与错误都必须写出 http 与 https`() {
        // 用户填 `192.168.1.5/video.mp4`（忘了协议）时，这句是唯一能让他自纠的信息。
        // 只写「地址无效」等于让他猜；只写一个前缀则会把 https 的用户也赶去改。
        listOf("msp_network_input_hint", "msp_network_invalid").forEach { key ->
            locales.forEach { dir ->
                val value = stringsOf(dir).getValue(key)
                assertTrue("$dir 的 $key 里没提到 http", value.contains("http://"))
                assertTrue("$dir 的 $key 里没提到 https", value.contains("https://"))
            }
        }
    }

    @Test
    fun `提示要说明协议可以省略`() {
        // `normalize` 里「省略协议 + 像域名」是**照收**的一支（`RemoteUrlRulesTest`
        // 的 `省略协议时主机名必须像域名` 钉着），所以文案必须给这条留出口：
        // 只写「以 http:// 或 https:// 开头」的话，把 `nas.local/a.mp4` 填进来
        // 的人会以为它不合法（提示虽然没变红，但写着「必须以…开头」），
        // 然后把一条本来能放的地址改坏。
        val words = mapOf(
            DIR_ZH to "域名",
            DIR_EN to "domain",
            DIR_HANT to "網域",
        )

        words.forEach { (dir, word) ->
            listOf("msp_network_input_hint", "msp_network_invalid").forEach { key ->
                val value = stringsOf(dir).getValue(key)
                assertTrue(
                    "$dir 的 $key 里没提「可以只写域名」，与 normalize 的行为不符",
                    value.contains(word),
                )
            }
        }
    }

    @Test
    fun `错误文案要区分出「这不是一个地址」`() {
        // 「无效」这个词太泛：网络地址页最坏的理解是「网络不通」，
        // 于是用户会去查 NAS、查 Wi-Fi，而真正的问题只是少打了 `http://`。
        // 三个语言里各取一个必须出现在句中的词，钉住这件事。
        val words = mapOf(
            DIR_ZH to "地址",
            DIR_EN to "address",
            DIR_HANT to "網址",
        )

        words.forEach { (dir, word) ->
            val value = stringsOf(dir).getValue("msp_network_invalid")
            assertTrue("$dir 的 msp_network_invalid 里没有点明这是「地址」本身的问题", value.contains(word))
        }
    }

    // ===== 空态 =====

    @Test
    fun `空态的标题与描述不是同一句`() {
        // 「还没有播放过网络地址」与「在上面填一个链接就能直接播放」两句缺一不可：
        // 只有标题是死胡同，只有描述则看不出这是空态还是加载失败。
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里空态的标题和描述写成了同一句",
                strings.getValue("msp_network_empty_desc"),
                strings.getValue("msp_network_empty_title"),
            )
        }
    }

    @Test
    fun `空态标题与分组标题不是同一句`() {
        // 「最近用过」是列表**常显**的分组标题，空态标题是列表为空时的占位。
        // 两者可能同屏出现（标题画在列表外面），写成同一句的话，
        // 空列表上会出现两行一模一样的字，看起来像渲染重复了。
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里「最近用过」与空态标题写成了同一句",
                strings.getValue("msp_network_empty_title"),
                strings.getValue("msp_network_history"),
            )
        }
    }

    // ===== 两个图标按钮 =====

    @Test
    fun `播放与移除不是同一句话`() {
        // 两个都是图标按钮，朗读文本（`contentDescription`）是视障用户唯一的线索：
        // 一个在输入框右边（开始播放），一个在列表行右边（把这条从历史里划掉）。
        // 同名字母上就是「点进去播不了、还从列表里消失了」也说不清是哪个按钮干的。
        locales.forEach { dir ->
            val strings = stringsOf(dir)
            assertNotEquals(
                "$dir 里「播放」与「从列表里移除」写成了同一句",
                strings.getValue("msp_network_remove"),
                strings.getValue("msp_network_play"),
            )
        }
    }

    // ===== 工具 =====

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

        val cache = mutableMapOf<String, Map<String, String>>()
    }
}
