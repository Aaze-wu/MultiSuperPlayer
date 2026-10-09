package com.multisuperplayer.core.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.settings.ApiKeyCiphertext
import com.multisuperplayer.core.data.settings.mspSettingsStore
import com.multisuperplayer.core.data.settings.normalizeApiKeyInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 「检查更新」用到的全部持久化设置。
 *
 * [channel] / [autoCheck] / [ignoredTag] / [checkIntervalMs] 是**状态**，
 * [lastCheckAtEpochMs] 是**节流的依据**，[hasToken] 是**有没有填令牌**。
 *
 * [hasToken] 放在这里而不是「要不要把令牌解密出来」：设置页只需要说「已设置」，
 * 每次读设置都去解一次密（要访问 Keystore，在低端机上要几十毫秒）毫无必要，
 * 而且解密失败时会打一行警告日志——那行日志会变成「每次进设置页都刷一条」。
 */
data class UpdateSettings(
    val channel: UpdateChannel,
    val autoCheck: Boolean,
    /** 用户点过「忽略这一版」的那个 tag。null = 没有忽略任何版本。 */
    val ignoredTag: String?,
    val lastCheckAtEpochMs: Long?,
    val hasToken: Boolean,
    /**
     * 自动检查的冷却窗口（毫秒）。
     *
     * 放在 `autoCheck` 旁边而不是替掉它：开关是总闸（要不要自动查），这个是节流
     * （自动查的话，两次之间至少隔多久）。两件事合成一个控件之后，「关掉」会和
     * 「设成一星期」变成同一个状态，而它们下一次要做的动作完全相反。
     *
     * 读到越界值（旧版本写的、被外部改坏的偏好文件）在**读出来的那一刻**就被
     * [UpdateCooldown.normalize] 夹回合法区间：消费方（[UpdateRules.skipsAutoCheck]）
     * 因此可以假定它已经是合法值，不用在每个比大小的地方各防一次。
     */
    val checkIntervalMs: Long,
)

/**
 * 更新相关的设置，共用 `msp_settings` 这一个 DataStore 文件。
 *
 * **不要**在这里新建 `preferencesDataStore`：同一个 name 建第二个实例会让
 * DataStore 直接抛 `There are multiple DataStores active for the same file`
 * （理由写在 `MspSettingsStore` 的注释里）。所以这里只 define 自己的 key。
 *
 * ## 令牌为什么不放 [com.multisuperplayer.core.data.settings.ApiKeyStore]
 *
 * 走得通（那个类的 providerId 是任意字符串），但它的 key 前缀写死成
 * `translation.api_key.<providerId>`，用它存令牌会得到
 * `translation.api_key.github_update` 这样一个名字——半年后有人来清理设置项时，
 * 从名字上完全看不出它属于「应用内更新」。所以这里单独定义 key，
 * 只复用加解密那一层（[ApiKeyCiphertext]）。
 *
 * @param defaultChannel 用户还没选过通道时用的那一个。由调用方（app 层）按
 *   **当前安装的是不是预发行版**算好传进来——`core:data` 读不到 `BuildConfig`，
 *   在这里硬编一个默认值就是替调用方做决定。
 */
class UpdateSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
    private val defaultChannel: UpdateChannel,
) {

    private val appContext = context.applicationContext

    /**
     * 还没读到 DataStore 时界面该拿什么开局。
     *
     * 存在的理由很实际：`store.data` 的第一个值要等一次磁盘读取，界面若用 `null`
     * 开局，开关会先画成关、再跳成开——用户看到的是「我的设置被重置了」。
     * 默认值的来源必须和 [settings] 里 `?:` 的兜底**完全一致**，所以它只写在这里。
     */
    val defaultSettings: UpdateSettings = UpdateSettings(
        channel = defaultChannel,
        autoCheck = DEFAULT_AUTO_CHECK,
        ignoredTag = null,
        lastCheckAtEpochMs = null,
        hasToken = false,
        checkIntervalMs = UpdateCooldown.DEFAULT_MS,
    )

    // 与其它设置共用一个文件。每次读都经过这里，保证「只有一个 store」。
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    val settings: Flow<UpdateSettings> = store.data
        .map { prefs ->
            UpdateSettings(
                channel = prefs[KEY_CHANNEL].toChannelOrNull() ?: defaultChannel,
                autoCheck = prefs[KEY_AUTO_CHECK] ?: DEFAULT_AUTO_CHECK,
                ignoredTag = prefs[KEY_IGNORED_TAG]?.takeIf { it.isNotBlank() },
                lastCheckAtEpochMs = prefs[KEY_LAST_CHECK]?.takeIf { it > 0 },
                hasToken = prefs[KEY_TOKEN]?.isNotBlank() == true,
                //「没存过」和「存了个坏的」走同一条路（都退回默认），
                // 夹在读出这一刻，调用方就不用每个比大小的地方各防一次。
                checkIntervalMs = UpdateCooldown.normalize(prefs[KEY_CHECK_INTERVAL]),
            )
        }
        .flowOn(dispatchers.io)

    suspend fun setChannel(channel: UpdateChannel) {
        withContext(dispatchers.io) { store.edit { it[KEY_CHANNEL] = channel.name } }
    }

    suspend fun setAutoCheck(enabled: Boolean) {
        withContext(dispatchers.io) { store.edit { it[KEY_AUTO_CHECK] = enabled } }
    }

    /**
     * 写入冷却窗口。
     *
     * 先过 [UpdateCooldown.normalize] 再落盘：存储里因此永远只有一个合法值，
     * 而「夹一次」比「在每个读它的地方都证明自己拿到了合法值」便宜得多。
     * 非法输入退回默认（而不是夹到最近的一档）：把空值/坏值当成「用户要求最短
     * 窗口」，会静默地把应用变成替用户频繁请求，而界面上完全看不出来。
     */
    suspend fun setCheckInterval(intervalMs: Long) {
        val normalized = UpdateCooldown.normalize(intervalMs)
        withContext(dispatchers.io) { store.edit { it[KEY_CHECK_INTERVAL] = normalized } }
    }

    /**
     * 记住「这一版我跳过了」。[tag] 为 null 表示**撤销**忽略。
     *
     * `remove` 而不是写一个空串：读的那一侧要把空串当成「没有忽略」，
     * 于是存储里存在两种都表示「没有」的值，而它们只有一个是真值。
     */
    suspend fun setIgnoredTag(tag: String?) {
        withContext(dispatchers.io) {
            store.edit { prefs ->
                if (tag.isNullOrBlank()) prefs.remove(KEY_IGNORED_TAG) else prefs[KEY_IGNORED_TAG] = tag
            }
        }
    }

    /**
     * 记下这次检查的时刻。
     *
     * 无论成功还是失败都要写：节流要挡的是「同一分钟内点十次检查更新」，
     * 而失败之后狂点是最常见的反应。只在成功时写，节流就挡不住这种情况。
     */
    suspend fun markChecked(atEpochMs: Long) {
        withContext(dispatchers.io) { store.edit { it[KEY_LAST_CHECK] = atEpochMs } }
    }

    /** 读出明文令牌；没填过或解不开都返回 null。 */
    suspend fun currentToken(): String? {
        val raw = withContext(dispatchers.io) { store.data.first()[KEY_TOKEN] } ?: return null
        val plain = ApiKeyCiphertext.decrypt(raw)
        if (plain == null) {
            // 解不开（换机、Keystore 被重置）时按「没填」处理：未认证也能用，只是额度低。
            // 日志是必须的——否则这个状态和「从没填过」在界面上完全一样，无从排查。
            MspLog.w(TAG) { "更新令牌解密失败（Keystore 可能已被重置），按未设置处理" }
        }
        return plain
    }

    /**
     * 保存令牌。
     *
     * @return true = 已写入；false = 输入是空白（或加密失败），**没有动旧值**。
     *
     * 与 `ApiKeyStore.put` 同一条规矩：设置页里那个输入框平时是空的（只显示「已设置」），
     * 所以「空输入」代表用户没动这一栏，而不是「我要删掉它」。删除必须走 [clearToken]。
     * 把空串当删除的后果是：用户点一次保存，令牌就没了，而界面上看起来一切正常——
     * 下一次检查更新被限流时才会发现问题，而且看起来像是 GitHub 的错。
     */
    suspend fun putToken(input: String): Boolean {
        val token = normalizeApiKeyInput(input)
        if (token == null) {
            MspLog.d(TAG) { "令牌输入为空，按「未修改」处理（清除请调用 clearToken）" }
            return false
        }
        val encrypted = ApiKeyCiphertext.encrypt(token)
        if (encrypted == null) {
            // 加密路径失败时**不能**退回明文存储：那等于静默降低安全等级。
            MspLog.w(TAG) { "令牌加密失败，未保存" }
            return false
        }
        withContext(dispatchers.io) { store.edit { it[KEY_TOKEN] = encrypted } }
        return true
    }

    /** 删除令牌。这是唯一的删除入口，必须由用户的明确动作触发。 */
    suspend fun clearToken() {
        withContext(dispatchers.io) { store.edit { it.remove(KEY_TOKEN) } }
    }

    private companion object {
        const val TAG = "UpdateSettings"

        /** 默认开着：更新系统不开，用户就永远得自己去商店看。 */
        const val DEFAULT_AUTO_CHECK = true

        val KEY_CHANNEL = stringPreferencesKey("update.channel")
        val KEY_AUTO_CHECK = booleanPreferencesKey("update.auto_check")
        val KEY_IGNORED_TAG = stringPreferencesKey("update.ignored_tag")
        val KEY_LAST_CHECK = longPreferencesKey("update.last_check_at")
        val KEY_TOKEN = stringPreferencesKey("update.github_token")

        /**
         * 冷却窗口。「没存过」与「旧的 12 小时」因此是同一个状态：默认值就是 12 小时，
         * 升级上来的用户不会因为多了这个开关而改变查更新的频率。
         */
        val KEY_CHECK_INTERVAL = longPreferencesKey("update.check_interval_ms")
    }
}

/**
 * 通道名 → 枚举。
 *
 * 用「按名字找」而不是 `valueOf`：那个会抛 `IllegalArgumentException`，
 * 于是某天有人把枚举项改名之后，**所有已经装了旧版的应用**在下次启动读设置时崩掉。
 * 读不出来就退回默认通道，那是一个用户能自己改回来的状态。
 *
 * 但「退回默认」对改过名的那一档不够：`0.8.1` 及以前叫 `PRERELEASE`（正式版 + 预发行版），
 * 现在叫 `BETA`。已装了预发行版的用户退回默认恰好也是 `BETA`，看不出问题；
 * 可一个**手动**把通道改成「预发行版」的正式版用户会被退回「只收正式版」——
 * 他下次再也不会收到测试版，而界面上显示的是他自己选过的那个选项。
 * 所以旧名字要显式翻译一次，而不是靠默认值兜住。
 */
internal fun String?.toChannelOrNull(): UpdateChannel? = when (this) {
    null -> null
    LEGACY_PRERELEASE_CHANNEL -> UpdateChannel.BETA
    else -> UpdateChannel.entries.firstOrNull { it.name == this }
}

/**
 * `0.8.1` 及以前对「正式版 + 预发行版」那一档的枚举名。
 *
 * 只读不写：从今往后存进去的永远是 `BETA`，这个常量只会越来越没用，但删不得——
 * 删掉就会让那批用户的设置在无人告知的情况下变掉，而这是**读**路径上的兼容，
 * 与以后还会不会写这个值无关。
 */
private const val LEGACY_PRERELEASE_CHANNEL = "PRERELEASE"
