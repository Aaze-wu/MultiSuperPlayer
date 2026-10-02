package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户在文件浏览器里亲手挑了一条字幕」这条路的测试。
 *
 * 它和自动查找最大的区别是**不需要猜**：文件名是现成的、用户点中的就是它。
 * 所以这里锁的是「我们有没有把这条确定性丢掉」——补错 scheme、关联分不够高、
 * 或者把它归进「名字对不上」那一类，都会让这条路变成一个需要用户再确认一次的流程。
 */
class SubtitleFilesTest {

    @Test
    fun `文件浏览器给的裸路径会被补成 file uri`() {
        // `FileSystemSource` 交出来的是裸的绝对路径（`/storage/...`）。播放那条路
        // 直接用它没事（Media3 接受无 scheme 的本地路径），但**读字幕**那条路走
        // `ContentResolver.openInputStream`，它只认 `file` / `content`——无 scheme 的
        // 会被当成 content uri 去问 ContentProvider，直接抛异常。
        // 少了这个 `file://`，症状是「点字幕跳过去、弹一句加载失败」。
        val source = subtitleSourceOf(
            path = "/storage/emulated/0/Download/Show.chs.srt",
            fileName = "Show.chs.srt",
        )

        assertEquals("file:///storage/emulated/0/Download/Show.chs.srt", source.uri)
    }

    @Test
    fun `已经是 uri 的路径原样保留`() {
        // SAF 来源（`content://`）和已经带好 scheme 的 `file://` 都不能被再包一层，
        // 否则会变成 `file://content://...` 这种谁也读不了的东西。
        val saf = "content://com.android.externalstorage.documents/tree/x/document/y"
        assertEquals(saf, subtitleSourceOf(path = saf, fileName = "Show.srt").uri)

        val file = "file:///storage/emulated/0/Show.srt"
        assertEquals(file, subtitleSourceOf(path = file, fileName = "Show.srt").uri)
    }

    @Test
    fun `亲手选中的候选是满分, 并且按门槛会自动生效`() {
        // 关联分的含义是「这个名字和那条媒体像不像」，自动查找靠它挑。用户点中的
        // 那条不需要猜，所以必须是满分——比任何自动猜出来的候选都靠前。
        val source = subtitleSourceOf("/x/Whatever.srt", "Whatever.srt")

        assertEquals(SubtitleFileNaming.SCORE_EXACT, source.matchScore)
        // 满分当然够门槛。写死一个 85 之类「刚好够」的分数也能过门槛，但会在
        // 候选列表里排在自动找到的精确匹配之后——用户指定了却不是第一条，说不通。
        assertTrue(source.isAutoMatchable)
    }

    @Test
    fun `亲手选中的候选不会被归进「名字对不上」`() {
        // `matchesMedia` 用来把候选分成「像这条片子的」和「只能手动选的」两组，
        // 界面对后者会说「名字对不上」。这句话在这里是错的：**就是他手动选的**。
        // 文件名故意取一个和媒体毫无关系的，确保这条断言不靠巧合成立。
        val source = subtitleSourceOf("/x/随便一个名字.srt", "随便一个名字.srt")

        assertTrue(source.matchesMedia)
    }

    @Test
    fun `语言与强制与双语标记照旧从文件名解析`() {
        // 手动指定的字幕也要能显示「简体中文」、也要遵守「强制字幕」的显示规则。
        // 这些信息全部从名字上解析，一个字节都不用读盘——所以这里能断言到具体值，
        // 而不是「跟自动查找一样」（那种写法在两边一起写错时照样通过）。
        val tagged = subtitleSourceOf("/x/Show.chs.forced.srt", "Show.chs.forced.srt")
        assertNotNull("后缀里的语言标记没被解析出来", tagged.languageTag)
        assertTrue("forced 标记没被解析出来", tagged.isForced)
        assertEquals(SubtitleFormat.SRT, tagged.format)

        val plain = subtitleSourceOf("/x/Show.srt", "Show.srt")
        assertFalse(plain.isForced)
    }

    @Test
    fun `字幕后缀清单与自动查找用的是同一份`() {
        // 这两份清单一旦分家，会出现「同目录里扫得到、在文件浏览器里却点不着」
        // （或者反过来）这种从界面上完全解释不了的差别。
        assertEquals(
            SubtitleFileNaming.discoverableExtensions.toSet(),
            subtitleFileExtensions,
        )
        assertTrue(subtitleFileExtensions.contains("srt"))
    }

    @Test
    fun `位图字幕不进字幕后缀清单`() {
        // `.sup` / `.sub+idx` 是图片加索引，解析器读不出文本。列进清单只会让用户
        // 选中一个**必然失败**的文件。
        val bitmap = (SubtitleFormat.PGS.extensions + SubtitleFormat.VOBSUB.extensions).toSet()

        assertTrue(
            "位图字幕被当成可选字幕了：${subtitleFileExtensions.intersect(bitmap)}",
            subtitleFileExtensions.intersect(bitmap).isEmpty(),
        )
    }

    @Test
    fun `拿不到大小时大小是零而不是负数`() {
        // 大小只用于显示，但它来自 provider 的 `SIZE` 列（可能为 -1 表示未知）。
        // 一个负的字节数会一路显示成「-1 B」。
        assertEquals(0L, subtitleSourceOf("/x/a.srt", "a.srt", sizeBytes = -1L).sizeBytes)
        assertEquals(0L, subtitleSourceOf("/x/a.srt", "a.srt").sizeBytes)
        assertEquals(2_048L, subtitleSourceOf("/x/a.srt", "a.srt", sizeBytes = 2_048L).sizeBytes)
    }

    // ------------------------------------------------ 系统文件选择器（OpenDocument）

    @Test
    fun `选择器给的名字优先，别的来源都让位`() {
        // `OpenableColumns` 是「打开这个 uri 之后它叫什么」的标准列，最可信。
        // 后面的候选不是备选的写法，而是**别的 provider 才会给**的东西：
        // 下载管理器只给 OpenableColumns，文档 provider 常常只给
        // DocumentsContract 那两组（连 size 都可能不给）。
        assertEquals(
            "Show.chs.srt",
            pickDisplayName(
                openableName = "Show.chs.srt",
                documentName = "另一个名字.srt",
                lastPathSegment = "primary:Download/又不是这个.srt",
                uriText = "content://provider/document/1",
            ),
        )
    }

    @Test
    fun `只有一组列时也能拿到名字`() {
        // 下载管理器那条路：只有 OpenableColumns。
        assertEquals(
            "a.srt",
            pickDisplayName("a.srt", null, null, "content://downloads/document/1"),
        )
        // 文档 provider 那条路：只有 DocumentsContract 的列。
        assertEquals(
            "b.srt",
            pickDisplayName(null, "b.srt", null, "content://documents/document/2"),
        )
    }

    @Test
    fun `两条列都没有时从 uri 最后一段里取出文件名`() {
        // `content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.srt`
        // 解码后的最后一段是 `primary:Download/a.srt`——那是一条**路径**，
        // 直接当标题就是「primary:Download/a.srt」这一长串。
        assertEquals(
            "a.srt",
            pickDisplayName(null, null, "primary:Download/a.srt", "content://p/document/3"),
        )
    }

    @Test
    fun `空白名字不算名字`() {
        // provider 完全可以回一个空串或者一串空格。空文件名会让界面出现一行
        // **没有标题**的字幕，比退到 uri 更糟：用户看不出自己选的是哪个文件。
        assertEquals(
            "b.srt",
            pickDisplayName("   ", "b.srt", null, "content://p/document/4"),
        )
        assertEquals(
            "b.srt",
            pickDisplayName("", "b.srt", null, "content://p/document/4"),
        )
    }

    @Test
    fun `什么都问不出来时退到 uri 本身`() {
        // 退到这里就说明这条候选**大概**挂不上（后缀都推不出来，解析会报
        // 「无法解析这个字幕文件」）。但退到 uri 仍然比让 `fileName` 变成空串好：
        // 空标题在界面上是一行看不见的东西，用户连「选了哪个」都不知道。
        assertEquals(
            "content://p/document/5",
            pickDisplayName(null, null, null, "content://p/document/5"),
        )
    }
}
