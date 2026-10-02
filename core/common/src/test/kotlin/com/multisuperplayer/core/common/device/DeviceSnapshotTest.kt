package com.multisuperplayer.core.common.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备信息文本。
 *
 * [DeviceInfo.snapshot] 本身要读 `WindowManager` / `Build`，只能实机验；
 * 但这个 data class 是纯的，而且**它才是「关于页显示什么」的决定者**，
 * 所以退化路径全部在这里钉：厂商字段留空、ABI 列表为空、屏幕尺寸读成 0
 * 都是真实会发生的（模拟器、老机型、被裁剪的 ROM），显示成一堆括号或空白行
 * 就等于没记录。
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

    // ------------------------------------------------------------------ 机型

    @Test
    fun `品牌和机型都显示`() {
        assertEquals("Google Pixel 7", device().deviceText())
    }

    @Test
    fun `机型里已经带了品牌时不重复`() {
        // 国产 ROM 的 MODEL 很多是「小米 14」这种自带品牌的写法，
        // 直接拼就变成「小米 小米 14」——一眼看上去像是我们拼错了。
        assertEquals("小米 14", device(manufacturer = "小米", model = "小米 14").deviceText())
        assertEquals("Redmi Note 12", device(manufacturer = "Redmi", model = "Redmi Note 12").deviceText())
    }

    @Test
    fun `机型不带品牌时正常拼接`() {
        // 另一种主流写法：机型是内部代号，品牌只在 `MANUFACTURER` 里。
        assertEquals("Xiaomi M2101K9C", device(manufacturer = "Xiaomi", model = "M2101K9C").deviceText())
    }

    @Test
    fun `大小写不同也算同一个品牌`() {
        // `manufacturer` 是 `google` 而 `MODEL` 是 `Google Pixel 7` 的情况真的存在，
        // 区分大小写就会拼成「google Google Pixel 7」。
        assertEquals("Google Pixel 7", device(manufacturer = "google", model = "Google Pixel 7").deviceText())
    }

    @Test
    fun `只有品牌时显示品牌`() {
        assertEquals("小米", device(manufacturer = "小米", model = "").deviceText())
    }

    @Test
    fun `只有机型时显示机型`() {
        assertEquals("Pixel 7", device(manufacturer = "", model = "Pixel 7").deviceText())
    }

    @Test
    fun `两个都没有时显示未知`() {
        assertEquals(DeviceSnapshot.UNKNOWN, device(manufacturer = "", model = "").deviceText())
        assertEquals(DeviceSnapshot.UNKNOWN, device(manufacturer = "  ", model = "  ").deviceText())
    }

    @Test
    fun `前后空白不会带进文本`() {
        assertEquals("小米 14", device(manufacturer = " 小米 ", model = " 14 ").deviceText())
    }

    // ------------------------------------------------------------------ 系统

    @Test
    fun `系统文本同时给出代号和 API`() {
        assertEquals("Android 14（API 34）", device().androidText())
    }

    @Test
    fun `拿不到系统代号时至少给 API`() {
        // 排障时要判断走哪条兼容分支，API 级别才是硬指标，代号只是好读。
        assertEquals("API 34", device(androidRelease = "").androidText())
        assertEquals("API 34", device(androidRelease = "   ").androidText())
    }

    // ------------------------------------------------------------------ 架构

    @Test
    fun `架构按顺序列出`() {
        assertEquals("arm64-v8a, x86_64", device(abis = listOf("arm64-v8a", "x86_64")).abiText())
    }

    @Test
    fun `架构为空时显示未知`() {
        // 本安装包只打了 arm64-v8a 与 x86_64，架构读不到时「FFmpeg 为什么加载失败」
        // 这个问题就无从判断，所以这一行绝不能是空的。
        assertEquals(DeviceSnapshot.UNKNOWN, device(abis = emptyList()).abiText())
    }

    @Test
    fun `架构用逗号加空格分隔`() {
        // 逗号后没空格在等宽字体里会粘成一坨，用户复制出来也没法直接读。
        assertTrue(device(abis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")).abiText().contains(", "))
    }

    // ------------------------------------------------------------------ 屏幕

    @Test
    fun `屏幕文本带分辨率与密度`() {
        assertEquals("1080×2400 @420dpi", device().screenText())
    }

    @Test
    fun `屏幕用的是全角乘号`() {
        // 半角 `x` 在等宽字体里会和数字糊在一起（`1080x2400` 看起来像 `1080×2400` 的
        // 另一种拼法，但复制到别处就变成了字母 x）。项目里其它地方也是全角。
        val text = device().screenText()

        assertTrue(text.contains('×'))
        assertFalse(text.contains('x'))
    }

    @Test
    fun `密度为 0 时不显示 dpi`() {
        // `@0dpi` 是明显的假信息，不如不说。
        assertEquals("1080×2400", device(densityDpi = 0).screenText())
    }

    @Test
    fun `尺寸读不到时显示未知`() {
        assertEquals(DeviceSnapshot.UNKNOWN, device(screenWidthPx = 0).screenText())
        assertEquals(DeviceSnapshot.UNKNOWN, device(screenHeightPx = 0).screenText())
    }

    // ------------------------------------------------------------------ 行

    @Test
    fun `五行的标签与顺序固定`() {
        // 顺序是有意的：机型 → 系统 → 架构 → 屏幕 → 语言。
        // 前三个是定位问题用的，所以不能因为「看着不整齐」被重排。
        assertEquals(
            listOf("设备", "系统", "CPU 架构", "屏幕", "语言"),
            device().rows().map { it.label },
        )
    }

    @Test
    fun `五行都能读出内容`() {
        assertEquals(
            listOf("Google Pixel 7", "Android 14（API 34）", "arm64-v8a", "1080×2400 @420dpi", "zh-CN"),
            device().rows().map { it.value },
        )
    }

    @Test
    fun `语言为空时显示未知`() {
        assertEquals(DeviceSnapshot.UNKNOWN, device(languageTag = "").rows().last().value)
    }

    @Test
    fun `什么都没读到时五行也不是空白`() {
        // 这是最坏情况：陌生机型 + 被裁剪的 ROM。整块显示成一片空白的话，
        // 用户发过来的日志抬头就等于没有。
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
            assertTrue("${row.label} 的值不该为空白", row.value.isNotBlank())
        }
    }
}
