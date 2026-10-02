package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.BrowserEntry

/**
 * 文件浏览器当前要显示的内容。
 *
 * ## 为什么 [Unreadable] 和 `Ready(emptyList())` 都不是「空」
 *
 * 这两个状态在界面上是**两句话**：
 *
 * - `Ready(emptyList())` → 「这个文件夹是空的」
 * - [Unreadable] → 「打不开这个文件夹」（后面必须跟一个动作：重新授权 / 去开权限）
 *
 * 合并成一个空列表，用户就会看着一个**明明有文件**的目录说「里面什么都没有」，
 * 而屏幕上没有任何线索指向「授权过期了」。这是本层存在的最主要理由。
 *
 * [onlyHidden] 是第三种「看起来是空的」：目录里确实有东西，但全被「显示隐藏文件」
 * 这个开关挡住了。它也需要自己的话术，否则用户同样只会看到一个空目录。
 */
sealed interface BrowserContent {

    /** 还没进入任何目录：用户停在来源列表页。 */
    data object Idle : BrowserContent

    data object Loading : BrowserContent

    data class Ready(
        val entries: List<BrowserEntry>,
        /** 因为单目录条数上限被截断（见 `MAX_ENTRIES_PER_DIRECTORY`）。 */
        val truncated: Boolean,
        /** 未过滤前，以 `.` 开头的条目数量。用来区分「真空」和「被开关挡住了」。 */
        val hiddenCount: Int,
    ) : BrowserContent {

        val empty: Boolean get() = entries.isEmpty()

        /** 有内容，但全是隐藏项。这时应该提示用户去打开开关，而不是说「这里是空的」。 */
        val onlyHidden: Boolean get() = empty && hiddenCount > 0
    }

    /** 读不到（授权失效 / 缺权限 / IO 错误）。**不是**空目录。 */
    data object Unreadable : BrowserContent

    /** 这个位置已经不是目录了。界面应该退回上一层。 */
    data object NotADirectory : BrowserContent

    companion object {

        /**
         * 把「列目录的结论」加上显示选项，翻成界面状态。
         *
         * 抽成纯函数是为了能单测：这里是**唯一**把
         * `BrowserListing` + `BrowserSort` + `showHidden` 三者合成一个结果的地方，
         * 而它的三个输入各有各的边界（空 / 截断 / 只有隐藏项）。
         */
        fun of(listing: BrowserListing, sort: BrowserSort, showHidden: Boolean): BrowserContent =
            when (listing) {
                is BrowserListing.Ready -> {
                    val arranged = BrowserRules.arrange(listing.entries, sort, showHidden)
                    Ready(
                        entries = arranged.entries,
                        truncated = arranged.truncated,
                        // 隐藏项数量按**未过滤**的原始表算：过滤之后的表里已经
                        // 看不到它们了，算出来永远是 0，`onlyHidden` 就永远不成立。
                        hiddenCount = listing.entries.count { it.hidden },
                    )
                }

                BrowserListing.Unreadable -> Unreadable
                BrowserListing.NotADirectory -> NotADirectory
            }
    }
}
