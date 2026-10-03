package com.multisuperplayer.player.di

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.player.AppPlaybackViewModel
import com.multisuperplayer.player.BuildConfig
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 从 `BuildConfig` 读构建信息。
 *
 * 抽成顶层函数而不是直接写在 Koin 定义里：`MspApplication.onCreate` 要在
 * `startKoin` **之前**把它交给日志设施（写会话抬头），两处必须拿到同一份值。
 * 写在 Koin 定义里的话，启动路径就只能自己再拼一遍，迟早会漏掉新加的字段。
 */
fun buildAppInfo(): AppBuildInfo = AppBuildInfo(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    gitCommit = BuildConfig.GIT_COMMIT,
    gitTag = BuildConfig.GIT_TAG,
    gitDirty = BuildConfig.GIT_DIRTY,
    buildTimeText = BuildConfig.BUILD_TIME,
    versionChannel = BuildConfig.VERSION_CHANNEL,
)

/**
 * app 模块自己的依赖。
 *
 * 只放「属于应用外壳」的东西：导航骨架需要的 ViewModel、构建信息。
 * 功能域（媒体库、播放、字幕、翻译、语音识别）各自的依赖都留在自己的 `di` 包下，
 * 这样把某个功能砍掉时，删一个模块 + 一行注册就干净了。
 *
 * [AppBuildInfo] 由外壳提供而不是 `core:common` 自己产生：`BuildConfig` 是**每个模块
 * 各自生成的**，只有 `:app` 的那一份带着 git 信息（见 `app/build.gradle.kts` 的
 * `buildConfigField`）。`feature:settings` 的关于页从这里取。
 */
val appModule = module {
    single { buildAppInfo() }

    viewModelOf(::AppPlaybackViewModel)
}
