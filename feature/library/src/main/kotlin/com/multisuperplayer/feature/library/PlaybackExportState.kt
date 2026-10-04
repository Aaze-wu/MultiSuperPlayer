package com.multisuperplayer.feature.library

import android.net.Uri
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.export.PlaybackExport
import com.multisuperplayer.core.data.export.PlaybackExportCollection
import com.multisuperplayer.core.data.export.PlaybackExportFormat
import com.multisuperplayer.core.data.export.PlaybackExportTrack
import com.multisuperplayer.core.data.export.TextExportWriter
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.CancellationException

/**
 * 「导出播放记录 / 导出播放列表」这件事的进度。
 *
 * ## 为什么不是一个 `MspText?`
 *
 * 导出有五种结局，其中三种都要说一句话，但它们**不是同一句话**：
 *
 * - [Saved]：成功，而且要说清「几条」——不然用户拿着一个空文件也不知道是自己选错了还是真没数据。
 *   文件写的那个名字也必须回显：他刚才在系统选择器里翻了好几个目录，你说「已保存」他不一定知道存哪了。
 * - [Failed]（写失败）：这句和「编码出错」完全不是一回事。写失败是可执行的（换目录、
 *   清理空间），编码出错不是（那是个 bug）。合并成一句的话，用户只会反复重试同一个目录。
 * - [Empty]：没有内容可导。它比 [Failed] 更早发生，而且**不该**被当成失败——
 *   一个「一个播放列表都没建」的用户点导出，报红字是冤枉他。
 *
 * ## 为什么 [Running] 要单独一个状态而不是一个布尔
 *
 * 它是**重入闸门**：SAF 回调可能在极短时间里回来两次（用户连点两下 icon、
 * 或选择器返回后又被系统重投一次），而两次写都会以 `"wt"` 清空同一个目标文件。
 * 第二次写发生在第一次还没写完时，文件就会**少一半**——且两边的返回值都是 `true`。
 * 所以导出前先看闸门，见 [exportPlaybackTo] 的两个调用方。
 */
sealed interface PlaybackExportState {

    /** 界面要说的那一句话；null = 什么都不用说（[Idle] 与 [Running]）。 */
    fun message(): MspText? = when (this) {
        PlaybackExportState.Idle, PlaybackExportState.Running -> null
        is PlaybackExportState.Saved -> if (missing > 0) {
            MspText.Res(R.string.msp_export_result_saved_missing, fileName, tracks, missing)
        } else {
            MspText.Res(R.string.msp_export_result_saved, fileName, tracks)
        }
        is PlaybackExportState.Failed -> message
        PlaybackExportState.Empty -> MspText.Res(R.string.msp_export_result_empty)
    }

    /** 什么都没发生。 */
    data object Idle : PlaybackExportState

    /** 正在写。它的唯一用途是挡住第二次导出（见类型注释）。 */
    data object Running : PlaybackExportState

    /** 写完了。[tracks] 是条数，[missing] 是其中「文件已不在」的条数。 */
    data class Saved(
        val fileName: String,
        val tracks: Int,
        val missing: Int,
    ) : PlaybackExportState

    /** 没写成。[message] 里是原因。 */
    data class Failed(val message: MspText) : PlaybackExportState

    /** 没有可导出的内容（空列表 / 详情页已经被关掉）。 */
    data object Empty : PlaybackExportState
}

/**
 * 把 [collections] 编码成一个文件写到 [uri]。
 *
 * ## 为什么这段逻辑在 feature 层而不是 `core:data`
 *
 * 它要的是三个「界面才知道」的东西：**当前屏幕上的那一份数据**（不是重新去读一遍
 * 仓库——用户看到 12 条、导出来 15 条，那种差异没人能解释）、View 层的 strings 资源、
 * 以及写失败时要说的那句话。`core:data` 里的 [PlaybackExport] 只负责纯编码
 * （列表 → 文本），这一层负责「把结果告诉用户」。
 *
 * ## 为什么读的是「屏幕上的那一份」
 *
 * 用户点了导出，他期望拿到的就是屏幕上那几条。重新去读仓库（或者重新过一遍媒体库）
 * 会引入一个时间差：这段时间里删除的记录会重新出现、文件的标题会变。
 *
 * [writer] 从不抛异常——写失败返回 `false`，这里把它翻译成「文件没有写进去」。
 * 只有当**编码**本身出错（比如资源 id 对不上）才会走到 [Failed] 的兜底那一支。
 */
internal suspend fun exportPlaybackTo(
    writer: TextExportWriter,
    uri: Uri,
    format: PlaybackExportFormat,
    collections: List<PlaybackExportCollection>,
    fileName: String,
    resolve: (MspText) -> String,
): PlaybackExportState {
    // 先判空再编码：空导出会写一个只有表头的文件，而那个文件**看起来是成功的**。
    // 「一个只有表头的 CSV」和「一个有 20 行的 CSV」在文件管理器里长得一模一样。
    if (!collections.hasExportableTracks) return PlaybackExportState.Empty
    return runCatching {
        val text = when (format) {
            PlaybackExportFormat.CSV -> PlaybackExport.csv(collections, resolve)
            PlaybackExportFormat.JSON ->
                PlaybackExport.json(collections, System.currentTimeMillis(), resolve)
        }
        // BOM 只给 CSV：Excel / WPS 中文版会把没有 BOM 的 UTF-8 猜成 GBK，
        // 结果是用户打开文件看见一屏乱码——而 JSON 有 BOM 就不再是合法 JSON。
        // 这条规矩住在格式自己的 `needsBom` 上，不在这里再判一次：
        // 每多一个导出入口就多一处可能写反的地方，而写反两边都不报错。
        val ok = writer.write(uri, text, withBom = format.needsBom)
        if (!ok) return@runCatching PlaybackExportState.Failed(
            MspText.Res(R.string.msp_export_result_write_failed),
        )
        val tracks = collections.sumOf { it.tracks.size }
        val missing = collections.sumOf { it.missingCount }
        MspLog.i(TAG) { "已导出 $fileName：$tracks 条，其中文件已不在 $missing 条" }
        PlaybackExportState.Saved(fileName = fileName, tracks = tracks, missing = missing)
    }.getOrElse { error ->
        // `runCatching` 把取消也当异常收下了。这里再抛回去：否则一个已经被销毁的
        // ViewModel 还会往状态里写一句「导出失败」，用户看到的是「我什么都没按，
        // 它说我失败了」。
        if (error is CancellationException) throw error
        MspLog.w(TAG, error) { "导出 $fileName 失败" }
        // 原因拼进那句话里（`msp_export_result_failed` 的 `%1$s`），而不是丢掉它：
        // 编码出错基本只有一种来源（资源 id 对不上），一句话说不清就只能让用户复现。
        // `isNotBlank` 那道过滤是必要的：某些系统异常 `message` 是空串，
        // 那会渲染成「导出失败：」——一个看起来像界面坏了而不是失败的句子。
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        PlaybackExportState.Failed(MspText.Res(R.string.msp_export_result_failed, reason))
    }
}

/**
 * 这批集合里到底有没有东西可导。
 *
 * 抽出来单独一个属性有两个原因：一是它决定了「返回 [PlaybackExportState.Empty]
 * 而不是写一个空文件」这条规矩，值得单独钉住；二是「全都空」和「有一个空」
 * 必须区别对待——用户勾了两个列表，其中一个恰好是空的，导出必须**照常进行**
 * （只丢那个空集合的曲目），而不是整体判空、什么都不给。
 * 写成 `collections.all { it.tracks.isEmpty() }` 时这两个判断读起来一模一样，
 * 加一个名字才能让「是 any 不是 all」这件事在代码里看得见。
 */
internal val List<PlaybackExportCollection>.hasExportableTracks: Boolean
    get() = any { it.tracks.isNotEmpty() }

/**
 * 播放列表页的一行 → 导出用的中间形态。
 *
 * 用 [PlaylistRow.display] 而不是 [PlaylistRow.entry]：后者在「媒体库里查不到」
 * 时是 null，而那一行**仍然要出现在导出文件里**（用快照的标题），理由见
 * [PlaylistRows.queue]——导出必须和「这个列表能播出什么」一致，否则用户拿着
 * 导出文件去核对，会发现少的那几条恰好在 app 里赫然在列。
 */
internal fun exportTrackOf(row: PlaylistRow): PlaybackExportTrack {
    val entry = row.display
    return PlaybackExportTrack(
        mediaId = entry.id,
        uri = entry.uri,
        title = entry.title,
        artist = entry.artist,
        kind = entry.kind,
        durationMs = entry.durationMs,
        missing = row.missing,
    )
}

/**
 * 「导出全部播放列表」要看的那份数据。
 *
 * [fileExists] 会真的去 `stat`（浏览页条目的路径），所以调用方必须把它放到
 * IO 线程上——和 [buildPlaylistsUiState] 走 `flowOn(dispatchers.io)` 是同一个理由。
 */
internal fun playlistExportCollections(
    playlists: List<Playlist>,
    library: MediaLibraryState,
    fileExists: (String) -> Boolean = { false },
): List<PlaybackExportCollection> {
    val byId = library.entriesById()
    return playlists.map { playlist ->
        PlaybackExportCollection(
            name = playlist.name,
            tracks = PlaylistRows.build(playlist.items, byId, fileExists).map(::exportTrackOf),
        )
    }
}

/** 打开中的那个列表 → 一份集合。 */
internal fun PlaylistDetail.toExportCollection(): PlaybackExportCollection =
    PlaybackExportCollection(name = name, tracks = rows.map(::exportTrackOf))

private const val TAG = "LibraryExport"
