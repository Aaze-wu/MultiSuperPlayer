package com.multisuperplayer.feature.library

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切回前台要不要重扫媒体库」这条策略的测试。
 *
 * 这条策略是为了补一个真实的缺口：应用首次启动时统一弹的系统授权框由应用根上的
 * launcher 发起，媒体库自己那个 launcher 拿不到回调，于是授权完成后媒体库**一直**
 * 停在「需要媒体授权」——用户看到的是「我刚点了允许，它还说没权限」。
 *
 * 关键在于它是**有条件的**：无条件重扫会让每次回到前台都白查一遍 MediaStore。
 * 所以这里两个方向都要钉住：该扫的那一格要扫，其余四格一律不许扫。
 */
class LibraryResumeRulesTest {

    @Test
    fun `上次的结论是没有权限时切回前台要重扫`() {
        assertTrue(
            "授权完成之后媒体库要靠这一次重扫才能自愈",
            shouldRescanOnResume(MediaLibraryState.NeedsPermission),
        )
    }

    @Test
    fun `已经有内容时不重扫，每次回前台都查一遍 MediaStore 是白花钱`() {
        assertFalse(
            "空列表也是「扫过了，结果是空的」，重扫解决不了任何问题",
            shouldRescanOnResume(MediaLibraryState.Ready(entries = emptyList())),
        )
    }

    @Test
    fun `部分权限的那种 Ready 同样不重扫`() {
        assertFalse(
            "缺权限的提示由 partial 横幅负责，不该靠回前台重扫来表达",
            shouldRescanOnResume(MediaLibraryState.Ready(entries = emptyList(), partial = true)),
        )
    }

    @Test
    fun `正在扫描时不重扫，避免和手里这一次撞车`() {
        assertFalse(
            "Loading 本来就意味着扫描在跑",
            shouldRescanOnResume(MediaLibraryState.Loading),
        )
    }

    @Test
    fun `扫描失败时不自动重扫，别把失败循环成反复重试`() {
        assertFalse(
            "失败原因是数据库异常，回前台重扫一次解决不了，只会来回闪",
            shouldRescanOnResume(MediaLibraryState.Error(MspText.Plain("boom"))),
        )
    }
}
