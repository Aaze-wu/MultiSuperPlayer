package com.multisuperplayer.player

import android.app.Application
import android.content.pm.ApplicationInfo
import com.multisuperplayer.core.common.di.commonModule
import com.multisuperplayer.core.data.di.dataModule
import com.multisuperplayer.core.player.di.playerModule
import com.multisuperplayer.core.subtitle.di.subtitleModule
import com.multisuperplayer.feature.library.di.libraryModule
import com.multisuperplayer.feature.player.di.playerFeatureModule
import com.multisuperplayer.feature.settings.di.settingsFeatureModule
import com.multisuperplayer.player.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import org.koin.core.context.startKoin

/**
 * 应用入口。
 *
 * 这里只做「启动依赖容器」这一件事。刻意不放任何业务初始化：
 * [onCreate] 是冷启动关键路径，多放 100ms 的活儿用户就直接看到白屏，
 * 媒体库扫描、字幕索引之类必须挪到后台或首次进入对应页面时再做。
 */
class MspApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // BuildConfig.DEBUG 需要 buildConfig=true；默认关闭，所以这里用系统标志位判断，
        // 避免为了一个日志级别去打开整个模块的 BuildConfig 生成。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        startKoin {
            androidLogger(if (debuggable) Level.INFO else Level.ERROR)
            androidContext(this@MspApplication)
            modules(
                commonModule,
                dataModule,
                subtitleModule,
                playerModule,
                libraryModule,
                playerFeatureModule,
                settingsFeatureModule,
                appModule,
            )
        }
    }
}
