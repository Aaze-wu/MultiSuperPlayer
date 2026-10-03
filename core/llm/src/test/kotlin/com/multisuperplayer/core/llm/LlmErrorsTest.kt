package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.text.MspText
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载/安装失败文案的测试。
 *
 * 锁的核心是**每一档说不同的话**：[LlmModelException] 分了六档，就是因为下一步动作
 * 完全不同（等一等 / 换源 / 改地址 / 换源重试 / 清存储）。文案一旦合并成一句
 * 「下载失败」，用户能做的只有「再点一次」，而其中的 `BadSource` 那一档
 * 再点一百次也不会好。
 *
 * 断言方式与 `TranslationFailureTextTest` 一致：`describeLlmFailure` 返回 [MspText]
 * （「哪一条 + 什么参数」），JVM 单测里拿不到 `Resources`，所以断言资源 id，
 * 并把参数单独拎出来检查。
 */
class LlmErrorsTest {

    /** 取出「哪一条资源」。不是 `Res` 就说明有人往 core 层里写死了界面语言。 */
    private fun idOf(error: Throwable): Int {
        val text = error.describeLlmFailure()
        assertTrue("必须是资源而不是写死的字符串，实际是 $text", text is MspText.Res)
        return (text as MspText.Res).id
    }

    private fun argsOf(error: Throwable): List<Any?> = (error.describeLlmFailure() as MspText.Res).args

    // ------------------------------------------------------------ 每一档说不同的话

    @Test
    fun `六档失败各有一条资源，没有任何两档共用`() {
        val kinds = listOf(
            "network" to LlmModelException.Network(IOException("boom")),
            "http" to LlmModelException.Http(404),
            "source" to LlmModelException.BadSource("hf-mirror.com"),
            "size" to LlmModelException.SizeMismatch("m.litertlm", 100L, 50L),
            "hash" to LlmModelException.HashMismatch("m.litertlm"),
            "write" to LlmModelException.Write(IOException("no space")),
        )

        val ids = kinds.map { (name, error) -> name to idOf(error) }

        assertEquals("有几档落在同一条资源上：$ids", ids.size, ids.map { it.second }.toSet().size)
        // 每一档都不能落进「未知」那条兜底（落了就说明 when 分支写漏了）
        ids.forEach { (name, id) ->
            assertNotEquals("$name 掉进了未知兜底", R.string.msp_llm_error_unknown, id)
        }
    }

    @Test
    fun `http 失败报的是状态码那个数`() {
        // 「服务器返回 404」里的 404 是用户唯一能拿去搜的数字，
        // 而 `%1$d` 传成字符串会让 aapt2 的格式化在运行期抛异常
        assertEquals(R.string.msp_llm_error_http, idOf(LlmModelException.Http(404)))
        assertEquals(listOf<Any?>(404), argsOf(LlmModelException.Http(404)))
    }

    @Test
    fun `体积不符报的是两个格式化过的体积，不是裸字节数`() {
        val error = LlmModelException.SizeMismatch("m.litertlm", 344_671_744L, 1_024L)

        // `预期 344671744` 这种话没人读得出来，而它恰恰是用户唯一能拿去
        // 和「存储空间不足」对照的数字
        assertEquals(R.string.msp_llm_error_size, idOf(error))
        val args = argsOf(error)
        assertEquals(2, args.size)
        val expected = args[0].toString()
        val actual = args[1].toString()
        assertTrue("预期值没有被格式化：$expected", expected.contains("MB"))
        assertTrue("实际值没有被格式化：$actual", actual.contains("B"))
        assertNotEquals("两个数不能一样：$args", expected, actual)
    }

    @Test
    fun `下载源不合法时把那个地址原样带出来`() {
        // 带出来才看得出「少了 https://」还是「多了个空格」
        assertEquals(R.string.msp_llm_error_source, idOf(LlmModelException.BadSource("hf-mirror.com")))
        assertEquals(listOf<Any?>("hf-mirror.com"), argsOf(LlmModelException.BadSource("hf-mirror.com")))
    }

    @Test
    fun `系统异常当网络问题说，并且带上它自己的原话`() {
        // UnknownHostException 的 message 是 `api.example.com`，信息量最大
        val unknownHost = UnknownHostException("api.example.com")

        assertEquals(R.string.msp_llm_error_network, idOf(unknownHost))
        assertEquals(listOf<Any?>("api.example.com"), argsOf(unknownHost))
    }

    @Test
    fun `没有 message 的异常用类名兜底，绝不给出空串`() {
        // `SocketTimeoutException()` 的空构造是真实存在的写法，而空串会让界面上
        // 出现一句以「：」结尾的怪话
        val silent = SocketTimeoutException()

        val detail = argsOf(silent).single().toString()

        assertTrue("兜底值不能是空串", detail.isNotBlank())
        assertEquals("SocketTimeoutException", detail)
    }

    @Test
    fun `认不出的异常落进兜底那一档，不抛`() {
        // 下载器里任何一处 `catch (Throwable)` 都会走到这里；
        // 这里抛异常的话，`install()` 的 catch 又会拿到一个新异常，提示语就永远出不来
        assertEquals(R.string.msp_llm_error_unknown, idOf(IllegalStateException("莫名其妙")))
        assertEquals(R.string.msp_llm_error_unknown, idOf(RuntimeException()))
    }

    @Test
    fun `包装过的网络异常仍然按网络说`() {
        val wrapped = LlmModelException.Network(UnknownHostException("api.example.com"))

        // 原话取的是外层异常的 message（"network failure"），不是内层的。
        // 这一点是**故意的**：内层的 cause 在日志里有完整堆栈，界面上一句话就够。
        assertEquals(R.string.msp_llm_error_network, idOf(wrapped))
        assertEquals("network failure", argsOf(wrapped).single())
    }
}
