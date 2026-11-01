package com.multisuperplayer.core.data.library

/**
 * 这一次扫描里，**SAF 那半边**要做什么。
 *
 * ## 为什么要把它单独写成一个纯函数
 *
 * 「这次要不要走进目录树」这一个判断，在扫描流程里有两个用处，而它们必须一致：
 *
 * 1. [MediaLibraryRepository.scanSaf] 用它决定「直接返回缓存」还是「真去枚举」；
 * 2. [MediaLibraryRepository.scan] 用它决定**要不要先发布一版只有 MediaStore 的中间结果**
 *    ——见 [WalkTrees]。
 *
 * 如果这两处各写一份条件，某天改了一处就会出现「先发布中间态，然后发现根本不用扫」
 * 或者反过来的情况：前者多一次闪动，后者会让用户继续对着转圈等一个不会变的缓存。
 * 抽成一个可以被单测钉死的函数之后，两边只可能是同一个答案。
 *
 * ## 四个分支各自对应的真实情形
 *
 * - [Cached]：上次扫过，这次没被要求强制重扫（MediaStore 的 ContentObserver 触发
 *   的自动重建都属于这种）。SAF 目录树是**慢**的那一半，也是**变化最少**的一半，
 *   所以它在内存里留一份结果。
 * - [ReuseCache]：授权清单读不出来（DataStore 文件被外部清掉了）。这是「我不知道
 *   现在有哪些树」，不是「一棵都没有」。
 * - [NoTrees]：真的没有授权任何目录，结果就是空——**必须**覆盖缓存，否则用户删光
 *   文件夹之后媒体库里还留着一堆 SAF 条目。正是因为这一句，它排在 [Cached] 前面：
 *   「确定没有」比「反正有缓存」更确定，两者冲突时以后者为准就是 bug。
 * - [WalkTrees]：唯一一种会花上几秒到几十秒的分支，也是唯一值得先发中间态的分支。
 */
internal enum class SafScanPlan {

    /** 清单读不出来：沿用上次结果。 */
    ReuseCache,

    /** 没有任何授权目录：结果是空，并且要覆盖缓存。 */
    NoTrees,

    /** 有缓存且没要求重扫：直接用缓存。 */
    Cached,

    /** 真的要走目录树（慢的那条路）。 */
    WalkTrees;

    companion object {

        /**
         * @param treeUris 授权清单；`null` 表示读不出来（区别于空表 = 没有授权）。
         * @param force 是否强制重扫（用户主动刷新 / 刚增删了目录）。
         * @param hasCache 内存里有没有上次的 SAF 结果。
         */
        fun of(treeUris: List<String>?, force: Boolean, hasCache: Boolean): SafScanPlan = when {
            // 空表是「我确定一棵都没有」，比任何缓存都确定：缓存里那些条目所属的
            // 目录已经被撤权了，继续展示它们只会让用户点开就报错。
            treeUris != null && treeUris.isEmpty() -> NoTrees

            // 「有缓存且没要求重扫」放在其次：这时连清单都不用看，直接用缓存，
            // 也顺手避免了「清单读不出来」那条告警在每次自动刷新时刷屏。
            !force && hasCache -> Cached
            treeUris != null -> WalkTrees
            else -> ReuseCache
        }
    }
}
