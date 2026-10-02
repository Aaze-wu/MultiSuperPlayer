package com.multisuperplayer.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字段转义/还原的单元测试。
 *
 * 这一层的失败**不抛异常**，只是让字段内容少一半（「标题」变成「标题的前半段」），
 * 而界面上没有任何提示。用户看到的是一个奇怪的名字，没人会去怀疑存储层。
 * 所以每个特殊字符都要有测试。
 */
class FieldTextCodecTest {

    @Test
    fun `普通字段直接拼接`() {
        assertEquals("a|b|c", FieldTextCodec.join(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), FieldTextCodec.split("a|b|c"))
    }

    @Test
    fun `字段里的分隔符被转义`() {
        val joined = FieldTextCodec.join(listOf("A|B", "C"))
        assertEquals("A\\|B|C", joined)
        // 关键：拆回来是两个字段，不是三个。
        assertEquals(listOf("A|B", "C"), FieldTextCodec.split(joined))
    }

    @Test
    fun `字段里的反斜杠被转义`() {
        val joined = FieldTextCodec.join(listOf("1\\2", "3"))
        assertEquals("1\\\\2|3", joined)
        assertEquals(listOf("1\\2", "3"), FieldTextCodec.split(joined))
    }

    @Test
    fun `反斜杠紧挨着分隔符时也能还原`() {
        // 「末尾是反斜杠的目录名」+ 分隔符 = 最容易出错的一种输入。
        val joined = FieldTextCodec.join(listOf("A\\", "B"))
        assertEquals(listOf("A\\", "B"), FieldTextCodec.split(joined))
    }

    @Test
    fun `多个字段同时含特殊字符`() {
        val fields = listOf("A|B", "C\\D", "E", "F\\\\G|H")
        assertEquals(fields, FieldTextCodec.split(FieldTextCodec.join(fields)))
    }

    @Test
    fun `空字段保留位置`() {
        assertEquals(listOf("", "", ""), FieldTextCodec.split(FieldTextCodec.join(listOf("", "", ""))))
        // 空输入也要给一个空字段，而不是空列表：字段个数是调用方判断格式的依据。
        assertEquals(listOf(""), FieldTextCodec.split(""))
    }

    @Test
    fun `末尾悬空的反斜杠按字面量处理且不抛异常`() {
        // 记录被写坏时（截断）不能抛异常：一抛就等于整张表读不出来。
        assertEquals(listOf("a\\"), FieldTextCodec.split("a\\"))
    }

    @Test
    fun `unescape 还原单个字段且不切分`() {
        assertEquals("A|B", FieldTextCodec.unescape("A\\|B"))
        assertEquals("A\\B", FieldTextCodec.unescape("A\\\\B"))
        // 没有转义前缀的分隔符原样保留——这里本来就只有一个字段。
        assertEquals("A|B", FieldTextCodec.unescape("A|B"))
        assertEquals("", FieldTextCodec.unescape(""))
    }

    @Test
    fun `中文和 emoji 原样往返`() {
        val fields = listOf("中文|标题", "emoji 🎵\\", "Artist｜全角竖线")
        assertEquals(fields, FieldTextCodec.split(FieldTextCodec.join(fields)))
    }

    @Test
    fun `换行没有转义也没有被切开`() {
        // 换行不在转义表里，但它也不是分隔符，所以单字段里原样保留。
        // （需要「一条记录一行」的场景用的是 PlaylistCodec 自己的转义表。）
        val joined = FieldTextCodec.join(listOf("line1\nline2", "x"))
        assertEquals(listOf("line1\nline2", "x"), FieldTextCodec.split(joined))
    }
}
