package com.multisuperplayer.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `预发行通道也接受正式版`() {
        // 装 alpha 的用户必须能收到正式版，否则他永远退不出预览通道。
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.7.0")),
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `同一个版本号重复出现不算更新`() {
        val result = UpdateRules.decide(
            current = current,
            releases = listOf(release("v0.6.8-alpha.1", preRelease = true)),
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
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
            channel = UpdateChannel.PRERELEASE,
            ignoredTag = null,
        )
        assertEquals(UpdateAvailability.UpToDate, result)
    }

    @Test
    fun `默认通道跟着当前装的那一路走`() {
        assertTrue(UpdateRules.defaultChannelFor(true) == UpdateChannel.PRERELEASE)
        assertTrue(UpdateRules.defaultChannelFor(false) == UpdateChannel.STABLE)
    }
}
