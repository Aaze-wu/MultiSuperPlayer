package com.multisuperplayer.core.data.update

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 下载失败的归类。
 *
 * 这一组测试存在的直接原因是一次真机崩溃：`SocketException: Connection reset` 从
 * `HttpURLConnection.getResponseCode()` 原样抛出，穿过 `UpdateViewModel` 的
 * `catch (e: UpdateException)` 冒到 `viewModelScope` ⇒ `FATAL EXCEPTION: main`。
 *
 * 所以这里断言的不是「异常类对不对」这一件小事，而是**「这个组件的失败一律是
 * `UpdateException`」这条契约**：只要有一个 IO 异常没被归类，应用就会崩。
 * 顺带钉住反向的两个边界（已分类的失败不许被套两层、编程错误不许被伪装成网络问题），
 * 否则「多加保险」会把真正的 bug 藏起来。
 */
class UpdateDownloaderFailureTest {

    @Test
    fun `连接被重置 - 归成网络失败并保留原始异常`() {
        val original = SocketException("Connection reset")

        val mapped = downloadFailureOf(original)

        assertTrue("SocketException 必须归成 Network，实际 ${mapped.javaClass.name}", mapped is UpdateException.Network)
        assertSame("原始异常必须挂成 cause，否则日志里看不到堆栈", original, mapped.cause)
    }

    @Test
    fun `读超时和域名解析不了 - 也走同一条路`() {
        // 这两个在真机上比「连接被重置」更常见：慢网络下发到一半卡住、或者
        // 用户从 Wi-Fi 切到移动数据时 DNS 换了。
        val timeouts = listOf(
            SocketTimeoutException("Read timed out"),
            UnknownHostException("objects.githubusercontent.com"),
            IOException("unexpected end of stream"),
        )

        timeouts.forEach { e ->
            val mapped = downloadFailureOf(e)
            assertTrue("${e.javaClass.simpleName} 必须归成 Network", mapped is UpdateException.Network)
            assertSame(e, mapped.cause)
        }
    }

    @Test
    fun `已分类的失败不被套两层`() {
        // 下载器自己 throw 的 `Http(403)` / `Checksum` / `AssetMismatch` 也会经过同一个
        // catch。再包一层 `Network` 会把「限流」说成「网络不通」，用户就会去做无用功。
        val already = UpdateException.Http(code = 403, rateLimited = true, remaining = "0")

        val mapped = downloadFailureOf(already)

        assertSame("已经分好类的失败必须原样放行", already, mapped)
    }

    @Test
    fun `协程取消原样透传`() {
        // `CancellationException` **不是** IOException，但它必须**不是**失败：
        // 用户离开页面、或者按下取消，都不该在界面上留下「网络不可达」。
        val cancelled = CancellationException("页面已离开")

        val mapped = downloadFailureOf(cancelled)

        assertSame("取消不是失败，必须原样重抛", cancelled, mapped)
    }

    @Test
    fun `编程错误原样重抛 - 不许伪装成网络问题`() {
        // 反过来的坑：如果这里写成 `catch (Throwable) → Network`，那么未来任何一个
        // NPE 都会被显示成「请检查网络连接」，而真因永远查不出来。
        listOf(
            IllegalStateException("FileOutputStream closed"),
            NullPointerException("connection"),
        ).forEach { e ->
            val mapped = downloadFailureOf(e)
            assertSame("${e.javaClass.simpleName} 必须原样重抛", e, mapped)
        }
    }

    @Test
    fun `归类结果是可读的失败文案而不是原始异常名`() {
        // 端到端的一小步：归类之后 `describeUpdateFailure()` 必须能给出
        // 「网络」那一条文案，而不是落到「未知失败」的兜底分支。
        val text = (downloadFailureOf(SocketException("Connection reset")) as UpdateException)
            .describeUpdateFailure()

        assertEquals(UpdateFailureText.Network, text)
    }
}
