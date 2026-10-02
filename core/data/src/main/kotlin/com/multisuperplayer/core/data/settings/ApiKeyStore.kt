package com.multisuperplayer.core.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存储。
 *
 * ## 为什么不用 `androidx.security:security-crypto`
 *
 * 那个库已经进入维护状态，而且它自己也在往 AndroidKeyStore 上套一层。我们要做的只有
 * 「用 Keystore 里的 AES 密钥加个密，把密文塞进已有的 DataStore」，几十行的事，
 * 不引入一个不再维护的依赖。
 *
 * ## 它挡住了什么、挡不住什么
 *
 * 挡住：备份/云同步把明文 key 一起带走、`adb backup`、开发者用文件管理器翻文件、
 * 崩溃日志里被顺手 `toString()` 出去。
 *
 * **挡不住**：拿到 root 并且能让本应用运行的攻击者——他可以反过来调 Keystore 解密。
 * 这一点写在这里，是为了不让人误以为它等于「key 绝对安全」。
 *
 * ## 「留空」不等于「删除」
 *
 * 这是这个类里唯一一处必须小心的地方。密钥不会回显给用户（界面上那栏永远是空的，
 * 旁边写着「已设置」），所以「空输入」代表的是**用户没动这一栏**，
 * 而不是「我要删掉它」。如果 [put] 把空串当删除，用户只是点了一次保存，
 * 密钥就没了，而且界面上看起来一切正常——下一次翻译才会 401。
 * 因此：空输入 ⇒ 什么都不做（返回 false），删除必须显式调 [clear]。
 */
class ApiKeyStore(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    // 与其它设置共用同一个 DataStore：同一个名字调用两次 preferencesDataStore 会直接崩。
    private val store: DataStore<Preferences> get() = appContext.mspSettingsStore

    /**
     * 保存密钥。
     *
     * @return true = 已写入；false = 输入是空白（或加密失败），**没有动存储里的旧值**。
     */
    suspend fun put(providerId: String, apiKey: String): Boolean {
        val key = normalizeApiKeyInput(apiKey)
        if (key == null) {
            MspLog.d(TAG) { "密钥输入为空，按「未修改」处理（清除请调用 clear）" }
            return false
        }

        val encrypted = ApiKeyCiphertext.encrypt(key)
        if (encrypted == null) {
            // 加密路径失败（Keystore 被锁/被重置）时**不能**退回明文存储：
            // 那等于静默地把安全等级降到「明文」。宁可保存失败让用户重试。
            MspLog.w(TAG) { "密钥加密失败，未保存" }
            return false
        }

        write(providerId, encrypted)
        return true
    }

    /** 读回明文密钥；没存过、或解不开（换机/Keystore 被清）都返回 null。 */
    suspend fun get(providerId: String): String? {
        val raw = withContext(dispatchers.io) {
            store.data.first()[apiKeyPreferenceKey(providerId)]
        } ?: return null

        val plain = ApiKeyCiphertext.decrypt(raw)
        if (plain == null) {
            // 解不开时当「未设置」：用户重填一次即可。这里必须留一行日志，
            // 否则这个状态在界面上和「从没设置过」完全一样，无从排查。
            MspLog.w(TAG) { "密钥解密失败（Keystore 可能已被重置），按未设置处理" }
        }
        return plain
    }

    /** 删除密钥。这是唯一的删除入口，必须由用户的明确动作触发。 */
    suspend fun clear(providerId: String) {
        withContext(dispatchers.io) {
            store.edit { it.remove(apiKeyPreferenceKey(providerId)) }
        }
    }

    private suspend fun write(providerId: String, encrypted: String) {
        withContext(dispatchers.io) {
            store.edit { it[apiKeyPreferenceKey(providerId)] = encrypted }
        }
    }

    private companion object {
        const val TAG = "ApiKeyStore"
    }
}

/** 每个服务商一把密钥：换服务商不该要求重新输入上一家的 key。 */
internal fun apiKeyPreferenceKey(providerId: String): Preferences.Key<String> =
    stringPreferencesKey("translation.api_key.$providerId")

/**
 * 输入规范化。
 *
 * 只做 trim：密钥本身允许含 `-` `.` `_` 之类的符号，也可能有用户从文档里
 * 复制时带上的首尾空格/换行。空 ⇒ null，由调用方决定「空」的含义。
 */
internal fun normalizeApiKeyInput(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

/**
 * Keystore 里那把 AES 密钥，加解密都在它上面做。
 *
 * 密钥**不进**这个文件，也不进 DataStore；DataStore 里只有密文。
 */
private object ApiKeyCiphertext {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "msp.translation.api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM 的推荐 IV 长度；Android 默认也给 12，这里显式校验，写死契约。 */
    internal const val IV_LENGTH = 12

    private const val TAG_BITS = 128

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // IV 不保密，但必须和密文一起存：GCM 解密时要用它。
        encodeKeyBlob(cipher.iv, ciphertext)
    }.getOrElse { error ->
        MspLog.w(TAG, error) { "AES/GCM 加密失败" }
        null
    }

    fun decrypt(encoded: String): String? {
        val (iv, ciphertext) = decodeKeyBlob(encoded) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse { error ->
            MspLog.w(TAG, error) { "AES/GCM 解密失败" }
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // 不设 setUserAuthenticationRequired：翻译是后台批量任务，弹指纹会直接把流程卡死。
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val TAG = "ApiKeyCipher"
}

/**
 * 密文的落盘格式：`hex(iv):hex(ciphertext)`。
 *
 * 用十六进制而不是 Base64，是为了整个编解码是纯 Kotlin/纯 JVM 的——
 * `android.util.Base64` 在单元测试里只会返回默认值，那会让下面这几行校验逻辑
 * **测不到**，而它恰好是「数据被截断/被写脏」时唯一能兜住的地方。
 */
internal fun encodeKeyBlob(iv: ByteArray, ciphertext: ByteArray): String =
    "${iv.toHex()}:${ciphertext.toHex()}"

/**
 * 解析密文。任何形式的损坏都返回 null，让调用方当「没设置过」——
 * 密钥用不了这件事**不能**抛异常：它发生在设置页打开、播放器启动这些地方。
 */
internal fun decodeKeyBlob(text: String): Pair<ByteArray, ByteArray>? {
    val parts = text.split(':')
    if (parts.size != 2) return null

    val iv = parts[0].hexToBytes() ?: return null
    val ciphertext = parts[1].hexToBytes() ?: return null

    // IV 长度不对就不可能解开：GCM 的 IV 长度是写死在加密侧的契约，
    // 早一点判定可以避免把「数据坏了」误报成「密钥不对」。
    if (iv.size != ApiKeyCiphertext.IV_LENGTH) return null
    if (ciphertext.isEmpty()) return null

    return iv to ciphertext
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val v = byte.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}

private fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = this[i * 2].digitToIntOrNull(16) ?: return null
        val low = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
