package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.DesktopLyricsFontSize
import com.multisuperplayer.core.data.settings.DesktopLyricsPosition
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.settings.clampDesktopLyricsPosition
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument

/**
 * 桌面歌词悬浮窗里的一行。
 *
 * [role] 不是「值不值得画」，而是**这一行是什么**：同样是文案，当前行要用亮色、
 * 译文要比原文略暗、下一行预览要更暗。把角色带出来而不是让渲染点按行序猜
 * （第 0 行是原文、第 1 行是译文……），因为「仅译文」模式下第 0 行就是译文、
 * 而双语模式下第 2 行又变成原文——按下标猜必然猜错。
 */
internal enum class DesktopLyricsRowRole {
    /** 当前这一句的原文（或「仅译文」模式下的译文）。 */
    CURRENT,

    /** 当前这一句的译文。 */
    TRANSLATION,

    /** 下一句的预览。 */
    NEXT,
}

internal data class DesktopLyricsRow(val text: String, val role: DesktopLyricsRowRole)

/**
 * 算出悬浮窗此刻要显示的行：**当前行（可能两行）+ 下一行预览**。
 *
 * ## 为什么是「当前行整组 + 下一行」而不是写死两行
 *
 * 「显示当前行 + 下一行」和「原文、译文各一行」是两条独立的要求，凑在一起时
 * 行数不是固定的：
 *
 * | 显示模式 | 行 |
 * | --- | --- |
 * | 仅原文 | 当前原文、下一行原文 |
 * | 仅译文 | 当前译文、下一行译文（该条没译文时退回原文） |
 * | 双语 | 当前原文、当前译文、下一行原文 |
 *
 * 最多三行。可以再加（下一行的译文），但那会让浮窗变成四行——一个盖在别人
 * 应用上的窗口，四行字已经比很多应用的正文区还高。
 *
 * ## 时间轴复用
 *
 * 位置换算走 [subtitleCuePosition]（与播放页字幕层、歌词页完全同一套「先缩放
 * 再平移」），行选择走 `cueFocusIndexAt`（歌词语义：最后一句已开始的），
 * 行内拆分走 [cueLinesFor]。三处都已经是纯函数且在测试里钉过，这里不重新实现
 * 一遍——浮窗和播放页对不上才是最难查的那类 bug。
 *
 * @return 空列表 = 此刻没有可显示的行（没有歌词 / 字幕关闭 / 字幕整轨为空）。
 */
internal fun desktopLyricsRows(
    document: SubtitleDocument?,
    positionMs: Long,
    mode: SubtitleDisplayMode,
    timelineOffsetMs: Long = 0L,
    ratePermille: Int = SUBTITLE_RATE_BASE_PERMILLE,
): List<DesktopLyricsRow> {
    if (document == null || document.isEmpty) return emptyList()
    if (mode == SubtitleDisplayMode.OFF) return emptyList()

    val cues = document.cues
    val position = subtitleCuePosition(positionMs, timelineOffsetMs, ratePermille)

    // 位置早于第一条时 `cueFocusIndexAt` 返回 -1。这里**不**返回空列表：
    // 前奏里把第一句提前显示出来，比让浮窗空着更有用，也和歌词页「还没开始
    // 就一直显示第一行」的观感一致。
    val focusIndex = document.cueFocusIndexAt(position).coerceAtLeast(0)

    // 空文本的 cue（SRT 里常见：用来「清屏」的空白条目）不能当当前行，
    // 否则浮窗会突然空一下。往后找第一条真的能画出东西的。
    val currentIndex = (focusIndex..cues.lastIndex).firstOrNull { index ->
        desktopLyricsLinesFor(cues[index], mode).isEmpty.not()
    } ?: return emptyList()

    // 「当前行的第一行」永远是 [DesktopLyricsRowRole.CURRENT]，「当前行的第二行」
    // 才是译文——不能按字段判断：仅译文模式下最高的那一行就是译文。
    val currentTexts = desktopLyricsLinesFor(cues[currentIndex], mode).visibleTexts()
    if (currentTexts.isEmpty()) return emptyList()

    val rows = ArrayList<DesktopLyricsRow>(3)
    currentTexts.forEachIndexed { index, text ->
        val role = if (index == 0) {
            DesktopLyricsRowRole.CURRENT
        } else {
            DesktopLyricsRowRole.TRANSLATION
        }
        rows += DesktopLyricsRow(text, role)
    }

    // 下一行预览只取「与当前行同一种投影」的那一行：双语模式下它就是下一行的
    // 原文，仅译文模式下是下一行的译文。
    val nextLines = cues.subList(currentIndex + 1, cues.size)
        .asSequence()
        .map { desktopLyricsLinesFor(it, mode) }
        .firstOrNull { !it.isEmpty }
    nextLines?.visibleTexts()?.firstOrNull()?.let { text ->
        rows += DesktopLyricsRow(text, DesktopLyricsRowRole.NEXT)
    }

    return rows
}

/**
 * [cueLinesFor] 的「浮窗版」：额外把**纯空白**当成不画。
 *
 * 字幕层可以接受一条内容全是空格的 cue（它画出来就是一片空），但浮窗是一块
 * 有背景的板子——把只含空格的 cue 当成当前行，用户看到的是「歌词变成了一个空
 * 框」，那看起来像坏了。这也正是 SRT 里用空白条目清屏时的意图：什么都不显示。
 */
private fun desktopLyricsLinesFor(cue: SubtitleCue, mode: SubtitleDisplayMode): CueLines {
    val lines = cueLinesFor(cue, mode)
    return CueLines(
        original = lines.original?.takeIf { it.isNotBlank() },
        translation = lines.translation?.takeIf { it.isNotBlank() },
    )
}

private fun CueLines.visibleTexts(): List<String> =
    listOfNotNull(
        original?.takeIf { it.isNotBlank() },
        translation?.takeIf { it.isNotBlank() },
    )

/**
 * 悬浮窗这一刻该不该存在。
 *
 * 每一个分支都有自己的用户可见后果，所以做成**穷尽**的枚举而不是一个布尔：
 *
 * - [TURNED_OFF]：用户自己关掉了。没有任何提示，安静地不出现。
 * - [NO_PERMISSION]：开关是开的，但「显示在其他应用上层」没授权。这个必须能
 *   区分出来——否则用户打开开关、什么都没发生，只会以为功能坏了。
 * - [SUBTITLES_OFF]：字幕显示模式是「关闭」。见 [SUBTITLES_OFF] 的说明。
 * - [NO_LYRICS]：没有正在播放的媒体，或者这首没有歌词。
 * - [APP_VISIBLE]：应用在前台。播放页自己会画字幕/歌词，再浮一个窗口出来只是
 *   在同一块屏幕上重复两遍。
 */
internal enum class DesktopLyricsGate {
    SHOWN,
    TURNED_OFF,
    NO_PERMISSION,
    SUBTITLES_OFF,
    NO_LYRICS,
    APP_VISIBLE,
}

/**
 * 判定顺序就是「谁的话更该听」：用户意图 → 系统授权 → 显示模式 → 有没有内容 → 前台。
 *
 * 把 [SUBTITLES_OFF] 排在 [APP_VISIBLE] 之前是刻意的：应用在前台时「字幕关闭」
 * 没有任何可见表现（字幕层本来就不画），用户看不到自己为什么没有歌词；而浮窗
 * 一旦出现，他会立刻发现「明明关了字幕却还有歌词」。两种都不理想，但「同一份
 * 设置决定所有文本是否显示」是更容易解释的那一种——关闭字幕 = 不看文本。
 */
internal fun desktopLyricsGate(
    enabled: Boolean,
    permissionGranted: Boolean,
    appVisible: Boolean,
    hasLyrics: Boolean,
    displayMode: SubtitleDisplayMode,
): DesktopLyricsGate = when {
    !enabled -> DesktopLyricsGate.TURNED_OFF
    !permissionGranted -> DesktopLyricsGate.NO_PERMISSION
    displayMode == SubtitleDisplayMode.OFF -> DesktopLyricsGate.SUBTITLES_OFF
    !hasLyrics -> DesktopLyricsGate.NO_LYRICS
    appVisible -> DesktopLyricsGate.APP_VISIBLE
    else -> DesktopLyricsGate.SHOWN
}

/**
 * 第一次打开浮窗时的位置：水平居中、靠近屏幕底部。
 *
 * 「靠近底部」而不是屏幕正中：桌面歌词的用法是「放在屏幕下沿，像字幕一样」，
 * 正中会压住桌面的图标和别人的界面内容。`bottomGapPx` 由调用方按 dp 换算后传入
 * （留出系统手势条的高度），这样这个函数仍然是纯的、不碰 `DisplayMetrics`。
 *
 * 返回前先夹一次：极端情况下（分屏、超小窗、超大字号）「居中 + 靠底」也可能
 * 落到屏幕外，而浮窗没有标题栏可以拖回来。
 */
internal fun defaultDesktopLyricsPosition(
    screenWidth: Int,
    screenHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    bottomGapPx: Int,
): DesktopLyricsPosition {
    val x = (screenWidth - windowWidth) / 2
    val y = screenHeight - windowHeight - bottomGapPx
    return clampDesktopLyricsPosition(
        x = x,
        y = y,
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
    )
}

/**
 * 浮窗的固定宽度（dp）：屏幕宽度的 92%，**不跟歌词走**。
 *
 * 改动前用的是 `widthIn(max = 92%)`，于是「窗」本身会随那一句多长而伸缩：五个字
 * 是一个小方块，二十个字是一条长条，切歌时左右两边一起跳。更麻烦的是位置按
 * 「左上角」存 ⇒ 宽度一变，视觉中心就漂（同一个 x，宽窗口看着偏右、窄窗口偏左），
 * 用户会以为「它自己挪了」。固定宽度之后「拖到哪就是哪」对**形状**也成立。
 *
 * 92% 是**沿用原来的上限**，不是新挑的数——那个上限本来就是为最长的句子定的，
 * 只是短句时用不到。留 8% 是为了贴边放时两侧还能看到底下的应用。
 *
 * 高度**故意不固定**：行数取决于有没有译文（双语三行、仅原文两行）和字号，
 * 把高度一起钉死就得为「没有译文时」预留一整行空白，比高度变一下更难看。
 *
 * @return dp 值（浮点）。放在纯函数里而不是写进 composable，是为了让
 *   「宽度只跟屏幕有关、与歌词无关」这件事能被单测钉住。
 */
internal fun desktopLyricsWindowWidthDp(screenWidthDp: Int): Float =
    screenWidthDp * DESKTOP_LYRICS_WIDTH_FRACTION

/** 浮窗宽度占屏幕宽度的比例，见 [desktopLyricsWindowWidthDp]。 */
internal const val DESKTOP_LYRICS_WIDTH_FRACTION = 0.92f

/**
 * 字号档位上下走一格。
 *
 * 到顶/到底就停住（不循环）：循环会让人在「特大」上再点一下突然变成「小」，
 * 而浮窗上的按钮不可能带说明文字。边界连着点几下都停在同一档，手感是「到头了」。
 */
internal fun stepDesktopLyricsFontSize(
    current: DesktopLyricsFontSize,
    step: Int,
): DesktopLyricsFontSize {
    val all = DesktopLyricsFontSize.entries
    return all[(current.ordinal + step).coerceIn(0, all.lastIndex)]
}

/**
 * 播放页控制条上那个「桌面歌词」按钮，**下一次点它会做什么**。
 *
 * 抽成纯函数是因为它有四种走向，而每种走向的正确与否只有在真机上才看得出来
 * （点一下跳错了地方、或者点了没反应）。四种走向的优先级是这样的：
 *
 * 1. **没权限**优先于一切。这个时候开没开、锁没锁都没意义——浮窗根本挂不上去。
 *    把用户送到系统的「显示在其他应用上层」，是唯一能推进的一步。
 * 2. **关着**就去开。这时哪怕已经有权限，也不该先去问别的。
 * 3. **锁着**就去解锁。它是「解除当前障碍」，而不是「关掉功能」：锁定的状态下
 *    浮窗点不动，用户按这一下八成就是被挡住的那个时刻。
 * 4. 剩下的情况才是**关掉**。
 *
 * 注意第 1 条与第 2 条的先后：关着 + 没权限时给出 [REQUEST_PERMISSION]，
 * 调用方应当**顺手把开关拨到开**（用户按这一下的意图就是「我要用」），
 * 而他被系统页面挡回来的那一刻功能已经开着。反过来先只开开关、让用户回到桌面
 * 发现什么都没有，是最容易发生的体验事故。
 */
internal enum class DesktopLyricsEntryAction {
    REQUEST_PERMISSION,
    ENABLE,
    UNLOCK,
    DISABLE,
}

/** 见 [DesktopLyricsEntryAction] 的说明。 */
internal fun desktopLyricsEntryAction(
    enabled: Boolean,
    locked: Boolean,
    permissionGranted: Boolean,
): DesktopLyricsEntryAction = when {
    !permissionGranted -> DesktopLyricsEntryAction.REQUEST_PERMISSION
    !enabled -> DesktopLyricsEntryAction.ENABLE
    locked -> DesktopLyricsEntryAction.UNLOCK
    else -> DesktopLyricsEntryAction.DISABLE
}

/**
 * 设置流送来一个版本坐标时，浮窗该怎么办。
 *
 * 抽成纯函数是因为浮窗本身在 JVM 里既测不了也复现不了（要手指 + 真机），
 * 而这条判断是「谁有权改窗口坐标」里最容易写错的一环。
 *
 * 背景：设置流每 200ms 就跟着播放进度推一次值（`controller.state` 里有 `positionMs`），
 * 于是「同一个旧位置」会在拖动过程中反复到达。任何一次把它摆上去，都会把窗口
 * 拽回手指按下之前的地方，下一次 `ACTION_MOVE` 又把它推回来——看起来也像乱窜。
 *
 * 注意：这**不是** v1.4.5 那个「满屏幕抖动」的全部原因。修完这条之后拖动仍然抖，
 * 真因在 [DesktopLyricsWindow.dragMove] 的坐标补偿（本地坐标 + 我们请求的目标位置，
 * 而窗口位置实测滞后约两帧 ⇒ 每帧都过补）。两条都得在才算修好。
 *
 * 分成五种而不是一个布尔值，是因为每种「不动」的**后果不同**：
 * 拖拽中要立刻返回（不能动 `lastSeen`，否则松手后同一个值会被当成新值），
 * 而「自己存的值回环回来」虽然也不摆，但要顺手结束等待。
 */
internal enum class DesktopLyricsPositionStep(
    /** 要不要把这个位置摆到窗口上。 */
    val apply: Boolean,
    /** 要不要结束「等自己刚存的值回环」的等待。 */
    val endSettle: Boolean,
) {
    /** 手指正按着：这条是拖动开始之前的旧值，摆上去就是把窗口拽回去。 */
    IGNORE_WHILE_DRAGGING(apply = false, endSettle = false),

    /** 和上一次处理过的值一模一样：播放位置每秒推五次状态，这种重复值要多少有多少。 */
    IGNORE_REPEATED(apply = false, endSettle = false),

    /** 自己刚存的值回来了：窗口本来就在那儿，不必再摆，但等待到此为止。 */
    IGNORE_ECHO(apply = false, endSettle = true),

    /**
     * 回环还没回来，这条是保存之前的旧值。
     *
     * 只能按住一会儿——**存的值和原来完全一样时 DataStore 不会发新值**，
     * 一直等下去就等于永远不再理会设置（在设置页点「重置位置」会彻底失灵）。
     */
    IGNORE_STALE(apply = false, endSettle = false),

    /** 真的摆过去。 */
    APPLY(apply = true, endSettle = true),
}

/**
 * 见 [DesktopLyricsPositionStep] 的说明。
 *
 * @param lastSeen 上一次处理过的设置值（`null` = 这个窗口还没处理过任何值）。
 * @param settling 自己刚写进设置、正在等它回来的那个位置（`null` = 没在等）。
 * @param settleExpired 等回环的超时是否已过。
 */
internal fun desktopLyricsPositionStep(
    incoming: DesktopLyricsPosition,
    lastSeen: DesktopLyricsPosition?,
    dragging: Boolean,
    settling: DesktopLyricsPosition?,
    settleExpired: Boolean,
): DesktopLyricsPositionStep = when {
    dragging -> DesktopLyricsPositionStep.IGNORE_WHILE_DRAGGING
    incoming == lastSeen -> DesktopLyricsPositionStep.IGNORE_REPEATED
    settling == null -> DesktopLyricsPositionStep.APPLY
    incoming == settling -> DesktopLyricsPositionStep.IGNORE_ECHO
    !settleExpired -> DesktopLyricsPositionStep.IGNORE_STALE
    else -> DesktopLyricsPositionStep.APPLY
}

/** [WindowDragTracker] 这一帧该上报什么。 */
internal enum class WindowDragEvent {
    /** 什么都不用做（还没跨过 touchSlop，或者已经处理完了）。 */
    NONE,

    /** 拖动从这一刻开始，起点见 [WindowDragTracker.downX]/[WindowDragTracker.downY]。 */
    START,

    /** 拖动进行中，把窗口挪到这一刻的手指位置。 */
    MOVE,

    /** 手指抬起/被取消。 */
    END,
}

/**
 * 浮窗拖动的「什么时候算开始拖」判定。
 *
 * 只回答一个问题：这一帧的手指位置该不该让窗口跟着走。抽成独立类是因为
 * 真正的位移计算必须用 `MotionEvent` 的屏幕绝对坐标，而那东西在 JVM 单测里
 * 造不出来；能测的只有这段判定。
 *
 * ## 为什么不用 Compose 的 `position`
 *
 * `PointerInputChange.position` 是**相对窗口**的坐标，而窗口本身在跟着手指移动
 * ⇒ 同一根手指的位置在本地坐标里会「跳回去」。用
 * 「本地坐标 + 我们请求的窗口位置」能把这个偏差补回来，**但补偿会过量**：
 * 实测（`input swipe` 400ms/200px，日志里 `local` 与 `getLocationOnScreen()`
 * 并排打出来）本地坐标用的窗口位置比我们刚设的目标**滞后约两帧**，于是每帧
 * 都多补 100~1000px——窗口先跟手、再倒着窜、再顺着窜，就是「满屏幕抖动」。
 *
 * `MotionEvent.rawX/rawY` 是屏幕绝对坐标，与窗口有没有移动**无关**，
 * 窗口位置直接取「起始窗口位置 + 手指位移」，一步到位、没有任何读回反馈，
 * 也不可能累积误差（贴到屏幕边缘被夹住之后往回拖也不会粘）。
 */
internal class WindowDragTracker(private val touchSlop: Float) {

    /** 按下那一刻的手指屏幕坐标——也是拖动的基准点。 */
    var downX = 0f
        private set
    var downY = 0f
        private set

    private var dragging = false

    /**
     * 已经松手、还没重新按下。
     *
     * 单独一个字段而不是复用 `dragging`：松手之后如果还收到 MOVE，
     * 光看 `dragging == false` 会把它当成**新的一次拖动开始**，
     * 于是一个迟到的 MOVE 就能把窗口莫名拽走一大截。
     */
    private var released = false

    fun down(rawX: Float, rawY: Float) {
        downX = rawX
        downY = rawY
        dragging = false
        released = false
    }

    fun move(rawX: Float, rawY: Float): WindowDragEvent {
        if (released) return WindowDragEvent.NONE
        if (!dragging) {
            val dx = rawX - downX
            val dy = rawY - downY
            if (dx * dx + dy * dy < touchSlop * touchSlop) return WindowDragEvent.NONE
            dragging = true
            return WindowDragEvent.START
        }
        return WindowDragEvent.MOVE
    }

    /** 松手。只有真的拖过才返回 [WindowDragEvent.END]——单纯点一下不该写盘。 */
    fun up(): WindowDragEvent {
        released = true
        if (!dragging) return WindowDragEvent.NONE
        dragging = false
        return WindowDragEvent.END
    }
}

