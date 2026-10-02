package com.multisuperplayer.core.model

/**
 * 「最近播放」列表里的一行。
 *
 * [entry] 是**当前**媒体库里查到的元数据，不是播放当时的快照；[positionMs] 和
 * [playedAtMs] 来自续播记录。
 *
 * ## 为什么查不到就不显示，而不是灰掉
 *
 * 播放列表是用户**手工攒的**，条目丢了必须让他看见（并告诉他是哪个文件，
 * 好让他自己去找回来）；最近播放是自动攒的，一条查不到的记录对他没有任何
 * 可执行的信息（「播放次数」这种列表灰一行只是噪音）。所以：
 *
 * - 最近播放：连不上就**不显示**，但**记录本身不删**——权限没给的时候连不上，
 *   给了权限它就该自己回来。这比「找不到就删掉」安全得多。
 * - 播放列表：连不上也**显示**（用快照里的标题），标成「文件已不在」。
 */
data class RecentPlay(
    val entry: MediaEntry,
    /** 上次播到的位置。 */
    val positionMs: Long,
    /** 上次播放的时间，用于显示「3 小时前」以及排序。 */
    val playedAtMs: Long,
)

/**
 * 播放列表里的一个条目。
 *
 * ## 为什么 id 之外还要存一份显示快照
 *
 * 只存 [mediaId] 的话，在下面这几种情况下这一行会变成一片空白，而用户什么也做不了：
 * 存储权限还没给（媒体库是空的）、文件被移到别处、SAF 授权被回收、
 * 云盘离线。存一份标题/时长快照，至少能显示「《XXX》——文件已不在」，
 * 用户可以自己决定是删掉还是去找回文件。
 *
 * 反过来，只存快照也是错的：那样文件改名/换码之后播放列表就永远播旧的。
 * 所以**两边都存**，以 id 为准、快照只当兜底——[PlaylistItem.resolvedBy] 就是
 * 这个优先级规则本身。
 */
data class PlaylistItem(
    /** 稳定的媒体 id（`MediaEntry.id`）。 */
    val mediaId: String,
    /** 写入时的可播 uri。媒体库查不到时用它兜底（内容 uri 一般仍然有效）。 */
    val uri: String,
    /** 写入时的标题快照。 */
    val title: String,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val kind: MediaKind = MediaKind.UNKNOWN,
) {
    /**
     * 拿当前媒体库里的同名条目覆盖快照。
     *
     * 查不到时原样返回自己——**降级成快照**而不是返回 null，因为「文件已不在」
     * 也是一条必须显示出来的信息。
     */
    fun resolvedBy(entries: Map<String, MediaEntry>): PlaylistItem {
        val entry = entries[mediaId] ?: return this
        return PlaylistItem(
            mediaId = mediaId,
            uri = entry.uri,
            title = entry.title,
            artist = entry.artist ?: artist,
            durationMs = if (entry.durationMs > 0L) entry.durationMs else durationMs,
            kind = if (entry.kind == MediaKind.UNKNOWN) kind else entry.kind,
        )
    }

    /** 列表里显示的副标题：沿用 [MediaEntry.subtitle] 的取舍（缺项优雅降级）。 */
    val subtitle: String get() = artist?.takeIf { it.isNotBlank() }.orEmpty()

    /** 当这一条可以直接交给播放器时用的 [MediaEntry]。 */
    fun toEntry(source: MediaSource = MediaSource.MEDIA_STORE): MediaEntry = MediaEntry(
        id = mediaId,
        uri = uri,
        title = title,
        kind = kind,
        source = source,
        artist = artist,
        durationMs = durationMs,
    )

    companion object {
        /** 从媒体库条目生成快照。 */
        fun of(entry: MediaEntry): PlaylistItem = PlaylistItem(
            mediaId = entry.id,
            uri = entry.uri,
            title = entry.title,
            artist = entry.artist?.takeIf { it.isNotBlank() },
            durationMs = entry.durationMs,
            kind = entry.kind,
        )
    }
}

/**
 * 用户创建的一个播放列表。
 *
 * 全部条目放在同一个值里（不分表）：一个播放列表最多几百条，整体读写一次
 * 就是一次 DataStore 写入；拆成「列表头 + 条目」两张表反而要处理
 * 「头写成功了、条目没写进去」这种半完成状态，而这里根本没有那个必要。
 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val items: List<PlaylistItem> = emptyList(),
) {
    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()
}
