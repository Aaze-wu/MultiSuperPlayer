package com.multisuperplayer.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长文件名该不该滚」的判定测试。
 *
 * 为什么这一层要写测试：真正会出错的地方不是规则本身，而是**它在界面上的落点**。
 * `core:ui` 与 `feature:library` 都没有 `compose-ui-test`，`basicMarquee` 起没起、
 * 外面那层 `Text` 是不是还留着省略号，单测一行都看不到。所以判定必须活在这个纯函数里，
 * 由这里把它锁死，界面上只留一句「为 true 就挂 marquee」。
 *
 * 反过来，这一组用例**不能**证明「看起来对」：滚动的方向、速度、是否越界由
 * Compose 自己的 `basicMarquee` 负责，只能在真机上看。
 */
class TitleScrollRulesTest {

    private val current = "media:1001"

    @Test
    fun `默认是开着的`() {
        // 仓库里那个键是 Boolean?（null = 用户没设置过），默认值的决定权在 UI 层。
        // 这条断言防的是「有人把默认值改成 false」——那会让这个功能对**所有老用户**
        // 变成默认关闭，而他们根本没关过。
        assertTrue(TitleScrollRules.DEFAULT_ENABLED)
    }

    @Test
    fun `只有当前那一条算当前`() {
        assertTrue(TitleScrollRules.isCurrent(current, current, selected = false))
        assertFalse(TitleScrollRules.isCurrent("media:2002", current, selected = false))
        assertFalse(TitleScrollRules.isCurrent(current, currentEntryId = null, selected = false))
    }

    @Test
    fun `勾上的算当前`() {
        // 多选态里用户操作的对象是勾选框，不是播放器。勾上的那一行必须滚——
        // 否则长名字在「要不要选它」的当口被截断，最难读的正是这几秒。
        assertTrue(TitleScrollRules.isCurrent("media:2002", current, selected = true))
        assertTrue(TitleScrollRules.isCurrent(null, null, selected = true))
    }

    @Test
    fun `没有 id 的行不滚`() {
        // `mediaId` 拼不出来时是 null，空串同样当「没有」。两个都放行的话，
        // 「当前 id 也是 null（没在播）」的列表会让**整页**每一行同时滚起来。
        assertFalse(TitleScrollRules.isCurrent(null, current, selected = false))
        assertFalse(TitleScrollRules.isCurrent("", "", selected = false))
        assertFalse(TitleScrollRules.isCurrent("  ", "  ", selected = false))
    }

    @Test
    fun `四个条件缺一不可`() {
        val all = TitleScrollRules.shouldScroll(
            enabled = true,
            animationsEnabled = true,
            isCurrent = true,
            text = "一个很长很长的文件名.mkv",
        )
        assertTrue(all)

        assertFalse(
            "设置里关掉之后一律不滚",
            TitleScrollRules.shouldScroll(false, true, true, "x.mkv"),
        )
        assertFalse(
            "系统开了「移除动画」就不滚",
            TitleScrollRules.shouldScroll(true, animationsEnabled = false, true, "x.mkv"),
        )
        assertFalse(
            "不是当前那一条就不滚",
            TitleScrollRules.shouldScroll(true, true, isCurrent = false, "x.mkv"),
        )
    }

    @Test
    fun `空标题不滚`() {
        // 空串上挂 marquee 只是每 1.4 秒空转一圈，白耗帧。
        assertFalse(TitleScrollRules.shouldScroll(true, true, true, ""))
        assertFalse(TitleScrollRules.shouldScroll(true, true, true, "   "))
    }

    @Test
    fun `节奏是两个正数且先停后走`() {
        // 这两个值直接决定手感，也是唯一会被后续改动「顺手调一下」的东西。
        // 下限 1ms 防的是 0/负数：0 会让 marquee 立刻起飞，负数在
        // `basicMarquee` 里会被当成「已经滚过一段」，表现是文字从中间开始。
        assertTrue(TitleScrollRules.INITIAL_DELAY_MILLIS > 0)
        assertTrue(TitleScrollRules.REPEAT_DELAY_MILLIS > 0)
        // 首停比圈间停短：用户刚看到这一屏的时候耐心最少。
        assertTrue(
            "首停(${TitleScrollRules.INITIAL_DELAY_MILLIS}) 应短于圈间停" +
                "(${TitleScrollRules.REPEAT_DELAY_MILLIS})",
            TitleScrollRules.INITIAL_DELAY_MILLIS < TitleScrollRules.REPEAT_DELAY_MILLIS,
        )
    }

    @Test
    fun `默认状态既不滚也没有当前项`() {
        // CompositionLocal 的默认值：预览和任何没被 App 包起来的调用点都不该自己动。
        val state = TitleScrollState()
        assertFalse(state.enabled)
        assertEquals(null, state.currentEntryId)
    }
}
