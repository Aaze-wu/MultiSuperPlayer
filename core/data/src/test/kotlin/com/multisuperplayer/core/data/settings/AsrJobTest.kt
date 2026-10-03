package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.asr.AsrException
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrServices
import com.multisuperplayer.core.asr.describeAsrFailure
import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置 → 这一次具体怎么做」的测试。
 *
 * ## 为什么这段判定值得单测
 *
 * 它决定三件用户看不见、事后也查不出来的事：
 * ① **往哪儿发**（选了云端但服务商 id 认不出来时，是发去默认厂商还是当自定义）；
 * ② **带不带密钥**（需要密钥却发出去的后果是 401，而且是在白传一块之后）；
 * ③ **要不要时间轴**（`supportsSegments` 来自预设，取错了要么 400、要么时间轴整块一条）。
 *
 * 三件事在真机上的表现都是「识别失败了，不知道为啥」。所以这里把每条分支都钉住。
 */
class AsrJobTest {

    private fun settingsOf(
        route: AsrRoute? = null,
        service: String? = null,
        baseUrl: String? = null,
        model: String? = null,
    ) = AsrSettings(
        storedRouteId = route?.id,
        storedCloudServiceId = service,
        storedCloudBaseUrl = baseUrl,
        storedCloudModel = model,
    )

    private fun cloudSettings(
        service: String? = null,
        baseUrl: String? = null,
        model: String? = null,
    ) = settingsOf(route = AsrRoute.CLOUD, service = service, baseUrl = baseUrl, model = model)

    /** 与 `AsrErrorsTest` 同一个口径：断言的是「哪条资源」，不是写死的字符串。 */
    private fun describeArgs(error: Throwable): List<Any?> =
        (error.describeAsrFailure() as MspText.Res).args

    private fun cloudJob(job: AsrJob): AsrJob.Cloud {
        assertTrue("应当是云端任务，实际是 $job", job is AsrJob.Cloud)
        return job as AsrJob.Cloud
    }

    // ------------------------------------------------------------ 本机

    @Test
    fun `本机时给出本机任务，密钥与云端字段一概不看`() {
        // 有密钥不等于要上传：这条路是用户没选过云端时的默认值，
        // 而「检测到有密钥就自动切云端」正是绝对不能加的那种逻辑
        val job = resolveJob(settingsOf(service = AsrServices.OPENAI.id), apiKey = "sk-secret")

        assertTrue("必须是本机任务，实际是 $job", job is AsrJob.OnDevice)
        assertEquals(AsrModelCatalog.DEFAULT_ID, (job as AsrJob.OnDevice).model.id)
    }

    @Test
    fun `本机任务带的是用户选的模型`() {
        val settings = AsrSettings(storedModelId = AsrModelCatalog.ZIPFORMER_ID)

        val job = resolveJob(settings, apiKey = null) as AsrJob.OnDevice

        assertEquals(AsrModelCatalog.ZIPFORMER_ID, job.model.id)
    }

    // ------------------------------------------------------------ 云端：参数

    @Test
    fun `云端没选过服务商时用默认那家的地址与模型`() {
        val job = cloudJob(resolveJob(cloudSettings(), apiKey = "sk-1"))
        val siliconflow = AsrServices.SILICONFLOW

        assertEquals(siliconflow.id, job.config.serviceId)
        assertEquals(siliconflow.baseUrl, job.config.baseUrl)
        assertEquals(siliconflow.model, job.config.model)
        assertEquals("sk-1", job.config.apiKey)
        // 硅基流动只收 file+model：这里取错就会多带两个字段并换回一个 400
        assertEquals(false, job.config.supportsSegments)
    }

    @Test
    fun `Groq 的地址带 openai 那一段且要时间轴`() {
        // 写死一个「差不多的」地址是这类代码最常见的错法：base 少一段就是 404，
        // 而 404 在界面上和「模型名写错了」长得一模一样
        val job = cloudJob(resolveJob(cloudSettings(service = AsrServices.GROQ.id), apiKey = "gsk_1"))

        assertEquals(AsrServices.GROQ.id, job.config.serviceId)
        assertTrue("Groq 的 base 多一段 /openai", job.config.baseUrl.contains("/openai/"))
        assertEquals("whisper-large-v3-turbo", job.config.model)
        assertTrue("Groq 支持 verbose_json + timestamp_granularities", job.config.supportsSegments)
    }

    @Test
    fun `用户填的地址与模型优先于预设`() {
        val job = cloudJob(
            resolveJob(
                cloudSettings(
                    service = AsrServices.GROQ.id,
                    baseUrl = "https://my-proxy.example.com/v1",
                    model = "whisper-large-v3",
                ),
                apiKey = "gsk_1",
            ),
        )

        assertEquals("https://my-proxy.example.com/v1", job.config.baseUrl)
        assertEquals("whisper-large-v3", job.config.model)
    }

    // ------------------------------------------------------------ 云端：密钥

    @Test
    fun `需要密钥而没填时在发请求之前就失败`() {
        // 401 只有等服务端读完整个请求体才会返回——也就是白传一块（约 9.6 MB）才知道。
        // 这里提前抛，文案和真正的 401 是同一档，用户看到的指引完全一致
        val error = assertThrows(AsrException.CloudAuth::class.java) {
            resolveJob(cloudSettings(service = AsrServices.GROQ.id), apiKey = null)
        }

        assertEquals(listOf(AsrServices.GROQ.displayName), describeArgs(error))
    }

    @Test
    fun `密钥是空白等于没填`() {
        listOf("", "   ", "\n").forEach { raw ->
            assertThrows(
                "「$raw」应当算没填密钥",
                AsrException.CloudAuth::class.java,
            ) { resolveJob(cloudSettings(), apiKey = raw) }
        }
    }

    @Test
    fun `自定义预设没有密钥也能组装`() {
        // 自建 / 中转站很可能不带鉴权（局域网部署、one-api 的免密钥渠道），
        // 强制要密钥就把它们直接堵死了
        val job = cloudJob(
            resolveJob(
                cloudSettings(
                    service = AsrServices.CUSTOM_ID,
                    baseUrl = "http://10.0.2.2:11434/v1",
                    model = "whisper-1",
                ),
                apiKey = null,
            ),
        )

        assertNull(job.config.apiKey)
        assertEquals(AsrServices.CUSTOM_ID, job.config.serviceId)
    }

    @Test
    fun `认不出来的服务商 id 当成自定义，不会静默改用别家`() {
        // 静默回落会表现为「地址还在框里，但请求发去了别家」，而失败信息指向别家。
        // 连带一个后果：supportsSegments 不再是任何一家的假设值，而是自定义的那条
        val job = cloudJob(resolveJob(cloudSettings(service = "openai-typo"), apiKey = "sk-1"))

        assertEquals(AsrServices.CUSTOM_ID, job.config.serviceId)
        assertEquals(AsrServices.CUSTOM.supportsSegments, job.config.supportsSegments)
    }
}
