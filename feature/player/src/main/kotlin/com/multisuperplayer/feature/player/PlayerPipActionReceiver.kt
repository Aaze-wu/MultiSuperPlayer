package com.multisuperplayer.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.player.PlaybackController
import org.koin.core.context.GlobalContext

private const val TAG = "PlayerPipActionReceiver"

/**
 * 画中画小窗口里那唯一一个按钮（播放/暂停）的接收方。
 *
 * ## 为什么要有它
 *
 * 小窗口上的按钮不是我们的 Compose 控件，而是系统画的 `RemoteAction`，
 * 触发方式是 `PendingIntent`。而 PendingIntent 只能指向「组件」——Activity、
 * Service、BroadcastReceiver 三者之一：
 *
 * - 指向 Activity：会把小窗口**撑回全屏**，正好和画中画的目的相反；
 * - 指向 Service：我们唯一的 Service 是 `MspPlaybackService`（Media3 的会话服务），
 *   它不认识任意自定义 Intent，得再套一层转发；
 * - 指向广播：最轻，而且下面这五行就是全部逻辑。
 *
 * ## 为什么取依赖失败只记一条日志
 *
 * 接收器是**系统构造**的，不是我们构造的。Koin 在 `Application.onCreate` 里启动，
 * 能进画中画就说明进程还活着、Koin 早就启动了，所以正常路径上取得到。
 * 但这里仍然不抛异常：抛出去会让系统把这条动作判成「应用无响应」，
 * 而且小窗口上的按钮会**从此再也不工作**（接收器实例已经被系统回收掉了）。
 * 宁可什么都不做也不要崩——播放本身还在继续。
 */
class PlayerPipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_PLAY_PAUSE) {
            MspLog.d(TAG) { "收到不认识的动作：${intent.action}" }
            return
        }

        val controller = runCatching {
            GlobalContext.get().get<PlaybackController>()
        }.getOrNull()

        if (controller == null) {
            MspLog.w(TAG) { "拿不到播放内核，画中画按钮这次忽略" }
            return
        }

        // 内核自己保证在主线程上改播放器状态，这里直接调就行。
        controller.togglePlayPause()
    }

    companion object {
        /** 动作名带包名，避免和别人的广播撞上（虽然这个接收器并不导出）。 */
        const val ACTION_TOGGLE_PLAY_PAUSE: String =
            "com.multisuperplayer.feature.player.action.PIP_TOGGLE_PLAY_PAUSE"
    }
}
