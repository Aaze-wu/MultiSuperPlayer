package com.multisuperplayer.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 播放页手势要改写的两个**窗口级**的量：亮度、系统媒体音量。
 *
 * 两者都不属于播放内核（内核只管解码和播放），也不属于数据层（不该持久化），
 * 所以单独放在这一层，做成一个可注入的薄壳：手势那部分只依赖它的两个方法，
 * 单测里换成假的就能把「拖到底会怎样」这类边界测完，不需要真的有个窗口。
 *
 * ## 亮度只改**当前窗口**，绝不写系统设置
 *
 * `Window.attributes.screenBrightness` 是一个**只在当前窗口生效**的覆盖值，
 * 退出这个页面（或者进程死掉）就自动失效。写 `Settings.System.SCREEN_BRIGHTNESS`
 * 是另一回事：那需要 `WRITE_SETTINGS` 特殊权限（要跳系统设置页让用户手动同意），
 * 而且改完是**全局永久**的——用户看完一部电影，回到桌面发现整个手机还是那么暗。
 */
@Stable
class PlayerWindowController(
    private val activity: Activity?,
    private val audioManager: AudioManager?,
) {

    // ---------------------------------------------------------------- 亮度

    /**
     * 当前亮度（[PlayerGestures.LevelRange.Brightness] 范围内）。
     *
     * 没设过覆盖值时读**系统**亮度：手势从「我看到的亮度」开始算才符合直觉。
     * 如果这里固定返回 1.0，那么在一块已经调暗的手机上，第一次向上拖会让屏幕
     * 突然变亮——因为起点错了。
     */
    fun currentBrightness(): Float {
        val window = activity?.window ?: return 1f
        val override = window.attributes.screenBrightness
        if (override >= 0f) {
            return PlayerGestures.LevelRange.Brightness.clamp(override)
        }
        return PlayerGestures.LevelRange.Brightness.clamp(systemBrightness())
    }

    fun setBrightness(level: Float) {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = PlayerGestures.LevelRange.Brightness.clamp(level)
        window.attributes = attributes
    }

    /** 离开播放页时把覆盖还给系统（不调的话亮度会一直停在用户最后拖到的值）。 */
    fun clearBrightness() {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    private fun systemBrightness(): Float {
        val context = activity ?: return 1f
        return try {
            // 不需要权限：SCREEN_BRIGHTNESS 是**可读**的（只有写才需要 WRITE_SETTINGS）。
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            (raw / 255f).coerceIn(0f, 1f)
        } catch (e: Exception) {
            // 厂商定制系统上偶发 SecurityException / SettingNotFoundException。
            // 读不到亮度不该让手势崩掉，退化成 1.0（= 不覆盖）即可。
            Log.w(TAG, "读取系统亮度失败，按 1.0 处理", e)
            1f
        }
    }

    // ---------------------------------------------------------------- 音量

    /**
     * 系统媒体音量（0~1）。
     *
     * 用的是 `STREAM_MUSIC`，不是播放器内部音量（[com.multisuperplayer.core.player.PlaybackController.setVolume]）：
     * 用户按音量键改的就是这个，手势跟着它才和按键一致。两个音量分开是另一件事，
     * 界面上也没有任何地方暴露内部音量，所以不必担心混淆。
     */
    fun currentSystemVolume(): Float {
        val manager = audioManager ?: return 1f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    }

    fun setSystemVolume(level: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val target = (PlayerGestures.LevelRange.Volume.clamp(level) * max).toInt().coerceIn(0, max)
        try {
            // flag 传 0：不弹系统自己的音量条。我们没有用 `setVolumeControlStream`，
            // 所以系统条不会自己冒出来，但显式传 0 让意图不受那些默认值影响。
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            // 勿扰模式 / 厂商策略会拒绝改音量。手势在那种机器上是无效的，
            // 但绝不能因此崩溃。
            Log.w(TAG, "设置系统音量被拒绝", e)
        }
    }

    private companion object {
        const val TAG = "PlayerWindowController"
    }
}

/**
 * 取出当前上下文的 [Activity]。
 *
 * 不用 `LocalContext.current as Activity`：在 Compose 预览、以及被 `ContextWrapper`
 * 包一层的场景（`ComponentActivity` 之外还有 `androidx` 的包装）里那个强制转换会崩。
 * 一路沿着 `baseContext` 往上找，找不到就返回 null——[PlayerWindowController] 的
 * 每个方法都对 null 有定义好的退路（什么也不做），所以「没有 Activity」是安全状态。
 *
 * `internal` 而不是 `private`：[PlayerFullscreenEffect] 也要拿同一个 Activity
 * （全屏要改的是**同一个窗口**的方向和系统栏）。各写一份向上查找会分叉。
 */
internal tailrec fun Context?.findActivity(): Activity? = when (this) {
    null -> null
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 记住一个 [PlayerWindowController]。
 *
 * 顺手在离开组合时清掉亮度覆盖：手势改的是窗口属性，而窗口是**整块**应用的，
 * 不清的话从播放页退到媒体库，整个应用还是暗的（用户会以为是系统出问题了）。
 */
@Composable
fun rememberPlayerWindowController(): PlayerWindowController {
    val activity = LocalContext.current.findActivity()
    val controller = remember(activity) {
        PlayerWindowController(
            activity = activity,
            audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
        )
    }
    // 离开组合时把亮度覆盖还回系统：手势改的是**窗口**属性，而窗口是整个应用的，
    // 不清的话从播放页退到媒体库，整个应用还是暗的（用户会以为系统出问题了）。
    DisposableEffect(controller) {
        onDispose { controller.clearBrightness() }
    }
    return controller
}
