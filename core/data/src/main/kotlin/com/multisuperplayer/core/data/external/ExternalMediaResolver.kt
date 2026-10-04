package com.multisuperplayer.core.data.external

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.data.remote.RemoteUrlRules
import com.multisuperplayer.core.model.ExternalMediaIds
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ExternalMediaResolver"

/**
 * [ExternalRef] → [MediaEntry]：把 provider 才知道的名字/大小/类型补上。
 *
 * ## 为什么要真的去查
 *
 * `content://` 的 uri 末尾常常只是一串数字（`…/video/media/123`），靠它当标题的
 * 结果是界面上出现一行 `123`。`DISPLAY_NAME` 是唯一能拿到真实文件名的办法，
 * 顺带还能拿到 `SIZE`（列表里显示「1.2 GB」用得上）和 `getType`（省内核一次嗅探）。
 *
 * ## 必须在收到请求的当下就查
 *
 * 外部递进来的 `content://` 读权限是**跟着收到它的那次调用**给的，不是永久授权：
 * 进程活着、那次交互还在，就能读；过一阵子（或下一次冷启动）同一个 uri 会直接
 * 抛 `SecurityException`。所以这里走的是「立刻解析 → 立刻入队播放」，
 * 而不是「存起来，以后从最近播放里点开」。这也意味着**分享进来的条目进播放列表后，
 * 隔天再播会失败**——这是 uri 授权模型决定的，不是这里的实现问题；
 * 要长期留存只能先复制到自己的目录，那是另一件事（[MediaSource.SHARED] 这个来源
 * 就是留给这类「看得到但留不住」的条目的）。
 *
 * ## 查不到也要出条目
 *
 * 查询失败（没权限、provider 没实现 OpenableColumns、文件刚被删）时**不返回 null**，
 * 而是用「uri 末尾那段」当名字：用户明明点了它，什么都不发生比一条播不出来的条目
 * 更难懂。大小取不到就是 0，界面上不会显示。
 */
class ExternalMediaResolver(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    suspend fun resolve(payload: ExternalPayload): List<MediaEntry> = withContext(dispatchers.io) {
        payload.refs.map { ref -> entryOf(ref, payload.mimeType) }
    }

    private fun entryOf(ref: ExternalRef, payloadMimeType: String?): MediaEntry {
        val details = detailsOf(ref.uri)
        val name = details.name?.takeIf { it.isNotBlank() } ?: ExternalMediaRules.fileNameOf(ref.uri)
        val mimeType = details.mimeType ?: payloadMimeType

        // 类型只**细化**，不重新决定收不收：收不收是纯规则 `ExternalMediaRules.read`
        // 的结论，在这里再判一次的话，同一条 uri 会得到两种答案——因为
        // 「uri 末尾那段」和 provider 给的 DISPLAY_NAME 压根不是同一个字符串
        // （`…/media/123` vs `电影.mp4`）。细化只补一个 case：刚才判不出类型时，
        // 现在有了真名字再试一次（`…/media/123` + 没有 MIME + DISPLAY_NAME 是 .mp4）。
        val kind = if (ref.kind == MediaKind.UNKNOWN) {
            SafScanRules.kindOf(name, mimeType) ?: MediaKind.UNKNOWN
        } else {
            ref.kind
        }

        return MediaEntry(
            // id 从这里开始就是**身份**了（播放位置、最近播放、播放列表都按它记），
            // 所以前缀必须写死在这里而不是让调用方传：外部来源的 id 只有
            // `ExternalMediaIds` 一个出处，`PlaylistItem.sourceOf` 靠它反推来源。
            id = when (ref.source) {
                MediaSource.REMOTE -> ExternalMediaIds.remoteIdOf(ref.uri)
                else -> ExternalMediaIds.sharedIdOf(ref.uri)
            },
            uri = ref.uri,
            title = if (name.isBlank()) ref.uri else RemoteUrlRules.displayTitleOf(name),
            kind = kind,
            source = ref.source,
            sizeBytes = details.sizeBytes,
            mimeType = mimeType,
            displayName = name.takeIf { it.isNotBlank() },
        )
    }

    private fun detailsOf(uriText: String): Details {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return Details()
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_FILE -> fileDetails(uri)
            ContentResolver.SCHEME_CONTENT -> contentDetails(uri)
            // 网络地址不查：没有本地元数据可查，名字与类型都由地址本身推导。
            else -> Details()
        }
    }

    private fun fileDetails(uri: Uri): Details {
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return Details()
        val file = File(path)
        // 文件不存在 / 读不到时 `length()` 返回 0 且不抛异常，条目照样建出来。
        return Details(name = file.name, sizeBytes = file.length().coerceAtLeast(0L))
    }

    private fun contentDetails(uri: Uri): Details {
        // 先要 MIME：`query` 失败（很常见）时它至少还留着类型。
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        var name: String? = null
        var size = 0L
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (error: Exception) {
            // 这里**必须**记日志：症状是「条目进了队列、标题是一串数字、大小是 0」，
            // 从界面上完全看不出是查询失败。而最可能的两个原因（发送方没给读权限、
            // 进程复用后授权已过期）都不会在别处留下任何痕迹。
            MspLog.w(TAG, error) { "读取外部 uri 的元数据失败：$uri" }
        }
        return Details(name = name, sizeBytes = size.coerceAtLeast(0L), mimeType = mimeType)
    }

    private data class Details(
        val name: String? = null,
        val sizeBytes: Long = 0L,
        val mimeType: String? = null,
    )
}
