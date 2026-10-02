package com.multisuperplayer.player

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.multisuperplayer.core.data.settings.wrapLocale

/**
 * 唯一的 Activity。
 *
 * 单 Activity + Compose 导航：媒体播放器在后台/画中画/投屏之间来回切换时，
 * 多 Activity 的栈管理会变成噩梦（比如从通知点进来时栈里已经有播放页），
 * 单 Activity 下这些只是导航状态，好推理得多。
 */
class MainActivity : ComponentActivity() {

    /**
     * 语言要在主题和 Compose 内容之前定下来，所以套在 base 上。
     *
     * [MspApplication] 已经套过一次了，这里还要再来一次，是因为 Activity 拿到的
     * base 不保证是那个已套过的 Context（通知、画中画、系统重建都走不同的路径）。
     * 重复套是幂等的：读到的还是同一个标签，套出来的还是同一个 Configuration。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 视频要铺到状态栏/导航栏后面，控件自己再加 inset。
        // 在 setContent 之前调用，避免主题（状态栏图标明暗）用错值。
        enableEdgeToEdge()

        setContent {
            MspApp()
        }
    }
}
