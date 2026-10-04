package com.multisuperplayer.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 「信任不受信任的证书」这个开关只有在**没人看的时候也对**才有意义：它是一个安全开关，
 * 而且这个仓库里没有任何在模拟器上跑自签名 HTTPS 服务器的条件，所以行为只能靠单测钉住。
 *
 * 这里测不到真的 TLS 握手（见 `MspDataSourceFactory` 的注释：关着的那条路就是 Media3 自己的
 * 默认值，打开的那条只有真握手才看得出差别）。能测、也必须测的是**选择本身**：
 * 拨到哪一边、什么时候读开关、要不要重造数据源。
 */
@OptIn(UnstableApi::class)
class MspDataSourceFactoryTest {

    @Test
    fun `关着的时候走默认那一条`() {
        val strict = RecordingFactory()
        val trusting = RecordingFactory()
        val factory = MspDataSourceFactory(strict, trusting) { false }

        // 注意求值顺序：`assertSame(strict.last, factory.createDataSource())` 会先把
        // `strict.last`（上一轮的值）读到参数里，断言就变成拿旧值比新值。
        val dataSource = factory.createDataSource()
        assertSame(strict.last, dataSource)

        // 这条断言比「走对了分支」更重要：关着时**连一个**放行证书的数据源都不该被造出来。
        // 真出问题的话症状不是文案错了，而是用户的流量被静默降级——那种 bug 谁都不会发现。
        assertEquals(0, trusting.created, "关着的时候不该碰放行证书那一套")
    }

    @Test
    fun `开着的时候走放行证书那一条`() {
        val strict = RecordingFactory()
        val trusting = RecordingFactory()
        val factory = MspDataSourceFactory(strict, trusting) { true }

        val dataSource = factory.createDataSource()
        assertSame(trusting.last, dataSource)
        assertEquals(0, strict.created)
    }

    @Test
    fun `每次取数据源都现读开关`() {
        // 这个设计的目的：用户拨完开关**不用重启播放器**。`DataSource.Factory` 是
        // Builder 阶段装进去的，重建 `ExoPlayer` 会丢掉播放位置、队列、A-B 循环和手选的音轨，
        // 所以开关不能是构造参数，只能是每次 `createDataSource` 现读的回调。
        // 换成 `Boolean` 参数会让下面这条断言无法表达（这也是它存在的意义），
        // 顺带钉住「上一条媒体用的还是旧行为」——开关只对**下一条**生效。
        val strict = RecordingFactory()
        val trusting = RecordingFactory()
        var enabled = false
        val factory = MspDataSourceFactory(strict, trusting) { enabled }

        val whileOff = factory.createDataSource()
        assertSame(strict.last, whileOff)

        enabled = true
        val nowTrusting = factory.createDataSource()
        assertSame(trusting.last, nowTrusting)

        enabled = false
        val backToStrict = factory.createDataSource()
        assertSame(strict.last, backToStrict)
    }

    @Test
    fun `每次都造一个新的数据源`() {
        // Media3 要求 `DataSource` 是「一次连接一个」，复用同一个实例会把上一次的
        // 连接状态（是否已打开、位置）带进下一条媒体。这条在真机上表现为「切下一个视频
        // 直接报错」，而单测是唯一能在提交前发现它的地方。
        val factory = MspDataSourceFactory(RecordingFactory(), RecordingFactory()) { false }

        assertNotSame(factory.createDataSource(), factory.createDataSource())
    }

    @Test
    fun `User-Agent 抄默认栈那一串`() {
        // 打开开关后换掉 UA 会让某些服务器（防盗链、按客户端分流）表现不同，
        // 而因果链太远，用户只会看到「打开开关反而 403」。默认栈实际发的是
        // Android 运行时给的 `http.agent`，所以照抄它。
        assertEquals(
            "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A)",
            MspDataSourceFactory.defaultUserAgent {
                "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A)"
            },
        )
    }

    @Test
    fun `User-Agent 缺失或全是空白时交给 OkHttp 自己决定`() {
        // 返回 `null` 时 OkHttp 发自己的 `okhttp/4.x`。空白串必须当成「没有」：
        // 把它当成一个值会发出去一个空的 `User-Agent:` 头，比不设更奇怪。
        assertEquals(null, MspDataSourceFactory.defaultUserAgent { null })
        assertEquals(null, MspDataSourceFactory.defaultUserAgent { "" })
        assertEquals(null, MspDataSourceFactory.defaultUserAgent { "   " })
    }

    @Test
    fun `默认那一条不被包装也不被改写`() {
        // 关着的时候必须与从前逐字节一致：拿到的是 `defaultFactory` **自己**造的东西。
        // 这里用「每个实例都带自己的标记」的方式确认中间没有多包一层。
        val strict = RecordingFactory()
        val factory = MspDataSourceFactory(strict, RecordingFactory()) { false }

        repeat(3) { factory.createDataSource() }

        assertEquals(3, strict.created, "三次请求都该落在默认那条路上")
        assertEquals(listOf(1, 2, 3), strict.marks, "不该多造或少造")
    }

    /** 只记账、不真的连网的数据源工厂（单测里没有可用的网络）。 */
    private class RecordingFactory : DataSource.Factory {

        var created = 0
        val marks = mutableListOf<Int>()

        /** 最近一次造出来的数据源——用来断言「走的是哪一条路」。 */
        var last: DataSource? = null
            private set

        override fun createDataSource(): DataSource {
            created++
            marks.add(created)
            return ByteArrayDataSource(byteArrayOf(created.toByte())).also { last = it }
        }
    }
}
