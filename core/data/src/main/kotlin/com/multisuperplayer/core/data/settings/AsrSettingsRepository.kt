package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    }
}

/**
 * 存储结构 → 领域模型。
 *
 * 抽成顶层函数是为了可测：仓库的构造需要 `Context` 和真实的 DataStore 文件，
 * 而「缺键怎么办」「存了一个不存在的模型 id 怎么办」恰恰是最容易写错的地方。
 */
internal fun Preferences.toAsrSettings(): AsrSettings = AsrSettings(
    storedModelId = this[AsrSettingsRepository.Keys.MODEL_ID],
    storedBaseUrl = this[AsrSettingsRepository.Keys.BASE_URL],
)
