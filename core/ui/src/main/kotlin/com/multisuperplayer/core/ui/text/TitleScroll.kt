package com.multisuperplayer.core.ui.text

import android.animation.ValueAnimator
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * 「长文件名滚动显示」的环境通道。
 *
 * 这件事需要两个**外界看不见**的输入：用户在设置里有没有关掉它，以及哪一条是「当前那条」。
 * 把它们当参数一层层传下去是不可能的——列表行藏在 `ListItem` 的槽位里、浏览页的行是
 * 私有 composable、队列行在另一个模块（`feature:player`），中间要穿过 6 个文件。
 * 而这两个值对**所有**列表都是同一份，正是 Compose 里 CompositionLocal 的用途。
 *
 * 默认值是 `enabled = false` 而不是「设置里的默认值」：
 * 预览（`@Preview`）和任何没被 App 包起来的调用点都不该自己动起来。
 * 真正的默认值（默认**开**）只写在应用根那一处，见 [TitleScrollRules.DEFAULT_ENABLED]。
 */
val LocalTitleScrollState = staticCompositionLocalOf { TitleScrollState() }

/**
 * 滚动的两个输入。
 *
 * 为什么是 `data class` 而不是两个 CompositionLocal：`staticCompositionLocalOf` 的
 * 失效粒度是**整个值**，两个值分开放只会让两棵子树各自重组一次；合在一起，
 * 「切了当前播放项」和「用户关了开关」走同一条路径、只需要写一次。
 */
data class TitleScrollState(
    /** 用户设置里的开关，`null` 在仓库里表示「没设置过」，到这一层必须已经解析成布尔。 */
    val enabled: Boolean = false,
    /** 当前播放的那一条的 id；null = 没在播。队列/媒体库/收藏/播放列表都按它认「当前」。 */
    val currentEntryId: String? = null,
)

/**
 * 「这一行该不该滚」的全部判断。
 *
 * ## 为什么只有一条在滚
 *
 * 长文件名滚动是**主动吸引注意力**的动画。若列表里十条长名字同时爬，一屏就变成
 * 一块会动的布告栏：既看不出哪一条在播，也读不进去任何一条（人眼无法同时读两段
 * 匀速平移的文字）。所以规则是「只有当前播放/选中的那一条滚，其余照旧单行省略」。
 *
 * ## 为什么连「系统移除动画」都要算进来
 *
 * 无障碍设置里把动画时长设为 0 之后，Compose 的 `animate*AsState` 会自己跳过动画，
 * 但 `basicMarquee` 走的是 `withFrameNanos` 循环，**不看那个设置**——它会在
 * 「我关了动画」的用户屏幕上继续爬。这是一段纯装饰动画，没有它信息一点不少，
 * 所以读 `ValueAnimator.areAnimatorsEnabled()` 直接不滚。
 *
 * ## 为什么全是纯函数
 *
 * `core:ui` 与 `feature:library` 都没有 `compose-ui-test` 依赖，布局和动画在 JVM 单测里
 * 一行都测不到。能测的只有这类判断，所以规则一点不留在 composable 里。
 */
object TitleScrollRules {

    /**
     * 用户没设置过时，滚动**是开着的**。
     *
     * 这是「UI 层的默认值」——仓库里那个键是 `Boolean?`，`null` 才表示没设置过，
     * 默认值的决定权必须留在这里（改默认值不影响已经显式关掉的人）。
     */
    const val DEFAULT_ENABLED: Boolean = true

    /**
     * 这一行是不是「当前那一条」。
     *
     * 两种来源：正在播放的（[entryId] 与 [currentEntryId] 相同），或者被用户勾选的。
     * 勾选优先且独立：多选态下用户正在看的是一批勾选框，这时候「哪一条在播」已经不是
     * 他此刻操作的对象了——他要数自己选了几条。
     *
     * [entryId] 为空就当不上「当前」：没有 id（`mediaId` 拼不出、未知条目）时宁可不动，
     * 也不要让整页的第一条莫名其妙地滚起来。
     */
    fun isCurrent(entryId: String?, currentEntryId: String?, selected: Boolean): Boolean {
        if (selected) return true
        return !entryId.isNullOrBlank() && entryId == currentEntryId
    }

    /**
     * 最终判据。四个条件缺一不可，任一条不满足就退回「单行 + 省略号」。
     *
     * [text] 为空也返回 false：空串上挂 marquee 只是每 1.4 秒空转一圈，
     * 白耗帧。
     */
    fun shouldScroll(
        enabled: Boolean,
        animationsEnabled: Boolean,
        isCurrent: Boolean,
        text: String,
    ): Boolean = enabled && animationsEnabled && isCurrent && text.isNotBlank()

    /**
     * 平移开始前先停多久（毫秒）。
     *
     * 900ms：用户手指离开、眼睛落到那一行的时间。太短会让「刚点开一个列表」和
     * 「第一条开始爬」撞在一起，看着像界面自己在抖；太长（2 秒以上）则像坏了。
     */
    const val INITIAL_DELAY_MILLIS: Int = 900

    /**
     * 爬完一整圈后停多久再从头开始（毫秒）。
     *
     * 1400ms：留出「读完尾巴」和「视线回到开头」的时间。用的是
     * `basicMarquee` 的默认节奏，改它只改这一处。
     */
    const val REPEAT_DELAY_MILLIS: Int = 1_400
}

/**
 * 单行标题：**该滚的时候滚，其余时候和以前一模一样**（一行 + 省略号）。
 *
 * 存在的意义是「只有一处实现」。列表行（媒体库/最近/收藏/播放列表）、浏览页的文件行、
 * 播放页的队列行各写一遍 `if (scrolling) Modifier.basicMarquee() else …` 的话，
 * 迟早会出现「设置里关了，队列里还在爬」这种事——那种 bug 极难被自己发现，
 * 因为验证时手上只有一条长名字。
 *
 * [entryId] 只用来判断「是不是当前那一条」，不参与渲染。
 * [selected] 供多选态使用（勾上的那一行也要滚：用户正在决定要不要它）。
 *
 * 样式与颜色必须能透传（队列行用它把当前项画成 primary 色），所以两个参数都保留
 * 「不传就继承」的默认值。
 */
@Composable
fun ScrollingTitleText(
    text: String,
    entryId: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val scrollState = LocalTitleScrollState.current
    // 系统的「移除动画」（无障碍 / 省电模式下也会打开）。它随时会被改，
    // 而改完系统会重建/重组界面，所以在这里读一次就够。
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val scrolling = TitleScrollRules.shouldScroll(
        enabled = scrollState.enabled,
        animationsEnabled = animationsEnabled,
        isCurrent = TitleScrollRules.isCurrent(
            entryId = entryId,
            currentEntryId = scrollState.currentEntryId,
            selected = selected,
        ),
        text = text,
    )
    Text(
        text = text,
        modifier = if (scrolling) {
            // `iterations = Int.MAX_VALUE` 就是「一直滚」，和默认值等价，
            // 但写出来是为了让下一眼看得出这不是「滚一次就停」。
            // 间距不传：默认是容器宽度的 1/3，两圈之间留得开。
            modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = TitleScrollRules.INITIAL_DELAY_MILLIS,
                repeatDelayMillis = TitleScrollRules.REPEAT_DELAY_MILLIS,
            )
        } else {
            modifier
        },
        style = style,
        color = color,
        // 滚动时用 `Clip` + `softWrap = false`：`basicMarquee` 把子项按**无限宽**测量，
        // 这时候 `Ellipsis` 永远不会命中（没有溢出可言），写它只会误导下一个读代码的人。
        maxLines = 1,
        softWrap = false,
        overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}
