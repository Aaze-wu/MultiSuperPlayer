package com.multisuperplayer.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放时不让屏幕熄灭」的判据。
 *
 * 这一条功能的窗口 flag 只是它的结果，真正会出错的地方全是**条件**：暂停了该不该放开、
 * 画中画里算不算、临时开关和设置里那个谁优先。三种情况都要在真机上出画面、等超时、
 * 盯着屏幕真的灭没灭才能验一次，所以判据被抽成纯函数放在这里逐条钉死。
 *
 * 特别要钉住的是**暂停必须放开**：这一条是用户最容易察觉、也最容易在实现里漏掉的
 * ——「进了播放页就一直亮」写起来更简单，而用户放下手机去看书回来发现屏幕亮了一小时。
 * 它不会崩、不会报错，只有等一次超时才发现。
 */
class PlayerKeepScreenOnRulesTest {

    @Test
    fun `播放中且用户没关掉才是常亮`() {
        assertTrue(
            PlayerKeepScreenOnRules.shouldKeepScreenOn(
                isPlaying = true,
                preference = true,
                inPip = false,
            ),
        )
    }

    @Test
    fun `暂停就放开屏幕`() {
        // 按下暂停、把手机放下——那一刻正是用户最期待屏幕自己熄掉的时候。
        assertFalse(
            PlayerKeepScreenOnRules.shouldKeepScreenOn(
                isPlaying = false,
                preference = true,
                inPip = false,
            ),
        )
    }

    @Test
    fun `用户关掉之后播放中也不常亮`() {
        // 临时开关与设置里的默认值合成同一个 `preference`，这条同时覆盖两种来源：
        // 判据只看结果，不看它是谁定的（谁定的那件事在 `PlayerUiState` 里）。
        assertFalse(
            PlayerKeepScreenOnRules.shouldKeepScreenOn(
                isPlaying = true,
                preference = false,
                inPip = false,
            ),
        )
    }

    @Test
    fun `画中画里不算播放中`() {
        // 画中画是「用小窗继续看、同时去干别的事」，干别的事的时候屏幕必须能熄。
        // 把它算成「正在播放所以不许熄屏」，等于让两百 dp 的小窗把整块屏幕钉亮。
        assertFalse(
            PlayerKeepScreenOnRules.shouldKeepScreenOn(
                isPlaying = true,
                preference = true,
                inPip = true,
            ),
        )
    }

    @Test
    fun `三个条件每一个单独就能否掉常亮`() {
        // 八个组合里唯一为真的一种是 `(true, true, false)`，其余全部列在这里：
        // 以后有人把某个条件写成「或者」（比如「播放中 或者 用户设了常亮」），
        // 就会在这张表上红掉，而不是等到用户发现「关掉了还是亮着」。
        val variants = listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, false),
            Triple(false, true, true),
            Triple(true, false, false),
            Triple(true, false, true),
            Triple(true, true, true),
        )

        variants.forEach { (playing, preference, inPip) ->
            assertFalse(
                "isPlaying=$playing preference=$preference inPip=$inPip 不该常亮",
                PlayerKeepScreenOnRules.shouldKeepScreenOn(playing, preference, inPip),
            )
        }
    }
}
