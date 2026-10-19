package com.multisuperplayer.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.settings.DesktopLyricsPosition
import com.multisuperplayer.core.data.settings.clampDesktopLyricsPosition
import kotlin.math.roundToInt

/**
 * 桌面歌词那个 `TYPE_APPLICATION_OVERLAY` 窗口的全部「脏」细节。
 *
 * 只做三件事：把 [ComposeView] 挂上去/摘下来、跟着手指挪窗口、把挪到的位置写回。
 * 「什么时候该出现」这类判断一律放在 [DesktopLyricsOverlayHost]，
 * 「显示成什么样」放在 [DesktopLyricsOverlayContent]——这里只跟 `WindowManager`
 * 打交道，方便单独推理。
 *
 * ## 位置只在「尺寸已知」之后才夹取
 *
 * `WRAP_CONTENT` 的窗口在 `addView` 之前没有宽高，用 0 去夹等于把所有位置夹成 (0,0)。
 * 所以先按上次记住的位置挂上去，等第一帧布局出来拿到真实尺寸再校正一次。
 *
 * ## 谁有权改窗口坐标
 *
 * 三个写入方，必须分清楚，否则就会互相打架（表现为「拖着的时候窗口满屏幕乱跳」）：
 *
 * 1. **手指**（[dragStart]/[dragMove]/[dragEnd]）——拖动期间它是唯一权威。
 * 2. **设置流**（[onSettingsPosition]）——只有「值真的变了」才算数。宿主把
 *    `controller.state` 整个 `combine` 在一起，而它里面带着 `positionMs`，播放时
 *    **每秒会重发五次**；把每次重发都当成「用户改了位置」去摆窗口，等于在拖动中
 *    每 200ms 把窗口从手指底下拽回原地。
 * 3. **尺寸/屏幕变化**（[onLayoutChanged]/[reclamp]）——只把当前坐标**夹回可见范围**，
 *    绝不重新计算位置：歌词从两行变三行、换字号都会触发它，重算就等于让窗口
 *    随着歌词换行自己横跳（默认位置是「按当前宽度居中」，宽度一变 x 就变）。
 *    宽度本身已经固定成屏幕的 92%（见 [desktopLyricsWindowWidthDp]），所以现在
 *    只有高度会变，而「只夹不重算」对高度同样正确：贴边放着的时候窗口长高
 *    会往下探出屏幕，夹一下就好，不该把它整体挪走。
 */
internal class DesktopLyricsWindow(
    private val context: Context,
    private val controller: DesktopLyricsController,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    /** 上次记住的位置（用户拖到的、或设置里重置成「未设置」）。 */
    private var savedPosition: DesktopLyricsPosition = DesktopLyricsPosition.UNSET

    /**
     * 还没摆成的位置（窗口还没有尺寸时挂在这里，等第一次布局回调）。
     *
     * 不能只是「摆一次就算」：`addView` 之前窗口宽高是 0，夹取会把位置夹成 (0,0)，
     * 所以第一次必须等到布局出来；也不能每次布局都重摆，那样内容一换行就跳。
     */
    private var pendingPosition: DesktopLyricsPosition? = null

    /** 上一次从设置流里收到的位置，用来认出「同一个值又发了一遍」。 */
    private var lastSeenPosition: DesktopLyricsPosition? = null

    /**
     * 松手后「等写盘回环」的那一小段时间里，认下自己刚存的值。
     *
     * 松手 → 写 DataStore → 设置流发出新值，这中间有一段延迟。这段时间里设置流
     * 发出来的还是**旧位置**，照它摆一次就是把窗口从手指松开的地方拽回原处。
     */
    private var settlingPosition: DesktopLyricsPosition? = null
    private var settleDeadlineMs = 0L

    /**
     * 拖动中。
     *
     * 拖动时的每一帧都会改窗口参数，而那会再触发一次布局回调；回调里如果
     * 又按 `savedPosition`（还停在拖动开始的位置）摆一次，窗口就会一边跟手一边
     * 被拽回去。
     */
    private var dragging = false

    /** 拖动开始那一刻：手指在**屏幕上**的位置 + 窗口当时的位置。 */
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0

    private val actions = DesktopLyricsActions(
        onDragStart = ::dragStart,
        onDragMove = ::dragMove,
        onDragEnd = ::dragEnd,
        onTogglePlay = controller::togglePlayPause,
        onLock = { controller.setLocked(true) },
        onFontStep = { step ->
            controller.setFontSize(
                stepDesktopLyricsFontSize(controller.state.value.style.fontSize, step),
            )
        },
        onOpenApp = { DesktopLyricsIntents.openApp(context) },
    )

    val isShowing: Boolean get() = view != null

    /** 挂上窗口（已经在显示就只把位置挪一下）。 */
    fun show(position: DesktopLyricsPosition) {
        if (view != null) {
            onSettingsPosition(position)
            return
        }
        savedPosition = position
        lastSeenPosition = position
        pendingPosition = position
        val windowManager = windowManager ?: return
        val owner = OverlayLifecycleOwner().apply { create() }
        val composeView = ComposeView(context).apply {
            // 摘下来就销毁组合：浮窗会反复开关，留着组合等于把整棵 UI 树
            // （以及它订阅着的歌词流）一直挂在那儿。
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                // 这里用 `collectAsState` 而不是 `collectAsStateWithLifecycle`：
                // 后者要一个会自己走 STARTED/RESUMED 的生命周期，而上面这个 owner
                // 从创建到销毁一直是 RESUMED——多一层判断只会让它更容易停在
                // 「暂停收集」上，那样歌词就不动了。
                val state by controller.state.collectAsState()
                DesktopLyricsOverlayContent(state = state, actions = actions)
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onLayoutChanged() }
        }
        val layoutParams = WindowManager.LayoutParams(
            fixedWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { windowManager.addView(composeView, layoutParams) }
            .onFailure { error ->
                // 没有悬浮窗权限（用户在设置里刚撤掉）时这里会抛 SecurityException。
                // 不往上层抛：一个画不出来的浮窗不该让播放流程崩掉。
                MspLog.w(TAG, error) { "挂不上桌面歌词窗口" }
                owner.destroy()
                return
            }
        view = composeView
        params = layoutParams
        lifecycleOwner = owner
    }

    fun hide() {
        val windowManager = windowManager ?: return
        val composeView = view ?: return
        view = null
        params = null
        dragging = false
        pendingPosition = null
        lastSeenPosition = null
        settlingPosition = null
        runCatching { windowManager.removeView(composeView) }
            .onFailure { MspLog.w(TAG, it) { "摘除桌面歌词窗口失败" } }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    /**
     * 锁定 = 整块穿透：手指落到歌词上时交给下面的应用处理。
     *
     * 解锁只能在应用里的播放页做——锁定之后这里连一个按钮都点不到，而
     * 「点不到还以为锁上了」比「必须回应用解锁」更糟。按钮文案见播放页。
     */
    fun setLocked(locked: Boolean) {
        val layoutParams = params ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (locked) layoutParams.flags or flag else layoutParams.flags and flag.inv()
        if (next == layoutParams.flags) return
        layoutParams.flags = next
        updateLayout()
    }

    /**
     * 设置流送来一个位置：只有**值真的变了**才动窗口。
     *
     * 用户拖过、或在设置页点了「重置位置」（值变回未设置）都会走这里。
     *
     * 判断全部交给纯函数 [desktopLyricsPositionStep]（它有单测）：这里只按它的
     * 结论改状态。这段逻辑以前是内联的，而内联版本漏掉了「拖动中不算数」，
     * 于是每 200ms 就跟着播放进度来一次的旧位置会在拖动途中把窗口拽回去
     * （v1.4.5 修的就是这个）。
     */
    private fun onSettingsPosition(position: DesktopLyricsPosition) {
        val step = desktopLyricsPositionStep(
            incoming = position,
            lastSeen = lastSeenPosition,
            dragging = dragging,
            settling = settlingPosition,
            settleExpired = SystemClock.uptimeMillis() >= settleDeadlineMs,
        )
        // 拖动中要立刻返回：这时候连 lastSeen 都不能动，
        // 否则松手后同一个值会被当成「新值」再摆一次。
        if (step == DesktopLyricsPositionStep.IGNORE_WHILE_DRAGGING) return
        lastSeenPosition = position
        if (step.endSettle) settlingPosition = null
        if (!step.apply) return
        savedPosition = position
        if (!applyPosition(position)) pendingPosition = position
    }

    /**
     * 窗口尺寸变了（换字号、多了一行译文、旋转）。
     *
     * 只做两件事：把还没摆成的第一次位置摆上；把当前坐标**夹回**可见范围。
     * 绝不重新计算位置——歌词一行变两行是常态，重算会让窗口跟着换行横向跳。
     */
    private fun onLayoutChanged() {
        if (dragging) return
        syncWidth()
        val pending = pendingPosition
        if (pending != null && applyPosition(pending)) {
            pendingPosition = null
            return
        }
        clampCurrent()
    }

    /**
     * 浮窗的固定宽度（像素）：屏幕宽度的 92%，见 [desktopLyricsWindowWidthDp]。
     *
     * 宽度只能在这里给（窗口层），**不能靠 Compose 里的 `.width(...)`**：
     * `WRAP_CONTENT` 的浮窗交给 Compose 的父约束上限**不是屏幕宽**——实测
     * 411dp / 420dpi 的屏幕上 `constraints.maxWidth == 840px`（正好 320dp），
     * 而 `Modifier.width` 和 `Modifier.requiredWidth` 最终都会走
     * `Constraints.constrainWidth`，算出来 993px 也会被夹回 840（症状：
     * 「设了 92% 却只有 78%」，而且怎么调都到不了）。显式给窗口宽度之后，
     * Compose 那边只要 `fillMaxWidth()`。
     */
    private fun fixedWidthPx(): Int {
        val metrics = context.resources.displayMetrics
        val screenWidthDp = context.resources.configuration.screenWidthDp
        return (desktopLyricsWindowWidthDp(screenWidthDp) * metrics.density).roundToInt()
    }

    /** 屏幕尺寸变了（旋转、分屏、改分辨率）就跟着改窗口宽度。 */
    private fun syncWidth() {
        val layoutParams = params ?: return
        val width = fixedWidthPx()
        if (layoutParams.width == width) return
        layoutParams.width = width
        updateLayout()
    }

    /**
     * 屏幕尺寸可能变了（旋转、分屏、改分辨率）之后重新夹取一次。
     *
     * 活动重建不会改变 `enabled` / 歌词内容，所以状态流不会再发一次值；
     * 不主动夹一把的话，竖屏贴在底部的歌词横屏后可能整块都在屏幕外。
     */
    fun reclamp() {
        if (!isShowing) return
        onLayoutChanged()
    }

    // ------------------------------------------------------------------ 拖动

    private fun dragStart(rawX: Float, rawY: Float) {
        val layoutParams = params ?: return
        dragging = true
        // 手指一按住，上一轮「等回环」的窗口期就该结束了：拖动期间不接受设置值。
        settlingPosition = null
        pendingPosition = null
        dragStartRawX = rawX
        dragStartRawY = rawY
        dragStartWindowX = layoutParams.x
        dragStartWindowY = layoutParams.y
    }

    /**
     * 手指现在在屏幕的 [rawX]/[rawY] 处。
     *
     * 直接「起始窗口位置 + 手指走过的距离」，**不读回任何东西**：
     * 不用 `layoutParams`（那是我们刚请求的目标，`updateViewLayout` 是异步的）、
     * 也不用 `getLocationOnScreen`（那是处理这一帧事件时的位置，而事件坐标
     * 是按**派发时**的窗口位置算的，两者本来就不重合）。
     *
     * 这就是「满屏幕抖动」的根因所在：拿本地坐标 + `layoutParams` 去补偿，
     * 实测窗口位置滞后约两帧 ⇒ 每帧都多补一大截，窗口先跟手、再倒着窜、
     * 再顺着窜。换了屏幕绝对坐标之后这条路彻底断掉：每一帧都是绝对量，
     * 没有反馈回路，也就没有累积误差（贴边被夹住再往回拖也不会粘）。
     */
    private fun dragMove(rawX: Float, rawY: Float) {
        moveTo(
            x = dragStartWindowX + (rawX - dragStartRawX),
            y = dragStartWindowY + (rawY - dragStartRawY),
        )
    }

    private fun dragEnd() {
        dragging = false
        val layoutParams = params ?: return
        val position = DesktopLyricsPosition(x = layoutParams.x, y = layoutParams.y)
        savedPosition = position
        // 写盘回环之前，设置流发的还是旧位置——先认下自己这个值。
        settlingPosition = position
        settleDeadlineMs = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        // 松手才写盘：拖一次几十帧，每帧一次 DataStore 事务既没必要也伤磁盘。
        controller.savePosition(position)
    }

    /** 把**当前**坐标夹回可见范围（不重新计算位置）。 */
    private fun clampCurrent() {
        val layoutParams = params ?: return
        moveTo(layoutParams.x.toFloat(), layoutParams.y.toFloat())
    }

    private fun moveTo(x: Float, y: Float) {
        val layoutParams = params ?: return
        val size = windowSize()
        val screen = screenSizePx()
        val clamped = clampDesktopLyricsPosition(
            x = x.roundToInt(),
            y = y.roundToInt(),
            windowWidth = size.width,
            windowHeight = size.height,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        val nextX = clamped.x ?: return
        val nextY = clamped.y ?: return
        if (nextX == layoutParams.x && nextY == layoutParams.y) return
        layoutParams.x = nextX
        layoutParams.y = nextY
        updateLayout()
    }

    /**
     * 按 [position] 摆好窗口。
     *
     * 位置「未设置」时用 [defaultDesktopLyricsPosition]（水平居中、贴底部），
     * 并且**不写盘**：没拖过就是没拖过，下次换设备/换分辨率还能重新算。
     */
    private fun applyPosition(position: DesktopLyricsPosition): Boolean {
        val layoutParams = params ?: return false
        val size = windowSize()
        if (size.width <= 0 || size.height <= 0) return false // 还没布局，等回调再来
        val screen = screenSizePx()
        val target = if (position.isSet) {
            DesktopLyricsPosition(position.x, position.y)
        } else {
            defaultDesktopLyricsPosition(
                screenWidth = screen.width,
                screenHeight = screen.height,
                windowWidth = size.width,
                windowHeight = size.height,
                // 贴底但别压住手势条/导航栏：留 96dp 左右的高度，
                // 换算成像素按 3x 屏算就是 288px 量级，够用且不会把歌词顶到屏幕中间。
                bottomGapPx = (96 * context.resources.displayMetrics.density).roundToInt(),
            )
        }
        val clamped = clampDesktopLyricsPosition(
            x = target.x ?: 0,
            y = target.y ?: 0,
            windowWidth = size.width,
            windowHeight = size.height,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        val nextX = clamped.x ?: return false
        val nextY = clamped.y ?: return false
        if (nextX == layoutParams.x && nextY == layoutParams.y) return true
        layoutParams.x = nextX
        layoutParams.y = nextY
        updateLayout()
        return true
    }

    private fun updateLayout() {
        val windowManager = windowManager ?: return
        val composeView = view ?: return
        val layoutParams = params ?: return
        runCatching { windowManager.updateViewLayout(composeView, layoutParams) }
            .onFailure { MspLog.w(TAG, it) { "更新桌面歌词窗口失败" } }
    }

    private fun windowSize(): Size {
        val composeView = view ?: return Size(0, 0)
        return Size(composeView.width, composeView.height)
    }

    @SuppressLint("DiscouragedApi")
    private fun screenSizePx(): Size {
        val windowManager = windowManager ?: return Size(0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Size(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels)
    }

    private companion object {
        const val TAG = "DesktopLyrics"

        /**
         * 松手后最多等这么久让「刚存的值」从 DataStore 回环回来。
         *
         * 超时只是兜底：拖到的位置与存着的一模一样时 DataStore 不会再发一次值，
         * 否则这里会一直以为「还在等回环」，之后用户在设置里重置位置就不生效了。
         */
        const val SETTLE_TIMEOUT_MS = 1_500L
    }
}

/**
 * 一个只为了让 `ComposeView` 能跑起来的最小 `LifecycleOwner`。
 *
 * 组合必须拿到 `ViewTreeLifecycleOwner`（`AbstractComposeView` 找不到就直接抛），
 * 而悬浮窗挂在 Service 上，周围没有任何现成的 owner。`SavedStateRegistryOwner`
 * 一起给：`rememberSaveable` 一类的 API 要它，虽然这里用不到，但缺了就是崩溃。
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
