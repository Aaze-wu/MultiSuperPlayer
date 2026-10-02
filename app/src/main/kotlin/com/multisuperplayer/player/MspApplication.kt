package com.multisuperplayer.player

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.multisuperplayer.core.common.di.commonModule
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.log.MspLogInitializer
import com.multisuperplayer.core.data.di.dataModule
import com.multisuperplayer.core.data.settings.wrapLocale
import com.multisuperplayer.core.player.di.playerModule
import com.multisuperplayer.core.subtitle.di.subtitleModule
import com.multisuperplayer.core.translate.di.translateModule
import com.multisuperplayer.feature.library.di.libraryModule
import com.multisuperplayer.feature.player.di.playerFeatureModule
import com.multisuperplayer.feature.settings.di.settingsFeatureModule
import com.multisuperplayer.player.di.appModule
import com.multisuperplayer.player.di.buildAppInfo
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import org.koin.core.context.startKoin

private const val TAG = "MspApplication"

/**
 * 应用入口。
 *
 * 这里只做两件轻活：「装配日志」和「启动依赖容器」。刻意不放任何业务初始化：
 * [onCreate] 是冷启动关键路径，多放 100ms 的活儿用户就直接看到白屏，
 * 媒体库扫描、字幕索引之类必须挪到后台或首次进入对应页面时再做。
 *
 * 日志装配的代价：唯一碰磁盘的是 `getExternalFilesDir`（顺手 mkdirs 一次），
 * 真正的写盘在它自己的后台线程上，所以放在这里是有意为之，不是疏忽。
 */
class MspApplication : Application() {

    /**
     * 把用户选的语言套到应用 Context 上。
     *
     * 必须在这里而不是 `onCreate`：`onCreate` 跑起来时 `Resources` 已经定型，
     * 再改就是「换了一个没人用的 Context」。33 以上这个方法内部什么都不做，
     * 交给系统的「每应用语言」（见 [wrapLocale] 的说明）。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.wrapLocale())
    }

    override fun onCreate() {
        super.onCreate()

        // 判断是不是 debug 包。用它而不是 BuildConfig.DEBUG，是因为
        // `applicationInfo.flags` 在任何构建类型/变体下都可靠，而 BuildConfig.DEBUG
        // 依赖 buildConfig 生成开关（本项目现在已经打开了，但这个标志位更直接）。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // 先装日志再启容器：容器里的 `LogRepository` 通过 lambda 取目录，谁先谁后都不会错，
        // 但启动过程本身（Koin 解析模块、失败堆栈）也应该落盘。
        MspLogInitializer.install(this, buildAppInfo(), release = !debuggable)
        MspLog.i(TAG) { "应用启动（debuggable=$debuggable）" }

        startKoin {
            androidLogger(if (debuggable) Level.INFO else Level.ERROR)
            androidContext(this@MspApplication)
            modules(
                commonModule,
                dataModule,
                subtitleModule,
                translateModule,
                playerModule,
                libraryModule,
                playerFeatureModule,
                settingsFeatureModule,
                appModule,
            )
        }
    }
}
