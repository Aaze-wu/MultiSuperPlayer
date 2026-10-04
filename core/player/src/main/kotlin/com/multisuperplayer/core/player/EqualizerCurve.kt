package com.multisuperplayer.core.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一个频段的中心频率和它的增益。
 *
 * 用**频率**给频段命名，而不是「第几段」：同一个设置在一台 5 段设备上和一台
 * 10 段设备上必须还是同一条曲线（见 [EqualizerCurve.gainAt]），用下标存下来
 * 的话换台设备就变成了「第 3 段 +6dB」，而这台机器的第 3 段可能是 910Hz 也可能是
 * 1.2kHz——用户看到的界面数字完全一样，听到的声音却不一样。
 *
 * @param centerFreqHz 中心频率（Hz）。
 * @param gainDb 增益（dB，正数是提升）。
 */
data class EqualizerBandGain(val centerFreqHz: Int, val gainDb: Float)

/**
 * 设备上报的均衡器能力。
 *
 * 全都是 `AudioEffect.Equalizer` 在**这台设备上**告诉我们的，不能写死：
 * 段数（5 段是常见值，但也有 10 段甚至不支持的）、每段的中心频率、增益范围
 * （常见是 ±15dB）都因设备而异。界面必须照着这个对象画，否则会出现
 * 「能拖但拖了没反应」（值被设备夹掉了）。
 *
 * @param centerFreqHz 每一段的中心频率，升序。
 * @param minGainDb 设备允许的最小增益（dB）。
 * @param maxGainDb 设备允许的最大增益（dB）。
 */
data class EqualizerCapability(
    val centerFreqHz: List<Int>,
    val minGainDb: Float,
    val maxGainDb: Float,
) {
    /** 段数。 */
    val bandCount: Int get() = centerFreqHz.size

    /**
     * 这台设备/这个音频会话上均衡器**能不能用**。
     *
     * 不光是「有段数」：增益范围上下界相同（或者反过来）的驱动我们也见过，那种
     * 情况下界面画出来是一条谁也拖不动的滑块，不如直接说「这台设备不支持」。
     */
    val isUsable: Boolean get() = bandCount > 0 && maxGainDb > minGainDb
}

/**
 * 增益曲线的算术。
 *
 * 这一层是纯的（不碰 `android.media.audiofx`、不碰资源），所以它是这套功能里
 * **唯一被单测覆盖**的部分：插值、夹取、编解码、文字格式化都在这里定死，
 * 上面那层只负责「把算出来的值递给系统」。
 */
object EqualizerCurve {

    /**
     * 界面允许的增益下界/上界（dB）。
     *
     * 这是**我们希望**的范围，实际能拖到哪儿还要和设备上报的范围取交集
     * （[gainRange]）：±12dB 已经足够把一副普通耳机调出明显差别，而 ±15dB
     * 全开时手机的扬声器基本只会破音。
     */
    const val UI_MIN_GAIN_DB = -12f
    const val UI_MAX_GAIN_DB = 12f

    /**
     * 组预设曲线用的频率点。
     *
     * 用 Android 上最常见的 5 段中心频率。预设只是「一条曲线」，真正要下发给
     * 设备的每一段增益是拿这条曲线在**设备自己的**中心频率上取值算出来的
     * （见 [toDeviceGains]），所以这里用哪几个点不影响正确性。
     */
    val STANDARD_FREQS_HZ: List<Int> = listOf(60, 230, 910, 3_600, 14_000)

    /** 平直曲线（全部 0dB）。 */
    fun flat(): List<EqualizerBandGain> = STANDARD_FREQS_HZ.map { EqualizerBandGain(it, 0f) }

    /**
     * 按 [STANDARD_FREQS_HZ] 的顺序，把一串增益拼成曲线。
     *
     * 长度不对时少的那头不补、多的那部分忽略——预设表里手写 5 个数，写错个数
     * 只是少一段，不该在类初始化的时候把应用崩掉。
     */
    fun of(vararg gainDb: Float): List<EqualizerBandGain> =
        STANDARD_FREQS_HZ.zip(gainDb.toList()) { freq, gain -> EqualizerBandGain(freq, gain) }

    /**
     * 曲线在 [centerFreqHz] 处的增益。
     *
     * 曲线两端的频率之外一律取端点的值（不外推）：外推一条 60Hz 起头的曲线到
     * 20Hz 会得到一个没人验证过的数字，而端点值至少是用户自己调的那一段。
     *
     * 段与段之间**按频率线性**插值，而不是按对数频率。真的要较真，听觉上的
     * 「中间」是对数频率上的中间（60Hz 和 230Hz 之间大约在 117Hz），但这一步
     * 只在「把预设曲线贴到一台频率点不一样的设备上」时才用得上，误差是几 Hz
     * 的事，换来的是这个函数能被一眼看懂。
     *
     * 前提：曲线的频率是升序的。外部进来的曲线都会先过 [normalize]
     * （[decode] 里做了），预设表也是手写升序。
     */
    fun gainAt(curve: List<EqualizerBandGain>, centerFreqHz: Int): Float {
        if (curve.isEmpty()) return 0f
        if (curve.size == 1) return curve.first().gainDb
        val first = curve.first()
        if (centerFreqHz <= first.centerFreqHz) return first.gainDb
        val last = curve.last()
        if (centerFreqHz >= last.centerFreqHz) return last.gainDb
        for (index in 1 until curve.size) {
            val high = curve[index]
            if (centerFreqHz <= high.centerFreqHz) {
                val low = curve[index - 1]
                val span = (high.centerFreqHz - low.centerFreqHz).toFloat()
                if (span <= 0f) return high.gainDb
                val ratio = (centerFreqHz - low.centerFreqHz) / span
                return low.gainDb + (high.gainDb - low.gainDb) * ratio
            }
        }
        return last.gainDb
    }

    /**
     * 把任意一条曲线重采样到标准频段上（也就是界面上固定画的那几段）。
     *
     * ## 为什么需要它
     *
     * 界面上画几根滑块必须是一个**定数**：如果画的是「设备上报的那几段」，那么
     * `Idle`（还没开始播放、还不知道设备有几段）时画不出来，只能等会话号到了
     * 才画——面板会在用户眼皮底下从 5 根变成 10 根。而存下来的曲线也未必正好
     * 落在标准频率上（换个设备写进去的、或者老版本留下的），直接用下标去对
     * 就会出现「拖第三根，声音变的是第五段」。
     *
     * 重采样之后，界面下标和曲线下标永远是同一件事，而设备那边的差异由
     * [toDeviceGains] 负责抹平。
     *
     * 空曲线（= 平直）重采样出来是 [flat]，不是空表：界面永远拿得到 5 个值。
     */
    fun onStandardBands(curve: List<EqualizerBandGain>): List<EqualizerBandGain> =
        STANDARD_FREQS_HZ.map { EqualizerBandGain(it, gainAt(curve, it)) }

    /**
     * 界面上增益滑块能拖到的区间：我们自己的 ±12dB 和设备上报范围的**交集**。
     *
     * 区间是空的时候（设备上报的两个边界一样大）返回 `null`，调用方据此把整块
     * 界面收掉。不取交集的话会出现「滑块能拖到 +12，但设备只认到 +6」——
     * 用户看到数字在变、声音不变，这种问题在真机上极其难认出来。
     */
    fun gainRange(capability: EqualizerCapability): ClosedFloatingPointRange<Float>? {
        if (!capability.isUsable) return null
        val min = maxOf(capability.minGainDb, UI_MIN_GAIN_DB)
        val max = minOf(capability.maxGainDb, UI_MAX_GAIN_DB)
        if (!min.isFinite() || !max.isFinite() || max <= min) return null
        return min..max
    }

    /** 把 [gainDb] 夹进设备范围。非有限值当 0dB 处理。 */
    fun clampGain(gainDb: Float, capability: EqualizerCapability): Float {
        if (!gainDb.isFinite()) return 0f
        val range = gainRange(capability) ?: return 0f
        return gainDb.coerceIn(range.start, range.endInclusive)
    }

    /**
     * 把一条曲线落到设备实际的每一段上。
     *
     * 返回值与 [EqualizerCapability.centerFreqHz] 一一对应，已经夹进设备范围，
     * 可以直接喂给 `Equalizer.setBandLevel`。
     */
    fun toDeviceGains(curve: List<EqualizerBandGain>, capability: EqualizerCapability): List<Float> =
        capability.centerFreqHz.map { clampGain(gainAt(curve, it), capability) }

    /**
     * 把设备的每一段增益读回来变成一条曲线（反向操作，用于「本来就在生效的设置」）。
     */
    fun fromDeviceGains(centerFreqHz: List<Int>, gainsDb: List<Float>): List<EqualizerBandGain> =
        centerFreqHz.zip(gainsDb) { freq, gain -> EqualizerBandGain(freq, gain) }

    /**
     * 两条曲线听起来是否一样（在 [STANDARD_FREQS_HZ] 上逐个比较，容差 [toleranceDb]）。
     *
     * 比的是**取值**而不是频点列表：存下来的是 5 段、设备是 10 段的时候，两者
     * 其实是同一条曲线，不该被判成「不一样」——判成不一样的话，用户每次打开面板
     * 都会看到自己选的预设被改成了「自定义」。
     */
    fun sameAs(a: List<EqualizerBandGain>, b: List<EqualizerBandGain>, toleranceDb: Float = 0.05f): Boolean {
        if (a.isEmpty() && b.isEmpty()) return true
        return STANDARD_FREQS_HZ.all { abs(gainAt(a, it) - gainAt(b, it)) <= toleranceDb }
    }

    /** 是不是一条平直曲线（全部在 0dB 附近）。 */
    fun isFlat(curve: List<EqualizerBandGain>, toleranceDb: Float = 0.05f): Boolean =
        curve.all { abs(it.gainDb) <= toleranceDb }

    /**
     * 持久化用的紧凑写法：`"60:6.0,230:4.0"`。
     *
     * 频率和增益写在一个字段里，而不是「一个频率表 + 一个增益表」两个字段：
     * 两个字段就有长度不一致的可能，而那种数据在读取时才会炸——那时已经没人
     * 记得是哪次写入弄坏的。
     */
    fun encode(curve: List<EqualizerBandGain>): String =
        curve.joinToString(",") { "${it.centerFreqHz}:${it.gainDb}" }

    /**
     * 还原 [encode] 写下的字符串。
     *
     * 认不出来一律返回 `null`（= 没有自定义曲线），**不抛异常**：这条路径上抛
     * 异常等于「升级之后播放页打不开」。任何一项解析失败就整条作废——把坏掉的
     * 那一项悄悄丢掉，用户看到的是一条少了某一段的曲线，而这完全看不出来。
     */
    fun decode(text: String?): List<EqualizerBandGain>? {
        if (text.isNullOrBlank()) return null
        val parsed = text.split(',').map { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return null
            val freq = parts[0].trim().toIntOrNull() ?: return null
            val gain = parts[1].trim().toFloatOrNull() ?: return null
            if (freq <= 0 || !gain.isFinite()) return null
            EqualizerBandGain(freq, gain)
        }
        if (parsed.isEmpty()) return null
        return normalize(parsed)
    }

    /**
     * 排序 + 去掉重复频率（同频率保留后出现的那个），让曲线满足 [gainAt] 的前提。
     */
    fun normalize(curve: List<EqualizerBandGain>): List<EqualizerBandGain> {
        val byFreq = LinkedHashMap<Int, EqualizerBandGain>()
        curve.sortedBy { it.centerFreqHz }.forEach { byFreq[it.centerFreqHz] = it }
        return byFreq.values.toList()
    }

    /**
     * 显示用的增益文字，例如 `+6 dB`、`-3.5 dB`、`0 dB`。
     *
     * 保留一位小数，整数不写小数部分：`+6.0 dB` 里那个 `.0` 没有任何信息，
     * 却会让一行五个数字的界面看起来更挤。`-0.0` 会被规整成 `0 dB`
     * （四舍五入之后是 -0 的时候符号没有意义）。
     *
     * `dB` 这个单位不翻译：三种语言里它都写 `dB`。
     */
    fun formatGain(gainDb: Float): String {
        if (!gainDb.isFinite()) return formatGain(0f)
        val rounded = (gainDb * 10f).roundToInt() / 10f
        val magnitude = if (rounded % 1f == 0f) abs(rounded).toInt().toString() else abs(rounded).toString()
        val sign = when {
            rounded > 0f -> "+"
            rounded < 0f -> "-"
            else -> ""
        }
        return "$sign$magnitude dB"
    }

    /**
     * 显示用的频率文字，例如 `60 Hz`、`3.6 kHz`、`14 kHz`。
     *
     * 单位是国际写法，不需要三种语言各来一份（这也是把它写成纯函数的原因：
     * 它进不了资源文件，也就不用管字符串守卫测试）。
     */
    fun formatFreq(centerFreqHz: Int): String {
        if (centerFreqHz <= 0) return "—"
        if (centerFreqHz < 1_000) return "$centerFreqHz Hz"
        val khz = (centerFreqHz / 100f).roundToInt() / 10f
        val magnitude = if (khz % 1f == 0f) khz.toInt().toString() else khz.toString()
        return "$magnitude kHz"
    }
}

/**
 * 内置的均衡器预设。
 *
 * ## 为什么不用系统自带的预设
 *
 * `android.media.audiofx.Equalizer` 自己带一组预设（`usePreset`），但它们的
 * **名字是厂商/系统的**（同一个 id 在两家手机上叫两个名字）、**段数取决于设备**，
 * 而且换台设备之后用户设置里存的那个 id 可能指向完全不同的曲线。整套曲线写在
 * 自己手里，跨设备才是同一件事；顺便，名字也就归我们自己的三种语言管。
 *
 * 曲线都在 [EqualizerCurve.STANDARD_FREQS_HZ]（60 / 230 / 910 / 3.6k / 14k）上定义。
 *
 * @param id 持久化契约。改名字可以，改这个字符串会让用户已有的设置回落到默认值。
 * @param curve 这条预设的增益曲线。
 */
enum class EqualizerPreset(val id: String, val curve: List<EqualizerBandGain>) {

    /** 平直：什么都不改。 */
    FLAT("flat", EqualizerCurve.of(0f, 0f, 0f, 0f, 0f)),

    /** 低音增强：抬低频、轻微削掉最上面的齿音。 */
    BASS("bass", EqualizerCurve.of(7f, 4f, 0f, 0f, -1f)),

    /** 高音增强：和低音增强镜像。 */
    TREBLE("treble", EqualizerCurve.of(-2f, -1f, 0f, 4f, 7f)),

    /** 人声：把伴奏让开一点，中频抬起来。 */
    VOCAL("vocal", EqualizerCurve.of(-3f, 0f, 3f, 3f, 0f)),

    /** 摇滚。 */
    ROCK("rock", EqualizerCurve.of(5f, 3f, -1f, 2f, 4f)),

    /** 流行。 */
    POP("pop", EqualizerCurve.of(-1f, 2f, 3f, 2f, -1f)),

    /** 爵士。 */
    JAZZ("jazz", EqualizerCurve.of(3f, 1f, 0f, 2f, 3f)),

    /** 古典。 */
    CLASSICAL("classical", EqualizerCurve.of(4f, 2f, -1f, 2f, 5f)),
    ;

    companion object {

        /** 装完之后的默认值：平直。 */
        val DEFAULT = FLAT

        /**
         * 从设置里存下来的字符串还原。认不出来就回 [DEFAULT]。
         *
         * 和画面比例那一组设置一样的取舍：这条路径上抛异常等于「升级之后播放页
         * 打不开」，而一个认不出来的 id 顶多让用户重新选一次。
         */
        fun fromId(id: String?): EqualizerPreset = entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * 哪条预设听起来和 [curve] 一样？一条都不像就返回 `null`（= 自定义）。
         *
         * 界面上「自定义」不是一个可以点的预设，而是「当前曲线匹配不上任何预设」
         * 这个状态：让用户点一个叫「自定义」的 chip 会得到一条平直曲线，那是
         * 骗人的。判断交给这里，界面只负责画。
         */
        fun matching(curve: List<EqualizerBandGain>): EqualizerPreset? =
            entries.firstOrNull { EqualizerCurve.sameAs(it.curve, curve) }
    }
}
