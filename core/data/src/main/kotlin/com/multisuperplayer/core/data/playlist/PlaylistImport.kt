package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.text.MspText

/**
 * 重名列表怎么办：并进已有的那一份，还是再建一个新的。
 *
 * 用户默认拿到的是「新建」（导入 = 往里加东西，不该悄悄改掉已有的列表），
 * 但同名的那一份仍然要**问一次**——「导入两次」这个最常见的操作里，
 * 用户想要的到底是「两份」还是「一份、合起来」，只有他知道。
 */
enum class PlaylistImportMergeChoice { MERGE, NEW }

/**
 * 一次导入里用户已经做过的决定。
 *
 * 为什么要 `decisions` 这张表 + `applyToAll`，而不是一次问完就算：
 * 重名是**逐名问**的（问「旅行」的时候还不知道「派对」也重名——文件里的列表
 * 是一个一个被处理的），所以每答一个都要把之前的答案带上重跑一遍。
 * 重跑是安全的：整个过程中**先问完、再写**（见 [PlaylistImporter.plan]），
 * 所以在最后一个问题被回答之前，库里一个字节都没动过。
 */
data class PlaylistImportOptions(
    /** 已经答过的名字 → 它的答法。 */
    val decisions: Map<String, PlaylistImportMergeChoice> = emptyMap(),
    /** 用户勾了「全部套用」：剩下的重名一律用 [applyToAllAs]，不再问。 */
    val applyToAll: Boolean = false,
    /** 配合 [applyToAll]。只有 [applyToAll] 为 true 时才有意义。 */
    val applyToAllAs: PlaylistImportMergeChoice = PlaylistImportMergeChoice.MERGE,
)

/**
 * 文件层面的统计。和「写了什么」无关，只回答「文件里有哪些东西是用不上的」。
 *
 * 这几件事**必须分开数**（而不是合成一个「跳过了 N 条」）：它们的改法完全不同——
 * 空列表是文件本身的内容问题，没有媒体 ID 是文件被改过，
 * 重复条目只是重复而已。
 */
data class PlaylistImportTally(
    /** 文件里有、但一条可用条目都没有的列表数。 */
    val emptyPlaylists: Int = 0,
    /** 条目里没有媒体 ID 的（没法还原：去重和「按当前媒体库取最新信息」都靠它）。 */
    val itemsWithoutId: Int = 0,
    /** 文件里同一个列表内重复出现的条目（只留第一条）。 */
    val duplicateItems: Int = 0,
) {
    val isEmpty: Boolean
        get() = emptyPlaylists == 0 && itemsWithoutId == 0 && duplicateItems == 0
}

/**
 * 一个文件「打算怎么写下去」。
 *
 * [pendingNames] 非空表示**还不能写**：里面有重名的列表等着用户选「合并还是
 * 新建」。等它空了，[plans] 就是完整的写入方案。
 */
data class PlaylistImportPlan(
    val plans: List<PlaylistPlan>,
    /** 还没决定的重名列表，按文件里的顺序。 */
    val pendingNames: List<String> = emptyList(),
    val tally: PlaylistImportTally = PlaylistImportTally(),
) {
    val isReady: Boolean get() = pendingNames.isEmpty()

    /** 文件里一个能写的东西都没有（空列表也没算）。 */
    val isEmpty: Boolean get() = plans.isEmpty() && tally.isEmpty
}

/**
 * 一次导入最后到底发生了什么。
 *
 * 每一个数字都对应界面上一句**不同**的话：合并了几个列表和新建了几个列表，
 * 后续要做的事完全不一样（前者已经有了内容，后者是全新的）。
 * 把它们合成一个「导入了 N 个列表」会让用户没法判断「我原来的列表被改了吗」。
 */
data class PlaylistImportOutcome(
    val playlistsCreated: Int = 0,
    val playlistsMerged: Int = 0,
    val itemsAdded: Int = 0,
    val playlistsSkippedEmpty: Int = 0,
    val itemsSkippedEmpty: Int = 0,
    val itemsSkippedDuplicate: Int = 0,
    val playlistsSkippedFull: Int = 0,
) {

    /**
     * 该跟用户说的一句话；没什么可说的时候返回 null。
     *
     * 「没什么可说」指的是**真的什么都没发生、也没什么要提醒的**。只说
     * 「成功」是不够的：文件里 12 条全都没有媒体 ID 时，「已导入 0 条」
     * 和沉默都不对，得让用户看见那个 12。
     */
    fun message(): MspText? {
        val notes = notes()
        if (playlistsCreated == 0 && playlistsMerged == 0) {
            if (notes.isEmpty()) return null
            return MspText.Res(
                R.string.msp_import_result_nothing_happened,
                MspText.join(SEPARATOR, notes),
            )
        }
        val head = ArrayList<MspText>(3)
        if (playlistsCreated > 0) {
            head += MspText.Res(R.string.msp_import_result_created, playlistsCreated)
        }
        if (playlistsMerged > 0) {
            head += MspText.Res(R.string.msp_import_result_merged, playlistsMerged)
        }
        if (itemsAdded > 0) {
            head += MspText.Res(R.string.msp_import_result_items, itemsAdded)
        }
        val main = MspText.join(SEPARATOR, head)
        if (notes.isEmpty()) return main
        return MspText.Res(R.string.msp_import_result_with_notes, main, MspText.join(SEPARATOR, notes))
    }

    private fun notes(): List<MspText> {
        val notes = ArrayList<MspText>(4)
        if (playlistsSkippedEmpty > 0) {
            notes += MspText.Res(R.string.msp_import_note_empty_playlists, playlistsSkippedEmpty)
        }
        if (itemsSkippedEmpty > 0) {
            notes += MspText.Res(R.string.msp_import_note_items_without_id, itemsSkippedEmpty)
        }
        if (itemsSkippedDuplicate > 0) {
            notes += MspText.Res(R.string.msp_import_note_duplicate_items, itemsSkippedDuplicate)
        }
        if (playlistsSkippedFull > 0) {
            // 把上限本身说出来：不然用户只会看到一个「有几个没导入」而不知道该删到多少。
            notes += MspText.Res(
                R.string.msp_import_note_full,
                PlaylistRules.MAX_PLAYLISTS,
                playlistsSkippedFull,
            )
        }
        return notes
    }

    private companion object {
        val SEPARATOR: MspText = MspText.Plain(" · ")
    }
}

/**
 * 导入界面此刻该说什么/该问什么。
 *
 * 放在 `core:data` 而不是界面层，和 `TranslationCacheStore.describe*` 是同一个
 * 理由：这些句子描述的是**文件和数据**（认不出表头、缺少哪一列、有 12 条没有
 * 媒体 ID），判断它们的东西全在这个模块里，句子跟着判断走才不会两边漂。
 * 界面层只负责把 [message] 念出来，以及画那个「合并还是新建」的对话框。
 */
sealed interface PlaylistImportState {

    /** 该念给用户听的一句话；不需要说话的几种状态返回 null。 */
    fun message(): MspText?

    /** 什么都没在发生。 */
    data object Idle : PlaylistImportState {
        override fun message(): MspText? = null
    }

    /**
     * 正在读文件。
     *
     * 单独一个状态是为了**拦住重入**：用户可能连点两下「导入」，
     * 两个读+写叠在一起会各自往同一个列表里加一遍（见 [PlaylistImportSink.apply]）。
     */
    data object Reading : PlaylistImportState {
        override fun message(): MspText? = null
    }

    /**
     * 等用户对一个重名列表做决定。
     *
     * [remaining] 是**含这一个**在内、还需要问几个。界面上要显示它：
     * 文件里有 20 个重名列表时，一个一个弹而不说还有几个，
     * 用户会以为这个框永远关不掉。
     */
    data class Ask(val name: String, val remaining: Int) : PlaylistImportState {
        override fun message(): MspText? = null
    }

    /** 写完了。 */
    data class Finished(val outcome: PlaylistImportOutcome) : PlaylistImportState {
        override fun message(): MspText? = outcome.message()
    }

    /** 读不了/认不出。 */
    data class Failed(val text: MspText) : PlaylistImportState {
        override fun message(): MspText? = text
    }

    /** 文件读出来了，但里面没有可以导入的内容。 */
    data object Empty : PlaylistImportState {
        override fun message(): MspText = MspText.Res(R.string.msp_import_result_nothing)
    }
}
