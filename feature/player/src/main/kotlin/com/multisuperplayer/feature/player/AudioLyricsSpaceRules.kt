package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/**
 * 音频页「歌词到底能拿到多少空间」的规则。
 *
 * ## 为什么要有这个文件
 *
 * 「留给歌词的空间太少」不是一个可以靠调一个 dp 数字解决的问题：音频页的高度被
 * 切成好几段（封面 / 标题 / 进度条 / 功能芯片 / 传输控制 / 会话芯片），**每一段都是
 * 定高的**，只有歌词拿剩下的。屏幕一矮，被挤掉的就全是歌词——而且是按比例挤的，
 * 所以在模拟器上看着还行、换台机器就只剩两行。
 *
 * 唯一稳的解法是**把「谁让位」写下来**，而不是把某一段调小一点：
 *
 * 1. 竖屏：封面是装饰，歌词是内容。空间不够先缩封面，缩到 0（连那道空隙一起收掉），
 *    保底给歌词 [MIN_LYRICS_HEIGHT]。
 * 2. 横屏：控件块在歌词**下面**，它多高歌词就少多高（实测约 172dp ≈ 4 行）。
 *    所以能搬就搬到**左栏**去——左栏在歌词旁边，不吃它的高度。搬不搬得动只看宽度
 *    （见 [landscapeControlsPlacement]），够宽就搬，不够宽就退回原版面。
 * 3. 两种情况都可能把歌词压到很紧，那时把歌词自己的内边距收窄（[isLyricsViewportTight]），
 *    换回来大约半行。
 *
 * ## 为什么是纯函数
 *
 * 和 `AudioLandscapeRules` / `TransportLayoutRules` 同一条理由：本模块里 Compose 布局
 * 跑不进 JVM 单测，但「判断」可以。这里的每个函数都对手里没有任何空间（0）、
 * 无界约束（`Dp.Infinity`）给出确定答案，实机上量到 0 的那一帧不会变成负高度。
 */
internal object AudioLyricsSpaceRules {

    // ---------------------------------------------------------------- 竖屏

    /** 有歌词时封面的**上限**边长。不是「固定尺寸」：空间不够时它会被继续缩小。 */
    internal val PORTRAIT_ARTWORK_MAX = 112.dp

    /**
     * 封面和歌词之间那道空隙。
     *
     * 只在封面真的画出来时才占位置：封面缩到 0 的时候这道空隙也得一起消失，
     * 否则「封面已经让位了」而歌词仍然被一条 12dp 的空白压着。
     */
    internal val ARTWORK_GAP = 12.dp

    /**
     * 歌词区至少要留这么高（含它自己的上下内边距 48dp）。
     *
     * 200dp 对应大约 3 行半歌词。这是「能不能称得上歌词区」的下限，不是舒适值：
     * 竖屏舞台一般有 400~500dp，正常情况封面根本不会被缩到。
     */
    internal val MIN_LYRICS_HEIGHT = 200.dp

    /**
     * 小于这个边长就不画封面了。
     *
     * 不是审美标准，是因为「让位」是一个硬阈值：舞台矮到刚好多出 1dp 时，
     * 上面那个公式会给出一个 **1dp 的封面**——屏幕上是一个认不出来的圆点，
     * 却依然吃掉 [ARTWORK_GAP] 加它自己的高度。小到这一步就直接不画，把空间
     * 全给歌词（代价是歌词会从「没有封面时的高度」一下落到 [MIN_LYRICS_HEIGHT]，
     * 这是硬保底的必然结果，不是 bug）。
     */
    internal val PORTRAIT_ARTWORK_MIN = 32.dp

    /**
     * 竖屏封面边长。
     *
     * 空间够（舞台高 ≥ 112 + 12 + 200）时就是 [PORTRAIT_ARTWORK_MAX]，不够时缩到刚好
     * 留下 [MIN_LYRICS_HEIGHT]，再不够就返回 0（这一帧不画封面，连空隙一起收掉）。
     *
     * @return 0 或 [PORTRAIT_ARTWORK_MIN]..[PORTRAIT_ARTWORK_MAX]——**中间没有值**：
     *   要么画一个至少认得出的封面，要么不画。
     */
    fun portraitArtworkSize(stageHeight: Dp): Dp {
        // 无界约束（放在可滚动容器里）时 `room` 是无穷大，`min` 自然给出上限；
        // 高度为 0 或负（还没量出来）时 `room` 是负数。两条退化输入都不需要单独分支。
        val room = stageHeight - MIN_LYRICS_HEIGHT - ARTWORK_GAP
        val size = min(PORTRAIT_ARTWORK_MAX, room)
        return if (size < PORTRAIT_ARTWORK_MIN) 0.dp else size
    }

    /**
     * 竖屏歌词区实际拿到的高度。
     *
     * 和 [portraitArtworkSize] 必须**成对使用**：那个函数决定封面多大，这个函数回答
     * 「封面让完之后歌词还剩多少」。分开算的话，两处对「封面为 0 时空隙还算不算」
     * 的理解一不一致就没人管了。
     */
    fun portraitLyricsHeight(stageHeight: Dp): Dp {
        val artwork = portraitArtworkSize(stageHeight)
        val gap = if (artwork > 0.dp) ARTWORK_GAP else 0.dp
        return (stageHeight - artwork - gap).coerceAtLeast(0.dp)
    }

    // ------------------------------------------------------------ 歌词视口

    /**
     * 这么矮的歌词视口就把内边距收窄。
     *
     * 260dp ≈ 5 行歌词：到这个尺寸上下各 24dp 的留白已经占掉了四分之一的高度，
     * 那时候「多一行歌词」比「留白好看」重要得多。
     */
    internal val LYRICS_TIGHT_THRESHOLD = 260.dp

    /** 歌词视口是不是紧到该收内边距了。未知（0 / 无界）一律按「不紧」处理。 */
    fun isLyricsViewportTight(viewportHeight: Dp): Boolean =
        viewportHeight > 0.dp && viewportHeight < LYRICS_TIGHT_THRESHOLD

    // ---------------------------------------------------------------- 横屏

    /**
     * 音频横屏传输控制那一行的侧按钮个数。
     *
     * **必须**和 `PlayerTransportControls` 里 `fullscreen = null` 时的算法对上
     * （4 个图标 + 播放键 = 5 个侧位）。多算一个会让左栏的门槛凭空抬高 48dp，
     * 少算一个会让搬过去的按钮溢出——见 [LEFT_COLUMN_MIN_WIDTH]。
     */
    private const val SIDE_SLOTS = 5

    /** 左栏左右两侧各留的空白（和 `AudioLandscapeLayout` 里那个 `padding(12.dp)` 是同一份）。 */
    internal val LEFT_COLUMN_PADDING = 12.dp

    /**
     * 左栏内部真正能用的宽度下限。
     *
     * 直接问 `TransportLayoutRules`，不抄数字：`48 × 5 + 56 = 296dp` 是「播放键还是它
     * 原本的大小」的宽度。比这窄一点也排得下（播放键会缩到和旁边一样大），但那一档
     * 已经不是原来那个「大一圈的主操作」了，没必要把它也算成可用。
     */
    internal val LEFT_COLUMN_CONTENT_MIN =
        TransportLayoutRules.BUTTON_MIN * SIDE_SLOTS + TransportLayoutRules.PLAY_MAX_COMPACT

    /** 左栏总宽下限：内容 + 两侧空白。 */
    internal val LEFT_COLUMN_MIN_WIDTH = LEFT_COLUMN_CONTENT_MIN + LEFT_COLUMN_PADDING * 2

    /**
     * 左栏最多占屏宽的比例。
     *
     * 控件搬过去之后左栏要装下那一排按钮，但**不能**为了装下它去挤歌词：超过 45%
     * 之后歌词就会窄到频繁折行。所以宽度放不下时宁可不搬（[LandscapeControlsPlacement.UnderLyrics]），
     * 也不把比例继续往上推。
     */
    internal val LEFT_WEIGHT_MAX = 0.45f

    /**
     * 控件搬进左栏时，左栏要为那两排额外留出的高度。
     *
     * 40dp（芯片行，`TextButton` 的最小高度）+ 60dp（`compact` 传输行：6+6 内边距 + 48
     * 的可点区域）+ 两段间距 ≈ 120dp。封面是从**高度**里让位的，不预留这一段的话
     * 封面会把下面两排顶出屏幕（`Column` 里那个 `weight(1f)` 只是「优先被压缩」，
     * 不保证一定放得下）。
     */
    internal val LANDSCAPE_CONTROLS_RESERVE = 120.dp

    /**
     * 横屏把控件放在哪一栏。
     *
     * 判据只有宽度，理由见 [LEFT_WEIGHT_MAX]：左栏按比例最多能占 45%，而它至少要
     * 320dp 才装得下那两排控件，所以屏宽 `320 / 0.45 ≈ 711dp` 是分界线。常见手机
     * 横屏是 800~930dp（360~411dp 的竖屏宽），都过线；真的窄窗口（分屏、小屏老设备）
     * 就退回原来的版面，那里的歌词本来也够看。
     *
     * @return [LandscapeControlsPlacement.InLeftColumn]（带左栏权重）或
     *   [LandscapeControlsPlacement.UnderLyrics]。宽度还没量出来（0 或负）时按
     *   「能搬」处理，避免第一帧先按窄窗口画一遍再跳。
     *
     * 注意搬得动时左栏宽度就**恒为** [LEFT_COLUMN_MIN_WIDTH]（屏再宽也是这个值）：
     * 权重取的是「刚好够装下那两排」的比例，所以多出来的宽度全部归歌词。
     */
    fun landscapeControlsPlacement(pageWidth: Dp): LandscapeControlsPlacement {
        if (pageWidth <= 0.dp) {
            return LandscapeControlsPlacement.InLeftColumn(leftWeight = LEFT_WEIGHT_MAX)
        }
        // 判据写成「屏宽的 45% 够不够左栏」而不是「先算权重再乘回去够不够」：
        // `W × (320 ÷ W) ≥ 320` 在浮点下**并不总成立**（实测 1220dp 会算成 319.99997），
        // 判据于是会在边界两侧来回翻——同一个屏宽一会儿搬一会儿不搬。
        // 乘一个正常数在浮点下是单调的，所以这样写既不会翻也不违反单调性。
        return if (pageWidth * LEFT_WEIGHT_MAX >= LEFT_COLUMN_MIN_WIDTH) {
            // 权重取「刚好装下左栏」的比例，于是左栏宽度恒等于下限：多出来的宽度
            // 全归歌词（否则平板上歌词会比手机上还窄）。
            LandscapeControlsPlacement.InLeftColumn(leftWeight = LEFT_COLUMN_MIN_WIDTH / pageWidth)
        } else {
            LandscapeControlsPlacement.UnderLyrics
        }
    }
}

/**
 * 横屏音频页的控件放在哪一栏。
 *
 * 两种结果各自对应一套**完整的**版面，不存在「一半搬过去」的中间态：搬过去之后
 * 右栏底部只剩进度条，右栏因此不再需要那条 `Surface` 分区。
 */
internal sealed interface LandscapeControlsPlacement {

    /**
     * 控件搬到左栏（封面和标题下面），右栏整栏留给歌词。
     *
     * @param leftWeight 左栏占屏宽的比例，右栏用 `1 - leftWeight`。
     */
    data class InLeftColumn(val leftWeight: Float) : LandscapeControlsPlacement

    /** 窗口不够宽：控件照旧压在歌词下面（原版面）。 */
    data object UnderLyrics : LandscapeControlsPlacement
}
