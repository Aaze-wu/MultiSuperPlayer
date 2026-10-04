package com.multisuperplayer.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NetworkUiState` 里那两个**算出来的字段**的守卫：`url` 与 `invalid`。
 *
 * 为什么值得单独测：这两个值决定了三件用户看得见的事——输入框下方的提示改成错误、
 * 右侧那个播放按钮能不能按、按下去会交给谁。如果把它们做成**字段**（谁改了输入谁负责
 * 同步维护），就得在每个写输入的地方都记得重算一次；漏一处就是「按钮亮着但按了没反应」
 * 或者「地址明明能用却说不能用」。做成 `get()` 只有一个真相，这几条用例钉住的就是
 * 这个真相本身。
 *
 * 这里**不测 `NetworkViewModel`**：它继承 `ViewModel`，`viewModelScope` 要
 * `Dispatchers.Main`，纯 JVM 单测里拿不到（本模块没有 `MainDispatcherRule`）。
 * 而 ViewModel 里除了转发给 `RemoteUrlStore` 之外没有别的逻辑，所以值钱的断言都在这里。
 */
class NetworkUiStateTest {

    // ===== 空输入不是一个错误 =====

    @Test
    fun `什么都没写的时候不算错`() {
        // 一进这一页输入框就是空的。把「空」判成错会让页面一亮起来就红着，
        // 而按「播放」也本来就是禁用的——那才是空的正确表达。
        val state = NetworkUiState(input = "")

        assertNull("空输入不该被规范化成任何地址", state.url)
        assertFalse("空输入不是错误", state.invalid)
    }

    @Test
    fun `只有空白也不算错`() {
        // 用户全选删掉之后可能留下空格（尤其是从别处粘过来再按退格）。
        val state = NetworkUiState(input = "   ")

        assertNull(state.url)
        assertFalse("只有空白仍然是「还没写」", state.invalid)
    }

    // ===== 半截输入算错 =====

    @Test
    fun `敲到一半的地址是错的`() {
        // 输入框下面那一行要在「继续敲」与「改」之间切换，靠的就是这个判断；
        // 如果这里返回 false，用户敲到 `h` 时下面还在显示提示，敲完整串错的也是提示。
        val state = NetworkUiState(input = "ht")

        assertNull(state.url)
        assertTrue("敲到一半应该已经判成错误，好让提示行切过去", state.invalid)
    }

    @Test
    fun `没有域名只有协议是错的`() {
        val state = NetworkUiState(input = "https://")

        assertNull(state.url)
        assertTrue(state.invalid)
    }

    // ===== 能用的地址 =====

    @Test
    fun `写全了就能播`() {
        val state = NetworkUiState(input = "https://nas.local/movie.mp4")

        assertEquals("https://nas.local/movie.mp4", state.url)
        assertFalse(state.invalid)
    }

    @Test
    fun `前后空格不算错`() {
        // 从聊天记录/浏览器粘过来常常带一个尾随空格，判成错就是「明明一模一样却说不行」。
        val state = NetworkUiState(input = "  https://nas.local/a.mp4  ")

        assertEquals("https://nas.local/a.mp4", state.url)
        assertFalse(state.invalid)
    }

    @Test
    fun `中文输入法打出来的全角标点能被修正`() {
        // 中文输入法下 `:` 与 `/` 出的是全角（这也是 `RemoteUrlRules` 里那张别名表的
        // 唯一理由）。在这一层要保证的是：修正之后**不算错**，
        // 而不是「判成错、让用户自己去发现那个全角斜杠」。
        val state = NetworkUiState(input = "https：／／nas.local/a.mp4")

        assertEquals("https://nas.local/a.mp4", state.url)
        assertFalse(state.invalid)
    }

    // ===== 历史列表的「还没读到」 =====

    @Test
    fun `默认的历史是 null 而不是空列表`() {
        // null = 还在读，empty = 读到了、确实没有。界面靠这个区分「转圈」与
        // 「空态插画」——把两者混成一个值，用户看到的就是「一进页面就说这里从来
        // 没放过东西」，而其实只是还没读出来。
        val state = NetworkUiState()

        assertNull("初始状态必须表示「还没读到」", state.history)
    }

    @Test
    fun `输入写在状态里而不是 composable 里`() {
        // 输入框的内容住在 ViewModel：键盘弹出引起重排、转屏、进后台再回来，
        // 只要它活在 composable 的 `remember` 里就会丢——用户看到的是
        // 「键盘一弹，我打的字没了」。这条断言本身很轻，但钉住了这个契约。
        val state = NetworkUiState(input = "https://a.example/b.mp4")

        assertEquals("https://a.example/b.mp4", state.input)
    }
}
