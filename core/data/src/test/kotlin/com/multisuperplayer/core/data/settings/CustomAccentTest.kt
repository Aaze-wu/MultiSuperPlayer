package com.multisuperplayer.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * 自定义强调色存储格式的单元测试。
 *
 * 这里的每一条都对应一种「用户的自定义色莫名其妙丢了 / 变成了别的颜色」：
 * 小数点被写成本地化的小数逗号、手改设置改坏一半、色相环上越界。
 */
class CustomAccentTest {

    @Test
    fun `编解码是精确往返的`() {
        // 写进去什么，读出来必须是同一个 Float：任何一点漂移都会让滑块自己跳一下
        // （因为滑块的初始位置就是读回来的这三个数）。
        val accent = CustomAccent(250f, 0.55f, 0.45f)

        assertEquals(accent, CustomAccentCodec.decode(CustomAccentCodec.encode(accent)))
    }

    @Test
    fun `写出的字面量是点号而不是逗号`() {
        // 这条看着像在测 `Float.toString`，其实是钉住**用户设备上的数据格式**：
        // 一旦哪天有人把手写拼接改成 `String.format("%.3f,...")`，德语/法语/中文区域
        // 会写出 `"250,000"`——分隔符从逗号变成小数点，三个数变成一个数，
        // 于是 decode 判定「只有 5 个部分」/「解析失败」，所有人的自定义色都回默认。
        // 只有在区域语言是逗号小数点的机器上才会复现，所以这里显式换一个区域来跑。
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("250.0,0.55,0.45", CustomAccentCodec.encode(CustomAccent(250f, 0.55f, 0.45f)))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `没有设置过与空串都是 null`() {
        assertNull(CustomAccentCodec.decode(null))
        assertNull(CustomAccentCodec.decode(""))
    }

    @Test
    fun `部分的数不够或不是数都是 null`() {
        // 手改设置文件的三种典型坏法。
        assertNull(CustomAccentCodec.decode("250,0.55"))
        assertNull(CustomAccentCodec.decode("250,0.55,0.45,1"))
        assertNull(CustomAccentCodec.decode("250,0.55,abc"))
        assertNull(CustomAccentCodec.decode("abc"))
    }

    @Test
    fun `非有限值当成没设置过`() {
        // `"NaN".toFloatOrNull()` 是能解析成功的，夹取也夹不住它（NaN 和任何数比较都是 false），
        // 放进去的话配色推导会得到全透明的颜色。所以必须在解析这一步就拒掉。
        assertNull(CustomAccentCodec.decode("NaN,0.5,0.5"))
        assertNull(CustomAccentCodec.decode("120,Infinity,0.5"))
        assertNull(CustomAccentCodec.decode("120,0.5,-Infinity"))
    }

    @Test
    fun `色相越界折算回一圈之内`() {
        // 色相是环：370° 就是 10°，-10° 就是 350°，而不是「非法」。
        assertEquals(10f, CustomAccentCodec.decode("370,0.5,0.5")!!.hueDegrees)
        assertEquals(350f, CustomAccentCodec.decode("-10,0.5,0.5")!!.hueDegrees)
        assertEquals(0f, CustomAccentCodec.decode("360,0.5,0.5")!!.hueDegrees)
    }

    @Test
    fun `有限但越界的饱和度与明度夹回自然范围`() {
        // 手改过设置的用户看到的是「颜色被夹到了边界」，而不是「我的设置没了」。
        val tooBig = CustomAccentCodec.decode("120,2.5,3.0")!!
        assertEquals(1f, tooBig.saturation)
        assertEquals(1f, tooBig.lightness)

        val tooSmall = CustomAccentCodec.decode("120,-1.0,-2.0")!!
        assertEquals(0f, tooSmall.saturation)
        assertEquals(0f, tooSmall.lightness)
    }

    @Test
    fun `前后空白不算错`() {
        // 手改文件时很容易在逗号后面留一个空格。
        assertEquals(
            CustomAccent(120f, 0.5f, 0.4f),
            CustomAccentCodec.decode(" 120 , 0.5 , 0.4 "),
        )
    }
}
