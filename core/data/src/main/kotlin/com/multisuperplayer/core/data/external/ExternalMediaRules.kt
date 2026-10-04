package com.multisuperplayer.core.data.external

import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.data.remote.RemoteUrlRules
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource

/**
 * 别的应用递给我们的「用户想播这些」。由 [ExternalMediaRules.read] 从纯数据造出来。
 *
 * @param action 收到的 action，只用来打日志（出问题时第一件事就是确认
 *   「到底收到的是 VIEW 还是 SEND」）。
 * @param mimeType 发送方声明的类型，已经去掉 `;charset=` 这类参数；没有时是 `null`。
 * @param refs 收下的 uri（[ExternalRef]）。**至少一条**，一条都没有时 [ExternalMediaRules.read]
 *   返回 `null` 而不是一个空 payload——「收到了但里面没有能播的东西」和「什么都没收到」
 *   在下游是同一种处理，多一个空集合只会多一种要判断的状态。
 */
data class ExternalPayload(
    val action: String,
    val mimeType: String?,
    val refs: List<ExternalRef>,
)

/**
 * 外部递进来的一条 uri，以及我们为它判定的来源与类型。
 *
 * [source] 是**逐条**判的而不是整批一个：`ACTION_SEND_MULTIPLE` 完全可能同时递进来
 * 一个 `content://` 和一个 `https://`（分享一叠「链接 + 本地文件」），
 * 而这两者在字幕查找、封面、id 前缀上的处理都不一样。
 */
data class ExternalRef(
    val uri: String,
    val source: MediaSource,
    val kind: MediaKind,
)

/**
 * 「外部打开 / 分享 / 网页调用」的读取规则，**纯函数**，可单测。
 *
 * ## 为什么参数是裸数据而不是 `Intent`
 *
 * 单测跑在 JVM 上，而 `android.jar` 在这些模块里只是一组空壳：`Intent` 的方法
 * 要么返回默认值、要么抛「not mocked」（`core:data/build.gradle.kts` 里
 * `unitTests.isReturnDefaultValues = true`）。也就是说**只要规则层碰了 `Intent`，
 * 这条路上就没有任何东西可以断言**——而这里恰好全是「哪些该收、哪些该扔、
 * 类型怎么判」这类必须要测的取舍。所以拆成两层：
 *
 * - `ExternalIntentReader`（碰 `Intent`，不测）负责把 `Intent` 摊成
 *   action / data / stream / MIME 四个值；
 * - 这里（不碰 `Intent`，全测）负责所有取舍。
 *
 * 常量写成字面量也是同一个理由：字面量在任何环境下都是它自己，
 * 而 `Intent.ACTION_VIEW` 是 `android.jar` 里的一个字段。
 */
internal object ExternalMediaRules {

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val EXTRA_STREAM = "android.intent.extra.STREAM"

    /**
     * 读一次外部请求；没有任何可播的东西时返回 `null`。
     *
     * | action | 看哪个字段 | 来源 |
     * |---|---|---|
     * | `VIEW` | `intent.data` | 协议是 http/https 的算 [MediaSource.REMOTE]，其余算 [MediaSource.SHARED] |
     * | `SEND` / `SEND_MULTIPLE` | `intent` 的 stream 列表 | 一律 [MediaSource.SHARED] |
     *
     * 其余 action 一律 `null`：我们的 manifest 只声明了这三个，别的 action 走到这里
     * 说明是被人显式指定包名发过来的（多半是调试），按「不认识」处理比猜一个语义好。
     *
     * 重复的 uri 会去重：`SEND_MULTIPLE` 里同一个文件被列两次是发送方的疏忽，
     * 而队列里出现两条一模一样的条目会让「下一首」看起来像没反应。
     */
    fun read(
        action: String?,
        mimeType: String?,
        dataUri: String?,
        streamUris: List<String>,
    ): ExternalPayload? {
        val candidates = when (action) {
            ACTION_VIEW -> listOfNotNull(dataUri?.takeIf { it.isNotBlank() })
            ACTION_SEND, ACTION_SEND_MULTIPLE -> streamUris.filter { it.isNotBlank() }
            else -> return null
        }
        if (candidates.isEmpty()) return null

        // `;` 后面是 charset 之类的参数，去掉再比（`SafScanRules.kindOf` 里也是这么做的）。
        val type = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
        val refs = candidates.distinct().mapNotNull { uri -> refOf(uri, type) }
        if (refs.isEmpty()) return null
        return ExternalPayload(action = action, mimeType = type, refs = refs)
    }

    /**
     * 一条 uri 的来源。
     *
     * 判据是**协议**而不是「看起来像不像网址」：`https://` 出现在路径里的
     * `content://` 也会是 http 前缀（`content://com.x/https://a.mp4`），
     * 只有协议那一段能说话。
     */
    fun sourceOf(uri: String): MediaSource {
        val scheme = uri.substringBefore("://", "").lowercase()
        return if (scheme == "http" || scheme == "https") MediaSource.REMOTE else MediaSource.SHARED
    }

    /**
     * uri 最后一段的文件名（含百分号解码）。
     *
     * 和网络地址用的是**同一条**规则：它们都只是「末尾那个路径段」。
     * 对 `content://` uri 来说它常常只是一串数字（`…/media/123`），
     * 那没关系——真正的文件名由 [ExternalMediaResolver] 查 `DISPLAY_NAME` 补上。
     */
    fun fileNameOf(uri: String): String = RemoteUrlRules.fileNameOf(uri)

    private fun refOf(uri: String, mimeType: String?): ExternalRef? {
        val source = sourceOf(uri)
        val kind = SafScanRules.kindOf(fileNameOf(uri), mimeType)
        if (kind != null) return ExternalRef(uri = uri, source = source, kind = kind)

        // 后缀明确不是媒体（字幕、图片、文档…）：
        // - 分享过来的直接剔掉。一次分享一叠文件是常见操作，把 `.txt`/`.jpg` 混进
        //   播放队列只会让「下一首」卡在一个必然失败的条目上；
        // - **网络地址不剔**：那是用户主动填进来的一条地址，「地址后面不是媒体」
        //   交给内核去做、去报错，比我们把它变成一个安静的空结果好
        //   （和 `RemoteUrlRules.kindOf` 对同一件事的取舍一致）。
        return if (source == MediaSource.REMOTE) {
            ExternalRef(uri = uri, source = source, kind = MediaKind.UNKNOWN)
        } else {
            null
        }
    }
}
