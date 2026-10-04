package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均衡器控制器的测试。
 *
 * 这里测的不是「有没有声音变好」——那测不出来——而是**接线和生命周期**：
 * 会话号来之前不建效果、会话变了要重建并把设置重新落一遍、同一个值重复上报
 * 不许重建（重建会让声音断一下）、设备不支持时安静退化。
 *
 * 真效果在 JVM 单测里建不出来（要 Binder 和音频服务），所以效果和会话号来源
 * 都是假实现。假实现要**照着真实现的契约写**（比如 [FakeSessionObserver]
 * 也会在建订阅时立刻回报一次当前值，真实现里
 * [PlayerAudioSessionObserver.observe] 就是这么干的），否则测的是一套
 * 只有测试里才成立的时序。
 */
class EqualizerControllerTest {

    private val fiveBand = EqualizerCapability(
        centerFreqHz = listOf(60, 230, 910, 3_600, 14_000),
        minGainDb = -15f,
        maxGainDb = 15f,
    )

    @Test
    fun `还没开始播放时不建效果`() {
        // 构造时机由 Koin 决定（应用启动/第一次进设置页），此时播放器可能还没
        // 有音频会话。这时候去建均衡器只会得到一个挂不上会话的空壳。
        val harness = Harness()
        harness.controller.start()
        assertTrue(harness.created.isEmpty())
        assertEquals(EqualizerStatus.Idle, harness.controller.status.value)
    }

    @Test
    fun `音频会话来了才建效果并读到能力`() {
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        assertEquals(1, harness.created.size)
        assertEquals(EqualizerStatus.Ready(fiveBand), harness.controller.status.value)
    }

    @Test
    fun `建不出效果的设备报不支持`() {
        val harness = Harness(capability = null)
        harness.controller.start()
        harness.observer.emit(42)
        assertEquals(EqualizerStatus.Unsupported, harness.controller.status.value)
    }

    @Test
    fun `设置先写每一段增益，最后才开开关`() {
        // 反过来的话会有一瞬间「开着、但用的是上一次的曲线」，听感上就是
        // 打开均衡器的那一下“咔”。顺序是这个函数唯一容易写错的地方。
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.BASS.curve))
        assertEquals(
            listOf("band:0=7.0", "band:1=4.0", "band:2=0.0", "band:3=0.0", "band:4=-1.0", "enabled:true"),
            harness.created.single().calls,
        )
    }

    @Test
    fun `设备段数比曲线少时只写设备有的段`() {
        val twoBand = EqualizerCapability(listOf(60, 230), -15f, 15f)
        val harness = Harness(capability = twoBand)
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.BASS.curve))
        assertEquals(listOf("band:0=7.0", "band:1=4.0", "enabled:true"), harness.created.single().calls)
    }

    @Test
    fun `增益夹进设备范围`() {
        val narrow = EqualizerCapability(listOf(60, 230, 910, 3_600, 14_000), -6f, 6f)
        val harness = Harness(capability = narrow)
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.BASS.curve))
        assertEquals(
            listOf("band:0=6.0", "band:1=4.0", "band:2=0.0", "band:3=0.0", "band:4=-1.0", "enabled:true"),
            harness.created.single().calls,
        )
    }

    @Test
    fun `还没开始播放就调整了设置，等会话来了也会生效`() {
        // 「打开面板 → 先拖滑块 → 才开始播放」是很常见的顺序，不能要求用户
        // 先放一首歌再回来调一遍。
        val harness = Harness()
        harness.controller.start()
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))
        assertTrue(harness.created.isEmpty())

        harness.observer.emit(42)
        val effect = harness.created.single()
        assertEquals(5, effect.calls.count { it.startsWith("band:") })
        assertEquals("enabled:true", effect.calls.last())
    }

    @Test
    fun `音频会话变了要重建效果并把设置重新落一遍`() {
        // 播放器重建（换片源、切解码模式）之后会话号会变，新效果是白纸一张：
        // 上一次写进去的增益不会跟过来。不重写的话表现是「换了个片子均衡器
        // 就失效了」，而面板上还显示着用户那条曲线。
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))

        harness.observer.emit(43)
        assertEquals(2, harness.created.size)
        assertTrue(harness.created[0].released)
        val second = harness.created[1]
        assertEquals(5, second.calls.count { it.startsWith("band:") })
        assertEquals("enabled:true", second.calls.last())
    }

    @Test
    fun `同一个会话号重复上报不重建效果`() {
        // 部分设备会重复上报同一个值，而重建效果会让声音断一下——
        // 那个断音比「开着均衡器」明显得多。
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))
        harness.observer.emit(42)
        assertEquals(1, harness.created.size)
        assertFalse(harness.created.single().released)
    }

    @Test
    fun `会话号回到 0 时不再往旧效果上写`() {
        // 播放结束/播放器释放之后会话号会回到 0。这时候设置还留着（用户下次
        // 播放要接着生效），但不能再往一个已经失效的会话上写东西。
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        harness.observer.emit(0)
        assertEquals(EqualizerStatus.Idle, harness.controller.status.value)
        assertTrue(harness.created.single().released)

        // 旧效果已经释放了，不能再往它上面写。用户的设置留在内存里，
        // 等下一个会话来了再落一遍（下一个断言就是在验证这件事还成立）。
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))
        assertTrue(harness.created.single().calls.isEmpty())

        harness.observer.emit(45)
        assertEquals(2, harness.created.size)
        val fresh = harness.created[1]
        assertEquals(5, fresh.calls.count { it.startsWith("band:") })
        assertEquals("enabled:true", fresh.calls.last())
    }

    @Test
    fun `设备不支持时调整设置既不崩也不写任何东西`() {
        val harness = Harness(capability = null)
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))
        assertTrue(harness.created.single().calls.isEmpty())
        assertEquals(EqualizerStatus.Unsupported, harness.controller.status.value)
    }

    @Test
    fun `release 会取消订阅并释放效果`() {
        val harness = Harness()
        harness.controller.start()
        harness.observer.emit(42)
        harness.controller.apply(EqualizerRequest(enabled = true, curve = EqualizerPreset.ROCK.curve))

        harness.controller.release()
        assertTrue(harness.observer.cancelled)
        assertTrue(harness.created.single().released)
        assertEquals(EqualizerStatus.Idle, harness.controller.status.value)

        // 已经取消订阅了：之后再有人推会话号也不该冒出来一个新效果。
        harness.observer.emit(44)
        assertEquals(1, harness.created.size)
    }

    @Test
    fun `重复 start 只订阅一次`() {
        // ViewModel 重建（横竖屏切换）时 init 会再跑一遍，重复订阅会变成
        // 一个会话号建出两个效果，两个效果抢同一个会话。
        val harness = Harness()
        harness.controller.start()
        harness.controller.start()
        assertEquals(1, harness.observer.subscribeCount)
    }

    // ---- 假实现 -------------------------------------------------------------

    /** 一个由测试直接控制的会话号来源。契约与 [PlayerAudioSessionObserver] 一致。 */
    private class FakeSessionObserver : AudioSessionObserver {

        var cancelled = false
        var subscribeCount = 0

        private var callback: ((Int) -> Unit)? = null

        override fun observe(onSessionId: (Int) -> Unit): AutoCloseable {
            subscribeCount++
            callback = onSessionId
            // 真实现也是这么干的：装监听的同时主动读一次当前值。
            onSessionId(0)
            return AutoCloseable {
                cancelled = true
                callback = null
            }
        }

        fun emit(sessionId: Int) {
            callback?.invoke(sessionId)
        }
    }

    /**
     * 把每一次调用按顺序记下来，而不是分成几个列表：**顺序**本身就是要断言
     * 的东西（见 `设置先写每一段增益，最后才开开关`）。
     */
    private class FakeEffect(override val capability: EqualizerCapability?) : EqualizerEffect {

        val calls = mutableListOf<String>()
        var released = false

        override fun setEnabled(enabled: Boolean) {
            calls += "enabled:$enabled"
        }

        override fun setBandGain(band: Int, gainDb: Float) {
            calls += "band:$band=$gainDb"
        }

        override fun release() {
            released = true
        }
    }

    private class Harness(capability: EqualizerCapability? = DEFAULT_CAPABILITY) {

        val created = mutableListOf<FakeEffect>()
        val observer = FakeSessionObserver()

        val controller = EqualizerController(observer) { sessionId ->
            check(sessionId != 0)
            FakeEffect(capability).also { created += it }
        }

        companion object {
            private val DEFAULT_CAPABILITY = EqualizerCapability(
                centerFreqHz = listOf(60, 230, 910, 3_600, 14_000),
                minGainDb = -15f,
                maxGainDb = 15f,
            )
        }
    }
}
