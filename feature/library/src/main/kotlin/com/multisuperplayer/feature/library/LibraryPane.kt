package com.multisuperplayer.feature.library

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryState

/**
 * 列表区域「这一刻到底该画什么」。
 *
 * ## 为什么要把这几个分支从 composable 里搬出来
 *
 * 它原本是 `LibraryScreen` 里一段 `when` 的裸条件 + 一个平行的 `if`。那个写法有两个后果：
 *
 * 1. `Ready` 这一个分支同时覆盖了「有内容」和「扫到 0 条」两种情况，于是只要扫描
 *    成功就无条件画一次空状态；而列表又是同一层 `Box` 里**另一个** `if` 画的。
 *    结果就是「还没有扫描到媒体」和 3 条媒体条目同时压在屏幕上。真机上复现过。
 * 2. 想覆盖这些分支就必须起 Compose 测试环境，于是 100 多个纯 JVM 单测一个都拦不住它。
 *
 * 搬成纯函数之后：每个分支都只是一次普通断言，而且 [Content] 成了「画列表」的
 * **唯一出口** —— 列表和空状态在类型上就互斥，不可能再叠在一起。
 */
internal sealed interface LibraryPane {

    /** 首次扫描还没出结果。 */
    data object Loading : LibraryPane

    /** 没拿到读媒体的权限。 */
    data object NeedsPermission : LibraryPane

    /** 库里一条都没有，并且**不是**因为搜索/筛选挡掉了。 */
    data object NoMedia : LibraryPane

    /** 库里有内容，只是被当前搜索或筛选挡光了。 */
    data object FilteredOut : LibraryPane

    /** 扫描失败，[message] 是给用户看的原因（已跟随仓库层的类型变成可翻译的文本）。 */
    data class Failure(val message: MspText) : LibraryPane

    /** 正常渲染列表。 */
    data object Content : LibraryPane
}

/**
 * 由状态推导出该画哪个分支。
 *
 * `when (val lib = library)` 是对 sealed interface 的穷尽匹配（无 `else`）：将来
 * `MediaLibraryState` 多一个状态，这里会直接编译失败，而不是默默落进某个分支。
 */
internal val LibraryUiState.pane: LibraryPane
    get() = when (val lib = library) {
        is MediaLibraryState.Loading -> LibraryPane.Loading
        is MediaLibraryState.NeedsPermission -> LibraryPane.NeedsPermission
        is MediaLibraryState.Error -> LibraryPane.Failure(lib.message)
        is MediaLibraryState.Ready -> when {
            // 注意顺序：先判「库里真的没有」，再判「被挡光了」。
            // 反过来写的话，扫描到 0 条会走进 FilteredOut，提示变成「没有匹配的内容」，
            // 也就是把「本机没有文件」说成了「你没搜到」。
            lib.entries.isEmpty() -> LibraryPane.NoMedia
            filteredOut -> LibraryPane.FilteredOut
            else -> LibraryPane.Content
        }
    }
