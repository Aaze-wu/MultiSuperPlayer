package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.R

import com.multisuperplayer.core.player.SpeedBoostOptions

/**
 * 播放内核相关的用户偏好。
 *
 * ## 为什么字段是 `Boolean?` 而不是 `Boolean`
 *
 * 沿用 [ThemeSettings] 的约定：可空 = 「用户从没设置过」，由消费者（这里是
 * `PlaybackController` 自己的默认值）决定初值。
 *
 * 这样做的意义在于：**默认值的唯一来源只有一处**。如果这里写成
 * `val forceSoftwareDecoding: Boolean = false`，数据层就有了一个副本，
 * 哪天内核把默认改成「硬件优先」之外的策略，这个副本不会跟着变，
 * 而症状是「全新安装的用户和升级上来的用户行为不一样」——极难排查。
 */
data class PlaybackSettings(
    /**
     * 是否强制改用 FFmpeg 软件解码。null = 没设置过（等价于 false，即让内核自己选）。
     *
     * ⚠️ 注意这个开关**不当**「让更多文件能放」用：内核默认就是
     * 「系统解码器优先，解不了/解失败自动换 FFmpeg」。它解决的是另一类问题——
     * 硬件解码器不报错，但画面花屏、变色、音画不同步。所以它默认关、且只对
     * 明确知道自己在做什么的用户有意义。
     */
    val forceSoftwareDecoding: Boolean? = null,

    /**
     * 画面比例默认用哪一种。null = 没设置过（等价于 [AspectRatioMode.FIT]）。
     *
     * 存默认值而不是「当前值」是有意的：用户在播放页临时切到「裁剪填满」看完一部片子，
     * 不应该导致下一部片子也默认被裁掉两边。播放页的那次切换只影响当前这一部。
     */
    val aspectRatioMode: AspectRatioMode? = null,

    /**
     * 倍速。null = 没设置过（等价于 1.0）。
     *
     * 这个**是**会跨文件保留的：把倍速当「这一部片子临时用的」没有意义——
     * 用 1.5 倍听播客的用户希望下一集也是 1.5 倍。范围由内核夹在 0.25–4.0。
     */
    val speed: Float? = null,

    /**
     * 是否记住播放进度并在下次接着播。null = 没设置过（等价于 true）。
     *
     * 默认开：这是「播放器应有的样子」。关掉的场景也确实存在（用播放器当背景音乐、
     * 想每次都从头听），所以做成开关而不是写死。
     */
    val rememberPosition: Boolean? = null,

    /**
     * 是否记录「最近播放」。null = 没设置过（等价于 true）。
     *
     * 和 [rememberPosition] 是两个开关，不是同一个：那个管的是「续播位置」（下次接着播），
     * 这个管的是「播过什么」（列表里有没有这一条）。三条组合都说得通——
     * 只想在首页看到看过什么、但每次都从头播；或者反过来说「别记录我看过什么」。
     *
     * 关掉时**只影响以后**：已经记下的记录留在磁盘上，重新打开开关就能看到。
     * 删数据是另一件事，而这个开关的语义是「别再记了」，不是「忘掉」——
     * 顺手删掉用户的历史，代价和收益完全不成比例（见 [rememberPosition] 的同款说明）。
     */
    val recordRecentPlays: Boolean? = null,

    /**
     * 按住画面时用的临时倍速。null = 没设置过（等价于 [SpeedBoostOptions.DEFAULT]）。
     *
     * 和 [speed] 一样是「跨文件保留」的偏好，但两者解决的是不同的问题：
     * [speed] 是「我打算用多快看完」，这个只是「按住的那两秒要多快」。
     * 所以它可以比 [speed] 快，也可以比 [speed] 慢（按住反而变慢虽然奇怪，
     * 但那是用户自己配的，播放器替他猜「其实你想更快」只会更莫名其妙）。
     *
     * 这里**只存值，不收敛**：档位表的唯一来源是 `:core:player` 的
     * [SpeedBoostOptions.normalize]，数据层再抄一份就会有两处默认值。
     */
    val boostSpeed: Float? = null,

    /**
     * 均衡器开关。null = 没设置过（等价于关）。
     *
     * 默认关：均衡器改变的是**所有**声音，而且关着的时候才是「录制时本来的样子」。
     * 一个默认打开的均衡器会让用户以为「我的耳机听起来就这样」，直到他某天在
     * 设置里发现有个东西一直在改他的声音。
     *
     * 和 [equalizerBandGains] 分开存：用户会「先关掉听两句对比一下」，关掉不等于
     * 放弃自己调好的那条曲线。共用一个键的话，关一次就等于把曲线也扔了。
     */
    val equalizerEnabled: Boolean? = null,

    /**
     * 均衡器曲线，`"60:6.0,230:4.0"` 这种写法（见 `:core:player` 的
     * `EqualizerCurve.encode`）。null = 没设置过（等价于平直）。
     *
     * ## 为什么存的是「频率→增益」而不是「第几段→增益」，也不存预设名
     *
     * 用频率是因为设备段数不一样（5 段、10 段都有），存下标会让同一个设置换台
     * 设备就变成另一条曲线（详见 `EqualizerBandGain` 的说明）。
     *
     * 不另存「选了哪个预设」是因为那是一个**派生**值：曲线匹配得上哪条预设就是
     * 哪条，匹配不上就是自定义（`EqualizerPreset.matching`）。两个字段分开存迟早
     * 会互相矛盾——「预设显示摇滚、曲线却是低音」这种状态一旦出现，用户就再也
     * 分不清界面上的高亮和实际听到的声音是不是一回事。
     *
     * 数据层**不解析**这个字符串：解析失败要回落到平直而不是崩掉，那个判断在
     * `EqualizerCurve.decode` 里（它返回 null 表示读不出来），只有一处。
     */
    val equalizerBandGains: String? = null,
)

/**
 * 画面比例。
 *
 * ## 为什么要单独一个枚举而不是「布尔 + 缩放系数」
 *
 * 四个选项背后是**四种不同的裁剪语义**，不是同一个参数的不同取值：
 * 适应和裁剪填满都是「保持比例、只是溢出部分截掉与否」，拉伸是「不保持比例」，
 * 原始是「按文件自己的比例适应」。用一个 Float 表示的话，调用方迟早会把
 * 「裁剪填满」写成 1.0 而把「拉伸」写成 NaN 或负数，然后得到一个没人能解释的画面。
 *
 * 与 `:core:ui` 里的 `MspBaseTheme` 一样，这里的 `id` 是**持久化契约**，
 * 枚举常量名可以改，`id` 不能。
 *
 * `label` 和 `description` 都是**界面文案**，也放在这里：播放页的选择面板和设置页的
 * 默认值选择器必须用同一套话，分头写两份必然漂移——用户会看到同一个模式在两个页面上
 * 叫不同的名字、或者描述不一样。
 *
 * @param label 短名，用在 chip、行尾的当前值上（地方小）。
 * @param description 一句话解释，用在选择列表里。
 */
enum class AspectRatioMode(val id: String, val label: MspText, val description: MspText) {
    /** 保持比例，完整显示，多余的地方留黑边。 */
    FIT(
        "fit",
        MspText.Res(R.string.msp_aspect_fit_label),
        MspText.Res(R.string.msp_aspect_fit_desc),
    ),

    /** 保持比例，铺满屏幕，超出部分裁掉（原「裁剪填满」）。 */
    CROP(
        "crop",
        MspText.Res(R.string.msp_aspect_crop_label),
        MspText.Res(R.string.msp_aspect_crop_desc),
    ),

    /** 不保持比例，强行拉满整个屏幕。 */
    STRETCH(
        "stretch",
        MspText.Res(R.string.msp_aspect_stretch_label),
        MspText.Res(R.string.msp_aspect_stretch_desc),
    ),

    /** 按视频自己的宽高比显示（竖向视频不会被拉宽）。 */
    ORIGINAL(
        "original",
        MspText.Res(R.string.msp_aspect_original_label),
        MspText.Res(R.string.msp_aspect_original_desc),
    ),
    ;

    companion object {
        /** 内核/界面的默认值。 */
        val DEFAULT = FIT

        /**
         * 从持久化的字符串还原。
         *
         * 认不出来（键不存在、或者存的是旧版本/被篡改的值）就回 [DEFAULT]，
         * 而不是抛异常：这条路径上抛异常等于「升级之后播放页打不开」。
         */
        fun fromId(id: String?): AspectRatioMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
