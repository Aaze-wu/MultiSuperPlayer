package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.data.export.ImportedPlaylist
import com.multisuperplayer.core.model.PlaylistItem

/**
 * 导入的编排：把「文件里读出来的东西」变成「怎么写下去」的方案，然后交给
 * [PlaylistImportSink] 一次写完。
 *
 * ## 为什么分成 plan / apply 两步，而不是边走边写
 *
 * 因为重名要**问用户**。文件里有 3 个列表和现有的重名时，问题得一个一个问
 * （一次弹 3 个框更糟），而每答一个都要拿「到目前为止的全部答案」重新算一遍方案。
 * 如果第一遍就把能写的先写了，那么：
 *
 * - 「还没决定」的那些列表被跳过，第二遍再补——中途出现「只导了一半」的状态；
 * - 第二遍里，第一遍刚建出来的列表**又变成重名**，用户会被问一遍已经答过的问题；
 * - 用户答到一半取消时，库里已经多了一堆半成品。
 *
 * 先问完再写就没有这三件事：`plan` 是纯计算（只读一次名字表），
 * 只要还有没决定的，就一个字都不写；用户取消 = 什么也没发生。
 */
class PlaylistImporter(private val sink: PlaylistImportSink) {

    /**
     * 算出「怎么写下去」。**不写任何东西**（只读一次现有的名字表）。
     *
     * 三件事在这里做完：
     * 1. 文件里**同名**的列表合成一项（两个同名列表在界面上是分不清的：
     *    哪一个是刚导入的？删哪一个？而 CSV 那边本来就按名字分组，统一成一种处理）；
     * 2. 数出用不上的条目（没有媒体 ID 的、同一个列表里重复的）；
     * 3. 对每一个列表决定：新建（名字要避开已占用的）还是合并（并进哪一个）。
     *
     * 还没被用户决定的重名列表进 [PlaylistImportPlan.pendingNames]，**不进** [PlaylistImportPlan.plans]。
     */
    suspend fun plan(
        imported: List<ImportedPlaylist>,
        options: PlaylistImportOptions,
    ): PlaylistImportPlan {
        val grouped = LinkedHashMap<String, MutableList<PlaylistItem>>()
        var emptyPlaylists = 0
        var itemsWithoutId = 0
        imported.forEach { playlist ->
            itemsWithoutId += playlist.itemsWithoutId
            val name = PlaylistRules.sanitizeName(playlist.name)
            // 名字为空是解析层的契约违约（`PlaybackImport.fallbackNameOf` 保证非空）。
            // 真出现的话按「这一项没有可用内容」处理：一个没有名字的播放列表
            // 建出来之后用户既看不见也没法删。
            if (name.isEmpty()) {
                emptyPlaylists++
                return@forEach
            }
            grouped.getOrPut(name) { ArrayList() }.addAll(playlist.items)
        }

        // 一次拿全已有的名字：重名判断要它，新名字要避开已被占用的名字也要它。
        val taken = LinkedHashMap(sink.nameIndex())
        val occupied = HashSet(taken.keys)
        val plans = ArrayList<PlaylistPlan>(grouped.size)
        val pending = ArrayList<String>()
        var duplicateItems = 0

        grouped.forEach { (name, rawItems) ->
            val seen = HashSet<String>(rawItems.size)
            val items = ArrayList<PlaylistItem>(rawItems.size)
            rawItems.forEach { item ->
                when {
                    item.mediaId.isBlank() -> itemsWithoutId++
                    !seen.add(item.mediaId) -> duplicateItems++
                    else -> items.add(item)
                }
            }
            if (items.isEmpty()) {
                emptyPlaylists++
                return@forEach
            }
            val existingId = taken[name]
            if (existingId == null) {
                plans += createPlan(name, items, occupied)
                return@forEach
            }
            when (decide(name, options)) {
                PlaylistImportMergeChoice.MERGE -> plans += PlaylistPlan.Append(
                    name = name,
                    items = items,
                    playlistId = existingId,
                )

                PlaylistImportMergeChoice.NEW -> plans += createPlan(name, items, occupied)

                // 还没问过：这一个先不定，也不占用名字。
                null -> pending += name
            }
        }

        return PlaylistImportPlan(
            plans = plans,
            pendingNames = pending,
            tally = PlaylistImportTally(
                emptyPlaylists = emptyPlaylists,
                itemsWithoutId = itemsWithoutId,
                duplicateItems = duplicateItems,
            ),
        )
    }

    /**
     * 真正落盘。[plan] 必须已经 [PlaylistImportPlan.isReady]（还有没决定的时候
     * 写下去就是一知半解的写入）。
     *
     * @return 写完之后该跟用户说的话；null 表示**一个字节都没写进去**
     *   （存储读写失败）——那种情况下要说的是「保存失败」，
     *   而不是「导入了 0 个列表」（后者听起来像文件里没东西）。
     */
    suspend fun apply(plan: PlaylistImportPlan): PlaylistImportOutcome? {
        val creates = plan.plans.filterIsInstance<PlaylistPlan.Create>()
        val appends = plan.plans.filterIsInstance<PlaylistPlan.Append>()
        val requestedAppendItems = appends.sumOf { it.items.size }
        val result = sink.apply(plan.plans)
        if (result !is PlaylistApplyResult.Applied) return null
        return PlaylistImportOutcome(
            playlistsCreated = result.created,
            playlistsMerged = appends.size,
            // 新建的那几个里被同一个上限裁掉的条目没有单独计数：
            // 我们自己导出的文件里不可能有（导出时就被同一个上限裁过）。
            itemsAdded = result.createdItems + result.appendedItems,
            playlistsSkippedEmpty = plan.tally.emptyPlaylists,
            itemsSkippedEmpty = plan.tally.itemsWithoutId,
            // 两处去重都要说：文件里本来就重复的，加上「已有的列表里已经有了的」。
            itemsSkippedDuplicate = plan.tally.duplicateItems +
                (requestedAppendItems - result.appendedItems).coerceAtLeast(0),
            playlistsSkippedFull = (creates.size - result.created).coerceAtLeast(0),
        )
    }

    /**
     * 新建方案。名字要避开**已经存在的**和**本批里已经计划好的**两种占用——
     * 后者是文件里自带重复名字时的情形（`PlaylistRules.uniqueName` 负责加后缀）。
     */
    private fun createPlan(
        name: String,
        items: List<PlaylistItem>,
        occupied: MutableSet<String>,
    ): PlaylistPlan {
        val finalName = PlaylistRules.uniqueName(name, occupied)
        occupied += finalName
        return PlaylistPlan.Create(name = finalName, items = items)
    }

    /** 这个名字该合并还是该新建；还没问过返回 null。 */
    private fun decide(name: String, options: PlaylistImportOptions): PlaylistImportMergeChoice? {
        options.decisions[name]?.let { return it }
        return if (options.applyToAll) options.applyToAllAs else null
    }
}
