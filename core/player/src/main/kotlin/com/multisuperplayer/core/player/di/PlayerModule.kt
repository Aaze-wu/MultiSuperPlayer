package com.multisuperplayer.core.player.di

import com.multisuperplayer.core.player.ExoPlayerController
import com.multisuperplayer.core.player.PlaybackController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * core:player 对外提供的绑定。
 *
 * 只注册**接口 → 实现**，不把 `ExoPlayer` 本身注册进容器：内核的线程约束
 * （只能在创建它的线程访问）如果泄漏给注入点，调用方迟早会从后台线程碰它。
 * 需要画面时用 [PlaybackController.player]。
 *
 * 用 `single` 而不是 `factory`：整个应用同时只应存在一个音频焦点持有者，
 * 多个实例会互相抢焦点、同时出声。
 */
val playerModule = module {
    single<PlaybackController> {
        ExoPlayerController(
            context = androidContext(),
            dispatchers = get(),
        )
    }
}
