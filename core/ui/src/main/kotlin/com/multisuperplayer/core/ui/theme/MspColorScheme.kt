package com.multisuperplayer.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.ui.R

/**
 * 主题基底。
 *
 * [BLACK] 不是 [DARK] 的换皮：AMOLED 屏上纯黑能让像素真正断电，
 * 省电且没有「灰蒙蒙的黑」；但它也让阴影和分割线失效，
 * 所以两套 surface 系列必须分别给值，不能共用。
 *
 * 界面上的名字是 [MspText]（延迟到 UI 边界才解析），而 [id] 是**与语言无关**的
 * 存储键：[fromId] 表的是它，数据层只存它。两者用一个字段兼顾会在一改语言后
 * 就把用户选过的主题认成「不认识的 id」而回退到默认值。
 */
enum class MspBaseTheme(val id: String, val label: MspText) {
    FOLLOW_SYSTEM("system", MspText.Res(R.string.msp_theme_system)),
    LIGHT("light", MspText.Res(R.string.msp_theme_light)),
    DARK("dark", MspText.Res(R.string.msp_theme_dark)),
    BLACK("black", MspText.Res(R.string.msp_theme_black)),
    ;

    val isDark: Boolean get() = this != LIGHT

    companion object {
        /** 默认值的**唯一来源**：数据层只存 id，不认识这个值。 */
        val DEFAULT: MspBaseTheme = FOLLOW_SYSTEM

        /**
         * 按 id 解析。
         *
         * [id] 为空（用户从未设置过）或认不出来（降级安装、手改过配置文件）
         * 都回退到 [DEFAULT]——主题这种东西**绝不能因为一个字符串对不上就崩**，
         * 最差也就是回到默认主题。
         */
        fun fromId(id: String?): MspBaseTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 强调色预设。
 *
 * 每种只声明**两个**「种子」（主色 + 主色容器），其余角色由
 * [composeColorScheme] 按对比度推导。
 *
 * 为什么不引 material-color-utilities 做完整的 HCT 调色板：
 * 那套算法的价值在于「从任意一张封面图生成和谐的整套配色」，
 * 属于「从封面取色」这条路；固定预设用不着它，而多引一个库
 * 就要为它承担版本冲突和 100KB 的体积。等做封面取色时再引。
 */
enum class MspAccent(
    val id: String,
    val label: MspText,
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
) {
    INDIGO(
        "indigo",
        MspText.Res(R.string.msp_accent_indigo),
        Color(0xFF4A54C8), Color(0xFFE0E0FF), Color(0xFFB9C0FF), Color(0xFF303A8C),
    ),
    VIOLET(
        "violet",
        MspText.Res(R.string.msp_accent_violet),
        Color(0xFF6A3FCB), Color(0xFFE9DDFF), Color(0xFFCDBDFF), Color(0xFF4B2A96),
    ),
    TEAL(
        "teal",
        MspText.Res(R.string.msp_accent_teal),
        Color(0xFF00695C), Color(0xFFA7F2E4), Color(0xFF64D8C4), Color(0xFF005044),
    ),
    GREEN(
        "green",
        MspText.Res(R.string.msp_accent_green),
        Color(0xFF2E6B2F), Color(0xFFB2F2AC), Color(0xFF97D78F), Color(0xFF17501A),
    ),
    AMBER(
        "amber",
        MspText.Res(R.string.msp_accent_amber),
        Color(0xFF8A5300), Color(0xFFFFDDB3), Color(0xFFFFB951), Color(0xFF663D00),
    ),
    ROSE(
        "rose",
        MspText.Res(R.string.msp_accent_rose),
        Color(0xFFB3245B), Color(0xFFFFD9E2), Color(0xFFFFB0C8), Color(0xFF8E0F45),
    ),
    ;

    companion object {
        val DEFAULT: MspAccent = INDIGO

        /** @see MspBaseTheme.Companion.fromId */
        fun fromId(id: String?): MspAccent =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 两个「取色」开关的默认值。
 *
 * 单独抽出来是因为同一个默认值有**三个**读者：[MspTheme] 的参数默认值、
 * `MspApp` 组装主题时的 `?:` 兜底、设置页显示开关状态时的 `?:` 兜底。
 * 以前这三处各写一遍字面量，于是「默认开」这个错值被复制了三份，
 * 改一处等于没改——而这正是「强调色点了没反应」的成因。
 */
object MspThemeDefaults {
    /**
     * 「跟随系统取色」（莫奈）默认**关**。
     *
     * 默认开的后果在 Android 12+ 上直接可见：系统取色的优先级高于强调色，
     * 于是设置页里那排强调色成了一个点了没有任何反应的死控件，
     * 而新装用户第一次进来必然踩到它。
     *
     * 系统主题色确实是用户对整台设备的一致选择，但它不能盖掉
     * 「应用内更细的选择应该赢」这条规则——想要莫奈的人自己去开，
     * 那里有明确的开关和说明。
     */
    const val USE_DYNAMIC_COLOR: Boolean = false

    /**
     * 「封面取色」默认**关**。
     *
     * 它由「当前正在播的内容」驱动，只能在用户看过它是什么样之后才打开；
     * 默认打开等于让整个应用的配色由随手点开的第一首歌决定。
     */
    const val COLOR_FROM_ARTWORK: Boolean = false
}

/**
 * 生成完整配色方案。
 *
 * 强调色族（primary / secondary / tertiary 三族的每个角色）全部由种子推导，
 * 中性色取自 M3 基线但去掉了紫偏。两条取色路径（预设 / 封面）共用
 * [composeColorSchemeFromSeeds]，规则只有一份。
 *
 * @param baseTheme 主题基底；[MspBaseTheme.BLACK] 会把中性色压到纯黑。
 * @param isDark 已解析过的明暗（基底是 FOLLOW_SYSTEM 时由调用方解析）。
 */
fun composeColorScheme(
    accent: MspAccent,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme = composeColorSchemeFromSeeds(
    lightPrimary = accent.lightPrimary,
    lightContainer = accent.lightContainer,
    darkPrimary = accent.darkPrimary,
    darkContainer = accent.darkContainer,
    baseTheme = baseTheme,
    isDark = isDark,
)

/**
 * 同 [composeColorScheme]，但种子来自封面取色（[ArtworkAccent]）。
 *
 * 两条路都必须走同一个实现：配色规则（哪个角色压哪个角色、纯黑怎么处理）
 * 只应该有一份，否则「预设好看、封面取色难看」这种问题会永远修不干净。
 */
fun composeColorScheme(
    artwork: ArtworkAccent,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme = composeColorSchemeFromSeeds(
    lightPrimary = artwork.lightPrimary,
    lightContainer = artwork.lightContainer,
    darkPrimary = artwork.darkPrimary,
    darkContainer = artwork.darkContainer,
    baseTheme = baseTheme,
    isDark = isDark,
)

/**
 * 生成完整配色方案。三步，顺序不能换：
 *
 * 1. 取 M3 基线的中性色，并**去掉色偏**。基线那套是以「紫」为种子调出来的，
 *    连背景、分割线、底部导航栏底色这些没有任何语义颜色的角色都带一点紫。
 * 2. 纯黑基底把中性角色压到纯黑（强调色角色不动，否则整屏只剩黑白）。
 * 3. 把强调色族的**每一个**角色都写进去。
 *
 * 第 3 步以前只写了 primary / secondary / tertiary 三个主色，没写到的角色
 * 一律保留**基线值**，而基线的品牌色是紫。用户看到的就是「强调色换了，
 * 可底部导航栏选中那一下、筛选项选中的 chip、只授权部分权限时的提示条
 * 仍然是紫的」：前两个读 `secondaryContainer`，第三个读 `tertiaryContainer`，
 * 都没人给值。
 *
 * 所以：**这里漏一个角色，那个角色显示的就是别人的品牌色**，不是中性灰。
 * 加角色时必须同时在 `MspColorSchemeTest` 里加断言。
 */
private fun composeColorSchemeFromSeeds(
    lightPrimary: Color,
    lightContainer: Color,
    darkPrimary: Color,
    darkContainer: Color,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme {
    // 亮色侧、暗色侧各算一遍派生值，而不是只算用得到的那一侧：
    // 「固定」色族（*Fixed）按定义**不随明暗切换**，只能用亮色种子推导。
    val lightSecondary = lightPrimary.desaturate(SECONDARY_DESATURATION)
    val lightSecondaryContainer = lightContainer.desaturate(SECONDARY_CONTAINER_DESATURATION)
    val lightTertiaryContainer = lightContainer.desaturate(TERTIARY_CONTAINER_DESATURATION)
    val darkSecondary = darkPrimary.desaturate(SECONDARY_DESATURATION)
    val darkSecondaryContainer = darkContainer.desaturate(SECONDARY_CONTAINER_DESATURATION)
    val darkTertiaryContainer = darkContainer.desaturate(TERTIARY_CONTAINER_DESATURATION)

    val primary = if (isDark) darkPrimary else lightPrimary
    val container = if (isDark) darkContainer else lightContainer
    val secondary = if (isDark) darkSecondary else lightSecondary
    val secondaryContainer = if (isDark) darkSecondaryContainer else lightSecondaryContainer
    val tertiaryContainer = if (isDark) darkTertiaryContainer else lightTertiaryContainer
    val onContainer = onColorFor(container)

    val oledBlack = baseTheme == MspBaseTheme.BLACK

    // 第一步（去色偏）+ 第二步（纯黑基底）。只动中性角色。
    val baseline = (if (isDark) darkColorScheme() else lightColorScheme()).withNeutralGreys()
    val neutrals = if (!oledBlack) {
        baseline
    } else {
        baseline.copy(
            background = Color.Black,
            onBackground = OledOnSurface,
            surface = Color.Black,
            onSurface = OledOnSurface,
            surfaceVariant = Color(0xFF141414),
            onSurfaceVariant = Color(0xFFB8B8B8),
            surfaceContainerLow = Color(0xFF050505),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF151515),
            surfaceContainerHighest = Color(0xFF1E1E1E),
            outline = Color(0xFF3A3A3A),
            outlineVariant = Color(0xFF262626),
        )
    }

    // 第三步：强调色族。一个都不能漏。
    return neutrals.copy(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = if (isDark) lightPrimary else darkPrimary,
        primaryFixed = lightContainer,
        primaryFixedDim = darkPrimary,
        onPrimaryFixed = onColorFor(lightContainer),
        onPrimaryFixedVariant = lightPrimary,

        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onColorFor(secondaryContainer),
        secondaryFixed = lightSecondaryContainer,
        secondaryFixedDim = darkSecondaryContainer,
        onSecondaryFixed = onColorFor(lightSecondaryContainer),
        onSecondaryFixedVariant = lightSecondary,

        // `tertiary` 保持「容器」这个亮色调：播放页把它当深色遮罩上的提示文字用
        // （见 SubtitleTrackPicker），换成主色会在遮罩上糊掉。
        tertiary = container,
        onTertiary = onContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onColorFor(tertiaryContainer),
        tertiaryFixed = lightTertiaryContainer,
        tertiaryFixedDim = darkTertiaryContainer,
        onTertiaryFixed = onColorFor(lightTertiaryContainer),
        onTertiaryFixedVariant = onColorFor(lightContainer),

        // 抬升（elevation）时往表面里混的那个色调。不写就还是基线紫。
        // 纯黑基底给透明：它承诺的是「像素真的断电」，混任何颜色都破坏这个承诺。
        surfaceTint = if (oledBlack) Color.Transparent else primary,
    )
}

/** 次要色族的降饱和比例。 */
private const val SECONDARY_DESATURATION = 0.35f

/** 次要容器色族（底部导航选中指示器、选中态 chip 的底色）。 */
private const val SECONDARY_CONTAINER_DESATURATION = 0.25f

/** 第三容器色族（「只授权了部分媒体访问」这类提示条），比次要再软一点。 */
private const val TERTIARY_CONTAINER_DESATURATION = 0.45f

/** 纯黑基底上的前景色。不用纯白——纯白压在纯黑上太刺眼。 */
private val OledOnSurface = Color(0xFFEDEDED)

/**
 * 把中性角色全部换成同亮度的灰。
 *
 * M3 基线配色是以紫色为种子调出来的，`background` / `surfaceContainer` /
 * `outline` / `onSurfaceVariant` 这些**没有语义颜色**的角色也带着紫偏。
 * 强调色一带上，这种偏色就露出来了：底部导航栏底色、输入框描边、次要文字
 * 全都「灰得发紫」。
 *
 * 只处理中性角色，强调色角色本来就该有颜色。
 */
private fun ColorScheme.withNeutralGreys(): ColorScheme = copy(
    background = background.dehued(),
    onBackground = onBackground.dehued(),
    surface = surface.dehued(),
    onSurface = onSurface.dehued(),
    surfaceVariant = surfaceVariant.dehued(),
    onSurfaceVariant = onSurfaceVariant.dehued(),
    surfaceContainerLowest = surfaceContainerLowest.dehued(),
    surfaceContainerLow = surfaceContainerLow.dehued(),
    surfaceContainer = surfaceContainer.dehued(),
    surfaceContainerHigh = surfaceContainerHigh.dehued(),
    surfaceContainerHighest = surfaceContainerHighest.dehued(),
    surfaceDim = surfaceDim.dehued(),
    surfaceBright = surfaceBright.dehued(),
    outline = outline.dehued(),
    outlineVariant = outlineVariant.dehued(),
    inverseSurface = inverseSurface.dehued(),
    inverseOnSurface = inverseOnSurface.dehued(),
)

/**
 * 去掉色偏，只留亮度。
 *
 * 用三通道平均，而不是从 [Color.luminance] 反解：这些都是「已经很接近灰」的
 * 颜色（三通道最大差几个 1/255），两种算法给出的结果肉眼分不出来，
 * 而平均不用在 sRGB 和线性空间之间来回换算。
 */
private fun Color.dehued(): Color {
    val grey = (red + green + blue) / 3f
    return copy(red = grey, green = grey, blue = grey)
}

/**
 * 同色调降饱和：往自己的灰上混。
 *
 * 以前这里写的是 `copy(alpha = 0.86f)`，那是错的：它给出来的是**半透明**色，
 * 画在什么背景上就变成什么颜色，同一个角色在两种背景上会呈现两种颜色。
 * 配色角色必须是不透明的。
 */
private fun Color.desaturate(amount: Float): Color {
    val grey = dehued()
    return copy(
        red = red + (grey.red - red) * amount,
        green = green + (grey.green - green) * amount,
        blue = blue + (grey.blue - blue) * amount,
    )
}

/**
 * 取一个在该背景上可读的前景色：在「近黑」和「白」之间选**对比度更高**的那个。
 *
 * 以前是按亮度阈值 0.5 挑的，那是错的：阈值法的前提是两个候选的对比度交点
 * 正好落在 0.5，而近黑是 [DarkOnColor]（相对亮度 ≈ 0.008），交点在 L ≈ 0.196。
 * 于是 0.196～0.5 这一段（中等亮度的强调色）全被判给白色，对比度只有
 * 1.9～3.6——青碧的深色模式就踩到了：darkPrimary 降饱和后 L = 0.493，
 * 「白字」→ 对比度 1.94，几乎看不见。
 *
 * 候选只有两个，直接比对比度就不用维护任何阈值：以后换种子也不会跑偏。
 *
 * 用 [Color.luminance]（已做 sRGB 线性化）而不是简单取 RGB 平均，
 * 否则纯黄这类高感知亮度色会被判成「暗色」而配出黑字。
 */
private fun onColorFor(background: Color): Color =
    if (contrastRatio(background, DarkOnColor) >= contrastRatio(background, Color.White)) {
        DarkOnColor
    } else {
        Color.White
    }

/** 「亮背景上的前景色」。不用纯黑：纯黑压在彩色上显得脏。 */
private val DarkOnColor = Color(0xFF191919)

/** WCAG 对比度。1 表示完全一样，21 表示纯黑配纯白。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (la.coerceAtLeast(lb) + 0.05f) / (la.coerceAtMost(lb) + 0.05f)
}
