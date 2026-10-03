package com.multisuperplayer.core.data.di

import com.multisuperplayer.core.asr.AsrModelDownloader
import com.multisuperplayer.core.asr.AsrModelInstaller
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrTranscriber
import com.multisuperplayer.core.asr.CloudAsrTranscriber
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.data.browser.BrowserRepository
import com.multisuperplayer.core.data.browser.StorageAccess
import com.multisuperplayer.core.data.history.PlaybackPositionRepository
import com.multisuperplayer.core.data.history.RecentPlayRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaStoreScanner
import com.multisuperplayer.core.data.library.SafTreeScanner
import com.multisuperplayer.core.data.library.SafTreeStore
import com.multisuperplayer.core.data.permissions.AppPermissions
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.data.power.KeepAliveAccess
import com.multisuperplayer.core.data.settings.ApiKeyStore
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import com.multisuperplayer.core.data.settings.LocaleSettingsRepository
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.data.subtitle.AsrSubtitleGenerator
import com.multisuperplayer.core.data.subtitle.FileSystemSubtitleLocator
import com.multisuperplayer.core.data.subtitle.GeneratedSubtitleStore
import com.multisuperplayer.core.data.subtitle.SafSubtitleLocator
import com.multisuperplayer.core.data.subtitle.SubtitleExportWriter
import com.multisuperplayer.core.data.subtitle.SubtitleFileLocator
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.data.update.GitHubReleasesSource
import com.multisuperplayer.core.data.update.UpdateApkVerifier
import com.multisuperplayer.core.data.update.UpdateChannel
import com.multisuperplayer.core.data.update.UpdateDownloader
import com.multisuperplayer.core.data.update.UpdateInstaller
import com.multisuperplayer.core.data.update.UpdateManager
import com.multisuperplayer.core.data.update.UpdateSettingsRepository
import com.multisuperplayer.core.data.update.UpdateSource
import com.multisuperplayer.core.data.update.UpdateSourceConfig
import com.multisuperplayer.core.data.update.UpdateVersion
import com.multisuperplayer.core.llm.LiteRtLmTextGenerator
import com.multisuperplayer.core.llm.LlmModelDownloader
import com.multisuperplayer.core.llm.LlmModelInstaller
import com.multisuperplayer.core.llm.LlmModelLocator
import com.multisuperplayer.core.llm.LlmTextGenerator
import com.multisuperplayer.core.player.PlaybackPositionStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val dataModule = module {
    single { MediaStoreScanner(context = androidContext()) }

    // SAF 目录扫描。与 MediaStore 两条来源在仓库里合并、去重，
    // 详见 MediaLibraryRepository 与 LibraryMergeRules 的注释。
    single { SafTreeScanner(context = androidContext()) }

    single { SafTreeStore(context = androidContext(), dispatchers = get()) }

    // 「所有文件访问」的状态与授权入口。默认关闭、永不主动申请，
    // 详情与理由见 StorageAccess 的类注释。
    single { StorageAccess(context = androidContext()) }

    // 权限页与首次启动申请要问的那几件事（状态 / 该申请什么 / 该去哪儿）。
    // 它把上面两个单例和「问过没有」的记忆拼在一起，所以排在这两个之后。
    single { AppPermissions(context = androidContext(), scanner = get(), storage = get()) }

    // 「后台保活」页要问的事：在不在电池优化白名单里、这台是哪家厂商、
    // 以及该跳到哪个系统页面。做成单例而不是每次 `KeepAliveAccess(context)`：
    // 它的方法本来就无状态，单例只是为了「谁拿到的都是同一份规则」，
    // 而不是为了共享缓存。
    single { KeepAliveAccess(context = androidContext()) }

    // 内置文件浏览器（只读）。依赖上面三个，所以排在这里。
    single {
        BrowserRepository(
            context = androidContext(),
            safTreeStore = get(),
            safScanner = get(),
            storage = get(),
            dispatchers = get(),
        )
    }

    single {
        MediaLibraryRepository(
            context = androidContext(),
            scanner = get(),
            safScanner = get(),
            safTreeStore = get(),
            dispatchers = get(),
        )
    }

    single { ThemeSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { SubtitleSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { PlaybackSettingsRepository(context = androidContext(), dispatchers = get()) }

    // 不加 dispatchers：这一个设置不用 DataStore、也不做异步读取，理由见 AppLocaleStore 的注释。
    single { LocaleSettingsRepository(context = androidContext()) }

    // 续播位置存在单独的 DataStore 文件里（不是 msp_settings）——
    // 它无界增长、写入频繁，混进设置文件会拖累用户设置。见 mspPositionsStore。
    single<PlaybackPositionStore> {
        PlaybackPositionRepository(context = androidContext(), dispatchers = get())
    }

    // 「最近播放」= 续播记录 × 当前媒体库的投影。需要读媒体库当前状态，
    // 所以只能排在上面两个注册之后。
    single { RecentPlayRepository(positionStore = get(), library = get()) }

    // 播放列表存在单独的 DataStore 文件（msp_playlists）里：写一次就是几 KB 自由文本，
    // 混进 msp_settings 会拖慢用户设置的写入。
    single { PlaylistStore(context = androidContext(), dispatchers = get()) }

    // API Key 加密存起来（AndroidKeyStore + AES/GCM），密文进 msp_settings 这个 DataStore。
    single { ApiKeyStore(context = androidContext(), dispatchers = get()) }

    single {
        TranslationSettingsRepository(
            context = androidContext(),
            dispatchers = get(),
            apiKeys = get(),
        )
    }

    single { ArtworkPaletteRepository(context = androidContext(), dispatchers = get()) }

    single { SubtitleFileLocator(context = androidContext()) }

    // SAF 目录里的字幕走另一条路（列兄弟目录而不是查 MediaStore），见类注释。
    single { SafSubtitleLocator(context = androidContext()) }

    // 文件浏览器打开的条目走第三条路（直接列目录）。零依赖：`java.io.File`
    // 连 `Context` 都不需要，所以它也是这一层里唯一能真单测的。
    single { FileSystemSubtitleLocator() }

    // 导出译文用。走 SAF，不申请存储权限。
    single { SubtitleExportWriter(context = androidContext(), dispatchers = get()) }

    // 应用自己生成的字幕（语音识别）存在这里，理由见类注释：
    // 放在私有目录而不是片子旁边，因为对那个目录没有写权限。
    // 它收 filesDir 而不是 Context，和 AsrModelLocator 一样——好在这层能单测。
    single { GeneratedSubtitleStore(filesDir = androidContext().filesDir, dispatchers = get()) }

    // parserRegistry 来自 core:subtitle 的 subtitleModule，由 MspApplication 一并加载。
    single {
        SubtitleRepository(
            context = androidContext(),
            locator = get(),
            safLocator = get(),
            fileSystemLocator = get(),
            parserRegistry = get(),
            generatedStore = get(),
            dispatchers = get(),
        )
    }

    // ----------------------------------------------------------- 语音识别（生成字幕）

    single { AsrSettingsRepository(context = androidContext(), dispatchers = get()) }

    // 模型文件的位置（filesDir/asr/<模型 id>/）。设置页要查「下了没有」，
    // 所以 locator 单独注册而不藏在识别器里。
    single { AsrModelLocator(AsrModelLocator.rootOf(androidContext().filesDir)) }

    single { AsrModelDownloader(dispatchers = get()) }

    single { AsrModelInstaller(locator = get(), downloader = get()) }

    // 组装 PcmExtractor / 引擎工厂 / locator 的活留在 core:asr（构造函数是 internal 的），
    // 这里只认生产入口。
    single { AsrTranscriber.create(androidContext(), get()) }

    // 云端识别：切片 + 上传 + 解析。与 AsrTranscriber.create 同一个理由——
    // 内部的 PcmExtractor / AudioSliceWriter / CloudAsrClient 都不该被界面层直接拿。
    // cacheDir 而不是 filesDir：切出来的 wav 是同一次识别用完就删的中间产物，
    // 放 cache 下才能被系统在空间紧张时回收（中途被杀留下的残片也由它自己清）。
    single { CloudAsrTranscriber.create(androidContext(), get()) }

    // 识别 → 序列化 → 落盘，界面层只能经它生成字幕。两条路（本机 / 云端）
    // 都从这一个入口出去，所以「存哪儿、怎么序列化、失败怎么报」只有一份实现。
    single { AsrSubtitleGenerator(transcriber = get(), cloud = get(), store = get()) }

    // ----------------------------------------------------------- 本地翻译（设备上跑的大模型）

    // 模型文件的位置（filesDir/llm/）。设置页要查「下了没有」，推理层拿它当模型路径，
    // 所以 locator 单独注册。与 AsrModelLocator 一样只收 filesDir——好在这层能单测。
    single { LlmModelLocator(LlmModelLocator.rootOf(androidContext().filesDir)) }

    single { LlmModelDownloader(dispatchers = get()) }

    single { LlmModelInstaller(locator = get(), downloader = get()) }

    // 推理引擎的宿主。**必须是单例**：引擎 `initialize()` 最长约 10 s，
    // 而且同一个进程里并发跑两个原生生成会崩在 native 侧（串行化在它内部）。
    // cacheDir 只是引擎的工作目录，可再生，所以放 cache 下。
    //
    // ⚠️ 删/换模型文件之前要先调 `release()`：模型是 mmap 进去的，
    // 没释放就删会出现「文件没了但空间没回来」。
    single<LlmTextGenerator> {
        LiteRtLmTextGenerator(
            locator = get(),
            cacheDir = File(androidContext().cacheDir, "llm"),
            dispatchers = get(),
        )
    }

    // ------------------------------------------------------------------ 应用更新

    // 通道的默认值跟着「当前装的是不是预发行版」走。反过来的默认值（一律正式版）
    // 看起来更稳，但它会让装预发行版的人**永远收不到后续的预发行版**，而且
    // 界面上显示的还是「已是最新版本」——一个静默失效的开关。
    single {
        UpdateSettingsRepository(
            context = androidContext(),
            dispatchers = get(),
            defaultChannel = if (get<AppBuildInfo>().isPreview) {
                UpdateChannel.PRERELEASE
            } else {
                UpdateChannel.STABLE
            },
        )
    }

    // 配置在**每次请求前**重新取，而不是构造时取一次：用户刚把令牌填进去，
    // 下一次检查就得用它。否则他会看到「填了令牌还是被限流」，然后认定令牌无效。
    //
    // 注册成 `UpdateSource` 接口而不是具体类：`UpdateManager` 要的是接口，
    // 而 Koin 按**类型**解析——只登记 `GitHubReleasesSource` 时接口没人认领，
    // 进更新页就是一句 `NoDefinitionFoundException`（编译期一点提示都没有）。
    // 这也正是「后续会加其他渠道」的接缝：换渠道只改这一行。
    single<UpdateSource> {
        GitHubReleasesSource(
            dispatchers = get(),
            configProvider = {
                UpdateSourceConfig(
                    repository = UpdateSourceConfig.DEFAULT_REPOSITORY,
                    token = get<UpdateSettingsRepository>().currentToken(),
                )
            },
        )
    }

    single { UpdateDownloader(context = androidContext(), dispatchers = get()) }
    single { UpdateApkVerifier(context = androidContext()) }
    single { UpdateInstaller(context = androidContext()) }

    single {
        UpdateManager(
            source = get(),
            settingsRepository = get(),
            downloader = get(),
            verifier = get(),
            // 解析不出来就传 `null`，而 `null` 在 `UpdateRules.decide` 里是
            // 「不知道当前版本」⇒ 什么都不报，而不是「所有发布都比当前新」⇒
            // 每次都提示有更新。后者的症状（天天提示更新）比前者（不提示）
            // 更难查，因为用户会当成真的。
            currentVersion = UpdateVersion.parse(get<AppBuildInfo>().versionName),
        )
    }
}
