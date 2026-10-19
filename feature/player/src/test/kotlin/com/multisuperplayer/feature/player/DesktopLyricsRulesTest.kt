package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.DesktopLyricsFontSize
import com.multisuperplayer.core.data.settings.DesktopLyricsPosition
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面歌词规则的单元测试。
 *
 * 浮窗本身（`WindowManager` + Compose）在 JVM 里测不了，所以「显示哪几行」
 * 「这一刻该不该有窗口」「第一次出现在哪」全部抽成了纯函数，这个文件是它们的规定。
 *
 * 最贵的一类错误是**浮窗和播放页对不上**：同一时刻播放页显示第 7 行、浮窗显示
 * 第 6 行，用户会以为桌面歌词坏了。所以这里也断言行选择与
 * `cueFocusIndexAt` / `subtitleCuePosition` 的组合结果。
 */
class DesktopLyricsRulesTest {

    private fun cue(
        index: Int,
        startMs: Long = index * 10_000L,
        text: String = "第 $index 行",
        translation: String? = null,
    ) = SubtitleCue(
        index = index,
        startMs = startMs,
        endMs = startMs + 9_000L,
        text = text,
        translation = translation,
    )

    private fun document(vararg cues: SubtitleCue) = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = cues.toList(),
    )

    private val bilingual = document(
        cue(0, text = "第一句", translation = "first line"),
        cue(1, text = "第二句", translation = "second line"),
        cue(2, text = "第三句", translation = "third line"),
    )

    // ------------------------------------------------------------ 行内容

    @Test
    fun `仅原文时显示当前行与下一行`() {
        val rows = desktopLyricsRows(bilingual, 12_000L, SubtitleDisplayMode.ORIGINAL_ONLY)

        assertEquals(2, rows.size)
        assertEquals("第二句", rows[0].text)
        assertEquals(DesktopLyricsRowRole.CURRENT, rows[0].role)
        assertEquals("第三句", rows[1].text)
        assertEquals(DesktopLyricsRowRole.NEXT, rows[1].role)
    }

    @Test
    fun `双语时显示当前原文、当前译文与下一行原文`() {
        val rows = desktopLyricsRows(bilingual, 12_000L, SubtitleDisplayMode.BILINGUAL)

        assertEquals(3, rows.size)
        assertEquals("第二句", rows[0].text)
        assertEquals(DesktopLyricsRowRole.CURRENT, rows[0].role)
        assertEquals("second line", rows[1].text)
        assertEquals(DesktopLyricsRowRole.TRANSLATION, rows[1].role)
        assertEquals("第三句", rows[2].text)
        assertEquals(DesktopLyricsRowRole.NEXT, rows[2].role)
    }

    @Test
    fun `仅译文时当前行与下一行都取译文`() {
        val rows = desktopLyricsRows(bilingual, 12_000L, SubtitleDisplayMode.TRANSLATION_ONLY)

        assertEquals(2, rows.size)
        assertEquals("second line", rows[0].text)
        assertEquals(DesktopLyricsRowRole.CURRENT, rows[0].role)
        assertEquals("third line", rows[1].text)
        assertEquals(DesktopLyricsRowRole.NEXT, rows[1].role)
    }

    @Test
    fun `双语模式下某条没译文时那一行不画空白`() {
        // 只翻译了一半的字幕很常见。退回原文，而不是留一行空白背景。
        val half = document(
            cue(0, text = "第一句"),
            cue(1, text = "第二句", translation = "second line"),
        )

        val rows = desktopLyricsRows(half, 0L, SubtitleDisplayMode.BILINGUAL)

        assertEquals(2, rows.size)
        assertEquals("第一句", rows[0].text)
        assertEquals(DesktopLyricsRowRole.CURRENT, rows[0].role)
        // 第二条有译文，但它是「下一行预览」，所以只出原文那一行。
        assertEquals("第二句", rows[1].text)
        assertEquals(DesktopLyricsRowRole.NEXT, rows[1].role)
    }

    @Test
    fun `完全没有翻译时仅译文模式退回原文`() {
        val plain = document(cue(0, text = "只有原文"))
        val rows = desktopLyricsRows(plain, 0L, SubtitleDisplayMode.TRANSLATION_ONLY)

        assertEquals(1, rows.size)
        assertEquals("只有原文", rows[0].text)
    }

    @Test
    fun `最后一句没有下一行`() {
        val rows = desktopLyricsRows(bilingual, 22_000L, SubtitleDisplayMode.ORIGINAL_ONLY)

        assertEquals(1, rows.size)
        assertEquals("第三句", rows[0].text)
        assertTrue(rows.none { it.role == DesktopLyricsRowRole.NEXT })
    }

    @Test
    fun `位置早于第一条时显示第一条`() {
        // 前奏里浮窗空着看起来就像没加载出来，提前显示第一句更有用。
        val rows = desktopLyricsRows(bilingual, 0L, SubtitleDisplayMode.ORIGINAL_ONLY)

        assertEquals("第一句", rows.first().text)
        assertEquals("第二句", rows[1].text)
    }

    @Test
    fun `两行之间不回到空白而是停在上一行`() {
        // 第 0 句 [0, 9000)，第 1 句从 10000 开始：9500 落在空档里。
        // 歌词语义（cueFocusIndexAt）会停在最后一句已经开始的行上，
        // 这样浮窗不会在每句结尾闪一下空白。
        val rows = desktopLyricsRows(bilingual, 9_500L, SubtitleDisplayMode.ORIGINAL_ONLY)

        assertEquals("第一句", rows[0].text)
    }

    @Test
    fun `空文本的 cue 被跳过`() {
        // SRT 里用来清屏的空白条目不能当当前行，否则浮窗会突然空一下。
        val withBlank = document(
            cue(0, text = "第一句"),
            cue(1, text = "   "),
            cue(2, text = "第三句"),
        )

        val rows = desktopLyricsRows(withBlank, 12_000L, SubtitleDisplayMode.ORIGINAL_ONLY)

        assertEquals("第三句", rows[0].text)
    }

    @Test
    fun `字幕关闭时没有任何行`() {
        assertTrue(desktopLyricsRows(bilingual, 12_000L, SubtitleDisplayMode.OFF).isEmpty())
    }

    @Test
    fun `没有文档或文档为空时没有任何行`() {
        assertTrue(desktopLyricsRows(null, 0L, SubtitleDisplayMode.ORIGINAL_ONLY).isEmpty())
        assertTrue(
            desktopLyricsRows(
                document(),
                0L,
                SubtitleDisplayMode.ORIGINAL_ONLY,
            ).isEmpty(),
        )
    }

    @Test
    fun `微调偏移与速率同样作用于浮窗`() {
        // 时间轴换算必须与播放页共用同一套「先缩放再平移」：
        // 用户把字幕调早 10 秒，浮窗也要早 10 秒，否则两个界面对不上。
        val rows = desktopLyricsRows(
            document = bilingual,
            positionMs = 22_000L,
            mode = SubtitleDisplayMode.ORIGINAL_ONLY,
            timelineOffsetMs = 10_000L,
        )

        assertEquals("第二句", rows[0].text)
    }

    // ------------------------------------------------------------ 该不该显示

    @Test
    fun `开关打开且系统授权且退到后台时显示`() {
        assertEquals(
            DesktopLyricsGate.SHOWN,
            desktopLyricsGate(
                enabled = true,
                permissionGranted = true,
                appVisible = false,
                hasLyrics = true,
                displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            ),
        )
    }

    @Test
    fun `开关关掉时安静地不显示`() {
        assertEquals(
            DesktopLyricsGate.TURNED_OFF,
            desktopLyricsGate(
                enabled = false,
                permissionGranted = true,
                appVisible = false,
                hasLyrics = true,
                displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            ),
        )
    }

    @Test
    fun `没有悬浮窗权限时能区分出来`() {
        // 这一条必须能和「没有歌词」分开：前者要去系统设置授权，后者只需换一首歌。
        // 合成一个布尔的话，用户点开开关、什么都没发生，会以为功能坏了。
        assertEquals(
            DesktopLyricsGate.NO_PERMISSION,
            desktopLyricsGate(
                enabled = true,
                permissionGranted = false,
                appVisible = false,
                hasLyrics = true,
                displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            ),
        )
    }

    @Test
    fun `应用在前台时不显示`() {
        assertEquals(
            DesktopLyricsGate.APP_VISIBLE,
            desktopLyricsGate(
                enabled = true,
                permissionGranted = true,
                appVisible = true,
                hasLyrics = true,
                displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            ),
        )
    }

    @Test
    fun `没有歌词时不显示`() {
        assertEquals(
            DesktopLyricsGate.NO_LYRICS,
            desktopLyricsGate(
                enabled = true,
                permissionGranted = true,
                appVisible = false,
                hasLyrics = false,
                displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            ),
        )
    }

    @Test
    fun `字幕关闭时不显示桌面歌词`() {
        assertEquals(
            DesktopLyricsGate.SUBTITLES_OFF,
            desktopLyricsGate(
                enabled = true,
                permissionGranted = true,
                appVisible = false,
                hasLyrics = true,
                displayMode = SubtitleDisplayMode.OFF,
            ),
        )
    }

    @Test
    fun `判定顺序以用户意图优先`() {
        // 同时满足「关掉开关」和「没权限」时，报「关掉了」——用户的动作比系统状态更该被相信。
        assertEquals(
            DesktopLyricsGate.TURNED_OFF,
            desktopLyricsGate(
                enabled = false,
                permissionGranted = false,
                appVisible = true,
                hasLyrics = false,
                displayMode = SubtitleDisplayMode.OFF,
            ),
        )
    }

    // ------------------------------------------------------------ 首次位置

    @Test
    fun `首次出现时水平居中并靠近底部`() {
        val position = defaultDesktopLyricsPosition(
            screenWidth = 1080,
            screenHeight = 2000,
            windowWidth = 980,
            windowHeight = 200,
            bottomGapPx = 100,
        )

        assertEquals(DesktopLyricsPosition(x = 50, y = 1700), position)
    }

    @Test
    fun `首次位置本身也在屏内`() {
        // 分屏 / 小窗里「居中 + 靠底」可能算到屏外，而浮窗没有标题栏可以拖回来。
        val position = defaultDesktopLyricsPosition(
            screenWidth = 600,
            screenHeight = 300,
            windowWidth = 580,
            windowHeight = 280,
            bottomGapPx = 100,
        )

        assertTrue(position.isSet)
        assertTrue(position.y!! >= 0)
        assertTrue(position.y!! + 280 <= 300)
    }

    @Test
    fun `窗口占满屏幕时首次位置贴左上角`() {
        val position = defaultDesktopLyricsPosition(
            screenWidth = 500,
            screenHeight = 400,
            windowWidth = 500,
            windowHeight = 400,
            bottomGapPx = 0,
        )

        assertEquals(DesktopLyricsPosition(0, 0), position)
    }

    @Test
    fun `空文档没有行也没有默认位置之外的副作用`() {
        assertNull(desktopLyricsRows(document(), 0L, SubtitleDisplayMode.BILINGUAL).firstOrNull())
    }

    // ------------------------------------------------------------- 字号步进

    @Test
    fun `字号步进 - 逐级上调`() {
        assertEquals(
            DesktopLyricsFontSize.MEDIUM,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.SMALL, 1),
        )
        assertEquals(
            DesktopLyricsFontSize.HUGE,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.LARGE, 1),
        )
    }

    @Test
    fun `字号步进 - 到底之后不再变, 也不会绕回最小`() {
        // 悬浮窗上的那两个按钮没有文字，只有图标：绕回最小的话，用户会以为
        // 自己按错了（"我明明一直在放大"）。所以到边界就停住。
        assertEquals(
            DesktopLyricsFontSize.HUGE,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.HUGE, 1),
        )
        assertEquals(
            DesktopLyricsFontSize.SMALL,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.SMALL, -1),
        )
    }

    @Test
    fun `字号步进 - 一次跨越很多级也只停在边界`() {
        assertEquals(
            DesktopLyricsFontSize.HUGE,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.SMALL, 99),
        )
        assertEquals(
            DesktopLyricsFontSize.SMALL,
            stepDesktopLyricsFontSize(DesktopLyricsFontSize.HUGE, -99),
        )
    }

    @Test
    fun `字号步进 - 零步不动`() {
        DesktopLyricsFontSize.entries.forEach { size ->
            assertEquals(size, stepDesktopLyricsFontSize(size, 0))
        }
    }

    @Test
    fun `字号步进 - 从任意一级走任意一步都落在合法值上`() {
        DesktopLyricsFontSize.entries.forEach { size ->
            (-5..5).forEach { step ->
                val result = stepDesktopLyricsFontSize(size, step)
                assertTrue(
                    "从 $size 走 $step 拿到了 $result",
                    result in DesktopLyricsFontSize.entries,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 入口按钮

    @Test
    fun `入口按钮 - 没有权限时永远是去授权`() {
        // 其余三位怎么组合都不该把它盖掉：浮窗挂不上去时，「开着」这件事毫无意义。
        listOf(true, false).forEach { enabled ->
            listOf(true, false).forEach { locked ->
                assertEquals(
                    DesktopLyricsEntryAction.REQUEST_PERMISSION,
                    desktopLyricsEntryAction(
                        enabled = enabled,
                        locked = locked,
                        permissionGranted = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `入口按钮 - 有权限但关着时是开启`() {
        assertEquals(
            DesktopLyricsEntryAction.ENABLE,
            desktopLyricsEntryAction(enabled = false, locked = false, permissionGranted = true),
        )
        // 关着的时候「锁着」是个残留状态（浮窗根本不在），不该抢到解锁分支。
        assertEquals(
            DesktopLyricsEntryAction.ENABLE,
            desktopLyricsEntryAction(enabled = false, locked = true, permissionGranted = true),
        )
    }

    @Test
    fun `入口按钮 - 开着且锁着时是解锁而不是关闭`() {
        assertEquals(
            DesktopLyricsEntryAction.UNLOCK,
            desktopLyricsEntryAction(enabled = true, locked = true, permissionGranted = true),
        )
    }

    @Test
    fun `入口按钮 - 只剩开着且没锁才是关闭`() {
        assertEquals(
            DesktopLyricsEntryAction.DISABLE,
            desktopLyricsEntryAction(enabled = true, locked = false, permissionGranted = true),
        )
    }

    @Test
    fun `入口按钮 - 三个开关的八种组合都落在这四种动作上`() {
        val seen = mutableSetOf<DesktopLyricsEntryAction>()
        listOf(true, false).forEach { enabled ->
            listOf(true, false).forEach { locked ->
                listOf(true, false).forEach { granted ->
                    seen += desktopLyricsEntryAction(enabled, locked, granted)
                }
            }
        }
        // 四种都要能被走到：任何一种到不了，就说明有一档状态在界面上永远出不来。
        assertEquals(DesktopLyricsEntryAction.entries.toSet(), seen)
    }
}
