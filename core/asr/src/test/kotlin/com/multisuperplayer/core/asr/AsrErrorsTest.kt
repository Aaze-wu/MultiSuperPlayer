package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * ASR 失败文案的测试。
 *
 * 锁的核心是**每一档说不同的话**：云端识别分了九档、加上本机那七档，就是因为
 * 「用户下一步该做什么」完全不同（改密钥 / 换地址 / 换模型 / 换服务商 / 等一等 / 清存储）。
 * 一旦合并成一句「识别失败」，用户能做的只有反复重试——而其中
 * [AsrException.CloudEndpoint]（地址写错）和 [AsrException.CloudBlocked]（账户策略挡的）
 * 这两档，重试一万次也不会好。
 *
 * 断言方式与 `LlmErrorsTest` 一致：`describeAsrFailure` 返回 [MspText]
 * （「哪一条 + 什么参数」），JVM 单测里拿不到 `Resources`，所以断言资源 id，
 * 参数单独拎出来检查。
 */
class AsrErrorsTest {

    /** 取出「哪一条资源」。不是 `Res` 就说明有人往 core 层里写死了界面语言。 */
    private fun idOf(error: Throwable): Int {
        val text = error.describeAsrFailure()
        assertTrue("必须是资源而不是写死的字符串，实际是 $text", text is MspText.Res)
        return (text as MspText.Res).id
    }

    private fun argsOf(error: Throwable): List<Any?> = (error.describeAsrFailure() as MspText.Res).args

    // ------------------------------------------------------------ 每一档说不同的话

    @Test
    fun `每一档失败各有一条资源，没有任何两档共用`() {
        val kinds = listOf(
            "模型网络" to AsrModelException.Network(IOException("boom")),
            "模型 HTTP" to AsrModelException.Http(404),
            "下载源地址" to AsrModelException.BadSource("hf-mirror.com"),
            "体积不符" to AsrModelException.SizeMismatch("m.onnx", 100L, 50L),
            "哈希不符" to AsrModelException.HashMismatch("m.onnx"),
            "写文件" to AsrModelException.Write(IOException("no space")),
            "模型没装" to AsrException.ModelMissing(fakeModel()),
            "没有音轨" to AsrException.NoAudioTrack,
            "没有时长" to AsrException.UnknownDuration,
            "解码失败" to AsrException.Decode(IOException("codec")),
            "没人说话" to AsrException.Silent,
            "引擎坏了" to AsrException.Engine(IllegalStateException("jni")),
            "存不下来" to AsrException.StorageFailed,
            "云端网络" to AsrException.CloudNetwork(IOException("timeout")),
            "云端 401" to AsrException.CloudAuth("groq"),
            "云端 403" to AsrException.CloudBlocked("policy"),
            "云端 404" to AsrException.CloudEndpoint(404),
            "云端地址没填" to AsrException.CloudAddress,
            "云端 400" to AsrException.CloudRequest("bad model"),
            "云端 413" to AsrException.CloudTooLarge(9_600_000L),
            "云端 429" to AsrException.CloudQuota("slow down"),
            "云端 5xx" to AsrException.CloudServer(503, "maintenance"),
            "云端响应" to AsrException.CloudResponse("<html>"),
        )

        val ids = kinds.map { (name, error) -> name to idOf(error) }

        assertEquals("有几档落在同一条资源上：$ids", ids.size, ids.map { it.second }.toSet().size)
        ids.forEach { (name, id) ->
            assertNotEquals("$name 掉进了未知兜底", R.string.msp_asr_error_unknown, id)
        }
    }

    @Test
    fun `云端 401 与 403 是两条不同的资源`() {
        // 整个云端错误分类里最要紧的一条：403 时密钥**可能是完全正确的**，
        // 「去重填密钥」是错的建议。两档共用一个资源就等于把这条区分抹掉。
        assertNotEquals(
            "403 不能和 401 说同一句话：403 改密钥是无效操作",
            idOf(AsrException.CloudAuth("groq")),
            idOf(AsrException.CloudBlocked("policy")),
        )
    }

    @Test
    fun `云端 404 与 400 是两条不同的资源`() {
        // 404 该改地址，400 该换模型——两个完全相反的动作。
        assertNotEquals(
            idOf(AsrException.CloudEndpoint(404)),
            idOf(AsrException.CloudRequest("bad model")),
        )
    }

    @Test
    fun `地址没填完与地址上没接口是两条不同的资源`() {
        // 404 证明**连接成功过**（域名对、只是没有这个接口），所以该补路径；
        // 地址没填完根本发不出去，该把地址填完整。两句要是同一句，
        // 第一次用自定义预设的人就会按 404 的建议去改路径——而他手上什么地址都没有。
        assertNotEquals(
            idOf(AsrException.CloudAddress),
            idOf(AsrException.CloudEndpoint(404)),
        )
        assertNotEquals(
            "空地址不能报成「连不上服务」：那会让人去换网络",
            idOf(AsrException.CloudNetwork(IOException("boom"))),
            idOf(AsrException.CloudAddress),
        )
    }

    // ---------------------------------------------------------------- 参数取值

    @Test
    fun `401 报的是本地化的服务商名而不是 id`() {
        // `serviceId` 进异常是为了让文案能延迟到「显示的那一刻」再解析语言——
        // 直接存显示名的话，用户切换语言后这句提示会停在旧语言上。
        assertEquals(
            MspText.Res(R.string.msp_asr_svc_siliconflow_name),
            argsOf(AsrException.CloudAuth(AsrServices.SILICONFLOW.id))[0],
        )
    }

    @Test
    fun `认不出的服务商 id 回退成「自定义」而不是把原始 id 印到界面上`() {
        // 用户存的 id 可能来自一个已经被删掉的预设。把 `my-old-gateway` 原样印进
        // 「… 拒绝了这次请求」既没有意义又像是内部错误。
        assertEquals(
            MspText.Res(R.string.msp_asr_svc_custom_name),
            argsOf(AsrException.CloudAuth("my-old-gateway"))[0],
        )
    }

    @Test
    fun `详情为空时换成专门的文案而不是留一个悬空冒号`() {
        // `%1$s` 拿到空串会渲染成「服务商不接受这次请求：。常见原因是……」。
        // 空串在整条链路上都必须当作「没有」，而且**只丢那一个字段**、
        // 不能丢掉整条提示（错误类型才是「该做什么」的来源）。
        assertEquals(MspText.Res(R.string.msp_asr_error_cloud_no_detail), argsOf(AsrException.CloudBlocked(""))[0])
        assertEquals(
            MspText.Res(R.string.msp_asr_error_cloud_no_detail),
            argsOf(AsrException.CloudQuota("   "))[0],
        )
    }

    @Test
    fun `详情里的换行被压平`() {
        assertEquals(
            MspText.Plain("line1 line2"),
            argsOf(AsrException.CloudRequest("line1\nline2"))[0],
        )
    }

    @Test
    fun `过长的详情被截断并带省略号`() {
        val long = "x".repeat(500)
        val rendered = argsOf(AsrException.CloudServer(500, long))[1] as MspText.Plain
        assertTrue("截断要看得见：${rendered.text.takeLast(4)}", rendered.text.endsWith("…"))
        assertEquals(161, rendered.text.length)
    }

    @Test
    fun `413 报的是实际发出去的体积`() {
        assertEquals(TimeFormat.fileSize(9_600_000L), argsOf(AsrException.CloudTooLarge(9_600_000L))[0])
    }

    @Test
    fun `服务商侧故障同时报状态码与原因`() {
        val args = argsOf(AsrException.CloudServer(503, "maintenance"))
        assertEquals(503, args[0])
        assertEquals(MspText.Plain("maintenance"), args[1])
    }

    @Test
    fun `没有任何时长信息时也不能是一句废话`() {
        // 这一档要能解释「为什么本机可以、云端不行」，否则用户得到一个
        // 「生成失败」而本机识别明明能跑同一个文件。
        assertEquals(R.string.msp_asr_error_unknown_duration, idOf(AsrException.UnknownDuration))
    }

    // ------------------------------------------------------------------ 兜底

    @Test
    fun `没预料到的异常走未知兜底而不是崩在界面线程`() {
        assertEquals(R.string.msp_asr_error_unknown, idOf(IllegalStateException("莫名其妙")))
        // IOException 是唯一被「按语义」收编的一类：它只可能是网络/文件读失败。
        assertEquals(R.string.msp_asr_error_network, idOf(IOException("boom")))
    }

    private fun fakeModel(): AsrModelInfo = AsrModelInfo(
        id = "fake",
        engine = AsrEngine.OFFLINE,
        repo = "owner/repo",
        name = MspText.Plain("假模型"),
        description = MspText.Plain(""),
        languageTag = "zh",
        files = listOf(
            AsrModelFile(role = AsrFileRole.MODEL, path = "m.onnx", sizeBytes = 1_000L, sha256 = "0".repeat(64)),
        ),
    )
}
