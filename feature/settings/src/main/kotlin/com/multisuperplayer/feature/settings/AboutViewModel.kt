package com.multisuperplayer.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.device.DeviceInfo
import com.multisuperplayer.core.common.device.DeviceSnapshot
import com.multisuperplayer.core.common.log.LogRepository
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导出日志这件事的三种状态。**没有中间态**：要么还没导出，要么正在写，要么有了结果。 */
sealed interface LogExportState {
    data object Idle : LogExportState

    data object Running : LogExportState

    /** 写成功了。[failedFiles] 非空时也要如实告诉用户——那正是他需要知道的那天可能缺了。 */
    data class Saved(
        val fileName: String,
        val includedFiles: Int,
        val failedFiles: List<String>,
    ) : LogExportState

    data class Failed(val message: MspText) : LogExportState
}

/**
 * 「关于」页的数据源。
 *
 * ## 为什么单独开一个 ViewModel
 *
 * 这一页有两件事会碰系统：读设备信息（要 WindowManager）和读/写日志文件。
 * 两件事都**不该发生在冷启动路径上**，而 `SettingsViewModel` 是 Activity 作用域、
 * 应用一启动就被创建（主题要靠它即时生效）。所以设备信息与日志放到这里：
 * 只有用户真的推开了「关于」这一页才会付出代价。
 *
 * 另外它和 [SettingsViewModel] 有个本质区别：这一页全是**只读**的，
 * 没有需要落盘的设置项，因此不注入任何 `SettingsRepository`。
 */
class AboutViewModel(
    context: Context,
    val buildInfo: AppBuildInfo,
    private val logs: LogRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val appContext = context.applicationContext

    /**
     * 设备信息。构造时同步读一次。
     *
     * 读失败**不抛**：这一页的其余部分（版本、许可、日志）都还有用，
     * 为了一个小节把整页变成崩溃不合理。[DeviceInfo.snapshot] 里已经有
     * API 30 与更低版本两条路径，这里再兜一层是为了防厂商 ROM 上的意外。
     */
    val device: DeviceSnapshot? = runCatching { DeviceInfo.snapshot(appContext) }
        .onFailure { MspLog.w(TAG, it) { "读取设备信息失败，关于页将不显示设备小节" } }
        .getOrNull()

    private val _logSummary = MutableStateFlow(LogSummary.Empty)

    /** 日志目录概览：几个文件、多大、覆盖哪几天。用来在导出前告诉用户「会得到什么」。 */
    val logSummary: StateFlow<LogSummary> = _logSummary.asStateFlow()

    private val _export = MutableStateFlow<LogExportState>(LogExportState.Idle)

    val export: StateFlow<LogExportState> = _export.asStateFlow()

    private val _linkFailure = MutableStateFlow<MspText?>(null)

    /**
     * 上一次「打开链接」的失败原因。null = 没失败过（或用户已关掉提示）。
     *
     * 做成一个可观察的状态而不是「吐一下 toast」：要展示的不是「失败了」，
     * 而是**哪个地址**失败了——用户下一步多半是把它手动抄进浏览器。
     */
    val linkFailure: StateFlow<MspText?> = _linkFailure.asStateFlow()

    init {
        refreshLogs()
    }

    /** 重算日志概览。导出后调用一次，让「文件数/大小」跟着变。 */
    fun refreshLogs() {
        viewModelScope.launch {
            _logSummary.value = withContext(dispatchers.io) {
                // 目录不可读（权限、还没装 sink）时给空概览，而不是让这一页报错。
                runCatching { logs.summary() }.getOrElse { LogSummary.Empty }
            }
        }
    }

    /** SAF 对话框要的建议文件名。纯计算，不碰磁盘。 */
    fun suggestedFileName(): String = logs.suggestedFileName()

    /**
     * 用系统浏览器打开 [url]。
     *
     * 失败**不抛也不静默**：设备上没有浏览器、或者 ROM 直接关掉了 `ACTION_VIEW` 时，
     * `startActivity` 会抛 [android.content.ActivityNotFoundException]，
     * 放出去就是闪退——而这一页唯一会做的事就是「打开一个网页」。
     * 失败时把地址本身带进提示里，用户至少能手动复制。
     *
     * 用 `applicationContext` 起 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`，
     * 否则会抛 `AndroidRuntimeException`（看起来像「没装浏览器」）。
     * 这个逻辑放在 ViewModel 而不是 Composable 里，是为了能单测。
     */
    fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val failure = runCatching { appContext.startActivity(intent) }.exceptionOrNull()
        if (failure != null) {
            MspLog.w(TAG, failure) { "打开链接失败：$url" }
        }
        _linkFailure.value = linkFailureText(failure, url)
    }

    /** 用户看过、关掉了提示。 */
    fun dismissLinkFailure() {
        _linkFailure.value = null
    }

    /**
     * 把报告写进用户选定的位置。
     *
     * 用 SAF 的 `CreateDocument` 而不是直接写自己的外部私有目录：
     * Android 11 起 `Android/data` 不能从文件管理器里浏览，写进去等于写进黑洞
     * ——用户拿着「已导出」的提示却找不到文件。SAF 写哪里由用户决定，
     * 也顺带绕开了存储权限。
     */
    fun exportTo(uri: Uri) {
        // 双击、或者点按钮时系统还没把上一次跑完，都会重入。第二次写同一个 uri
        // 会把文件截断成半份，所以这里直接挡掉。
        if (_export.value == LogExportState.Running) return
        _export.value = LogExportState.Running

        viewModelScope.launch {
            val outcome = withContext(dispatchers.io) {
                runCatching { writeReport(uri) }
            }
            _export.value = outcome.getOrElse { error ->
                MspLog.w(TAG, error) { "导出日志失败" }
                // IO 异常自带的话是技术细节，翻不了也不应该翻；
                // 但它至少比空串有用，空串用 [MspText.plainOrUnknown] 兑成「未知」。
                LogExportState.Failed(MspText.plainOrUnknown(error.message ?: error::class.java.simpleName))
            }
            refreshLogs()
        }
    }

    /**
     * 报告里带上的抬头：构建信息 + 设备信息。和关于页上显示的是同一批数据。
     *
     * 顺带在这里渲染成 `String`：[LogRepository.buildReport] 要的是已经拼好的行，
     * 而日志文件是写给「发给别人看」的，不应该带资源 id。
     */
    private fun reportHeader(): List<String> =
        (buildInfo.rows() + device?.rows().orEmpty()).map { it.render(appContext.resources) }

    /** 在 IO 线程上执行。 */
    private fun writeReport(uri: Uri): LogExportState {
        val report = logs.buildReport(header = reportHeader())
        val stream = appContext.contentResolver.openOutputStream(uri)
            ?: return LogExportState.Failed(
                MspText.Res(R.string.msp_settings_about_export_open_failed),
            )
        stream.use { it.write(report.text.toByteArray(Charsets.UTF_8)) }
        MspLog.i(TAG) {
            "已导出日志：${report.fileName}（${report.includedFiles} 个文件，" +
                "跳过 ${report.failedFiles.size} 个）"
        }
        return LogExportState.Saved(
            fileName = report.fileName,
            includedFiles = report.includedFiles,
            failedFiles = report.failedFiles,
        )
    }

    private companion object {
        const val TAG = "AboutViewModel"
    }
}

/**
 * 「打开外链失败」该给用户看什么。成功返回 null（不留提示）。
 *
 * 抽成顶层纯函数，是因为它是这一页唯一的分支逻辑，而 [AboutViewModel] 本身在
 * 纯 JVM 单测里构造不出来：它的构造要 `Context`，而本模块没开
 * `unitTests.isReturnDefaultValues`，`Intent` 一构造就会抛「not mocked」。
 * 这个函数不碰任何 Android 类，所以能被 [com.multisuperplayer.feature.settings]
 * 的单测直接钉住——「失败提示里必须带地址」这条约定，光靠真机上戳一次是守不住的：
 * 它错的后果是用户拿着「打开失败」四个字、无路可走。
 */
internal fun linkFailureText(failure: Throwable?, url: String): MspText? =
    if (failure == null) null else MspText.Res(R.string.msp_settings_about_link_failed, url)
