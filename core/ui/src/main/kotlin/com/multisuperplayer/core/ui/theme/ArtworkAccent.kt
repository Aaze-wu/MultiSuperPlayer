package com.multisuperplayer.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 从当前封面推导出的强调色，形状和 [MspAccent] 声明的那两组种子完全一致。
 *
 * 刻意不复用 [MspAccent]：那个是**用户可选列表**，多一个「封面」条目会让
 * 「用户选了哪个」和「当前实际用的是哪个」两件事混在一个枚举里——
 * 于是「保存到 DataStore」就会存下一个并不属于列表的 id。
 */
@Immutable
data class ArtworkAccent(
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
)

/**
 * 封面强调色的**持有者**。
 *
 * ## 为什么要一个可变对象，而不是直接用一个 CompositionLocal 传值
 *
 * 取色是异步的（要解码封面、跑量化），而 [MspTheme] 在整棵树的**最外层**。
 * 如果做成「值型」的 CompositionLocal，就得由 App 这个层级去持有状态、
 * 再往下传；但真正知道「现在放的是哪张封面」的是播放页，它在树的**很深处**。
 * 让深处的人写、浅处的人读，中间放一个共享的可变对象是唯一不要从 App 到
 * 播放页打通一条参数链的做法。
 *
 * [value] 是 `mutableStateOf`，所以只有真正读它的那个 `@Composable`
 * （也就是 [MspTheme] 里计算配色方案的那一小段）会在换封面时重组，
 * 而不是整棵子树——这正是用 [staticCompositionLocalOf] 传「值」
 * 反而做不到的细粒度。
 */
@Stable
class ArtworkAccentState {

    var value: ArtworkAccent? by mutableStateOf(null)
        private set

    fun update(accent: ArtworkAccent?) {
        value = accent
    }

    /**
     * 换歌时用：先清空再交给调用方写入新值。
     *
     * 不这么做的话，「上一首有封面、这一首没有」会让界面**留在上一张专辑的
     * 颜色上**，看起来像是取值错了，其实是没人擦掉它。
     */
    fun clear() {
        value = null
    }
}

private val NoopArtworkAccentState = ArtworkAccentState()

/**
 * 封面强调色的读（[MspTheme]）/ 写（播放页）通道。
 *
 * 没人提供时给一个共享的空实现，这样预览和单测里 `PlayerScreen` 直接
 * 渲染也不会因为「local 没提供」而炸——写进一个没人读的对象是无害的。
 */
val LocalArtworkAccentState = staticCompositionLocalOf { NoopArtworkAccentState }

/** 语法糖：`val accent = LocalArtworkAccent.current`。 */
val LocalArtworkAccent: ArtworkAccent?
    @Composable get() = LocalArtworkAccentState.current.value
