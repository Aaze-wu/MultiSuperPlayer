package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览页多选的测试。
 *
 * 这里真正要盯住的是**「什么算能选的一项」**：目录进去是另一层，字幕是给正在播的
 * 那条挂的，两者都没有「播放 / 加入播放列表」这个动作。选上它们的结果是操作条说
 * 「已选 3 项」而只有 2 项真的会进队列——数字不对，用户没法发现是哪一项。
 */
class BrowserSelectionTest {

    private fun dir(name: String) = BrowserEntry(ref = "/mnt/$name", name = name, isDirectory = true)

    private fun file(name: String, kind: MediaKind? = MediaKind.VIDEO) = BrowserEntry(
        ref = "/mnt/$name",
        name = name,
        isDirectory = false,
        sizeBytes = 1024L,
        lastModifiedMs = 1_700_000_000_000L,
        kind = kind,
        mimeType = "video/mp4",
    )

    private val listing = listOf(
        dir("Movies"),
        file("a.mp4"),
        file("b.srt", kind = null),
        file("c.mkv"),
        file("notes.txt", kind = null),
        file("d.mka", kind = MediaKind.UNKNOWN),
    )

    @Test
    fun `只有能播的文件进多选集合`() {
        val selectable = selectableMedia(listing)
        // 标题是去过后缀的（`displayTitle`），与媒体库一致。
        assertEquals(listOf("a", "c", "d"), selectable.map { it.title })
    }

    @Test
    fun `未知类型仍可选中因为内核可能认得`() {
        // `MediaKind.UNKNOWN` 是「后缀/MIME 认不出」，交内核探测；`null` 才是
        // 「明确不是媒体」。把两者合并会让冷门容器突然选不上。
        val ids = selectableMedia(listing).map { it.id }
        assertTrue(BrowserEntry.mediaIdOf("/mnt/d.mka") in ids)
    }

    @Test
    fun `目录和字幕永远进不了选择集`() {
        val ids = selectableMedia(listing).map { it.id }
        assertFalse(BrowserEntry.mediaIdOf("/mnt/Movies") in ids)
        assertFalse(BrowserEntry.mediaIdOf("/mnt/b.srt") in ids)
        assertFalse(BrowserEntry.mediaIdOf("/mnt/notes.txt") in ids)
    }

    @Test
    fun `顺序与目录里看到的一致`() {
        // 队列的顺序必须是用户眼里的顺序：按 id 重排会让「播放」从别的地方开始。
        assertEquals(
            listOf("/mnt/a.mp4", "/mnt/c.mkv", "/mnt/d.mka"),
            selectableMedia(listing).map { it.uri },
        )
    }

    @Test
    fun `选择集的键就是媒体 id`() {
        val selectable = selectableMedia(listing)
        val selected = LibrarySelectionRules.addAll(emptyList(), selectable)
        assertEquals(selectable.map { it.id }, selected)
        assertTrue(selected.all { BrowserEntry.isBrowserMediaId(it) })
    }

    @Test
    fun `来源被标成文件系统`() {
        // 播放列表与字幕查找都靠它：标错成媒体库会去按 relativePath 找同目录字幕。
        assertTrue(selectableMedia(listing).all { it.source == MediaSource.FILE_SYSTEM })
    }

    @Test
    fun `全选后是取消全选`() {
        val selectable = selectableMedia(listing)
        val all = LibrarySelectionRules.addAll(emptyList(), selectable)
        assertTrue(LibrarySelectionRules.allSelected(all, selectable))
        // 一个可播的都没有时不能显示「取消全选」——那是个点了没反应的说法。
        assertFalse(LibrarySelectionRules.allSelected(emptyList(), emptyList()))
    }

    @Test
    fun `取消选择只清掉当前目录这一批`() {
        val selectable = selectableMedia(listing)
        val selected = LibrarySelectionRules.addAll(listOf("file:/other/x.mp4"), selectable)
        assertEquals(
            listOf("file:/other/x.mp4"),
            LibrarySelectionRules.removeAll(selected, selectable),
        )
    }

    @Test
    fun `选择集里的条目解析回可播清单`() {
        val selectable = selectableMedia(listing)
        val selected = listOf(BrowserEntry.mediaIdOf("/mnt/c.mkv"))
        assertEquals(listOf("/mnt/c.mkv"), LibrarySelectionRules.resolve(selectable, selected).map { it.uri })
    }

    @Test
    fun `逆序点选后的顺序就是点击顺序`() {
        // 目录里 a 在 c 前面，但用户先点了 c。加入播放列表/开始播放都必须以 c 为先。
        val selectable = selectableMedia(listing)
        val c = BrowserEntry.mediaIdOf("/mnt/c.mkv")
        val a = BrowserEntry.mediaIdOf("/mnt/a.mp4")
        val selected = toggleSelection(toggleSelection(emptyList(), selectable, c), selectable, a)
        assertEquals(
            listOf("/mnt/c.mkv", "/mnt/a.mp4"),
            LibrarySelectionRules.resolve(selectable, selected).map { it.uri },
        )
    }

    @Test
    fun `换到另一个目录后旧选择被剪掉`() {
        val previous = listOf(BrowserEntry.mediaIdOf("/mnt/a.mp4"), BrowserEntry.mediaIdOf("/mnt/c.mkv"))
        val nextDir = listOf(file("z.mp4"))
        assertEquals(emptyList<String>(), pruneSelection(previous, selectableMedia(nextDir)))
    }

    @Test
    fun `清单里还在的 id 会保留`() {
        val selected = listOf(
            BrowserEntry.mediaIdOf("/mnt/a.mp4"),
            BrowserEntry.mediaIdOf("/mnt/已删除.mp4"),
        )
        assertEquals(
            listOf(BrowserEntry.mediaIdOf("/mnt/a.mp4")),
            pruneSelection(selected, selectableMedia(listing)),
        )
    }

    @Test
    fun `选择集没变时返回同一个实例`() {
        // 调用方靠 `!==` 决定「要不要写回状态」：每进一次目录都白写一次状态
        // 就是每次切目录多重组一遍整页。
        val selected = listOf(BrowserEntry.mediaIdOf("/mnt/a.mp4"))
        assertSame(selected, pruneSelection(selected, selectableMedia(listing)))
        // 空集合一律原样返回（连算都不用算）。
        assertSame(emptyList<String>(), pruneSelection(emptyList(), selectableMedia(listing)))
    }

    @Test
    fun `离开目录等于清空选择`() {
        // 回到来源清单时 `content` 不是 Ready ⇒ 可播清单是空的 ⇒ 全部被剪掉。
        // 也就是说「退出目录即退出多选」，不需要另一个状态字段来记这件事。
        val selected = listOf(BrowserEntry.mediaIdOf("/mnt/a.mp4"))
        assertEquals(emptyList<String>(), pruneSelection(selected, emptyList()))
    }

    @Test
    fun `目录的 id 塞不进选择集`() {
        // 真实出现过的界面错误：长按一个目录，那一行的方框变成**勾选态**，
        // 而操作条写着「已选 0 项」——两个数字当面对不上。根因是长按落在整行
        // 上，把不可选的 id 也塞了进来。
        val selectable = selectableMedia(listing)
        val selected = toggleSelection(emptyList(), selectable, BrowserEntry.mediaIdOf("/mnt/Movies"))
        assertEquals(emptyList<String>(), selected)
        assertSame(emptyList<String>(), selected)
    }

    @Test
    fun `字幕的 id 塞不进选择集`() {
        val selectable = selectableMedia(listing)
        assertEquals(
            emptyList<String>(),
            toggleSelection(emptyList(), selectable, BrowserEntry.mediaIdOf("/mnt/b.srt")),
        )
    }

    @Test
    fun `能播的 id 正常进出选择集`() {
        val selectable = selectableMedia(listing)
        val id = BrowserEntry.mediaIdOf("/mnt/a.mp4")
        val selected = toggleSelection(emptyList(), selectable, id)
        assertEquals(listOf(id), selected)
        assertEquals(emptyList<String>(), toggleSelection(selected, selectable, id))
    }

    @Test
    fun `不可选的 id 不影响已经选好的那批`() {
        val selectable = selectableMedia(listing)
        val id = BrowserEntry.mediaIdOf("/mnt/a.mp4")
        val selected = toggleSelection(emptyList(), selectable, id)
        // 返回**同一个实例**：不必白触发一次重组。
        assertSame(selected, toggleSelection(selected, selectable, BrowserEntry.mediaIdOf("/mnt/Movies")))
    }
}
