package com.multisuperplayer.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全局设置文件 `msp_settings`。
 *
 * **必须是顶层属性，而且整个应用只能有这一处声明。**
 *
 * `preferencesDataStore` 保证「同一个 name 只创建一个实例」，但这份保证靠的是这个
 * 委托自己持有的一份静态缓存。在另一个文件里再写一遍
 * `preferencesDataStore(name = "msp_settings")` 会得到**第二个**实例，DataStore
 * 会直接抛：
 *
 * ```
 * IllegalStateException: There are multiple DataStores active for the same file
 * ```
 *
 * 这个异常发生在第一次读取设置的时候，也就是应用启动后几秒内——不会在编译期暴露，
 * 也不会在写这段代码的人手里出现（他要建第二个仓库时才会）。所以把声明单独放在
 * 这里，让「第二个实例」这件事在 code review 里一眼可见。
 *
 * 于是约定是：**设置文件的数量由「用户可见的设置分组」决定，而不是由仓库数量决定。**
 * 主题、字幕、播放各自一个仓库，共用这一个文件。
 */
internal val Context.mspSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "msp_settings",
)
