package com.multisuperplayer.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mediaUriKindOf] 的测试。
 *
 * 这一条判据是 v1.2.4 那个 bug 的**唯一分岔点**：判成 [MediaUriKind.FILE_SYSTEM] 的
 * `content://…` 会被交给 `File(path).exists()`，而它对任何 content uri 都返回 false。
 * 界面上的症状是「在系统文件选择器添加的文件夹里挑的文件，一进播放列表就说文件已不在」，
 * 不抛异常、不崩、日志里也没有任何东西——所以只能靠这里的用例钉住。
 *
 * 真正的 `stat` / 跨进程 `query` 需要 Android，在 JVM 上跑不了（`unitTests.isReturnDefaultValues`
 * 会把它们变成返回 0/null 的空壳），因此被测的是**分流前的纯字符串规则**；
 * 判定之后的动作交给 `MediaUriProbe`，它只能在真机上验证。
 */
class MediaUriKindTest {

    @Test
    fun `SAF 的 document uri 必须算 content 而不是路径`() {
        // 这就是 bug 本体：`DocumentsContract.buildDocumentUriUsingTree` 拼出来的形态。
        val safUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic" +
            "/document/primary%3AMusic%2Fa.mp3"

        assertEquals(MediaUriKind.CONTENT, mediaUriKindOf(safUri))
    }

    @Test
    fun `媒体库的 content uri 也算 content`() {
        assertEquals(MediaUriKind.CONTENT, mediaUriKindOf("content://media/external/audio/media/42"))
    }

    @Test
    fun `别的应用递进来的 content uri 仍然算 content`() {
        // 判据只看 scheme，不看 authority：SAF、MediaStore、第三方 provider 走同一条路
        // （先 query 再退化成打开），不该在分流这一层再分叉。
        assertEquals(MediaUriKind.CONTENT, mediaUriKindOf("content://com.other.app/file/9"))
    }

    @Test
    fun `裸绝对路径算文件系统`() {
        assertEquals(MediaUriKind.FILE_SYSTEM, mediaUriKindOf("/storage/emulated/0/Movies/a.mp4"))
    }

    @Test
    fun `file uri 算文件系统`() {
        // 浏览页和手动选字幕都会走 `localFileUri`，于是路径可能带着 `file://` 前缀，
        // 还可能带 `%XX` 转义（空格、中文名）。两者都是本地文件。
        assertEquals(MediaUriKind.FILE_SYSTEM, mediaUriKindOf("file:///storage/emulated/0/a.mp4"))
        assertEquals(MediaUriKind.FILE_SYSTEM, mediaUriKindOf("file:///storage/emulated/0/My%20Movie.mp4"))
    }

    @Test
    fun `相对路径也算文件系统`() {
        // `localFilePath` 对不带 scheme 的输入原样返回，包括看起来不像绝对路径的。
        // 区分「相对还是绝对」不是这一层的职责：`File("Music/a.mp4").exists()` 相对的是
        // 进程的工作目录，问出来的答案一样是 false，但至少它问的是同一个 API。
        assertEquals(MediaUriKind.FILE_SYSTEM, mediaUriKindOf("Music/a.mp4"))
    }

    @Test
    fun `远端地址算不支持`() {
        // 网络条目没有本地文件可查（`sourceOf` 把它们判成 REMOTE，本来也走不到探针），
        // 但万一走到了，答案必须是「不在」而不是「这是一条路径」。
        assertEquals(MediaUriKind.UNSUPPORTED, mediaUriKindOf("https://host/dir/a.mp4"))
        assertEquals(MediaUriKind.UNSUPPORTED, mediaUriKindOf("rtsp://host/a.mp4"))
    }

    @Test
    fun `空串算不支持`() {
        // 播放列表文件可以手改，`uri` 为空是可能的；空串交给 `File("")` 会
        // 悄悄变成「当前目录存在」，那就把一条彻底没救的条目说成还能播了。
        assertEquals(MediaUriKind.UNSUPPORTED, mediaUriKindOf(""))
    }

    @Test
    fun `scheme 大小写不敏感`() {
        // uri 的 scheme 按 RFC 3986 是大小写不敏感的，系统拼出来的永远是 `content://`，
        // 但播放列表可能被别的工具写过一遍。
        assertEquals(MediaUriKind.CONTENT, mediaUriKindOf("CONTENT://media/external/audio/media/42"))
        assertEquals(MediaUriKind.FILE_SYSTEM, mediaUriKindOf("FILE:///sdcard/a.mp4"))
    }
}
