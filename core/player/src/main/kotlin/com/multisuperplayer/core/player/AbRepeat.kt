package com.multisuperplayer.core.player

/**
 * A-B 循环的状态。
 *
 * 三个状态，**不是一个可空 range**：
 *
 * ```
 * None ──点第 1 下──▶ (A, null) ──点第 2 下──▶ (A, B) ──点第 3 下──▶ None
 * ```
 *
 * 为什么不能只用「一个完整的区间 + null」表示：用户在 A 点按下第一下之后，
 * 屏幕上必须能标出「A 在这里」——如果这一刻只是 null，用户看到的就是
 * 「点了没反应」，然后他会在同一个地方再点一下，得到一个零长度的循环
 * （表现为一直在原地卡着，看起来就是播放器挂了）。
 *
 * @param startMs A 点位置；null = 还没设过 A。
 * @param endMs B 点位置；null = 只设了 A，还没开始循环。
 */
data class AbRepeatState(
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    /** 已经在循环了（A 和 B 都有）。 */
    val isActive: Boolean get() = startMs != null && endMs != null

    /** 只标了 A，等用户点第二下。 */
    val isWaitingForEnd: Boolean get() = startMs != null && endMs == null

    /** 什么都不显示。 */
    val isEmpty: Boolean get() = startMs == null && endMs == null

    /** 够不够长到值得循环。见 [AbRepeatPolicy.MIN_SPAN_MS]。 */
    val spanMs: Long get() = if (startMs != null && endMs != null) endMs - startMs else 0L

    /**
     * 位置是否已经越过 B 点、该绕回 A 点了。
     *
     * 用「>=」而不是「==」：位置是每 50ms 采一次的离散值，几乎不可能**正好**
     * 落在 B 上，用相等判断等于永远不触发。
     */
    fun hasPassedEnd(positionMs: Long): Boolean =
        endMs != null && positionMs >= endMs

    companion object {
        val None = AbRepeatState()
    }
}

/**
 * A-B 循环的状态推进规则。
 *
 * 做成纯函数（不碰内核、不碰时间）是为了能穷举：这个功能只有一个交互
 * （反复点同一个按钮），三条分支、两个边界，全部可以用单测钉死。
 * 放在 `ExoPlayerController` 里内联写的话，「第二次点的位置离 A 太近」这种
 * 边界永远不会被测到，而它恰恰是最容易把用户卡住的那一种。
 */
object AbRepeatPolicy {

    /**
     * A 和 B 至少差这么远才算一个有效的循环。
     *
     * 1 秒的依据：位置刷新是 50ms 一次，用户手抖最多也就差几百毫秒；
     * 而低于 1 秒的循环区间在听觉上根本不是一个「片段」，是一个爆音。
     */
    const val MIN_SPAN_MS = 1_000L

    /**
     * 按一下「A-B 循环」按钮之后的新状态。
     *
     * 语义（三条，顺序即优先级）：
     * 1. 还没设 A → 把当前位置设为 A；
     * 2. 设了 A 但没设 B → 当前位置设为 B，开始循环；**但**若离 A 不到
     *    [MIN_SPAN_MS]，说明用户其实是想取消，直接清空（见下）；
     * 3. 已经在循环 → 清空。
     *
     * 第 2 条里那个「太近就清空」是刻意选的：另一种做法是「太近就把它当成新的 A」，
     * 但那和「在同一个地方点两下」几乎无法区分，用户会陷入「怎么点都取消不掉」
     * 的状态。清空则保证**任何状态再点一下都能往前走**，不存在死角。
     *
     * @param durationMs 总时长，未知时传 0。>0 时位置会被夹进 `[0, durationMs]`：
     *   播到结尾时 `currentPosition` 可能略微超过 `duration`，不夹的话 A 会落在
     *   文件外面，绕回去就是一次 seek 到不存在的位置（内核自己会夹，但那时
     *   界面上的标记和实际位置就对不上了）。
     */
    fun advance(
        current: AbRepeatState,
        positionMs: Long,
        durationMs: Long = 0L,
    ): AbRepeatState {
        val position = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }

        return when {
            current.startMs == null -> AbRepeatState(startMs = position, endMs = null)

            current.endMs == null -> {
                val span = position - current.startMs
                if (span >= MIN_SPAN_MS) {
                    AbRepeatState(startMs = current.startMs, endMs = position)
                } else {
                    AbRepeatState.None
                }
            }

            else -> AbRepeatState.None
        }
    }

    /**
     * 把状态夹到当前时长之内（换歌/换文件之后调用）。
     *
     * 为什么需要它：A-B 是**跨媒体**残留的状态吗？不是——但它确实可能在新文件
     * 开始播放的那一瞬间还在（用户切歌时上一首的 A 标记还挂在屏幕上）。
     * 新文件比旧文件短的话，那个 B 点就在文件外面，循环会一直 seek 到结尾
     * 然后卡住。所以每次换媒体都夹一次；夹不了（比如 B 超出新时长）就整体清空。
     */
    fun clampTo(state: AbRepeatState, durationMs: Long): AbRepeatState {
        if (state.isEmpty) return state
        if (durationMs <= 0L) return state

        val start = state.startMs?.coerceIn(0L, durationMs)
        val end = state.endMs?.coerceIn(0L, durationMs)
        // 夹完还要满足「至少差 MIN_SPAN_MS」，否则宁可整个清掉：
        // 留一个零长度的循环等于把播放器卡死在该点上。
        if (start == null || end == null) return AbRepeatState(startMs = start, endMs = null)
        return if (end - start >= MIN_SPAN_MS) {
            AbRepeatState(startMs = start, endMs = end)
        } else {
            AbRepeatState.None
        }
    }
}
