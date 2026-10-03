package com.multisuperplayer.core.player.di

import com.multisuperplayer.core.player.ExoPlayerController
import com.multisuperplayer.core.player.NextlibSoftwareDecoderSupport
import com.multisuperplayer.core.player.PlaybackController
import com.multisuperplayer.core.player.SoftwareDecoderSupport
import com.multisuperplayer.core.player.TrackSelectionController
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
    // 注册成接口而不是直接调 `FfmpegLibrary`：`:feature:settings` 需要读「有没有 FFmpeg」
    // 才能决定那一行显示成什么样，但不应该为此依赖 nextlib。
    //
    // `single` 而不是 `factory`：它内部会 `System.loadLibrary`，重复创建没有意义。
    single<SoftwareDecoderSupport> { NextlibSoftwareDecoderSupport() }

    // 续播位置的存取接口定义在 core:player，实现（DataStore）在 core:data。
    // 这里只声明「内核需要它」，具体是谁由 dataModule 决定——内核因此不必
    // 依赖 DataStore、AndroidKeyStore 这些东西。
    single<PlaybackController> {
        ExoPlayerController(
            context = androidContext(),
            dispatchers = get(),
            softwareDecoders = get(),
            positionStore = get(),
        )
    }

    // 字幕那一层只有「有哪些轨 / 选哪条 / 读到哪些行」这几件事要做，
    // 不该拿到整个 [PlaybackController]（暂停、倍速、队列它一样也不需要）。
    // 绑定到**同一个**单例：轨道清单必须是同一个内核在维护，两个实例会变成
    // 两份真相，而且多出来的那个内核还会白白占着解码器。
    single<TrackSelectionController> { get<PlaybackController>() }
}
