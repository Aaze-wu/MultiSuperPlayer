package com.multisuperplayer.core.data.history

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import com.multisuperplayer.core.player.PlaybackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「续播记录 × 当前媒体库」投影的单元测试。
 *
 * 这一层的每种失败在界面上都长得一模一样：**「最近播放是空的」**——
 * 没给权限、文件被删、SAF 授权失效、limit 传了个 0，全都是同一个画面。
 * 正因为界面区分不出来，只能在这一层把每种组合钉死。
 */
class RecentPlayRulesTest {

    private fun entry(id: String, title: String = id) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = MediaKind.AUDIO,
        source = MediaSource.MEDIA_STORE,
    )

    private fun record(mediaId: String, positionMs: Long = 60_000L, savedAtMs: Long = 1_000L) =
        PlaybackRecord(mediaId = mediaId, positionMs = positionMs, savedAtMs = savedAtMs)

    private fun project(
        records: List<PlaybackRecord>,
        entries: List<MediaEntry>,
        limit: Int = RecentPlayRules.DEFAULT_LIMIT,
    ) = RecentPlayRules.project(records, entries, limit)

    // --------------------------------------------------------------- limit

    @Test
    fun `limit 为零时一条都不要`() {
        // 0 是「一条都不要」，不是「不限」：把两者共用一个输入，
        // 调用方传进来一个没算出来的 0 就会变成整表拉出来。
        val records = listOf(record("audio:1"))
        val entries = listOf(entry("audio:1"))

        assertTrue(project(records, entries, limit = 0).isEmpty())
        assertTrue(project(records, entries, limit = -1).isEmpty())
    }

    @Test
    fun `limit 限制返回条数且取最近的那些`() {
        val records = listOf(
            record("audio:1", savedAtMs = 300L),
            record("audio:2", savedAtMs = 200L),
            record("audio:3", savedAtMs = 100L),
        )
        val entries = listOf(entry("audio:1"), entry("audio:2"), entry("audio:3"))

        val result = project(records, entries, limit = 2)

        assertEquals(listOf("audio:1", "audio:2"), result.map { it.entry.id })
    }

    // --------------------------------------------------------------- 空输入

    @Test
    fun `没有记录时是空的`() {
        assertTrue(project(emptyList(), listOf(entry("audio:1"))).isEmpty())
    }

    @Test
    fun `没有媒体库条目时是空的`() {
        // 没给存储权限就是这个形状——不该崩，也不该显示「文件已不在」。
        assertTrue(project(listOf(record("audio:1")), emptyList()).isEmpty())
    }

    // --------------------------------------------------------------- 过滤

    @Test
    fun `位置为 0 的记录仍然算播过`() {
        // 这一条以前是反过来的（位置 0 被当成脏数据滤掉），而那个规则让整个
        // 「最近播放」对短媒体永久失效：写入方对「刚播过但下次从头播」的记录
        // **就是**按 0 写的，一滤掉那个文件就永远不出现在列表里。
        // 记住区分：负数才是脏数据，0 是一个正常值。
        val result = project(
            listOf(record("audio:1", positionMs = 0L), record("audio:2", positionMs = 1L)),
            listOf(entry("audio:1"), entry("audio:2")),
        )

        assertEquals(listOf("audio:1", "audio:2"), result.map { it.entry.id }.sorted())
    }

    @Test
    fun `位置为 0 的记录也带着 0 而不是被改成别的值`() {
        // 界面上靠这个 0 决定显示「不记位置」还是「播放到 00:00」——
        // 这一层要是顺手把它变正数，那句话就再也不会出现。
        val result = project(
            listOf(record("audio:1", positionMs = 0L)),
            listOf(entry("audio:1")),
        )

        assertEquals(0L, result.single().positionMs)
    }

    @Test
    fun `位置为负数的记录被过滤`() {
        val result = project(
            listOf(record("audio:1", positionMs = -5L), record("audio:2")),
            listOf(entry("audio:1"), entry("audio:2")),
        )

        assertEquals(listOf("audio:2"), result.map { it.entry.id })
    }

    @Test
    fun `查不到元数据的记录被跳过而不是让整表作废`() {
        // 现在查不到可能只是权限没给；用户把权限补上之后它应该自己回来。
        val result = project(
            listOf(record("audio:ghost", savedAtMs = 900L), record("audio:1", savedAtMs = 100L)),
            listOf(entry("audio:1")),
        )

        assertEquals(listOf("audio:1"), result.map { it.entry.id })
    }

    @Test
    fun `所有记录都查不到时返回空表`() {
        val result = project(
            listOf(record("audio:ghost1"), record("audio:ghost2")),
            listOf(entry("audio:1")),
        )

        assertTrue(result.isEmpty())
    }

    // --------------------------------------------------------------- 排序

    @Test
    fun `按播放时间从新到旧排`() {
        val records = listOf(
            record("audio:1", savedAtMs = 100L),
            record("audio:2", savedAtMs = 300L),
            record("audio:3", savedAtMs = 200L),
        )
        val entries = records.map { entry(it.mediaId) }

        val result = project(records, entries)

        assertEquals(listOf("audio:2", "audio:3", "audio:1"), result.map { it.entry.id })
    }

    @Test
    fun `时间相同时按 id 排以便结果稳定`() {
        // 同一毫秒写入的两条记录：没有次级排序键的话顺序由输入顺序决定，
        // 界面上就是「列表偶尔换个顺序」。
        val records = listOf(
            record("audio:b", savedAtMs = 100L),
            record("audio:a", savedAtMs = 100L),
            record("audio:c", savedAtMs = 100L),
        )

        val result = project(records, records.map { entry(it.mediaId) })

        assertEquals(listOf("audio:a", "audio:b", "audio:c"), result.map { it.entry.id })
    }

    // --------------------------------------------------------------- 字段来源

    @Test
    fun `元数据来自当前媒体库而不是播放当时的快照`() {
        val result = project(
            listOf(record("audio:1", positionMs = 42_000L, savedAtMs = 999L)),
            listOf(entry("audio:1", title = "改名之后的标题")),
        )

        val row = result.single()
        assertEquals("改名之后的标题", row.entry.title)
        assertEquals(42_000L, row.positionMs)
        assertEquals(999L, row.playedAtMs)
    }

    @Test
    fun `默认 limit 是五十`() {
        assertEquals(50, RecentPlayRules.DEFAULT_LIMIT)

        val records = (0 until 80).map { record("audio:$it", savedAtMs = it.toLong()) }

        assertEquals(50, project(records, records.map { entry(it.mediaId) }).size)
    }

    // ---------------------------------------------------------- 库外解析名单

    private fun unresolved(
        records: List<PlaybackRecord>,
        entries: List<MediaEntry>,
        limit: Int = RecentPlayRules.DEFAULT_LIMIT,
    ) = RecentPlayRules.unresolvedIds(records, entries, limit)

    @Test
    fun `媒体库里有的 id 不进库外解析名单`() {
        // 每一条都要付一次跨进程 `query` 或 `stat`，已经查得到的没理由再问一次。
        val records = listOf(record("audio:1"), record("audio:2"))
        val entries = listOf(entry("audio:1"), entry("audio:2"))

        assertTrue(unresolved(records, entries).isEmpty())
    }

    @Test
    fun `浏览页的 id 进库外解析名单`() {
        // 这一条就是 v1.4.5 修的那个 bug 的形状：记录在磁盘上，媒体库里永远没有
        // 对应的条目（浏览页放的文件刻意不进库），于是投影一条都出不来。
        val records = listOf(
            record("file:/storage/emulated/0/Music/a.mp3", savedAtMs = 200L),
            record("audio:1", savedAtMs = 100L),
        )

        assertEquals(
            listOf("file:/storage/emulated/0/Music/a.mp3"),
            unresolved(records, listOf(entry("audio:1"))),
        )
    }

    @Test
    fun `同一个 id 只问一次`() {
        // 同一个文件播十次就是十条记录；不去重的话这一页要问十遍同一个文件。
        val records = (1..10).map { record("file:/a.mp3", savedAtMs = it.toLong()) }

        assertEquals(listOf("file:/a.mp3"), unresolved(records, emptyList()))
    }

    @Test
    fun `名单按时间倒序且受 limit 限制`() {
        // 上限是必需的：续播记录最多留着 200 条，全都拿去问就是 200 次跨进程调用。
        val records = (1..10).map { record("file:/$it.mp3", savedAtMs = it.toLong()) }

        assertEquals(
            listOf("file:/10.mp3", "file:/9.mp3", "file:/8.mp3"),
            unresolved(records, emptyList(), limit = 3),
        )
    }

    @Test
    fun `limit 为零时不问任何 id`() {
        val records = listOf(record("file:/a.mp3"))

        assertTrue(unresolved(records, emptyList(), limit = 0).isEmpty())
        assertTrue(unresolved(records, emptyList(), limit = -1).isEmpty())
    }

    @Test
    fun `位置为负数的记录不进库外解析名单`() {
        // 投影会滤掉它们，那就没必要为它们付一次 IO。
        val records = listOf(record("file:/bad.mp3", positionMs = -1L))

        assertTrue(unresolved(records, emptyList()).isEmpty())
    }
}
