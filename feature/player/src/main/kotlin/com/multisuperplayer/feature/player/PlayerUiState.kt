package com.multisuperplayer.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.SpeedBoostOptions

/**
 * 竖直拖动时屏幕中间那个提示泡的内容。
 *
 * @param isVolume true = 音量，false = 亮度。用布尔量而不是直接塞一个枚举：
 *   这两件事在这里的差别**只有**一个图标和一个数字，多一层枚举只会多一层 when。
 */
@Immutable
data class PlayerLevelHint(val isVolume: Boolean, val percent: Int)

/**
 * 屏幕中间那个「进度」提示泡的内容。
 *
 * 只有**水平拖动**会产生它：手指正按在画面上，想看的位置常常就在手指底下，
 * 所以提示要写清楚「松手会落到哪儿」。
 *
 * 三个字段都必需、没有默认值。它曾经也服务「双击快进」（那时只知道跳了多少、
 * 落点由内核算，于是 `targetMs` 是可空的）——双击改成播放/暂停之后那条生产
 * 路径就没有了，而留着一个**永远不为 null** 的可空字段，等于给界面留一段永远
 * 不会执行的画法：下一个人只会据此以为「双击快进还在」。
 *
 * @param deltaMs 相对按下时的变化：正数 = 前进，负数 = 后退。连正负号一起存是
 *   因为提示泡上要写「+12 秒 / -8 秒」，而这个符号只有产生这个提示的人手上有。
 * @param targetMs 松手时会落到的位置。
 * @param durationMs 总时长，用来画「00:52 / 01:00」。
 */
@Immutable
data class PlayerSeekHint(
    val deltaMs: Long,
    val targetMs: Long,
    val durationMs: Long,
)

/**
 * 播放页上会弹出的选择面板。
 *
 * 一个面板只改一个维度：`SPEED` 改倍速、`ASPECT_RATIO` 改画面比例、`AUDIO_TRACK`
 * 改音轨。字幕不在这张表里——它是单独一个 `ModalBottomSheet`（见 `PlayerRoute` 里
 * 的 `showSubtitleSheet`），因为它下面挂着一整棵状态机（扫描、翻译、导出），
 * 和这三个「选一个值就关掉」的面板不是一类东西。
 */
enum class PlayerSheet { SPEED, ASPECT_RATIO, AUDIO_TRACK }

/**
 * 播放页的**界面**状态：全屏、锁定、控制条显隐、提示泡、当前面板。
 *
 * ## 为什么它不放在 ViewModel 里
 *
 * 这些状态**全都会在旋转屏幕时被丢弃并重建**（除了全屏，它的初值来自当前方向），
 * 这正是它们应有的行为：转屏之后控制条重新出现、提示泡消失、面板关掉。
 * 放进 ViewModel 就得在每次转屏时手动清一遍，而漏清一个的症状是
 * 「转屏之后控制条不出现」，非常难归因。
 *
 * ## 为什么是一个可变对象而不是一堆参数
 *
 * 控制条显隐要被子控件改（点一下画面），又要被自动隐藏的计时器改，还要被
 * 「打开面板」改。全部做成回调参数的话，`PlayerScreen` 的签名会膨胀到
 * 十几个 `onXxx`，而它们之间还有约束（锁定 ⇒ 控制条必须隐藏）。
 * 约束写在下面的方法里，就只有一份。
 */
@Stable
class PlayerUiState(initialFullscreen: Boolean = false) {

    var fullscreen: Boolean by mutableStateOf(initialFullscreen)
        private set

    var locked: Boolean by mutableStateOf(false)
        private set

    /**
     * 控制条是否可见。
     *
     * 初值取 [initialFullscreen] 的反：进全屏的那一刻控制条是要出现的（用户刚做完
     * 一个动作，需要看到反馈），而竖屏下控制条本来就在画面外面、永远可见。
     */
    var controlsVisible: Boolean by mutableStateOf(!initialFullscreen)
        private set

    /** 锁定状态下那唯一一个解锁按钮是否露出来（点一下画面就露 3 秒）。 */
    var lockHintVisible: Boolean by mutableStateOf(false)
        private set

    var levelHint: PlayerLevelHint? by mutableStateOf(null)
        private set

    /**
     * 水平拖动的进度提示。null = 不显示。
     *
     * 和 [levelHint] 分开而不是合并成一个「提示」类型：两者的值域和含义毫无关系
     * （一个是 0..100 的百分比，一个是时间），合并只会让两边都多一层拆包、
     * 而拆错的时候没有任何东西会报错——想把 `12000` 当成百分比画成一根满格的
     * 进度条，是那种看一秒就知道不对、但在此之前得先跑一遍界面的错误。
     */
    var seekHint: PlayerSeekHint? by mutableStateOf(null)
        private set

    /**
     * 双击播放/暂停的提示。null = 不显示，true = 刚切成「播放中」，
     * false = 刚切成「已暂停」。
     *
     * ## 为什么是可空布尔
     *
     * 三种状态（不显示 / 播放中 / 已暂停）正好就是 `Boolean?` 的三个取值，
     * 再包一层 data class 只会在每个读写点多一次拆包；而参数名写成 `playing`
     * 之后，调用点 `applyPlayPauseHint(!state.isPlaying)` 一眼能读出「提示的是
     * 切换**之后**的状态」。
     *
     * ## 为什么要有它
     *
     * 双击是唯一**没有别的反馈**的手势：横屏全屏时控制条多半已经淡出，用户看不到
     * 播放键图标那个变化，双击之后画面完全没变——他会以为手势没生效，于是再双击
     * 一次，播放/暂停又切了回去，看起来就像「双击没反应」。
     */
    var playPauseHint: Boolean? by mutableStateOf(null)
        private set

    /**
     * 按住画面时的临时倍速。null = 没在加速（提示泡也不显示）。
     *
     * 存的是**倍速值本身**而不是一个布尔：提示泡上要写「2× 播放中」，
     * 而布尔每加一处使用就要在界面里再读一次设置；更要紧的是，界面上的值和
     * 真正下给内核的值必须是同一个（都由 [SpeedBoostOptions.normalize] 算出来），
     * 否则会出现「写着 2× 实际在放 3×」——这种偏差只有盯着听才分辩得出来。
     */
    var speedBoost: Float? by mutableStateOf(null)
        private set

    var openSheet: PlayerSheet? by mutableStateOf(null)
        private set

    /**
     * 这一部片子**临时**改过的画面比例。null = 没改过，跟随设置里的默认值。
     *
     * 为什么是「覆盖值」而不是直接存一个非空的比例：非空就必须在一开始拿到
     * 设置里的默认值，而设置是异步从 DataStore 载入的，首帧拿到的总是空。
     * 于是必然要写「如果用户还没改过，就跟随新到的设置值」这种脏标记判断，
     * 而它在**首次载入**时是反的（当前值恰好等于默认值，于是被当成
     * 「用户已经改过了」，默认值永远生效不了）。
     *
     * 用可空覆盖值把两件事分开存，就不需要任何判断：[aspectRatio] 每次
     * 现算，设置晚一点到也能才对。
     */
    var aspectRatioOverride: AspectRatioMode? by mutableStateOf(null)
        private set

    /**
     * 进/出全屏。
     *
     * 进入时强制把控制条翻开：全屏是一个明确的动作，如果进去之后控制条是隐藏的，
     * 用户会以为播放器卡住了。
     *
     * 叫 `applyXxx` 而不是 `setFullscreen`：属性 `fullscreen` 自己就会生成一个
     * `setFullscreen(Z)V`，同名函数会和它撞 JVM 签名，Kotlin 直接报
     * 「Platform declaration clash」——而编辑器里的静态分析常常看不到，只有真编译才报。
     */
    fun applyFullscreen(value: Boolean) {
        fullscreen = value
        if (value) controlsVisible = true
    }

    /**
     * 上/解锁。
     *
     * 锁定时**同时**把控制条收起来——这是锁定这个功能存在的全部意义：
     * 口袋里的一次误触不该能让画面被拖到别处。反过来解锁时不动控制条，
     * 让用户自己去点：解锁之后马上弹出一堆控件，和「我只想解锁」这个意图不符。
     */
    fun applyLocked(value: Boolean) {
        locked = value
        lockHintVisible = false
        if (value) {
            controlsVisible = false
            // 锁定时手势层会被整个停掉（见 PlayerScreen 的 gesturesEnabled），
            // 「松手」那个回调在拖动中途被打断的情况下也会跑（手势层里有 finally），
            // 但多一步兜底：提示泡上写着「2× 播放中」而实际早就恢复了，是那种
            // 看一眼就明白、却完全不知道从哪查起的假信息。
            speedBoost = null
        }
    }

    fun toggleControls() {
        if (locked) {
            lockHintVisible = true
        } else {
            controlsVisible = !controlsVisible
        }
    }

    fun hideControls() {
        if (!locked) controlsVisible = false
    }

    /** 露出解锁按钮（点画面、或者按返回键）。 */
    fun revealLockedControls() {
        lockHintVisible = true
    }

    fun hideLockHint() {
        lockHintVisible = false
    }

    /**
     * 显示/收起亮度音量提示。
     *
     * 三个提示泡都画在**同一个位置**（画面正中），所以这里顺手把中间让出来：
     * 双击播放/暂停之后一秒内又开始拖动的话，两个胶囊会叠在一起，看着像界面坏了。
     *
     * 让位只在**显示**时发生（`hint != null`）：传 null 是「收起」，而一个提示泡
     * 的计时器到期不该把另一个无关的提示也收走。
     */
    fun applyLevelHint(hint: PlayerLevelHint?) {
        levelHint = hint
        if (hint != null) playPauseHint = null
    }

    /** 显示/收起进度提示。@param hint null = 收起（让位规则见 [applyLevelHint]）。 */
    fun applySeekHint(hint: PlayerSeekHint?) {
        seekHint = hint
        if (hint != null) playPauseHint = null
    }

    /**
     * 显示/收起双击播放/暂停的提示。
     *
     * @param playing true = 刚切成播放中，false = 刚切成已暂停，null = 收起。
     */
    fun applyPlayPauseHint(playing: Boolean?) {
        playPauseHint = playing
        if (playing != null) {
            levelHint = null
            seekHint = null
        }
    }

    /** 开始/结束「按住加速」。@param speed null = 已松手。 */
    fun applySpeedBoost(speed: Float?) {
        speedBoost = speed
    }

    /**
     * 本次播放用哪个画面比例（把设置里的默认值作为兜底）。
     *
     * @param default 设置里存的默认比例。**每次读**而不是存起来：设置可能比
     *   这一页晚到，存起来就永远差一次。
     */
    fun aspectRatio(default: AspectRatioMode): AspectRatioMode = aspectRatioOverride ?: default

    /**
     * 临时改画面比例，**不写回设置**。
     *
     * 「裁掉两边」看一部老片是这一部片子的事，不应该让下一部也默认被裁。
     * 永久改默认值的地方在设置页（见 `PlaybackSettings.aspectRatioMode` 的注释）。
     */
    fun setAspectRatio(mode: AspectRatioMode) {
        aspectRatioOverride = mode
    }

    /**
     * 打开一个面板，并把控制条一并翻开。
     *
     * 收控制条的逻辑是「播放中 4 秒不动就收」，面板打开时它会把控制条收走，
     * 于是关掉面板之后下面的按钮位置全变了——用户回来点的东西和刚才看到的不是一回事。
     */
    fun openSheet(sheet: PlayerSheet) {
        openSheet = sheet
        controlsVisible = true
    }

    fun closeSheet() {
        openSheet = null
    }
}

/**
 * 记住一个 [PlayerUiState]。
 *
 * @param initialFullscreen 初值。**必须**由调用方按「当前是否横屏」传入，
 *   而不是默认 false 再靠 `LaunchedEffect` 补一次：后者会让横屏进入播放页时
 *   先按竖屏布局画一帧，再跳成横屏——那是一次肉眼可见的闪动。
 */
@Composable
fun rememberPlayerUiState(initialFullscreen: Boolean = false): PlayerUiState =
    remember { PlayerUiState(initialFullscreen = initialFullscreen) }
