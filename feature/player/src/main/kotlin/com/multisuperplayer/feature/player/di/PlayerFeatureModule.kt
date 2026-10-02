package com.multisuperplayer.feature.player.di

import com.multisuperplayer.feature.player.PlayerViewModel
import com.multisuperplayer.feature.player.SubtitleViewModel
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
}
