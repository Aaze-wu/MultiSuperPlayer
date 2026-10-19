package com.multisuperplayer.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
     * 拖动中。
     *
     * 拖动时的每一帧都会改窗口参数，而那会再触发一次布局回调；回调里如果
     * 又按 `savedPosition`（还停在拖动开始的位置）摆一次，窗口就会一边跟手一边
     * 被拽回去。
     */
    private var dragging = false

    /** 拖动开始那一刻：手指在窗口内的位置 + 窗口当时的位置。 */
    private var dragStartLocalX = 0f
    private var dragStartLocalY = 0f
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
            applySavedPosition(position)
            return
        }
        savedPosition = position
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
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                // 尺寸变了（换字号、多一行译文）：重新夹一次，别让窗口有一半在屏幕外。
                if (!dragging) applyPosition(savedPosition)
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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

    /** 跟着设置走（用户拖过、或在设置页点了「重置位置」）。 */
    fun applySavedPosition(position: DesktopLyricsPosition) {
        savedPosition = position
        applyPosition(position)
    }

    /**
     * 屏幕尺寸可能变了（旋转、分屏、改分辨率）之后重新夹取一次。
     *
     * 活动重建不会改变 `enabled` / 歌词内容，所以状态流不会再发一次值；
     * 不主动夹一把的话，竖屏贴在底部的歌词横屏后可能整块都在屏幕外。
     */
    fun reclamp() {
        if (!isShowing) return
        applyPosition(savedPosition)
    }

    // ------------------------------------------------------------------ 拖动

    private fun dragStart(localX: Float, localY: Float) {
        val layoutParams = params ?: return
        dragging = true
        dragStartLocalX = localX
        dragStartLocalY = localY
        dragStartWindowX = layoutParams.x
        dragStartWindowY = layoutParams.y
    }

    /**
     * 手指移到窗口内 [localX]/[localY] 处（相对窗口左上角）。
     *
     * 窗口自己在动，所以本地坐标里已经把「窗口挪了多少」扣掉了。用
     * `params.x - dragStartWindowX` 把它加回去，就得到**手指在屏幕上真实走过的距离**。
     * 这个写法对「夹到屏幕边缘后手指继续往外走」也是自洽的：夹住时窗口没动，
     * 本地坐标的变化量就等于手指继续走的量，松手再往回拖不会有偏移累积。
     */
    private fun dragMove(localX: Float, localY: Float) {
        val layoutParams = params ?: return
        val appliedX = layoutParams.x - dragStartWindowX
        val appliedY = layoutParams.y - dragStartWindowY
        moveTo(
            x = dragStartWindowX + (localX + appliedX - dragStartLocalX),
            y = dragStartWindowY + (localY + appliedY - dragStartLocalY),
        )
    }

    private fun dragEnd() {
        dragging = false
        val layoutParams = params ?: return
        val position = DesktopLyricsPosition(x = layoutParams.x, y = layoutParams.y)
        savedPosition = position
        // 松手才写盘：拖一次几十帧，每帧一次 DataStore 事务既没必要也伤磁盘。
        controller.savePosition(position)
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
    private fun applyPosition(position: DesktopLyricsPosition) {
        val layoutParams = params ?: return
        val size = windowSize()
        if (size.width <= 0 || size.height <= 0) return // 还没布局，等回调再来
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
        val nextX = clamped.x ?: return
        val nextY = clamped.y ?: return
        if (nextX == layoutParams.x && nextY == layoutParams.y) return
        layoutParams.x = nextX
        layoutParams.y = nextY
        updateLayout()
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
