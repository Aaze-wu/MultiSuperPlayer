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
 * 这件事需要几个**外界看不见**的输入：用户在设置里有没有关掉它、哪一条是「当前那条」、
 * 以及用户是不是把这一页点名成了「全量滚动」。
 * 把它们当参数一层层传下去是不可能的——列表行藏在 `ListItem` 的槽位里、浏览页的行是
 * 私有 composable、队列行在另一个模块（`feature:player`），中间要穿过 6 个文件。
 * 而这些值对**所有**列表都是同一份，正是 Compose 里 CompositionLocal 的用途。
 *
 * 默认值是 `enabled = false` 而不是「设置里的默认值」：
 * 预览（`@Preview`）和任何没被 App 包起来的调用点都不该自己动起来。
 * 真正的默认值（默认**开**）只写在应用根那一处，见 [TitleScrollRules.DEFAULT_ENABLED]。
 */
val LocalTitleScrollState = staticCompositionLocalOf { TitleScrollState() }

/**
 * 「可以单独指定为始终滚动」的页面。
 *
 * ## 为什么需要这一层
 *
 * 「只有当前那一条滚」是**列表被人一条条读**时的正确规则，但它有一个前提：
 * 用户此刻的注意力集中在一条上（正在播的那条、刚勾的那条）。
 * 而**浏览页（文件选择器）和媒体库不是这个用法**——用户在那里的任务是
 * 「在一堆文件里找一个」，眼睛是横向扫过去的，一屏十条都可能是目标。
 * 此时只让播放中的那一条动，等于这个功能在最需要它的两页上等于不存在。
 *
 * 反过来，全页一起滚也不该是默认：一屏十条长名字同时爬，页面变成一块布告栏。
 * 所以规则改成**按页面显式开启**：默认一个都不开（行为与上一版完全一致），
 * 用户在设置里点名哪些页面要全量滚动。
 *
 * ## 为什么「整页滚」不会让短名字也晃起来
 *
 * `basicMarquee` 自己就会判断溢出：`MarqueeModifierNode.measure` 把子项按
 * `Constraints.Infinity` 量完之后，动画协程第一句是
 * `if (contentWidth <= containerWidth) return@snapshotFlow null`（见
 * `androidx.compose.foundation.BasicMarquee.kt`，1.11.4）。
 * 所以这里**不需要**自己量文字宽度，也不需要在外面再套一层溢出判断——
 * 放得下的名字即使挂着 marquee 也一动不动。
 *
 * ## `id` 是持久化契约
 *
 * 这些字符串会写进用户设备的 DataStore（`DisplaySettings.alwaysScrollPages`）。
 * **改名等于把所有人设过的页面清空**，所以有单测把它们逐字锁住。
 */
enum class TitleScrollPage(val id: String) {
    /** 媒体库。 */
    LIBRARY("library"),

    /** 浏览（文件选择器）的目录内容与来源清单。 */
    BROWSE("browse"),

    /** 最近播放。 */
    RECENT("recent"),

    /** 收藏（文件行与文件夹行）。 */
    FAVORITES("favorites"),

    /** 播放列表：清单页的列表名，以及详情页里的条目。 */
    PLAYLISTS("playlists"),

    /** 播放页的上滑队列。 */
    QUEUE("queue"),

    /** 网络地址的历史清单。 */
    NETWORK("network"),
    ;

    companion object {
        /**
         * 从持久化的 id 还原。认不出来的 id 返回 `null`（不抛异常）。
         *
         * 「认不出来」是会真实发生的：新版本加了一页、用户设过，然后降级安装回来。
         * 那时候宁可少滚一页，也不能让设置页崩掉。
         */
        fun fromId(id: String?): TitleScrollPage? =
            entries.firstOrNull { page -> page.id == id }

        /** 把仓库里那串 id 变成页面集合，认不出来的丢掉。 */
        fun known(ids: Set<String>?): Set<TitleScrollPage> =
            ids.orEmpty().mapNotNullTo(LinkedHashSet()) { fromId(it) }
    }
}

/**
 * 滚动的三个输入。
 *
 * 为什么是 `data class` 而不是几个 CompositionLocal：`staticCompositionLocalOf` 的
 * 失效粒度是**整个值**，分开放只会让几棵子树各自重组一次；合在一起，
 * 「切了当前播放项」和「用户关了开关」走同一条路径、只需要写一次。
 */
data class TitleScrollState(
    /** 用户设置里的开关，`null` 在仓库里表示「没设置过」，到这一层必须已经解析成布尔。 */
    val enabled: Boolean = false,
    /** 当前播放的那一条的 id；null = 没在播。队列/媒体库/收藏/播放列表都按它认「当前」。 */
    val currentEntryId: String? = null,
    /**
     * 用户点名「这一页所有长名字都滚」的页面。空集 = 全部页面都按「只有当前那一条滚」。
     *
     * 这是**页面级**的开关，不是行级的：它由设置页写、由 App 根一次性注入，
     * 行自己无权改动。
     */
    val alwaysScrollPages: Set<TitleScrollPage> = emptySet(),
)

/**
 * 「这一行该不该滚」的全部判断。
 *
 * ## 为什么只有一条在滚
 *
 * 长文件名滚动是**主动吸引注意力**的动画。若列表里十条长名字同时爬，一屏就变成
 * 一块会动的布告栏：既看不出哪一条在播，也读不进去任何一条（人眼无法同时读两段
 * 匀速平移的文字）。所以默认规则是「只有当前播放/选中的那一条滚，其余照旧单行省略」。
 *
 * 这条默认规则的**例外**是按页面点名：浏览页与媒体库上用户是在横向扫文件名，
 * 只滚一条等于这个功能在最需要它的地方不存在。哪些页面被点名由用户在设置里决定，
 * 判断落在 [alwaysScrolls] 里（见 [TitleScrollPage] 的完整理由）。
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
     * 这一行所在的页面是否被用户点名「全量滚动」。
     *
     * [page] 为空表示「这一行不属于任何可配置页面」（对话框里的列表、预览）：
     * 那种情况下退回「只有当前那一条滚」。
     */
    fun alwaysScrolls(page: TitleScrollPage?, pages: Set<TitleScrollPage>): Boolean =
        page != null && page in pages

    /**
     * 最终判据。任一条不满足就退回「单行 + 省略号」。
     *
     * [alwaysScroll] 是**页面级**的通行证：用户在设置里把这一页点名之后，
     * 「是不是当前那一条」不再参与判断——他要的是把这一页的文件名全扫一遍。
     * 注意它**不能**越过 [enabled] 与 [animationsEnabled]：页面勾选是从属于总开关的，
     * 总开关关掉之后逐页勾选只是留着勾痕（界面上也一并置灰），不应该还在滚。
     *
     * [text] 为空也返回 false：空串上挂 marquee 只是每 1.4 秒空转一圈，
     * 白耗帧。
     */
    fun shouldScroll(
        enabled: Boolean,
        animationsEnabled: Boolean,
        isCurrent: Boolean,
        alwaysScroll: Boolean,
        text: String,
    ): Boolean = enabled && animationsEnabled && (alwaysScroll || isCurrent) && text.isNotBlank()

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
 * [page] **必须**由调用方说明「这一行长在哪一页」。它是用户能不能把这一页设成
 * 「所有长名字都滚」的唯一依据，所以刻意**不给默认值**：漏传就是编译错误，
 * 而不是一个静默发现不了的功能缺口（这个功能坏了的表现只是「不滚」，
 * 没人会因此报错）。不属于任何列表页的场合传 `null`。
 * [selected] 供多选态使用（勾上的那一行也要滚：用户正在决定要不要它）。
 *
 * 样式与颜色必须能透传（队列行用它把当前项画成 primary 色），所以两个参数都保留
 * 「不传就继承」的默认值。
 */
@Composable
fun ScrollingTitleText(
    text: String,
    entryId: String?,
    page: TitleScrollPage?,
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
        alwaysScroll = TitleScrollRules.alwaysScrolls(page, scrollState.alwaysScrollPages),
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
