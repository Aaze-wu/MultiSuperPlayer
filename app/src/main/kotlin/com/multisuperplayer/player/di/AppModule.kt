package com.multisuperplayer.player.di

import com.multisuperplayer.player.AppPlaybackViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * app 模块自己的依赖。
 *
 * 只放「属于应用外壳」的东西：导航骨架需要的 ViewModel、以及后面会有的
 * 主题偏好。功能域（媒体库、播放、字幕、翻译、语音识别）各自的依赖
 * 都留在自己的 `di` 包下，这样把某个功能砍掉时，删一个模块 + 一行注册就干净了。
 */
val appModule = module {
    viewModelOf(::AppPlaybackViewModel)
}
