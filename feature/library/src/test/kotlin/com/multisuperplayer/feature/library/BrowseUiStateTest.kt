package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.SafTreeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildBrowseUiState` 的测试。
 *
 * 「授权还在不在」是那种只能靠拔 SD 卡、在系统设置里撤销授权才复现的分支。
 * 把它做成纯函数入参，就是为了让它至少能被这里覆盖到——真机上没人会去测它。
 */
class BrowseUiStateTest {

    private fun tree(uri: String, label: String?, addedAtMs: Long = 1L) =
        SafTreeInfo(uri = uri, label = label, addedAtMs = addedAtMs)

    @Test
    fun `每一条目录都单独问一次还能不能打开`() {
        val trees = listOf(
            tree("content://tree/ok", "Music"),
            tree("content://tree/dead", "Movies"),
        )

        val state = buildBrowseUiState(trees) { uri -> uri.endsWith("/ok") }

        assertEquals(listOf(true, false), state.trees!!.map { it.accessible })
        assertFalse(state.loading)
    }

    @Test
    fun `顺序与授权顺序一致`() {
        val trees = listOf(
            tree("content://tree/2", "B", addedAtMs = 2L),
            tree("content://tree/1", "A", addedAtMs = 1L),
        )

        val state = buildBrowseUiState(trees) { true }

        // 数据层已经按加入时间排过序，这里再排一次就会和界面上「刚加的排最后」矛盾。
        assertEquals(listOf("content://tree/2", "content://tree/1"), state.trees!!.map { it.info.uri })
    }

    @Test
    fun `label 为 null 时原样保留，由界面换成卷根的说法`() {
        val state = buildBrowseUiState(listOf(tree("content://tree/root", null))) { true }

        // 本地化字符串不能存进数据层，所以这一层不替它编一个名字。
        assertNull(state.trees!!.single().info.label)
    }

    @Test
    fun `一个目录都没授权不是加载中`() {
        val state = buildBrowseUiState(emptyList()) { true }

        assertFalse(state.loading)
        assertEquals(0, state.trees?.size)
    }

    @Test
    fun `默认状态是加载中`() {
        assertTrue(BrowseUiState().loading)
        assertNull(BrowseUiState().trees)
    }

    @Test
    fun `uri 原样带出来，撤销授权后还能对上系统设置里那一条`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

        val state = buildBrowseUiState(listOf(tree(uri, "Music"))) { false }

        assertEquals(uri, state.trees!!.single().info.uri)
    }
}
