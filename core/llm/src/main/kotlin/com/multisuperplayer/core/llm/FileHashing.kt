package com.multisuperplayer.core.llm

import java.io.File
import java.security.MessageDigest

/** 读文件算哈希时的缓冲区。256 KB：比 8 KB 少 30 倍的循环次数，又不至于占太多内存。 */
private const val HASH_BUFFER_BYTES = 256 * 1024

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * 算一个文件的 sha256，返回**小写**十六进制。
 *
 * 为什么是流式读而不是 `file.readBytes()`：模型文件 345 MB，一次性读进内存
 * 就是在给系统一个杀进程的理由（低端机上真会 OOM）。流式读的内存占用是常数。
 *
 * 为什么不用 `FileChannel` + `MappedByteBuffer`：映射 345 MB 会让整个进程的
 * 地址空间被这份数据占住，而它只在下载完成时用一次——为一个一次性的校验
 * 让 vm 多映射几百 MB 不划算，而且映射窗口的释放时机在 Android 上并不确定。
 *
 * ⚠️ 与 `core:asr` 的 `sha256HexOfFile` 是同一段逻辑的两份副本：那个是
 * `internal`，跨模块看不见；而两个模块之间只共享 `core:common` 与 `core:model`，
 * 为一个 20 行的纯函数把 `core:llm` 绑到 `core:asr`（连带它的 sherpa jar 与
 * jniLibs）上不划算。改动这里时记得两边一起看。
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

/** 字节数组转小写十六进制。手写而不是 `BigInteger(1, bytes).toString(16)`：后者**丢掉前导 0**，于是哈希长度会在 63 和 64 之间跳。 */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[value ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(out)
}
