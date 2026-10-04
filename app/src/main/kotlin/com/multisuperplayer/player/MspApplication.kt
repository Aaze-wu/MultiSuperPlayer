package com.multisuperplayer.player

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.multisuperplayer.core.common.di.commonModule
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.log.MspLogInitializer
import com.multisuperplayer.core.data.artwork.ArtworkLoader
import com.multisuperplayer.core.data.artwork.installArtworkComponents
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
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

private const val TAG = "MspApplication"

/**
 * Coil 内存缓存占应用可用内存的比例。
 *
 * 0.25 是 Coil 自己的默认值（`ImageLoader.Builder` 里 `maxSizePercent(application)`
 * 不带参数时就是它），这里写出来是为了让它**可被搜到**——列表滚动卡不卡主要
 * 由这个数决定，而默认值藏在库里的时候没人会想起它。
 */
private const val COIL_MEMORY_CACHE_PERCENT = 0.25

/**
 * 应用入口。
 *
 * 这里只做三件轻活：「装配日志」「启动依赖容器」「装配取图」。刻意不放任何业务初始化：
 * [onCreate] 是冷启动关键路径，多放 100ms 的活儿用户就直接看到白屏，
 * 媒体库扫描、字幕索引之类必须挪到后台或首次进入对应页面时再做。
 *
 * 日志装配的代价：唯一碰磁盘的是 `getExternalFilesDir`（顺手 mkdirs 一次），
 * 真正的写盘在它自己的后台线程上，所以放在这里是有意为之，不是疏忽。
 * 取图装配的代价：只构造一个 `ArtworkLoader`（一个 `File` 对象和一个信号量）
 * 和一个惰性工厂，真正的解码和内存缓存都要等第一次用图才发生。
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

        // 必须排在 startKoin 之后：下面要从容器里取 ArtworkLoader。
        installCoil()
    }

    /**
     * 装配 Coil 的单例 `ImageLoader`。
     *
     * ## 为什么写在这里，而不是让 Coil 第一次用图时自己建
     *
     * Coil 默认是「第一次遇到 `AsyncImage` 才创建单例」，那一次发生在
     * **构建界面的线程上**。而这里要往它的组件表里塞 [ArtworkLoader]，
     * 那个实例来自 Koin 容器，容器在 `startKoin` 之后才可用。
     *
     * 放在 `onCreate` 里紧跟 `startKoin`，时机的对错一眼可见；
     * 交给「首次用到时」就成了一条「构建界面时容器一定已经起来」的隐含约定，
     * 而这条约定只在冷启动竞态下才失效——那种 bug 复现不了，只能靠读代码发现。
     *
     * ## 为什么是 `setSafe` 不是 `setUnsafe`
     *
     * `setSafe` 在单例**已经被创建**之后不覆盖（并抛异常提示调用太晚），
     * `setUnsafe` 则会直接替换掉一个可能正在被使用的 `ImageLoader`
     * （替换后它自己的内存缓存就没人管了）。这里是启动路径上的唯一一次装配，
     * 不该存在「悄悄换掉一个已经在跑的 ImageLoader」这种可能。
     *
     * ## 磁盘缓存为什么关掉
     *
     * [ArtworkLoader] 自己已经落盘了一份 512 长边的 JPEG，而且那一份是
     * **缩好尺寸**的。Coil 再缓存一份同样的字节只是让磁盘占用翻倍。
     * 内存缓存留着——列表滚动时不重复解码图靠的就是它。
     *
     * ## 为什么 Fetcher 和 Keyer 在同一个函数里注册
     *
     * 自定义 model 缺了 Keyer，内存缓存会**静默**失效（只打一行警告日志）。
     * 成对注册的理由写在 `installArtworkComponents` 的注释里，出口只留一个。
     */
    private fun installCoil() {
        val artworkLoader = GlobalContext.get().get<ArtworkLoader>()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { installArtworkComponents(artworkLoader) }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, COIL_MEMORY_CACHE_PERCENT)
                        .build()
                }
                .diskCache(null)
                .build()
        }
        MspLog.d(TAG) { "取图已装配（封面走自定义 Fetcher，磁盘缓存交给 ArtworkLoader）" }
    }
}
