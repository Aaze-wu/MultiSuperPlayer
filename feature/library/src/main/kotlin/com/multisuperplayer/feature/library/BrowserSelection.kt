package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry

/**
 * 当前目录里**能进多选**的条目，顺带转成 [MediaEntry]。
 *
 * 判据就是「点一下会不会去播放」（[BrowserEntry.playable]：是文件、且不是明确
 * 非媒体的后缀）。目录进去是另一层，字幕是给正在播的那条挂的，两者被选中之后
 * **没有任何动作可做**——而操作条上写着「已选 3 项」，其中一项却播不了，
 * 这个数字就是假话。
 *
 * 返回的是转换后的媒体条目而不是原条目：多选的两个动作（播放、加入播放列表）
 * 都只吃 [MediaEntry]，在这一步转完，界面层和 [LibrarySelectionRules] 都不需要
 * 再关心两种条目类型的区别。转换不出来的（`toMediaEntry()` 返回 null）自然被剔掉。
 *
 * 顺序原样保留（跟屏幕上看到的排列一致）：队列的顺序必须是用户眼里的顺序。
 */
internal fun selectableMedia(entries: List<BrowserEntry>): List<MediaEntry> =
    entries.mapNotNull { if (it.playable) it.toMediaEntry() else null }

/**
 * 把选择集剪到「当前这份可播清单里还存在的」那些 id。
 *
 * 必须剪：进另一个目录、换排序、文件被删之后，选择集里会留下**屏幕上没有**的
 * 条目，操作条于是显示「已选 5 项」而只有 2 行是勾上的，而「播放」会播到
 * 用户看不见的东西。空集合直接返回**同一个实例**，这样调用方可以靠
 * `!==` 判断「要不要写回状态」，不必白触发一次重组。
 */
internal fun pruneSelection(selected: Set<String>, selectable: List<MediaEntry>): Set<String> {
    if (selected.isEmpty()) return selected
    val alive = selectable.mapTo(HashSet()) { it.id }
    val pruned = selected.intersect(alive)
    return if (pruned.size == selected.size) selected else pruned
}

/**
 * 勾 / 取消勾一个 id，**但清单里没有的 id 一律不接**。
 *
 * 不接是必须的：目录和字幕行在界面上**照样画着勾选框**（只是禁用），长按又落在
 * 整行上，所以「把一个不可选的 id 塞进选择集」是一件很容易发生的事。真发生了会
 * 变成最难看的那种错——`ids` 里有 1 个、`count` 是 0，于是操作条写着「已选 0 项」
 * 而某一行**画着对勾**，两个数字当面对不上（实测就是这样：长按一个目录，
 * 它的方框变成了勾选态，计数却是 0）。
 *
 * 用 [pruneSelection] 那套「先塞进去再剪」也能得到同样的集合，但那要求调用方
 * 保证每次改动都过一遍剪枝；这里在入口上判一次，代价是每次点击一遍 O(清单长度)，
 * 换来的是「不可选的 id 根本进不来」这条不变式。
 */
internal fun toggleSelection(selected: Set<String>, selectable: List<MediaEntry>, id: String): Set<String> =
    if (selectable.any { it.id == id }) LibrarySelectionRules.toggle(selected, id) else selected

/**
 * 目录页的多选接线。
 *
 * 打成一个包而不是给 `DirectoryBrowser` 加九个参数：这几个东西**要么同时有用、
 * 要么同时没用**（就是 [mode]），散开之后漏传一个的表现只是「某个按钮点了没反应」，
 * 而这种错很难从界面上看出是哪一层没接上。
 *
 * [ids] 存的是 `MediaEntry.id`（也就是 [BrowserEntry.mediaId]），不是 [BrowserEntry.ref]：
 * 集合里的元素最终要交给播放器、写进播放列表，那两件事都以媒体 id 为准，
 * 在这里换一次身份键，两边的答案就永远对得上。
 *
 * [count] 单列一个字段而不是用 `ids.size`：它必须是**当前这份目录里还看得见的**
 * 选中数（进目录、换排序、文件消失都要重新算）。两个数字在切换的那一帧会不一致，
 * 而界面上显示的那个必须是看得见的那个。
 */
internal class BrowserSelection(
    val ids: Set<String> = emptySet(),
    val count: Int = 0,
    val allSelected: Boolean = false,
    val onToggle: (String) -> Unit = {},
    val onExit: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onAddToPlaylist: () -> Unit = {},
    val onPlay: () -> Unit = {},
) {
    /** 是否处于多选状态。整页的动作条、行的勾选框、返回键都由它决定。 */
    val mode: Boolean get() = ids.isNotEmpty()
}
