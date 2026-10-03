package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrServices
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "AsrSettings"

/**
 * 语音识别偏好的读写。
 *
 * 与其余设置仓库共用 `msp_settings` 这一个文件——见 [mspSettingsStore] 的说明，
 * 这里**绝不能**再写一遍 `preferencesDataStore`（同一个文件被两处声明会直接崩）。
 */
class AsrSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败只吞 [IOException]（文件被删、磁盘读不出来）并回退到默认值。
     * 其他异常照旧抛出——否则真正的 bug 会伪装成「我选的模型莫名其妙变回去了」。
     */
    val settings: Flow<AsrSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取语音识别偏好失败，回退到默认模型" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toAsrSettings() }

    /**
     * 记住用户选的模型。
     *
     * 存的是**清单里那条模型的 id**（先用 [AsrModelCatalog.byId] 解析一遍），
     * 不是用户点进来的那个字符串：界面上传来的是列表项的 id，理论上一定有效，
     * 但万一将来列表改了名字，存进去一个不存在的 id 会静默回落到默认模型，
     * 表现为「我明明选了中英双语，重启又变回中文离线了」——一个查不出原因的 bug。
     */
    suspend fun setModelId(id: String) = edit { prefs ->
        prefs[Keys.MODEL_ID] = AsrModelCatalog.byId(id).id
    }

    /**
     * 记住下载源。**空白 = 恢复默认**（删掉这个键）。
     *
     * 「填空白」必须是一种能表达的操作：用户把地址删干净、保存，期望是回到默认镜像站。
     * 如果不删键而是存一个空字符串，那么下次启动读出来的是空串，界面显示一个空输入框，
     * 而实际请求会走默认值——界面和实际行为不一致，是排不出来的「地址明明空着却在下载」。
     */
    suspend fun setBaseUrl(raw: String) = edit { prefs ->
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            prefs.remove(Keys.BASE_URL)
        } else {
            prefs[Keys.BASE_URL] = normalized
        }
    }

    /**
     * 记住识别路线（本机 / 云端）。
     *
     * 收枚举而不是字符串：这是**唯一**一个由界面两个选项直接决定的设置，没有
     * 「用户手打」这条路，所以非法值根本不该能构造出来。存的是 [AsrRoute.id]，
     * 不是枚举序号——见 [Keys] 的说明。
     */
    suspend fun setRoute(route: AsrRoute) = edit { prefs ->
        prefs[Keys.ROUTE] = route.id
    }

    /**
     * 记住云端服务商预设。
     *
     * 与 [setModelId] 同理，存的是**清单里那个 id**（先过一遍 [AsrServices.byId]），
     * 而不是界面传上来的字符串。区别在于 [AsrServices.byId] 认不出来时回落到「自定义」
     * 而不是默认服务商——所以万一将来预设改了名，用户界面上会变成「自定义 + 空地址」
     * （一眼看得出要去填），而不是「地址还在但请求发去了别家」。
     *
     * ## 换家时要把上一家的地址与模型一并清掉
     *
     * 不清的话，用户从 OpenAI 切到 Groq、地址还指着 `api.openai.com`，就是一个
     * 「设置看起来生效了、实际在问上一家（还带着上一家的密钥）」的状态。
     *
     * 清成**删键**而不是把新预设的值写死进盘（翻译那边是后者，见
     * `TranslationSettingsRepository.setProvider`）：这里预设地址是**可能被修正的事实**
     * （Groq 的 base 真的带一段 `/openai`、硅基流动的主机真的 `.com`），写成键之后就永久
     * 留在了用户设备上，我们改对了数据他也不会变。删键的语义是「以后跟着预设走」，
     * 正好是 [AsrSettings.cloudBaseUrl] 已经定义好的那套。
     *
     * ## 重选同一家不能清
     *
     * 选单里点已经选中那一行也会走到这里。如果无条件清，用户刚填好的自建地址会在
     * 「又点了一下同一家」之后消失——而这个动作看起来什么都不该发生。所以先比 id。
     */
    suspend fun setCloudServiceId(id: String) {
        val preset = AsrServices.byId(id)
        val changed = settings.first().cloudService.id != preset.id
        edit { prefs ->
            prefs[Keys.CLOUD_SERVICE_ID] = preset.id
            if (changed) {
                prefs.remove(Keys.CLOUD_BASE_URL)
                prefs.remove(Keys.CLOUD_MODEL)
            }
        }
    }

    /** 记住云端地址。**空白 = 用预设自带的**（删掉这个键），与 [setBaseUrl] 同一套语义。 */
    suspend fun setCloudBaseUrl(raw: String) = edit { prefs ->
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            prefs.remove(Keys.CLOUD_BASE_URL)
        } else {
            prefs[Keys.CLOUD_BASE_URL] = normalized
        }
    }

    /**
     * 记住云端模型名。**空白 = 用预设自带的**（删掉这个键）。
     *
     * 注意这里**不能**归一化成预设自带的模型名再存：用户清空这一栏的期望是
     * 「以后跟着预设走」，而不是「把预设当前的名字刻在盘上」。
     */
    suspend fun setCloudModel(raw: String) = edit { prefs ->
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            prefs.remove(Keys.CLOUD_MODEL)
        } else {
            prefs[Keys.CLOUD_MODEL] = normalized
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    /**
     * 持久化键名。
     *
     * ⚠️ **这是写入用户设备的契约，改名等于把所有人的选择清空**（旧文件里的键读不出来，
     * 会静静地回落到默认模型）。所以按字面量在测试里锁住。
     *
     * 存的是模型 **id 字符串**而不是序号：往清单里插一条模型、或者在设置页换一下顺序，
     * 序号就会整体错位，用户选的「中英双语」会变成「中文离线」——而屏幕上只是
     * 「识别结果好像变差了」，没人会联想到版本升级。
     */
    internal object Keys {
        val MODEL_ID = stringPreferencesKey("asr.model_id")

        val BASE_URL = stringPreferencesKey("asr.base_url")

        val ROUTE = stringPreferencesKey("asr.route")

        val CLOUD_SERVICE_ID = stringPreferencesKey("asr.cloud_service_id")

        val CLOUD_BASE_URL = stringPreferencesKey("asr.cloud_base_url")

        val CLOUD_MODEL = stringPreferencesKey("asr.cloud_model")
    }
}

/**
 * 存储结构 → 领域模型。
 *
 * 抽成顶层函数是为了可测：仓库的构造需要 `Context` 和真实的 DataStore 文件，
 * 而「缺键怎么办」「存了一个不存在的模型 id 怎么办」恰恰是最容易写错的地方。
 */
internal fun Preferences.toAsrSettings(): AsrSettings {
    val stored = AsrSettings(
        storedModelId = this[AsrSettingsRepository.Keys.MODEL_ID],
        storedBaseUrl = this[AsrSettingsRepository.Keys.BASE_URL],
        storedRouteId = this[AsrSettingsRepository.Keys.ROUTE],
        storedCloudServiceId = this[AsrSettingsRepository.Keys.CLOUD_SERVICE_ID],
        storedCloudBaseUrl = this[AsrSettingsRepository.Keys.CLOUD_BASE_URL],
        storedCloudModel = this[AsrSettingsRepository.Keys.CLOUD_MODEL],
    )

    // 密钥的 owner id 由 `cloudApiKeyOwner` 算出来（`asr-` 那段前缀只写在那一处），
    // 在这里重写一遍前缀就会在日后改前缀时静默读到旧的键——表现是「密钥明明填了,
    // 设置页却说没填」（或者相反，识别时拿着 null 报 401）。所以先构造再补这一步。
    return stored.copy(
        cloudApiKeyStored = this[apiKeyPreferenceKey(stored.cloudApiKeyOwner)] != null,
    )
}
