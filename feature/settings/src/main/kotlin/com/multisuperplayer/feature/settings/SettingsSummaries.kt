package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.llm.LlmModelCatalog
import com.multisuperplayer.core.llm.LlmModelInfo
import com.multisuperplayer.core.llm.LlmModelStatus
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
import com.multisuperplayer.core.translate.describeMissingItems
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults

/**
 * 设置首页四个入口的副标题。
 *
 * 为什么要把它们抽成**纯函数**而不是直接写在 `Composable` 里：
 *
 * 1. 入口页的全部价值就在这几行字上。如果副标题写的是功能说明（「调整播放速度」），
 *    这一页就退化成了目录，用户点进去才知道自己是不是要改的东西——那还不如直接
 *    把所有设置铺开（这正是这次重写要去掉的形态）。副标题必须是**当前状态**，
 *    所以它值得被测试钉住。
 * 2. 它们是「当前状态 → 一句话」的映射，没有任何界面依赖，可以在 JVM 单测里
 *    直接喂各种脏数据（未设置、旧值不在档位表里、开关互相覆盖）检查输出。
 *
 * 返回 [MspText] 而不是 `String`：这些句子里混着主题名、语言名和「缺哪一项」，
 * 全都要跟着界面语言走；而取值（倍速、文件大小、日期范围）又和语言无关。
 * 把「哪一句 + 什么参数」写下来，解析留给 UI 那一层，纯函数就能继续在 JVM 里测。
 *
 * 片段之间用 [MspText.join] 拼，分隔符是 [SEPARATOR] 这条资源——**不在代码里拼 `String`**：
 * 那样测试就只能看到一整条拼好的句子，说不出「哪几段」。
 *
 * 全部是单行文本。`ListItem` 的 supporting 文本会在需要时折行，但两行以上就说明
 * 摘要写多了——入口页应该一眼扫完。
 */
internal object SettingsSummaries {

    /** 摘要片段之间的分隔符。译者可以换成「・」或者「, 」。 */
    private val SEPARATOR: MspText = MspText.Res(R.string.msp_settings_summary_sep)

    /** 拼一段摘要：`null` 的片段直接跳过（「没有这条尾巴」比「空字符串」更好读）。 */
    private fun join(vararg parts: MspText?): MspText =
        MspText.join(SEPARATOR, parts.filterNotNull())

    /**
     * 外观：`纯黑（OLED） · 青碧 · 封面取色`。
     *
     * @param systemColorSupported 本机是否支持系统取色（Android 12+）。**必须由调用方传入**：
     *   判断要用 `Build.VERSION.SDK_INT`，而它在 JVM 单测里恒为 0，写进纯函数就再也
     *   测不了「能取色」的那条分支了。
     *
     * 「系统取色未生效」这个尾巴是有意加的：开关开着但实际没生效时，用户看到的颜色
     * 是强调色，而他在这一行上完全无从知道为什么。说出来，再在「外观」页里解释原因。
     */
    fun appearance(theme: ThemeSettings, systemColorSupported: Boolean): MspText {
        val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
        val accent = MspAccent.fromId(theme.accentId)
        // 两个取色开关都是「有值才生效」的可空布尔，兜底值只从 MspThemeDefaults 来，
        // 不写字面量——曾经这里写的是 `?: true`，正是「点强调色没反应」的成因。
        val artworkColor = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK
        val dynamicWanted = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR
        // 纯黑模式下系统取色给的是一堆深灰，正好把「省像素」这件事毁掉，所以那条路不通。
        val dynamicBlockedByOled = baseTheme == MspBaseTheme.BLACK
        val dynamicActive = dynamicWanted && systemColorSupported && !dynamicBlockedByOled && !artworkColor

        // 自定义色生效时**绝不能**报预设的名字：用户自己拖了个绿色，这一行却写着「靛蓝」，
        // 而入口页的这一行正是他判断「我的设置到底存下来没有」的唯一依据。
        val accentLabel =
            if (theme.customAccent != null) {
                MspText.Res(R.string.msp_settings_summary_accent_custom)
            } else {
                accent.label
            }

        // 覆盖优先级：封面取色 > 系统取色 > 自定义 > 强调色。同时说两个是自相矛盾的：实际生效的只有一个。
        val tail = when {
            artworkColor -> MspText.Res(R.string.msp_settings_summary_tail_artwork)
            dynamicActive -> MspText.Res(R.string.msp_settings_summary_tail_dynamic)
            dynamicWanted -> MspText.Res(R.string.msp_settings_summary_tail_dynamic_blocked)
            else -> null
        }
        return join(baseTheme.label, accentLabel, tail)
    }

    /**
     * 播放：`1.0× · 长按 2.0× · 适应`，后面按需挂上几个「偏离默认」的状态。
     *
     * 前三个总是显示（哪怕都是默认值）：摘要的长度可预期，用户才知道自己每次都看到了
     * 同样的三件事；只把「非默认」的项显出来，会让这一行时有时无，看起来像坏了。
     * 后面的旗标相反——它们只在偏离默认时出现，因为「没有强制软解」不是一件需要提醒的事。
     */
    fun playback(playback: PlaybackSettings, softwareDecodingAvailable: Boolean): MspText {
        val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
        val boost = SpeedBoostOptions.normalize(playback.boostSpeed)
        val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
        val forceSoftware = playback.forceSoftwareDecoding ?: false
        val rememberPosition = playback.rememberPosition ?: true
        val recordRecentPlays = playback.recordRecentPlays ?: true
        val trustUntrustedCertificates = playback.trustUntrustedCertificates ?: false

        return join(
            MspText.Plain(PlaybackSpeedOptions.format(speed)),
            MspText.Res(R.string.msp_settings_summary_long_press, SpeedBoostOptions.format(boost)),
            aspect.label,
            // 「强制软解」只有在真的能生效时才说：本安装包不含 FFmpeg 时那个开关在
            // 「播放」页里是灰的，摘要却声称已开启，就是自相矛盾。
            if (forceSoftware && softwareDecodingAvailable) {
                MspText.Res(R.string.msp_settings_summary_tail_force_software)
            } else {
                null
            },
            if (softwareDecodingAvailable) null else MspText.Res(R.string.msp_settings_summary_tail_no_ffmpeg),
            if (rememberPosition) null else MspText.Res(R.string.msp_settings_summary_tail_no_position),
            if (recordRecentPlays) null else MspText.Res(R.string.msp_settings_summary_tail_no_recent),
            // 和别的尾标相反：这一条是「偏离默认时要说出来」，而且它是**安全**状态：
            // 用户可能一年前为了某台 NAS 打开过，之后再没想起它。摘要是他唯一
            // 每次都会路过的位置。
            if (trustUntrustedCertificates) {
                MspText.Res(R.string.msp_settings_summary_tail_trust_certificates)
            } else {
                null
            },
        )
    }

    /**
     * 字幕与翻译：`硅基流动 · deepseek-v3 · 译成中文`，没配好时直接说缺什么。
     *
     * 「缺什么」必须写出来。这一行是用户最常看的（从播放页的字幕面板翻不出来时，
     * 他只想确认这两件事），只写「未配置」等于让他点进三层页面自己找。
     *
     * 缺项清单来自 [TranslationSettings.missingItems]，和「测试连接」按钮同源——
     * 在这里再判一次就会出现「这一行说缺密钥、点进去却说齐了」这种自相矛盾。
     *
     * @param localStatus 选的是设备上的服务商时，模型文件在磁盘上的状态；
     *   `null` = 还没读过盘（首帧）。**不知道就不说**：本地模型有没有下载完不属于
     *   设置（它是文件系统的事实），而这一行如果说「可以翻译」而模型还没下，
     *   用户点进播放页才发现——那时他已经离开了唯一能下载模型的地方。
     */
    fun translation(translation: TranslationSettings, localStatus: LlmModelStatus? = null): MspText {
        val provider = translation.provider.displayName
        if (!translation.ready) {
            return join(
                provider,
                MspText.Res(
                    R.string.msp_settings_summary_translation_incomplete,
                    describeMissingItems(translation.missingItems),
                ),
            )
        }
        // 设备上的服务商：设置里的 `model` 是模型清单的 id（`qwen3-0.6b`，同时是目录名），
        // 用户认识的是清单里的名字（`Qwen3 0.6B（本地）`）。直接把 id 显示出来，
        // 他会以为要自己去填一个叫这个的模型名。
        if (translation.onDevice) {
            val model = LlmModelCatalog.byId(translation.model)
            return join(
                provider,
                model.name,
                localStatus?.let { localModelStatus(model, it) },
                MspText.Res(R.string.msp_settings_summary_translate_to, translation.target.label),
            )
        }
        // 模型名是用户自己填的（与语言无关），但「没填」这件事得说出来。
        val model = translation.model.takeIf { it.isNotBlank() }
            ?.let(MspText::Plain)
            ?: MspText.Res(R.string.msp_settings_summary_model_unset)
        return join(
            provider,
            model,
            MspText.Res(R.string.msp_settings_summary_translate_to, translation.target.label),
        )
    }

    /**
     * 本地模型入口行：`Qwen3 0.6B（本地） · 未下载 · 约 328.7 MB`。
     *
     * 体积只在下过之前接在后面（同 [asr]）：下到一半时那个数字已经在「已收到 42%」里了，
     * 再写一遍会让人以为进度和体积是两件事。
     */
    fun localModel(model: LlmModelInfo, status: LlmModelStatus): MspText = join(
        model.name,
        localModelStatus(model, status),
        if (status == LlmModelStatus.Absent) model.sizeText() else null,
    )

    /**
     * 本地模型文件的磁盘状态：`未下载` / `未下完（已收到 42%）` / `已下载`。
     *
     * 与 [asrStatus] 的说法**故意不同**：这里的下载不支持断点续传，所以不能说「已下载 42%」
     * （那读起来像「再下 58% 就完了」）。「未下完」+ 已收到的字节数才是不骗人的说法。
     *
     * ⚠️ 百分比必须有 [model] 才能算：`Incomplete` 只带「已收到多少」，总量在清单里。
     * 让状态自己带上总量看似更干净，但那个数来自“清单”而不是磁盘，写进状态就多了一份
     * 可能和清单不一致的副本。
     */
    fun localModelStatus(model: LlmModelInfo, status: LlmModelStatus): MspText = when (status) {
        LlmModelStatus.Absent -> MspText.Res(R.string.msp_settings_summary_local_model_absent)
        is LlmModelStatus.Incomplete -> MspText.Res(
            R.string.msp_settings_summary_local_model_partial,
            percentOf(status.presentBytes, model.sizeBytes),
        )
        LlmModelStatus.Ready -> MspText.Res(R.string.msp_settings_summary_local_model_ready)
    }

    /**
     * 语音识别入口行：按**当前路线**分叉到 [asrOnDevice] 或 [asrCloud]。
     *
     * 这一行以前只认 `model + status`，于是在云端那条路上会显示成
     * `中文离线（小模型） · 未下载 · 约 78.1 MB`——每一个字都是真的，整句话却是假的：
     * 云端根本不下载模型，用户会照这句话去点「下载」再等一场空。
     *
     * 分叉只写在这一处。两个分支各自是纯函数，也各自能被喂脏数据（见 [asrOnDevice]）。
     */
    fun asr(settings: AsrSettings, status: AsrModelStatus): MspText = if (settings.usesCloud) {
        asrCloud(settings)
    } else {
        asrOnDevice(settings.model, status)
    }

    /**
     * 本机那条路：`中文离线（小模型） · 已下载`。
     *
     * 没下过的时候把体积也接在后面（`中文离线（小模型） · 未下载 · 约 78.1 MB`）：
     * 入口页这一行是用户决定要不要点进去看的**唯一**依据，而「这条要花 78 MB 还是 190 MB」
     * 正是那个决定要看的东西。已经下了一部分时不接体积：那时那个数字已经在「42%」里了。
     *
     * 参数是模型本身而不是 `AsrSettings`：这条路上的全部输入就是「哪条模型 + 磁盘上什么状态」，
     * 而体积写错（`totalBytes = 0`）的那类脏数据只能在单测里手工构造出来——
     * `AsrModelCatalog.byId` 会把认不出来的 id 换成默认模型，从设置那一头永远喂不进来。
     */
    fun asrOnDevice(model: AsrModelInfo, status: AsrModelStatus): MspText = join(
        model.name,
        asrStatus(model, status),
        if (status == AsrModelStatus.Absent) model.sizeText() else null,
    )

    /**
     * 云端那条路：`云端识别 · 硅基流动`，后面按需挂上缺什么。
     *
     * 云端的两个前置条件（地址、密钥）**缺哪个说哪个**，和本机那条路上的「未下载」
     * 占的是同一个位置：播放页那个按钮在这两种情况下都点不动，入口行不写出来，
     * 用户只能点进去一个一个看。都不缺时这条尾巴整个不出现。
     */
    fun asrCloud(settings: AsrSettings): MspText = join(
        MspText.Res(R.string.msp_settings_summary_asr_cloud, settings.cloudService.displayName),
        if (settings.cloudAddressLooksValid) {
            null
        } else {
            MspText.Res(R.string.msp_settings_summary_asr_missing_address)
        },
        if (settings.cloudNeedsApiKey && !settings.cloudApiKeyStored) {
            MspText.Res(R.string.msp_settings_summary_asr_missing_key)
        } else {
            null
        },
    )

    /**
     * 一条模型的磁盘状态：`未下载` / `已下载 42%` / `已下载`。
     *
     * 三种说法必须分开，理由同 [fileAccess]：它们对应的下一步完全不同
     * （下载 / 继续下载 / 已经可以直接用），合成一句「未就绪」等于让用户自己猜。
     */
    fun asrStatus(model: AsrModelInfo, status: AsrModelStatus): MspText = when (status) {
        AsrModelStatus.Absent -> MspText.Res(R.string.msp_settings_summary_asr_absent)
        is AsrModelStatus.Partial -> MspText.Res(
            R.string.msp_settings_summary_asr_partial,
            percentOf(status.presentBytes, model.totalBytes),
        )
        AsrModelStatus.Ready -> MspText.Res(R.string.msp_settings_summary_asr_ready)
    }

    /**
     * 已下比例的整数百分比，向下取整。
     *
     * **向下**取：下到 99.6% 时说「100%」会让人以为已经能用了，而 `statusOf` 认的
     * 是「每个文件的字节数都对得上」，差一个字节就还是 `Partial`——说 100% 就是错话。
     *
     * `totalBytes <= 0` 时返回 0 而不抛：体积来自代码里的常量表，但一条写错的条目
     * 不该把设置页变成崩溃页（同一原则见 `Resource` 类文档里的取舍）。
     */
    private fun percentOf(presentBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val percent = presentBytes.coerceAtLeast(0L) * 100 / totalBytes
        return percent.toInt().coerceIn(0, 100)
    }

    /**
     * 文件访问：`已开启` / `未开启` / `本机系统版本不支持`。
     *
     * 三种说法**必须**分开，与 `BrowserRootIssue` 那边是同一个理由：
     * 「系统太旧」和「用户没开」在界面上一个是灰的、一个是能点的。
     * 合成一句「未开启」会让 Android 10 的用户去一个没有这一项的设置页里翻找。
     */
    fun fileAccess(supported: Boolean, granted: Boolean): MspText = when {
        !supported -> MspText.Res(R.string.msp_settings_file_access_unsupported)
        granted -> MspText.Res(R.string.msp_settings_file_access_on)
        else -> MspText.Res(R.string.msp_settings_file_access_off)
    }

    /** 关于：只放版本号。检查更新、开源许可、导出日志都在这一页里，不必再挤进副标题。 */
    fun about(buildInfo: AppBuildInfo): MspText = buildInfo.summaryText()

    /**
     * 关于页的日志概览：`3 个 · 1.2 MB`。
     *
     * 没有文件时明说，不显示「0 个 · 0 B」——那读起来像是统计坏了，
     * 而事实是「这个安装包还没产生过日志」，「导出日志」按钮也就是点得出东西的（见 AboutScreen）。
     */
    fun logFiles(summary: LogSummary): MspText {
        if (summary.isEmpty) return MspText.Res(R.string.msp_settings_summary_no_logs)
        return MspText.Res(
            R.string.msp_settings_summary_log_usage,
            summary.fileCount,
            TimeFormat.fileSize(summary.totalBytes),
        )
    }

    /**
     * 日志覆盖的日期范围：`2026-10-02 ~ 2026-10-05`。
     *
     * 只剩一天时**只显示一天**：写成 `2026-10-02 ~ 2026-10-02` 会被读成「这中间有几天」，
     * 用户就会怀疑日志丢了几天。
     *
     * 日期用 [MspText.Plain]：ISO 日期和 `~` 与界面语言无关，做成待翻译的文案只会
     * 让翻译者以为自己要动它。
     */
    fun logRange(summary: LogSummary): MspText {
        val oldest = summary.oldestDay ?: return MspText.Res(R.string.msp_settings_summary_no_range)
        val newest = summary.newestDay ?: return MspText.Plain(oldest)
        return MspText.Plain(if (oldest == newest) oldest else "$oldest ~ $newest")
    }
}
