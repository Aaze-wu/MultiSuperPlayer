package com.multisuperplayer.core.common.log

/** 一个日志文件的「身份」：只带清理策略需要的两个字段，好让策略本身能被单测。 */
data class LogFileEntry(val name: String, val bytes: Long)

/**
 * 日志文件的清理策略。
 *
 * 两条规则叠加，都是**纯计算**（不碰文件系统）：
 *
 * 1. **数量**：只留最新的 [KEEP_FILES] 个文件。文件名是 `msp-yyyy-MM-dd.log`，
 *    字典序恰好等于时间序，所以「最新」直接按名字排，不需要任何日期运算——
 *    也就不会出现「跨月/跨年算错」或者「用户调了系统时间导致历史被误删」。
 * 2. **体积**：保留的文件总大小超过 [MAX_TOTAL_BYTES] 时，**从最旧的开始删**，
 *    直到回到上限以内。
 *
 * 唯一的硬约束：**最新的那个文件永远不删**。没有它，一台日志刷得特别猛的设备
 * 会在「单个文件就已经超过总上限」时把当天日志也删掉，而当天日志正是要导出的那份
 * ——用户点「导出日志」会得到一份空的报告。
 */
object LogRetention {

    /** 保留最近多少个日志文件（一天一个）。 */
    const val KEEP_FILES: Int = 7

    /** 全部日志文件加起来的体积上限。 */
    const val MAX_TOTAL_BYTES: Long = 4L * 1024 * 1024

    /**
     * 返回需要删除的文件名，**从最旧的到最新的**。
     *
     * 顺序要定下来：调用方会把结果打进日志（「清理了 2 个旧日志」），未定义的顺序
     * 会让同一件事在每次运行里显示出不同的结果，看起来像出了随机问题。
     * 选「从旧到新」是因为它和「从最旧的开始删」这条规则读起来一致。
     *
     * [maxBytes] 传 0 或负数表示**不限制体积**（只按数量清理）。
     * 这里刻意把「0」定义成「不限制」而不是「全删」：这个参数的来源是常量或配置，
     * 一个写错的值不该把用户的日志清空。
     */
    fun plan(
        entries: List<LogFileEntry>,
        keepFiles: Int = KEEP_FILES,
        maxBytes: Long = MAX_TOTAL_BYTES,
    ): List<String> {
        val newestFirst = entries.sortedByDescending { it.name }
        val doomed = mutableListOf<String>()

        // --- 规则 1：数量 ---
        // 至少留一个。`keepFiles = 0` 时若照字面执行会把包括最新那个在内的全部文件删掉，
        // 正好违反上面那条「最新的那个永远不删」——而调用方完全可能把这个参数配成 0
        // （它的语义容易被理解成「不留历史」），那一天用户的日志会一条不剩。
        val effectiveKeep = keepFiles.coerceAtLeast(1)
        val kept = mutableListOf<LogFileEntry>()
        newestFirst.forEachIndexed { index, entry ->
            if (index < effectiveKeep) kept += entry else doomed += entry.name
        }

        // --- 规则 2：体积（从最旧的开始删，最新的一律保留） ---
        if (maxBytes > 0 && kept.size > 1) {
            var total = kept.sumOf { it.bytes }
            for (index in kept.indices.reversed()) {
                if (total <= maxBytes) break
                if (index == 0) break // 最新的那个不删
                doomed += kept[index].name
                total -= kept[index].bytes
            }
        }

        return doomed.sorted()
    }
}
