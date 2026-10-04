package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.BrowserEntry

/**
 * 列一层目录的结果。
 *
 * ## 为什么必须是三个分支而不是「一个列表 + 一个成功标志」
 *
 * 「这个目录是空的」和「这个目录问不通」在界面上要做**完全不同**的事：
 * 前者是一句「这里没有文件」，后者要引导用户去重新授权 / 去开权限。
 * 如果都表现成空列表，用户看到的是「明明有文件的目录里什么都没有」，
 * 而这条线索**指不出任何方向**。
 *
 * 第三种 [NotADirectory] 同样不能省：它意味着 [BrowserEntry.ref] 不是目录
 * （provider 把它换成了文件、或者目录被删了又建成了同名文件）。这不是「读不到」，
 * 而是「这个位置已经不是目录了」，界面应该退回上一层而不是留在原地转圈。
 */
sealed interface BrowserListing {

    /** 列出来了。空列表表示这个目录**确实是空的**。 */
    data class Ready(val entries: List<BrowserEntry>) : BrowserListing

    /** 问不通：授权失效、缺权限、IO 错误。**不是**「空目录」。 */
    data object Unreadable : BrowserListing

    /** 这个 ref 指向的不是目录（或者已经不是了）。 */
    data object NotADirectory : BrowserListing
}

/**
 * 排序方式。
 *
 * 只有四种，而且**不含「按大小」**：目录优先永远生效，而目录的大小恒为 0，
 * 「按大小排序」在文件浏览器里会把所有目录挤成一堆无意义的相对顺序。
 * 媒体库里那种「按大小」是给扁平列表用的，这里不适用。
 *
 * [label] 跟着枚举走（而不是让界面自己 `when`），和 `LibrarySort` 一致：
 * 两个地方各写一份 `when`，迟早有一边漏了新值。
 */
enum class BrowserSort(val label: MspText) {
    NAME_ASC(MspText.Res(R.string.msp_browser_sort_name_asc)),
    NAME_DESC(MspText.Res(R.string.msp_browser_sort_name_desc)),
    NEWEST(MspText.Res(R.string.msp_browser_sort_newest)),
    OLDEST(MspText.Res(R.string.msp_browser_sort_oldest)),
}
