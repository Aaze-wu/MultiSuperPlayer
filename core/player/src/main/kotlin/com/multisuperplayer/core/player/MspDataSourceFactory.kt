package com.multisuperplayer.core.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 播放器用的网络数据源工厂：**默认那一套**，以及用户明确要求时才生效的「信任不受信任的证书」那一套。
 *
 * ## 为什么要有第二条路
 *
 * 自建服务器（NAS、openlist/AList、反向代理）经常用自签名证书或私有 CA 签的证书，
 * 系统的信任库里没有它，握手就死在
 * 「`Trust anchor for certification path not found`」。这件事没有「修好服务器」以外的
 * 正统解法，但用户就是这台服务器的主人——他能自己判断要不要放行，所以给一个**默认关闭**的开关，
 * 而不是替他决定。默认关闭是这条设计的核心：一个默认放行所有证书的播放器，等于把每个用户
 * 的流量都暴露给同一个 Wi-Fi 下的任何人。
 *
 * ## 为什么是「工厂里二选一」而不是「切换时重建 player」
 *
 * `DataSource.Factory` 是 Builder 阶段指定的，按设置项重建 `ExoPlayer` 会丢掉当前播放位置、
 * 队列、A-B 循环、手选的音轨——而用户是在设置页拨完开关、切回播放页才开播的，那时任何
 * 丢失都不可接受（同 [ExoPlayerController.player] 上渲染器工厂的注释）。所以这里装**一个**
 * 会看开关的工厂，[createDataSource] 每次被调用时现读开关：开关拨完，**下一条**媒体就是新行为，
 * 不需要重建任何东西。
 *
 * 正因为「现读」，参数是 `() -> Boolean` 而不是 `Boolean`。
 *
 * ## 关着的时候必须与从前**逐字节**一致
 *
 * 关着时返回的是 `DefaultDataSource.Factory(context)` 造出来的东西——也就是 Media3
 * `ExoPlayer.Builder` 自己的默认值（`DefaultHttpDataSource` + 8 秒连接/读取超时），
 * 而不是「我们也写一个 OkHttp 但小心点」。理由：默认路径是**所有人**每天都在走的路径，
 * 为了一个少数人才打开的开关去换掉它，风险（重定向策略、Range 请求、代理、UA 嗅探）
 * 换来的收益是零。装 OkHttp 那一条只在开关打开后才会被创建。
 */
@OptIn(UnstableApi::class)
class MspDataSourceFactory internal constructor(
    private val defaultFactory: DataSource.Factory,
    private val trustingFactory: DataSource.Factory,
    private val trustUntrustedCertificates: () -> Boolean,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = if (trustUntrustedCertificates()) {
        trustingFactory.createDataSource()
    } else {
        defaultFactory.createDataSource()
    }

    companion object {
        /**
         * 生产环境用的构造：两条路都是 `DefaultDataSource`，区别只在「http(s) 走谁」。
         *
         * `DefaultDataSource`（而不是直接给它一个 HTTP 源）是必须的：`file://`、`content://`、
         * `asset://` 这些本地 scheme 由它自己处理，基类只服务 http(s)。换句话说，
         * 打开这个开关**不会**影响播放本地文件的那条路。
         */
        fun create(context: Context, trustUntrustedCertificates: () -> Boolean): MspDataSourceFactory {
            val appContext = context.applicationContext
            val trustingBase = OkHttpDataSource.Factory(trustingHttpClient())
                // 和默认栈发同一个 User-Agent，见 [defaultUserAgent]。
                .setUserAgent(defaultUserAgent())
            return MspDataSourceFactory(
                defaultFactory = DefaultDataSource.Factory(appContext),
                trustingFactory = DefaultDataSource.Factory(appContext, trustingBase),
                trustUntrustedCertificates = trustUntrustedCertificates,
            )
        }

        /**
         * 默认栈实际发出去的那串 User-Agent。
         *
         * 默认的 `DefaultHttpDataSource` **不显式设** UA（源码里是 `if (userAgent != null)`），
         * 最终发出去的是 Android 运行时给 `HttpURLConnection` 的默认值，也就是
         * `http.agent` 系统属性（形如 `Dalvik/2.1.0 (Linux; U; Android 14; ...)`）。
         * OkHttp 不设则发自己的 `okhttp/4.x`——两者对服务器**可见且不同**，而有些服务器
         * （防盗链、按客户端分流的逻辑）真会因此分流，那就会出现「打开开关反而 403」
         * 这种完全想不到的因果。所以这里把那串值原样拄过来。
         *
         * 属性缺失（自定义运行时/测试环境）时返回 `null`，交给 OkHttp 自己的默认值——
         * **比编一个假名字好**：至少不会冒充一个它并不是的客户端。
         *
         * 取值方式做成参数（而不是直接调 `System.getProperty`）是为了能单测：
         * 这函数的全部内容就是两种边界（缺失、空白串），而它们在真机上永远不会出现。
         */
        internal fun defaultUserAgent(
            readProperty: (String) -> String? = System::getProperty,
        ): String? = readProperty("http.agent")?.takeIf { it.isNotBlank() }

        /**
         * 超时和 Media3 的 `DefaultHttpDataSource` 保持一致（8 秒连接 / 8 秒读取）。
         *
         * OkHttp 自己的默认值是各 10 秒，不显式设就会变成「拨完开关，同一台服务器超时时间
         * 悄悄变了 2 秒」——排查网络问题时这种偏差最费时间。
         */
        private fun trustingHttpClient(): OkHttpClient {
            val trustManager = TrustAnyCertificate
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            return OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                // 证书链放行了还不够：OkHttp 会再单独校验「证书里的主机名和地址是否一致」，
                // 而自建服务器用 IP 访问、证书却签给域名（或者干脆是自签名）时，
                // 卡住的正是这一步——只放行证书链会让开关看起来「有时管用有时不管用」。
                .hostnameVerifier { _, _ -> true }
                .build()
        }

        /** 见 [trustingHttpClient]。 */
        private const val DEFAULT_TIMEOUT_SECONDS = 8L
    }
}

/**
 * 「所有证书都收」的信任管理器。只在用户打开开关之后才会被构造和使用（见 [MspDataSourceFactory]）。
 *
 * ⚠️ 它**故意什么都不做**，这才是这个功能本身——不要「顺手」在这里加一个证书白名单：
 * 白名单需要用户能看见并管理证书，是可信任的中间人（MITM）之外的另一套设计，
 * 而这里要解决的是「我的服务器用自签名证书，而我是它的主人」。
 */
private object TrustAnyCertificate : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
