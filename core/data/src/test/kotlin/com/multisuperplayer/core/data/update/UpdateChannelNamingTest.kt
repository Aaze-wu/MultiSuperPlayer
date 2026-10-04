package com.multisuperplayer.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 通道名与枚举项之间那层翻译。
 *
 * 这层代码看着没什么可测的，出问题的样子也很安静：改一次枚举名，老用户的设置
 * 读不出来，于是**退回默认通道**。退回默认通道不报错、界面显示得也像模像样，
 * 只有当事人发现自己「选过的那一项变回去了」。
 *
 * 所以两条都要钉：现在的名字能读回来（改名时记得加迁移），
 * 以及老名字 `PRERELEASE` 会被翻译成 `BETA` 而不是掉进兜底。
 */
class UpdateChannelNamingTest {

    @Test
    fun `只有两档`() {
        // 三档会在界面上多出一个「永远选不到任何东西」的选项：剩下的那一档是
        // alpha，而 alpha 不对外发布。这一条不是洁癖，是那个决策的落点。
        assertEquals(2, UpdateChannel.entries.size)
    }

    @Test
    fun `每个通道名都能原样读回来`() {
        val broken = UpdateChannel.entries.filter { channel ->
            channel.name.toChannelOrNull() != channel
        }

        assertEquals(emptyList(), broken, "这些通道名写出去之后读不回来，改名时漏了迁移")
    }

    @Test
    fun `旧的 PRERELEASE 翻译成 BETA`() {
        // 0.8.1 及以前那一档叫 PRERELEASE。直接靠默认值兜住的话，装了预发行版的用户
        // 恰好也是 BETA（看不出问题），但一个**手动**选过这一档的正式版用户会被
        // 悄悄退回「只收正式版」——他下次再也不会收到测试版。
        assertEquals(UpdateChannel.BETA, "PRERELEASE".toChannelOrNull())
    }

    @Test
    fun `认不出来的名字读成 null 而不是崩掉`() {
        // `valueOf` 会抛 IllegalArgumentException：某天有人把枚举项改名之后，
        // 所有已经装了旧版的应用在下次启动读设置时崩掉。读不出来就退回默认通道，
        // 那是一个用户能自己改回来的状态。
        assertNull(null.toChannelOrNull())
        assertNull("".toChannelOrNull())
        assertNull("GARBAGE".toChannelOrNull())
        // 名字是精确比较的（写出去的是 `channel.name`），小写不算——
        // 宽松匹配会让「谁写进去的」变得说不清，而迁移需要一个确定的判据。
        assertNull("beta".toChannelOrNull())
    }
}
