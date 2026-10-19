package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.DesktopLyricsFontSize
import com.multisuperplayer.core.data.settings.DesktopLyricsOpacity
import com.multisuperplayer.core.data.settings.DesktopLyricsPosition
import com.multisuperplayer.core.data.settings.DesktopLyricsSettingsRepository
import com.multisuperplayer.core.data.settings.DesktopLyricsStyle
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.data.subtitle.SubtitleScan
import com.multisuperplayer.core.data.subtitle.SubtitleLoadResult
import com.multisuperplayer.core.data.subtitle.bestAutoMatch
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 悬浮窗这一刻要画的东西。 */
internal data class DesktopLyricsUiState(
    val rows: List<DesktopLyricsRow> = emptyList(),
    val style: DesktopLyricsStyle = DesktopLyricsStyle.DEFAULT,
    val locked: Boolean = false,
    val isPlaying: Boolean = false,
    /**
     * 字幕显示模式。
     *
     * 浮窗本身不看它（模式已经在 [DesktopLyricsRow] 里生效了），但「要不要出现」
     * 的门禁要看：用户把字幕关掉之后 `rows` 自然是空的，只用 `rows.isEmpty()`
     * 判断会把「你关的」和「这首歌没有词」混成同一句提示。
     */
    val mode: SubtitleDisplayMode = SubtitleDisplayMode.DEFAULT,
    /** 没有歌词时浮窗显示曲名，至少让人知道「浮窗没错、是这首歌没词」。 */
    val title: String? = null,
) {
    val hasLyrics: Boolean get() = rows.isNotEmpty()
}

/**
 * 桌面歌词的应用级状态源。
 *
 * ## 为什么必须是 Koin 的 `single`
 *
 * 悬浮窗活在 Activity 之外——它连「播放页那个导航项」都不存在的时候也要工作。
 * 而 `SubtitleViewModel` 是 `viewModel` 作用域（跟着播放页的 `NavBackStackEntry`），
 * 出了播放页就没有实例。所以「当前歌词文档」必须有一份**应用级**的落点，
 * 由播放页主动推上来，浮窗只读它。
 *
 * ## 两条数据来源，播放页优先
 *
 * 1. **播放页推送**（[publish]）：带着用户手选的轨道、内嵌字幕、以及歌词页那份
 *    时间轴微调与速率。这是最准的一份。
 * 2. **自己兜底加载**：用户从播放页返回列表、只用迷你播放器听歌时，播放页的
 *    ViewModel 已经销毁，推送也随之中断。此时按「自动匹配」重新扫一遍外挂字幕。
 *
 * 兜底**只在没有推送时**生效，而且用同一套 `bestAutoMatch()` 判据——不做第二份
 * 打分逻辑，否则浮窗和播放页会挂上不同的字幕，那是最难查的一类「对不上」。
 * 兜底拿不到内嵌字幕与译文，这是有意的取舍：那两样只有解析过整份文档的人才有。
 *
 * ## 为什么类本身是 public、成员却全是 internal
 *
 * 它要被 [SubtitleViewModel] 的构造函数接住，而那个类是 public——Kotlin 不允许
 * 一个 public 构造参数用 internal 类型。真正想藏的是里面的状态（`DesktopLyricsUiState`
 * 里全是 internal 的行模型），所以类是 public，露出面全部 internal。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLyricsController(
    private val playback: PlaybackController,
    private val subtitleRepository: SubtitleRepository,
    private val desktopSettings: DesktopLyricsSettingsRepository,
    subtitleSettings: SubtitleSettingsRepository,
) {

    /** 播放页推上来的那份（含 ownerKey，用于「谁推的谁收走」）。 */
    private class Published(
        val ownerKey: Any,
        val document: SubtitleDocument?,
        val timelineOffsetMs: Long,
        val ratePermille: Int,
    )

    /**
     * 自己的作用域，不跟任何 ViewModel 走。
     *
     * `Main.immediate`：`stateIn` 的初值同步下发，浮窗一打开就是正确内容，
     * 不会先闪一帧空的。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val published = MutableStateFlow<Published?>(null)

    /** 兜底加载的结果（只按曲目缓存，切歌才重扫）。 */
    private val fallback = combine(
        playback.currentEntry.distinctUntilChangedBy { it?.id },
        published.distinctUntilChanged { old, new -> (old != null) == (new != null) },
    ) { entry, pub ->
        entry to (pub != null)
    }
        .distinctUntilChanged()
        .mapLatest { (entry, hasPublication) ->
            // 播放页在推送时不做兜底：那一份更准，重扫只是白烧一次 MediaStore 查询。
            if (entry == null || hasPublication) null else loadAutoLyrics(entry)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    internal val state: StateFlow<DesktopLyricsUiState> = combine(
        combine(playback.currentEntry, playback.positionMs, playback.state) { entry, position, playbackState ->
            Triple(entry, position, playbackState.isPlaying)
        },
        combine(desktopSettings.settings, subtitleSettings.settings) { desktop, subtitles ->
            desktop to subtitles.displayMode
        },
        combine(published, fallback) { pub, auto -> pub to auto },
    ) { (entry, positionMs, isPlaying), (settings, mode), (pub, auto) ->
        val document = if (pub != null) pub.document else auto
        val rows = desktopLyricsRows(
            document = document,
            positionMs = positionMs,
            mode = mode,
            timelineOffsetMs = pub?.timelineOffsetMs ?: 0L,
            ratePermille = pub?.ratePermille ?: SUBTITLE_RATE_BASE_PERMILLE,
        )
        DesktopLyricsUiState(
            rows = rows,
            style = settings.style,
            locked = settings.locked,
            isPlaying = isPlaying,
            mode = mode,
            title = entry?.title,
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
        DesktopLyricsUiState(),
    )

    /**
     * 播放页把当前文档推上来。
     *
     * 推送频率是「文档/偏移/速率变化时」，**不含播放位置**——位置每 200ms 变一次，
     * 让它触发推送等于每 200ms 跨组件拷一次整份文档引用（虽然只是引用，但会在
     * 浮动窗口那边引发一次无意义的重算）。位置由 [state] 自己在内部合并。
     */
    internal fun publish(
        ownerKey: Any,
        document: SubtitleDocument?,
        timelineOffsetMs: Long,
        ratePermille: Int,
    ) {
        published.value = Published(ownerKey, document, timelineOffsetMs, ratePermille)
    }

    /**
     * 播放页销毁时收回自己的推送。
     *
     * 比对 ownerKey 而不是无脑清空：旧播放页的 `onCleared` 可能晚于新播放页的
     * 第一次 `publish`（返回上一页再点进另一首歌），无条件清空会把**新**那份
     * 抹掉，症状是「换歌之后浮窗一直停在旧歌词上」。
     */
    internal fun unpublish(ownerKey: Any) {
        published.value = published.value?.takeIf { it.ownerKey !== ownerKey }
    }

    // ------------------------------------------------------------------ 动作

    internal fun togglePlayPause() = playback.togglePlayPause()

    internal fun setEnabled(enabled: Boolean) {
        scope.launch { desktopSettings.setEnabled(enabled) }
    }

    internal fun setLocked(locked: Boolean) {
        scope.launch { desktopSettings.setLocked(locked) }
    }

    internal fun setFontSize(size: DesktopLyricsFontSize) {
        scope.launch { desktopSettings.setFontSize(size) }
    }

    internal fun setOpacity(opacity: DesktopLyricsOpacity) {
        scope.launch { desktopSettings.setOpacity(opacity) }
    }

    /**
     * 记下用户拖到的位置。
     *
     * 只在**松手**时调用：拖动过程中每帧写一次 DataStore，等于把一次手势变成
     * 几百次磁盘事务，而用户从手感上完全看不出区别。
     */
    internal fun savePosition(position: DesktopLyricsPosition) {
        scope.launch { desktopSettings.setPosition(position) }
    }

    /**
     * 没有播放页推送时，自己找一份能显示的字幕。
     *
     * 失败一律返回 `null`（浮窗那时显示曲名）：兜底路径不该弹出错误提示，
     * 用户根本没打开任何字幕界面。
     */
    private suspend fun loadAutoLyrics(entry: MediaEntry): SubtitleDocument? {
        val scan = subtitleRepository.scan(entry)
        val source = (scan as? SubtitleScan.Found)?.sources?.bestAutoMatch() ?: return null
        return (subtitleRepository.load(source) as? SubtitleLoadResult.Loaded)?.document
    }

    private companion object {
        /**
         * 浮窗收起后上游还能多活 5 秒。
         *
         * 回到应用 → 浮窗隐藏 → 再切出去，两次之间常常只有一两秒；立刻停掉上游
         * 会让每次进出都重新扫一遍目录。
         */
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
