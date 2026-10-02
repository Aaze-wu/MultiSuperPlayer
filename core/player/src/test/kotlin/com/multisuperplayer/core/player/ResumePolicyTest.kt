package com.multisuperplayer.core.player

import com.multisuperplayer.core.player.ResumePolicy.Action
import com.multisuperplayer.core.player.ResumePolicy.Persist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条位置该怎么落盘」的判定。
 *
 * 这个对象在这之前**一个测试都没有**，而它出的错全都很安静：
 *
 * - 「太短不记」误判 → 短片永远不出现在最近播放里（就是那个「最近播放留不住」）；
 * - 「看完了」误判 → 每次打开都从头播，看起来像续播功能坏了；
 * - 两个分支写成一个 → 上面两种症状轮着出现，而日志里一个字都没有。
 *
 * 所以边界值（多一毫秒、少一毫秒）比中间值重要：中间值怎么判都对。
 */
class ResumePolicyTest {

    // ------------------------------------------------------------ 三条分支

    @Test
    fun `看过一半是值得记的`() {
        assertEquals(Action.REMEMBER, ResumePolicy.persistAction(30_000L, 60_000L))
    }

    @Test
    fun `只看了几秒只记播过，不记位置`() {
        // 3 秒：用户点开看了一眼就退出。写进去的话下次从 3 秒开始，
        // 但**这一条必须出现在最近播放里**——他确实刚播过。
        assertEquals(Action.MARK_PLAYED, ResumePolicy.persistAction(3_000L, 600_000L))
    }

    @Test
    fun `快看完时归零，而不是记下九成九`() {
        // 位置 57 秒 / 60 秒 = 95%，正好是阈值。记下 57 秒的话下次一进来就跳到
        // 快结束的位置，用户会以为文件坏了；归零则下次从头播，而且记录还在。
        assertEquals(Action.RESTART_FROM_HEAD, ResumePolicy.persistAction(57_000L, 60_000L))
    }

    // ------------------------------------------------------------ 阈值边界

    @Test
    fun `正好到最短时长就算值得记`() {
        assertEquals(Action.REMEMBER, ResumePolicy.persistAction(15_000L, 600_000L))
    }

    @Test
    fun `比最短时长少一毫秒就只记播过`() {
        // 这一毫秒之差的两边行为完全不同（写位置 vs 保位置），钉住它，
        // 免得将来有人把 `<` 改成 `<=`。
        assertEquals(Action.MARK_PLAYED, ResumePolicy.persistAction(14_999L, 600_000L))
    }

    @Test
    fun `差一点到看完时仍然记位置`() {
        // 56 秒 / 60 秒 = 93.3%，还没到 95%。这里要是判成「看完了」，
        // 一段五分钟的片子在这之前的位置就全白看了。
        assertEquals(Action.REMEMBER, ResumePolicy.persistAction(56_000L, 60_000L))
    }

    @Test
    fun `很短的片段播完了是从头再来`() {
        // 位置 10 秒 < 15 秒，同时 10 秒 >= 10 秒 * 95%：两条规则同时命中。
        // 判定顺序在这里不重要（结果都是 0），但**名称**很重要：这是「看完了」，
        // 不是「只看了几秒」。两者写盘时走的路径不同（write(0) vs markPlayed()），
        // 将来任何一条改了行为，这个顺序就会从「无所谓」变成「有所谓」。
        assertEquals(Action.RESTART_FROM_HEAD, ResumePolicy.persistAction(10_000L, 10_000L))
    }

    @Test
    fun `片段报的时长比位置还短时也归零`() {
        // 元数据不可信（容器没写对时长、或刚从 VBR 文件里估出来）时会这样。
        // 归零是安全的一侧：大不了下次从头播，而不是跳到文件中间。
        assertEquals(Action.RESTART_FROM_HEAD, ResumePolicy.persistAction(70_000L, 60_000L))
    }

    @Test
    fun `位置为 0 是记播过而不是从头再来`() {
        // 两者写下去的都是 0，但在内核里是两条不同的路：MARK_PLAYED 走 markPlayed()
        // （保住已有位置），RESTART_FROM_HEAD 走 write(0)（覆盖掉位置）。
        // 判成后者时，一个从没播过的新文件会把「昨天看到 40 分钟」的那条记录抹掉。
        assertEquals(Action.MARK_PLAYED, ResumePolicy.persistAction(0L, 600_000L))
    }

    // ------------------------------------------------------------ 时长未知

    @Test
    fun `时长未知时只按够不够长判`() {
        // 直播、还没解析出元数据、时长报 0 的文件。要是按时长比例判，
        // `0 * 0.95 = 0`，任何位置都算「已看完」，续播永远不生效。
        assertEquals(Action.REMEMBER, ResumePolicy.persistAction(20_000L, 0L))
    }

    @Test
    fun `时长未知且位置太短时也只记播过`() {
        assertEquals(Action.MARK_PLAYED, ResumePolicy.persistAction(5_000L, 0L))
    }

    @Test
    fun `时长未知时不会有看完这一说`() {
        // 位置再大也一样：不知道总长就没法说「看完了」，归零会把续播彻底关掉。
        assertEquals(Action.REMEMBER, ResumePolicy.persistAction(99_999_999L, 0L))
    }

    // ------------------------------------------------------------ 与 shouldRemember 的关系

    @Test
    fun `值得记的位置一定和 shouldRemember 一致`() {
        val samples = listOf(
            0L to 0L,
            0L to 60_000L,
            15_000L to 60_000L,
            15_000L to 0L,
            57_000L to 60_000L,
            60_000L to 60_000L,
            14_999L to 14_999L,
            100L to 0L,
        )

        for ((position, duration) in samples) {
            // persistAction 的第一条分支就是 shouldRemember；这里钉住的是
            // 「它没有被悄悄改成别的判定」——两个函数将来只改一个的话，
            // 「值不值得记」在存储层和内核层会给出两个答案。
            val remembered = ResumePolicy.persistAction(position, duration) == Action.REMEMBER

            assertEquals(
                "position=$position duration=$duration",
                ResumePolicy.shouldRemember(position, duration),
                remembered,
            )
        }
    }

    @Test
    fun `每个分支都至少被一个输入命中`() {
        // 三条分支里有任何一条永远走不到，就是一个已经被人改坏的判定规则——
        // 而它在界面上只是「这个动作再也不会发生」。
        val samples = listOf(
            0L to 0L,
            0L to 600_000L,
            3_000L to 600_000L,
            14_999L to 600_000L,
            15_000L to 600_000L,
            56_000L to 60_000L,
            57_000L to 60_000L,
            10_000L to 10_000L,
            20_000L to 0L,
        )

        val reached = samples.map { (position, duration) ->
            ResumePolicy.persistAction(position, duration)
        }.toSet()

        assertEquals(Action.entries.toSet(), reached)
    }

    @Test
    fun `不该记位置的时候一定是另外两种动作之一`() {
        val position = 5_000L
        val duration = 600_000L

        assertFalse(ResumePolicy.shouldRemember(position, duration))
        assertTrue(ResumePolicy.persistAction(position, duration) != Action.REMEMBER)
    }

    // ------------------------------------------------- 两个开关怎么合成一步落盘

    @Test
    fun `两个开关都开着时三条动作原样落到存储`() {
        // 默认状态：内核该做什么完全由位置和时长决定，开关不参与。
        val cases = listOf(
            Triple(30_000L, 60_000L, Persist.POSITION),
            Triple(57_000L, 60_000L, Persist.ZERO),
            Triple(3_000L, 600_000L, Persist.MARK_PLAYED),
        )

        for ((position, duration, expected) in cases) {
            assertEquals(
                "position=$position duration=$duration",
                expected,
                ResumePolicy.persistStep(position, duration, rememberPosition = true, recordRecentPlays = true),
            )
        }
    }

    @Test
    fun `关掉记录最近播放后，播完了只清已有记录而不是新建一条`() {
        // 设备上实测到的坑：`write(id, 0)` 会把「归零」和「记一条播过」一起做掉，
        // 于是关掉开关之后，一个从头看到尾的短片仍然会出现在最近播放里。
        assertEquals(
            Persist.ZERO_IF_RECORDED,
            ResumePolicy.persistStep(
                positionMs = 57_000L,
                durationMs = 60_000L,
                rememberPosition = true,
                recordRecentPlays = false,
            ),
        )
    }

    @Test
    fun `关掉记录最近播放后，太短的位置什么都不写`() {
        assertEquals(
            Persist.NOTHING,
            ResumePolicy.persistStep(
                positionMs = 3_000L,
                durationMs = 600_000L,
                rememberPosition = true,
                recordRecentPlays = false,
            ),
        )
    }

    @Test
    fun `关掉记录最近播放挡不住续播位置`() {
        // 开关管的是「历史里多不多一条」，不是「下次从哪开始播」——
        // 挡住这一条的话，用户只想关掉列表，却连续播一起丢了。
        assertEquals(
            Persist.POSITION,
            ResumePolicy.persistStep(
                positionMs = 30_000L,
                durationMs = 60_000L,
                rememberPosition = true,
                recordRecentPlays = false,
            ),
        )
    }

    @Test
    fun `不记位置时仍然能记下播过`() {
        // 「每次从头播、但看得见看过什么」就是靠这一条配出来的：位置一个字节都不写
        // （markPlayed 保住已有位置），但时间戳推到现在，列表里看得见。
        assertEquals(
            Persist.MARK_PLAYED,
            ResumePolicy.persistStep(
                positionMs = 30_000L,
                durationMs = 60_000L,
                rememberPosition = false,
                recordRecentPlays = true,
            ),
        )
    }

    @Test
    fun `不记位置时永远不会写位置`() {
        // 只要「记住播放位置」是关的，三种动作都不该变成写位置——
        // 尤其是「看完了」那条：它会把位置写成 0，等于把用户昨天看到的位置抹掉。
        val samples = listOf(
            0L to 0L,
            3_000L to 600_000L,
            15_000L to 600_000L,
            30_000L to 60_000L,
            57_000L to 60_000L,
            10_000L to 10_000L,
        )

        for ((position, duration) in samples) {
            val step = ResumePolicy.persistStep(position, duration, rememberPosition = false, recordRecentPlays = true)
            assertTrue("position=$position duration=$duration step=$step", step == Persist.MARK_PLAYED)
        }
    }

    @Test
    fun `两个开关都关掉时什么都不写`() {
        val samples = listOf(
            0L to 0L,
            3_000L to 600_000L,
            30_000L to 60_000L,
            57_000L to 60_000L,
        )

        for ((position, duration) in samples) {
            assertEquals(
                "position=$position duration=$duration",
                Persist.NOTHING,
                ResumePolicy.persistStep(position, duration, rememberPosition = false, recordRecentPlays = false),
            )
        }
    }

    @Test
    fun `每个落盘步骤都至少被一个开关组合命中`() {
        // 五种结果里有任何一种永远走不到，就是一条已经写死的路径——
        // 而「开关不管用」和「开关一关就什么都不记」在界面上都只是「列表空着」。
        val reached = mutableSetOf<Persist>()
        val flags = listOf(true, false)

        for (remember in flags) {
            for (recent in flags) {
                for ((position, duration) in listOf(0L to 0L, 3_000L to 600_000L, 30_000L to 60_000L, 57_000L to 60_000L)) {
                    reached += ResumePolicy.persistStep(position, duration, remember, recent)
                }
            }
        }

        assertEquals(Persist.entries.toSet(), reached)
    }
}
