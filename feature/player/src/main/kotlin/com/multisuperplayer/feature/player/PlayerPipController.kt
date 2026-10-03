package com.multisuperplayer.feature.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.player.MspVideoSize
import kotlin.math.roundToInt

private const val TAG = "PlayerPipController"

/**
 * 画中画窗口的外壳。
 *
 * ## 为什么这一层需要存在
 *
 * 画中画的所有入口都长在 `android.app.Activity` 上（`enterPictureInPictureMode` /
 * `setPictureInPictureParams` / `isInPictureInPictureMode`），而这一页所在的是
 * `feature:player` 模块——它**不能**认识 `app` 模块里的 `MainActivity`。
 * 于是这里照 `PlayerWindowController` 的老路子办：只拿到一个 `Activity` 接口，
 * 调框架方法，类型上不依赖谁实现了它。
 *
 * ## 播放会不会被打断
 *
 * 不会。`ExoPlayer` 由 Koin 单例 [com.multisuperplayer.core.player.PlaybackController]
 * 持有，画中画只改 Activity 的可见性，不碰播放内核。所以这里**没有**任何
 * 「进画中画前先保活」的逻辑——那种代码看着像保险，实际只会掩盖真正的耦合问题。
 *
 * ## 版本分支
 *
 * - `enterPictureInPictureMode(params)` / `setPictureInPictureParams` / `isInPictureInPictureMode`
 *   是 **API 26+**（我们的 minSdk 就是 26），不需要判断。
 * - `setAutoEnterEnabled` / `setSeamlessResizeEnabled` 是 **API 31+**。低版本上
 *   「划走自动进画中画」这个手势不存在，只能靠用户点我们的入口按钮——所以
 *   下面每一处用到它们的地方都带版本判断，而不是整块功能被关掉。
 */
@Stable
class PlayerPipController(
    private val activity: Activity?,
    private val context: Context?,
) {

    /**
     * 这台设备有没有画中画的系统特性。
     *
     * 只查特性不够严谨（Activity 还得在清单里声明 `supportsPictureInPicture`），
     * 但清单声明是**编译期确定**的，而特性是**设备相关**的——所以只有后者
     * 值得在运行时问一次，前者漏了的话框架会返回 false / 抛异常，下面都接了。
     */
    val isSupported: Boolean =
        activity != null &&
            context != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** 进画中画时注册的回调，用于 [stopObservingModeChanges] 反注册。 */
    private var modeConsumer: Consumer<PictureInPictureModeChangedInfo>? = null

    /**
     * 带着当前画面尺寸进画中画。
     *
     * @return 是否真的进去了。false 的场合调用方**不需要**做任何补救：
     *   控件没动、状态没动，用户看到的就是「点了没反应」，比把一个半残的
     *   小窗口交给他好。
     */
    fun enter(video: MspVideoSize, isPlaying: Boolean): Boolean {
        val activity = activity ?: return false
        if (!isSupported) {
            MspLog.d(TAG) { "这台设备没有画中画特性" }
            return false
        }

        // 方向锁必须先解开。
        //
        // 这一页在横屏全屏时会把 Activity 锁死在 `SENSOR_LANDSCAPE`
        // （见 PlayerFullscreenEffect）。带着这个锁进画中画，系统会按「一个被
        // 锁成横屏的 Activity」去算窗口尺寸，在部分 ROM 上直接拒绝进入，
        // 在另一部分上画出一个比例不对的窗口。进画中画之后方向锁也没有意义了
        // ——窗口大小是用户自己拖的。
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        return try {
            val entered = activity.enterPictureInPictureMode(
                buildParams(video = video, isPlaying = isPlaying, autoEnter = false),
            )
            if (entered) {
                MspLog.i(TAG) { "进入画中画" }
            } else {
                MspLog.w(TAG) { "系统拒绝了进入画中画的请求" }
            }
            entered
        } catch (e: Exception) {
            // 清单没声明 supportsPictureInPicture 时这里会抛 IllegalStateException
            // （异常文案是「Activity must support picture in picture mode」）。
            // 这种错只能靠日志发现，不能让它冒到 composition 上去。
            MspLog.w(TAG, e) { "进入画中画失败" }
            false
        }
    }

    /**
     * 同步画中画参数。
     *
     * 三件事都要跟着变：**窗口形状**（换集换片源）、**动作按钮上的图标**
     * （播/暂停要跟着切）、以及**划走时要不要自动进画中画**（[autoEnter]）。
     *
     * @param autoEnter 只有正在播视频时为 true。音频**必须**是 false：否则用户
     *   在音频播放页划一下手势，会得到一个纯黑的小窗口。低于 API 31 时这个参数
     *   被忽略（那里没有自动进入这回事）。
     */
    fun update(video: MspVideoSize, isPlaying: Boolean, autoEnter: Boolean) {
        val activity = activity ?: return
        if (!isSupported) return
        try {
            activity.setPictureInPictureParams(
                buildParams(video = video, isPlaying = isPlaying, autoEnter = autoEnter),
            )
        } catch (e: Exception) {
            MspLog.w(TAG, e) { "更新画中画参数失败" }
        }
    }

    /**
     * 离开播放页时清场。
     *
     * **必须做**：`PictureInPictureParams` 是挂在 Activity 上的，不是挂在
     * composition 上的。不清的话，`setAutoEnterEnabled(true)` 会一直留着——
     * 用户回到媒体库、按一下 Home，整个**媒体库**会被塞进画中画窗口里。
     */
    fun release() {
        val activity = activity ?: return
        if (!isSupported) return
        try {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().build())
        } catch (e: Exception) {
            MspLog.w(TAG, e) { "清空画中画参数失败" }
        }
    }

    /**
     * 订阅「进/出了画中画」。
     *
     * 用 `androidx.activity` 的监听器而不是重写 Activity 的
     * `onPictureInPictureModeChanged`：后者只在 `:app` 模块的 `MainActivity` 里
     * 写得到，而播放页不能反过来依赖 app 模块（架构会颠倒）。
     *
     * 重复调用会先反注册上一次，避免回调被叠着调两遍（进一次画中画触发两次
     * 状态写入本身无害，但那种「多注册一份」的账迟早会在别处出事）。
     */
    fun observeModeChanges(onChanged: (Boolean) -> Unit) {
        val activity = activity as? ComponentActivity ?: return
        stopObservingModeChanges()
        val consumer = Consumer<PictureInPictureModeChangedInfo> { info ->
            onChanged(info.isInPictureInPictureMode)
        }
        modeConsumer = consumer
        activity.addOnPictureInPictureModeChangedListener(consumer)
    }

    /** 反注册 [observeModeChanges]。 */
    fun stopObservingModeChanges() {
        val activity = activity as? ComponentActivity ?: return
        modeConsumer?.let { activity.removeOnPictureInPictureModeChangedListener(it) }
        modeConsumer = null
    }

    private fun buildParams(
        video: MspVideoSize,
        isPlaying: Boolean,
        autoEnter: Boolean,
    ): PictureInPictureParams {
        val ratio = PlayerPipRules.aspectRatio(video)
        val builder = PictureInPictureParams.Builder()
            // 系统的 Rational 不接受 NaN / 0 / 负数，而 PlayerPipRules 已经
            // 保证给出的比值是有限的、落在 [MIN, MAX] 里。
            .setAspectRatio(
                Rational((ratio * RATIONAL_SCALE).roundToInt(), RATIONAL_SCALE.toInt()),
            )

        playPauseAction(isPlaying)?.let { builder.setActions(listOf(it)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            // 画中画窗口在「用户拖着改大小」和「全屏切回小窗」两种情况下都允许
            // 平滑缩放，否则系统会先按旧尺寸画一帧、再跳到新尺寸。
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * 画中画窗口里那个播放/暂停按钮。
     *
     * 没有它就只能用通知栏或者耳机按键暂停——而画中画存在的意义正是
     * 「一边看一边干别的」，让用户为此回到通知栏是荒谬的。
     *
     * 返回 null 的两种情形（设备没有位子、拿不到 Context）都不该让整个
     * 画中画功能失效：动作按钮是锦上添花，窗口本身才是功能。
     */
    private fun playPauseAction(isPlaying: Boolean): RemoteAction? {
        val context = context ?: return null
        val activity = activity ?: return null
        if (activity.maxNumPictureInPictureActions <= 0) return null

        val iconRes = if (isPlaying) R.drawable.msp_pip_pause else R.drawable.msp_pip_play
        // 标题复用控制条上同一个词的文案（`msp_player_play` / `msp_player_pause`），
        // 不另开两个「画中画专用」的键：词完全一样，多出来的两份文案只会在
        // 某一次翻译改动里分叉成两个说法。
        val titleRes = if (isPlaying) R.string.msp_player_pause else R.string.msp_player_play
        val title = context.getString(titleRes)
        val intent = Intent(context, PlayerPipActionReceiver::class.java)
            .setAction(PlayerPipActionReceiver.ACTION_TOGGLE_PLAY_PAUSE)

        // FLAG_IMMUTABLE 是 API 23+ 且**必须**显式给：目标 SDK 31+ 上省略它会抛
        // 「Mutable implicit PendingIntent」异常。FLAG_UPDATE_CURRENT 保证图标
        // 从播切到暂停之后，同一个 requestCode 上的 PendingIntent 用的是新 Intent。
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_TOGGLE_PLAY_PAUSE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent,
        )
    }

    private companion object {
        /** 把浮点宽高比换成有理数的分母：三位小数足够区分 16:9 和 21:9。 */
        const val RATIONAL_SCALE = 1000f

        /** 播放/暂停按钮的 PendingIntent 请求码（同一个位子上复用）。 */
        const val REQUEST_TOGGLE_PLAY_PAUSE = 9001
    }
}

/**
 * 记住一个 [PlayerPipController]，并在离开播放页时清掉画中画参数。
 *
 * 清理放在这里而不是调用点：只要有人在用这个控制器，它就会负责把 Activity 上
 * 那份状态收干净——忘了清的症状（媒体库被塞进画中画窗口）离得足够远，
 * 指望下一个人在调用点记得写是不现实的。
 */
@Composable
internal fun rememberPlayerPipController(): PlayerPipController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity, context) { PlayerPipController(activity, context) }
    DisposableEffect(controller) {
        onDispose {
            controller.stopObservingModeChanges()
            controller.release()
        }
    }
    return controller
}
