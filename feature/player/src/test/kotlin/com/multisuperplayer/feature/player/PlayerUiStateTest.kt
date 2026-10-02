package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.AspectRatioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页的界面状态。
 *
 * 这是一个普通的类（用 Compose 的 `mutableStateOf` 存值，但不需要 composition），
 * 所以「锁定之后还能不能点出控制条」「比例覆盖值会不会把默认值吃掉」这些
 * 只能在真机上试出来的问题，这里全都能直接跑。
 */
class PlayerUiStateTest {

    @Test
    fun `竖屏进入时控制条是可见的`() {
        val state = PlayerUiState(initialFullscreen = false)
        assertFalse(state.fullscreen)
        assertTrue(state.controlsVisible)
    }

    @Test
    fun `横屏进入时控制条先收起来`() {
        // 横屏（全屏）直接进播放页时，控制条应该是收着的——竖屏下它本来就画在
        // 画面外面、永远可见，两者不能共用一个初值。
        val state = PlayerUiState(initialFullscreen = true)
        assertTrue(state.fullscreen)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun `进入全屏时控制条一定翻开`() {
        // 全屏是一个明确的动作；进去之后控制条是隐藏的话，用户会以为播放器卡住了。
        val state = PlayerUiState(initialFullscreen = false)
        state.toggleControls()
        assertFalse(state.controlsVisible)

        state.applyFullscreen(true)
        assertTrue("进全屏后控制条应该可见", state.controlsVisible)
        assertTrue(state.fullscreen)
    }

    @Test
    fun `退出全屏不动控制条`() {
        val state = PlayerUiState(initialFullscreen = true)
        state.applyFullscreen(false)
        assertFalse(state.fullscreen)
        // 竖屏下控制条在画面外面，「显示/隐藏」对它没有意义，所以这里不强行翻开。
        assertFalse(state.controlsVisible)
    }

    @Test
    fun `锁定时控制条被收走`() {
        // 锁定这个功能存在的全部意义就是这个：口袋里的一次误触不该能让画面被拖到别处。
        val state = PlayerUiState()
        assertTrue(state.controlsVisible)
        state.applyLocked(true)
        assertTrue(state.locked)
        assertFalse("锁定后控制条必须隐藏", state.controlsVisible)
        assertFalse(state.lockHintVisible)
    }

    @Test
    fun `解锁时不自动弹控制条`() {
        // 「解锁」是一个单独的意图，紧接着弹出一堆控件会和它不符；
        // 用户想看控制条再点一下画面即可。
        val state = PlayerUiState()
        state.applyLocked(true)
        state.applyLocked(false)
        assertFalse(state.locked)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun `锁定时点画面露出解锁按钮而不是控制条`() {
        // 这是锁定状态下的唯一出口：如果这里翻的是控制条，用户就永远解不了锁
        // （控制条上有解锁按钮，但一显示控制条就意味着误触又能生效了）。
        val state = PlayerUiState()
        state.applyLocked(true)

        state.toggleControls()
        assertTrue("应该露出解锁按钮", state.lockHintVisible)
        assertFalse("锁定时控制条不能出现", state.controlsVisible)
    }

    @Test
    fun `锁定时重复点画面不会改变控制条`() {
        val state = PlayerUiState()
        state.applyLocked(true)
        repeat(5) { state.toggleControls() }
        assertFalse(state.controlsVisible)
        assertTrue(state.lockHintVisible)
    }

    @Test
    fun `解锁后控制条能重新翻出来并再次被自动隐藏`() {
        // 锁定状态下的控制条**必须**是关死的（没有任何操作能让它出现），
        // 而解锁之后它又要完全恢复正常——只测前一半的话，一个「解锁后也
        // 永远收不起来控制条」的实现会照样通过。
        val state = PlayerUiState()
        state.applyLocked(true)
        state.toggleControls()
        assertFalse(state.controlsVisible)

        state.applyLocked(false)
        state.toggleControls()
        assertTrue("解锁后点画面应该能翻出控制条", state.controlsVisible)

        state.hideControls()
        assertFalse(state.controlsVisible)
    }

    @Test
    fun `解锁按钮的显隐是独立的`() {
        val state = PlayerUiState()
        state.applyLocked(true)
        state.revealLockedControls()
        assertTrue(state.lockHintVisible)
        state.hideLockHint()
        assertFalse(state.lockHintVisible)
    }

    @Test
    fun `两个提示泡互不干扰`() {
        // 合并成一个「提示」类型的话，显示「快进 10 秒」时很容易把 10000
        // 当成百分比画成一根满格的进度条——那是个看一秒就知道不对、
        // 但在此之前得先跑一遍界面的错误。
        val state = PlayerUiState()
        state.applyLevelHint(PlayerLevelHint(isVolume = true, percent = 60))
        state.setSeekHint(-PlayerGestures.DOUBLE_TAP_SEEK_MS)

        assertEquals(PlayerLevelHint(isVolume = true, percent = 60), state.levelHint)
        assertEquals(-PlayerGestures.DOUBLE_TAP_SEEK_MS, state.seekHintMs!!)

        // 收起快进提示不应该顺手把音量泡也清掉：一次双击之后音量泡还该显示一会儿。
        state.setSeekHint(null)
        assertNull(state.seekHintMs)
        assertEquals(PlayerLevelHint(isVolume = true, percent = 60), state.levelHint)
    }

    @Test
    fun `没有临时改动时跟随默认比例`() {
        val state = PlayerUiState()
        assertEquals(AspectRatioMode.FIT, state.aspectRatio(AspectRatioMode.FIT))
        // 设置晚到（从 CROP 换成 STRETCH）也要立刻跟着变：这正是「不缓存默认值」
        // 的原因，缓存的话永远差一次。
        assertEquals(AspectRatioMode.STRETCH, state.aspectRatio(AspectRatioMode.STRETCH))
    }

    @Test
    fun `临时改动之后不再跟随默认值`() {
        val state = PlayerUiState()
        state.setAspectRatio(AspectRatioMode.CROP)
        assertEquals(AspectRatioMode.CROP, state.aspectRatio(AspectRatioMode.FIT))
        // 默认值再怎么变，本次播放都用用户临时选的那个。
        assertEquals(AspectRatioMode.CROP, state.aspectRatio(AspectRatioMode.STRETCH))
    }

    @Test
    fun `默认比例与临时改动恰好相同时也算改过`() {
        // 用「覆盖值 + 现算」而不是「脏标记」的原因：脏标记要比较当前值和默认值，
        // 而首次载入时两者恰好相等，于是被当成「用户已经改过了」，默认值永远生效不了。
        val state = PlayerUiState()
        state.setAspectRatio(AspectRatioMode.FIT)
        assertEquals(AspectRatioMode.FIT, state.aspectRatioOverride)
        assertEquals(AspectRatioMode.FIT, state.aspectRatio(AspectRatioMode.ORIGINAL))
    }

    @Test
    fun `打开面板时控制条一并翻开`() {
        // 收控制条的逻辑是「播放中 4 秒不动就收」，它会在面板打开时把控制条收走，
        // 于是关掉面板之后下面的按钮位置全变了——用户回来点的东西和刚才看到的不是一回事。
        val state = PlayerUiState()
        state.hideControls()
        state.openSheet(PlayerSheet.SPEED)

        assertEquals(PlayerSheet.SPEED, state.openSheet)
        assertTrue(state.controlsVisible)

        state.closeSheet()
        assertNull(state.openSheet)
        // 关掉面板不重新收控制条：收它的计时器会接着算，不需要这里再插一手。
        assertTrue(state.controlsVisible)
    }
}
