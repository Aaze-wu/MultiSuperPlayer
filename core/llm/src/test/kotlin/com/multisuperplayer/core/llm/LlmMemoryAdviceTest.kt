package com.multisuperplayer.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这台机器跑不跑得动这条模型」的判定。
 *
 * 这个判定的代价是**不对称**的，两边都用真机上的真实数字钉住：
 * - 该警告却没警告 = 用户白下 1.82 GB，而且下完之后很可能一加载就 OOM；
 * - 不该警告却警告 = 一句灰红字，用户照样可以下、也照样能用。
 *
 * 所以阈值宁可偏低（多报），但**不能低到把官方说能跑的机型也标红**——
 * 那时这句提示就变成噪音，用户学会无视它，于是上面那个代价又回来了。
 *
 * 两个锚点都是官方 manifest 里的实测值，不是估出来的：
 * - Pixel 8a（Tensor G3，7.75 GB 内存）：CPU 后端峰值 2685 MB，官方明说能跑；
 * - Galaxy S26 CPU 后端峰值 2760 MB。
 */
class LlmMemoryAdviceTest {

    private val gib = 1024L * 1024 * 1024

    private val hunyuan = LlmModelCatalog.byId(LlmModelCatalog.HY_MT2_18B_ID)

    private val qwen = LlmModelCatalog.byId(LlmModelCatalog.QWEN3_06B_ID)

    // ------------------------------------------------------------ 阈值两侧

    @Test
    fun `官方说能跑的机型不能报警`() {
        // Pixel 8a：8 GB 内存、峰值 2.68 GB。它是上游 README 里点名跑得通的机型，
        // 如果这条红了，说明阈值被调高了，而代价是「所有 8 GB 手机都被劝退」。
        assertFalse(LlmMemoryAdvice.isRisky(hunyuan, 8 * gib))
    }

    @Test
    fun `中端机要报警`() {
        // 反过来那一半。两半合起来才说明阈值夹在 8 GB 与 6 GB 之间，
        // 而不是随手取的一个数——只有一侧的测试，把阈值改成 0.99 也照样绿。
        assertTrue(LlmMemoryAdvice.isRisky(hunyuan, 6 * gib))
    }

    @Test
    fun `本项目的模拟器上必然报警`() {
        // 4 GB 的 AVD 正是本版做验证要用的那台机器。这条断言同时是
        // 「内存提示这一版能被真机测到」的保证：如果哪天它不再报警，
        // 说明模型清单里那个内存数字被删了，而不是模拟器变大了。
        assertTrue(LlmMemoryAdvice.isRisky(hunyuan, 4 * gib))
    }

    @Test
    fun `轻量那条在同样小的机器上不报警`() {
        // 阈值不是「所有模型都比一比」：0.6B 那条在 4 GB 上都够用，
        // 对它报警会把「换一条小的」这条唯一出路也一起标红。
        assertFalse(LlmMemoryAdvice.isRisky(qwen, 4 * gib))
    }

    // ------------------------------------------------------------ 两种「不知道」

    @Test
    fun `上游没给实测内存时不猜`() {
        // 猜的两种方向都有害：猜小 ⇒ 永远不警告（假的安全结论），
        // 猜大 ⇒ 在中端机上劝退一条其实跑得动的模型。
        assertFalse(LlmMemoryAdvice.isRisky(qwen, 1 * gib))
        assertFalse(LlmMemoryAdvice.isRisky(qwen, 128 * gib))
    }

    @Test
    fun `读不到整机内存时不报警`() {
        // `ActivityManager` 读不到时调用方传 `0`。这一档与上一档不同：
        // 模型的内存需求是知道的，只是**不知道这台机器有多少**——
        // 那种情况下必须沉默，因为 `0` 一旦被当成真值，任何模型都会大于 0。
        assertFalse(LlmMemoryAdvice.isRisky(hunyuan, 0L))
        assertFalse(LlmMemoryAdvice.isRisky(hunyuan, -1L))
    }
}
