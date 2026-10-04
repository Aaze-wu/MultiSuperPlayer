package com.multisuperplayer.core.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Cue` → [BitmapCueSpec] 的翻译。
 *
 * ## 为什么要单独测这一层
 *
 * 位图字幕从 Media3 到屏幕之间有三个坐标系：`Cue` 里是**混着哨兵值的浮点**
 * （没写过的项是 `DIMEN_UNSET`，锚点则没有哨兵值——默认就是 `START` = 0），
 * [BitmapCueSpec] 里是「有就写、没有就 null」的干净比例，最后才是像素矩形。
 * 中间这层最容易出的错是**把哨兵值当成真值**：
 *
 * - `position = DIMEN_UNSET` 直接送进摆放公式 → 字幕被摆到屏幕外（那是个很大的负数），
 *   看起来就是「字幕不见了」，而且文本层一切正常；
 * - `size = DIMEN_UNSET` 直接当宽度用 → 宽度约等于 0 → 同样什么都不画，
 *   和「容器真的写了 0」长得一模一样。
 *
 * 所以这里逐项钉住「哨兵 → 0 / null」的翻译，包括 [Cue.DIMEN_UNSET] 与 `NaN`
 * 两种坏值，以及锚点里没见过的整数（认不出来时只能当 `START`）。
 *
 * ## 只测一半是故意的
 *
 * [Cue.toBitmapCueSpec] 不碰 `Bitmap`，能在宿主机 JVM 上跑；
 * [Cue.toEmbeddedBitmapCue] 要一张真的位图，而 `Bitmap` 在单测里构造不出来
 * （`android.jar` 的空壳让它恒为 null）。所以这里只断言「没有位图就翻不出位图字幕」
 * 这一半——另一半（有位图就翻得出来）靠模拟器上真的放一份 `.sup` 验。
 *
 * 另一个绕不开的约束也在这一点上：`Cue` 硬性要求 `text` 与 `bitmap` **至少一个非空**
 * （`checkNotNull(text)`），而宿主机上 `bitmap` 永远是 null——所以这里每一条 cue
 * 都得挂一句占位文本，见 [cueBuilder]。
 */
class BitmapCuesTest {

    /**
     * 造一个「只有尺寸信息、没有内容」的 cue。
     *
     * 占位文本不是可选的：Media3 的 `Cue` 构造器要求 `text` 与 `bitmap` 至少一个非空，
     * 而单测里造不出 `Bitmap`（`android.jar` 是空壳，`createBitmap` 恒返回 null）。
     * 也就是说**宿主机上根本不存在「text 和 bitmap 都空」的 cue**——真实位图 cue 的
     * `text` 确实是 null，但那种 cue 只能在设备上出现。反正 [Cue.toBitmapCueSpec]
     * 不看 `text`，占位文本不会影响这里的任何断言。
     */
    private fun cueBuilder(): Cue.Builder = Cue.Builder().setText("placeholder")

    @Test
    fun `什么都没写时位置折成零 尺寸留空`() {
        // 一个只有文本的 `Cue` 走这条翻译时不该留下任何负的哨兵值。
        val spec = cueBuilder().build().toBitmapCueSpec()

        assertEquals(0f, spec.position, 0f)
        assertEquals(0f, spec.line, 0f)
        assertEquals(CueAnchor.START, spec.positionAnchor)
        assertEquals(CueAnchor.START, spec.lineAnchor)
        assertNull("`size` 是「容器写没写」，不是「0 还是别的值」", spec.size)
        assertNull(spec.bitmapHeight)
    }

    @Test
    fun `写了的比例和锚点原样搬过来`() {
        val spec = cueBuilder()
            .setPosition(0.25f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_END)
            .setLine(0.75f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.5f)
            .setBitmapHeight(0.125f)
            .build()
            .toBitmapCueSpec()

        assertEquals(0.25f, spec.position, 0f)
        assertEquals(CueAnchor.END, spec.positionAnchor)
        assertEquals(0.75f, spec.line, 0f)
        assertEquals(CueAnchor.MIDDLE, spec.lineAnchor)
        assertEquals(0.5f, spec.size)
        assertEquals(0.125f, spec.bitmapHeight)
    }

    @Test
    fun `没写过的尺寸保留 null 而不是折成零`() {
        // 「容器写了 0」和「容器没写」在**摆放结果**上是一回事（都是零矩形），
        // 但在**数据**上是两回事：折成 0 之后没人分得清「这条 cue 有宽度信息」
        // 还是「这条 cue 的信息丢了」。排查「为什么这份 pgs 画不出来」时
        // 这两种原因的排查方向完全不同（前者看片源，后者看解析），
        // 所以清单一律按「null = 没写」保留。
        assertNull("没写 → null", cueBuilder().build().toBitmapCueSpec().size)
        assertEquals(
            "写了 0 → 就是 0",
            0f,
            cueBuilder().setSize(0f).build().toBitmapCueSpec().size,
        )
    }

    @Test
    fun `锚点认不出来时一律按起点`() {
        // 锚点没有「没写过」的哨兵值：Media3 的 `Cue` 只定义了
        // `ANCHOR_TYPE_START` = 0 / `MIDDLE` = 1 / `END` = 2，默认值就是 0（起点）。
        // 所以要防的是**没见过的值**：容器或新版 Media3 给一个 3，`when` 必须落到
        // `else` 而不是抛异常，也不该猜成「中间」（猜错就是整块字幕横移半个屏）。
        assertEquals(
            CueAnchor.START,
            cueBuilder().setPositionAnchor(Cue.ANCHOR_TYPE_START).build()
                .toBitmapCueSpec().positionAnchor,
        )
        assertEquals(
            CueAnchor.START,
            cueBuilder().setLineAnchor(99).build().toBitmapCueSpec().lineAnchor,
        )
        assertEquals(
            CueAnchor.END,
            cueBuilder().setPositionAnchor(Cue.ANCHOR_TYPE_END).build()
                .toBitmapCueSpec().positionAnchor,
        )
    }

    @Test
    fun `坏掉的浮点值折成零`() {
        // `NaN` 与 `DIMEN_UNSET` 是两种坏法：前者的后果是 Kotlin 的 `roundToInt()`
        // 抛异常（Java 的 `Math.round` 只返回 0），后者是一个很大的负数
        // （乘上画面宽就是「摆到屏幕左边十万八千里」）。两者都必须变成「这一项没有信息」。
        val spec = cueBuilder()
            .setPosition(Float.NaN)
            .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
            .setBitmapHeight(Cue.DIMEN_UNSET)
            .build()
            .toBitmapCueSpec()

        assertEquals(0f, spec.position, 0f)
        assertEquals(0f, spec.line, 0f)
        assertNull("哨兵值不能被当成一个真的高度", spec.bitmapHeight)
    }

    @Test
    fun `尺寸的哨兵值认的就是 media3 那个常量`() {
        // 这条看着像废话，钉的却是一件真事：我们对「没写过」的判断比的是
        // `Cue.DIMEN_UNSET` 这个常量，而不是自己抄一个 `-3.4e38` 之类的字面量。
        // 用字面量的版本在 Media3 换掉这个值之后会静默地把所有哨兵值当成真值
        // （每一项都变成「容器写了」），而屏幕上只表现为「字幕位置全错」。
        assertNull(
            "`DIMEN_UNSET` 是「没写过」，不是「一个很小很小的高度」",
            cueBuilder().setSize(Cue.DIMEN_UNSET).build().toBitmapCueSpec().size,
        )
        assertNull(
            cueBuilder().setBitmapHeight(Cue.DIMEN_UNSET).build()
                .toBitmapCueSpec().bitmapHeight,
        )
    }

    @Test
    fun `没有位图的 cue 翻不出位图字幕`() {
        // `bitmap ?: return null` 这一行是文本层与位图层之间的分界：
        // Media3 送来的文本 cue 里 `bitmap` 是 null，它不该出现在位图层里
        // （否则位图层会画一个空矩形，而文本层反而被挤掉）。
        assertNull(cueBuilder().build().toEmbeddedBitmapCue())
        assertNull(cueBuilder().setBitmapHeight(0.1f).build().toEmbeddedBitmapCue())
    }

    @Test
    fun `整包转换只留下有位图的那几条`() {
        // `onCues` 拿到的是混着来的 list，这里必须**过滤**而不是提前返回：
        // 一条轨里只要出现过一张位图，整包就都得走一遍筛选。
        val cues = listOf(
            Cue.Builder().setText("line 1").build(),
            Cue.Builder().setText("line 2").build(),
        )

        assertEquals(emptyList<EmbeddedBitmapCue>(), cues.toEmbeddedBitmapCues())
    }

    @Test
    fun `位图字幕的状态默认是空的`() {
        // 默认值承重：`PlayerScreen` 在「这条轨不是位图轨」时会读到默认的那个对象，
        // 它必须是空列表而不是 null，否则每一帧都要多一个判空分支。
        assertEquals(emptyList<EmbeddedBitmapCue>(), EmbeddedBitmapState().cues)
    }
}
