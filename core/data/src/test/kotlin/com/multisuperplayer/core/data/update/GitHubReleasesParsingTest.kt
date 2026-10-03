package com.multisuperplayer.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub `/releases` 响应的解析。
 *
 * 用一份**从真实响应里抄下来**的骨架做样本（字段名照抄，值改小）：真机上要试这一段
 * 得恰好有一个新版本发布，所以它是整条链路里最难现场复现、也最该被钉死的一环。
 */
class GitHubReleasesParsingTest {

    @Test
    fun `解析真实形态的一条发行版`() {
        val json = """
            [
              {
                "tag_name": "v0.6.8-alpha.1",
                "prerelease": true,
                "draft": false,
                "published_at": "2026-10-03T15:04:50Z",
                "body": "### 后台保活",
                "assets": [
                  {
                    "name": "app-release.apk",
                    "size": 141371221,
                    "content_type": "application/vnd.android.package-archive",
                    "digest": "sha256:442d6bbe964e453294275eaf96204c8da549cae0fcde9368a88e7aecb6907753",
                    "browser_download_url": "https://github.com/Aaze-wu/MultiSuperPlayer/releases/download/v0.6.8-alpha.1/app-release.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val releases = parseGitHubReleases(json)

        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals("v0.6.8-alpha.1", release.tagName)
        assertEquals(608, release.version.versionCode)
        assertTrue(release.isPreRelease)
        assertEquals(141371221L, release.apkSizeBytes)
        assertEquals(
            "442d6bbe964e453294275eaf96204c8da549cae0fcde9368a88e7aecb6907753",
            release.apkSha256,
        )
        assertTrue(release.isInstallable)
        assertEquals(
            java.time.Instant.parse("2026-10-03T15:04:50Z").toEpochMilli(),
            release.publishedAtEpochMs,
        )
    }

    @Test
    fun `草稿不出现在候选里`() {
        val json = """[{"tag_name":"v0.9.0","draft":true,"assets":[]}]"""
        assertEquals(emptyList(), parseGitHubReleases(json))
    }

    @Test
    fun `跳过解析不了 tag 的那一条而不是整次失败`() {
        // 仓库里只要有人发过一个奇怪的 tag，这一条就不能让整个功能永远坏掉。
        val json = """
            [
              {"tag_name": "nightly", "assets": []},
              {"tag_name": "v0.7.0", "assets": [
                {"name":"app-release.apk","browser_download_url":"https://example.invalid/a.apk","size":10}
              ]}
            ]
        """.trimIndent()

        val releases = parseGitHubReleases(json)
        assertEquals(1, releases.size)
        assertEquals("v0.7.0", releases.single().tagName)
    }

    @Test
    fun `挑 APK 看文件名后缀而不是 content_type`() {
        // content_type 取决于上传方式（curl 上传会变成 octet-stream），
        // 按类型找会在换一种上传方式后突然找不到，而表现是「有新版本但没有下载地址」。
        val json = """
            [{"tag_name":"v0.7.0","assets":[
              {"name":"checksums.txt","browser_download_url":"https://example.invalid/c.txt","size":5},
              {"name":"app-release.apk","browser_download_url":"https://example.invalid/app.apk","size":42}
            ]}]
        """.trimIndent()

        val release = parseGitHubReleases(json).single()
        assertEquals("https://example.invalid/app.apk", release.apkUrl)
        assertEquals(42L, release.apkSizeBytes)
    }

    @Test
    fun `没有 APK 的发行版仍然被解析出来但不可安装`() {
        // 不能在这一层丢掉它：界面上「这一版没有安装包」和「这一版不存在」是两句话。
        val json = """[{"tag_name":"v0.7.0","assets":[]}]"""
        val release = parseGitHubReleases(json).single()
        assertNull(release.apkUrl)
        assertEquals(false, release.isInstallable)
    }

    @Test
    fun `顶层结构不对时返回空列表而不是抛异常`() {
        assertEquals(emptyList(), parseGitHubReleases("{}"))
        assertEquals(emptyList(), parseGitHubReleases("not json"))
        assertEquals(emptyList(), parseGitHubReleases(""))
    }

    @Test
    fun `digest 缺冒号前缀时按整串处理`() {
        // GitHub 现在给的是 `sha256:hex`，但旧响应里这个字段根本不存在；
        // 解析成 null 就跳过校验，而不是拿一个假的十六进制串去比。
        val withPrefix = """
            [{"tag_name":"v0.7.0","assets":[
              {"name":"a.apk","browser_download_url":"https://example.invalid/a.apk","digest":"sha256:ABC"}
            ]}]
        """.trimIndent()
        assertEquals("ABC", parseGitHubReleases(withPrefix).single().apkSha256)

        val withoutDigest = """
            [{"tag_name":"v0.7.0","assets":[
              {"name":"a.apk","browser_download_url":"https://example.invalid/a.apk"}
            ]}]
        """.trimIndent()
        assertNull(parseGitHubReleases(withoutDigest).single().apkSha256)
    }

    @Test
    fun `时间戳解析失败只让时间变成 null`() {
        assertNull(parseInstantMs("上个月"))
        assertTrue(requireNotNull(parseInstantMs("2026-10-03T15:04:50Z")) > 0)
    }
}
