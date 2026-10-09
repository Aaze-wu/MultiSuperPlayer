package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelInstaller
import com.multisuperplayer.core.asr.AsrModelLocator
import com.multisuperplayer.core.asr.AsrModelProgress
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrServices
import com.multisuperplayer.core.asr.describeAsrFailure
import com.multisuperplayer.core.asr.transcriptionsUrl
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.settings.ApiKeyStore
import com.multisuperplayer.core.data.settings.AsrSettings
import com.multisuperplayer.core.data.settings.AsrSettingsRepository
import com.multisuperplayer.core.data.subtitle.GeneratedSubtitleCacheStats
import com.multisuperplayer.core.data.subtitle.GeneratedSubtitleStore
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
 * 「清除生成的字幕」那一行的状态。
 *
 * 三个字段合成一个对象，理由和翻译页的 `TranslationCacheState` 一样：拆成两个
 * `StateFlow` 会在清空完成的那几帧里拼出「还剩 3 份」+「已清空」这种自相矛盾的组合。
 *
 * [failed] 只在**删除失败**时为 true。读盘失败不算：那时 [stats] 是上一次读到的值，
 * 而「清空失败」是一句针对用户刚才那一下动作的话，不能拿另一种原因去填。
 */
data class GeneratedSubtitleCacheState(
    val stats: GeneratedSubtitleCacheStats = GeneratedSubtitleCacheStats(entries = 0, bytes = 0L),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

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
    val cache: GeneratedSubtitleCacheState = GeneratedSubtitleCacheState(),
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

    /** 走云端时那栏地址能不能拿去发请求（空 = 还没填）。见 [AsrSettings.cloudAddressLooksValid]。 */
    val cloudAddressLooksValid: Boolean get() = settings.cloudAddressLooksValid

    /**
     * 云端那一栏地址里**实际要请求**的完整端点。
     *
     * 直接拿 `transcriptionsUrl` 算（与 `CloudAsrClient` 用的是同一个函数），不在这里
     * 再拼一次：多一份拼接就会多一份不一致，而地址错一位的后果是 404，
     * 而 404 在界面上和「模型名写错了」长得一模一样。
     */
    val cloudEndpoint: String get() = transcriptionsUrl(settings.cloudBaseUrl)
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
 *
 * ## 两条路各自要填的东西不一样
 *
 * 「本机」要选模型 + 下载源（一个磁盘和流量的选择），「云端」要选服务商 + 地址 +
 * 模型名 + 密钥（一个「把东西发给谁」的选择）。两种设置集合并排摆在一页上会让用户
 * 看到一半与自己无关的项，所以界面按 [AsrRoute] 分叉（见 [AsrSettingsScreen]），
 * 这里只负责两边的读写。
 *
 * ## 密钥为什么不经过 `AsrSettingsRepository`
 *
 * 密钥不是一项设置，而是一份凭证：它存在 `ApiKeyStore` 里（Keystore 加密），
 * 与设置分开。owner id 由 [AsrSettings.cloudApiKeyOwner] 算出来——它是**唯一**一处
 * 定义那个前缀的地方，这里只引用不重写；而「存了没有」这个布尔值由设置流程带出来
 * （[AsrSettings.cloudApiKeyStored]），所以界面看到的与实际要用的必然是同一把。
 */
class AsrSettingsViewModel(
    private val asrSettings: AsrSettingsRepository,
    private val asrModels: AsrModelLocator,
    private val asrInstaller: AsrModelInstaller,
    private val apiKeys: ApiKeyStore,
    private val generatedSubtitles: GeneratedSubtitleStore,
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
        // 生成的字幕占的地方必须是**首帧就对**的：下面那个 [refresh] 只挂在
        // `ON_RESUME` 上，而同一个 Activity 内部的页面切换不会让它重新 resume——
        // 只靠那条路的话，这一行会一直显示成「还没有缓存」（而它是错的）。
        viewModelScope.launch { refreshCacheStats() }
    }

    /**
     * 重新读一遍磁盘状态。
     *
     * 界面在外层 `ON_RESUME` 时调：模型也可能是在**播放页的面板**里下完的，
     * 那个动作发生在这个 ViewModel 之外，没有任何回调会通知这里。
     *
     * 生成的字幕同理（它是在播放页里跑出来的），所以两者在同一个时机一起读：
     * 做成两个 `refresh` 只会让界面漏调一个。
     */
    fun refresh() {
        viewModelScope.launch {
            val statuses = readStatuses()
            _state.update { it.copy(statuses = statuses) }
            refreshCacheStats()
        }
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

    /**
     * 换识别路线。
     *
     * 不做任何「发现当前路线不可用就帮你换一条」的事：两条路都能成功、代价不同，
     * 这是用户的选择（见 [AsrRoute] 的 KDoc）。界面上选云端后紧跟一段说明，
     * 把「会有什么代价」说在明处，而不是替他决定。
     */
    fun selectRoute(route: AsrRoute) {
        if (route == _state.value.settings.route) return
        viewModelScope.launch { asrSettings.setRoute(route) }
    }

    /**
     * 换服务商。
     *
     * 界面传上来的是预设列表里的 id，仓库会再过一遍 [AsrServices.byId] 并（在真的换了
     * 一家时）清掉上一家的地址与模型——这里再判一次「是不是同一家」只是为了让
     * 「点中已选中的那一行」变成一个无损动作。
     */
    fun selectCloudService(id: String) {
        if (AsrServices.byId(id).id == _state.value.settings.cloudService.id) return
        viewModelScope.launch { asrSettings.setCloudServiceId(id) }
    }

    /** 改云端地址。**空白 = 回到预设自带的**（与下载源同一套语义）。 */
    fun setCloudBaseUrl(raw: String) {
        viewModelScope.launch { asrSettings.setCloudBaseUrl(raw) }
    }

    /** 改云端模型名。**空白 = 回到预设自带的**。 */
    fun setCloudModel(raw: String) {
        viewModelScope.launch { asrSettings.setCloudModel(raw) }
    }

    /**
     * 保存云端密钥。
     *
     * 空输入**不调**仓库（[ApiKeyStore.put] 对空值的语义就是「不动」），也不会破坏
     * 已有密钥：要删密钥只有下面那个显式动作。
     *
     * `onDone` 回到主线程才调：那一头写的是 Compose 状态（见密钥那一块里那条提示），
     * 在 IO 线程上写一次就会随机崩在「保存密钥」这个看起来完全无关的动作上。
     */
    fun saveCloudApiKey(raw: String, onDone: (Boolean) -> Unit = {}) {
        val owner = _state.value.settings.cloudApiKeyOwner
        viewModelScope.launch {
            val stored = withContext(dispatchers.io) {
                runCatching { apiKeys.put(owner, raw) }
                    .onFailure { error -> MspLog.w(TAG, error) { "保存云端识别密钥失败" } }
                    .getOrDefault(false)
            }
            onDone(stored)
        }
    }

    /** 删除云端密钥。界面上必须是一个独立的、写明后果的按钮。 */
    fun clearCloudApiKey() {
        val owner = _state.value.settings.cloudApiKeyOwner
        viewModelScope.launch {
            withContext(dispatchers.io) { apiKeys.clear(owner) }
        }
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

    /**
     * 清空生成的字幕（设置页里那一个按钮的全部实现）。
     *
     * 重入保护：清空期间按钮照样可以点（`SettingActionButtonRow` 没有禁用态），
     * 第二次点会去删一个正在被删的东西——删不掉就报「清空失败」，而第一次其实是成功的。
     *
     * 规模**读回来**而不是自己拼：删干净了没有只有磁盘知道，所以清完再 stat 一次。
     * 返回 false 时那个数字是有意义的（还剩几份），不能显示成一句笼统的失败——
     * 用户唯一能从界面上得到的结论是「这个按钮是假的」。
     *
     * 不动模型文件：这一页上两个删除动作的语义完全不同（一个是「把这些算出来的东西
     * 还给磁盘」，一个是「把 190 MB 重新下回来的准备」），不能共用一个入口。
     */
    fun clearGeneratedSubtitleCache() {
        viewModelScope.launch {
            if (_state.value.cache.busy) return@launch
            _state.update { it.copy(cache = it.cache.copy(busy = true, failed = false)) }
            val removed = withContext(dispatchers.io) { generatedSubtitles.clear() }
            val stats = withContext(dispatchers.io) { generatedSubtitles.stats() }
            MspLog.i(TAG) { "清空生成的字幕：removed=$removed，剩下 ${stats.entries} 份" }
            _state.update {
                it.copy(
                    cache = GeneratedSubtitleCacheState(
                        stats = stats,
                        busy = false,
                        failed = !removed,
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

    /**
     * 重读「生成的字幕占了多少地方」。
     *
     * 走 IO 线程：`GeneratedSubtitleStore.stats()` 会列一次目录并逐个 stat，
     * 而它在主线程上被调用的后果很直接——打开这一页时掉帧。
     *
     * 顺手把 `failed` 清掉：这是**重新读出来的一份事实**，上一次的删除失败
     * 已经不代表现在的状态了（和 `SettingsViewModel.refreshCacheStats` 一致）。
     */
    private suspend fun refreshCacheStats() {
        val stats = withContext(dispatchers.io) { generatedSubtitles.stats() }
        _state.update { it.copy(cache = it.cache.copy(stats = stats, failed = false)) }
    }
}
