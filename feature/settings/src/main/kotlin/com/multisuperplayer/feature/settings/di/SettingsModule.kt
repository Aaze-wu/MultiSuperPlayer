package com.multisuperplayer.feature.settings.di

import com.multisuperplayer.feature.settings.AboutViewModel
import com.multisuperplayer.feature.settings.AsrSettingsViewModel
import com.multisuperplayer.feature.settings.LocalModelSettingsViewModel
import com.multisuperplayer.feature.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 设置功能的依赖注册。
 *
 * 命名带 `Feature` 是因为内核里已经有一个 `playerModule`（`:core:player`），
 * 两个都叫 `module` 级别的同名变量在 `MspApplication` 里会撞车。
 *
 * ⚠️ **每个 ViewModel 都得在这里出现一次**。`viewModelOf` 不是自动发现：
 * 漏一行编译能过、单测能过，只在真机上点进去那一刻
 * `NoDefinitionFoundException` 闪退（Koin 4 没有 androidContext 就构造不出来，
 * 所以这一层没法用 JVM 单测盖住）。新增界面时先回来补这一行。
 */
val settingsFeatureModule = module {
    // Activity 作用域（在 `MspApp` 与设置页里取到的是同一个实例），主题即时生效靠这一点。
    viewModelOf(::SettingsViewModel)
    // 「关于」页单独一个：它读设备信息和日志文件，不该出现在冷启动路径上。
    viewModelOf(::AboutViewModel)
    // 语音识别子页单独一个：它要 DataStore、下载器和引擎，只在进那一页时构造。
    viewModelOf(::AsrSettingsViewModel)
    // 本地翻译模型（下载/删除）自己一页、自己一个 ViewModel：
    // 它的依赖里有会常驻引擎的 LlmTextGenerator，挂到 Activity 作用域的
    // SettingsViewModel 上等于每次冷启动都为「用不上本地翻译」的用户构造一份。
    viewModelOf(::LocalModelSettingsViewModel)
}
