package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 扫描纯规则的单元测试。
 *
 * 这里的每一条都在防一个**界面上看不出来**的失败：
 * 后缀表漏一个 ⇒ 那种文件永远不出现；相对路径算错 ⇒ 字幕自动关联失效；
 * 去重键算错 ⇒ 同一个文件出现两次；父目录算错 ⇒ 找字幕时列到别的目录去了。
 */
class SafScanRulesTest {

    // --------------------------------------------------------------- 类型判定

    @Test
    fun `常见音频视频后缀都能判出大类`() {
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("song.mp3", null))
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("song.flac", null))
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("song.ape", null))
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("movie.mkv", null))
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("movie.mp4", null))
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("movie.rmvb", null))
        // 大写后缀也要认。SAF 上的文件名大小写完全随意。
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("MOVIE.MKV", null))
        // 视频容器里装纯音频的那些格式（.mka / .ts）在两张表里都出现，
        // 归到哪一类都不影响播放，这里只是确认它们在表里。
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("album.mka", null))
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("stream.ts", null))
    }

    @Test
    fun `不认识的后缀按 UNKNOWN 收下而不是丢掉`() {
        // 这是「白名单 vs 黑名单」的设计本身：冷门格式必须能进库，
        // 否则装了 FFmpeg 也播不了——文件根本不会出现在列表里。
        assertEquals(MediaKind.UNKNOWN, SafScanRules.kindOf("weird.xyz", null))
        assertEquals(MediaKind.UNKNOWN, SafScanRules.kindOf("no_extension", null))
    }

    @Test
    fun `字幕图片文档等明确不是媒体的文件被挡掉`() {
        assertNull(SafScanRules.kindOf("movie.srt", null))
        assertNull(SafScanRules.kindOf("movie.ass", null))
        assertNull(SafScanRules.kindOf("cover.jpg", null))
        assertNull(SafScanRules.kindOf("notes.txt", null))
        assertNull(SafScanRules.kindOf("archive.zip", null))
        assertNull(SafScanRules.kindOf("app.apk", null))
        assertNull(SafScanRules.kindOf(".nomedia", null))
    }

    @Test
    fun `后缀说不是媒体时优先于 MIME`() {
        // 真实案例：provider 把 .lrc 报成 audio/x-lyric。信 MIME 的话歌词会进媒体库。
        assertNull(SafScanRules.kindOf("song.lrc", "audio/x-lyric"))
    }

    @Test
    fun `办公文档和电子书不会被当成音频`() {
        // 回归：用户报「docx 被识别成音频」。当时这些后缀一条都不在后缀表里，
        // 于是 `kindOf` 走到第 4 步返回 UNKNOWN ⇒ `BrowserEntry.playable = true`
        // ⇒ 点一下交给播放器，而播放页只有音频 / 视频两种版面（唯一判据是
        // `entry.kind == MediaKind.VIDEO`）⇒ 一份 Word 文档打开成一个播不出
        // 任何东西的音频页（封面 + 歌词），崩溃日志里一句痕迹都没有。
        val documents = listOf(
            "报告.docx", "报告.doc",
            "表格.xlsx", "表格.xls", "幻灯片.pptx", "幻灯片.ppt",
            "开放文档.odt", "开放文档.ods", "开放文档.odp", "老文档.rtf",
            "电子书.epub", "电子书.mobi", "电子书.azw3", "电子书.fb2", "漫画.cbz",
            // 同一类里的常见变体：WPS（国内几乎人手一个）、国标 OFD、iWork、
            // Visio / Publisher / XPS / CHM。它们的「被当成音频」后果一模一样。
            "WPS文字.wps", "WPS演示.dps", "国标文档.ofd",
            "页面.pages", "数字.numbers", "幻灯片.key",
            "流程图.vsdx", "出版物.pub", "定版文档.xps", "帮助文档.chm",
        )
        documents.forEach { assertNull("`$it` 不该被当成媒体", SafScanRules.kindOf(it, null)) }
    }

    @Test
    fun `办公文档的后缀优先于说谎的 MIME`() {
        // 两种真实的「MIME 说谎」来源：DocumentsProvider 自己报错（给一个 .docx
        // 报 `audio/*`），以及分享进来的 uri 由系统去猜 MIME。
        // 「后缀黑名单放在 MIME 之前」这个顺序本来就是为了兜住它们，但它一直没被
        // 测过——而这恰好是「补后缀」这条修法能生效的前提：光加黑名单、不保证
        // 优先级，一个乱报 MIME 的 provider 仍然能把 docx 变成音频。
        assertNull(SafScanRules.kindOf("报告.docx", "audio/mpeg"))
        assertNull(SafScanRules.kindOf("报告.docx", "video/mp4"))
        // Office 文档真实的 MIME 不以 audio/video 开头，走的是第 4 步；
        // 这里断言的是「不许变成 UNKNOWN」。
        assertNull(
            SafScanRules.kindOf(
                "报告.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertNull(SafScanRules.kindOf("表格.xlsx", "application/vnd.ms-excel"))
    }

    @Test
    fun `三张后缀表两两不相交且都是小写无点的裸后缀`() {
        // 结构守卫。往这三张表里加错一个后缀，后果**全是静默的**：
        // - 真的媒体后缀被写进黑名单 ⇒ 那种格式永远不出现在浏览页；
        // - 写成 `.mp3` / `MP3` ⇒ `extensionOf` 给的是小写无点的裸后缀，
        //   这两种写法一条都匹配不上，等于没写；
        // - 同一条既在黑名单又在媒体表里 ⇒ 结果取决于判断顺序，
        //   而顺序是不会有人记得的东西。
        val tables = mapOf(
            "NON_MEDIA" to SafScanRules.NON_MEDIA_EXTENSIONS,
            "AUDIO" to SafScanRules.AUDIO_EXTENSIONS,
            "VIDEO" to SafScanRules.VIDEO_EXTENSIONS,
        )
        tables.forEach { (name, extensions) ->
            extensions.forEach { extension ->
                assertEquals(
                    "$name 里的 `$extension` 应该是小写裸后缀",
                    extension.lowercase().removePrefix("."),
                    extension,
                )
                assertTrue("$name 里不该有空串", extension.isNotEmpty())
            }
        }
        val overlap = tables
            .flatMap { (name, extensions) -> extensions.map { extension -> extension to name } }
            .groupBy { it.first }
            .filterValues { it.size > 1 }
            .mapValues { (_, owners) -> owners.map { it.second } }
        assertTrue("同一个后缀出现在多张表里：$overlap", overlap.isEmpty())
    }

    @Test
    fun `后缀不认识时信 MIME`() {
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("track.xyz", "audio/flac"))
        assertEquals(MediaKind.VIDEO, SafScanRules.kindOf("clip.xyz", "video/mp2t"))
        // MIME 带参数时要能剥掉分号后面的部分。
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf("odd", "audio/mpeg; charset=utf-8"))
        // MIME 也认不出来时按 UNKNOWN。
        assertEquals(MediaKind.UNKNOWN, SafScanRules.kindOf("odd", "application/octet-stream"))
    }

    @Test
    fun `没有文件名时什么都不崩`() {
        assertEquals(MediaKind.UNKNOWN, SafScanRules.kindOf(null, null))
        assertEquals(MediaKind.UNKNOWN, SafScanRules.kindOf(null, "application/octet-stream"))
        // 没有名字就没有后缀，黑名单**无从下手**，只能信 MIME。
        // 换句话说：黑名单能拦住歌词文件，是因为它有文件名——这一点要写下来，
        // 否则很容易以为「黑名单已经很全了，不用再看 MIME」。
        assertEquals(MediaKind.AUDIO, SafScanRules.kindOf(null, "audio/x-lrc"))
    }

    // --------------------------------------------------------------- docId 还原

    @Test
    fun `相对路径去掉卷名并保留结尾斜杠`() {
        // 保留结尾斜杠是为了能直接和 MediaStore 的 RELATIVE_PATH 比。
        assertEquals("Music/", SafScanRules.relativePathOf("primary:Music/a.mp3"))
        assertEquals("Music/Album/", SafScanRules.relativePathOf("primary:Music/Album/a.mp3"))
        // 根目录下的文件：空串（和 MediaStore 的表示一致），不是 null。
        assertEquals("", SafScanRules.relativePathOf("primary:a.mp3"))
        // SD 卡卷名不是 primary。
        assertEquals("Podcasts/", SafScanRules.relativePathOf("1234-5678:Podcasts/x.mp3"))
        // 不带冒号 = 不是 ExternalStorageProvider，路径无意义。
        assertNull(SafScanRules.relativePathOf("some-opaque-id"))
    }

    @Test
    fun `卷名与文件名解析`() {
        assertEquals("primary", SafScanRules.volumeOf("primary:Music/a.mp3"))
        assertEquals("", SafScanRules.volumeOf("no-colon-id"))
        assertEquals("a.mp3", SafScanRules.fileNameOf("primary:Music/a.mp3"))
        assertEquals("a.mp3", SafScanRules.fileNameOf("primary:a.mp3"))
        assertEquals("no-colon-id", SafScanRules.fileNameOf("no-colon-id"))
    }

    @Test
    fun `后缀小写化且不含点`() {
        assertEquals("mp3", SafScanRules.extensionOf("a.MP3"))
        assertEquals("mkv", SafScanRules.extensionOf("My.Movie.mkv"))
        assertEquals("", SafScanRules.extensionOf("noext"))
        assertEquals("", SafScanRules.extensionOf(null))
        // 隐藏文件的「后缀」就是它自己：`.nomedia` 的结果是 nomedia，
        // 而它在黑名单里，所以会被挡掉。
        assertEquals("nomedia", SafScanRules.extensionOf(".nomedia"))
    }

    @Test
    fun `父目录 documentId`() {
        // 找同目录外挂字幕要用它拼 children uri。
        assertEquals("primary:Music", SafScanRules.parentDocumentIdOf("primary:Music/a.mp3"))
        assertEquals("primary:Music/Album", SafScanRules.parentDocumentIdOf("primary:Music/Album/a.mp3"))
        // 直接在卷根的文件，它的目录就是卷根。少了这个冒号会拼出一个无效的 children uri。
        assertEquals("primary:", SafScanRules.parentDocumentIdOf("primary:a.mp3"))
        assertEquals("1234-5678:", SafScanRules.parentDocumentIdOf("1234-5678:a.mp3"))
        assertEquals("1234-5678:Download", SafScanRules.parentDocumentIdOf("1234-5678:Download/a.mp3"))
        // 非 ExternalStorageProvider：算不出目录，调用方必须按「看不见」处理。
        assertNull(SafScanRules.parentDocumentIdOf("no-colon-id"))
    }

    @Test
    fun `SAF id 带 saf 前缀避免和 MediaStore 撞车`() {
        assertEquals("saf:primary:Music/a.mp3", SafScanRules.safIdOf("primary:Music/a.mp3"))
        assertTrue(SafScanRules.safIdOf("primary:Music/a.mp3").startsWith(SafScanRules.SAF_ID_PREFIX))
    }

    // --------------------------------------------------------------- 去重

    @Test
    fun `同一个文件的身份键只和大小写无关`() {
        val a = SafScanRules.identityKey("Music/", "Song.mp3", 1024L)
        val b = SafScanRules.identityKey("music/", "song.MP3", 1024L)
        assertEquals(a, b)
    }

    @Test
    fun `路径或文件名不同则身份不同`() {
        val a = SafScanRules.identityKey("Music/", "song.mp3", 1024L)
        val b = SafScanRules.identityKey("Music/Album/", "song.mp3", 1024L)
        val c = SafScanRules.identityKey("Music/", "song2.mp3", 1024L)
        val d = SafScanRules.identityKey("Music/", "song.mp3", 2048L)
        assertEquals(4, setOfNotNull(a, b, c, d).size)
    }

    @Test
    fun `信息不足时不参与去重`() {
        // 大小未知（provider 不给）：用 0 当「一样大」会把同目录所有文件判成同一个。
        assertNull(SafScanRules.identityKey("Music/", "song.mp3", 0L))
        assertNull(SafScanRules.identityKey("Music/", "song.mp3", -1L))
        // 路径未知（非 ExternalStorageProvider）。
        assertNull(SafScanRules.identityKey(null, "song.mp3", 1024L))
        // 名字是空的。
        assertNull(SafScanRules.identityKey("Music/", "  ", 1024L))
        assertNull(SafScanRules.identityKey("Music/", null, 1024L))
    }

    @Test
    fun `根目录下的文件路径是空串而不是 null`() {
        // 空串是**有效**路径（和 MediaStore 一致），必须能算出身份键；
        // 如果这里返回 null，根目录下的文件就永远无法和 MediaStore 条目合并。
        val key = SafScanRules.identityKey("", "a.mp3", 10L)
        assertEquals("|a.mp3|10", key)
    }
}
