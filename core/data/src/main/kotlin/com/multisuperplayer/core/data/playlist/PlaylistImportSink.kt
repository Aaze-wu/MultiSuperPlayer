package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.model.PlaylistItem

/**
 * 「把一批播放列表写进去」需要的两个动作。
 *
 * ## 为什么是一个接口，而不是让编排直接调 [PlaylistStore]
 *
 * 导入里真正需要单测的是**那些判断**：文件里第 N 个列表该新建、还是该并进
 * 已有的那一份；名字重了叫什么；哪些条目要丢掉。直接依赖 [PlaylistStore]
 * 就把这些判断和 DataStore、`Context` 绑死在了一起，而 `core:data` 的单测是
 * 纯 JVM（没有 Robolectric，见 `core/data/build.gradle.kts`）——于是重名、
 * 达上限、文件里两个同名列表这些分支就只能靠真机手点。
 *
 * 换成一个两方法的接口，测试里塞一个内存假货，那些分支全都能跑。
 */
interface PlaylistImportSink {

    /**
     * 现有播放列表的「名字 → id」。
     *
     * 名字都按 [PlaylistRules.sanitizeName] 归一过（导入文件里的名字也要走
     * 同一个函数，否则「标题 」和「标题」会被当成两个不同的名字）。
     *
     * 一次拿全、而不是每个列表各查一次：新名字必须避开**同一批里刚刚计划好的**
     * 那些名字，而那件事只有编排自己知道（它往这份表里补）。
     *
     * 库里的重名是允许的（`create` 不禁止），这种情况下**以列表页上排在前面的
     * 那一个为准**——用户看到的第一个就是他会认为是「那个列表」的那一个。
     */
    suspend fun nameIndex(): Map<String, String>

    /**
     * 按 [plans] 落盘，**一次写完**（生产实现里就是一次 `DataStore.edit`）。
     *
     * 为什么不是「一个列表一次」：
     *
     * - 分多次写会留下「导了一半」的中间状态，而那种状态在界面上和「导完了」
     *   长得一模一样（列表都出现了），于是没有人会去补；
     * - 只有一次写完，才谈得上「要么全都进去、要么什么都没发生」，也才不会
     *   出现「读到一半发现某个方案不合法，前面几个已经写进去了」。
     *
     * 名字、以及「新建还是合并」都已在计划里定好，这里**不再做任何判断**。
     */
    suspend fun apply(plans: List<PlaylistPlan>): PlaylistApplyResult
}

/**
 * [PlaylistImportSink.apply] 的落盘结果。
 *
 * 刻意是密封类型而不是「一个带 failed 标记的数据类」：失败和成功**必须**被分开
 * 处理。退化成一组零的话，界面上说的是「已导入 0 条」——而用户会把它读成
 * 「文件里没有东西」，真正的结论应该是「没写进去，重试一次」。
 */
sealed interface PlaylistApplyResult {

    data class Applied(
        /** 真正新建了几个（已达 [PlaylistRules.MAX_PLAYLISTS] 时少于计划里的新建数）。 */
        val created: Int,
        /** 新建的那几个列表里，真正写进去的条目数。 */
        val createdItems: Int,
        /** 合并进已有列表的条目数（已有的 media id 会被去重，所以可能少于传进去的）。 */
        val appendedItems: Int,
    ) : PlaylistApplyResult

    /** 一个字节都没写进去（存储读不出/写不进）。 */
    data object Failed : PlaylistApplyResult
}

/**
 * 一个列表要怎么写下去。
 *
 * [name] 和 [items] 都已经定好：名字是最终名字（重名已经加过后缀、已经截断到
 * [PlaylistRules.MAX_NAME_LENGTH]），条目已经去过重。落盘时只剩两件事——
 * 选一个 id、按 [PlaylistRules.withAdded] 合进去。
 */
sealed interface PlaylistPlan {

    val name: String

    val items: List<PlaylistItem>

    /** 新建一个叫 [name] 的列表。 */
    data class Create(
        override val name: String,
        override val items: List<PlaylistItem>,
    ) : PlaylistPlan

    /** 把 [items] 追加到 [playlistId] 这个已有列表里。 */
    data class Append(
        override val name: String,
        override val items: List<PlaylistItem>,
        val playlistId: String,
    ) : PlaylistPlan
}
