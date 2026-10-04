package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.llm.LlmInstallProgress
import com.multisuperplayer.core.llm.LlmModelCatalog
import com.multisuperplayer.core.llm.LlmModelInfo
import com.multisuperplayer.core.llm.LlmModelInstaller
import com.multisuperplayer.core.llm.LlmModelLocator
import com.multisuperplayer.core.llm.LlmModelStatus
import com.multisuperplayer.core.llm.LlmTextGenerator
import com.multisuperplayer.core.llm.describeLlmFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LocalModelViewModel"

/**
 * 「本地模型」页上的那条提示。同 `AsrSettingsMessage`：
 * 失败的那条要能手动关掉，成功的那条关不关都行。
 */
data class LocalModelMessage(val text: MspText, val failed: Boolean)

/**
 * 「本地模型」设置页的状态：模型文件（占 345 MB 的那一个）的下载、校验与删除。
 *
 * 与 `TranslationSettingsScreen` 里那些纯设置项分开成一个页面，是因为这里的每个
 * 动作都**要跑几十秒到几分钟**（下载 345 MB）并且**要读磁盘**（文件在不在、
 * 占了多少空间）。挤进翻译设置页的话，那一页首次打开就得读一次盘，而它本来是
 * 一次 DataStore 读取——一个只有网络配置的页面不该因为「本地模型」这一行变慢。
 *
 * [statuses] 与 `AsrSettingsUiState` 一样是空表兜底成「未下载」，而不是显示
 * 「未知」：首帧的空白不该看起来像错误。
 */
data class LocalModelUiState(
    val settings: TranslationSettings = TranslationSettings(),
    val statuses: Map<String, LlmModelStatus> = emptyMap(),
    val installing: Boolean = false,
    val progress: LlmInstallProgress? = null,
    val message: LocalModelMessage? = null,
) {

    /**
     * 当前选中的模型。
     *
     * 设置里存的是 id（它同时是目录名），而**用户认识的是清单里的名字**
     * （`Qwen3 0.6B（本地）`）。所以要过一遍清单，不能把 id 直接显示出来。
     * 认不出来的 id 由 [LlmModelCatalog.byId] 兜底成默认模型——同 `AsrModelCatalog`：
     * 一条脏设置不该让设置页崩，回默认模型还能自己恢复。
     */
    val model: LlmModelInfo get() = LlmModelCatalog.byId(settings.model)

    val status: LlmModelStatus get() = statusOf(model)

    fun statusOf(model: LlmModelInfo): LlmModelStatus = statuses[model.id] ?: LlmModelStatus.Absent

    /**
     * 「删除模型（释放 345 MB）」里的那个数。
     *
     * 下了一半时只能说**已经占掉的那些**：说 345 MB 会让用户以为删掉能拿回全部，
     * 而实际回收的是 `.part` 的那点大小。
     */
    val occupiedBytes: Long get() = occupiedBytesOf(model, status)

    /** 用户填的下载源原文（空 = 用内置镜像站）。界面靠它判断要不要显示占位符。 */
    val source: String get() = settings.localModelSource

    /** 下载源的格式自检。同语音识别页：空算合格（空就是「用默认镜像站」）。 */
    val sourceLooksValid: Boolean get() = looksLikeHttpUrl(settings.localModelSource)
}

/** 这条模型在磁盘上占了多少。三态各自的意义见 [LlmModelStatus]。 */
internal fun occupiedBytesOf(model: LlmModelInfo, status: LlmModelStatus): Long = when (status) {
    LlmModelStatus.Absent -> 0L
    is LlmModelStatus.Incomplete -> status.presentBytes
    LlmModelStatus.Ready -> model.sizeBytes
}

/**
 * 「本地模型」页的 ViewModel。
 *
 * ## 为什么独立于 `SettingsViewModel`
 *
 * 一来它需要 `LlmModelInstaller`（拖着一个下载器）与 `LlmTextGenerator`（引擎宿主），
 * 而 `SettingsViewModel` 是 **Activity 作用域**的——加进去就等于「每次冷启动都构造
 * 一遍引擎宿主」。二来这一页的动作是长任务，独立的作用域让「用户返回上一页」
 * 与「下载要不要继续」这两件事互不牵连（现在下载绑在 viewModelScope 上，
 * 退出这一页就取消——与语音识别页一致，理由见 [cancelInstall]）。
 *
 * ## 删除为什么需要引擎
 *
 * 模型是 mmap 进引擎的，不先释放就删文件，磁盘空间要等进程重启才回来
 * （见 [LlmTextGenerator.release]）。所以这里注入了引擎，**只为了删除前那一句
 * `release()`**——没有任何生成会让用户在这一页上等。
 */
class LocalModelSettingsViewModel(
    private val translationSettings: TranslationSettingsRepository,
    private val models: LlmModelLocator,
    private val installer: LlmModelInstaller,
    private val generator: LlmTextGenerator,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalModelUiState())
    val state: StateFlow<LocalModelUiState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            translationSettings.settings.collect { settings ->
                // 设置到了就把磁盘状态一起刷出来：两者的来源不同（DataStore / 文件系统），
                // 分成两次 emit 会让界面出现「模型名换了、状态还是上一条的」那一帧。
                val statuses = readStatuses()
                _state.update { it.copy(settings = settings, statuses = statuses) }
            }
        }
    }

    /** 重新读一次磁盘状态（从别处删过文件、或系统清过缓存之后回来时用）。 */
    fun refresh() {
        viewModelScope.launch { _state.update { it.copy(statuses = readStatuses()) } }
    }

    /**
     * 换一条本地模型。
     *
     * 下载中先停掉：不停的话下载器还在往**旧模型**的目录里写，而进度回调会把
     * 那些字节显示在新的那一行上（进度条跑到一半，然后新模型的状态还是「未下载」）。
     */
    fun selectModel(id: String) {
        if (id == _state.value.model.id) return
        stopInstall()
        viewModelScope.launch { translationSettings.setModel(id) }
    }

    fun setSource(raw: String) {
        viewModelScope.launch { translationSettings.setLocalModelSource(raw) }
    }

    /**
     * 下载（或重新下载）当前模型。
     *
     * 已经下好时是空操作——[LlmModelInstaller.install] 自己是幂等的，界面也会在
     * 「已下载」状态下换成「删除」，所以这个分支正常走不到。
     *
     * 失败**不抛**：异常换成 [LocalModelMessage] 显示在页面上，并记一行日志。
     * 抛出去的结果是 `viewModelScope` 里一个没人接的异常 → 整页崩掉，
     * 而这里最可能发生的失败是「网络断了」，那正是用户想看到提示而不是崩溃的时刻。
     */
    fun install() {
        if (installJob?.isActive == true) return

        val model = _state.value.model
        val source = _state.value.settings.localModelSource
        _state.update { it.copy(installing = true, progress = null, message = null) }
        MspLog.i(TAG) { "开始下载本地模型 ${model.id}" }

        installJob = viewModelScope.launch {
            // 留住协程本身：取消之后回调还在队列里排着，往里写状态就会把
            // 「已取消」的那一帧盖掉（界面重新显示成一个还在跑的进度条）。
            val scope = this
            try {
                installer.install(model, source) { progress ->
                    if (scope.isActive) _state.update { it.copy(progress = progress) }
                }
                MspLog.i(TAG) { "本地模型已就绪：${model.id}" }
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        statuses = readStatuses(),
                        message = LocalModelMessage(
                            text = MspText.Res(R.string.msp_settings_local_model_installed, model.name),
                            failed = false,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                // 用户自己取消的（或退出了这一页）：不是错误，不弹提示。
                // 状态里的 installing 由 stopInstall/取消方负责收尾。
                throw cancelled
            } catch (failure: Throwable) {
                MspLog.w(TAG, failure) { "下载本地模型失败：${model.id}" }
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        statuses = readStatuses(),
                        message = LocalModelMessage(failure.describeLlmFailure(), failed = true),
                    )
                }
            }
        }
    }

    /**
     * 停止下载。
     *
     * 取消**不保留**已下载的字节（下载器不支持断点续传，见 `LlmModelLocator` 的类注释）：
     * 界面上必须说「重新下载」而不是「继续」，否则就是在承诺一件做不到的事。
     */
    fun cancelInstall() = stopInstall()

    /**
     * 删除模型文件。
     *
     * 先 [LlmTextGenerator.release] 再删，理由见类注释（mmap 与磁盘空间）。
     * 释放失败**不阻止删除**：那说明引擎本来就没加载，而「用户想删掉这 345 MB」
     * 是他明确表达过的意图——留着删不掉比多占一会儿空间更糟。
     */
    fun remove() {
        stopInstall()
        val model = _state.value.model
        viewModelScope.launch {
            runCatching { generator.release() }
                .onFailure { MspLog.w(TAG, it) { "释放本地推理引擎失败，仍然继续删除模型" } }

            val removed = installer.remove(model)
            if (!removed) MspLog.i(TAG) { "本地模型 ${model.id} 的文件本来就不在" }
            _state.update {
                it.copy(
                    statuses = readStatuses(),
                    message = LocalModelMessage(
                        text = if (removed) {
                            MspText.Res(R.string.msp_settings_local_model_removed, model.name)
                        } else {
                            // 界面上明明写着「已下载」，点删除却说没什么可删的：这句话
                            // 就是来解释这个矛盾的（多半是另一个进程/应用先把文件清掉了）。
                            MspText.Res(R.string.msp_settings_local_model_remove_missing)
                        },
                        failed = false,
                    ),
                )
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun stopInstall() {
        installJob?.cancel()
        installJob = null
        _state.update { it.copy(installing = false, progress = null) }
    }

    /**
     * 读一次磁盘状态。走 IO 调度器：`File.isFile` / `length()` 是系统调用，
     * 在模型文件缺失时（目录遍历）更慢，别让它占着主线程。
     */
    private suspend fun readStatuses(): Map<String, LlmModelStatus> =
        withContext(dispatchers.io) {
            LlmModelCatalog.models.associate { it.id to models.statusOf(it) }
        }
}
