package com.multisuperplayer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.multisuperplayer.core.data.external.ExternalIntentReader
import com.multisuperplayer.core.data.external.PendingExternalPlayback
import com.multisuperplayer.core.data.settings.wrapLocale
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

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

        // 冷启动：在别的应用里点「用本应用打开」、从网页调起、分享过来，
        // intent 都是从这里进来的。
        //
        // 必须放在 `setContent` **之前**：反过来的话界面会先按「没有待播」
        // 画一帧媒体库，请求到了之后才跳播放页——看起来就是先闪一下列表。
        // 而 `submit` 是 suspend（要查 ContentResolver 拿文件名与大小），
        // 所以这里只是把它丢进协程，真正跳转由 `MspApp` 里那个收集点完成。
        handleExternalIntent(intent)

        setContent {
            MspApp()
        }
    }

    /**
     * 应用**已经在跑**的时候，新的 `ACTION_VIEW` / `ACTION_SEND` 走这里，不新建 Activity。
     *
     * 这是 [android:launchMode="singleTask"] 的直接后果，**不覆写这个方法的话请求会被
     * 系统静默丢掉**：没有任何异常、没有任何日志，用户看到的就是「点了没反应」，
     * 而在后台的应用还会因为「已经在栈顶」被重新抬到前台（看起来像「打开了但又没打开」）。
     * 典型的复现路径：先正常打开本应用 → 回桌面 → 在文件管理器里打开一个视频。
     *
     * `setIntent` 不能省：不换掉的话 `getIntent()` 永远停在最初那一次，
     * 以后任何读 `getIntent()` 的地方（包括系统重建时重新交给我们那份）
     * 拿到的都是上一次的地址。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * 把 intent 里的媒体交给 [PendingExternalPlayback]，**不在这里导航**。
     *
     * 分工是刻意的：Activity 只认「系统给了我什么」，`navController` 在 Compose 树里，
     * 由 `MspApp` 那一层负责「拿它干什么」。
     *
     * 认不出东西就什么都不做（`read` 返回 null）：这不是错误路径，而是**常态**——
     * 从桌面图标启动、系统重建、分享一个纯文本，走的都是同一条路。
     *
     * Koin 容器用 [GlobalContext] 取（而不是参数注入）：`MspApplication.onCreate` 已经
     * 在任何一个 Activity 之前启动了容器，所以这里取一定拿得到。这一份状态之所以不能是
     * ViewModel，见 [PendingExternalPlayback] 的类注释。用 `lifecycleScope` 而不是
     * `applicationScope`：解析结果最终是给这个 Activity 的界面用的，它没了就不用解析了。
     */
    private fun handleExternalIntent(intent: Intent?) {
        val payload = ExternalIntentReader.read(intent) ?: return
        val pending = GlobalContext.get().get<PendingExternalPlayback>()
        lifecycleScope.launch { pending.submit(payload) }
    }
}
