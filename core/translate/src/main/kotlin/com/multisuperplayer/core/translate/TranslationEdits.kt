package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private const val TAG = "TranslationEdits"

/**
 * 人工逐句修正的 key。
 *
 * ## 为什么 key 里要有「原文摘要」而不是只用 cue 序号
 *
 * 用户换了一条字幕文件（同一部片子重下了个更好的版本），行数可能差几行——
 * 此时按序号复用会**把 corrections 整体错位一句**，而且错得毫无声响：
 * 每条都还在、每条都合法，只是全都串行了。加上原文摘要之后，
 * 对不上的条目自然失效，退回模型译文，这比错位好得多。
 */
object TranslationEditsCodec {

    fun key(cueIndex: Int, sourceText: String): String =
        "$cueIndex:${sha256HexShort(sourceText.trim(), 8)}"

    fun encode(edits: Map<String, String>): String = JsonObject(
        edits.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .associate { it.key to JsonPrimitive(it.value) },
    ).toString()

    /** 坏内容一律当成「没有人工修正」，不抛异常：这不该阻断播放。 */
    fun decode(text: String?): Map<String, String> {
        val json = text?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val obj = TranslationJson.parseObjectOrNull(json) ?: return emptyMap()
        return obj.entries
            .mapNotNull { (key, value) ->
                value.rawStringOrNull()?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()
    }
}

/**
 * 每部片子的「人工修正译文」存储。
 *
 * 放在单独的小文件里、而不是塞进 DataStore：一部两小时的片子可能有几百条修正，
 * 全放进一个 Preferences 文件会让**每次设置读取**（包括播放页订阅的设置流）
 * 都把这些文本序列化一遍。
 *
 * ## 为什么清空修正时**不删除文件**
 *
 * 「空 Map」有两种来源：用户真的删光了修正，和某个调用方传错了参数。
 * 如果把它们都当成「删除文件」，第二种情况就是一次静默的数据丢失。
 * 所以这里只会写 `{}`，永不删文件——几十字节的代价换掉一整类风险。
 */
class TranslationEditsStore(
    private val directory: File,
    private val dispatchers: DispatcherProvider,
) {

    private val mutex = Mutex()

    suspend fun load(mediaKey: String): Map<String, String> = withContext(dispatchers.io) {
        mutex.withLock {
            val file = fileFor(mediaKey)
            if (!file.exists()) return@withLock emptyMap()
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                MspLog.w(TAG, it) { "读人工修正失败：${file.name}" }
                return@withLock emptyMap()
            }
            TranslationEditsCodec.decode(text)
        }
    }

    suspend fun save(mediaKey: String, edits: Map<String, String>) = withContext(dispatchers.io) {
        mutex.withLock {
            val file = fileFor(mediaKey)
            runCatching {
                directory.mkdirs()
                val temp = File(directory, file.name + ".tmp")
                temp.writeText(TranslationEditsCodec.encode(edits), Charsets.UTF_8)
                // 先写临时文件再改名：写一半被系统杀掉也不会留下半个 JSON。
                if (file.exists()) file.delete()
                if (!temp.renameTo(file)) {
                    throw IllegalStateException("改名失败：${temp.name} → ${file.name}")
                }
            }.onFailure {
                MspLog.w(TAG, it) { "保存人工修正失败：${file.name}" }
            }
        }
        Unit
    }

    internal fun fileFor(mediaKey: String): File = File(directory, "$mediaKey.json")

    companion object {
        const val DIRECTORY_NAME = "translation_edits"
    }
}

/** 把媒体 URI 变成一个安全的文件名。 */
fun translationMediaKey(mediaUri: String?): String =
    sha256HexShort(mediaUri.orEmpty(), 16)
