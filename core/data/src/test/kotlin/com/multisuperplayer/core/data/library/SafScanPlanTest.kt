package com.multisuperplayer.core.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SafScanPlan.of] 的单元测试。
 *
 * 这个判断决定两件事：**SAF 目录树要不要真的去枚举**（几万次 provider 调用的那种），
 * 以及**要不要先发一版只有 MediaStore 的中间结果**。判错的两个方向代价不同：
 *
 * - 该扫却报 `Cached` ⇒ 用户新授权的目录永远不出现，而且没有任何报错；
 * - 不该扫却报 `WalkTrees` ⇒ 每次自动刷新都白跑一遍全量枚举（这正是「启动/刷新很慢」
 *   的一个独立来源），外加多闪一次列表。
 *
 * 所以四个分支挨个钉一遍，包括最容易写错的优先级：缓存命中要排在「清单读不出来」前面。
 */
class SafScanPlanTest {

    @Test
    fun `有缓存且没强制重扫时直接命中缓存`() {
        assertEquals(
            SafScanPlan.Cached,
            SafScanPlan.of(treeUris = listOf("content://tree/a"), force = false, hasCache = true),
        )
    }

    @Test
    fun `强制重扫即使有缓存也要走目录树`() {
        // 「重新扫描」按钮和「增删授权目录」走的就是这条：用户的心智是「我现在就要
        // 最新的」，给他缓存等于点了没反应。
        assertEquals(
            SafScanPlan.WalkTrees,
            SafScanPlan.of(treeUris = listOf("content://tree/a"), force = true, hasCache = true),
        )
    }

    @Test
    fun `有授权目录且没有缓存时要走目录树`() {
        assertEquals(
            SafScanPlan.WalkTrees,
            SafScanPlan.of(treeUris = listOf("content://tree/a"), force = false, hasCache = false),
        )
    }

    @Test
    fun `一棵授权目录都没有时结果是空并且要覆盖缓存`() {
        assertEquals(
            SafScanPlan.NoTrees,
            SafScanPlan.of(treeUris = emptyList(), force = false, hasCache = true),
        )
    }

    @Test
    fun `清单读不出来时沿用缓存而不是当成没有授权`() {
        // null = 「不知道现在有哪些树」，和空表 = 「确定一棵都没有」是两件事。
        // 混为一谈会让媒体库里所有 SAF 条目突然消失一次，而它们其实还在。
        assertEquals(
            SafScanPlan.ReuseCache,
            SafScanPlan.of(treeUris = null, force = false, hasCache = false),
        )
        assertEquals(
            SafScanPlan.ReuseCache,
            SafScanPlan.of(treeUris = null, force = true, hasCache = false),
        )
    }

    @Test
    fun `缓存命中优先于清单读不出来`() {
        // 这条顺序看着无关紧要，实际上决定了每次自动刷新会不会刷一条告警日志：
        // 有缓存时压根不需要关心清单。
        assertEquals(
            SafScanPlan.Cached,
            SafScanPlan.of(treeUris = null, force = false, hasCache = true),
        )
    }
}
