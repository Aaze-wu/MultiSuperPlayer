package com.multisuperplayer.core.common.device

import com.multisuperplayer.core.common.R
import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设备信息文本。
 *
 * [DeviceInfo.snapshot] 本身要读 `WindowManager` / `Build`，只能实机验；
 * 但这个 data class 是纯的，而且**它才是「关于页显示什么」的决定者**，
 * 所以退化路径全部在这里钉：厂商字段留空、ABI 列表为空、屏幕尺寸读成 0
 * 都是真实会发生的（模拟器、老机型、被裁剪的 ROM），显示成一堆括号或空白行
 * 就等于没记录。
 *
 * 断言的是 [MspText] 结构（哪条资源 + 什么参数），不是渲染后的字符串：
 * 渲染结果取决于当前语言，而这里没有 `Resources`。见 `AppBuildInfoTest` 的类注释。
 */
class DeviceSnapshotTest {

    private fun device(
        manufacturer: String = "Google",
        model: String = "Pixel 7",
        androidRelease: String = "14",
        sdkInt: Int = 34,
        abis: List<String> = listOf("arm64-v8a"),
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2400,
        densityDpi: Int = 420,
        languageTag: String = "zh-CN",
    ) = DeviceSnapshot(
        manufacturer = manufacturer,
        model = model,
        androidRelease = androidRelease,
        sdkInt = sdkInt,
        abis = abis,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        densityDpi = densityDpi,
        languageTag = languageTag,
    )

    private val unknown: MspText = MspText.Res(R.string.msp_value_unknown)

    // ------------------------------------------------------------------ 机型

    @Test
    fun `品牌和机型都显示`() {
        assertEquals(
            MspText.Res(R.string.msp_device_brand_model, "Google", "Pixel 7"),
            device().deviceText(),
        )
    }

    @Test
    fun `机型里已经带了品牌时不重复`() {
        // 国产 ROM 的 MODEL 很多是「小米 14」这种自带品牌的写法，
        // 直接拼就变成「小米 小米 14」——一眼看上去像是我们拼错了。
        assertEquals(MspText.Plain("小米 14"), device(manufacturer = "小米", model = "小米 14").deviceText())
        assertEquals(
            MspText.Plain("Redmi Note 12"),
            device(manufacturer = "Redmi", model = "Redmi Note 12").deviceText(),
        )
    }

    @Test
    fun `机型不带品牌时正常拼接`() {
        // 另一种主流写法：机型是内部代号，品牌只在 `MANUFACTURER` 里。
        assertEquals(
            MspText.Res(R.string.msp_device_brand_model, "Xiaomi", "M2101K9C"),
            device(manufacturer = "Xiaomi", model = "M2101K9C").deviceText(),
        )
    }

    @Test
    fun `大小写不同也算同一个品牌`() {
        // `manufacturer` 是 `google` 而 `MODEL` 是 `Google Pixel 7` 的情况真的存在，
        // 区分大小写就会拼成「google Google Pixel 7」。
        assertEquals(
            MspText.Plain("Google Pixel 7"),
            device(manufacturer = "google", model = "Google Pixel 7").deviceText(),
        )
    }

    @Test
    fun `只有品牌时显示品牌`() {
        assertEquals(MspText.Plain("小米"), device(manufacturer = "小米", model = "").deviceText())
    }

    @Test
    fun `只有机型时显示机型`() {
        assertEquals(MspText.Plain("Pixel 7"), device(manufacturer = "", model = "Pixel 7").deviceText())
    }

    @Test
    fun `两个都没有时显示未知`() {
        assertEquals(unknown, device(manufacturer = "", model = "").deviceText())
        assertEquals(unknown, device(manufacturer = "  ", model = "  ").deviceText())
    }

    @Test
    fun `前后空白不会带进文本`() {
        // 走的是「品牌 + 机型」那条拼接分支（机型是 `14`，不以品牌开头），
        // 所以这里同时钉住了两件事：分支选对了，且两个参数都是**去过空白**的。
        // 旧版直接 `"$manufacturer $model"`，日志里就会多出一串看不出是什么的空格。
        assertEquals(
            MspText.Res(R.string.msp_device_brand_model, "小米", "14"),
            device(manufacturer = " 小米 ", model = " 14 ").deviceText(),
        )
    }

    // ------------------------------------------------------------------ 系统

    @Test
    fun `系统文本同时给出代号和 API`() {
        assertEquals(MspText.Res(R.string.msp_android_with_api, "14", 34), device().androidText())
    }

    @Test
    fun `拿不到系统代号时至少给 API`() {
        // 排障时要判断走哪条兼容分支，API 级别才是硬指标，代号只是好读。
        assertEquals(MspText.Res(R.string.msp_api_only, 34), device(androidRelease = "").androidText())
        assertEquals(MspText.Res(R.string.msp_api_only, 34), device(androidRelease = "   ").androidText())
    }

    // ------------------------------------------------------------------ 架构

    @Test
    fun `架构按顺序列出`() {
        // 分隔符是半角逗号加空格，本来就与语言无关，所以是 Plain 而不是资源。
        assertEquals(
            MspText.Plain("arm64-v8a, x86_64"),
            device(abis = listOf("arm64-v8a", "x86_64")).abiText(),
        )
    }

    @Test
    fun `架构为空时显示未知`() {
        // 本安装包只打了 arm64-v8a 与 x86_64，架构读不到时「FFmpeg 为什么加载失败」
        // 这个问题就无从判断，所以这一行绝不能是空的。
        assertEquals(unknown, device(abis = emptyList()).abiText())
    }

    @Test
    fun `架构用逗号加空格分隔`() {
        // 逗号后没空格在等宽字体里会粘成一坨，用户复制出来也没法直接读。
        val text = device(abis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")).abiText()

        assertTrue(text is MspText.Plain && "arm64-v8a, x86_64, armeabi-v7a" == text.text)
    }

    // ------------------------------------------------------------------ 屏幕

    @Test
    fun `屏幕文本带分辨率与密度`() {
        assertEquals(
            MspText.Res(R.string.msp_screen_size, 1080, 2400, 420),
            device().screenText(),
        )
    }

    @Test
    fun `屏幕那条文案用的是全角乘号`() {
        // 半角 `x` 是字母：`1080x2400` 复制到 issue 里会被当成变量名，在等宽字体里
        // 也会和数字糊在一起。乘号现在住在文案里（`MspText.Res` 相等只验证了
        // 「用了哪条资源」，验不到资源里写了什么），所以这条守卫直接看文案。
        val xml = File(repoRoot(), "core/common/src/main/res/values/strings.xml").readText()
        val line = xml.lineSequence().firstOrNull { it.contains("name=\"msp_screen_size\"") }

        assertNotNull("values/strings.xml 里找不到 msp_screen_size", line)
        assertTrue("分辨率里应当是全角乘号：$line", line!!.contains("×"))
        assertFalse("分辨率里不该出现半角 x：$line", line!!.contains("x"))
    }

    @Test
    fun `密度为 0 时不显示 dpi`() {
        // `@0dpi` 是明显的假信息，不如不说。
        assertEquals(
            MspText.Res(R.string.msp_screen_size_plain, 1080, 2400),
            device(densityDpi = 0).screenText(),
        )
    }

    @Test
    fun `尺寸读不到时显示未知`() {
        assertEquals(unknown, device(screenWidthPx = 0).screenText())
        assertEquals(unknown, device(screenHeightPx = 0).screenText())
    }

    // ------------------------------------------------------------------ 行

    @Test
    fun `五行的标签与顺序固定`() {
        // 顺序是有意的：机型 → 系统 → 架构 → 屏幕 → 语言。
        // 前三个是定位问题用的，所以不能因为「看着不整齐」被重排。
        assertEquals(
            listOf(
                MspText.Res(R.string.msp_row_device),
                MspText.Res(R.string.msp_row_system),
                MspText.Res(R.string.msp_row_abi),
                MspText.Res(R.string.msp_row_screen),
                MspText.Res(R.string.msp_row_language),
            ),
            device().rows().map { it.label },
        )
    }

    @Test
    fun `五行都能读出内容`() {
        assertEquals(
            listOf(
                MspText.Res(R.string.msp_device_brand_model, "Google", "Pixel 7"),
                MspText.Res(R.string.msp_android_with_api, "14", 34),
                MspText.Plain("arm64-v8a"),
                MspText.Res(R.string.msp_screen_size, 1080, 2400, 420),
                MspText.Plain("zh-CN"),
            ),
            device().rows().map { it.value },
        )
    }

    @Test
    fun `语言为空时显示未知`() {
        assertEquals(unknown, device(languageTag = "").rows().last().value)
        assertEquals(unknown, device(languageTag = "   ").rows().last().value)
    }

    @Test
    fun `什么都没读到时五行渲染出来也不可能是空白`() {
        // 这是最坏情况：陌生机型 + 被裁剪的 ROM。整块显示成一片空白的话，
        // 用户发过来的日志抬头就等于没有。
        //
        // 单测里没有 `Resources`，所以钉住的是「渲染输入」：每个值要么是一条资源，
        // 要么是一段非空白的纯文本。真正危险的写法是 `MspText.Plain("")`。
        val empty = device(
            manufacturer = "",
            model = "",
            androidRelease = "",
            sdkInt = 0,
            abis = emptyList(),
            screenWidthPx = 0,
            screenHeightPx = 0,
            densityDpi = 0,
            languageTag = "",
        )

        empty.rows().forEach { row ->
            when (val value = row.value) {
                is MspText.Plain -> assertTrue("${row.label} 的值不该为空白", value.text.isNotBlank())
                is MspText.Res -> assertFalse("${row.label} 的值不该是一条空资源引用", value.id == 0)
            }
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 从测试的工作目录往上找 `settings.gradle.kts`。
     *
     * 不用相对路径硬拼：单测的工作目录由 Gradle 决定，某个版本改成别的目录之后
     * 相对路径会静默失效（读到的文件不存在 → 断言看不出区别）。往上找根目录再拼，
     * 至少找不到时会直接抛。
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 往上找不到 settings.gradle.kts")
    }
}
