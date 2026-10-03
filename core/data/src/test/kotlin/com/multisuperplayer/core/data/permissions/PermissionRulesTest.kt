package com.multisuperplayer.core.data.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限状态判定。
 *
 * 这里的每一条都能在真机上发生，但**很难在真机上穷举**：`rationale` 的取值取决于
 * 用户上次是怎么点的（拒绝一次 vs 拒绝两次并勾选「不再询问」），「所有文件访问」
 * 的专属设置页只在 30+ 上有，蓝牙权限只在 31+ 上有。所以判定逻辑全是纯函数，
 * 由这里的假数据钉住；真机上只验证「这一份判定接到的输入对不对」。
 *
 * 断言写成「一组事实 → 一个状态」，不做循环生成：循环能让用例数好看，
 * 但一旦哪条错了，报出来的是「下标 7 不符」，而不是「被拒过两次之后该去设置页」。
 */
class PermissionRulesTest {

    // ------------------------------------------------------------------ stateOf

    @Test
    fun `stateOf - 已经允许时前面问过什么都不影响`() {
        // 三条事实同时给真：只有 granted 该赢。顺序写错的话，
        // 一个已经允许的权限会因为「上次拒绝时记下的 rationale」被说成「可以再申请」。
        val state = PermissionRules.stateOf(
            PermissionFacts(granted = true, rationale = true, asked = true),
        )

        assertEquals(PermissionState.GRANTED, state)
    }

    @Test
    fun `stateOf - 被拒过但系统还愿意弹框时是可以再申请`() {
        val state = PermissionRules.stateOf(
            PermissionFacts(granted = false, rationale = true, asked = true),
        )

        assertEquals(PermissionState.ASK_AGAIN, state)
    }

    @Test
    fun `stateOf - 被拒过而且系统不愿再弹框时只能去设置`() {
        val state = PermissionRules.stateOf(
            PermissionFacts(granted = false, rationale = false, asked = true),
        )

        assertEquals(PermissionState.GO_TO_SETTINGS, state)
    }

    @Test
    fun `stateOf - 从来没问过时说没问过, 而不是去设置`() {
        // 把 NOT_ASKED 与 GO_TO_SETTINGS 混起来的话，首次启动那条路（自动申请一次）
        // 会让用户看到「已被拒绝」——而他从没拒绝过。
        val state = PermissionRules.stateOf(
            PermissionFacts(granted = false, rationale = false, asked = false),
        )

        assertEquals(PermissionState.NOT_ASKED, state)
    }

    // -------------------------------------------------------------- mediaState

    @Test
    fun `mediaState - 一条都没允许时按普通判定走, 四种状态都传得出来`() {
        // 一条都没允许时，「缺的那一半怎么办」还不成立，问题就是「怎么再问一次」，
        // 所以这时必须完整保留 stateOf 的四态（而不是一律说成「只允许了一部分」）。
        assertEquals(
            PermissionState.NOT_ASKED,
            PermissionRules.mediaState(media(rationale = false, asked = false)),
        )
        assertEquals(
            PermissionState.ASK_AGAIN,
            PermissionRules.mediaState(media(rationale = true, asked = true)),
        )
        assertEquals(
            PermissionState.GO_TO_SETTINGS,
            PermissionRules.mediaState(media(rationale = false, asked = true)),
        )
    }

    @Test
    fun `mediaState - 音频视频都允许了才是已允许`() {
        val state = PermissionRules.mediaState(
            MediaPermissionFacts(
                audioGranted = true,
                videoGranted = true,
                partialVisual = false,
                rationale = false,
                asked = true,
            ),
        )

        assertEquals(PermissionState.GRANTED, state)
    }

    @Test
    fun `mediaState - 只允许了音频时说只允许了一部分, 不说去设置`() {
        // 这是这个函数存在的全部理由。系统对**已允许**的权限永远答 `rationale = false`，
        // 于是 asked=true 会让它落到 GO_TO_SETTINGS：「音频给了、视频没给」的用户
        // 会看到「已被拒绝」，而其实再申请一次就能补上视频。
        val state = PermissionRules.mediaState(
            MediaPermissionFacts(
                audioGranted = true,
                videoGranted = false,
                partialVisual = false,
                rationale = false,
                asked = true,
            ),
        )

        assertEquals(PermissionState.PARTIAL, state)
    }

    @Test
    fun `mediaState - 只允许了视频时说只允许了一部分`() {
        val state = PermissionRules.mediaState(
            MediaPermissionFacts(
                audioGranted = false,
                videoGranted = true,
                partialVisual = false,
                rationale = false,
                asked = true,
            ),
        )

        assertEquals(PermissionState.PARTIAL, state)
    }

    @Test
    fun `mediaState - 视频只是用户勾选的那几个时也说不完整`() {
        // Android 14 上「仅选择部分」的结果：两个视频权限都算允许，
        // 但媒体库里只有用户挑的那几个文件。只说「已允许」的话，
        // 用户会以为手机里其它视频消失了。
        val state = PermissionRules.mediaState(
            MediaPermissionFacts(
                audioGranted = true,
                videoGranted = true,
                partialVisual = true,
                rationale = false,
                asked = true,
            ),
        )

        assertEquals(PermissionState.PARTIAL, state)
    }

    // ----------------------------------------------------------- canAskInPlace

    @Test
    fun `canAskInPlace - 本机没有这一项时永远不能申请`() {
        // 「永远不会有授权框」和「现在不能弹」是两件事，但对这一格来说结果一样：
        // 点下去什么都发生不了，所以界面不该给这个动作。
        PermissionKind.entries.forEach { kind ->
            assertFalse("$kind 在本机不支持时不该有申请动作", PermissionRules.canAskInPlace(kind, PermissionState.UNSUPPORTED))
        }
    }

    @Test
    fun `canAskInPlace - 已经允许时不能再申请`() {
        // 允许了之后再走 requestPermissions 会弹一个空框或者什么都不发生，
        // 这一行这时该给的唯一动作是「去系统里关掉」。
        PermissionKind.entries.forEach { kind ->
            assertFalse("$kind 已允许时不该再申请", PermissionRules.canAskInPlace(kind, PermissionState.GRANTED))
        }
    }

    @Test
    fun `canAskInPlace - 所有文件访问永远只能去设置页`() {
        // 它是特权权限，系统不给应用弹框这一条路：对那些状态调
        // `requestPermissions` 会被静默拒绝，用户点「申请」时界面毫无反应。
        listOf(
            PermissionState.NOT_ASKED,
            PermissionState.ASK_AGAIN,
            PermissionState.GO_TO_SETTINGS,
            PermissionState.PARTIAL,
        ).forEach { state ->
            assertFalse(
                "所有文件访问在 $state 时也不该就地申请",
                PermissionRules.canAskInPlace(PermissionKind.ALL_FILES, state),
            )
        }
    }

    @Test
    fun `canAskInPlace - 其余三项只要系统还愿意弹框就能申请`() {
        listOf(PermissionKind.MEDIA, PermissionKind.NOTIFICATION, PermissionKind.BLUETOOTH).forEach { kind ->
            listOf(
                PermissionState.NOT_ASKED,
                PermissionState.ASK_AGAIN,
                PermissionState.PARTIAL,
            ).forEach { state ->
                assertTrue("$kind 在 $state 时应当能就地申请", PermissionRules.canAskInPlace(kind, state))
            }
        }
    }

    @Test
    fun `canAskInPlace - 被系统记住拒绝之后只能去设置页, 不再给一个按了没反应的申请`() {
        // 这一格曾经写成 true（理由「弹不弹框由系统决定」），但 GO_TO_SETTINGS 的定义就是
        // 「系统已经不再弹框」：再调一次 requestPermissions 只会被静默拒绝，
        // 而同一行左边的状态文字写的是「已被拒绝」——一个按了没反应的按钮。
        // 这一条与 PermissionState 文档里那句「哪几态还弹得出来」必须说的是同一件事。
        listOf(PermissionKind.MEDIA, PermissionKind.NOTIFICATION, PermissionKind.BLUETOOTH).forEach { kind ->
            assertFalse(
                "$kind 在 GO_TO_SETTINGS 时不该就地申请",
                PermissionRules.canAskInPlace(kind, PermissionState.GO_TO_SETTINGS),
            )
        }
    }

    // ------------------------------------------------------- 清单覆盖的对照表

    @Test
    fun `清单对照表 - 三份清单内部没有重复`() {
        // 重复项会让权限页上出现两行一样的东西，或者让清单覆盖测试自己骗自己。
        assertEquals(
            "可更改项里有重复",
            PermissionRules.LISTED_PERMISSIONS.size,
            PermissionRules.LISTED_PERMISSIONS.toSet().size,
        )
        assertEquals(
            "折叠区里有重复",
            PermissionRules.OTHER_PERMISSIONS.size,
            PermissionRules.OTHER_PERMISSIONS.toSet().size,
        )
        assertEquals(
            "未单列项里有重复",
            PermissionRules.NOT_LISTED_PERMISSIONS.size,
            PermissionRules.NOT_LISTED_PERMISSIONS.toSet().size,
        )
    }

    @Test
    fun `清单对照表 - 三份之间也不该有交集`() {
        val listed = PermissionRules.LISTED_PERMISSIONS.toSet()
        val other = PermissionRules.OTHER_PERMISSIONS.toSet()
        val notListed = PermissionRules.NOT_LISTED_PERMISSIONS.toSet()

        assertEquals("同一项既单列又折叠", emptySet<String>(), listed intersect other)
        assertEquals("同一项既单列又说不单列", emptySet<String>(), listed intersect notListed)
        assertEquals("同一项既折叠又说不单列", emptySet<String>(), other intersect notListed)
    }

    @Test
    fun `清单对照表 - 四项可更改权限对应的权限名都在单列清单里`() {
        // 单列清单是拿来跟 AndroidManifest 对照的，所以它必须**看得到**四项里每一项
        // 实际申请的那个权限名。漏一条（比如加了第五项却忘了往这里加），
        // 覆盖测试就再也拦不住「声明了权限但页面上没说」。
        assertTrue(PermissionRules.LISTED_PERMISSIONS.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertTrue(PermissionRules.LISTED_PERMISSIONS.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(PermissionRules.LISTED_PERMISSIONS.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(PermissionRules.LISTED_PERMISSIONS.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertTrue(PermissionRules.LISTED_PERMISSIONS.contains(Manifest.permission.READ_MEDIA_VIDEO))
    }

    @Test
    fun `清单对照表 - 旧系统上的媒体读取与部分选择都不单独列成一项`() {
        // READ_EXTERNAL_STORAGE 是媒体读取在 ≤32 上的**名字**，
        // READ_MEDIA_VISUAL_USER_SELECTED 是那次申请的**结果分支**；
        // 单独列出来会让权限页看起来有两个视频权限。
        assertTrue(PermissionRules.NOT_LISTED_PERMISSIONS.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        assertTrue(
            PermissionRules.NOT_LISTED_PERMISSIONS.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        )
    }

    @Test
    fun `清单对照表 - 折叠区里的都是应用改不了的`() {
        // 前台的媒体通知、网络访问这些在安装时就已经定了。往这里塞一条可更改的权限，
        // 权限页上就会有一项「列出来但点不了」。
        PermissionRules.OTHER_PERMISSIONS.forEach { permission ->
            assertFalse("$permission 是可更改项，不该放进折叠区", PermissionRules.LISTED_PERMISSIONS.contains(permission))
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun media(audio: Boolean = false, video: Boolean = false, rationale: Boolean, asked: Boolean) =
        MediaPermissionFacts(
            audioGranted = audio,
            videoGranted = video,
            partialVisual = false,
            rationale = rationale,
            asked = asked,
        )
}
