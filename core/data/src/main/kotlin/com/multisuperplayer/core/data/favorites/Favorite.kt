package com.multisuperplayer.core.data.favorites

import com.multisuperplayer.core.data.browser.BrowserCrumb
import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText

/**
 * 一条收藏：**一个文件**或**一个文件夹**。
 *
 * ## 为什么这一层住在 `core:data` 而不是 `core:model`
 *
 * 文件夹的一半属性是 `BrowserSourceKind` / `BrowserTrail`——它们本身就住在
 * `core:data`（`core:model` 刻意不知道「来源」怎么解释 `ref`，见 `BrowserEntry` 的注释）。
 * 把 `Favorite` 放进 `core:model` 就得把那张表搬过去，或者在这一层退化成一个
 * 裸字符串——前者会打破模块依赖方向，后者会让「这个 ref 该怎么解释」在类型上
 * 彻底消失，也就是 v1.2.4 修过的那一类 bug（`saf:` 被当成媒体库）的温床。
 *
 * ## 两类收藏的取舍
 *
 * - [File] 直接复用 [PlaylistItem]。它是「文件 + 一份显示快照」的既有答案：
 *   id 是主键、快照只是兜底，文件改名/换码之后仍然能播，文件不见时仍然能显示
 *   一行有名字的记录。收藏需要的正是这两件事，重造一个字段几乎一样的类型
 *   只会让两处行为慢慢分叉（比如一边补了 `sourceOf` 的推导、另一边忘了）。
 * - [Folder] 存的是**整条面包屑**，不是「当前目录的一次 ref」。理由和
 *   [BrowserTrail] 一样：SAF / 文件系统 / 媒体库三种来源里，「从这一层怎么回到
 *   上一层」根本算不出来，能记下来的只有用户实际走过的那条链。顺带的好处是
 *   收藏的文件夹被点开之后，面包屑上不只一个孤零零的目录名，用户还能往上走。
 *
 * 两类共用一个 [key]（也是去重键、删除键）：界面上是一个混排列表，
 * 用两个不同类型的键会让「取消收藏这一条」这种动作必须先进 `when` 分支。
 */
sealed interface Favorite {

    /** 稳定身份。同一个文件/同一个文件夹无论收藏几次都只有这一个键。 */
    val key: String

    /** 收藏时间（毫秒）。列表按它倒序，所以它就是「顺序」本身。 */
    val addedAtMs: Long

    /** 一个被收藏的文件。 */
    data class File(
        val item: PlaylistItem,
        override val addedAtMs: Long,
    ) : Favorite {
        override val key: String get() = FavoriteRules.fileKey(item.mediaId)
    }

    /**
     * 一个被收藏的文件夹。
     *
     * [kind] 必须和 [crumbs] 来自同一棵树：`ref` 的解释方式由它决定，
     * 混用会让「打开收藏」列出一个完全不相关的目录（或者直接读不出来）。
     */
    data class Folder(
        val kind: BrowserSourceKind,
        val crumbs: List<FavoriteCrumb>,
        override val addedAtMs: Long,
    ) : Favorite {

        init {
            require(crumbs.isNotEmpty()) { "收藏的文件夹至少要有一段面包屑" }
        }

        override val key: String get() = FavoriteRules.folderKey(kind, crumbs.last().ref)

        /** 列表里显示的名字：最里面那一层的目录名。 */
        val name: String get() = crumbs.last().label

        /**
         * 还原成可以交给浏览页的导航栈。
         *
         * 名字一律变成 [MspText.Plain]：收藏时**已经解析过**（存储层只有 `String`，
         * 见 [FavoriteRules.folderOf]），所以这里没有资源 id 可用了。
         * 这也正是「换语言之后收藏的文件夹仍然显示旧语言目录名」的原因——
         * 目录名是用户自己的数据，本来就不该跟着界面语言变；唯一会变的是
         * 来源根那一层（「内部存储」），而那一层在收藏时就已经是当时的语言。
         */
        fun toTrail(): BrowserTrail = BrowserTrail(
            kind = kind,
            crumbs = crumbs.map { BrowserCrumb(MspText.Plain(it.label), it.ref) },
        )
    }
}

/**
 * 收藏里的一段面包屑。
 *
 * 和 [BrowserCrumb] 的差别只有一个：[label] 是 `String`。
 * 收藏要落盘，而 `MspText.Res` 是资源 id——存下来之后用户换一种语言，
 * 这一条会显示**新**语言的根目录名，但同一棵树里其它层的目录名不会跟着变，
 * 于是面包屑上半截说英文、下半截说中文。存解析后的文本是唯一自洽的选择。
 * 代价是「换语言后旧的根目录名留着旧语言」，对用户自己的文件夹名毫无影响，
 * 对「内部存储」这种系统名也只影响已经收藏过的那几条。
 */
data class FavoriteCrumb(val label: String, val ref: String)
