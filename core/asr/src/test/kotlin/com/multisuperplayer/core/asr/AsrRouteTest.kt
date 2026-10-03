package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别路线的测试。
 *
 * 这一层错起来**没有任何报错**：`byId` 认不出来时回落到本机（不回云端），所以
 * 一个写错的 id 只表现为「我明明选了云端，它又变回本机了」；而反过来——如果哪天
 * 有人把 [AsrRoute.DEFAULT] 改成云端——表现是「一段私人录音莫名其妙被上传了」，
 * 那也是无声的。所以两件事都要用断言钉住：**id 的字面量**（持久化契约）和
 * **缺省是哪一边**（隐私口径）。
 */
class AsrRouteTest {

    @Test
    fun `缺省是本机而不是云端`() {
        // 这是隐私口径，不是偏好：默认云端意味着用户什么都没选，一段私人录音
        // 就已经被上传了。选错的代价也不对称——默认本机、用户想用云端，他会自己去切；
        // 默认云端、用户没注意，那段音频已经出去了，撤不回来。
        assertEquals(AsrRoute.ON_DEVICE, AsrRoute.DEFAULT)
    }

    @Test
    fun `id 按字面量锁住`() {
        // ⚠️ 这是写进用户设备的契约：改名不会编译失败、不会崩，只会让盘上的
        // "cloud" 读不出来 → 回落到本机 → 用户下次点「生成字幕」时
        // 模型不见了（因为本机模型没装过）。锁住它，让「顺手重命名」红一次。
        assertEquals("on_device", AsrRoute.ON_DEVICE.id)
        assertEquals("cloud", AsrRoute.CLOUD.id)
    }

    @Test
    fun `认不出来一律回本机`() {
        // 老版本留下的值 / 手改过的配置 / 以后删掉的路，读取侧都得活着。
        listOf(null, "", "   ", "CLOUD", "cloud ", "local", "0", "true").forEach { raw ->
            assertEquals("「$raw」应当回落到本机", AsrRoute.DEFAULT, AsrRoute.byId(raw))
        }
    }

    @Test
    fun `云端能按 id 读回来`() {
        assertEquals(AsrRoute.CLOUD, AsrRoute.byId("cloud"))
    }

    @Test
    fun `两条路的显示名各自是一条资源`() {
        // 写死成字符串就等于把语言焊死在中/英/繁之一上；两条共用一条资源
        // 则会在设置页出现两个一模一样的选项。
        val ids = AsrRoute.entries.map { route ->
            val name = route.displayName
            assertTrue("${route.id} 的显示名必须是资源，实际是 $name", name is MspText.Res)
            (name as MspText.Res).id
        }

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }
}
