package com.multisuperplayer.feature.settings.di

import com.multisuperplayer.feature.settings.AboutViewModel
import com.multisuperplayer.feature.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 设置功能的依赖注册。
 *
 * 命名带 `Feature` 是因为内核里已经有一个 `playerModule`（`:core:player`），
 * 两个都叫 `module` 级别的同名变量在 `MspApplication` 里会撞车。
 */
val settingsFeatureModule = module {
    // Activity 作用域（在 `MspApp` 与设置页里取到的是同一个实例），主题即时生效靠这一点。
    viewModelOf(::SettingsViewModel)
    // 「关于」页单独一个：它读设备信息和日志文件，不该出现在冷启动路径上。
    viewModelOf(::AboutViewModel)
}
