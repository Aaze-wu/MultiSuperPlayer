package com.multisuperplayer.core.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些说明类提示被关掉了」这份偏好的纯函数部分。
 *
 * 写入路径在仓库里（要 DataStore，测不了），但**语义**全在这三个扩展函数上：
 * 缺键等于空集、未知 id 写不进去、恢复等于删键。任何一条写错，症状都是
 * 「提示关了却还在」或者「恢复了却不再出现」，而这两种都只在真机上看得见。
 */
class HintSettingsTest {

    @Test
    fun `没关过任何提示时是空集`() {
        // 缺键与「存了一个空集合」在语义上必须一样，否则第一次升级上来的
        // 用户和点过「恢复」的用户会走到两条不同的分支上。
        assertEquals(emptySet<String>(), emptyPreferences().dismissedHints())
    }

    @Test
    fun `关闭一条之后读得出那一条`() {
        val prefs = mutablePreferencesOf()
        prefs.dismissHint(HintIds.BROWSE_SOURCES)

        assertEquals(setOf(HintIds.BROWSE_SOURCES), prefs.dismissedHints())
    }

    @Test
    fun `重复关闭同一条是幂等的`() {
        val prefs = mutablePreferencesOf()
        prefs.dismissHint(HintIds.PLAYLIST_DRAG)
        prefs.dismissHint(HintIds.PLAYLIST_DRAG)

        assertEquals(setOf(HintIds.PLAYLIST_DRAG), prefs.dismissedHints())
    }

    @Test
    fun `多次关闭不同的提示会累积`() {
        val prefs = mutablePreferencesOf()
        prefs.dismissHint(HintIds.BROWSE_SOURCES)
        prefs.dismissHint(HintIds.PLAYER_QUEUE_DRAG)

        assertEquals(
            setOf(HintIds.BROWSE_SOURCES, HintIds.PLAYER_QUEUE_DRAG),
            prefs.dismissedHints(),
        )
    }

    @Test
    fun `未知的 id 不会被写进去`() {
        // 这条不是洁癖：id 拼错时如果照样写盘，用户会得到一份「关掉了但界面照旧」
        // 的数据，而且他会以为这个功能坏了。同理，任何**警告类**提示只要不在
        // `HintIds.all` 里，就不可能被这条写路径关掉。
        val prefs = mutablePreferencesOf()
        prefs.dismissHint("no_such_hint")
        prefs.dismissHint("")

        assertEquals(emptySet<String>(), prefs.dismissedHints())
    }

    @Test
    fun `恢复会清空全部并删键`() {
        val prefs = mutablePreferencesOf()
        prefs.dismissHint(HintIds.BROWSE_SOURCES)
        prefs.dismissHint(HintIds.PLAYLIST_DRAG)
        prefs.restoreHints()

        assertEquals(emptySet<String>(), prefs.dismissedHints())
        // 删键而不是写入空集合：留一个空集合会让「没设置过」和「恢复过」在磁盘上
        // 长得不一样，而下游只应该看 `dismissedHints()` 一个结论。这里断言的是
        // 存储层的形状，正是为了让那条结论永远不需要再区分这两种情况。
        assertFalse(prefs.contains(HintKeys.Dismissed))
    }

    @Test
    fun `三条已知提示互不相同且都不为空`() {
        // `HintIds.all` 既用于校验写入，也用于设置页统计条数。重复或空串会让
        // 「已关闭 N 条」这个数字变得不可信（或者让空 id 把自己写进集合）。
        assertTrue(HintIds.all.all { it.isNotBlank() })
        assertEquals(3, HintIds.all.size)
        assertEquals(
            setOf(HintIds.BROWSE_SOURCES, HintIds.PLAYLIST_DRAG, HintIds.PLAYER_QUEUE_DRAG),
            HintIds.all,
        )
    }
}
