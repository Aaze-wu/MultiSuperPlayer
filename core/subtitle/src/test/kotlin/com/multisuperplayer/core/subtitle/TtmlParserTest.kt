package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TtmlParserTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
    ttp:frameRate="30" ttp:tickRate="1000" xml:lang="zh">
  <body>
    <div>
      <p begin="00:00:01.000" end="00:00:03.000">第一句</p>
      <p begin="00:00:04:15" end="00:00:06:15">帧时钟</p>
      <p begin="7.5s" end="9s">偏移时钟<br/>换行</p>
      <p begin="10000t" end="12000t">tick 时钟</p>
    </div>
  </body>
</tt>"""

    private fun parse(content: String = sample) = TtmlParser().parse(content)

    @Test
    fun `解析时钟写法`() {
        val cue = parse().cues[0]
        assertEquals(1_000L, cue.startMs)
        assertEquals(3_000L, cue.endMs)
        assertEquals("第一句", cue.text)
    }

    @Test
    fun `帧时钟按声明的帧率换算而不是默认 25fps`() {
        // 00:00:04:15 在 30fps 下是 4.5 秒；如果误按 25fps 会得到 4.6 秒，
        // 如果被当成 "hh:mm:ss:ms" 会得到 4.15 秒——两种都「看起来合理」。
        val cue = parse().cues[1]
        assertEquals(4_500L, cue.startMs)
        assertEquals(6_500L, cue.endMs)
    }

    @Test
    fun `秒与毫秒后缀写法`() {
        val cue = parse().cues[2]
        assertEquals(7_500L, cue.startMs)
        assertEquals(9_000L, cue.endMs)
    }

    @Test
    fun `tick 按声明的时基换算`() {
        val cue = parse().cues[3]
        assertEquals(10_000L, cue.startMs)
        assertEquals(12_000L, cue.endMs)
    }

    @Test
    fun `br 元素变成换行`() {
        assertEquals("偏移时钟\n换行", parse().cues[2].text)
    }

    @Test
    fun `记录帧率与时基到元数据`() {
        val metadata = parse().metadata
        assertEquals("zh", metadata["lang"])
        // 数值格式（30 / 30.0）不是重点，重点是「声明的值被记住了」，
        // 所以这里只断言前缀，避免把测试绑死在 toString 的实现细节上。
        assertTrue(metadata["frameRate"]!!.startsWith("30"), "实际：${metadata["frameRate"]}")
        assertTrue(metadata["tickRate"]!!.startsWith("1000"), "实际：${metadata["tickRate"]}")
    }

    @Test
    fun `dur 属性被当成结束时间`() {
        val result = parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="2s" dur="1.5s">只有 dur</p>
              </div></body>
            </tt>
            """.trimIndent(),
        )
        val cue = result.cues.single()
        assertEquals(2_000L, cue.startMs)
        assertEquals(3_500L, cue.endMs)
    }

    @Test
    fun `span 上的 begin 与 end 生成逐字时间`() {
        val result = parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:01.500">你</span><span begin="00:00:01.500" end="00:00:03.000">好</span></p>
              </div></body>
            </tt>
            """.trimIndent(),
        )
        val cue = result.cues.single()
        assertEquals("你好", cue.text)
        assertEquals(2, cue.karaoke.size)
        assertEquals(1_000L, cue.karaoke[0].startMs)
        assertEquals(500L, cue.karaoke[0].durationMs)
        assertEquals(1_500L, cue.karaoke[1].startMs)
    }

    @Test
    fun `region 的 origin 转成归一化位置`() {
        val result = parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <head><layout>
                <region xml:id="top" tts:origin="10% 5%" xmlns:tts="http://www.w3.org/ns/ttml#styling"/>
              </layout></head>
              <body><div>
                <p begin="1s" end="2s" region="top">上方</p>
              </div></body>
            </tt>
            """.trimIndent(),
        )
        val position = assertNotNull(result.cues.single().position)
        assertEquals(0.1f, position.anchorX)
        assertEquals(0.05f, position.anchorY)
    }

    @Test
    fun `非 XML 内容抛出解析异常`() {
        val error = runCatching { parse("这不是 XML") }.exceptionOrNull()
        assertTrue(error is SubtitleParseException, "实际异常：$error")
    }

    @Test
    fun `格式判定为 TTML`() {
        assertEquals(SubtitleFormat.TTML, parse().format)
    }
}
