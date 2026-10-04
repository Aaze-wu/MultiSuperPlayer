package com.multisuperplayer.core.data.update

import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 「检查更新」这一整件事的入口。
 *
 * 只做编排：取设置 → 问数据源 → 交给 [UpdateRules] 判定 / 下载 → 交给
 * [UpdateApkVerifier] 校验。**判断本身一行都不在这里**（在 [UpdateRules] 里），
 * 因为那是纯逻辑、有单测的部分，而编排需要真机。
 *
 * @param currentVersion 当前安装的版本。传 null 表示读不出来（构建信息缺失），
 *   此时结论是「无法确定当前版本」而不是拿一个猜的去比。
 */
class UpdateManager(
    private val source: UpdateSource,
    private val settingsRepository: UpdateSettingsRepository,
    private val downloader: UpdateDownloader,
    private val verifier: UpdateApkVerifier,
    private val currentVersion: UpdateVersion?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 界面要显示的设置（通道、自动检查、上次检查时间、有没有填令牌）。 */
    val settings = settingsRepository.settings

    /** 首帧的兜底，见 `UpdateSettingsRepository.defaultSettings` 的注释。 */
    val defaultSettings = settingsRepository.defaultSettings

    /**
     * 检查一次。
     *
     * **失败时也会记下这次检查的时刻**：节流挡的是「同一分钟内点十次」，而失败之后
     * 狂点是最常见的反应。只在成功时记，节流就正好在最需要它的场景失效。
     * 代价是失败后 12 小时内不再自动检查——用户仍然可以手动点（手动那条路不看节流）。
     *
     * **被开关或节流跳过的检查什么都不记**（见下）。跳过和失败是两件事：
     * 前者是「这一次请求压根没发出去」，后者是「发出去了、没成」。
     *
     * @param trigger 谁发起的。用户在界面上点的那次传 [UpdateCheckTrigger.MANUAL]
     *   （跳过节流，点了就得真的去问）；启动时和进更新页时传
     *   [UpdateCheckTrigger.AUTO]（受「自动检查更新」开关与
     *   [UpdateRules.AUTO_CHECK_INTERVAL_MS] 限制）。
     * @return 判定结果；被跳过时返回 null——**null 不是「已经是最新」**，
     *   界面在拿到 null 时必须保持原样，否则每次进这一页都会把「有新版」擦成「已是最新」。
     * @throws UpdateException 网络/HTTP/解析失败。界面用 [describeUpdateFailure] 转成文案。
     */
    suspend fun check(
        trigger: UpdateCheckTrigger = UpdateCheckTrigger.MANUAL,
    ): UpdateAvailability? {
        // 时钟只读一次：它同时决定「要不要跳过」和「记下什么时候查的」。
        // 读两次的话注入的测试时钟会变成一个被观察的计数器（同一件事两个答案）。
        val at = now()
        val snapshot = settings.first()
        if (
            UpdateRules.skipsAutoCheck(
                trigger = trigger,
                autoCheck = snapshot.autoCheck,
                lastCheckAtEpochMs = snapshot.lastCheckAtEpochMs,
                atEpochMs = at,
            )
        ) {
            // **连 `markChecked` 都不记**，直接返回。记了的话，「压根没发生的一次检查」
            // 会占用掉那 12 小时的窗口：用户把开关关掉、过一会儿再打开，
            // 第一次启动检查就会被上一次没发生的检查挡掉 12 小时——
            // 一个自己造成的、界面上完全看不出来的静默失效。
            return null
        }
        return try {
            val releases = source.listReleases()
            UpdateRules.decide(
                current = currentVersion,
                releases = releases,
                channel = snapshot.channel,
                ignoredTag = snapshot.ignoredTag,
            )
        } finally {
            settingsRepository.markChecked(at)
        }
    }

    suspend fun setChannel(channel: UpdateChannel) = settingsRepository.setChannel(channel)

    suspend fun setAutoCheck(enabled: Boolean) = settingsRepository.setAutoCheck(enabled)

    /** 忽略某一版。传 null 撤销忽略。 */
    suspend fun setIgnoredTag(tag: String?) = settingsRepository.setIgnoredTag(tag)

    suspend fun currentToken(): String? = settingsRepository.currentToken()

    suspend fun putToken(input: String): Boolean = settingsRepository.putToken(input)

    suspend fun clearToken() = settingsRepository.clearToken()

    /**
     * 下载并校验。
     *
     * 校验（包名 + 签名）放在这里而不是交给调用方，是因为**它们必须成对发生**：
     * 只要有一条路径能拿到「下载完成」的文件而不经过校验，那条路径就是可被利用的。
     * 返回值一定是已经通过校验的文件。
     *
     * @throws UpdateException 下载失败、摘要不符、或者包被拒（[UpdateException.RejectedApk]）。
     */
    suspend fun downloadAndVerify(
        release: UpdateRelease,
        onProgress: (UpdateProgress) -> Unit = {},
    ): File {
        val apk = downloader.download(release, onProgress)
        when (val result = verifier.verify(apk)) {
            is ApkVerification.Accepted -> {
                MspLog.d(TAG) { "安装包校验通过：${apk.name}" }
                return apk
            }

            is ApkVerification.Rejected -> {
                // 被拒的文件必须**立刻删掉**：留着它既占 140 MB，又会让下一次下载的
                // 「文件已存在」分支把它当成下好的东西。
                val deleted = apk.delete()
                MspLog.w(TAG) { "安装包被拒绝（${result.reason}），已删除=$deleted" }
                throw UpdateException.RejectedApk(result)
            }
        }
    }

    private companion object {
        const val TAG = "UpdateManager"
    }
}

/**
 * 把失败说成人话。
 *
 * 与 `describeAsrFailure` 同一个套路：文案与「失败原因」的映射只有这一处，
 * 界面不做 `is` 判断。
 *
 * **限流必须与网络错误分开**：前者的下一步是「过一会儿再试，或者填一个令牌」，
 * 后者是「检查网络」。合成一句「检查失败」的后果是用户反复点重试、每次都失败，
 * 而他永远不知道只要等一小时就好。
 */
fun UpdateException.describeUpdateFailure(): UpdateFailureText = when (this) {
    is UpdateException.Network -> UpdateFailureText.Network

    is UpdateException.Http -> when {
        rateLimited -> UpdateFailureText.RateLimited
        code == 404 -> UpdateFailureText.NotFound
        else -> UpdateFailureText.ServerError
    }

    is UpdateException.NoAsset -> UpdateFailureText.NoAsset
    is UpdateException.AssetMismatch -> UpdateFailureText.DownloadCorrupted
    is UpdateException.Checksum -> UpdateFailureText.DownloadCorrupted
    is UpdateException.RejectedApk -> when (verification.reason) {
        ApkRejection.NOT_AN_APK -> UpdateFailureText.DownloadCorrupted
        ApkRejection.PACKAGE_MISMATCH,
        ApkRejection.SIGNATURE_MISMATCH,
        -> UpdateFailureText.SignatureMismatch
    }
}

/**
 * 失败的类别。界面按它选文案——**不按异常类型**：异常是多层包裹的
 * （数据源抛 HTTP、下载器抛摘要、校验器抛包被拒），在界面里 `is` 一遍会在
 * 某一层换掉异常类型时静默失效，表现为「错误提示对不上真正的失败原因」。
 */
enum class UpdateFailureText {
    /** 网络不通 / DNS / 超时。 */
    Network,

    /** 被 GitHub 限流（403/429）。 */
    RateLimited,

    /** 仓库或发布找不到（404）。 */
    NotFound,

    /** 服务端错误（5xx）。 */
    ServerError,

    /** 这一版没有可下载的安装包。 */
    NoAsset,

    /** 下载完了但内容坏了（长度不符 / 摘要不符 / 不是有效 APK）。 */
    DownloadCorrupted,

    /** 包是真的 APK，但不是我们签的那个。 */
    SignatureMismatch,

    /**
     * 系统里没有能处理 APK 的安装器（或共享路径配置错了）。
     *
     * 与 [DownloadCorrupted] 分开：前者的文件是好的，只是这台机器装不了，
     * 后者要重新下。合成一句会让用户反复重下那 140 MB。
     */
    NoInstaller,
}
