package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.text.MspText
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 失败文案的单元测试。
 *
 * 这里锁的核心是**两条方向相反的补救建议不能混**：
 * 形状不对 ⇒ 换模型/换服务商；预算不够 ⇒ 换单次输出上限更高的模型。
 * 文案一旦合并，用户就会用错药，而且从界面上看不出自己用错了。
 *
 * 断言方式：`describeTranslationFailure` 返回的是 [MspText]（「哪一条 + 什么参数」），
 * 而这里拿不到 `Resources`（JVM 单测里 `getString` 不可用），所以
 * - 分支选择：断言资源 id；
 * - 参数：把整棵树摊成「用到了哪些 id / 传了哪些值」再断言；
 * - 文案本身写得好不好：读 `values/strings.xml`，直接对文本下断言。
 *
 * 注意 `assertEquals` 的参数顺序是「期望值、实际值、消息」——
 * 顺序写反时 Kotlin 会掉到 `assertEquals(Double, Double, tolerance)` 那个重载上，
 * 报出来的是「期望 Double」这种看不懂的错。
 */
class TranslationFailureTextTest {

    /**
     * 全部失败档。
     *
     * 加档时**必须**加进这个列表：`每一档都有话说` 是遍历它跑的，
     * 漏了一档就等于那一档的文案从来没被检查过——而新档恰恰是最容易只写一半的
     * （本地的三档就是后来才补上的，`when` 里加了分支，这个列表里却还没有它们）。
     */
    private fun allFailures(): List<TranslationFailure> = listOf(
        TranslationFailure.NotConfigured(MissingConfigItem.BASE_URL),
        TranslationFailure.Unauthorized(401, "invalid_api_key"),
        TranslationFailure.QuotaExceeded(429, "insufficient balance"),
        TranslationFailure.RateLimited(429, 30L, "too many requests"),
        TranslationFailure.Rejected(404, "model not found"),
        TranslationFailure.ServerError(503, "upstream error"),
        TranslationFailure.Network("IOException: Connection reset"),
        TranslationFailure.BadResponse("缺少 translations 字段"),
        TranslationFailure.EmptyCompletion("length", 2048, 2048, "content 为空"),
        TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"),
        TranslationFailure.LocalModelMissing("qwen3-0.6b"),
        TranslationFailure.LocalEngineUnavailable("load failed"),
        TranslationFailure.LocalGenerationFailed("empty output"),
    )

    @Test
    fun `每一档都有话说而且都带下一步`() {
        allFailures().forEach { failure ->
            val name = failure::class.simpleName
            val text = describeTranslationFailure(failure)

            // 两个参数类型不完全相同时（`Plain` vs `MspText`）泛型推不出来，
            // Kotlin 会掉到 `assertNotEquals(Double, Double, Double)` 那个重载上，
            // 报出一句「期望 Double」的看不懂的错。所以这里显式给类型参数。
            assertNotEquals<MspText?>(MspText.Plain(""), text.message, "$name 没有消息")
            assertNotNull("$name 没有建议", text.hint)
            assertNotEquals<MspText?>(MspText.Plain(""), text.hint, "$name 的建议是空的")
            // 消息必须是资源：写死一句话就等於在英文界面里招供出中文。
            assertTrue("$name 的消息不是资源", text.message is MspText.Res)
        }
    }

    @Test
    fun `形状不对与预算不够必须给出不同的建议`() {
        val bad = describeTranslationFailure(TranslationFailure.BadResponse("缺少 translations 字段"))
        val truncated = describeTranslationFailure(TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"))

        assertNotEquals(truncated.message, bad.message, "两档的结论不能长得一样")
        assertNotEquals(truncated.hint, bad.hint, "两档的补救方向相反，建议不能一样")
        assertEquals(
            MspText.Res(R.string.msp_translate_fail_bad_response_hint),
            bad.hint,
            "形状不对给的是「换模型/缩小批次」那一支",
        )
        assertEquals(
            R.string.msp_translate_fail_truncated_hint,
            (truncated.hint as MspText.Res).id,
            "预算不够给的是「调大预算/缩小批次」那一支",
        )
        assertTrue(
            "预算不够要给出当前的上限值",
            truncated.hint.flat().values.contains(2048),
        )
    }

    @Test
    fun `形状不对与预算不够的资源文案也不一样`() {
        val bad = resource("msp_translate_fail_bad_response_hint")
        val truncated = resource("msp_translate_fail_truncated_hint")

        assertNotEquals(truncated, bad, "两句话不能合并")
        assertTrue("形状不对要指向模型", bad.contains("模型"))
        assertTrue("预算不够要指向单次上限更高的模型", truncated.contains("模型"))
        assertTrue("预算不够要报出当前上限", truncated.contains("%1\$d"))
    }

    @Test
    fun `预算档的估算值必须真的用得上`() {
        // 曾经把 `%1$d`（上限）和 `%1$s`（估算片段）写成同一个索引，
        // 结果估算值被整段丢掉：不报错、不报警，只是屏幕上少一句话。
        val text = describeTranslationFailure(TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"))
        val values = text.hint!!.flat().values

        assertTrue("上限要显示", values.contains(2048))
        assertTrue("估算值不能被丢掉", values.contains(4096))
        assertTrue("文案里得留出第二个参数的位置", resource("msp_translate_fail_truncated_hint").contains("%2\$s"))
    }

    @Test
    fun `空输出要指出是推理预算被吃掉`() {
        val text = describeTranslationFailure(
            TranslationFailure.EmptyCompletion("length", 2048, 2048, "content 为空"),
        )

        assertTrue("要带上 finish_reason", text.message.flat().values.contains("length"))
        assertTrue(
            "要带上 finish_reason 的标签",
            text.message.flat().ids.contains(R.string.msp_translate_fail_reason),
        )
        assertTrue("要指出思考花了多少", text.hint!!.flat().values.contains(2048))
    }

    @Test
    fun `建议里点名当前服务商和模型`() {
        val text = describeTranslationFailure(
            TranslationFailure.Rejected(404, "model not found"),
            providerName = TranslationServices.MOONSHOT.displayName,
            model = "kimi-k2.6",
        )

        assertTrue(
            "换模型之前得先知道现在用的是哪个",
            text.hint!!.flat().ids.contains(R.string.msp_translate_svc_moonshot_name),
        )
        assertTrue("模型要按名字点出来", text.hint!!.flat().values.contains("kimi-k2.6"))
        assertTrue(
            "要说明这是「当前」值",
            text.hint!!.flat().ids.contains(R.string.msp_translate_current_of),
        )
    }

    @Test
    fun `没填服务商和模型时不要编一个`() {
        val text = describeTranslationFailure(TranslationFailure.Unauthorized(401, "x"))

        assertFalse("没填就别写「当前…」", text.hint!!.flat().ids.contains(R.string.msp_translate_current_of))
        assertEquals("x", text.raw)
    }

    @Test
    fun `空白的服务商名字也当没填`() {
        val text = describeTranslationFailure(
            TranslationFailure.Rejected(404, "model not found"),
            providerName = MspText.Plain("   "),
            model = "",
        )

        assertFalse(text.hint!!.flat().ids.contains(R.string.msp_translate_current_of))
        assertFalse(text.hint!!.flat().ids.contains(R.string.msp_translate_current_provider))
    }

    @Test
    fun `厂商原文永远保留`() {
        val text = describeTranslationFailure(TranslationFailure.Unauthorized(401, "invalid_api_key"))

        assertEquals("invalid_api_key", text.raw)
        assertTrue(
            "用户只有看到状态码才能自己判断是不是 key 的问题",
            text.message.flat().values.contains(401),
        )
    }

    @Test
    fun `厂商什么都没说时不给空的原文块`() {
        val text = describeTranslationFailure(TranslationFailure.Unauthorized(401, "   "))

        assertNull("空串等于没有原文，界面据此不显示原文块", text.raw)
    }

    @Test
    fun `网络档把系统原文原样透出`() {
        val detail = "UnknownHostException: Unable to resolve host api.deepseek.com"
        val text = describeTranslationFailure(TranslationFailure.Network(detail))

        assertEquals(MspText.Plain(detail), text.hint)
        assertNull("网络档没有额外的厂商原文", text.raw)
    }

    @Test
    fun `网络档的三种补充说明互不相同`() {
        // 超时是等、DNS 是地址填错、明文被拦是要 adb reverse ——
        // 三件事的解法完全不同，共用一句话等于三件都没说。
        val hints = listOf(NetworkNote.TIMEOUT, NetworkNote.UNRESOLVED_HOST, NetworkNote.CLEARTEXT_BLOCKED)
            .map { describeTranslationFailure(TranslationFailure.Network("IOException: x", it)).hint }

        assertEquals(3, hints.toSet().size, "三种情况必须三句不同的话")
        hints.forEach { hint ->
            assertTrue("补充说明必须带上系统原文", hint!!.flat().values.contains("IOException: x"))
        }
    }

    @Test
    fun `限流档带上服务商要求的等待时间`() {
        val text = describeTranslationFailure(TranslationFailure.RateLimited(429, 30L, "too many requests"))

        assertTrue(text.hint!!.flat().values.contains(30L))
    }

    @Test
    fun `限流档没给等待时间也能给建议`() {
        val withWait = describeTranslationFailure(TranslationFailure.RateLimited(429, 30L, "x"))
        val without = describeTranslationFailure(TranslationFailure.RateLimited(429, null, "x"))

        assertNotEquals(withWait.hint, without.hint, "没给等待时间就该换一句话，而不是留一个空括号")
        assertEquals(MspText.Res(R.string.msp_translate_fail_rate_limited_hint), without.hint)
    }

    @Test
    fun `本地三档各有各的结论，而且与形状档预算档都不重合`() {
        val missing = describeTranslationFailure(TranslationFailure.LocalModelMissing("qwen3-0.6b"))
        val engine = describeTranslationFailure(TranslationFailure.LocalEngineUnavailable("load failed"))
        val generation = describeTranslationFailure(TranslationFailure.LocalGenerationFailed("empty output"))
        val bad = describeTranslationFailure(TranslationFailure.BadResponse("缺少 translations 字段"))
        val truncated = describeTranslationFailure(TranslationFailure.Truncated("length", 4096, 2048, "括号没闭合"))

        val messages = listOf(missing, engine, generation, bad, truncated).map { it.message }
        // ⚠️ 本文件里的 `assertEquals` 是 `kotlin.test` 的那一个（显式 import 压过了
        // `org.junit.Assert.*` 的星号导入），签名是「期望值、实际值、消息」——
        // 消息写在**最后**。写成 JUnit 的顺序会让 Kotlin 掉到
        // `assertEquals(Double, Double, Double)` 那个重载上，报一句「期望 Double」。
        assertEquals(messages.size, messages.toSet().size, "五档说了五句不同的话")
        assertEquals(
            R.string.msp_translate_fail_local_model,
            (missing.message as MspText.Res).id,
            "模型没下好给的是「去下载」那一支",
        )
        assertEquals(R.string.msp_translate_fail_local_engine, (engine.message as MspText.Res).id)
        assertEquals(R.string.msp_translate_fail_local_generation, (generation.message as MspText.Res).id)
    }

    @Test
    fun `模型没下好时报的是模型名字，不是设置里存的那个 id`() {
        // 数据层存的是 id（一会儿要当目录名用），用户认识的是「Qwen3 0.6B（本地）」。
        // 直接把 id 印出来，用户会去搜一个搜不到的型号——这正是两个名字
        // 必须在这一层（显文案这一步）才转换的理由。
        val text = describeTranslationFailure(TranslationFailure.LocalModelMissing("qwen3-0.6b"))
        val flat = text.message.flat()

        assertTrue(
            "要出现模型的名字",
            flat.ids.contains(com.multisuperplayer.core.llm.R.string.msp_llm_model_qwen3_name),
        )
        assertFalse("id 不该出现在界面上", flat.values.contains("qwen3-0.6b"))
    }

    @Test
    fun `认不出的模型 id 也报一个能认的名字`() {
        // 旧配置、被删掉的模型、手改过的设置都会走到这里。
        // 不兜底的话这句话会变成「本机模型「」还没下载好」——引号里空空如也。
        val text = describeTranslationFailure(TranslationFailure.LocalModelMissing("no-such-model"))

        assertTrue(
            "要回落到默认模型的名字",
            text.message.flat().ids.contains(com.multisuperplayer.core.llm.R.string.msp_llm_model_qwen3_name),
        )
    }

    @Test
    fun `引擎起不来时要明说下载解决不了`() {
        // 这一档与「模型没下好」分开，就是因为下一步**相反**：
        // 一个去下载，一个下载再多次也没用。文案里含糊其辞的话，
        // 用户会反复删了重下 345 MB。
        val engine = resource("msp_translate_fail_local_engine_hint")
        val missing = resource("msp_translate_fail_local_model_hint")

        assertNotEquals(engine, missing)
        assertTrue("模型档要指向设置里的下载入口，实际是：$missing", missing.contains("设置"))
        // 引擎档不能只是「不说下载」——它必须**主动**把「再下一次就好了」这个错觉
        // 按掉。用户刚下完 345 MB，第一反应必然是「是不是没下好」。
        assertTrue(
            "引擎档要点明下载解决不了，实际是：$engine",
            engine.contains("下载") && engine.contains("解决不了"),
        )
        assertTrue(
            "引擎档要给出此刻能做的事，实际是：$engine",
            engine.contains("重启") || engine.contains("云端"),
        )
    }

    @Test
    fun `本地生成失败要给出当前服务商与模型`() {
        // 本地档的 providerName 是「本地（设备上运行）」，用户据此确认
        // 自己确实在用本地那一家，而不是以为还在走云端
        val text = describeTranslationFailure(
            TranslationFailure.LocalGenerationFailed("empty output"),
            providerName = TranslationServices.LOCAL.displayName,
            model = "qwen3-0.6b",
        )

        assertTrue(
            "要点名当前服务商",
            text.hint!!.flat().ids.contains(R.string.msp_translate_svc_local_name),
        )
        assertTrue("要点名当前模型", text.hint.flat().ids.contains(R.string.msp_translate_current_model))
    }

    @Test
    fun `缺项清单的文案只有一份`() {
        // 设置页的清单和引擎的报错共用 describeMissingItem()：
        // 两处各写一遍的话，就会出现「设置页说可以翻译、点下去说缺东西」。
        MissingConfigItem.entries.forEach { item ->
            val label = describeMissingItem(item)
            assertTrue("$item 的文案必须是一个资源", label is MspText.Res)
            assertEquals(
                when (item) {
                    MissingConfigItem.BASE_URL -> R.string.msp_translate_missing_base_url
                    MissingConfigItem.BASE_URL_SCHEME -> R.string.msp_translate_missing_base_url_scheme
                    MissingConfigItem.MODEL -> R.string.msp_translate_missing_model
                    MissingConfigItem.API_KEY -> R.string.msp_translate_missing_api_key
                    MissingConfigItem.BATCH_SIZE -> R.string.msp_translate_missing_batch_size
                },
                (label as MspText.Res).id,
                "$item 指向了别的资源",
            )
        }
    }

    @Test
    fun `缺项清单拼接用语言自己的分隔符`() {
        val two = describeMissingItems(listOf(MissingConfigItem.BASE_URL, MissingConfigItem.API_KEY))

        assertEquals(
            MspText.Res(
                R.string.msp_translate_join_list,
                MspText.Res(R.string.msp_translate_missing_base_url),
                MspText.Res(R.string.msp_translate_missing_api_key),
            ),
            two,
            "分隔符得跟着语言走，不能用 joinToString 写死",
        )
        assertEquals(
            MspText.Plain(""),
            describeMissingItems(emptyList()),
            "什么都不缺时给空文本，调用方据此判断",
        )
    }

    @Test
    fun `引擎的缺项报错能落到具体那一项上`() {
        val failure = TranslationEngine.validateConfig(
            TranslationConfig(
                baseUrl = "api.deepseek.com",
                apiKey = null,
                model = "m",
                target = TranslationTarget.DEFAULT,
            ),
        )

        assertEquals(TranslationFailure.NotConfigured(MissingConfigItem.BASE_URL_SCHEME), failure)
        val text = describeTranslationFailure(requireNotNull(failure))
        assertTrue(
            "报错里要出现那一项的名字，而不是一句「请检查设置」",
            text.message.flat().ids.contains(R.string.msp_translate_missing_base_url_scheme),
        )
    }

    @Test
    fun `英文资源里不能残留中文`() {
        // 「界面能切英文」这件事最容易在这里破功：漏翻一两句，切过去才发现。
        val offenders = readResource("values-en")
            .filterValues { value -> value.any { it.isCjk() } }
            .keys

        assertEquals(emptySet<String>(), offenders, "这些键的英文文案里还有中日韩字符")
    }

    @Test
    fun `三种语言的关键字集合完全一致`() {
        val base = readResource("values").keys
        assertEquals(base, readResource("values-en").keys, "英文少了键")
        assertEquals(base, readResource("values-b+zh+Hant").keys, "繁体少了键")
    }

    // ===== 下面是给上面那些断言用的工具 =====

    /** 一棵 [MspText] 树摊平后的样子：用到了哪些资源 + 传了哪些值参数。 */
    private class FlatTree(val ids: List<Int>, val values: List<Any?>)

    /**
     * 把 [MspText] 摊平。
     *
     * 单独分出 ids 和 values，是因为两者类型不同：混在一个列表里
     * 就只能靠「id 都是大整数」这种巧合去过滤值参数。
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

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 读某个语言的 `strings.xml`：键 → 文本。 */
    private fun readResource(dir: String): Map<String, String> {
        val file = File(repoRoot(), "core/translate/src/main/res/$dir/strings.xml")
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }
    }

    /**
     * 模拟 aapt2 对文本的处理：首尾空白会被裁掉，除非值被一对双引号包住
     * （Android 文档里的「保留空白」写法），那时被裁掉的是引号本身。
     *
     * 少了这一步，测试读到的是 XML 原文，`> · <` 在设备上被烫成 `·` 这类
     * 只能靠眼睛看出来的问题就永远漏掉了。
     */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1)
        } else {
            raw.trim()
        }

    private fun resource(key: String): String =
        readResource("values")[key] ?: error("values/strings.xml 里没有 $key")

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
