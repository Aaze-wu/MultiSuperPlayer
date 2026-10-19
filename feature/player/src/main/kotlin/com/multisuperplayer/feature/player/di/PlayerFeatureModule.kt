package com.multisuperplayer.feature.player.di

import com.multisuperplayer.feature.player.DesktopLyricsController
import com.multisuperplayer.feature.player.DesktopLyricsOverlayHost
import com.multisuperplayer.feature.player.PlayerViewModel
import com.multisuperplayer.feature.player.SubtitleViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 播放页功能域的依赖绑定。
 *
 * 名字带 `Feature` 是为了和 `:core:player` 里的 `playerModule` 区分开：
 * 那个绑定的是**播放内核**（`PlaybackController`），这个绑定的是**界面状态**。
 * 两者同名会让人以为 `PlaybackController` 是在这里注册的。
 */
val playerFeatureModule = module {
    viewModelOf(::PlayerViewModel)
    // 字幕单独一个 ViewModel：它的生命周期跟着「播放页这个导航项」，而字幕轨
    // 的加载是异步的、会失败、会被切歌打断——把这些塞进 PlayerViewModel 会让
    // 那个「刻意什么都不做」的转发层变成唯一一个有状态的地方。
    viewModelOf(::SubtitleViewModel)

    // 桌面歌词的状态源。必须是 `single`：浮窗活在导航图之外（Activity 之外
    // 的那个窗口），而播放页是 `viewModel` 作用域。每次注入都新建一个的话，
    // 两边会各自持有一份「当前字幕」，浮窗永远看不到播放页加载出来的那份。
    single {
        DesktopLyricsController(
            playback = get(),
            subtitleRepository = get(),
            desktopSettings = get(),
            subtitleSettings = get(),
        )
    }

    // 浮窗的开关。`createdAtStart`：它要在**还没有任何播放页**的时候就成立——
    // 用户在设置页打开开关、然后退出应用，这时没人会去注入它。Koin 启动时把它
    // 构造出来，它的 `init` 里注册 Activity 生命周期回调并开始观察状态。
    single(createdAtStart = true) {
        DesktopLyricsOverlayHost(
            context = androidContext(),
            controller = get(),
            settings = get(),
            // 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）的唯一读取口：浮窗挂不挂得上、
            // 播放页那个按钮说什么，都由它给出的同一个结论决定。
            permissions = get(),
        )
    }
}
