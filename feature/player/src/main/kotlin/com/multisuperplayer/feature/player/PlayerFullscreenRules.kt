package com.multisuperplayer.feature.player

/**
 * 「横过来自动进全屏」这条规则本身。
 *
 * 抽出来只有一个原因：它是**纯函数**，能被纯 JVM 单测覆盖。判断画中画那一堆
 * 事（见 [PlayerPipRules]）用的是同一个办法。
 *
 * ## 为什么不能只看「窗口是不是横的」
 *
 * 画中画小窗自己就是个横窗口：16:9 的小窗在系统的配置里就是 `land`
 * （实测：整屏配置是 `sw411dp w411dp h914dp`，小窗是 `sw128dp w228dp h128dp`）。
 * 于是「窗口是横的」在整个画中画期间都成立，而这条规则一旦成立就会去请求
 * `SENSOR_LANDSCAPE`——**于是用户手里竖着的手机被锁成横屏**：
 *
 * 1. 进小窗（当时是竖屏），小窗的配置让 [PlayerScreen] 里那条 `LaunchedEffect`
 *    把 `fullscreen` 打开；
 * 2. 在小窗里这个「全屏」看不出问题（那一两百 dp 的窗口里本来就没有系统栏）；
 * 3. 退出小窗，`fullscreen` 已经是 `true`，这次真的去请求 `SENSOR_LANDSCAPE`
 *    ——播放页横过来了，而用户从头到尾只是把小窗点开又点掉。
 *
 * 也就是说：**这不是「偶尔读到上一帧的旧配置」，而是画中画期间一直成立。**
 *
 * ## 所以看的是「窗口够不够大」
 *
 * `Configuration.smallestScreenWidthDp` 是窗口自己的最小边，**与方向无关**：
 * 整屏 411dp（这块 1080×2400 / 420dpi 的机器），16:9 的小窗只有 128dp。
 *
 * 门槛取 [MIN_SCREEN_WIDTH_DP] = 300dp，两头都留了余量：
 *
 * - 比任何机型的整屏 `sw` 都小：最小的小屏手机也有 320dp（`sw320dp` 是 Android
 *   最老的「small」档）。所以「一整块屏幕」永远过得去这个门槛。
 * - 比实测见到的画中画窗口大得多：这块机器上整屏是 `sw411dp`，而小窗实测过
 *   `sw128dp`（另一个比例下 `sw213dp`）——系统给画中画窗口的上限本来就压得
 *   很低，离 300dp 还很远。
 *
 * 顺带一个好处：分屏里每个应用窗口的 `sw` 只有两百多 dp，于是分屏时**不会**
 * 因为手机横过来就把自己变成全屏（那本来也做不到）。
 *
 * 除了「要不要全屏」，这里也放同一个 `PlayerScreen` 里另一条「让位」规则：
 * [shouldShowBottomBar]。
 */
internal object PlayerFullscreenRules {

    /** 窗口的 `sw` 小于这个值就当成「这不是一块屏幕」，而是个小窗口。 */
    const val MIN_SCREEN_WIDTH_DP: Int = 300

    /**
     * 现在该不该因为「横过来了」而自动进全屏。
     *
     * @param windowLandscape 窗口当前是不是横的（[rememberIsLandscape]）。
     * @param smallestScreenWidthDp 窗口的 `Configuration.smallestScreenWidthDp`。
     */
    fun shouldEnterFullscreen(windowLandscape: Boolean, smallestScreenWidthDp: Int): Boolean =
        windowLandscape && smallestScreenWidthDp >= MIN_SCREEN_WIDTH_DP

    /**
     * 底部导航栏该不该显示。
     *
     * 两种「让位」都会把整条底部栏**连根拔掉**（再看 `MspAppScaffold` 的 `bottomBar`）：
     *
     * - **全屏**：那是用户明确要求的「只要画面」。
     * - **画中画**：小窗实测只有 `w379dp h213dp`，而 `NavigationBar` 自己固定 80dp——
     *   它在小窗里吃掉将近四成的高度，于是小窗变成「上面一小条画面 + 下面一条导航」，
     *   而这个窗口的全部意义就是那一小块画面。
     *
     * ## 为什么不能用「窗口够不够宽」来判断（和 [shouldEnterFullscreen] 反着来）
     *
     * 那条规则能用尺寸判断，是因为「一整块屏幕」和「小窗」的 `sw` 差着数量级；
     * 这里不行：画中画小窗（`sw213dp`）和分屏窗口（`sw260dp`）几乎一样大，
     * 但**分屏里的导航栏该留着**——用户还得在另一半屏幕里切标签。
     * 所以这里读的是「窗口处在什么模式」，不是「窗口有多大」。
     *
     * 抽成纯函数是为了能单测：真实调用点在 `PlayerScreen` 的 `DisposableEffect` 里，
     * 那里既拿不到真窗口也没法断言。
     */
    fun shouldShowBottomBar(fullscreen: Boolean, inPip: Boolean): Boolean =
        !fullscreen && !inPip
}
