package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelInstaller
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrModelProgress
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.asr.describeAsrFailure
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AsrSettingsViewModel"

/**
 * 「语音识别」设置页上的那条提示。
 *
 * [failed] 不只是一个颜色：失败的那条**要能被手动关掉**（否则它会一直挂在那里，
 * 而用户可能已经去改了下载源），成功的提示只是「刚才那一下成了」，
 * 关不关都行。界面按这个字段决定给不给关闭按钮。
 */
data class AsrSettingsMessage(val text: MspText, val failed: Boolean)

/**
 * 「语音识别」设置页的状态。
 *
 * 下载的三个字段（[installing] / [progress] / [message]）放在同一个 data class 里，
 * 而不是像翻译页那样拆成三个 `StateFlow`：这一页只有**一个**会动的动作，
 * 拆开只会让「正在下载」和进度条有可能不同步（先画出进度条、再画按钮，
 * 中间那一帧的进度条是空的）。
 *
 * [statuses] 是「每条模型在磁盘上的状态」，不是单条——模型列表要在一次绘制里
 * 把两条都显示出来。没读过盘时是空表（[statusOf] 兜底成「未下载」，
 * 而不是让界面显示「未知」：首帧的空白不该看起来像错误）。
 */
data class AsrSettingsUiState(
    val settings: AsrSettings = AsrSettings(),
    val statuses: Map<String, AsrModelStatus> = emptyMap(),
    val installing: Boolean = false,
    val progress: AsrModelProgress? = null,
    val message: AsrSettingsMessage? = null,
) {

    /** 当前选中的模型。 */
    val model: AsrModelInfo get() = settings.model

    /** 当前选中模型的状态。 */
    val status: AsrModelStatus get() = statusOf(model)

    fun statusOf(model: AsrModelInfo): AsrModelStatus =
        statuses[model.id] ?: AsrModelStatus.Absent

    /**
     * 当前模型占了多少磁盘。
     *
     * 「删除模型（释放 190 MB）」里的那个数。下了一半时**不能**说总体积：
     * 释放不了的东西写出来就是在骗人。
     */
    val occupiedBytes: Long get() = status.occupiedBytesOf(model)

    /**
     * 填进去的下载源看起来是不是一个完整地址。
     *
     * 只用来决定那一栏要不要标红，**不是**一道拦截：空值是真话（用默认源），
     * 而域名拼错这种事只有下的时候才知道。
     */
    val sourceLooksValid: Boolean get() = looksLikeHttpUrl(settings.storedBaseUrl)
}

/** 某个状态下这条模型实际占了磁盘多少。见 [AsrSettingsUiState.occupiedBytes]。 */
internal fun AsrModelStatus.occupiedBytesOf(model: AsrModelInfo): Long = when (this) {
    AsrModelStatus.Absent -> 0L
    is AsrModelStatus.Partial -> presentBytes
    AsrModelStatus.Ready -> model.totalBytes
}

/**
 * 下载源是不是以 http(s):// 开头（空值算合格：空 = 用默认源）。
 *
 * 只检查协议头，不解析 URL：这一栏的错法几乎只有「少写 `https://`」这一种
 * ——`AsrModelCatalog.normalizeModelBaseUrl` 故意不补协议（见其注释），
 * 而拼错的域名要到真下的时候才会以 `UnknownHostException` 露头。
 */
internal fun looksLikeHttpUrl(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}

/**
 * 「语音识别」设置页。
 *
 * ## 这一页存在的理由
 *
 * 播放页的字幕面板里那个「生成字幕」按钮**没有任何可选项**：它用当前选中的模型、
 * 当前的下载源，然后把事做完。那是对的默认行为，但两件事必须有个地方说清楚：
 *
 * 1. **用哪条模型**。两条差着一倍体积、差着中文/英文的口径，用户下哪条是个真实的选择；
 * 2. **从哪儿下**。默认是国内镜像，而镜像站有自己的可用性曲线（也可能被公司的网络
 *    掐掉）。把它做成一个可填的地址，是唯一一种「下不动时还能自救」的办法。
 *
 * 这一页**不做**识别本身：那件事依赖「用户正在播哪条片子」，属于播放页。
 */
class AsrSettingsViewModel(
    private val asrSettings: AsrSettingsRepository,
    private val asrModels: AsrModelLocator,
    private val asrInstaller: AsrModelInstaller,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(AsrSettingsUiState())
    val state: StateFlow<AsrSettingsUiState> = _state.asStateFlow()

    /** 正在跑的下载。它必须能被取消：这是这个应用里唯一一个会持续几分钟的动作。 */
    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            asrSettings.settings.collect { settings ->
                // 每读到一次设置就重看一眼磁盘。两件事（用的哪条 / 那条在不在）在界面上
                // 总是并排出现，分两次更新会让中间那一帧出现「换成了另一条，但状态还是旧的」。
                val statuses = readStatuses()
                _state.update { it.copy(settings = settings, statuses = statuses) }
            }
        }
    }

    /**
     * 重新读一遍磁盘状态。
     *
     * 界面在外层 `ON_RESUME` 时调：模型也可能是在**播放页的面板**里下完的，
     * 那个动作发生在这个 ViewModel 之外，没有任何回调会通知这里。
     */
    fun refresh() {
        viewModelScope.launch { _state.update { it.copy(statuses = readStatuses()) } }
    }

    /**
     * 换一条模型。
     *
     * 正在下载时先掐掉：不掐的话进度会挂到**新**选中的那一行上，而它下的其实是
     * 另一条模型的文件——用户会看到「已下载 40%」然后瞬间跳到 100% 或者退回 0%。
     * （界面上同时也会把选项置灰，这里是第二道保险。）
     */
    fun selectModel(id: String) {
        if (id == _state.value.settings.model.id) return
        stopInstall()
        viewModelScope.launch { asrSettings.setModelId(id) }
    }

    /**
     * 改下载源。**只影响之后的下载**：正在跑的那个任务已经按旧地址在下了，
     * 中途换地址会让「下的这一半」和「下的那一半」来自不同版本（哈希对不上，
     * 而 `AsrModelInstaller` 是按文件校验的，最后会以 `HashMismatch` 收场）。
     */
    fun setBaseUrl(raw: String) {
        viewModelScope.launch { asrSettings.setBaseUrl(raw) }
    }

    /** 下全当前模型缺的文件。已经下好的不会重下。 */
    fun install() {
        if (installJob?.isActive == true) return
        val model = _state.value.settings.model
        val baseUrl = _state.value.settings.baseUrl
        _state.update { it.copy(installing = true, progress = null, message = null) }

        installJob = viewModelScope.launch {
            // 进度回调是个普通 lambda，`this` 已经不再是协程作用域，所以要先把 scope 留住。
            // 用它是为了拦掉「取消之后又来的那一次进度回调」（取消是在下载循环里被发现的）。
            val scope = this
            try {
                asrInstaller.install(model, baseUrl) { progress ->
                    if (scope.isActive) _state.update { it.copy(progress = progress) }
                }
                MspLog.i(TAG) { "模型已就绪：${model.id}" }
                val statuses = readStatuses()
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        statuses = statuses,
                        message = AsrSettingsMessage(
                            MspText.Res(R.string.msp_settings_asr_install_done, model.name),
                            failed = false,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                // 用户主动停止：不是失败。状态已经由 [stopInstall] 放回「未在下载」，
                // 这里把取消继续往上抛（协程取消必须继续传播，否则会卡住取消流程）。
                throw cancelled
            } catch (failure: Throwable) {
                MspLog.w(TAG, failure) { "下载模型失败：${model.id}" }
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        message = AsrSettingsMessage(failure.describeAsrFailure(), failed = true),
                    )
                }
            }
        }
    }

    /**
     * 停止下载。
     *
     * 已经下好的文件**留着**：`AsrModelLocator` 认的是「大小对得上的文件」，
     * 所以再点「继续下载」就是从下一个文件接着走，前面那 100 MB 不会白花。
     */
    fun cancelInstall() = stopInstall()

    /**
     * 删掉当前模型的全部文件。
     *
     * 确认对话框在界面上（[AsrSettingsScreen]）：这里不做二次确认——「点没点过确认」
     * 属于界面的事实，ViewModel 再来猜一次就会出现「弹了两次窗」这类问题。
     *
     * 删不掉的失败也不抛出去：删的只是「重新下得回来的模型文件」，
     * 说一句「已经不在手机上了」比让异常冒到 UI 线程好。
     */
    fun remove() {
        val model = _state.value.settings.model
        stopInstall()
        viewModelScope.launch {
            val removed = withContext(dispatchers.io) { asrModels.remove(model) }
            MspLog.i(TAG) { "删除模型 ${model.id}，removed=$removed" }
            val statuses = readStatuses()
            _state.update {
                it.copy(
                    statuses = statuses,
                    message = AsrSettingsMessage(
                        text = MspText.Res(
                            if (removed) {
                                R.string.msp_settings_asr_remove_done
                            } else {
                                R.string.msp_settings_asr_remove_missing
                            },
                            model.name,
                        ),
                        failed = false,
                    ),
                )
            }
        }
    }

    /** 关掉页面上那条提示（失败的那条必需能关，否则它会一直挂着挡着下面的设置）。 */
    fun dismissMessage() {
        if (_state.value.message != null) _state.update { it.copy(message = null) }
    }

    private fun stopInstall() {
        val running = installJob?.takeIf { it.isActive }
        installJob = null
        if (running == null) return
        running.cancel()
        // 不等那个协程真的结束再改状态：取消是在下载循环的下一次迭代才被发现的
        // （最多一个 128 KB 缓冲区），等它会让「停止」看起来慢半拍。
        _state.update { it.copy(installing = false, progress = null) }
    }

    /**
     * 读一遍所有模型的状态。
     *
     * 走 IO 线程：`AsrModelLocator.statusOf` 只 stat 文件长度（不校验哈希，见其注释），
     * 但那也是几次系统调用，放在主线程上读两道模型就是「打开设置页时掉帧」的现成理由。
     */
    private suspend fun readStatuses(): Map<String, AsrModelStatus> = withContext(dispatchers.io) {
        AsrModelCatalog.models.associate { model -> model.id to asrModels.statusOf(model) }
    }
}
