package com.multisuperplayer.feature.player.di

import com.multisuperplayer.feature.player.PlayerViewModel
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
}
