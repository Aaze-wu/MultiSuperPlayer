package com.multisuperplayer.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「横过来自动进全屏」的判定。
 *
 * 这组用例的大部分篇幅花在**不该进全屏**的那一侧：这条规则的代价不对称——
 * 少进一次全屏只是没自动转过去（用户还能自己点），多进一次会把方向锁成横屏，
 * 而最典型的就是画中画小窗把它触发（见 [PlayerFullscreenRules] 的 KDoc）。
 */
class PlayerFullscreenRulesTest {

    @Test
    fun `整屏横屏进全屏`() {
        assertTrue(
            "411dp 的机器横过来（配置里的 sw 还是 411dp，方向是横）应该进全屏",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = 411,
            ),
        )
    }

    @Test
    fun `画中画小窗不算横屏`() {
        assertFalse(
            "16:9 的小窗（实测 sw128dp w228dp h128dp）在配置里是横的，但它不该进全屏：" +
                "进了就会在退出小窗时把用户的竖屏手机锁成横屏",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = 128,
            ),
        )
    }

    @Test
    fun `小窗拖大一点仍然不算横屏`() {
        assertFalse(
            "用户把小窗拖到 220dp 宽也不该算：门槛是给「不是一整块屏幕」留的，不是给某个具体尺寸",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = 220,
            ),
        )
    }

    @Test
    fun `刚好到门槛算`() {
        assertTrue(
            "门槛是「大于等于」，300dp 应该算",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = PlayerFullscreenRules.MIN_SCREEN_WIDTH_DP,
            ),
        )
    }

    @Test
    fun `差 1dp 不算`() {
        assertFalse(
            "门槛的边界要卡在整数上：299dp 是小窗口",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = PlayerFullscreenRules.MIN_SCREEN_WIDTH_DP - 1,
            ),
        )
    }

    @Test
    fun `门槛比最小的小屏手机还小`() {
        assertTrue(
            "Android 最老的 small 档是 sw320dp，门槛必须留在它下面，否则那小屏手机横过来就不进全屏了",
            PlayerFullscreenRules.MIN_SCREEN_WIDTH_DP < 320,
        )
    }

    @Test
    fun `竖屏不进全屏`() {
        assertFalse(
            "窗口是竖的，再大也不进全屏（这条规则只负责「横过来」这一个方向）",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = false,
                smallestScreenWidthDp = 411,
            ),
        )
    }

    @Test
    fun `竖屏的小窗口也不进全屏`() {
        assertFalse(
            "竖着的小窗（用户把画中画窗口拉成竖的）两个条件都不满足",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = false,
                smallestScreenWidthDp = 128,
            ),
        )
    }

    @Test
    fun `平板横屏进全屏`() {
        assertTrue(
            "平板的 sw 更大，同样进全屏",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = 800,
            ),
        )
    }

    @Test
    fun `分屏里的窄窗口不进全屏`() {
        assertFalse(
            "分屏时每个应用窗口的 sw 只有两百多 dp：那时候手机横过来也不该把自己变成全屏（它也做不到）",
            PlayerFullscreenRules.shouldEnterFullscreen(
                windowLandscape = true,
                smallestScreenWidthDp = 260,
            ),
        )
    }

    @Test
    fun `普通播放页显示底部导航栏`() {
        assertTrue(
            "既没全屏也没进小窗：底部导航栏照常显示（用户要靠它切标签）",
            PlayerFullscreenRules.shouldShowBottomBar(fullscreen = false, inPip = false),
        )
    }

    @Test
    fun `全屏时藏掉底部导航栏`() {
        assertFalse(
            "全屏是用户明确要求的「只要画面」，底部栏要让位",
            PlayerFullscreenRules.shouldShowBottomBar(fullscreen = true, inPip = false),
        )
    }

    @Test
    fun `画中画时藏掉底部导航栏`() {
        assertFalse(
            "小窗实测 w379dp h213dp，而导航栏固定 80dp：不藏的话它吃掉小窗近四成高度",
            PlayerFullscreenRules.shouldShowBottomBar(fullscreen = false, inPip = true),
        )
    }
}
