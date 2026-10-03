package com.multisuperplayer.core.data.update

/**
 * 版本号（三段数字 + 可选预发行后缀），只服务于「检查更新」这一件事。
 *
 * ## 为什么不直接比 `versionCode`
 *
 * [versionCode] 确实和 `app/build.gradle.kts` 用同一套编码，但它**不足以判断
 * 「有没有更新」**：同一数值段的预发行版共用同一个 versionCode
 * （`v0.7.0-alpha.1` 与 `v0.7.0-alpha.2` 都是 700），而这两者恰恰必须能分出先后。
 * 拿 versionCode 比，alpha.2 永远不会被认成比 alpha.1 新——用户看着应用里写着
 * alpha.1，商店页写着 alpha.2，而「检查更新」说已是最新。
 *
 * 所以比较走完整的语义化版本规则；[versionCode] 只用来判断「是不是同一数值段」
 * 以及喂给界面上那些「给人看的数字」。
 *
 * ## 严格与宽容的边界
 *
 * **严格**（会拒绝解析）：数字段不是数字、预发行标识符为空（`0.7.0-alpha..1`）、
 * 悬空连字符（`0.7.0-`）。这些都会让顺序算错，而且错得看不出来。
 *
 * **宽容**（接受）：前导 `v`（`v0.7.0`）、两位（`0.7` 视为 `0.7.0`）、
 * `+build` 元数据（与比较无关，忽略）。这些只是写法不同，不影响先后。
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** `alpha.1` → `["alpha", "1"]`。正式版是**空列表**，不是 null。 */
    val preRelease: List<String>,
) : Comparable<UpdateVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    /** 与 `app/build.gradle.kts` 同一套编码，用于界面显示与「同数值段」判断。 */
    val versionCode: Int get() = major * 10_000 + minor * 100 + patch

    /** 去掉预发行后缀的 `x.y.z`。 */
    val core: String get() = "$major.$minor.$patch"

    override fun toString(): String =
        if (isPreRelease) "$core-${preRelease.joinToString(".")}" else core

    /**
     * 语义化版本 2.0 的比较规则。
     *
     * 1. 数字三段逐段比；
     * 2. 三段都相同时，**带预发行后缀的反而更小**（`1.0.0-alpha < 1.0.0`）。
     *    这一条是整件事的关键：漏掉它，「0.7.0-alpha.1 比 0.7.0 新」会被判成
     *    有更新，于是安装了正式版的用户被劝去装一个更早的预发行版；
     * 3. 都带后缀时逐标识符比：纯数字按数值比、含字母按 ASCII 比、
     *    **数字标识符比字母标识符小**，前缀短的更小（`alpha < alpha.1`）。
     */
    override fun compareTo(other: UpdateVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        private val NUMERIC = Regex("^[0-9]+$")

        /**
         * 解析 `v0.7.0`、`0.7.0`、`1.0.0-alpha.1`、`0.7`、`1.2.3+build.7`。
         *
         * 解析不出来返回 **null**，而不是抛异常：tag 是别人（或几个月后的你）
         * 随手写的，一个写错的 tag 不该让整次检查失败——那一条跳过就好。
         */
        fun parse(raw: String): UpdateVersion? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            // 只有紧跟着数字时才去掉 `v`：否则 `version` 这种词会被吃成 `ersion`，
            // 变成一个「看起来解析成功了」的垃圾版本号。
            val body = if (
                trimmed.length > 1 &&
                (trimmed[0] == 'v' || trimmed[0] == 'V') &&
                trimmed[1].isDigit()
            ) {
                trimmed.substring(1)
            } else {
                trimmed
            }

            // `+build.7` 只是元数据，按规范不参与比较。
            val withoutBuild = body.substringBefore('+')
            if (withoutBuild.isEmpty()) return null

            val corePart = withoutBuild.substringBefore('-')
            // 有连字符就必须有后缀：`0.7.0-` 多半是 tag 写错了，
            // 静默按正式版处理会把它排到所有预发行版前面（见 compareTo 的第 2 条）。
            val hasDash = withoutBuild.length > corePart.length
            val prePart = withoutBuild.substringAfter('-', "")
            if (hasDash && prePart.isEmpty()) return null

            val segments = corePart.split('.')
            // 只接受 x.y 或 x.y.z。一段（`0.7` 那种整数字）和四段都拒绝：
            // 前者是「这是版本号还是构建号」都说不清，后者本项目从未使用。
            if (segments.size !in 2..3) return null
            if (segments.any { !NUMERIC.matches(it) }) return null
            val numbers = segments.map { it.toIntOrNull() ?: return null }

            val pre = if (prePart.isEmpty()) {
                emptyList()
            } else {
                val identifiers = prePart.split('.')
                // 空标识符（`alpha..1`）会让「谁更长」这件事没有定义。
                if (identifiers.any { it.isEmpty() }) return null
                identifiers
            }

            return UpdateVersion(
                major = numbers[0],
                minor = numbers[1],
                patch = numbers.getOrElse(2) { 0 },
                preRelease = pre,
            )
        }

        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() && b.isEmpty()) return 0
            if (a.isEmpty()) return 1 // a 是正式版 ⇒ 更大
            if (b.isEmpty()) return -1

            val shared = minOf(a.size, b.size)
            for (index in 0 until shared) {
                val result = compareIdentifier(a[index], b[index])
                if (result != 0) return result
            }
            // 前缀相同则短的更小：`alpha < alpha.1`。
            return compareValues(a.size, b.size)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val left = a.toIntOrNull()
            val right = b.toIntOrNull()
            return when {
                left != null && right != null -> compareValues(left, right)
                left != null -> -1 // 数字标识符永远小于字母标识符
                right != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
