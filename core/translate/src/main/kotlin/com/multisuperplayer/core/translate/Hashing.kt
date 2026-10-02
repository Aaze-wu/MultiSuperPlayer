package com.multisuperplayer.core.translate

import java.security.MessageDigest

/**
 * SHA-256 十六进制摘要。
 *
 * 用它做缓存 key，而不是用原文本身当 key：原文可能跨很多行、含换行与特殊字符，
 * 作为 JSONL 里的字段既不省空间也不好比对。摘要还有个附带好处——
 * 缓存文件里不会留下整句台词（用户可能在意这个）。
 */
internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val v = byte.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

/** 短摘要，用于「cue 序号 + 原文摘要」这种要人眼看的场合。 */
internal fun sha256HexShort(value: String, chars: Int = 8): String = sha256Hex(value).take(chars)
