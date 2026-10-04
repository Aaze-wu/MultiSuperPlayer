package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 错误文案是「用户能不能自己解决问题」的唯一线索，所以要锁住三件事：
 * 不同原因不能共用一套文案、文案里要有可执行的建议、未知错误码不能丢信息。
 *
 * 另一条同样重要的约束是：文案**不能承诺界面上不存在的东西**。0.3 的版本里
 * 「解码器缺失」那句写的是「可以在设置里启用 FFmpeg 软件解码后再试」——
 * 而当时根本没有那个设置项。现在软件解码是默认自动回退的，这个错误反而是
 * 「软解也试过了」的意思，所以文案必须跟着改（否则会把用户支去一个
 * 又一个不存在的开关）。
 *
 * ⚠️ 自从 [PlaybackErrorMapper] 改为返回 [MspText] 之后，**断言的对象从字符串
 * 变成了「哪一条 + 什么参数」**。这不是偷懒：文案本身进了 `strings.xml`，
 * 会随语言和译审改字，靠中文子串断言的测试会在第一次翻译时全线崩掉，而它
 * 想守住的其实只是「这个错误码走的是这一支」。语言相关的质量由资源文件
 * 自己的测试守（见本文件末尾两条读 XML 的用例）。
 */
class PlaybackErrorMapperTest {

    @Test
    fun `文件不存在给出可理解的说明`() {
        assertEquals(
            MspText.Res(
                R.string.msp_playback_error_with_code,
                MspText.Res(R.string.msp_playback_error_file_not_found),
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ),
            PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    @Test
    fun `权限问题走的是重新授权那一支`() {
        // 权限问题和网络问题的「下一步动作」不同：一个是重新选择文件（重新授权），
        // 一个是重试。这一点现在由「走哪一支」表达，而不是由句子里有没有
        // 「重新选择」四个字表达。
        val permission =
            PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        val network =
            PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        assertEquals(
            R.string.msp_playback_error_no_permission,
            permission.branchId(),
            "权限问题必须给出可执行的动作（重新授权），不能退化成通用文案",
        )
        assertNotEquals(permission.branchId(), network.branchId())
    }

    @Test
    fun `解码器缺失的文案必须指出下一步动作`() {
        // 用户的第一诉求是「播放所有格式」，这条错误会是最常见的。
        // 只写「播放失败」等于没写。
        val text = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertEquals(
            R.string.msp_playback_error_decoder_software_not_tried,
            text.branchId(),
            "默认（还没试过软解）时要说「再点一次会重试」，不能说结局已定",
        )
        assertNotEquals(R.string.msp_playback_error_generic, text.branchId())
    }

    @Test
    fun `软解参与程度不同必须给三句不同的话`() {
        val code = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        val notTried = PlaybackErrorMapper.describe(code, softwareDecoding = SoftwareDecodingAttempt.NOT_TRIED)
        val failed = PlaybackErrorMapper.describe(code, softwareDecoding = SoftwareDecodingAttempt.FAILED)
        val unavailable = PlaybackErrorMapper.describe(code, softwareDecoding = SoftwareDecodingAttempt.UNAVAILABLE)

        assertEquals(
            3,
            setOf(notTried, failed, unavailable).size,
            "三种情况的下一步动作不同（重试 / 换文件 / 换设备），不能共用文案。\n" +
                "未试过：$notTried\n试过并失败：$failed\n本包没带：$unavailable",
        )
    }

    @Test
    fun `本包不含 FFmpeg 时不能把锅推给「再去试软解」`() {
        // 这条曾经是个真 bug：文案建议去开一个不存在的开关。
        // 现在能表达的是「没走那一支」，剩下的交给 strings.xml 的文本检查。
        val unavailable = PlaybackErrorMapper.describe(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            softwareDecoding = SoftwareDecodingAttempt.UNAVAILABLE,
        )

        assertEquals(R.string.msp_playback_error_decoder_unavailable, unavailable.branchId())
        assertNotEquals(
            MspText.Res(R.string.msp_playback_error_decoder_software_not_tried),
            unavailable,
            "本包没带软解，不能说「再点一次会重试一次」",
        )
    }

    @Test
    fun `软解已经是 FFmpeg 时解码失败的说法不能自相矛盾`() {
        // 用户手动打开了「强制软件解码」，此时报错说的是「软解也失败了」，
        // 而不是「本机没有解码器」（本机明明有，只是它不认这个文件）。
        val bothFailed = PlaybackErrorMapper.describe(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            softwareDecoding = SoftwareDecodingAttempt.FAILED,
        )
        val notFailed = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_DECODING_FAILED)

        assertEquals(R.string.msp_playback_error_decoder_both_failed, bothFailed.branchId())
        assertEquals(R.string.msp_playback_error_decoder_init, notFailed.branchId())
        assertNotEquals(bothFailed, notFailed)
    }

    @Test
    fun `不同失败原因不能共用同一句文案`() {
        val format = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val network = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val permission = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertEquals(3, setOf(format, network, permission).size, "三类错误必须给出三种不同文案")
    }

    @Test
    fun `格式问题不提供重试网络类问题才提供`() {
        assertTrue(PlaybackErrorMapper.isFormatProblem(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))

        assertTrue(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(PlaybackErrorMapper.isFormatProblem(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))

        // 本地文件找不到：既不是格式问题，重试也没用。
        assertFalse(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun `未知错误码仍保留原始错误码`() {
        val text = PlaybackErrorMapper.describe(errorCode = 987_654)
        val res = text as MspText.Res

        assertEquals(MspText.Res(R.string.msp_playback_error_generic), res.args[0])
        assertTrue(
            res.args.contains(987_654),
            "未知错误必须保留错误码供用户反馈，实际参数：${res.args}",
        )
    }

    @Test
    fun `具体原因会追加到文案里`() {
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeNames = listOf("UnknownHostException"),
        )
        val res = text as MspText.Res

        assertEquals(R.string.msp_playback_error_with_code_and_cause, res.id)
        assertTrue(res.args.contains("UnknownHostException"), "实际参数：${res.args}")
    }

    @Test
    fun `没有信息量的通用原因不追加`() {
        // cause 就是 PlaybackException 本身时，追加只会让文案更啰嗦。
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            causeNames = listOf("androidx.media3.common.PlaybackException"),
        )
        val res = text as MspText.Res

        assertEquals(
            R.string.msp_playback_error_with_code,
            res.id,
            "通用原因不该走「带原因」那一支",
        )
        assertFalse(res.args.any { it is String && it.contains("media3") }, "实际参数：${res.args}")
    }

    @Test
    fun `被 R8 重打包成单字母的原因不追加`() {
        // 用户实际看到的那条提示是「（错误码 2001，u）」——发布版把 Media3 的异常类
        // 重打包成了 `q5.u`。一个单字母的名字对用户是零信息，而且比不说还糟：
        // 看起来像程序出了 bug。所以「名字太短」和「名字是通用异常」一样直接丢。
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeNames = listOf("q5.u", "q5.v"),
        )
        val res = text as MspText.Res

        assertEquals(R.string.msp_playback_error_with_code, res.id, "实际参数：${res.args}")
    }

    @Test
    fun `证书不被信任与网络不通必须给两句不同的话`() {
        // 两者共用 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`（自签名证书实测就被
        // 折成这个码），所以只能靠 cause 链区分。一句文案被两种相反成因共用，
        // 就一定有一边在说谎；而它们的下一步动作是相反的（查 Wi-Fi / 确认服务器证书）。
        val certificate = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeNames = PlaybackErrorMapper.causeNames(sslHandshakeFailure()),
        )
        val network = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeNames = PlaybackErrorMapper.causeNames(IOException("connect timed out")),
        )

        assertEquals(R.string.msp_playback_error_certificate, certificate.branchId())
        assertEquals(R.string.msp_playback_error_network_failed, network.branchId())
        assertNotEquals(certificate, network)
    }

    @Test
    fun `证书那一句不再拼接无意义的原因`() {
        // 证书那句已经把「下一步去哪」说完了（去设置里打开允许不受信任的证书）。
        // 再挂一个 `SSLHandshakeException` 只是把一个英文类名塞给用户看。
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeNames = PlaybackErrorMapper.causeNames(sslHandshakeFailure()),
        )
        val res = text as MspText.Res

        assertEquals(R.string.msp_playback_error_with_code, res.id, "实际参数：${res.args}")
        assertFalse(res.args.any { it is String }, "不该有任何原因子串：${res.args}")
    }

    @Test
    fun `只有握手失败才叫证书问题`() {
        // `SSLHandshakeException` **本身不算**：协议版本不对、双方没有共同密码套件
        // 也抛它，而那两种再怎么信任证书也放不过去——把它们说成「证书不被信任」
        // 会把用户支去拨一个没有用的开关。
        assertFalse(
            PlaybackErrorMapper.isCertificateFailure(
                listOf("javax.net.ssl.SSLHandshakeException"),
            ),
        )
        assertTrue(
            PlaybackErrorMapper.isCertificateFailure(
                listOf("java.security.cert.CertificateException"),
            ),
        )
        assertTrue(
            PlaybackErrorMapper.isCertificateFailure(
                listOf("java.security.cert.CertPathValidatorException"),
            ),
        )
        // 主机名不匹配也靠「放宽校验」修，所以也算。
        assertTrue(
            PlaybackErrorMapper.isCertificateFailure(
                listOf("javax.net.ssl.SSLPeerUnverifiedException"),
            ),
        )
        assertFalse(PlaybackErrorMapper.isCertificateFailure(listOf("java.io.IOException")))
    }

    @Test
    fun `causeNames 会走完整条异常链`() {
        // 实测的链是 `PlaybackException → q5.u → SSLHandshakeException
        // → CertificateException → CertPathValidatorException`：真正有用的那两类
        // 在第二、三层。只看 `cause` 看不到它们。
        val names = PlaybackErrorMapper.causeNames(sslHandshakeFailure())

        assertEquals("java.lang.RuntimeException", names.first(), "实际：$names")
        assertTrue(names.any { it == "javax.net.ssl.SSLHandshakeException" }, "实际：$names")
        assertTrue(
            names.any { it == "java.security.cert.CertPathValidatorException" },
            "实际：$names",
        )
        assertEquals(4, names.size, "RuntimeException → IOException → SSLHandshakeException → CertPathValidatorException，实际：$names")
    }

    @Test
    fun `没有 cause 时链就只有一层`() {
        // 链必须自己收尾：不能假设任何异常都带 cause（`IllegalArgumentException`
        // 这种随手抛的就没有），也不能无限往下走。
        assertEquals(emptyList(), PlaybackErrorMapper.causeNames(null))
        assertEquals(
            listOf("java.lang.RuntimeException"),
            PlaybackErrorMapper.causeNames(RuntimeException("isolated")),
        )
    }

    /** 复现实测那条链：握手失败包着证书校验失败。 */
    private fun sslHandshakeFailure(): Throwable {
        val certificate = CertPathValidatorException(
            "Trust anchor for certification path not found.",
        )
        val handshake = SSLHandshakeException("java.security.cert.CertPathValidatorException")
        handshake.initCause(certificate)
        return RuntimeException(IOException(handshake))
    }

    // ===== 下面是资源文件自身的检查：纯逻辑断言管不到「话写得对不对」 =====

    @Test
    fun `三种软解口径在资源文件里是三句不同的话`() {
        val xml = languageStrings()

        val sentences = listOf(
            "msp_playback_error_decoder_unavailable",
            "msp_playback_error_decoder_software_failed",
            "msp_playback_error_decoder_software_not_tried",
        ).map { name ->
            val line = xml.lineSequence().firstOrNull { it.contains("name=\"$name\"") }
            assertTrue(line != null, "values/strings.xml 里缺少 $name")
            line!!
        }

        assertEquals(3, sentences.toSet().size, "三句必须真的不一样：\n${sentences.joinToString("\n")}")
    }

    @Test
    fun `不含 FFmpeg 的那句不再提设置里的开关`() {
        // 那个开关从来不存在。这条断言守的是「别再把用户支到空处」。
        val xml = languageStrings()
        val line = xml.lineSequence().first { it.contains("name=\"msp_playback_error_decoder_unavailable\"") }

        assertFalse(line.contains("设置"), "不该建议去设置里改什么，实际：$line")
    }

    @Test
    fun `证书那句必须写出那个开关叫什么`() {
        // 这句是「用户能不能自己解决」的唯一线索，而它指向的设置项名字**写在另一个模块**
        // （`feature/settings` 的 `msp_settings_trust_certificates`）。两处各写一遍名字
        // 的话一定会漂移：错误提示说「打开『信任证书』」，而设置里那一行叫「允许不受信任的证书」——
        // 用户会以为那是两个东西。所以那串字面量在这里钉一次。
        val xml = languageStrings()
        val line = xml.lineSequence().first { it.contains("name=\"msp_playback_error_certificate\"") }

        assertTrue(
            line.contains("允许不受信任的证书"),
            "文案必须指名道姓地说出设置项的名字，实际：$line",
        )
        assertTrue(line.contains("播放"), "还要说清去哪个页面，实际：$line")
    }

    @Test
    fun `证书那句不会把「网络不通」的建议也抄一遍`() {
        // 两种成因的下一步动作是相反的：一个去查 Wi-Fi/地址，一个去确认服务器证书。
        // 两边都说等于什么都没说。
        val xml = languageStrings()
        val certificate = xml.lineSequence()
            .first { it.contains("name=\"msp_playback_error_certificate\"") }
        val network = xml.lineSequence()
            .first { it.contains("name=\"msp_playback_error_network_failed\"") }

        assertNotEquals(certificate, network)
        assertFalse(certificate.contains("请检查网络"), "实际：$certificate")
    }

    /** 读 zh-Hans 的 `values/strings.xml` 原文——JVM 单测里拿不到 `Resources`。 */
    private fun languageStrings(): String {
        val file = File(
            repoRoot(),
            "core/player/src/main/res/values/strings.xml",
        )
        assertTrue(file.isFile, "读不到资源文件：$file")
        return file.readText()
    }

    /** 单元测试的工作目录是模块目录，但为了稳（Gradle 换过行为）还是往上找标志文件。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（没有 settings.gradle.kts）")
    }
}

/**
 * 取出「这条错误走的是哪一支」——`describe` 总是把分支包在
 * 「（错误码 N）」那一层里，所以往里钻一层。
 */
private fun MspText.branchId(): Int = ((this as MspText.Res).args[0] as MspText.Res).id
