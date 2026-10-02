package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 切批。
 *
 * 最要紧的一条是**进度分母**：`planTranslationBatches` 与本模块的
 * `translatableIndices()` 必须用同一个「这行能不能翻」的口径。
 * 两处不一致的话，注释行会被算进总数——那条片子就永远停在 87%。
 */
class TranslationBatchingTest {

    @Test
    fun `注释行与空行不算可翻行`() {
        assertEquals(null, cueText(docOf("").cues, 0))
        assertEquals(null, cueText(docOf("   ").cues, 0))
        assertEquals(null, cueText(docOf("歌词", commentIndices = setOf(0)).cues, 0))
        assertEquals(null, cueText(docOf("a").cues, 99))
        assertEquals("a", cueText(docOf(" a ").cues, 0))
    }

    @Test
    fun `可翻下标与切批口径一致`() {
        val document = doc(6, commentIndices = setOf(2, 4))
        val indices = document.translatableIndices()
        val planned = planTranslationBatches(document.cues).flatMap { it.cueIndices }

        assertEquals(listOf(0, 1, 3, 5), indices)
        assertEquals(indices, planned)
    }

    @Test
    fun `全是注释时没有批次`() {
        val document = doc(3, commentIndices = setOf(0, 1, 2))
        assertTrue(document.translatableIndices().isEmpty())
        assertTrue(planTranslationBatches(document.cues).isEmpty())
        assertTrue(planTranslationBatches(emptyList()).isEmpty())
    }

    @Test
    fun `按行数切批`() {
        val batches = planTranslationBatches(doc(20).cues, batchSize = 8)
        assertEquals(listOf(8, 8, 4), batches.map { it.texts.size })
        // 20 行按 8 切 ⇒ 三批，起点是 0/8/16（最后一批只剩 4 行）。
        assertEquals(listOf("t0", "t8", "t16"), batches.map { it.texts.first() })
        assertEquals(listOf("t7", "t15", "t19"), batches.map { it.texts.last() })
        // 切批只负责分组，一行都不能丢、也不能重复。
        assertEquals((0 until 20).toList(), batches.flatMap { it.cueIndices })
    }

    @Test
    fun `按字符数提前切批`() {
        val cues = docOf(*Array(10) { "字".repeat(100) }).cues
        // 每行 100 字 + 分隔符 1 ⇒ 两行 201 字。
        assertEquals(listOf(2, 2, 2, 2, 2), planTranslationBatches(cues, maxChars = 250).map { it.texts.size })
        assertEquals(
            List(10) { 1 },
            planTranslationBatches(cues, maxChars = TranslationBatching.MIN_MAX_CHARS).map { it.texts.size },
        )
    }

    @Test
    fun `单行超过预算也自成一批`() {
        // 不特殊处理的话会切出空批次（甚至死循环），而那表现为「卡在准备阶段」。
        val batches = planTranslationBatches(docOf("长".repeat(5_000)).cues, maxChars = 200)
        assertEquals(1, batches.size)
        assertEquals(1, batches.first().texts.size)
    }

    @Test
    fun `批次大小参数被夹到合法范围`() {
        assertEquals(3, planTranslationBatches(doc(3).cues, batchSize = 0).size)
        assertEquals(
            1,
            planTranslationBatches(doc(3).cues, batchSize = TranslationBatching.MAX_BATCH_SIZE + 100).size,
        )
    }

    @Test
    fun `上文只往回取可翻行`() {
        val cues = docOf("x", "", "y", "z").cues
        val batches = planTranslationBatches(cues, batchSize = 1)
        assertEquals(listOf(listOf(0), listOf(2), listOf(3)), batches.map { it.cueIndices })
        assertEquals(emptyList(), batches[0].contextBefore)
        // 中间的空行不能混进上文：模型会把空行当成「这里该断句」。
        assertEquals(listOf("x"), batches[1].contextBefore)
        assertEquals(listOf("x", "y"), batches[2].contextBefore)
    }

    @Test
    fun `上文行数被夹到上限`() {
        val batches = planTranslationBatches(doc(20).cues, batchSize = 1, contextLines = 999)
        assertEquals(TranslationBatching.MAX_CONTEXT_LINES, batches[10].contextBefore.size)
        assertEquals(emptyList(), planTranslationBatches(doc(3).cues, batchSize = 1, contextLines = 0)[1].contextBefore)
    }

    @Test
    fun `只翻指定行`() {
        val batches = planTranslationBatches(doc(20).cues, pendingIndices = listOf(5, 99, 5, 1), batchSize = 8)
        assertEquals(listOf(1, 5), batches.flatMap { it.cueIndices })
    }

    @Test
    fun `指定行里夹着注释时被跳过`() {
        val document = doc(6, commentIndices = setOf(1))
        val batches = planTranslationBatches(
            document.cues,
            pendingIndices = document.translatableIndices(),
            batchSize = 8,
        )
        assertEquals(listOf(0, 2, 3, 4, 5), batches.flatMap { it.cueIndices })
    }

    @Test
    fun `上下文取不到时不补空串`() {
        assertTrue(contextBefore(doc(3).cues, 0, 2).isEmpty())
        assertTrue(contextBefore(doc(3).cues, 1, 0).isEmpty())
        assertNull(cueText(doc(1).cues, -1))
    }
}
