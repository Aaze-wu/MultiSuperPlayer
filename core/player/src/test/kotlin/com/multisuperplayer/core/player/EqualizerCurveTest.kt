package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均衡器曲线算术的测试。
 *
 * 这里测的是**这台设备的段数和 5 段预设对不上时会发生什么**、以及**设置被写坏
 * 之后还能不能打开面板**这两类事——它们都只在特定设备/特定数据上才出现，
 * 而在真机上复现的成本极高（要凑齐一台 10 段设备、还要手工把 DataStore 改坏）。
 */
class EqualizerCurveTest {

    /** 常见手机的 5 段能力：±15dB，频率点正好和我们的预设一样。 */
    private val fiveBand = EqualizerCapability(
        centerFreqHz = listOf(60, 230, 910, 3_600, 14_000),
        minGainDb = -15f,
        maxGainDb = 15f,
    )

    /** 10 段设备：频率点比预设密。 */
    private val tenBand = EqualizerCapability(
        centerFreqHz = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000),
        minGainDb = -15f,
        maxGainDb = 15f,
    )

    /** 增益范围比我们的 ±12 窄的设备。 */
    private val narrowBand = fiveBand.copy(minGainDb = -6f, maxGainDb = 6f)

    @Test
    fun `平直曲线到哪儿都是 0dB`() {
        val flat = EqualizerCurve.flat()
        EqualizerCurve.STANDARD_FREQS_HZ.forEach {
            assertEquals(0f, EqualizerCurve.gainAt(flat, it), 0f)
        }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), EqualizerCurve.toDeviceGains(flat, fiveBand))
    }

    @Test
    fun `端点之外维持端点值，不外推`() {
        // 外推是最容易写错、也最容易被发现不了的地方：20Hz 处如果按 60→230
        // 那段斜率外推，低音增强预设会得到一个比用户调过的那一段还要猛的增益，
        // 而手机上根本没人会去放 20Hz 的信号来验证它。
        val bass = EqualizerPreset.BASS.curve
        assertEquals(7f, EqualizerCurve.gainAt(bass, 20), 0f)
        assertEquals(7f, EqualizerCurve.gainAt(bass, 0), 0f)
        assertEquals(-1f, EqualizerCurve.gainAt(bass, 20_000), 0f)
    }

    @Test
    fun `段与段之间按频率线性插值`() {
        // 60Hz 处 +6dB、230Hz 处 0dB 的话，145Hz（正好中间）应该是 +3dB。
        val curve = listOf(EqualizerBandGain(60, 6f), EqualizerBandGain(230, 0f))
        assertEquals(3f, EqualizerCurve.gainAt(curve, 145), 0f)
        assertEquals(6f, EqualizerCurve.gainAt(curve, 60), 0f)
        assertEquals(0f, EqualizerCurve.gainAt(curve, 230), 0f)
    }

    @Test
    fun `空曲线和平直曲线等价`() {
        // 自定义曲线被清空、或者还没设置过的时候，取值必须是 0dB 而不是崩。
        assertEquals(0f, EqualizerCurve.gainAt(emptyList(), 1_000), 0f)
        assertTrue(EqualizerCurve.sameAs(emptyList(), EqualizerCurve.flat()))
    }

    @Test
    fun `单段曲线整体取同一个值`() {
        val one = listOf(EqualizerBandGain(1_000, 4f))
        assertEquals(4f, EqualizerCurve.gainAt(one, 60), 0f)
        assertEquals(4f, EqualizerCurve.gainAt(one, 14_000), 0f)
    }

    @Test
    fun `预设曲线贴到 10 段设备上还是同一条曲线`() {
        // 这是「用频率而不是用下标存曲线」这件事的**唯一目的**：设备段数不同时
        // 取值要落回同一个形状，而不是变成另一条曲线。
        val gains = EqualizerCurve.toDeviceGains(EqualizerPreset.BASS.curve, tenBand)
        assertEquals(tenBand.bandCount, gains.size)
        // 62Hz 落在 60(7dB) 和 230(4dB) 之间，插出来应当比两端都更靠近 7。
        assertTrue("62Hz 处应当接近 7dB，实际 ${gains[1]}", gains[1] > 6f)
        // 16kHz 在最后一端之外，取端点值 -1dB。
        assertEquals(-1f, gains.last(), 0f)
    }

    @Test
    fun `增益夹进设备范围`() {
        // 设备只给 ±6dB 时，用户拖到 +12 必须被夹到 +6：不夹的话滑块数字在变、
        // 声音不变，真机上极难认出来是这个原因。
        val gains = EqualizerCurve.toDeviceGains(EqualizerPreset.BASS.curve, narrowBand)
        assertEquals(listOf(6f, 4f, 0f, 0f, -1f), gains)
        assertEquals(6f, EqualizerCurve.clampGain(100f, narrowBand), 0f)
        assertEquals(-6f, EqualizerCurve.clampGain(-100f, narrowBand), 0f)
    }

    @Test
    fun `滑块的区间是两边范围的交集`() {
        val range = EqualizerCurve.gainRange(fiveBand)!!
        assertEquals(-12f, range.start, 0f)
        assertEquals(12f, range.endInclusive, 0f)

        val narrow = EqualizerCurve.gainRange(narrowBand)!!
        assertEquals(-6f, narrow.start, 0f)
        assertEquals(6f, narrow.endInclusive, 0f)
    }

    @Test
    fun `没有段数或者范围是空的设备视为不支持`() {
        assertNull(EqualizerCurve.gainRange(fiveBand.copy(centerFreqHz = emptyList())))
        assertNull(EqualizerCurve.gainRange(fiveBand.copy(minGainDb = 0f, maxGainDb = 0f)))
        assertNull(EqualizerCurve.gainRange(fiveBand.copy(minGainDb = 6f, maxGainDb = -6f)))
        assertFalse(fiveBand.copy(minGainDb = 0f, maxGainDb = 0f).isUsable)
        assertTrue(fiveBand.isUsable)
    }

    @Test
    fun `非有限增益当成 0dB`() {
        assertEquals(0f, EqualizerCurve.clampGain(Float.NaN, fiveBand), 0f)
        assertEquals(0f, EqualizerCurve.clampGain(Float.POSITIVE_INFINITY, fiveBand), 0f)
    }

    @Test
    fun `编解码一来一回还是同一条曲线`() {
        val curve = EqualizerPreset.ROCK.curve
        assertEquals(curve, EqualizerCurve.decode(EqualizerCurve.encode(curve)))
    }

    @Test
    fun `写坏的数据一律当没有自定义曲线`() {
        // 这条路径上抛异常等于「升级之后播放页打不开」，所以任何读不出来的东西
        // 都回落到预设。逐项检查：只坏一项也必须作废整条——悄悄丢掉坏的那一项
        // 会让用户看到一条少了一段、但看起来完全正常的曲线。
        assertNull(EqualizerCurve.decode(null))
        assertNull(EqualizerCurve.decode(""))
        assertNull(EqualizerCurve.decode("   "))
        assertNull(EqualizerCurve.decode("垃圾"))
        assertNull(EqualizerCurve.decode("60:"))
        assertNull(EqualizerCurve.decode("60:abc"))
        assertNull(EqualizerCurve.decode(":3"))
        assertNull(EqualizerCurve.decode("0:3"))
        assertNull(EqualizerCurve.decode("-60:3"))
        assertNull(EqualizerCurve.decode("60:3,230:NaN"))
        assertNull(EqualizerCurve.decode("60:3,坏数据"))
    }

    @Test
    fun `解码会排序并去掉重复频率`() {
        // 前提是 `gainAt` 要求升序，而字符串是外部数据，必须在这里规整好。
        val decoded = EqualizerCurve.decode("230:4,60:6,230:5")!!
        assertEquals(listOf(60, 230), decoded.map { it.centerFreqHz })
        assertEquals(5f, decoded.last().gainDb, 0f)
    }

    @Test
    fun `自定义曲线匹配不上任何预设`() {
        val custom = EqualizerCurve.of(1f, 2f, 3f, 2f, 1f)
        assertNull(EqualizerPreset.matching(custom))
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.matching(EqualizerPreset.ROCK.curve))
        // 平直曲线是合法的预设，不是「自定义」。
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.matching(EqualizerCurve.flat()))
    }

    @Test
    fun `认不出来的预设 id 回落默认值`() {
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromId(null))
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromId(""))
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromId("不存在的预设"))
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.fromId("rock"))
    }

    @Test
    fun `预设 id 不重复`() {
        // id 是持久化契约，两份预设共用一个 id 的话，用户存下来的那个值到底指哪一条
        // 就取决于遍历顺序——这种 bug 的表现是「重启之后声音变了」。
        val ids = EqualizerPreset.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(EqualizerPreset.entries.all { it.curve.isNotEmpty() })
    }

    @Test
    fun `增益文字`() {
        assertEquals("0 dB", EqualizerCurve.formatGain(0f))
        assertEquals("0 dB", EqualizerCurve.formatGain(-0.04f))
        assertEquals("+6 dB", EqualizerCurve.formatGain(6f))
        assertEquals("-3.5 dB", EqualizerCurve.formatGain(-3.5f))
        assertEquals("-12 dB", EqualizerCurve.formatGain(-12f))
        // 不是一个有限数的时候也不能显示出「NaN dB」。
        assertEquals("0 dB", EqualizerCurve.formatGain(Float.NaN))
    }

    @Test
    fun `频率文字`() {
        assertEquals("60 Hz", EqualizerCurve.formatFreq(60))
        assertEquals("910 Hz", EqualizerCurve.formatFreq(910))
        assertEquals("3.6 kHz", EqualizerCurve.formatFreq(3_600))
        assertEquals("14 kHz", EqualizerCurve.formatFreq(14_000))
        assertEquals("2 kHz", EqualizerCurve.formatFreq(2_000))
        // 没有频率的设备（段数为 0 时读不到）不该显示成「0 kHz」。
        assertEquals("—", EqualizerCurve.formatFreq(0))
        assertEquals("—", EqualizerCurve.formatFreq(-1))
    }

    @Test
    fun `平直判定`() {
        assertTrue(EqualizerCurve.isFlat(EqualizerCurve.flat()))
        assertTrue(EqualizerCurve.isFlat(emptyList()))
        assertFalse(EqualizerCurve.isFlat(EqualizerPreset.BASS.curve))
    }

    @Test
    fun `重采样到标准频段后永远是那五段`() {
        // 面板上的滑块数量必须是个定数：跟着设备走的话，会话号到了之后面板会
        // 在用户眼皮底下从 5 根变成 10 根，而空曲线（从没设置过）那一路
        // 会连一根都画不出来。
        val empty = EqualizerCurve.onStandardBands(emptyList())
        assertEquals(EqualizerCurve.STANDARD_FREQS_HZ, empty.map { it.centerFreqHz })
        assertTrue(empty.all { it.gainDb == 0f })

        val ten = EqualizerCurve.onStandardBands(
            EqualizerCurve.fromDeviceGains(tenBand.centerFreqHz, List(tenBand.centerFreqHz.size) { 4f }),
        )
        assertEquals(EqualizerCurve.STANDARD_FREQS_HZ, ten.map { it.centerFreqHz })
        assertTrue(ten.all { it.gainDb == 4f })
    }

    @Test
    fun `重采样出来的曲线在标准频段上的取值和原曲线一致`() {
        // 这条是「拖第三根滑块，声音真的变第三段」的根据：重采样之后两条曲线
        // 在每个标准频率上的增益必须一样，否则界面画的和听得到的就错位了。
        val rocky = EqualizerPreset.ROCK.curve
        val resampled = EqualizerCurve.onStandardBands(rocky)
        assertTrue(EqualizerCurve.sameAs(rocky, resampled))
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.matching(resampled))

        // 本来就落在标准频段上的曲线，重采样是恒等变换（不是插值出来的近似值）。
        assertEquals(rocky, resampled)
    }
}
