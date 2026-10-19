package com.multisuperplayer.core.data.favorites

import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText

/**
 * 收藏的纯规则：身份键、去重、上限、顺序。
 *
 * 全部是顶层/对象内函数、不碰 Android、不碰 DataStore，所以可以直接单测。
 * 存储层（`FavoriteStore`）只负责「读出来、交给这些函数、写回去」。
 */
object FavoriteRules {

    /**
     * 最多收藏多少条。
     *
     * 200 的依据不是内存（再多也放得下），而是**这一页还有没有用**：
     * 收藏的本意是「比媒体库更快地找到那几个东西」，几百条之后它就是一个
     * 不能搜索的媒体库。上限存在的作用只是防止有人把整个音乐库一次性收藏进来，
     * 那一次写入会变成几十 KB 的 DataStore 记录。
     */
    const val MAX_FAVORITES = 200

    private const val FILE_PREFIX = "file:"
    private const val FOLDER_PREFIX = "folder:"
    private const val KEY_SEPARATOR = '\u001F'

    /** 文件的键：`file:` + 媒体 id（媒体 id 本身已经带得出「来自哪个来源」）。 */
    fun fileKey(mediaId: String): String = FILE_PREFIX + mediaId

    /**
     * 文件夹的键：`folder:` + 来源 + 最里层的 ref。
     *
     * 用最常见的那种分隔符 `|` 是不行的：`ref` 是路径或 document uri，
     * 里面完全可能有 `|`（Unix 文件名只禁 NUL 和 `/`）。这里用 ASCII US，
     * 它在路径/uri 里出现的概率可以忽略，而且不会和 [FavoriteCodec] 的字段分隔符
     * 混在一起——键**不进**存储格式，只活在内存里，所以不需要转义。
     */
    fun folderKey(kind: BrowserSourceKind, ref: String): String =
        FOLDER_PREFIX + kind.name + KEY_SEPARATOR + ref

    /** 从媒体条目造一条文件收藏。 */
    fun fileOf(entry: MediaEntry, addedAtMs: Long): Favorite.File =
        Favorite.File(item = PlaylistItem.of(entry), addedAtMs = addedAtMs)

    /**
     * 从浏览位置造一条文件夹收藏。
     *
     * [resolve] 由存储层提供（它有 `Resources`），把面包屑里的 [MspText] 解析成文本。
     * 做成参数而不是在这里直接用 `Resources`：这一层要保持可 JVM 单测，
     * 而「哪一段用哪个语言解析」本身就是唯一需要外部输入的东西。
     */
    fun folderOf(
        trail: BrowserTrail,
        addedAtMs: Long,
        resolve: (MspText) -> String,
    ): Favorite.Folder = Favorite.Folder(
        kind = trail.kind,
        crumbs = trail.crumbs.map { FavoriteCrumb(label = resolve(it.label), ref = it.ref) },
        addedAtMs = addedAtMs,
    )

    /**
     * 加入一条收藏。**新收藏排在最前面**。
     *
     * 顺序即时间倒序，没有单独的 `order` 键：收藏列表没有拖动排序的交互
     * （不像播放列表），一个字段能表达的事不值得多一个写入点。
     *
     * 已存在时返回**同一个实例**（调用方可以靠 `!==` 判断「什么都没变」，
     * 不必白写一次 DataStore）；已满时也返回同一个实例——静默丢弃，
     * 由界面根据 [MAX_FAVORITES] 说一句「已达上限」，而不是在这里造一个异常。
     */
    fun withAdded(current: List<Favorite>, favorite: Favorite): List<Favorite> {
        if (current.any { it.key == favorite.key }) return current
        if (current.size >= MAX_FAVORITES) return current
        return listOf(favorite) + current
    }

    /** 批量加入，保持传入顺序（后传入的排在更前面，与逐条加入一致）。 */
    fun withAddedAll(current: List<Favorite>, incoming: List<Favorite>): List<Favorite> =
        incoming.fold(current) { acc, favorite -> withAdded(acc, favorite) }

    /**
     * 按 key 移除。
     *
     * 空集合直接返回同一实例；没有实际删掉时也返回同一实例，理由同 [withAdded]。
     */
    fun withRemoved(current: List<Favorite>, keys: Set<String>): List<Favorite> {
        if (keys.isEmpty()) return current
        val remaining = current.filterNot { it.key in keys }
        return if (remaining.size == current.size) current else remaining
    }

    fun contains(current: List<Favorite>, key: String): Boolean = current.any { it.key == key }

    /** 这个浏览位置是不是已经收藏过。 */
    fun containsFolder(current: List<Favorite>, kind: BrowserSourceKind, ref: String): Boolean =
        contains(current, folderKey(kind, ref))

    /** 这些条目里还有多少条**没被**收藏（用于界面上那个「收藏」按钮的取舍）。 */
    fun unfavorited(current: List<Favorite>, entries: List<MediaEntry>): List<MediaEntry> {
        if (current.isEmpty()) return entries
        val keys = current.mapTo(HashSet(), Favorite::key)
        return entries.filterNot { fileKey(it.id) in keys }
    }

    /** 这些条目是不是**全部**已经收藏（决定按钮是「收藏」还是「取消收藏」）。 */
    fun allFavorited(current: List<Favorite>, entries: List<MediaEntry>): Boolean {
        if (entries.isEmpty()) return false
        val keys = current.mapTo(HashSet(), Favorite::key)
        return entries.all { fileKey(it.id) in keys }
    }

    fun fileKeysOf(entries: List<MediaEntry>): Set<String> = entries.mapTo(HashSet()) { fileKey(it.id) }
}
