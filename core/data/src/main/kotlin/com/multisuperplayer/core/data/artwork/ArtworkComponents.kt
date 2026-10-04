package com.multisuperplayer.core.data.artwork

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.multisuperplayer.core.model.ArtworkRequest
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 把封面取图接到 Coil 上。
 *
 * ## 为什么 Fetcher 和 Keyer 必须**成对**注册
 *
 * Coil 官方文档里写得很直白：*Fetchers that use a custom data type need to
 * register a `Keyer` for the same data type to have its results cached by the
 * memory cache.*（`Fetcher.kt` 的注释与 `docs/image_pipeline.md` 都写了这一句。）
 *
 * 少了 Keyer，`ComponentRegistry.key()` 返回 `null`，Coil 会打一行
 * `No keyer is registered for data with type 'ArtworkRequest'...` 的警告，
 * 然后**跳过内存缓存**。表现不是报错，而是「列表滚回去的时候封面会重新加载
 * 一下」——一个几乎看不见、但会让每个列表行反复走一遍磁盘的退化。
 * 所以这两个注册放在同一个函数里，只留一个出口。
 *
 * ## 为什么 Fetcher 返回 `null` 而不抛异常
 *
 * `EngineInterceptor.fetch()` 是拿 `ComponentRegistry.newFetcher(...)` 返回的
 * 下标 + 1 继续往后找下一个 Factory 的。返回 `null` 等于说「我处理不了这个，
 * 交给别人」，而抛异常是「出错了」。取不到封面（没有内嵌封面的音频）
 * 属于前者，抛异常只会让 Coil 走错误分支、留下一条误导性的堆栈。
 */
fun ComponentRegistry.Builder.installArtworkComponents(loader: ArtworkLoader) {
    add(ArtworkFetcher.Factory(loader), ArtworkRequest::class)
    add(ArtworkKeyer(), ArtworkRequest::class)
}

/**
 * 把 [ArtworkLoader] 抽出来的那张 JPEG 交给 Coil。
 *
 * 这里 `dataSource = DataSource.DISK` 是实话：文件是本地读的。
 * 它会影响 Coil 的内存缓存策略（本地来源不会因为网络缓存策略被跳过）。
 */
internal class ArtworkFetcher(
    private val request: ArtworkRequest,
    private val loader: ArtworkLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = loader.load(request) ?: return null
        return SourceFetchResult(
            source = ImageSource(
                file = file.absolutePath.toPath(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = MIME_TYPE,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * `ArtworkRequest` 是纯数据类（uri + kind），没有上下文依赖，
     * 所以 `create` 永远能给出一张 Fetcher；「能不能取到图」留到
     * [fetch] 里回答——那才是唯一需要真的去解码的地方。
     */
    class Factory(private val loader: ArtworkLoader) : Fetcher.Factory<ArtworkRequest> {
        override fun create(
            data: ArtworkRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ArtworkFetcher(request = data, loader = loader)
    }

    private companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}

/**
 * 封面在内存缓存里的键。
 *
 * 请求尺寸**不进键**是有意的：磁盘上只有一份 512 长边的原图，
 * 列表行（40dp）和播放页（220dp）读的是同一个文件，解码时各自采样。
 * 尺寸进键的话，同一张封面会按尺寸缓存成好几份内存位图，
 * 而磁盘上依然只有一份——多出来的那点命中率换不来什么。
 *
 * Coil 自己会在内存缓存键上叠加请求尺寸，所以「列表的大图命中播放页的小图」
 * 这种事已经由它挡住了。
 */
internal class ArtworkKeyer : Keyer<ArtworkRequest> {
    override fun key(data: ArtworkRequest, options: Options): String =
        ArtworkRules.cacheKeyOf(data.uri, data.kind)
}
