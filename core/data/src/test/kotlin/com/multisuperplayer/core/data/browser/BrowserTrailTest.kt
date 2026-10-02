package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航栈的单元测试。
 *
 * 这个类存在的理由是「上一层在两种来源里都算不出来」，所以这里要盯住的是
 * **ref 有没有被弄脏**：`up()` 之后当前 ref 必须是当初真的进过的那一个，
 * 不能是任何形式推出来的路径。一旦退化成路径运算，SAF 来源上
 * 「返回上一层」就会指向一个打不开的位置，而界面看起来完全正常。
 */
class BrowserTrailTest {

    private val root = BrowserTrail.root(
        kind = BrowserSourceKind.SAF,
        label = MspText.Plain("内部存储"),
        ref = "content://tree/root",
    )

    @Test
    fun `根上没有上一层`() {
        assertFalse(root.canGoUp)
        // 已经在根上时 `up()` 返回自己，不是抛异常也不是空栈——
        // 界面上的「返回」按钮会一直在，必须能安全地按。
        assertEquals(root, root.up())
        assertEquals(1, root.crumbs.size)
    }

    @Test
    fun `逐级进入后每一层的 ref 都是进来时那一个`() {
        val two = root.enter(MspText.Plain("Music"), "content://tree/root/document/music")
        val three = two.enter(MspText.Plain("Album"), "content://tree/root/document/album")

        assertEquals(
            listOf(MspText.Plain("内部存储"), MspText.Plain("Music"), MspText.Plain("Album")),
            three.crumbs.map { it.label },
        )
        assertEquals(
            listOf(
                "content://tree/root",
                "content://tree/root/document/music",
                "content://tree/root/document/album",
            ),
            three.crumbs.map { it.ref },
        )
        assertEquals("content://tree/root/document/album", three.current.ref)
        assertTrue(three.canGoUp)
    }

    @Test
    fun `返回上一层恢复的是当初那一层，不是算出来的路径`() {
        val two = root.enter(MspText.Plain("Music"), "content://tree/root/document/music")
        val three = two.enter(MspText.Plain("Album"), "content://tree/root/document/album")

        val back = three.up()

        assertEquals(two, back)
        assertEquals("content://tree/root/document/music", back.current.ref)
        assertEquals(MspText.Plain("Music"), back.current.label)
    }

    @Test
    fun `面包屑跳转截断到该层并为之后的内容腾出空间`() {
        val three = root
            .enter(MspText.Plain("Music"), "a")
            .enter(MspText.Plain("Album"), "b")

        val jumped = three.jumpTo(0)

        assertEquals(root, jumped)
        // 关键：跳回中间层之后，再进入子目录应当**替换**原先更深的那些段，
        // 而不是堆在后头。这里验证「从跳转结果继续 enter」的深度是对的。
        val branched = jumped.enter(MspText.Plain("Movies"), "c")
        assertEquals(listOf(MspText.Plain("内部存储"), MspText.Plain("Movies")), branched.crumbs.map { it.label })
    }

    @Test
    fun `越界跳转原样返回`() {
        val three = root.enter(MspText.Plain("Music"), "a")
        // 负数与越界的下标都要能安全忽略：面包屑的点击区域和列表一起滚，
        // 手势滑动时越界点击并不罕见。
        assertEquals(three, three.jumpTo(-1))
        assertEquals(three, three.jumpTo(2))
        assertEquals(three, three.jumpTo(Int.MAX_VALUE))
    }

    @Test
    fun `空的段列表直接拒绝构造`() {
        // 没有 `current` 的导航栈在界面上就是「面包屑是空的 + 列不出任何东西」，
        // 两个症状都不指向真正的原因。所以这里进不来。
        val error = runCatching { BrowserTrail(BrowserSourceKind.SAF, emptyList()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `来源类型在整条链上保持不变`() {
        // `kind` 决定用哪个 DirectorySource 解释 ref。如果某一层换掉了它，
        // 上层是 SAF uri、下层是绝对路径，界面就会把 uri 当路径去列。
        val two = root.enter(MspText.Plain("Music"), "/storage/emulated/0/Music")
        assertEquals(BrowserSourceKind.SAF, two.kind)
        assertEquals(BrowserSourceKind.SAF, two.up().kind)
    }

    // ------------------------------------------------------------ 从条目进入

    @Test
    fun `从目录条目进入时显示名就是目录名`() {
        // 目录名是用户自己的数据（不翻译），所以用 Plain。
        // 这里同时验证 `enter(entry)` 与 `enter(label, ref)` 结果一致——
        // 两者不一致的话，界面点进去和面包屑显示的内容会分叉。
        val entry = BrowserEntry(ref = "/x/Movies", name = "Movies", isDirectory = true)

        assertEquals(
            root.enter(MspText.Plain("Movies"), "/x/Movies"),
            root.enter(entry),
        )
    }

    @Test
    fun `文件条目不能进入`() {
        // 传文件进来是调用方的编程错误：静默接受会在面包屑上多出一个
        // 「点了打不开」的假目录，而问题现场只剩下一条面包屑。
        val file = BrowserEntry(
            ref = "/x/movie.mp4",
            name = "movie.mp4",
            isDirectory = false,
            kind = MediaKind.VIDEO,
        )

        val error = runCatching { root.enter(file) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `从来源清单构造时显示名走 MspText，不被提前解析成字符串`() {
        // 第一段的显示名有可能是 `R.string`（「内部存储」）。如果 `BrowserRoot`
        // 的 label 在这一步被强行解析，数据层就得拿到 `Resources`。
        val storageRoot = BrowserRoot(
            kind = BrowserSourceKind.FILE_SYSTEM,
            ref = "/storage/emulated/0",
            label = MspText.Plain("内部存储"),
            detail = null,
            // 「可用」的定义就是没有任何 issue 要报告。
            issue = null,
        )

        val trail = BrowserTrail.root(storageRoot)

        assertEquals(BrowserSourceKind.FILE_SYSTEM, trail.kind)
        assertEquals(storageRoot.ref, trail.current.ref)
        assertEquals(storageRoot.label, trail.current.label)
        assertFalse(trail.canGoUp)
    }
}
