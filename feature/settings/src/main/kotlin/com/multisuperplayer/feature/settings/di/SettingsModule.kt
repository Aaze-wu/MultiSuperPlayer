package com.multisuperplayer.feature.settings.di

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
    viewModelOf(::SettingsViewModel)
}
