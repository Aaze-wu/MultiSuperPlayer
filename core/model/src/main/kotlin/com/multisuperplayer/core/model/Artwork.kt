package com.multisuperplayer.core.model

/**
 * 一次封面请求。
 *
 * ## 为什么不直接把 uri 字符串当 Coil 的 model
 *
 * 光有 uri 不够：**取法分两条完全不同的路**——音频是把内嵌封面掏出来
 * （`ContentResolver.loadThumbnail` / `MediaMetadataRetriever.embeddedPicture`），
 * 视频是在时间轴上抽一帧（`getFrameAtTime`）。只传 uri 的话，取图那一层
 * 还得回头去问「这个 uri 是音频还是视频」，那它就得多依赖一次媒体库查询，
 * 而列表滚动时每一行都要问一次。
 *
 * [kind] 带在请求里，取图器只做一个 `when`。
 *
 * ## 为什么放在 core:model
 *
 * `core:ui` 要知道请求长什么样（它的 `ArtworkImage` 收这个类型），
 * `core:data` 要生产它。两边共同依赖的只有 `core:model`。
 * 放在 `core:data` 的话，`core:ui` 就不得不依赖整个数据层
 * （连带 MediaStore、DataStore、播放器），只为拿一个 data class。
 *
 * ## [uri] 的三种形态
 *
 * - 媒体库条目：`content://media/external/...`
 * - 文件浏览器 / SAF 条目：SAF 的 document uri，或文件系统**绝对路径**
 * - 网络来源：不会有 [ArtworkRequest]，见 [ArtworkSourceRules]
 *
 * 取图层对「content uri」和「绝对路径」两种都要能处理，判据见
 * `ArtworkLoader`（`content://` 前缀才走 `ContentResolver`）。
 */
data class ArtworkRequest(
    val uri: String,
    val kind: MediaKind,
)

/**
 * 「这条记录能不能取封面、去哪儿取」。
 *
 * ## 为什么值得单独抽一个纯函数对象
 *
 * 判错的代价不对称：判成「不能取」只是少一张图，判成「能取」则会让
 * **每一行**都挂一次注定失败的取图（而且失败路径还会被反复触发——
 * 列表每次滚回来都重试一遍）。这种事必须在单测里钉死，
 * 靠在实机上翻列表翻不出来。
 *
 * ## 判据只有两条
 *
 * 1. **类型**：只有音、视频有封面这回事。[MediaKind.UNKNOWN] 连自己是
 *    音频还是视频都不知道，给它抽帧是在猜。
 * 2. **来源**：[MediaSource.REMOTE] 是本机没有的东西。既抽不了帧，
 *    也不该为了一个列表缩略图去把整个视频下下来。
 *
 * 其余来源一律放行：媒体库、SAF、文件浏览器拿到的都是**本机可打开的 uri**，
 * 取不到图的时候有兜底图标，白跑一次的成本只有一次失败的解码。
 */
object ArtworkSourceRules {

    /** 只有音、视频有封面这回事。 */
    fun wantsArtwork(kind: MediaKind): Boolean =
        kind == MediaKind.AUDIO || kind == MediaKind.VIDEO

    /**
     * 媒体条目的封面请求；不该取或取不到时返回 `null`。
     *
     * ## 为什么用 `artworkUri ?: uri` 兜底
     *
     * 这条**不是**「猜一个值」，因为媒体库条目的 artworkUri 本来就是这么来的：
     * `MediaStoreScanner` 里写着 `artworkUri = uri.toString()`，两者的值相等。
     * 而播放列表里的条目（`PlaylistItem.toEntry()`）**根本不带 artworkUri 字段**
     * ——它构造 `MediaEntry` 时那个参数走了默认值 `null`，但它的 `uri` 就是
     * 那个 content uri。
     *
     * 少了这条兜底，会出现「播放列表页有封面、媒体库页没有」这种查不出原因的差异。
     */
    fun requestFor(entry: MediaEntry): ArtworkRequest? =
        requestFor(
            uri = entry.artworkUri?.takeIf { it.isNotBlank() } ?: entry.uri,
            kind = entry.kind,
            source = entry.source,
        )

    /** 上面那条的显式版本。 */
    fun requestFor(uri: String?, kind: MediaKind, source: MediaSource): ArtworkRequest? {
        if (!wantsArtwork(kind)) return null
        if (source == MediaSource.REMOTE) return null
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return ArtworkRequest(uri = trimmed, kind = kind)
    }

    /**
     * 文件浏览器条目的封面请求。
     *
     * 浏览页的条目还没有 [MediaSource]（它没进任何库），但它的
     * [BrowserEntry.ref] 反而是最「可打开」的一种：绝对路径或 SAF document uri，
     * 两种都能直接交给系统解码，所以这里不做来源判断。
     *
     * 目录、字幕、明确不是媒体的文件在 [BrowserEntry.playable] 上已经被挡掉了
     * ——那一页的 `kind` 与 `mimeType` 是两件事（`.lrc` 的 kind 是 `null` 而不是
     * `UNKNOWN`），判据必须用 `playable`，不能自己看后缀。
     */
    fun requestFor(entry: BrowserEntry): ArtworkRequest? {
        if (!entry.playable) return null
        val kind = entry.kind ?: return null
        if (!wantsArtwork(kind)) return null
        // 和上面那个重载一样挡一下空 uri：这不是「来源判断」，而是「注定失败的
        // 取图别排队」。空的 ref 在实机上不该出现（ref 由 provider 给出），
        // 但两条重载对「空 uri」给不同的答案，读代码的人迟早会在这里绊一下。
        if (entry.ref.isBlank()) return null
        return ArtworkRequest(uri = entry.ref, kind = kind)
    }
}
