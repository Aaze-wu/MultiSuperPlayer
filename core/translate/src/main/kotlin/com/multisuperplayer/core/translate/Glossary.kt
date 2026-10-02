package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 术语表：源词 → 指定译法。
 *
 * ## 为什么只有「一个 Map」，却能表达两件事
 *
 * 用户实际想要的是两种东西：
 * 1. **人名/专有名词不要翻译**（`桐人` 保持 `桐人`，`Excalibur` 保持 `Excalibur`）；
 * 2. **固定译法**（`Guild` 一律译成「公会」，不要一会儿「行会」一会儿「协会」）。
 *
 * 这两种需求对管线的要求完全不同，所以不能用一个标志位混着存：
 * - 「不翻译」必须**确定性生效**，不能交给模型去判断（它会自己发挥）⇒ 走哨兵保护；
 * - 「固定译法」只是**提示词里的偏好**，交给模型执行更合适（同义形式、变位、
 *   语境里的语序调整模型比字符串替换做得好）⇒ 只写进 system prompt。
 *
 * 于是约定：`value` 为空 **或** 与 `key` 完全相同 ⇒ 「不翻译」；
 * 否则 ⇒ 「固定译法」。这个约定在界面上直接显示成「不翻译 / 译成 …」，用户不用理解内部机制。
 */
typealias Glossary = Map<String, String>

/** 需要哨兵保护的词（不翻译、保持原样）。 */
fun Glossary.doNotTranslateTerms(): List<String> = entries
    .filter { it.key.isNotBlank() && (it.value.isBlank() || it.value == it.key) }
    .map { it.key.trim() }
    .distinct()

/** 固定译法（写进提示词）。 */
fun Glossary.fixedTranslations(): List<Pair<String, String>> = entries
    .filter { it.key.isNotBlank() && it.value.isNotBlank() && it.value != it.key }
    .map { it.key.trim() to it.value.trim() }
    .distinctBy { it.first }

/**
 * 术语表的指纹，参与缓存 key。
 *
 * 少了这一步就会出现「用户加了术语表、重新翻译，结果全走缓存、一条都没变」——
 * 界面上看起来像按钮坏了。所以术语表一变，缓存 key 就必须变。
 */
internal fun Glossary.fingerprint(): String {
    if (isEmpty()) return "none"
    val canonical = entries
        .filter { it.key.isNotBlank() }
        .map { it.key.trim() to it.value.trim() }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("\u0001") { "${it.first}\u0002${it.second}" }
    return sha256HexShort(canonical, 12)
}

/**
 * 落盘用的 JSON 文本（设置里存成字符串，避免 DataStore 里塞字符串集合并丢掉顺序）。
 *
 * 公开给 `core:data` 的设置仓库用：术语表是用户设置的一部分，编码规则必须只有一份，
 * 否则「界面存进去的」和「引擎读出来的」迟早对不上（表现为术语表静默失效）。
 */
fun encodeGlossary(glossary: Glossary): String =
    JsonObject(glossary.entries.associate { it.key to JsonPrimitive(it.value) }).toString()

/** 读回来。任何一项坏掉都只丢那一项，不丢整张表。 */
fun decodeGlossary(text: String?): Glossary {
    val json = text?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val obj = TranslationJson.parseObjectOrNull(json) ?: return emptyMap()
    return obj.entries
        .mapNotNull { (key, value) -> value.rawStringOrNull()?.let { key to it } }
        .toMap()
}
