package com.multisuperplayer.core.data.history

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.player.PlaybackRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「最近播放的一行 → 续播记录」的还原规则（`RecentPlayRules.recordOf`）。
 *
 * 它只在「删除 + 撤销」这条路上用，出错既不报错也不丢数据，只会让列表**顺序不对**：
 * 这一页按记录里的时间戳倒序排，还原时把时间戳换成「现在」，撤销之后那一行就会
 * 跳到最上面。用户看到的是「撤销把顺序弄坏了」，很难联想到时间戳被重写了——
 * 所以这里钉的是「三个字段一对一搬过去」，而不是「能构造出一个对象」。
 */
class RecentPlayRecordTest {

    private fun entry(id: String) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = id,
        kind = MediaKind.AUDIO,
        source = MediaSource.MEDIA_STORE,
    )

    private fun row(
        id: String,
        positionMs: Long = 60_000L,
        playedAtMs: Long = 1_000L,
    ) = RecentPlay(entry(id), positionMs, playedAtMs)

    @Test
    fun `位置和时间戳原样搬过去`() {
        // 时间戳挑一个明显属于过去的绝对时刻：实现里若改用 write()（它盖新时间戳），
        // 这一条会挂。
        val playedAt = 1_600_000_000_000L

        val record = RecentPlayRules.recordOf(row("a1", positionMs = 12_000L, playedAtMs = playedAt))

        assertEquals(12_000L, record.positionMs)
        assertEquals(playedAt, record.savedAtMs)
    }

    @Test
    fun `媒体 id 取条目的 id 而不是 uri`() {
        // 续播存储的键就是这个 id（`resume.<id>`）。写成 uri 会得到一条谁都读不出来
        // 的记录：撤销看着成功，列表里却没有它。
        val record = RecentPlayRules.recordOf(row("file:/storage/1/a.mp3"))

        assertEquals("file:/storage/1/a.mp3", record.mediaId)
    }

    @Test
    fun `位置为零的记录也能还原`() {
        // 0 是**一条正常记录**（刚播过、下次从头播），不是「没有记录」的值。
        assertEquals(0L, RecentPlayRules.recordOf(row("a1", positionMs = 0L)).positionMs)
    }

    @Test
    fun `还原后再投影会得到原来那一行`() {
        // 往返一致：`project` 和 `recordOf` 必须对「哪一列对应哪个字段」有同一个答案。
        val original = row("a1", positionMs = 12_000L, playedAtMs = 1_600_000_000_000L)
        val entries = listOf(entry("a1"), entry("a2"))

        val back = RecentPlayRules.project(listOf(RecentPlayRules.recordOf(original)), entries)

        assertEquals(listOf(original), back)
    }

    @Test
    fun `位置为负的行还原出的记录会被投影滤掉`() {
        // 负数位置是脏数据（写入方按 `coerceAtLeast(0)` 写）。撤销时不必自己判：
        // 投影那一层本来就会滤掉它，两处都判反而会出现「撤销成功但列表里没有」
        // 这种看起来像失败的结果。
        val bad = row("a1", positionMs = -1L)

        assertEquals(
            emptyList<RecentPlay>(),
            RecentPlayRules.project(listOf(RecentPlayRules.recordOf(bad)), listOf(entry("a1"))),
        )
    }

    @Test
    fun `记录里的时间戳就是行上的播放时间`() {
        // 这两个字段容易被当成两件不同的事（一个是「续播点的保存时刻」，
        // 一个是「最近一次播放的时刻」），实际上 project 就是原样搬的。
        val record = PlaybackRecord(mediaId = "a1", positionMs = 5L, savedAtMs = 1_600_000_000_000L)
        val projected = RecentPlayRules.project(listOf(record), listOf(entry("a1"))).single()

        assertEquals(record.savedAtMs, projected.playedAtMs)
    }
}
