package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText

/**
 * 「这台机器跑得动这条模型吗」。
 *
 * ## 为什么只是提示，不是禁止
 *
 * 因为**我们判不准**。`totalMem` 是整机内存，模型能不能跑还取决于同一时刻有多少应用
 * 在后台、系统是 32 位还是 64 位、厂商的 LMKD 有多激进。判错的方向上代价并不对称：
 *
 * - 该警告而没警告 = 用户白下 1.82 GB（很糟）
 * - 不该警告而警告了 = 用户看到一句灰字，照样可以下（轻微）
 *
 * 所以这里只给一句话，**不置灰按钮**。真要拦住的话，代价是「配置够用但被我们误判」
 * 的用户永远用不上这条模型，而他没有别的办法绕过。
 *
 * ## 阈值为什么是 0.4
 *
 * 拿一个已知能跑的机型当锚：Pixel 8a 总内存 8 GB，官方实测这条模型峰值 2.68 GB，
 * 比值 0.34——**不能报警**。反过来，一台 6 GB 的中端机比值 0.46，那已经不是
 * 「可能有点紧张」了：Android 系统自己通常要吃掉 40% 上下，而进程单独要吃 2.7 GB
 * 时 LMKD 很容易在加载途中把它杀掉（症状是「翻译到一半闪退」，看起来像模型坏了）。
 *
 * 阈值取在两者之间，并且**必须是常数**：写成一个能配置的值会让「为什么这台机器警告、
 * 那台不警告」变成一个没法回答的问题。
 */
object LlmMemoryAdvice {

    /**
     * 峰值内存超过整机内存的这个比例就提示。见类注释里的 8 GB / 6 GB 两个锚点。
     */
    const val RISKY_TOTAL_MEMORY_RATIO: Double = 0.4

    /**
     * 该不该提示。
     *
     * 两边都要否掉，而且**两种「不知道」是不同的原因**：
     *
     * - [LlmModelInfo.peakMemoryBytes] 为 `null`：上游没给实测值，我们没有任何依据说
     *   它跑不动。猜一个数只会让提示变成谣言。
     * - `totalMemoryBytes <= 0`：读不到整机内存（旧系统、非常规 Context 包装）。
     *   这时若按 `0` 算，任何模型都会「超过 0 的 40%」⇒ 每条都警告，提示立刻失去意义。
     */
    fun isRisky(model: LlmModelInfo, totalMemoryBytes: Long): Boolean {
        val peak = model.peakMemoryBytes ?: return false
        if (totalMemoryBytes <= 0L) return false
        return peak > totalMemoryBytes * RISKY_TOTAL_MEMORY_RATIO
    }

    /**
     * 提示那一行：`本机总内存约 3.8 GB，这条模型可能跑不起来`。
     *
     * 把整机内存也写出来，是因为「可能跑不起来」单独一句没法行动：用户不知道
     * 是差一点还是差得远。有了这两个数（另一个在模型卡片上）他能自己判断
     * 「先关掉几个后台应用再试」还是「换 0.6B 那条」。
     */
    fun warningText(totalMemoryBytes: Long): MspText =
        MspText.Res(R.string.msp_llm_model_memory_warning, TimeFormat.fileSize(totalMemoryBytes))
}
