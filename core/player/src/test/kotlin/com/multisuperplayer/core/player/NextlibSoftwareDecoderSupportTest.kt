package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 软件解码探测的边界行为。
 *
 * 这个类看起来没什么可测（「JVM 上当然没有 libmedia3ext.so」），但它锁住的是一类
 * **启动即崩**的失败：`FfmpegLibrary.isAvailable()` 走 JNI，原生库缺失时抛的是
 * `UnsatisfiedLinkError`——那是 `Error` 不是 `Exception`，`catch (e: Exception)`
 * 根本抓不到。探测本身没做兜底的话，症状是「换一个 CPU 架构装机 → 一打开应用就闪退」。
 *
 * 所以这里要的是：**探测失败必须表达成「不可用」，而不是把异常透出去**。
 */
class NextlibSoftwareDecoderSupportTest {

    @Test
    fun `原生库加载不了时报告不可用而不是抛异常`() {
        val support = NextlibSoftwareDecoderSupport()

        // 不 catch 任何东西：只要探测把 UnsatisfiedLinkError 透出来，这条测试就红。
        assertFalse("JVM 单测环境里没有 libmedia3ext.so，应当报告不可用", support.available)
        assertNull("不可用时不该给出一个编造的版本号", support.version)
    }

    @Test
    fun `不可用的实现是个正常值而不是 null`() {
        // 用 `SoftwareDecoderSupport.Unavailable` 而不是让内核收一个可空参数：
        // 「没有 FFmpeg」是一种正常状态，用 null 表示的话，每个使用者都得
        // 自己决定「null 是什么意思」，迟早有人把它当「还没探测」处理。
        val support = SoftwareDecoderSupport.Unavailable

        assertFalse(support.available)
        assertNull(support.version)
        assertEquals(false, support.available)
    }
}
