package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 解析缓存命中规则的测试。
 *
 * 这里的核心不是「能不能缓存」，而是**什么时候必须当没有缓存**：文件被重写过
 * （重新生成字幕、外部编辑）而 uri 没变时，缓存必须失效。锁不住这条，
 * 「重新扫描字幕」这个按钮的语义就被缓存吃掉了——磁盘上是新字幕，
 * 界面还是旧字幕，而且看起来一切正常。
 */
class ParsedSubtitleCacheTest {

    private fun source(
        uri: String = "content://media/external/file/VoiceJa3.asr.srt",
        sizeBytes: Long = 1_024L,
    ) = SubtitleSource(
        uri = uri,
        fileName = "VoiceJa3.asr.srt",
        format = SubtitleFormat.SRT,
        languageTag = null,
        isForced = false,
        isBilingual = false,
        sizeBytes = sizeBytes,
        matchScore = 100,
        trailingTagCount = 0,
        origin = SubtitleOrigin.GENERATED_ASR,
    )

    private fun document(cueCount: Int) = SubtitleDocument(
        track = SubtitleTrack(
            id = "t",
            origin = SubtitleOrigin.GENERATED_ASR,
            format = SubtitleFormat.SRT,
            cueCount = cueCount,
        ),
        cues = (1..cueCount).map {
            SubtitleCue(index = it, startMs = it * 1_000L, endMs = it * 1_000L + 500L, text = "第 $it 句")
        },
    )

    @Test
    fun `同 uri 同体积直接命中缓存`() {
        val cache = ParsedSubtitleCache()
        val parsed = document(6)
        cache.put(source(), parsed)

        assertSame(parsed, cache.get(source()))
    }

    @Test
    fun `同 uri 但体积变了必须重新解析`() {
        // 这条就是设备上撞到的缺陷：重新生成字幕，uri（合成 uri）不变、
        // 内容从 2 条变成 6 条，界面却一直显示 2 条，杀进程重开才对。
        val cache = ParsedSubtitleCache()
        cache.put(source(sizeBytes = 216L), document(2))

        assertNull(cache.get(source(sizeBytes = 612L)))
    }

    @Test
    fun `不同 uri 不互相命中`() {
        val cache = ParsedSubtitleCache()
        cache.put(source(uri = "content://a.srt"), document(2))

        assertNull(cache.get(source(uri = "content://b.srt")))
    }

    @Test
    fun `体积未知时不缓存`() {
        // 拿不到体积就没有校验依据。宁可不缓存（最多慢一点），
        // 也不要把「文件被换了」变成永久的错觉。
        val cache = ParsedSubtitleCache()
        cache.put(source(sizeBytes = 0L), document(2))

        assertNull(cache.get(source(sizeBytes = 0L)))
    }

    @Test
    fun `写入后用改过的体积取到的是新的那一份`() {
        // 同一个 uri 只留当前体积那一份：旧键留着的话，文件哪天被写回旧体积
        // （重新生成又变回同样的句数），那一份更早的解析结果又会被端上来。
        val cache = ParsedSubtitleCache()
        cache.put(source(sizeBytes = 216L), document(2))
        val regenerated = document(6)
        cache.put(source(sizeBytes = 612L), regenerated)

        assertNull("旧体积那份必须被顶掉", cache.get(source(sizeBytes = 216L)))
        assertSame(regenerated, cache.get(source(sizeBytes = 612L)))
    }

    @Test
    fun `超过上限时淘汰最久没用过的那条`() {
        // 上限存在的意义是「切走再切回来别重新解析」，不是当数据库用；
        // 真淘汰了也只是重新解析一次（几毫秒），不是错。
        val cache = ParsedSubtitleCache(maxEntries = 2)
        cache.put(source(uri = "content://a.srt"), document(1))
        cache.put(source(uri = "content://b.srt"), document(2))
        // 摸一下 a：让 b 成为最久没用过的。
        assertNotNull(cache.get(source(uri = "content://a.srt")))
        cache.put(source(uri = "content://c.srt"), document(3))

        assertNull(cache.get(source(uri = "content://b.srt")))
        assertNotNull(cache.get(source(uri = "content://a.srt")))
        assertNotNull(cache.get(source(uri = "content://c.srt")))
    }
}
