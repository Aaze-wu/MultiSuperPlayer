package com.multisuperplayer.core.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 续播位置文件 `msp_positions`。
 *
 * ## 为什么不跟 `msp_settings` 共用一个文件
 *
 * [com.multisuperplayer.core.data.settings.mspSettingsStore] 的注释里立的规矩是
 * 「设置文件的数量由用户可见的设置分组决定」。续播位置**不是用户可见的设置**，
 * 而且有两条它自己特有的性质，让把它混进去变成一个坏主意：
 *
 * 1. **无界增长**（每看一部片子多一条），而设置文件是固定几十个键。
 * 2. **写得很频繁**（播放中每 5 秒一次），而设置只在用户拨开关时写。
 *
 * DataStore 的 `edit` 是「读整个文件 → 改 → 原子重写」。混在一起的话，播放中
 * 每 5 秒的重写会把用户所有设置（包括加密后的 API Key 密文）一起搬一遍；
 * 更糟的是，一个损坏的续播记录会让**设置**一起读不出来——而设置读不出来的
 * 表现是「主题、字幕、翻译配置全丢了」。
 *
 * ## 为什么这不算「第二个 DataStore 实例」
 *
 * 那个 `IllegalStateException: There are multiple DataStores active for the same file`
 * 只针对同一个 `name`。这里是**不同的文件**，所以完全合法——前提是全项目
 * 只有这一处声明 `name = "msp_positions"`。
 */
internal val Context.mspPositionsStore: DataStore<Preferences> by preferencesDataStore(
    name = "msp_positions",
)

/**
 * 存储键名的前缀。
 *
 * 每个媒体一个键：`resume.<媒体 id>`。用「一个键一个媒体」而不是「一个大字符串
 * 装下所有记录」，是为了让一次写入只影响一个条目——存成一个大 JSON 的话，
 * 每次 `edit` 都要把几百条记录重新序列化一遍，并且任何一条损坏都会让**全部**
 * 记录读不出来。
 */
internal const val RESUME_KEY_PREFIX = "resume."
