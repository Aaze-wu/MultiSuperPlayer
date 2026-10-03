package com.multisuperplayer.core.asr

import java.io.File
import java.security.MessageDigest

/**
 * 算文件的 sha256。
 *
 * **流式**、不把文件读进内存：这里要校验的两个模型文件是 181 MB 和 81 MB，
 * 而 `File.readBytes()` 在低内存设备上直接就是一次 `OutOfMemoryError`——
 * 那个崩溃发生在「下载完 190 MB 之后」，用户看到的会是「下载完成了但应用重启了」。
 *
 * 用 `inputStream()` 而不是 `FileChannel` + `MappedByteBuffer`：模型放在应用私有目录，
 * 但哪怕是私有目录，映射一个大文件也会占住地址空间，而这里每秒读几十 MB 的顺序读取
 * 完全不是瓶颈（实测 181 MB 约 0.3 s）。
 */
internal fun sha256HexOfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

/** 摘要转小写十六进制，和 [AsrModelFile.sha256] 的形式一致。 */
internal fun ByteArray.toHex(): String {
    val hex = HEX_DIGITS
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(hex[value ushr 4])
            append(hex[value and 0x0F])
        }
    }
}

private const val HASH_BUFFER_BYTES = 256 * 1024

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
