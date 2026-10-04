package com.multisuperplayer.core.player

import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 要应用的一套均衡器设置：开不开，以及用哪条曲线。
 *
 * 「曲线」而不是「第几段的增益」：见 [EqualizerBandGain] 的说明——存频率才跨设备。
 */
data class EqualizerRequest(val enabled: Boolean, val curve: List<EqualizerBandGain>)

/**
 * 均衡器当前处于哪种状态。
 *
 * 三种状态必须是三种形状，不能压成「`capability` 是不是 null」：
 *
 * - [Idle]：还没开始播放，ExoPlayer 的音频会话号还是 0。这时**什么都不知道**，
 *   界面要说「开始播放后可用」，而不是「你的设备不支持」——后者会让人以为
 *   这台手机就是不行，实际上是面板开早了。
 * - [Ready]：能用了，界面照着 [Ready.capability] 画。
 * - [Unsupported]：会话有了、但均衡器建不出来（驱动不支持、被别的应用占满
 *   效果槽位……）。这时才该说「不支持」。
 */
sealed interface EqualizerStatus {

    /** 还没有音频会话（没开始播放）。 */
    data object Idle : EqualizerStatus

    /** 可用。 */
    data class Ready(val capability: EqualizerCapability) : EqualizerStatus

    /** 这台设备/这个会话上建不出均衡器。 */
    data object Unsupported : EqualizerStatus
}

/**
 * 对系统均衡器（`android.media.audiofx.Equalizer`）的最小抽象。
 *
 * 抽出来的理由和这一层的其他抽象一样：真正的实现在 JVM 单测里跑不起来
 * （`AudioEffect` 的构造函数要 Binder 和音频服务），而**「会话换了要重建、
 * 设备不支持要安静降级、应用设置的顺序」这些判定**是可以测的，它们也恰恰
 * 是最容易写错的地方。
 */
interface EqualizerEffect {

    /** 设备能力；不支持时 `null`。 */
    val capability: EqualizerCapability?

    /** 开/关。 */
    fun setEnabled(enabled: Boolean)

    /** 设置第 [band] 段的增益（dB），[band] 是本效果自己的段下标。 */
    fun setBandGain(band: Int, gainDb: Float)

    /** 释放。可以重复调用。 */
    fun release()
}

/**
 * 观察「当前音频会话是哪一个」。
 *
 * 均衡器必须挂在一个具体的音频会话上，而会话号在播放开始之前是 0、在播放器
 * 重建之后会变。这一层把它抽象成「订阅 + 回调」：真正的实现（
 * [PlayerAudioSessionObserver]）去听 media3 的 `Player.Listener`，
 * 单测里就是一个可以直接喂值的假实现。
 */
fun interface AudioSessionObserver {

    /**
     * 开始观察，并**立刻**用当前会话号回调一次（可能是 0）。
     *
     * @return 取消观察的方法。重复调用必须是安全的。
     */
    fun observe(onSessionId: (Int) -> Unit): AutoCloseable
}

/**
 * 从 [PlaybackController] 的播放器上读会话号，并跟着它变。
 */
class PlayerAudioSessionObserver(private val playback: PlaybackController) : AudioSessionObserver {

    override fun observe(onSessionId: (Int) -> Unit): AutoCloseable {
        val player = playback.player
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                onSessionId(audioSessionId)
            }
        }
        player.addListener(listener)
        // 播放器可能早就开始播了（监听是在面板第一次打开时才装上的），
        // 所以这里必须主动读一次当前值，不能只等回调。
        onSessionId(player.audioSessionId)
        return AutoCloseable { player.removeListener(listener) }
    }
}

/**
 * 均衡器：把用户设置落到真实的音频会话上。
 *
 * ## 归属
 *
 * 它跟着**播放内核**（`:core:player`）走，而不是播放窗口/界面的生命周期：
 * `ExoPlayer` 是进程级单例，均衡器挂在它的音频会话上，用户退出播放页之后
 * 音乐还在放，均衡器也还得在。反过来，把它塞进 `PlayerWindowController`
 * （那个只管窗口亮度/系统音量）就会出现「划掉播放页之后音效没了」。
 *
 * ## 为什么实例化的时候不建效果
 *
 * 因为均衡器需要音频会话号，而会话号在**开始播放之前不存在**（是 0）。构造
 * 时机由外部决定（Koin），跟播放状态无关，所以真正的 attach 发生在
 * [start] 里、以及每次会话号变化时。
 *
 * ## 设备不支持时安静降级
 *
 * `AudioEffect` 在模拟器、部分驱动、效果槽位被占满的设备上会抛异常。整个功能
 * 在这些设备上的正确行为是「面板上说一句不支持」，而不是崩掉播放页面——
 * 所以每一次和效果打交道都夹在 try/catch 里（见 [AndroidEqualizerEffect]）。
 */
class EqualizerController(
    private val observer: AudioSessionObserver,
    private val createEffect: (audioSessionId: Int) -> EqualizerEffect = { AndroidEqualizerEffect(it) },
) {

    private val _status = MutableStateFlow<EqualizerStatus>(EqualizerStatus.Idle)

    /** 当前状态，界面照着它决定是画控件还是画一句说明。 */
    val status: StateFlow<EqualizerStatus> = _status.asStateFlow()

    private var effect: EqualizerEffect? = null
    private var attachedSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var subscription: AutoCloseable? = null
    private var request: EqualizerRequest? = null

    /**
     * 开始跟着播放器走。
     *
     * 由界面层在 ViewModel 初始化时调用。重复调用是安全的。
     */
    fun start() {
        if (subscription != null) return
        subscription = observer.observe(::attachTo)
    }

    /**
     * 应用一套设置，并记住它（之后音频会话变了、重建效果时会重新应用）。
     *
     * 效果还没建出来（[EqualizerStatus.Idle]）时只是记住：这样「打开面板 →
     * 调整 → 才开始播放」也能生效，而不是要用户先放一首再回来调一遍。
     */
    fun apply(request: EqualizerRequest) {
        this.request = request
        applyToEffect()
    }

    /** 停止跟着播放器走并释放效果。对进程级单例来说通常用不上，给测试和收尾用。 */
    fun release() {
        subscription?.close()
        subscription = null
        releaseEffect()
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
        _status.value = EqualizerStatus.Idle
    }

    /**
     * 音频会话变了（第一次播放 / 播放器重建）。
     *
     * 会话号没变而且效果还在的话直接返回：`onAudioSessionIdChanged` 在某些设备上
     * 会重复上报同一个值，而重建效果会让声音**断一下**（效果要重新挂到
     * AudioTrack 上），那种断音比「开着均衡器」明显得多。
     */
    private fun attachTo(audioSessionId: Int) {
        if (audioSessionId == attachedSessionId && effect != null) return
        releaseEffect()
        attachedSessionId = audioSessionId
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) {
            _status.value = EqualizerStatus.Idle
            return
        }
        val created = createEffect(audioSessionId)
        effect = created
        val capability = created.capability
        _status.value = if (capability == null) {
            EqualizerStatus.Unsupported
        } else {
            EqualizerStatus.Ready(capability)
        }
        // 新会话上原来那套设置要重新落一遍：效果是新建的，上一次的增益不会跟过来。
        applyToEffect()
    }

    private fun applyToEffect() {
        val effect = effect ?: return
        val capability = (_status.value as? EqualizerStatus.Ready)?.capability ?: return
        val request = request ?: return
        // 先写每一段的增益、最后才开开关：反过来的话会有一个极短的瞬间
        // 「开着，但用的是上一次的曲线」，听感上就是开均衡器的那一下“咔”。
        val gains = EqualizerCurve.toDeviceGains(request.curve, capability)
        gains.forEachIndexed { band, gainDb -> effect.setBandGain(band, gainDb) }
        effect.setEnabled(request.enabled)
    }

    private fun releaseEffect() {
        effect?.release()
        effect = null
    }
}

/**
 * 真正调用系统均衡器的那一层。
 *
 * 每一处和 `AudioEffect` 打交道的地方都包了 try/catch：
 *
 * - 构造会抛（`UnsupportedOperationException` / 各种 `RuntimeException`）；
 * - 释放之后再调用会抛 `IllegalStateException`；
 * - 部分设备的 `getCenterFreq` / `bandLevelRange` 在某些段上抛 `IllegalArgumentException`。
 *
 * 全都是 `RuntimeException` 的子类，所以这里只写一个 catch。这个功能的失败模式
 * **必须是「没效果」而不是「崩播放页」**：用户放着一部片子，不该因为均衡器不支持
 * 就中断播放。
 */
internal class AndroidEqualizerEffect(audioSessionId: Int) : EqualizerEffect {

    private var effect: Equalizer? = try {
        // priority = 0：不跟别的应用抢效果链的优先级（抢到别人的会把人家的音效踢掉）。
        Equalizer(0, audioSessionId)
    } catch (e: RuntimeException) {
        null
    }

    override val capability: EqualizerCapability? = readCapability()

    private fun readCapability(): EqualizerCapability? {
        val effect = effect ?: return null
        return try {
            val range = effect.bandLevelRange
            if (range == null || range.size < 2) return null
            val bands = effect.numberOfBands.toInt()
            if (bands <= 0) return null
            val freqs = (0 until bands).map { band -> effect.getCenterFreq(band.toShort()) / 1_000 }
            if (freqs.isEmpty() || freqs.any { it <= 0 }) return null
            EqualizerCapability(
                centerFreqHz = freqs,
                // 设备报的是毫贝（millibel），1dB = 100mB。
                minGainDb = range[0] / 100f,
                maxGainDb = range[1] / 100f,
            ).takeIf { it.isUsable }
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val effect = effect ?: return
        try {
            effect.setEnabled(enabled)
        } catch (e: RuntimeException) {
            // 忽略：开关失败的最坏结果是「声音和设置不一致」，而下一次应用
            // 设置还会再试一遍。这里没有上报渠道，用户能做的也只是关掉它。
        }
    }

    override fun setBandGain(band: Int, gainDb: Float) {
        val effect = effect ?: return
        try {
            // 设备认的是毫贝的 Short，所以先乘 100 再四舍五入：
            // 直接 toShort() 是截断，会把 +6.9dB 变成 +6.8dB。
            effect.setBandLevel(band.toShort(), (gainDb * 100f).roundToInt().toShort())
        } catch (e: RuntimeException) {
            // 同上：越界的段下标/不支持的增益值只会让这一段不生效。
        }
    }

    override fun release() {
        try {
            effect?.release()
        } catch (e: RuntimeException) {
            // 已经释放过或者底层服务先一步消失了，都属于正常情况。
        }
        effect = null
    }
}
