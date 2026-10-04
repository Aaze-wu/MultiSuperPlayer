package com.multisuperplayer.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [UpdateRules.decide] 的筛选规则。
 *
 * 每条规则都要有「不该被选中的那条确实没被选中」的反例：这个函数的失败方式是
 * 挑错了一条，而挑错之后界面照样显示得像模像样。
 */
class UpdateRulesTest {

    private fun release(
        tag: String,
        preRelease: Boolean = false,
        apk: Boolean = true,
        publishedAt: Long? = null,
    ): UpdateRelease {
        val version = requireNotNull(UpdateVersion.parse(tag))
        return UpdateRelease(
            tagName = tag,
            version = version,
            isPreRelease = preRelease,
            publishedAtEpochMs = publishedAt,
            notes = null,
            apkUrl = if (apk) "https://example.invalid/$tag/app-release.apk" else null,
            apkSizeBytes = if (apk) 1234L else null,
            apkSha256 = null,
        )
    }

    private val current = UpdateVersion.parse("0.6.8-alpha.1")!!

    @Test
    fun `正式版通道不接受预发行版`() {
        val result = UpdateRules.decide(
            current = UpdateVersion.parse("0.6.8")!!,
            releases = listOf(release("v0.7.0-alpha.1", preRelease = true)),
            channel = UpdateChannel.STABLE,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `测试版通道也接受正式版`() {
        // 装 beta 的用户必须能收到正式版，否则他永远退不出测试通道。
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.7.0")),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        val available = assertIs<UpdateAvailability.Available>(result)
        assertEquals("v0.7.0", available.release.tagName)
    }

    @Test
    fun `没有可下载 APK 的发行版不算更新`() {
        // 提示了也装不上，等于把用户骗去一个死页面。
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.7.0", apk = false)),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `同一个版本号重复出现不算更新`() {
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.6.8-alpha.1", preRelease = true)),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `按版本号取最高而不是按发布时间取最新`() {
        // 给旧版本补发一个 alpha 的场景：按发布时间选会把用户从 0.7.0 劝回 0.6.5。
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(
                release("v0.7.0", publishedAt = 1_000L),
                release("v0.6.5-alpha.3", preRelease = true, publishedAt = 9_999L),
            ),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        val available = assertIs<UpdateAvailability.Available>(result)
        assertEquals("v0.7.0", available.release.tagName)
    }

    @Test
    fun `忽略过的版本单独成一种结论`() {
        val releases = listOf(release("v0.7.0"))
        val result = UpdateRules.decide(
            current = current,
            releases = releases,
            channel = UpdateChannel.BETA,
            ignoredTag = "v0.7.0",
        )
        val ignored = assertIs<UpdateAvailability.Ignored>(result)
        assertEquals("v0.7.0", ignored.release.tagName)
    }

    @Test
    fun `忽略的是版本而不是永远不再提示`() {
        // 下一条更新的版本出现时结论要变回 Available。
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.7.0"), release("v0.7.1")),
            channel = UpdateChannel.BETA,
            ignoredTag = "v0.7.0",
        )
        val available = assertIs<UpdateAvailability.Available>(result)
        assertEquals("v0.7.1", available.release.tagName)
    }

    @Test
    fun `当前版本读不出来时不猜一个`() {
        val result = UpdateRules.decide(
            current = null,
            releases = listOf(release("v0.7.0")),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.NotChecked, result)
    }

    @Test
    fun `没有候选时是已是最新而不是还没查过`() {
        // 这两种结论界面要说不同的话：前者有依据，后者没有。
        val result = UpdateRules.decide(
            current = current,
            releases = emptyList(),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `默认通道跟着当前装的那一路走`() {
        assertTrue(UpdateRules.defaultChannelFor(true) == UpdateChannel.BETA)
        assertTrue(UpdateRules.defaultChannelFor(false) == UpdateChannel.STABLE)
    }

    // ------------------------------------------------- 三条线：正式版 / beta / alpha

    @Test
    fun `测试版通道收得到公开测试版`() {
        val result = UpdateRules.decide(
            current = UpdateVersion.parse("0.8.1")!!,
            releases = listOf(release("v0.9.0-beta.1", preRelease = true)),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        val available = assertIs<UpdateAvailability.Available>(result)
        assertEquals("v0.9.0-beta.1", available.release.tagName)
    }

    @Test
    fun `正式版通道收不到测试版`() {
        val result = UpdateRules.decide(
            current = UpdateVersion.parse("0.8.1")!!,
            releases = listOf(release("v0.9.0-beta.1", preRelease = true)),
            channel = UpdateChannel.STABLE,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `测试版通道收不到内部 alpha`() {
        // alpha 不对外发布，但「不发布」是人的约定，不是会执行的规则。
        // 万一有一版漏在更新源上，选了「测试版」的用户不该被推过去。
        val result = UpdateRules.decide(
            current = UpdateVersion.parse("0.8.1")!!,
            releases = listOf(release("v0.9.0-alpha.1", preRelease = true)),
            channel = UpdateChannel.BETA,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `两个通道是包含关系而不是两条平行的路`() {
        // 正式版 ⊆ 测试版。反过来（某一版只有正式版通道收得到）意味着装了
        // 测试版的用户被卡住：他收不到下一版，也退不回正式版。
        val releases = listOf(
            release("v0.9.0"),
            release("v0.9.0-beta.1", preRelease = true),
            release("v0.9.0-alpha.1", preRelease = true),
            release("v0.9.0-rc.1", preRelease = true),
        )
        val stable = releases.filter { UpdateChannel.STABLE.allows(it) }
        val beta = releases.filter { UpdateChannel.BETA.allows(it) }
        val kinds = beta.map { it.version.preReleaseKind }

        assertTrue(stable.single().tagName == "v0.9.0", "正式版通道只该收那一条正式版")
        assertTrue(beta.containsAll(stable), "测试版通道必须把正式版通道收到的也收进来")
        assertTrue(kinds.contains("beta"), "测试版通道要收 beta")
        assertFalse(kinds.contains("alpha"), "测试版通道不收 alpha")
        // rc 既不是 beta、也没有对应的通道：没人收是**有意的**（它不会对外发），
        // 但得钉住，否则下次有人「顺手放宽」到「任何预发行版」时没人拦。
        assertFalse(kinds.contains("rc"), "测试版通道也不收 rc")
    }

    @Test
    fun `稳定 tag 被误勾成预发行时正式版通道不收`() {
        // 两个方向的代价不对称：晚一版收到是可接受的，把测试包推给全体稳定用户不是。
        val misflagged = release("v0.9.0", preRelease = true)

        assertTrue(UpdateChannel.BETA.allows(misflagged))
        assertFalse(UpdateChannel.STABLE.allows(misflagged))
    }

    @Test
    fun `beta tag 忘勾预发行时测试版通道仍然收得到`() {
        // 判据看 tag 而不是那个手填的开关：忘勾一次的代价不该是
        // 「测试版通道什么都收不到，而且谁都没发现」。
        val missed = release("v0.9.0-beta.1", preRelease = false)

        assertTrue(UpdateChannel.BETA.allows(missed))
        assertFalse(UpdateChannel.STABLE.allows(missed))
    }
}
