package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 多选的选择集规则，纯函数。
 *
 * 选择集本身是**界面瞬态**（不进 ViewModel、不落盘）：它只描述「这一次长按选了哪些行」，
 * 配置变更后重来一次是合理的，而存进持久化会让「上次选的东西」在冷启动后突然出现在
 * 操作条里。放在这里是为了能单测——`Set` 的增删看起来没什么可测的，
 * 直到出现「全选按钮在全选状态下点了没反应」或者「取消选择把没显示的行也取消掉了」。
 */
internal object LibrarySelectionRules {

    /**
     * 点一下某一行：已选则取消，未选则选中。
     *
     * 空 id 直接返回原集合（**同一个实例**）：id 是主键，空的 id 意味着调用方拿到的
     * 是一条残缺数据，把它塞进选择集会出现一个「选了 1 项但界面上没有一行是高亮的」
     * 的操作条——用户唯一的出路是按取消。
     */
    fun toggle(selected: Set<String>, id: String): Set<String> {
        if (id.isBlank()) return selected
        return if (id in selected) selected - id else selected + id
    }

    /** 全选：把 [entries] 里的 id 并进来，已有的选择保留。 */
    fun addAll(selected: Set<String>, entries: List<MediaEntry>): Set<String> {
        if (entries.isEmpty()) return selected
        val ids = entries.map { it.id }.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return selected
        // 已经是全集时返回原实例：调用方（界面）靠 `===` 之外的**结构相等**本来也能跳过
        // 重组，但保持「没变就同一个实例」这条约定能让测试直接断言 assertSame。
        return if (ids.all { it in selected }) selected else selected + ids
    }

    /** 取消选择 [entries] 里的这些行，**只**取消这些。 */
    fun removeAll(selected: Set<String>, entries: List<MediaEntry>): Set<String> {
        if (selected.isEmpty() || entries.isEmpty()) return selected
        val ids = entries.map { it.id }.toSet()
        val remaining = selected - ids
        return if (remaining.size == selected.size) selected else remaining
    }

    /** [entries] 是否**全部**已被选中。空列表返回 false（否则按钮文案会显示成「取消全选」）。 */
    fun allSelected(selected: Set<String>, entries: List<MediaEntry>): Boolean {
        if (entries.isEmpty()) return false
        return entries.all { it.id.isNotBlank() && it.id in selected }
    }

    /**
     * 选择集里实际存在于 [entries] 的那些条目，保持 [entries] 的顺序。
     *
     * 操作（加入播放列表、播放）拿到的必须是**当前可见**的条目：库里可能因为权限变化、
     * 文件被删而少了几条，直接按选择集去库里按 id 查找会拿到「已经不存在的东西」。
     */
    fun resolve(entries: List<MediaEntry>, selected: Set<String>): List<MediaEntry> =
        entries.filter { it.id in selected }
}
