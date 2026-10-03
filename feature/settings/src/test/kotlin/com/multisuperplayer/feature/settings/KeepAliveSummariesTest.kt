package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.power.DeviceVendor
import com.multisuperplayer.core.data.power.KeepAliveState
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * 「后台保活」页每一行的文案。
 *
 * 这一页要说清两件互相独立的事：**电池优化白名单**（那个开关）和
 * **厂商后台管理**（那一行跳转）。测试盯的正是这个区分：
 *
 * - 两种白名单状态各说各的（和 `PermissionSummaries` 里「三态不能合成一句」同一条理由）；
 * - 九种厂商各有自己的标题**且互不相同**——用一句兜底串冒充具体厂商的后果是
 *   「按下去跳到一个不存在的页面」，而这里就是拦住它的地方；
 * - 「有厂商页」与「没有厂商页」的副标题是**两句**话：前者要说的
 *   「厂商那套不看上面那个白名单」在后者的场景下没有意义（本来就不去厂商页）。
 *
 * 另外这几句是这一页里唯一经过 [KeepAliveSummaries] 的；页面上的标题、
 * 帮助问号、说明那几句由 `KeepAliveScreen` 直接取 `R.string`，所以资源那一组
 * 测试改成管「这一片资源键有没有多出来的」。多出来的键意味着「写了没人用」，
 * 而没人用的字符串在多语言下是最容易只改一套的那种。
 */
class KeepAliveSummariesTest {

    // ------------------------------------------------------------ 入口页那一行

    @Test
    fun `入口摘要 - 在白名单里就说已加入`() {
        assertRes(
            R.string.msp_keep_alive_entry_on,
            KeepAliveSummaries.entry(KeepAliveState.UNRESTRICTED),
        )
    }

    @Test
    fun `入口摘要 - 不在白名单里要说可能被限制`() {
        assertRes(
            R.string.msp_keep_alive_entry_off,
            KeepAliveSummaries.entry(KeepAliveState.RESTRICTED),
        )
    }

    // ------------------------------------------------------------ 开关副标题

    @Test
    fun `开关副标题 - 两个状态各有一句`() {
        assertRes(
            R.string.msp_keep_alive_switch_on,
            KeepAliveSummaries.switchSubtitle(KeepAliveState.UNRESTRICTED),
        )
        assertRes(
            R.string.msp_keep_alive_switch_off,
            KeepAliveSummaries.switchSubtitle(KeepAliveState.RESTRICTED),
        )
    }

    // ------------------------------------------------------------ 厂商那一行

    @Test
    fun `厂商标题 - 九种厂商各有自己的标题`() {
        val expected = mapOf(
            DeviceVendor.XIAOMI to R.string.msp_keep_alive_vendor_xiaomi,
            DeviceVendor.HUAWEI to R.string.msp_keep_alive_vendor_huawei,
            DeviceVendor.HONOR to R.string.msp_keep_alive_vendor_honor,
            DeviceVendor.OPPO to R.string.msp_keep_alive_vendor_oppo,
            DeviceVendor.VIVO to R.string.msp_keep_alive_vendor_vivo,
            DeviceVendor.MEIZU to R.string.msp_keep_alive_vendor_meizu,
            DeviceVendor.SAMSUNG to R.string.msp_keep_alive_vendor_samsung,
            DeviceVendor.ONE_PLUS to R.string.msp_keep_alive_vendor_one_plus,
            DeviceVendor.OTHER to R.string.msp_keep_alive_vendor_other,
        )
        val actual = DeviceVendor.values().associateWith { vendor ->
            idOf(KeepAliveSummaries.vendorTitle(vendor))
        }

        Assert.assertEquals(expected, actual)
        // 「每种厂商都有自己的标题」这件事光靠上面的 map 断言不够：两条表项
        // 指向同一个 id 时 map 仍然相等，只有断言 id 的**个数**才能抓到。
        Assert.assertEquals(
            "有两种厂商用了同一条标题",
            expected.size,
            actual.values.toSet().size,
        )
    }

    @Test
    fun `厂商副标题 - 认得出厂商时说厂商那套不看白名单`() {
        DeviceVendor.values()
            .filter { vendor -> vendor != DeviceVendor.OTHER }
            .forEach { vendor ->
                assertRes(
                    vendor.toString(),
                    R.string.msp_keep_alive_vendor_note_page,
                    KeepAliveSummaries.vendorSubtitle(vendor),
                )
            }
    }

    @Test
    fun `厂商副标题 - 认不出厂商时说要退到应用信息`() {
        assertRes(
            R.string.msp_keep_alive_vendor_note_fallback,
            KeepAliveSummaries.vendorSubtitle(DeviceVendor.OTHER),
        )
    }

    @Test
    fun `厂商副标题 - 认得出和认不出说的不是同一句`() {
        // 一句兜底串套全场的写法在别的页上还能接受，在这里不行：
        // 认不出厂商时那一次跳转**不会**去厂商页，说「厂商那套不看白名单」就是错的。
        Assert.assertNotEquals(
            idOf(KeepAliveSummaries.vendorSubtitle(DeviceVendor.XIAOMI)),
            idOf(KeepAliveSummaries.vendorSubtitle(DeviceVendor.OTHER)),
        )
    }

    // ------------------------------------------------------------ 资源本身

    @Test
    fun `资源 - 这些键在三套文案里都在`() {
        val missing = LOCALES.flatMap { locale ->
            val strings = settingsStrings(locale)
            KEEP_ALIVE_KEYS.filterNot { key -> strings.containsKey(key) }.map { key -> "$locale/$key" }
        }

        Assert.assertEquals("这些键在某套文案里缺失", emptyList<String>(), missing)
    }

    @Test
    fun `资源 - 保活页的资源键和这份清单一一对应`() {
        val declared = settingsStrings("values").keys
            .filter { key -> key.startsWith("msp_keep_alive_") }
            .toSet()
        val listed = KEEP_ALIVE_KEYS.filter { key -> key.startsWith("msp_keep_alive_") }.toSet()

        Assert.assertEquals(
            "values/strings.xml 里多出来的保活键：写了没人用，或者忘了登记进这份清单",
            emptySet<String>(),
            declared - listed,
        )
        Assert.assertEquals(
            "这份清单里多出来的键：values/strings.xml 里没有对应的字符串",
            emptySet<String>(),
            listed - declared,
        )
    }

    @Test
    fun `资源 - 繁体文案里真的是中文而不是抄过来的简体`() {
        // 繁体那一套是手写的，最容易出现的错是整条从基线复制过来没改。
        val offenders = KEEP_ALIVE_KEYS.filterNot { key ->
            settingsStrings("values-b+zh+Hant")[key]?.any { char -> char.isCjk() } == true
        }

        Assert.assertEquals("这些键的繁体文案里没有汉字", emptyList<String>(), offenders)
    }

    @Test
    fun `资源 - 英文文案里没有汉字`() {
        // 反过来也要管：英文那套里混进汉字，说明某一行是从基线整条粘过来的。
        val offenders = KEEP_ALIVE_KEYS.filter { key ->
            settingsStrings("values-en")[key]?.any { char -> char.isCjk() } == true
        }

        Assert.assertEquals("这些键的英文文案里有汉字", emptyList<String>(), offenders)
    }

    // ===== 下面是给上面那些断言用的工具 =====

    /**
     * 断言「这一句用的是哪条资源」。
     *
     * 这里**不**像 `PermissionSummariesTest` 那样真拼出句子：这一页的文案都没有参数，
     * 而那句「用户会读到什么」已经被 `values/strings.xml` 本身决定了。
     * 真正会错的是**选错一句**（比如把 `RESTRICTED` 接到「已加入」上），那是 id 层面的错。
     */
    private fun assertRes(expected: Int, actual: MspText) {
        Assert.assertEquals(idOf(actual), expected)
    }

    private fun assertRes(what: String, expected: Int, actual: MspText) {
        Assert.assertEquals(what, idOf(actual), expected)
    }

    private fun idOf(text: MspText): Int = when (text) {
        is MspText.Res -> text.id
        is MspText.Plain -> error("这一页的文案都该来自资源，不该是硬编码的「${text.text}」")
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun settingsStrings(locale: String): Map<String, String> {
        val file = File(repoRoot(), "feature/settings/src/main/res/$locale/strings.xml")
        Assert.assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
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
        val LOCALES = listOf("values", "values-en", "values-b+zh+Hant")

        /**
         * 保活页用到的全部资源键。
         *
         * 这份清单是**登记表**，不是「测试关心的子集」：多一个或少一个都会被
         * `资源 - 保活页的资源键和这份清单一一对应` 抓到。写在这里的价值是
         * 新增文案的人必须来登记一次，顺带就会被上面几条按状态/厂商的断言覆盖到。
         */
        val KEEP_ALIVE_KEYS = setOf(
            "msp_settings_keep_alive",
            "msp_keep_alive_entry_on",
            "msp_keep_alive_entry_off",
            "msp_keep_alive_section_switch",
            "msp_keep_alive_switch_title",
            "msp_keep_alive_switch_on",
            "msp_keep_alive_switch_off",
            "msp_keep_alive_switch_help",
            "msp_keep_alive_switch_note",
            "msp_keep_alive_section_vendor",
            "msp_keep_alive_vendor_xiaomi",
            "msp_keep_alive_vendor_huawei",
            "msp_keep_alive_vendor_honor",
            "msp_keep_alive_vendor_oppo",
            "msp_keep_alive_vendor_vivo",
            "msp_keep_alive_vendor_meizu",
            "msp_keep_alive_vendor_samsung",
            "msp_keep_alive_vendor_one_plus",
            "msp_keep_alive_vendor_other",
            "msp_keep_alive_vendor_note_page",
            "msp_keep_alive_vendor_note_fallback",
            "msp_keep_alive_vendor_action",
            "msp_keep_alive_vendor_help",
        )
    }
}
