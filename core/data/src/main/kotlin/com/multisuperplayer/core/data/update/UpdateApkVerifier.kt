package com.multisuperplayer.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.multisuperplayer.core.common.log.MspLog
import java.io.File
import java.security.MessageDigest

/** 一个 APK 被拒的原因。分开成枚举而不是一句话，是为了让界面能给出不同的下一步。 */
enum class ApkRejection {
    /** 系统根本读不出这个包：文件坏了、不是 APK、或者被截断了。 */
    NOT_AN_APK,

    /** 包名不是本应用。 */
    PACKAGE_MISMATCH,

    /** 包名对，但签名对不上。 */
    SIGNATURE_MISMATCH,
}

sealed interface ApkVerification {
    data object Accepted : ApkVerification
    data class Rejected(val reason: ApkRejection, val detail: String) : ApkVerification
}

/**
 * 安装之前的最后一道关：**这个 APK 能不能覆盖安装本应用**。
 *
 * ## 为什么这是安全边界，而不是「顺手做的检查」
 *
 * 下载地址是网络内容，摘要也是网络内容——如果哪天 GitHub 账号被拿走，
 * 攻击者可以同时改 APK 和它旁边的 sha256，摘要校验会「通过」。
 * 唯一无法伪造的是**签名**：Android 只允许签名相同的 APK 覆盖安装，
 * 而私钥不在发布通道里。所以校验签名不是「多加一道保险」，
 * 它是整条链路上唯一真正不可绕过的那一步——摘要只防传输损坏，签名防替换。
 *
 * 提前校验（而不是直接丢给系统安装器）的价值在于**用户看到的话**：
 * 系统会在按完安装之后报一句 `应用未安装`，用户完全无法判断是被换了包、
 * 还是签名不同、还是存储不够。在这里拒绝，我们能说清是哪一条。
 *
 * ## 为什么包名也要显式比
 *
 * 签名相同但包名不同是可能的（同一个开发者签了另一个应用），此时系统会**装成
 * 另一个应用**，而且装得上、不报错——用户以为升级完成了，实际上多了一个
 * 不知道从哪来的应用，本应用还是旧版。
 */
class UpdateApkVerifier(context: Context) {

    private val appContext = context.applicationContext

    fun verify(apk: File): ApkVerification {
        if (!apk.exists() || apk.length() == 0L) {
            return ApkVerification.Rejected(ApkRejection.NOT_AN_APK, "文件不存在或为空")
        }

        val packageManager = appContext.packageManager
        val info = packageManager.getArchiveInfo(apk)
            ?: return ApkVerification.Rejected(ApkRejection.NOT_AN_APK, "系统读不出这个安装包")

        val expectedPackage = appContext.packageName
        val actualPackage = info.packageName.orEmpty()
        if (actualPackage != expectedPackage) {
            MspLog.w(TAG) { "拒绝安装：包名 $actualPackage ≠ $expectedPackage" }
            return ApkVerification.Rejected(ApkRejection.PACKAGE_MISMATCH, actualPackage)
        }

        val expected = packageManager.signatureDigestsOf(expectedPackage)
        val actual = info.signatureDigests()
        if (expected.isEmpty()) {
            // 读不到当前应用的签名时**不能**放行。这个分支在正常情况下不会走到
            // （当前应用的签名一定读得到），能走到说明 PackageManager 给了意料外的答案，
            // 此时放行等于把「校验失败」当成「校验通过」。
            MspLog.w(TAG) { "拒绝安装：读不到当前应用的签名，无法比对" }
            return ApkVerification.Rejected(ApkRejection.SIGNATURE_MISMATCH, "no-local-signature")
        }
        if (actual.isEmpty()) {
            MspLog.w(TAG) { "拒绝安装：读不到安装包的签名" }
            return ApkVerification.Rejected(ApkRejection.SIGNATURE_MISMATCH, "no-apk-signature")
        }
        if (expected != actual) {
            // 日志里打摘要的前 16 位：既够比对，又不会把完整证书串进日志。
            MspLog.w(TAG) {
                "拒绝安装：签名不符（本机 ${expected.first().take(16)}… / 包内 ${actual.first().take(16)}…）"
            }
            return ApkVerification.Rejected(ApkRejection.SIGNATURE_MISMATCH, actual.first().take(16))
        }

        return ApkVerification.Accepted
    }

    private companion object {
        const val TAG = "UpdateApkVerifier"
    }
}

/**
 * `getPackageArchiveInfo` 的 flag 随版本走。
 *
 * API 28 起必须用 `GET_SIGNING_CERTIFICATES`：`GET_SIGNATURES` 返回的
 * `PackageInfo.signatures` 在那个版本之后可能为空（尤其是走 v3 签名轮换的包），
 * 于是校验会得到「读不到签名」并拒绝——那是一个**看起来像安全告警的假失败**。
 */
private fun PackageManager.getArchiveInfo(apk: File): PackageInfo? {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
    return getPackageArchiveInfo(apk.absolutePath, flags)
}

/** 已安装应用自己的签名摘要集合。 */
private fun PackageManager.signatureDigestsOf(packageName: String): Set<String> {
    val info = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, signatureFlags())
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
    return info?.signatureDigests().orEmpty()
}

private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES
} else {
    @Suppress("DEPRECATION")
    PackageManager.GET_SIGNATURES
}

/**
 * 取签名证书的 SHA-256 集合。
 *
 * 用 `apkContentsSigners`（也就是**当前**签名证书）而不是 `signingCertificateHistory`：
 * 后者在做过密钥轮换的包上会包含历史证书，于是「本机应用的完整历史」和
 * 「一个全新下载的包的完整历史」长度可能不同——比出来是「签名不符」，
 * 而两边的签名其实都合法。
 */
private fun PackageInfo.signatureDigests(): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    // `apkContentsSigners` 在多签名者与单签名者两种情况下都返回当前的实际签名证书，
    // 所以不需要按 `hasMultipleSigners()` 分支——分出来的两支会是一模一样的代码。
    (signingInfo?.apkContentsSigners ?: emptyArray()).map { it.toDigest() }.toSet()
} else {
    @Suppress("DEPRECATION")
    (signatures ?: emptyArray()).map {
        it.toDigest()
    }.toSet()
}

private fun android.content.pm.Signature.toDigest(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()
