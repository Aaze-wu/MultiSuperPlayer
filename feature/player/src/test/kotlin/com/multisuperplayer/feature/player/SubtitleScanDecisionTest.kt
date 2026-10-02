package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.subtitle.SubtitleScan
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.subtitleSourceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「扫描结果 + 当前选择 ⇒ 接下来干什么」的规则。
 *
 * ## 为什么值得单独测
 *
 * 这段逻辑原来长在 `loadFlow` 的协程里，`when (scan)` 的三个失败分支一律
 * `emit + return`。于是「用户在文件浏览器里亲手挑了一条字幕、而那个目录恰好扫不出来」
 * 会把加载整个吞掉——界面上只剩一句「目录不可用」。
 *
 * 这个组合不是边角情况，而是**手动指定字幕的主场**：需要手动挑的场景就是扫描
 * 最容易失败的地方（`Download/` 根、`.nomedia` 目录、媒体库不索引的路径）。
 *
 * 真机上第一次跑就撞上了：代码写完了、编译过了、941 个测试全绿，字幕就是不出现。
 * 因为那段 `if` 夹在协程管线中间，只有跑真机才验得到——所以现在它是这个纯函数。
 */
class SubtitleScanDecisionTest {

    /** 用户在文件浏览器里点中的那一条（裸路径，正是 `FileSystemSource` 给的形状）。 */
    private val manual: SubtitleSource = subtitleSourceOf(
        "/sdcard/Download/msp-browse/MspBrowseTest.zh.srt",
        "MspBrowseTest.zh.srt",
    )

    private val autoPicked: SubtitleSource = subtitleSourceOf(
        "/sdcard/Movies/Show.chs.srt",
        "Show.chs.srt",
    )

    private val manualSelection = SubtitleSelection.Source(manual)

    private fun proceed(step: ScanStep): ScanStep.Proceed {
        assertTrue("期望继续加载，实际是 $step", step is ScanStep.Proceed)
        return step as ScanStep.Proceed
    }

    private fun stop(step: ScanStep): ScanStep.Stop {
        assertTrue("期望停下来提示，实际是 $step", step is ScanStep.Stop)
        return step as ScanStep.Stop
    }

    @Test
    fun `扫到了就把候选交给挑选`() {
        val step = decideScanStep(SubtitleScan.Found(listOf(autoPicked)), SubtitleSelection.Auto)

        assertEquals(listOf(autoPicked), proceed(step).candidates)
    }

    @Test
    fun `扫到一个候选都没有也是继续而不是提示`() {
        // 目录可见、就是没字幕。这时「自动挑选」该显示「没有字幕」，
        // 但那句话由 `scanFound` 从空候选里推出来，不该在这里先停一次。
        val step = decideScanStep(SubtitleScan.Found(emptyList()), SubtitleSelection.Auto)

        assertEquals(emptyList<SubtitleSource>(), proceed(step).candidates)
    }

    @Test
    fun `目录拿不到时自动挑选停下来提示`() {
        val step = decideScanStep(SubtitleScan.NoDirectory, SubtitleSelection.Auto)

        assertEquals(SubtitleIssue.NoDirectory, stop(step).issue)
    }

    @Test
    fun `目录拿不到时手选的字幕照样加载`() {
        // 回归：这一条挂了就等于「扫描失败时手动指定字幕」这个功能整体失效。
        // 状态是直接构造的——`NoDirectory` 今天只由 Android 9 及以下的媒体库条目
        // 产生（浏览器条目已改走「直接列目录」），而这类状态本来就难在单测里真造出来。
        val step = decideScanStep(SubtitleScan.NoDirectory, manualSelection)

        assertEquals(emptyList<SubtitleSource>(), proceed(step).candidates)
    }

    @Test
    fun `目录不可见时手选的字幕照样加载`() {
        val step = decideScanStep(SubtitleScan.DirectoryInvisible, manualSelection)

        assertEquals(emptyList<SubtitleSource>(), proceed(step).candidates)
    }

    @Test
    fun `查询失败时手选的字幕照样加载`() {
        val failure = SubtitleScan.Failed(MspText.Plain("SecurityException: denied"))

        val step = decideScanStep(failure, manualSelection)

        assertEquals(emptyList<SubtitleSource>(), proceed(step).candidates)
    }

    @Test
    fun `查询失败时自动挑选把原始原因透出来`() {
        val failure = SubtitleScan.Failed(MspText.Plain("SecurityException: denied"))

        val issue = stop(decideScanStep(failure, SubtitleSelection.Auto)).issue

        assertEquals(SubtitleIssue.ScanFailed(MspText.Plain("SecurityException: denied")), issue)
    }

    @Test
    fun `手选的字幕不会混进候选列表`() {
        // 混进去会让「候选」这个列表有两种含义：一条是目录里扫到的，
        // 一条是用户点过但目录里没有的。面板据此画单选框，多一条就成了幽灵选项。
        val step = decideScanStep(SubtitleScan.DirectoryInvisible, manualSelection)

        assertTrue(proceed(step).candidates.isEmpty())
    }
}
