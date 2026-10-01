package com.multisuperplayer.core.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.multisuperplayer.core.common.log.MspLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "MspPlaybackService"

/**
 * 后台播放服务。
 *
 * ## 它做什么
 *
 * 把已经存在的播放内核包一层 [MediaSession]，从而得到：
 * - 通知栏 / 锁屏 / 蓝牙耳机 / 车机的播放控制；
 * - 进程进入后台后继续播放（前台服务）；
 * - 系统媒体面板（Android 11+ 的快速设置里的媒体卡片）。
 *
 * ## 它**不**做什么（重要）
 *
 * 它不创建、也不释放 `ExoPlayer`。
 *
 * Media3 的教科书用法是把播放器交给 `MediaSessionService` 持有，但那样会带来一个
 * 后果：**UI 必须通过 `MediaController` 跨进程/跨服务地间接操作播放器**，
 * 于是「读播放状态」变成异步的，`StateFlow` 全部要重写。
 *
 * 这里选择让 `ExoPlayer` 由 Koin 单例持有（见 [di.playerModule]），Service 只在
 * 它外面套一层会话壳。好处是 UI 拿到的状态依然是同步可读的 StateFlow；
 * 代价是 `onDestroy` 里**不能** release 播放器（那是销毁单例，用户切歌再回来就崩），
 * 这一点在 [onDestroy] 里显式写了。
 *
 * 因此：进程被杀 → 播放中断且不会自动恢复。这是当前阶段的已知取舍，
 * 需要「被杀后能恢复」时再换成真正的 `MediaController` 架构。
 */
class MspPlaybackService : MediaSessionService(), KoinComponent {

    private val controller: PlaybackController by inject()

    /** 为 null 表示会话已经释放（[onDestroy] 之后）。 */
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val session = MediaSession.Builder(this, controller.player)
            .setId(SESSION_ID)
            .apply {
                // 点通知回到应用。用 packageManager 里的启动 Intent，
                // 这样 core:player 不需要知道 app 模块的 Activity 类名——
                // 反过来依赖会让 core 依赖 feature 层，架构就颠倒了。
                launchIntent()?.let { setSessionActivity(it) }
            }
            .build()

        mediaSession = session
        setMediaNotificationProvider(buildNotificationProvider())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉应用。
     *
     * 「划掉界面不打断音乐」是必须保证的行为，而父类默认实现在**没有播放**时会回收
     * 服务、在**有播放**时的行为历史上随版本变化过（早期版本会
     * `pauseAllPlayersAndStopSelf()`，那会把正在播放的音乐也停掉）。
     * 与其依赖一个会变的行为，不如这里显式表态：在播就不碰，没在播才交给父类回收。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isPlaybackOngoing()) {
            MspLog.d(TAG) { "任务被划掉，但正在播放，保持后台播放" }
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 只释放会话，**不**释放播放器——播放器是 Koin 单例，生命周期属于整个进程。
        //
        // 这里 release 掉播放器会造成：服务被系统回收（例如暂停时划掉任务）之后，
        // 用户回到应用点播放 → 用的是已 release 的 ExoPlayer → IllegalStateException。
        // 这类崩溃只在"暂停→划掉→回来"这个路径上出现，非常难复现。
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------- 私有

    private fun buildNotificationProvider(): DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider(
            this,
            // SAM 转换：通知 id 固定即可，同一个应用同时只有一个播放会话。
            { NOTIFICATION_ID },
            CHANNEL_ID,
            R.string.msp_playback_channel_name,
        ).apply {
            // 不设置的话用的是 Media3 自带的音符图标，能用但不是我们的品牌。
            setSmallIcon(R.drawable.msp_ic_notification_playback)
        }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            intent,
            // FLAG_IMMUTABLE 在 Android 12+ 是强制的：可变 PendingIntent 会被
            // 系统拒绝。这里也不需要被外部填充，所以直接声明不可变。
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /**
         * 通知渠道的 id。
         *
         * 不要在版本之间改它：改了等于新开一个渠道，用户的「静音此渠道」设置会失效，
         * 于是我们刚被静音过的东西又会响一次。
         */
        const val CHANNEL_ID = "msp_playback"

        /** 固定通知 id：同时只会有一个播放通知。 */
        const val NOTIFICATION_ID = 1001

        private const val SESSION_ID = "msp_playback_session"
    }
}
