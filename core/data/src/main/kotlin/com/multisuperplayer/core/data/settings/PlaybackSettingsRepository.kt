package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "PlaybackSettings"

/**
 * 播放偏好的读写。
 *
 * 与 [ThemeSettingsRepository] / [SubtitleSettingsRepository] 共用 `msp_settings`
 * 这一个文件——声明在 [mspSettingsStore] 里，那个文件同时说明了为什么这里只能有一份
 * （再写一个 `preferencesDataStore(name = "msp_settings")` 会在启动几秒后抛
 * 「There are multiple DataStores active for the same file」，而且编译期完全看不出来）。
 */
class PlaybackSettingsRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 读取失败不能让界面崩掉，也不能卡在「永远没有值」。
     *
     * 只吞 [IOException]（磁盘读不出来，比如刚恢复出厂），回退到「全空」，
     * 于是所有字段都是 null = 走内核自己的默认值。其它异常照旧抛出：
     * 那些是自己代码写错（比如键类型不对），静默吞掉只会让 bug 更难找。
     */
    val settings: Flow<PlaybackSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                MspLog.w(TAG, error) { "读取播放设置失败，回退到默认值" }
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> prefs.toPlaybackSettings() }

    suspend fun setForceSoftwareDecoding(enabled: Boolean) = edit {
        it[Keys.FORCE_SOFTWARE_DECODING] = enabled
    }

    suspend fun setAspectRatioMode(mode: AspectRatioMode) = edit {
        it[Keys.ASPECT_RATIO_MODE] = mode.id
    }

    suspend fun setSpeed(speed: Float) = edit {
        it[Keys.SPEED] = speed
    }

    suspend fun setRememberPosition(enabled: Boolean) = edit {
        it[Keys.REMEMBER_POSITION] = enabled
    }

    /**
     * 只影响**往后**记不记，已经在库里的记录不论开关都留着——
     * 「关掉历史」如果顺手把历史删了，那这个开关就是一次不可逆的破坏，
     * 而用户点它的时候想的是「别再记了」，不是「把以前的删掉」。
     */
    suspend fun setRecordRecentPlays(enabled: Boolean) = edit {
        it[Keys.RECORD_RECENT_PLAYS] = enabled
    }

    suspend fun setBoostSpeed(speed: Float) = edit {
        it[Keys.BOOST_SPEED] = speed
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) = edit {
        it[Keys.EQUALIZER_ENABLED] = enabled
    }

    /**
     * 存的是 `EqualizerCurve.encode` 出来的字符串。
     *
     * 参数用 String 而不是 `List<EqualizerBandGain>`：数据层因此不必依赖
     * `:core:player` 的那套类型（它已经依赖了，但那是为了别的东西），
     * 更重要的是**编码只有一处**——数据层自己拼一遍字符串，就会在两处
     * 定义同一个格式。
     */
    suspend fun setEqualizerBandGains(encoded: String) = edit {
        it[Keys.EQUALIZER_BAND_GAINS] = encoded
    }

    /**
     * 安全相关的开关，所以只写值、**不做任何“聪明”的联动**：
     * 不在这里顺手清掉别的记录、也不弹提示——写入者只有设置页那一个开关，
     * 多一处分叉就多一处「关掉之后没有真的关掉」的可能。
     */
    suspend fun setTrustUntrustedCertificates(enabled: Boolean) = edit {
        it[Keys.TRUST_UNTRUSTED_CERTIFICATES] = enabled
    }

    /**
     * 播放时屏幕常亮（见 [PlaybackSettings.keepScreenOnWhilePlaying]）。
     *
     * 写的是设置里的**默认值**；播放页那个临时开关不走这条路——它只改这一次会话，
     * 写了反而会让用户下周打开播放器时发现「上次那一下变成默认了」。
     */
    suspend fun setKeepScreenOnWhilePlaying(enabled: Boolean) = edit {
        it[Keys.KEEP_SCREEN_ON_WHILE_PLAYING] = enabled
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { store.edit(block) }
    }

    internal object Keys {
        /**
         * ⚠️ 这是写在**用户设备上的契约**：改名等于把所有用户的这一项设置清空。
         *
         * 前缀 `playback.` 是分组，不是命名空间，不需要和类名一致——
         * 但**需要和已有键不重名**，所以单测里按字面量锁住了它。
         */
        val FORCE_SOFTWARE_DECODING = booleanPreferencesKey("playback.force_software_decoding")

        val ASPECT_RATIO_MODE = stringPreferencesKey("playback.aspect_ratio_mode")

        val SPEED = floatPreferencesKey("playback.speed")

        val REMEMBER_POSITION = booleanPreferencesKey("playback.remember_position")

        /**
         * 和 [REMEMBER_POSITION] 相邻但要分清：那个是「下次接着播」，
         * 这个是「列表里记不记这一条」。两个键分开存，因为用户会想要
         * 「每次都从头播、但看得见看过什么」——共用一个键就永远做不到。
         */
        val RECORD_RECENT_PLAYS = booleanPreferencesKey("playback.record_recent_plays")

        /** 长按画面时的临时倍速（见 [PlaybackSettings.boostSpeed]）。 */
        val BOOST_SPEED = floatPreferencesKey("playback.boost_speed")

        /** 均衡器开关（见 [PlaybackSettings.equalizerEnabled]）。 */
        val EQUALIZER_ENABLED = booleanPreferencesKey("playback.equalizer_enabled")

        /**
         * 均衡器曲线（见 [PlaybackSettings.equalizerBandGains]）。
         *
         * 键名里带 `band_gains` 而不是 `curve`：真正存在磁盘上的是「每个频段的
         * 增益」这个字符串，名字要和内容对得上，将来有人直接翻 DataStore 文件
         * 也看得懂。
         */
        val EQUALIZER_BAND_GAINS = stringPreferencesKey("playback.equalizer_band_gains")

        /**
         * 是否放行不受信任的 https 证书（见 [PlaybackSettings.trustUntrustedCertificates]）。
         *
         * 键名里把 `untrusted` 写全很重要：将来若有人加一个「按域名白名单」的机制，
         * 那是一个**不同**的设置项（更窄、更安全），不能把这个键改造成那个——
         * 改键等于把已经打开了它的用户的选择默默换成另一种语义。
         */
        val TRUST_UNTRUSTED_CERTIFICATES =
            booleanPreferencesKey("playback.trust_untrusted_certificates")

        /**
         * 播放时屏幕常亮（见 [PlaybackSettings.keepScreenOnWhilePlaying]）。
         *
         * 键名里把条件 `while_playing` 写全很重要：不写的话，将来若有人加一个
         * 「完全不让屏幕熄灭」的开关，两个键会看起来是同一件事——它们是两个不同的
         * 设置项（后者的作用范围更宽），不能共用一个键再靠语义区分。
         */
        val KEEP_SCREEN_ON_WHILE_PLAYING =
            booleanPreferencesKey("playback.keep_screen_on_while_playing")
    }
}

/**
 * 从「一堆键值」还原成一个设置对象。
 *
 * 刻意做成顶层函数而不是 `PlaybackSettingsRepository` 的私有方法：
 * 仓库需要一个 `Context` + 一个真实的 DataStore 文件才能构造，在 JVM 单测里
 * 起不来；而这个映射才是真正会写错的那部分（键名写错、默认值写反、类型不对）。
 */
internal fun Preferences.toPlaybackSettings(): PlaybackSettings = PlaybackSettings(
    forceSoftwareDecoding = this[PlaybackSettingsRepository.Keys.FORCE_SOFTWARE_DECODING],
    // 认不出来的字符串会落到 [AspectRatioMode.fromId] 里的 FIT，也就是默认值；
    // 「键不存在」保持 null。两者对消费者等价，但 null 保留了「没设置过」这个信息。
    aspectRatioMode = this[PlaybackSettingsRepository.Keys.ASPECT_RATIO_MODE]
        ?.let(AspectRatioMode::fromId),
    speed = this[PlaybackSettingsRepository.Keys.SPEED],
    rememberPosition = this[PlaybackSettingsRepository.Keys.REMEMBER_POSITION],
    recordRecentPlays = this[PlaybackSettingsRepository.Keys.RECORD_RECENT_PLAYS],
    boostSpeed = this[PlaybackSettingsRepository.Keys.BOOST_SPEED],
    equalizerEnabled = this[PlaybackSettingsRepository.Keys.EQUALIZER_ENABLED],
    // 只读出来，**不解析**：字符串读不动的时候该由上层回落到平直曲线，
    // 而「读不动」的样子是 null 或一段垃圾，两者对上层是一回事。
    equalizerBandGains = this[PlaybackSettingsRepository.Keys.EQUALIZER_BAND_GAINS],
    trustUntrustedCertificates =
        this[PlaybackSettingsRepository.Keys.TRUST_UNTRUSTED_CERTIFICATES],
    keepScreenOnWhilePlaying =
        this[PlaybackSettingsRepository.Keys.KEEP_SCREEN_ON_WHILE_PLAYING],
)
