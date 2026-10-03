package com.multisuperplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 「上一首」的动作选择。
 *
 * 这一组测试保的是**两个方向的错**，而且两个都不会报错：
 *
 * - 用回 `seekToPrevious()` ⇒ 播过 3 秒后按一次只是重播当前项
 *   （症状是「要按两下才切得走」，见 [PreviousTrackRules] 的说明）；
 * - 只有 `seekToPreviousMediaItem()`、没有退化分支 ⇒ 停在第一条时按钮
 *   **按下去什么都不发生**（没有异常、没有日志、没有画面变化）。
 *
 * 所以这里同时钉住「有上一条时必须切」与「没有上一条时必须做点什么」——
 * 只看其中一条都会漏掉另一半。
 */
class PreviousTrackRulesTest {

    @Test
    fun `有上一条时切上一条，不做重播当前项`() {
        assertEquals(
            PreviousTrackRules.Action.PREVIOUS_ITEM,
            PreviousTrackRules.actionFor(hasPreviousItem = true),
        )
    }

    @Test
    fun `没有上一条时回到本条目开头，而不是什么都不做`() {
        // 这一条是「按钮不能是死的」：`seekToPreviousMediaItem()` 在没有上一条时
        // 是空操作，必须换一条命令，否则用户按下去屏幕上什么都不会发生。
        assertEquals(
            PreviousTrackRules.Action.RESTART_CURRENT,
            PreviousTrackRules.actionFor(hasPreviousItem = false),
        )
    }

    @Test
    fun `两个分支必须不同`() {
        // 防止将来有人把「没有上一条」也改成切上一条（那就退回空操作），
        // 或者把两个分支写成同一个动作。
        assertNotEquals(
            PreviousTrackRules.actionFor(hasPreviousItem = true),
            PreviousTrackRules.actionFor(hasPreviousItem = false),
        )
    }
}
