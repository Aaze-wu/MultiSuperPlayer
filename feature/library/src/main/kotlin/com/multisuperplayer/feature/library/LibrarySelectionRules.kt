package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 多选的选择集规则，纯函数。
 *
 * 选择集本身是**界面瞬态**（不进 ViewModel、不落盘）：它只描述「这一次长按选了哪些行」，
 * 配置变更后重来一次是合理的，而存进持久化会让「上次选的东西」在冷启动后突然出现在
 * 操作条里。放在这里是为了能单测——`Set` 的增删看起来没什么可测的，
 * 直到出现「全选按钮在全选状态下点了没反应」或者「取消选择把没显示的行也取消掉了」。
 *
 * ## 为什么是 `List` 而不是 `Set`
 *
 * 选择集**是有顺序的，而且这个顺序会被用户看见**：多选之后点「加入播放列表」，
 * 条目按选择集里的顺序追加到播放列表；点「播放」则以第一条为起点。用 `Set` 的时候
 * 顺序只是 `LinkedHashSet` 的实现细节（今天的 `selected + id` 恰好把新元素放在末尾），
 * 类型上没有任何东西承诺它，于是「按点击顺序」这条契约能在一次看似无关的重构里
 * 悄悄变成「按屏幕顺序」——而两种顺序在列表本来就排得整整齐齐时看起来一模一样，
 * 只有用户刻意逆序点选才会发现队列反了。
 *
 * 重复的 id 在入口处（[toggle] / [addAll]）就不会产生，所以 `List` 不会退化成多重集。
 */
internal object LibrarySelectionRules {

    /**
     * 点一下某一行：已选则取消，未选则**追加到末尾**。
     *
     * 追加到末尾而不是插到前面，是为了让「先点 A 再点 B」得到 `[A, B]`——
     * 也就是用户点选的先后顺序，而不是某种「最新的排最前」。加入播放列表时
     * 用户看到的新条目顺序必须和手指的顺序一致。
     *
     * 空 id 直接返回原选择集（**同一个实例**）：id 是主键，空的 id 意味着调用方拿到的
     * 是一条残缺数据，把它塞进选择集会出现一个「选了 1 项但界面上没有一行是高亮的」
     * 的操作条——用户唯一的出路是按取消。
     */
    fun toggle(selected: List<String>, id: String): List<String> {
        if (id.isBlank()) return selected
        return if (id in selected) selected - id else selected + id
    }

    /** 全选：把 [entries] 里的 id 按顺序并进来，已有的选择保留。 */
    fun addAll(selected: List<String>, entries: List<MediaEntry>): List<String> {
        if (entries.isEmpty()) return selected
        // 用 HashSet 做成员判断：全选一个几千条的库时，每个 id 都去扫一遍选择集
        // 是 O(n·m)，而这里只需要「见过的就跳过」。
        val known = selected.toHashSet()
        val appended = ArrayList<String>()
        for (entry in entries) {
            val id = entry.id
            if (id.isBlank()) continue
            if (!known.add(id)) continue
            appended += id
        }
        // 已经是全集时返回原实例：调用方（界面）靠**结构相等**本来也能跳过重组，
        // 但保持「没变就同一个实例」这条约定能让测试直接断言 assertSame。
        return if (appended.isEmpty()) selected else selected + appended
    }

    /** 取消选择 [entries] 里的这些行，**只**取消这些，其余保持原有顺序。 */
    fun removeAll(selected: List<String>, entries: List<MediaEntry>): List<String> {
        if (selected.isEmpty() || entries.isEmpty()) return selected
        val ids = entries.mapTo(HashSet()) { it.id }
        val remaining = selected.filterNot { it in ids }
        return if (remaining.size == selected.size) selected else remaining
    }

    /**
     * [entries] 是否**全部**已被选中。空列表返回 false（否则按钮文案会显示成「取消全选」）。
     *
     * 这里收 `Collection` 而不是 `List`：全选只问「在不在里面」，和顺序无关，
     * 类型上也不该暗示自己关心顺序。
     */
    fun allSelected(selected: Collection<String>, entries: List<MediaEntry>): Boolean {
        if (entries.isEmpty()) return false
        return entries.all { it.id.isNotBlank() && it.id in selected }
    }

    /**
     * 选择集里实际存在于 [entries] 的那些条目，**按选择顺序**（也就是点选顺序）返回。
     *
     * 两件事都不能省：
     *
     * - 必须过滤掉不在 [entries] 里的（库里可能因为权限变化、文件被删而少了几条，
     *   直接按 id 去查会拿到「已经不存在的东西」）；
     * - 必须按选择顺序而不是 [entries] 的顺序。多选之后「加入播放列表」和「播放」
     *   都按这个顺序说话，按屏幕顺序的话用户逆着点选就会得到反过来的队列——
     *   而屏幕顺序在列表整齐时看起来和点击顺序一模一样，只有刻意逆序才看得出来。
     */
    fun resolve(entries: List<MediaEntry>, selected: List<String>): List<MediaEntry> {
        if (selected.isEmpty() || entries.isEmpty()) return emptyList()
        // 按 id 建一次索引，而不是对每个选中 id 去扫一遍 [entries]：
        // 全选一个大库时后者是 O(n·m)。
        val byId = entries.associateBy { it.id }
        return selected.mapNotNull { byId[it] }
    }
}
