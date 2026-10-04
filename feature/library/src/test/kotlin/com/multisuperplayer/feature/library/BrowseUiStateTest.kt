package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.browser.BrowserContent
import com.multisuperplayer.core.data.browser.BrowserSort
import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.data.browser.BrowserTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowseUiState] 是纯数据：这里只钉它**推导**出来的三个布尔量。
 *
 * 三个都值得单独测，因为它们的写法各自容易写歪：
 * - `loading` 必须按「有没有读到」判断，而不是「列表是不是空的」——一个真的没有任何
 *   来源的设备上，`roots = emptyList()` 是**已经读完**的结果，写成 `roots.isEmpty()`
 *   会让那个设备永远停在转圈上。
 * - `browsing` 用 `trail != null`，而不是「内容是不是 Ready」——停在来源清单时内容是
 *   `Idle`，而用户还没选目录这件事和「目录读不出来」是两件事。
 * - `canGoUp` 要靠 `trail.canGoUp` 而不是「内容非空」，否则在根目录里会给出一个
 *   点了没反应的返回按钮。
 */
class BrowseUiStateTest {

    private fun rootTrail(): BrowserTrail =
        BrowserTrail.root(
            kind = BrowserSourceKind.FILE_SYSTEM,
            label = MspText.Plain("内部存储"),
            ref = "/storage/emulated/0",
        )

    private fun trailAtDepth(depth: Int): BrowserTrail {
        var current = rootTrail()
        repeat(depth) { index ->
            current = current.enter(MspText.Plain("第 $index 层"), "/storage/emulated/0/$index")
        }
        return current
    }

    @Test
    fun `还没读到来源时算加载中`() {
        val state = BrowseUiState()

        assertNull(state.roots)
        assertTrue(state.loading)
        assertFalse(state.browsing)
        assertFalse(state.canGoUp)
    }

    @Test
    fun `来源列表为空不等于还在加载`() {
        val state = BrowseUiState(roots = emptyList())

        assertFalse("空列表是读完了的结果，不是还没读完", state.loading)
        assertFalse(state.browsing)
    }

    @Test
    fun `停在来源清单时不在浏览状态，也没有上一层`() {
        val state = BrowseUiState(roots = emptyList(), content = BrowserContent.Idle)

        assertFalse(state.browsing)
        assertFalse(state.canGoUp)
    }

    @Test
    fun `默认内容是想选的目录，而不是一个空目录`() {
        val state = BrowseUiState()

        // `Idle` 与 `Ready(emptyList())` 必须区分：混用会让界面对「刚打开还没选目录」
        // 显示「这个文件夹是空的」。
        assertEquals(BrowserContent.Idle, state.content)
    }

    @Test
    fun `进了来源根就算在浏览，但根上没有上一层`() {
        val state = BrowseUiState(roots = emptyList(), trail = rootTrail())

        assertTrue(state.browsing)
        assertFalse("来源根就是最上面一层", state.canGoUp)
    }

    @Test
    fun `进到子目录之后可以返回上一层`() {
        val state = BrowseUiState(roots = emptyList(), trail = trailAtDepth(1))

        assertTrue(state.browsing)
        assertTrue(state.canGoUp)
    }

    @Test
    fun `排序与隐藏开关是状态的一部分，默认名称升序且不显示隐藏项`() {
        val state = BrowseUiState()

        assertEquals(BrowserSort.NAME_ASC, state.sort)
        assertFalse(state.showHidden)
    }

    @Test
    fun `排序与隐藏开关原样透传`() {
        val state = BrowseUiState(sort = BrowserSort.NEWEST, showHidden = true)

        assertEquals(BrowserSort.NEWEST, state.sort)
        assertTrue(state.showHidden)
    }
}
