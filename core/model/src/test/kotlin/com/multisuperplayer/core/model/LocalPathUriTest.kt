package com.multisuperplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「路径 ⇄ `file://` uri」这条规则的测试。
 *
 * 这里锁的不是「拼出来的字符串长什么样」，而是两件真正要紧的事：
 *
 * 1. **`#` 与 `%` 不能出现在 uri 的语法位置上**（前者变成 fragment、后者变成转义开头），
 *    否则磁盘上的文件明明存在，读的时候却报 `FileNotFoundException`；
 * 2. **编码必须可逆**——写进去什么样，解出来就得是原路径，一个字符都不能差。
 *
 * 用[localFilePath]反解而不是拿字符串拼期望值：后者等于把实现抄一遍，
 * 编码写了却没生效、或者解错一个字节，都照样通过。
 */
class LocalPathUriTest {

    @Test
    fun `普通路径补上 file scheme`() {
        assertEquals(
            "file:///storage/emulated/0/Movies/a.mp4",
            localFileUri("/storage/emulated/0/Movies/a.mp4"),
        )
    }

    @Test
    fun `中文与空格能被原样还原`() {
        val path = "/storage/emulated/0/电影/我的 片子.mp4"

        val uri = localFileUri(path)

        assertTrue("必须带 file scheme：$uri", uri.startsWith("file://"))
        assertEquals(path, localFilePath(uri))
    }

    @Test
    fun `井号不会变成 fragment`() {
        val path = "/storage/emulated/0/Movies/歌曲#1.mp4"

        val uri = localFileUri(path)

        // 裸写 `#` 之后的内容在 `Uri` 眼里不再属于路径 —— 这是「无法播放」那种症状的成因。
        assertFalse("路径段里不该留下裸的 `#`：$uri", uri.substring("file://".length).contains('#'))
        assertEquals(path, localFilePath(uri))
    }

    @Test
    fun `百分号不会变成转义开头`() {
        val path = "/storage/emulated/0/Movies/100% 完成.mp4"

        val uri = localFileUri(path)

        assertEquals(path, localFilePath(uri))
        // 再解一次不能变形：`%` 只有被编码成 `%25`，第二遍才不会把它当转义的开头。
        assertEquals(path, localFilePath(localFileUri(requireNotNull(localFilePath(uri)))))
    }

    @Test
    fun `问号不会变成 query`() {
        val path = "/storage/emulated/0/Movies/a?b.mp4"

        assertEquals(path, localFilePath(localFileUri(path)))
    }

    @Test
    fun `斜杠与冒号不被编码`() {
        // `/` 是目录分隔符，编码它会把一整条路径压缩成一个文件名的字符；
        // `:` 在 uri 的路径段里本来就合法。
        assertEquals("file:///a:b/c.mp4", localFileUri("/a:b/c.mp4"))
    }

    @Test
    fun `已经是 uri 的输入原样返回`() {
        // 文件浏览器给的是裸路径，手动选字幕给的是 SAF uri —— 两条路都直接调这里，
        // 再包一层会得到 `file://content://...` 这种谁也读不了的东西。
        val saf = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fa.mp4"
        assertEquals(saf, localFileUri(saf))

        val file = "file:///storage/emulated/0/Show.srt"
        assertEquals(file, localFileUri(file))
    }

    @Test
    fun `别的 scheme 反解不成路径`() {
        // 猜出来的路径会去操作**别的文件**（比如把 `content://media/external/video/media/42`
        // 当成 `/42`），所以这里必须老实返回 null。
        assertNull(localFilePath("content://media/external/video/media/42"))
        assertNull(localFilePath("http://example.com/a.mp4"))
        assertNull(localFilePath(""))
    }

    @Test
    fun `裸路径原样返回`() {
        assertEquals("/storage/emulated/0/a.mp4", localFilePath("/storage/emulated/0/a.mp4"))
    }

    @Test
    fun `认不出的转义原样保留`() {
        // 手工拼过、只有一半编码的 uri 不该整条失败：最坏的结果是找不到文件，
        // 而那正是它本来就有的结果。
        assertEquals("/a/100% s.mp4", localFilePath("file:///a/100% s.mp4"))
    }
}
