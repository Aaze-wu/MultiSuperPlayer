package com.multisuperplayer.core.common.appinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建信息（关于页与日志抬头共用）。
 *
 * 这里全是**降级**路径的测试：正常配置下每一行都有值，看不出问题；
 * 真正会出事的是构建脚本没注入成功的时候——`gitOutput` 失败、仓库里没有 tag、
 * 名字取不到文件……那时如果显示成空白行，用户和开发者都会以为「这个字段本来就没有」，
 * 而不是「构建脚本坏了」。所以每个字段都必须退化成 `未知` 且**能被认出来**。
 */
class AppBuildInfoTest {

    private fun info(
        versionName: String = "0.5.4",
        versionCode: Int = 50400,
        gitCommit: String = "09b06a6",
        gitTag: String = "v0.5.3",
        gitDirty: Boolean = false,
        buildTimeText: String = "2026-10-02 15:30 +08:00",
    ) = AppBuildInfo(versionName, versionCode, gitCommit, gitTag, gitDirty, buildTimeText)

    // ------------------------------------------------------------------ 版本

    @Test
    fun `版本号带上版本码`() {
        assertEquals("0.5.4 (50400)", info().versionText())
    }

    @Test
    fun `没有版本码时只显示版本号`() {
        // 版本码为 0 是「构建脚本没算出来」，显示 `0.5.4 (0)` 是误导。
        assertEquals("0.5.4", info(versionCode = 0).versionText())
    }

    @Test
    fun `拿不到版本号时显示未知而不是空行`() {
        assertEquals(AppBuildInfo.UNKNOWN, info(versionName = "").versionText())
        assertEquals(AppBuildInfo.UNKNOWN, info(versionName = "   ").versionText())
    }

    @Test
    fun `拿不到版本号时不会只显示一堆括号`() {
        // 早期的写法是拼接 `"$name ($code)"`，名字为空时会显示成 ` (50400)`：
        // 一个带数字的、看起来像样但没有任何意义的字符串。
        val text = info(versionName = "").versionText()

        assertFalse(text.contains("50400"))
        assertEquals("未知", text)
    }

    // ------------------------------------------------------------------ 提交

    @Test
    fun `干净的工作区只显示 commit`() {
        assertEquals("09b06a6", info().commitText())
    }

    @Test
    fun `有未提交改动时必须说出来`() {
        // 否则一个本地改过的包和一个干净的 tag 包显示成同一个 commit，
        // 而两者行为可能完全不同——「这个 bug 在 X 版本上能复现」就变成了假话。
        assertEquals("09b06a6（含未提交改动）", info(gitDirty = true).commitText())
    }

    @Test
    fun `拿不到 commit 时不显示未提交改动`() {
        // 「未知（含未提交改动）」是自己打自己的脸：连是哪个 commit 都不知道，
        // 谈不上它干不干净。脏标记只在有 commit 可谈时才有意义。
        assertEquals(AppBuildInfo.UNKNOWN, info(gitCommit = "", gitDirty = true).commitText())
        assertEquals(AppBuildInfo.UNKNOWN, info(gitCommit = AppBuildInfo.UNKNOWN, gitDirty = true).commitText())
    }

    // ------------------------------------------------------------------ 来源

    @Test
    fun `tag 与 commit 都有时都显示`() {
        assertEquals("v0.5.3（09b06a6）", info().sourceText())
    }

    @Test
    fun `只有 tag 时只显示 tag`() {
        assertEquals("v0.5.3", info(gitCommit = "").sourceText())
    }

    @Test
    fun `只有 commit 时只显示 commit`() {
        // 仓库里一个 tag 都没有时（比如别人 clone 之后重新打），仍然要能认出是哪个提交。
        assertEquals("09b06a6", info(gitTag = "").sourceText())
        assertEquals("09b06a6", info(gitTag = AppBuildInfo.UNKNOWN).sourceText())
    }

    @Test
    fun `都没有时显示未知`() {
        assertEquals(AppBuildInfo.UNKNOWN, info(gitCommit = "", gitTag = "").sourceText())
    }

    @Test
    fun `来源里要带未提交改动`() {
        // 一开始这里写的是「不用带」，理由是「脏不脏由 commit 那一行去说」——
        // 但关于页一共只有三行（版本/构建来源/构建时间），**没有 commit 那一行**，
        // 于是「本地改过的包」和「干净的 tag 包」在界面上长得一模一样。
        // 既然没有别的地方说，就只能在这里说。
        assertEquals("v0.5.3（09b06a6，含未提交改动）", info(gitDirty = true).sourceText())
        // 只有 tag / 只有 commit 的降级路径也要带上，否则脏的包在降级时又变回「看起来干净」。
        assertEquals("v0.5.3，含未提交改动", info(gitCommit = "", gitDirty = true).sourceText())
        assertEquals("09b06a6，含未提交改动", info(gitTag = "", gitDirty = true).sourceText())
    }

    // ------------------------------------------------------------------ 时间与汇总

    @Test
    fun `构建时间用构建机写死的文本`() {
        // 带偏移量的字符串：设备时区和构建机不同也不会显示出偏移几小时的假时间。
        assertEquals("2026-10-02 15:30 +08:00", info().buildTimeLabel())
    }

    @Test
    fun `构建时间可以留空但要显示成未知`() {
        assertEquals(AppBuildInfo.UNKNOWN, info(buildTimeText = "").buildTimeLabel())
        assertEquals(AppBuildInfo.UNKNOWN, info(buildTimeText = "  ").buildTimeLabel())
    }

    @Test
    fun `三行抬头内容固定`() {
        val rows = info().rows()

        assertEquals(listOf("版本", "构建来源", "构建时间"), rows.map { it.label })
        assertEquals(listOf("0.5.4 (50400)", "v0.5.3（09b06a6）", "2026-10-02 15:30 +08:00"), rows.map { it.value })
    }

    @Test
    fun `摘要就是版本号`() {
        // 设置首页那一行只放得下一个版本号，点进去才看得到来源。
        assertEquals(info().versionText(), info().summaryText())
    }

    // ------------------------------------------------------------------ 兜底值

    @Test
    fun `兜底值的三个字段都能被认出来`() {
        val unknown = AppBuildInfo.Unknown

        assertEquals(AppBuildInfo.UNKNOWN, unknown.versionText())
        assertEquals(AppBuildInfo.UNKNOWN, unknown.sourceText())
        assertEquals(AppBuildInfo.UNKNOWN, unknown.buildTimeLabel())
        assertEquals(AppBuildInfo.UNKNOWN, unknown.commitText())
    }

    @Test
    fun `兜底值不靠空串表示缺失`() {
        // 空串和未知必须区分：空串是拼接算出来的（`"$name"` 取不到名字时就是空的），
        // 未知是「我知道我取不到」。前者会让整行静默消失，后者一定会显示出来。
        assertEquals(AppBuildInfo.UNKNOWN, AppBuildInfo.Unknown.gitCommit)
        assertNotEquals("", AppBuildInfo.Unknown.gitCommit)
    }

    @Test
    fun `就算什么都没取到, 三行也都能读`() {
        AppBuildInfo.Unknown.rows().forEach { row ->
            assertTrue("${row.label} 的值不该为空", row.value.isNotEmpty())
            assertFalse("${row.label} 的值不该是空白", row.value.isBlank())
        }
    }
}
