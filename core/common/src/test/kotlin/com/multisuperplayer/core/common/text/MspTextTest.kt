package com.multisuperplayer.core.common.text

import com.multisuperplayer.core.common.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延迟解析的文案（见 [MspText] 的类注释）。
 *
 * 这里钉的是这套机制本身的三个要点：
 * 1. 嵌套参数会被递归解析（否则「设置摘要里嵌一个时长」就退化成传 `String`）；
 * 2. 非 [MspText] 的参数原样传下去（否则 `%1$d` 会因为被转成字符串而崩掉格式）；
 * 3. 兜底词是一个**共享**取值（设备信息和日志抬头各写一份的话，两处不一致会让排障的人
 *    以为它们来自不同字段）。
 *
 * `resolve` 本身（那一次 `resources.getString`）在 JVM 单测里测不到——没有真的
 * `Resources`——所以它尽可能薄：只做「摊平 + 取字符串」，判断全在 [MspText.Res.flatArgs]
 * 和调用方。这是有意的分工，不是漏测。
 */
class MspTextTest {

    @Test
    fun `没有参数时摊平结果为空`() {
        assertArrayEquals(arrayOf<Any?>(), MspText.Res(1).flatArgs { "不该被调用" })
    }

    @Test
    fun `嵌套的 MspText 参数会先被解析`() {
        val outer = MspText.Res(1, "甲", MspText.Plain("乙"), 3)

        val flat = outer.flatArgs { arg -> "解析(${arg})" }

        assertEquals(listOf("甲", "解析(Plain(text=乙))", 3), flat.toList())
    }

    @Test
    fun `多层嵌套每一层都会被解析`() {
        // 真实例子：`得到未提交改动的来源（v0.5.3（09b06a6（含未提交改动）））`，
        // 括号里还嵌了两层。
        //
        // 注意 `flatArgs` 只摊平**最外层**：内层交给注入的解析器，
        // 而真实现里它就是 `it.resolve(resources)`（自己会递归下去）。
        // 假解析器这里也递归，所以内层那串被完整展开过。
        val inner = MspText.Res(2, "14", 34)
        val outer = MspText.Res(1, inner, MspText.Plain("尾巴"))

        fun fakeResolve(text: MspText): String = when (text) {
            is MspText.Plain -> text.text
            is MspText.Res -> "«${text.id}»(" + text.flatArgs(::fakeResolve).joinToString(",") + ")"
        }

        assertEquals(listOf("«2»(14,34)", "尾巴"), outer.flatArgs(::fakeResolve).toList())
    }

    @Test
    fun `数字参数保持数字`() {
        // `%1$d` 收到 `String` 会 `IllegalFormatConversionException`。
        // 摊平不能顺手把参数转成字符串——这一点必须钉住。
        val flat = MspText.Res(1, 1080, 2400, 420).flatArgs { "x" }

        assertEquals(3, flat.size)
        assertTrue(flat[0] is Int && flat[1] is Int && flat[2] is Int)
    }

    @Test
    fun `null 参数原样传下去`() {
        val flat = MspText.Res(1, null).flatArgs { "x" }

        assertEquals(1, flat.size)
        assertEquals(null, flat[0])
    }

    @Test
    fun `unknown 就是那条未知文案`() {
        // 用相等而不是同一个实例：data class 已经保证了「同一条文案 == 相等」，
        // 而这条相等关系正是别的测试用来断言分支的写法。
        assertEquals(MspText.Res(R.string.msp_value_unknown), MspText.unknown())
        assertEquals(MspText.unknown(), MspText.unknown())
    }

    @Test
    fun `空白一律当成取不到`() {
        assertEquals(MspText.unknown(), MspText.plainOrUnknown(""))
        assertEquals(MspText.unknown(), MspText.plainOrUnknown("   "))
        assertEquals(MspText.unknown(), MspText.plainOrUnknown("\n\t"))
    }

    @Test
    fun `取到了就去掉前后空白`() {
        assertEquals(MspText.Plain("小米"), MspText.plainOrUnknown(" 小米 "))
    }

    @Test
    fun `Plain 的解析结果就是它自己`() {
        val text = MspText.Plain("arm64-v8a")

        assertEquals("arm64-v8a", text.text)
    }
}
