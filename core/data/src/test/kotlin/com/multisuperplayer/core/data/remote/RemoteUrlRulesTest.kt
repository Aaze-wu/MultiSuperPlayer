package com.multisuperplayer.core.data.remote

import com.multisuperplayer.core.model.ExternalMediaIds
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手输网络地址的清洗规则。
 *
 * 这里每一条断言都对应一个「真机上会发生、但看不出起因」的输入：中文输入法把
 * `:` 打成 `：`、从资源管理器复制的带反斜杠路径、只写了 `www.x.com/a.mp4`
 * 而没写协议、把片名打进地址框。这些输入最终都以同一种面目出现——**播放失败**，
 * 而失败信息（网络错误 / 无法打开）指不到真正的起因。所以规则必须在这里锁死。
 */
class RemoteUrlRulesTest {

    @Test
    fun `省略协议时补上 http`() {
        assertEquals("http://www.x.com/a.mp4", RemoteUrlRules.normalize("www.x.com/a.mp4"))
        assertEquals("http://192.168.1.5:8080/a.mp4", RemoteUrlRules.normalize("192.168.1.5:8080/a.mp4"))
    }

    @Test
    fun `全角标点会被换成 ASCII`() {
        // 实测症状：中文输入法下敲 http://192.168.1.5:11434/v1 会得到
        // http：／／192.168.1.5：11434／v1。看起来「地址填好了」，其实是必然失败的字符串。
        assertEquals(
            "http://192.168.1.5:11434/v1",
            RemoteUrlRules.normalize("http：／／192.168.1.5：11434／v1"),
        )
        // `。` 顶替 `.` 也是同一个输入法的行为。
        assertEquals("http://a.b.com/m.mp4", RemoteUrlRules.normalize("http://a。b.com/m.mp4"))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("http://a.com/b.mp4", RemoteUrlRules.normalize("  http://a.com/b.mp4\n"))
    }

    @Test
    fun `地址中间不能有空白或反斜杠`() {
        // 空白 = 用户粘了两段东西进来；反斜杠 = 从 Windows 资源管理器复制的路径。
        // 拼成 URL 只会得到一个必然失败的请求，早点说「这不是地址」更有用。
        assertNull(RemoteUrlRules.normalize("http://a.com/My Movie.mp4"))
        assertNull(RemoteUrlRules.normalize("C:\\Movies\\movie.mp4"))
    }

    @Test
    fun `不支持的协议被拒绝`() {
        // 内核认 rtsp/rtmp，但缓冲、seek、断点续播全和 http 不一样，没验证过的路径
        // 不该出现在地址栏里——「能填进去但放不出来」的入口比没有入口更糟。
        assertNull(RemoteUrlRules.normalize("ftp://a.com/b.mp4"))
        assertNull(RemoteUrlRules.normalize("rtsp://a.com/b.mp4"))
        assertNull(RemoteUrlRules.normalize("magnet:?xt=urn:btih:x"))
        assertNull(RemoteUrlRules.normalize("file:///sdcard/a.mp4"))
    }

    @Test
    fun `协议大小写无关，返回的总是小写`() {
        assertEquals("http://a.com/b.mp4", RemoteUrlRules.normalize("HTTP://a.com/b.mp4"))
        assertEquals("https://a.com/b.mp4", RemoteUrlRules.normalize("HtTpS://a.com/b.mp4"))
    }

    @Test
    fun `主机名不合法时拒绝`() {
        assertNull(RemoteUrlRules.normalize("http:///b.mp4"))        // 主机名是空
        assertNull(RemoteUrlRules.normalize("http://.com/b.mp4"))    // 以点开头
        assertNull(RemoteUrlRules.normalize("http://a.com./b.mp4"))  // 以点结尾
        assertNull(RemoteUrlRules.normalize("://a.com/b.mp4"))       // 只有分隔符
    }

    @Test
    fun `省略协议时主机名必须像域名`() {
        // 把片名打进地址框（「让子弹飞」）必须立刻失败，而不是等几十秒的网络错误。
        assertNull(RemoteUrlRules.normalize("让子弹飞"))
        assertNull(RemoteUrlRules.normalize("nas/movies/a.mp4"))
        // 带后缀的片名是**最常见**的那一种：从文件管理器复制过来就是 `TestClip.mp4`，
        // 它含有点 ⇒ 只判「有没有点」的话它会被补成 `http://TestClip.mp4`。
        // 实测在真机上这就是一个「卡十几秒然后说网络错误」的假阳。
        assertNull(RemoteUrlRules.normalize("TestClip.mp4"))
        assertNull(RemoteUrlRules.normalize("movie.mkv"))
        assertNull(RemoteUrlRules.normalize("song.flac"))
        assertNull(RemoteUrlRules.normalize("lyrics.lrc")) // 明确不是媒体的后缀同样不是主机名
        // 显式写了协议的人意图明确，照收：局域网里的 http://nas 是合法地址。
        assertEquals("http://nas/movies/a.mp4", RemoteUrlRules.normalize("http://nas/movies/a.mp4"))
        // 反过来：显式协议下就算主机名看起来像文件名也照收——判断对错是用户的事，
        // 这一层只负责「能不能拼成一个地址」。
        assertEquals("http://TestClip.mp4/a.mp4", RemoteUrlRules.normalize("http://TestClip.mp4/a.mp4"))
        // 主机名本身没后缀的写法不能被误伤（`nas.local`、局域网 IP）。
        assertEquals("http://nas.local/a.mp4", RemoteUrlRules.normalize("nas.local/a.mp4"))
        assertEquals("http://192.168.1.5:8080/a.mp4", RemoteUrlRules.normalize("192.168.1.5:8080/a.mp4"))
    }

    @Test
    fun `规范化是幂等的`() {
        // 历史里存的是规范化之后的地址，用户从历史里再点一次会再走一遍 normalize，
        // 两次结果必须一样，否则同一条地址会在历史上堆成两条（id 不同）。
        val urls = listOf("  HTTP：／／A.com／b.mp4 ", "www.x.com/a.mp4", "http://192.168.1.5:11434/v1")
        for (raw in urls) {
            val once = requireNotNull(RemoteUrlRules.normalize(raw))
            assertEquals(once, RemoteUrlRules.normalize(once))
        }
    }

    @Test
    fun `文件名按百分号解码，加号是字面量`() {
        assertEquals(
            "My Movie.mp4",
            RemoteUrlRules.fileNameOf("https://host/a%20b/My%20Movie.mp4?token=1"),
        )
        // `+` 在 form 编码里是空格，但它在地址里是字面量：`a+b.mp4` 不能被解成 `a b.mp4`。
        assertEquals("a+b.mp4", RemoteUrlRules.fileNameOf("https://host/a+b.mp4"))
        assertEquals("电影.mp4", RemoteUrlRules.fileNameOf("https://host/%E7%94%B5%E5%BD%B1.mp4"))
        // `%` 后面不是合法十六进制时原样返回，不抛异常也不清空。
        assertEquals("100%.mp4", RemoteUrlRules.fileNameOf("https://host/100%.mp4"))
    }

    @Test
    fun `文件名会去掉查询串和锚点`() {
        assertEquals("a.mp4", RemoteUrlRules.fileNameOf("https://host/a.mp4#t=30"))
        assertEquals("a.mp4", RemoteUrlRules.fileNameOf("https://host/a.mp4?x=1&y=2"))
        // 地址指向站点根（末尾就是 `/`）时没有路径段，退回空串，调用方会改用整条地址当标题。
        assertEquals("", RemoteUrlRules.fileNameOf("https://host/"))
        // 连 `/` 都没有时最后一段是主机名本身：这不是错误，只是它同时是个能用的标题，
        // 而类型判定会得到「没有后缀」⇒ 未知类型（不会把它误判成某种媒体）。
        assertEquals("host", RemoteUrlRules.fileNameOf("https://host"))
        assertEquals(MediaKind.UNKNOWN, RemoteUrlRules.kindOf("https://host"))
    }

    @Test
    fun `标题去掉最后一个后缀，但整名就是后缀时不截`() {
        assertEquals("My Movie", RemoteUrlRules.displayTitleOf("My Movie.mp4"))
        assertEquals("a.b", RemoteUrlRules.displayTitleOf("a.b.mkv"))
        // `.nomedia` 这种整个名字就是一个后缀：去完会变成空串，标题就成一行空白。
        assertEquals(".nomedia", RemoteUrlRules.displayTitleOf(".nomedia"))
        assertEquals("没有后缀", RemoteUrlRules.displayTitleOf("没有后缀"))
    }

    @Test
    fun `没有路径段时用整条地址当标题`() {
        val url = "https://host/"
        assertEquals(url, RemoteUrlRules.titleOf(url))
        assertEquals("My Movie", RemoteUrlRules.titleOf("https://host/dir/My%20Movie.mp4"))
    }

    @Test
    fun `类型判定与本地文件共用同一张后缀表`() {
        // 同一个文件「下载下来能播、直接播地址却进了未知类型」是不能接受的。
        assertEquals(MediaKind.VIDEO, RemoteUrlRules.kindOf("https://host/a.mp4"))
        assertEquals(MediaKind.VIDEO, RemoteUrlRules.kindOf("https://host/a.mkv"))
        assertEquals(MediaKind.VIDEO, RemoteUrlRules.kindOf("https://host/a.ts"))
        assertEquals(MediaKind.AUDIO, RemoteUrlRules.kindOf("https://host/a.flac"))
        assertEquals(MediaKind.AUDIO, RemoteUrlRules.kindOf("https://host/a.mka"))
    }

    @Test
    fun `地址后面不是媒体时仍然收下，只是类型未知`() {
        // 用户是主动把这条地址填进来的，「地址后面不是媒体」交给内核去报错，
        // 比我们把它变成一个安静的空结果好。
        assertEquals(MediaKind.UNKNOWN, RemoteUrlRules.kindOf("https://host/a.jpg"))
        assertEquals(MediaKind.UNKNOWN, RemoteUrlRules.kindOf("https://host/a.txt"))
        assertEquals(MediaKind.UNKNOWN, RemoteUrlRules.kindOf("https://host/stream"))
    }

    @Test
    fun `地址铸成的条目带 url 前缀，来源是远端`() {
        val url = "https://host/dir/My%20Movie.mp4"
        val entry = RemoteUrlRules.toEntry(url)

        // 前缀是回放时反推来源的唯一依据（`PlaylistItem.sourceOf`），
        // 同时借它把「同目录字幕」这类本地查找挡掉——远端没有同目录。
        assertEquals(ExternalMediaIds.remoteIdOf(url), entry.id)
        assertEquals(url, entry.uri)
        assertEquals(MediaSource.REMOTE, entry.source)
        assertEquals("My Movie", entry.title)
        assertEquals("My Movie.mp4", entry.displayName)
        assertEquals(MediaKind.VIDEO, entry.kind)
    }

    @Test
    fun `历史按时间倒序，时间戳坏掉的排最后`() {
        val arranged = RemoteUrlRules.arrangeHistory(
            listOf("old" to 1L, "new" to 3L, "broken" to 0L, "mid" to 2L),
        )
        // 时间戳是我们的簿记、不是用户的数据：读不出来时把那条排到最后，
        // 而不是把它丢掉——地址是用户一条条输进来的。
        assertEquals(listOf("new", "mid", "old", "broken"), arranged)
    }

    @Test
    fun `历史并列时按地址排序，方便截断`() {
        // 同一毫秒的并列必须有个确定的次序，否则 DataStore 的键顺序会漏到界面上。
        val arranged = RemoteUrlRules.arrangeHistory(
            listOf("b" to 5L, "a" to 5L, "c" to 5L),
            limit = 2,
        )
        assertEquals(listOf("a", "b"), arranged)
        assertEquals(RemoteUrlRules.HISTORY_LIMIT, RemoteUrlRules.arrangeHistory(List(50) { "u$it" to it.toLong() }).size)
    }
}
