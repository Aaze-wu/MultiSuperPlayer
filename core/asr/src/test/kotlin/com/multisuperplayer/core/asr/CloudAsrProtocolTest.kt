package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 云端识别协议层的测试：地址拼接、multipart 构造、响应解析。
 *
 * 全都是纯函数，所以一个测试也不碰网络——而这正是把它们从 [CloudAsrClient]
 * 里拆出来的理由。这里每一条断言都对应一次**真实会被踩到的**事故：
 *
 * - multipart 声明的长度和实际写出的字节差一个，服务商只说「音频损坏」；
 * - `start` 是秒被当成毫秒，所有字幕挤在开头；
 * - 200 + HTML 错误页被当成文字，生成一条内容为 `<html>` 的字幕；
 * - `end == start` 的字幕在播放器里永远匹配不上，表现成「少了一句」。
 */
class CloudAsrProtocolTest {

    // ------------------------------------------------------------------ 地址

    @Test
    fun `地址没写到接口时自动补上`() {
        val expected = "https://api.openai.com/v1/audio/transcriptions"
        assertEquals(expected, transcriptionsUrl("https://api.openai.com/v1"))
        assertEquals("结尾多一个斜杠不该拼出双斜杠", expected, transcriptionsUrl("https://api.openai.com/v1/"))
        assertEquals("前后空白要吃掉", expected, transcriptionsUrl("  https://api.openai.com/v1  "))
    }

    @Test
    fun `地址已经写到接口时原样返回`() {
        // 否则会拼成 `.../audio/transcriptions/audio/transcriptions`，得到 404——
        // 而 404 的文案是「地址写错了」，用户改完反而更错。
        val full = "https://api.groq.com/openai/v1/audio/transcriptions"
        assertEquals(full, transcriptionsUrl(full))
    }

    // -------------------------------------------------------------- multipart

    @Test
    fun `声明的长度与实际写出的字节数逐字节相等`() {
        val audio = tempFileOf(1024)
        try {
            val body = buildTranscriptionBody(
                boundary = "BOUNDARY",
                model = "whisper-1",
                audio = audio,
                fileName = "audio.wav",
                mimeType = "audio/wav",
                supportsTimestamps = true,
            )
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            val bytes = out.toByteArray()

            // 这是 `setFixedLengthStreamingMode` 的前提条件。少一字节会被服务端
            // 判成「被截断的音频」，多一字节直接 ProtocolException。
            assertEquals(body.contentLength, bytes.size.toLong())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `头部里没有多余的字节混进文件正文`() {
        val audio = tempFileOf(8)
        try {
            val body = buildTranscriptionBody(
                boundary = "BOUNDARY",
                model = "whisper-1",
                audio = audio,
                fileName = "audio.wav",
                mimeType = "audio/wav",
                supportsTimestamps = false,
            )
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            val text = String(out.toByteArray(), Charsets.ISO_8859_1)

            assertTrue("第一个字符就该是分界符：$text", text.startsWith("--BOUNDARY\r\n"))
            assertTrue("结尾必须是 --BOUNDARY-- ", text.endsWith("--BOUNDARY--\r\n"))
            // 头部空行（CRLFCRLF）是「头部结束、正文开始」的分界；用 `\n` 的话
            // 正文的第一个字节会是一个多余的 `\r`。
            assertTrue(text.contains("Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1"))
            assertTrue(
                text.contains(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                        "Content-Type: audio/wav\r\n\r\n",
                ),
            )
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `不支持时间轴的服务商只收到 file 与 model`() {
        val audio = tempFileOf(8)
        try {
            val body = buildTranscriptionBody(
                boundary = "B",
                model = "FunAudioLLM/SenseVoiceSmall",
                audio = audio,
                fileName = "audio.wav",
                mimeType = "audio/wav",
                supportsTimestamps = false,
            )
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            val text = String(out.toByteArray(), Charsets.ISO_8859_1)

            // 硅基流动的请求体在官方文档里只有这两个字段；多发一个字段是 400 的机会，
            // 不是「多一点信息」。
            assertTrue(!text.contains("response_format"))
            assertTrue(!text.contains("timestamp_granularities"))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `支持时间轴时两个字段一起发`() {
        val audio = tempFileOf(8)
        try {
            val body = buildTranscriptionBody(
                boundary = "B",
                model = "whisper-1",
                audio = audio,
                fileName = "audio.wav",
                mimeType = "audio/wav",
                supportsTimestamps = true,
            )
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            val text = String(out.toByteArray(), Charsets.ISO_8859_1)

            assertTrue(text.contains("name=\"response_format\"\r\n\r\nverbose_json"))
            // 结尾的 `[]` 是 OpenAPI 里 array 型表单字段的名字，少写会得到
            // 「你给的不是数组」。
            assertTrue(text.contains("name=\"timestamp_granularities[]\"\r\n\r\nsegment"))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `随机 boundary 只用字母数字`() {
        val boundary = randomBoundary()
        assertTrue("boundary 不能出现在正文里，所以字符集要足够窄：$boundary", boundary.matches(Regex("[-A-Za-z0-9]+")))
        assertTrue("要能当 token 用，不能带引号：$boundary", !boundary.contains('"'))
    }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `verbose_json 的秒浮点换算成毫秒并加上块偏移`() {
        val segments = parseTranscriptionSegments(VERBOSE_JSON, chunkStartMs = 300_000L, chunkEndMs = 600_000L)

        assertEquals(2, segments.size)
        assertEquals(AsrSegment(startMs = 300_000L, endMs = 302_400L, text = "你好世界"), segments[0])
        assertEquals(AsrSegment(startMs = 302_400L, endMs = 304_900L, text = "再见"), segments[1])
    }

    @Test
    fun `每一段的文本都去掉前导空格`() {
        // 官方示例里是 `" The beach was…"`（句首空格是刻意保留的）。直接显示就是
        // 每行前面一个空格。
        val body = """{"segments":[{"start":0.0,"end":1.0,"text":" The beach"}]}"""
        val segments = parseTranscriptionSegments(body, 0L, 1_000L)
        assertEquals("The beach", segments.single().text)
    }

    @Test
    fun `没有 segments 时整块回退成一条字幕`() {
        // 硅基流动的响应里只有 text。这不是错误，是「这家服务商的能力就这样」。
        val body = """{"text":" 你好，世界。"}"""
        val segments = parseTranscriptionSegments(body, chunkStartMs = 0L, chunkEndMs = 300_000L)
        assertEquals(1, segments.size)
        assertEquals(AsrSegment(startMs = 0L, endMs = 300_000L, text = "你好，世界。"), segments.single())
    }

    @Test
    fun `segments 一条都读不出来时回退成整块`() {
        // 字段名对不上（比如把 start/end 写成了 from/to）时不能整块失败：
        // text 还是完整的，整块一条至少能看。
        val body = """{"text":"有点内容","segments":[{"from":0.0,"to":1.0,"text":"x"}]}"""
        val segments = parseTranscriptionSegments(body, 0L, 5_000L)
        assertEquals(AsrSegment(startMs = 0L, endMs = 5_000L, text = "有点内容"), segments.single())
    }

    @Test
    fun `一部分 segments 读不出来时保留能读的`() {
        val body = """
            {"segments":[
              {"start":0.0,"end":1.0,"text":"第一句"},
              {"id":-1,"start":null,"end":null,"text":null},
              {"start":1.0,"end":2.0,"text":"第三句"}
            ]}
        """.trimIndent()
        val segments = parseTranscriptionSegments(body, 0L, 3_000L)
        assertEquals(listOf("第一句", "第三句"), segments.map { it.text })
    }

    @Test
    fun `零长度的时间轴被撑到最短可见时长`() {
        // end == start 的字幕在播放器里永远匹配不上：不报错、不显示，就是「少了一句」。
        val body = """{"segments":[{"start":2.0,"end":2.0,"text":"短句"}]}"""
        val segments = parseTranscriptionSegments(body, 0L, 5_000L)
        assertEquals(2_000L, segments.single().startMs)
        assertEquals(2_400L, segments.single().endMs)
    }

    @Test
    fun `倒序的时间轴也被撑开`() {
        val body = """{"segments":[{"start":3.0,"end":1.0,"text":"错序"}]}"""
        val segments = parseTranscriptionSegments(body, 0L, 5_000L)
        assertTrue("结束必须晚于开始", segments.single().endMs > segments.single().startMs)
    }

    @Test
    fun `有 text 但清洗后为空时这一块直接跳过`() {
        // 这一段里没有人说话（模型只输出标点或占位符），是**正常结果**，
        // 不应该生成一条空字幕，也不应该报警。
        val body = """{"text":"<unk>"}"""
        assertEquals(emptyList<AsrSegment>(), parseTranscriptionSegments(body, 0L, 5_000L))
    }

    @Test
    fun `不是 JSON 时抛可读响应失败而不是把 body 当文字`() {
        val html = "<html><body>502 Bad Gateway</body></html>"
        val error = runCatching { parseTranscriptionSegments(html, 0L, 5_000L) }.exceptionOrNull()
        assertTrue("实际是 $error", error is AsrException.CloudResponse)
        assertTrue(
            "异常里要带上响应原文的片段，否则这条失败无法复盘",
            (error as AsrException.CloudResponse).detail.contains("502 Bad Gateway"),
        )
    }

    @Test
    fun `JSON 数组也不能当成对象`() {
        // 中转站返回数组是真实存在的（同一个网关的不同形态）。
        val error = runCatching { parseTranscriptionSegments("[1,2,3]", 0L, 5_000L) }.exceptionOrNull()
        assertTrue("实际是 $error", error is AsrException.CloudResponse)
    }

    @Test
    fun `有 JSON 但既没有 segments 也没有 text 时抛异常`() {
        // 「响应里根本没有 text」和「有 text 但内容是空的」是两回事：前者是
        // 服务商返回体不对（要报出来），后者是这一段没人说话（正常跳过）。
        val error = runCatching { parseTranscriptionSegments("""{"task":"transcribe"}""", 0L, 5_000L) }
            .exceptionOrNull()
        assertTrue("实际是 $error", error is AsrException.CloudResponse)
    }

    @Test
    fun `空响应体的异常里有可读的说明`() {
        val error = runCatching { parseTranscriptionSegments("   ", 0L, 5_000L) }.exceptionOrNull()
        assertTrue("实际是 $error", error is AsrException.CloudResponse)
        assertTrue("空响应要能看出来是空的", (error as AsrException.CloudResponse).detail.isNotEmpty())
    }

    private fun tempFileOf(bytes: Int): File {
        val file = File.createTempFile("msp-cloud-asr", ".wav")
        // 全部用非 ASCII 字节：断言里会按 ISO-8859-1 找 ASCII 子串，正文里出现
        // 巧合的 ASCII 序列会让断言变得不可靠。
        file.writeBytes(ByteArray(bytes) { 0xAA.toByte() })
        return file
    }

    private companion object {
        /** OpenAI `verbose_json` 的响应形状（字段取自官方示例）。 */
        val VERBOSE_JSON = """
            {"task":"transcribe","language":"chinese","duration":4.9,
             "text":" 你好世界 再见",
             "segments":[
               {"id":0,"seek":0,"start":0.0,"end":2.4,"text":" 你好世界","tokens":[1,2],"temperature":0.0},
               {"id":1,"seek":0,"start":2.4,"end":4.9,"text":" 再见 ","tokens":[3],"temperature":0.0}
             ],
             "usage":{"type":"duration","seconds":5}}
        """.trimIndent()
    }
}
