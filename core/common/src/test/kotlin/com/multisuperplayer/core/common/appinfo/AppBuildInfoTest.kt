package com.multisuperplayer.core.common.appinfo

import com.multisuperplayer.core.common.R
import com.multisuperplayer.core.common.text.MspText
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
 * 而不是「构建脚本坏了」。所以每个字段都必须退化成「未知」且**能被认出来**。
 *
 * ## 断言的是「选了哪条文案」，不是「那句话长什么样」
 *
 * 支持多语言之后，渲染出来的字符串取决于当前语言，单测里没有 `Resources`
 * 可渲染。所以这一层改成断言 [MspText] 的**结构**：用哪条资源、参数是什么。
 * 这比断言字符串更耐改——译者改标点不会让测试变红，但少了一个分支一定会。
 * 「渲染出来不是空白」这件事由 `MspText.unknown()` 兜底，见文件末尾的说明。
 */
class AppBuildInfoTest {

    private fun info(
        versionName: String = "0.5.4",
        versionCode: Int = 50400,
        gitCommit: String = "09b06a6",
        gitTag: String = "v0.5.3",
        gitDirty: Boolean = false,
        buildTimeText: String = "2026-10-02 15:30 +08:00",
        versionChannel: String = "",
    ) = AppBuildInfo(
        versionName,
        versionCode,
        gitCommit,
        gitTag,
        gitDirty,
        buildTimeText,
        versionChannel,
    )

    /** 「未知」这条文案在多个断言里出现，抽出来免得写错 id。 */
    private val unknown: MspText = MspText.Res(R.string.msp_value_unknown)

    private val dirty: MspText = MspText.Res(R.string.msp_dirty_suffix)

    // ------------------------------------------------------------------ 版本

    @Test
    fun `版本号带上版本码`() {
        assertEquals(
            MspText.Res(R.string.msp_version_with_code, "0.5.4", 50400),
            info().versionText(),
        )
    }

    @Test
    fun `没有版本码时只显示版本号`() {
        // 版本码为 0 是「构建脚本没算出来」，显示 `0.5.4 (0)` 是误导。
        assertEquals(MspText.Plain("0.5.4"), info(versionCode = 0).versionText())
    }

    @Test
    fun `拿不到版本号时显示未知而不是空行`() {
        assertEquals(unknown, info(versionName = "").versionText())
        assertEquals(unknown, info(versionName = "   ").versionText())
    }

    @Test
    fun `拿不到版本号时不会只显示一堆括号`() {
        // 早期的写法是拼接 `"$name ($code)"`，名字为空时会显示成 ` (50400)`：
        // 一个带数字的、看起来像样但没有任何意义的字符串。
        // 现在走的是「哪条文案」这条线：名字取不到就走「未知」，不会碰那条带括号的模板。
        val text = info(versionName = "").versionText()

        assertNotEquals(MspText.Res(R.string.msp_version_with_code, "", 50400), text)
        assertEquals(unknown, text)
    }

    // ------------------------------------------------------------------ 提交

    @Test
    fun `干净的工作区只显示 commit`() {
        assertEquals(MspText.Plain("09b06a6"), info().commitText())
    }

    @Test
    fun `有未提交改动时必须说出来`() {
        // 否则一个本地改过的包和一个干净的 tag 包显示成同一个 commit，
        // 而两者行为可能完全不同——「这个 bug 在 X 版本上能复现」就变成了假话。
        assertEquals(
            MspText.Res(R.string.msp_wrapped_in_parens, "09b06a6", dirty),
            info(gitDirty = true).commitText(),
        )
    }

    @Test
    fun `拿不到 commit 时不显示未提交改动`() {
        // 「未知（含未提交改动）」是自己打自己的脸：连是哪个 commit 都不知道，
        // 谈不上它干不干净。脏标记只在有 commit 可谈时才有意义。
        assertEquals(unknown, info(gitCommit = "", gitDirty = true).commitText())
    }

    // ------------------------------------------------------------------ 来源

    @Test
    fun `tag 与 commit 都有时都显示`() {
        assertEquals(
            MspText.Res(R.string.msp_wrapped_in_parens, "v0.5.3", MspText.Plain("09b06a6")),
            info().sourceText(),
        )
    }

    @Test
    fun `只有 tag 时只显示 tag`() {
        assertEquals(MspText.Plain("v0.5.3"), info(gitCommit = "").sourceText())
    }

    @Test
    fun `只有 commit 时只显示 commit`() {
        // 仓库里一个 tag 都没有时（比如别人 clone 之后重新打），仍然要能认出是哪个提交。
        assertEquals(MspText.Plain("09b06a6"), info(gitTag = "").sourceText())
    }

    @Test
    fun `都没有时显示未知`() {
        assertEquals(unknown, info(gitCommit = "", gitTag = "").sourceText())
    }

    @Test
    fun `来源里要带未提交改动`() {
        // 一开始这里写的是「不用带」，理由是「脏不脏由 commit 那一行去说」——
        // 但关于页一共只有三行（版本/构建来源/构建时间），**没有 commit 那一行**，
        // 于是「本地改过的包」和「干净的 tag 包」在界面上长得一模一样。
        // 既然没有别的地方说，就只能在这里说。
        assertEquals(
            MspText.Res(
                R.string.msp_wrapped_in_parens,
                "v0.5.3",
                MspText.Res(R.string.msp_wrapped_in_parens, "09b06a6", dirty),
            ),
            info(gitDirty = true).sourceText(),
        )
        // 只有 tag / 只有 commit 的降级路径也要带上，否则脏的包在降级时又变回「看起来干净」。
        assertEquals(
            MspText.Res(R.string.msp_appended_with_comma, "v0.5.3", dirty),
            info(gitCommit = "", gitDirty = true).sourceText(),
        )
        assertEquals(
            MspText.Res(R.string.msp_wrapped_in_parens, "09b06a6", dirty),
            info(gitTag = "", gitDirty = true).sourceText(),
        )
    }

    // ------------------------------------------------------------------ 时间与汇总

    @Test
    fun `构建时间用构建机写死的文本`() {
        // 带偏移量的字符串：设备时区和构建机不同也不会显示出偏移几小时的假时间。
        assertEquals(MspText.Plain("2026-10-02 15:30 +08:00"), info().buildTimeLabel())
    }

    @Test
    fun `构建时间可以留空但要显示成未知`() {
        assertEquals(unknown, info(buildTimeText = "").buildTimeLabel())
        assertEquals(unknown, info(buildTimeText = "  ").buildTimeLabel())
    }

    @Test
    fun `三行抬头内容固定`() {
        val rows = info().rows()

        assertEquals(
            listOf(
                MspText.Res(R.string.msp_row_version),
                MspText.Res(R.string.msp_row_source),
                MspText.Res(R.string.msp_row_build_time),
            ),
            rows.map { it.label },
        )
        assertEquals(
            listOf(
                MspText.Res(R.string.msp_version_with_code, "0.5.4", 50400),
                MspText.Res(R.string.msp_wrapped_in_parens, "v0.5.3", MspText.Plain("09b06a6")),
                MspText.Plain("2026-10-02 15:30 +08:00"),
            ),
            rows.map { it.value },
        )
    }

    @Test
    fun `摘要就是版本号`() {
        // 设置首页那一行只放得下一个版本号，点进去才看得到来源。
        assertEquals(info().versionText(), info().summaryText())
    }

    // ------------------------------------------------------------------ 预览标记

    @Test
    fun `带通道后缀的版本是预览版`() {
        // 关于页靠这个标记决定要不要挂上「预览版」徽章。
        // 正式包里通道号是空字符串，于是徽章整块不出现。
        assertTrue(info(versionChannel = "alpha").isPreview)
        assertTrue(info(versionChannel = "beta").isPreview)
        assertTrue(info(versionChannel = "rc").isPreview)
    }

    @Test
    fun `正式版不带预览标记`() {
        assertFalse(info().isPreview)
        assertFalse(info(versionChannel = "").isPreview)
    }

    @Test
    fun `通道号是空白时不算预览版`() {
        // 与其它字段同一套规矩：空白等于「没取到」，不能因为非空就当作有通道。
        // 否则构建脚本注错一个空格，正式包就会自己挂上「预览版」徽章。
        assertFalse(info(versionChannel = "   ").isPreview)
    }

    @Test
    fun `兜底值不是预览版`() {
        assertFalse(AppBuildInfo.Unknown.isPreview)
    }

    // ------------------------------------------------------------------ 测试版标记

    @Test
    fun `beta 既是预览版也是测试版`() {
        // 两个属性管的事不同：`isPreview` 管「要不要挂标记」，
        // `isBetaChannel` 管「挂哪一个词」。beta 两个都是真。
        val beta = info(versionChannel = "beta")

        assertTrue(beta.isPreview)
        assertTrue(beta.isBetaChannel)
    }

    @Test
    fun `通道名大小写和空格不影响是不是测试版`() {
        // 这个字段来自构建脚本，大小写或多余空格都不该让它变成「另一个通道」。
        assertTrue(info(versionChannel = "BETA").isBetaChannel)
        assertTrue(info(versionChannel = " beta ").isBetaChannel)
    }

    @Test
    fun `别的通道不是测试版`() {
        // alpha 走的是「预览版」那个词：它不对外发布，说成「测试版」
        // 等于告诉用户「这个可以去装了」。
        assertFalse(info().isBetaChannel)
        assertFalse(info(versionChannel = "").isBetaChannel)
        assertFalse(info(versionChannel = "   ").isBetaChannel)
        assertFalse(info(versionChannel = "alpha").isBetaChannel)
        // `beta1` 是另一个名字，不能因为前缀相同就算进来——
        // 通道名是要拿去和构建脚本、tag、更新源对齐的东西，不是模糊匹配。
        assertFalse(info(versionChannel = "beta1").isBetaChannel)
        assertFalse(AppBuildInfo.Unknown.isBetaChannel)
    }

    // ------------------------------------------------------------------ 兜底值

    @Test
    fun `兜底值的四个字段都能被认出来`() {
        val fallback = AppBuildInfo.Unknown

        assertEquals(unknown, fallback.versionText())
        assertEquals(unknown, fallback.sourceText())
        assertEquals(unknown, fallback.buildTimeLabel())
        assertEquals(unknown, fallback.commitText())
    }

    @Test
    fun `兜底值的字段本身是空的, 显示成什么由渲染决定`() {
        // 旧版把「未知」直接写进字段里（`gitCommit = "未知"`），于是「取到了什么」和
        // 「取不到时显示什么」混成一个值：换个词就要改数据，而且数据层被迫带着一种语言。
        // 现在字段留空表示「没取到」，`MspText.unknown()` 负责显示——
        // 仍然是「一定能被认出来」，只是分工清楚了。
        assertEquals("", AppBuildInfo.Unknown.gitCommit)
        assertEquals("", AppBuildInfo.Unknown.gitTag)
        assertNotEquals("未知", AppBuildInfo.Unknown.gitCommit)
    }

    @Test
    fun `就算什么都没取到, 三行渲染出来也不可能是空白`() {
        // 最坏情况：构建脚本完全没注入。
        //
        // 这里**渲染不了**（单测里没有 `Resources`），所以退一步钉住「渲染输入」：
        // 每一行的值要么是一条资源（不可能是空的），要么是一段非空白的纯文本。
        // 真正会出事的写法是 `MspText.Plain("")`——那才是「静默消失」，
        // 而这条断言正好能抓住它。
        AppBuildInfo.Unknown.rows().forEach { row -> assertRenderable(row.label, row.value) }
    }

    private fun assertRenderable(label: MspText, value: MspText) {
        when (value) {
            is MspText.Plain -> assertTrue("$label 的值不该为空白", value.text.isNotBlank())
            is MspText.Res -> assertFalse("$label 的值不该是一条空资源引用", value.id == 0)
        }
    }
}
