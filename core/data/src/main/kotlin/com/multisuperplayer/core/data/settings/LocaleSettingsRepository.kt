package com.multisuperplayer.core.data.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 语言设置仓库。
 *
 * 它只是 [AppLocaleStore] 的一层薄包装，存在的唯一理由是**让 `ViewModel` 能用到它**：
 * `AppLocaleStore` 的每个方法都要 `Context`，而 `ViewModel` 拿到 `Context` 是个
 * 反模式（配置变更、进程重建之后它可能已经过期）。这里把 `Context` 收在构造参数里，
 * 对外只暴露「值」和「写」两件事。
 *
 * 刻意**不加** `dispatchers`：SharedPreferences 的读是内存里的同步读，
 * 写也只是 apply() 到内存队列，包一层 IO 调度器只会让「写完了吗」更难回答。
 */
class LocaleSettingsRepository(private val context: Context) {

    /** 当前语言。33 以上由系统重建应用，所以这个 Flow 只发一次也是对的。 */
    val language: Flow<AppLanguage> = AppLocaleStore.observe(context)

    /**
     * 写入选择。
     *
     * 33 以上写进系统之后由系统重建 Activity；低于 33 调用方要自己重建，
     * 判断读 [requiresManualRecreate]，别在界面里重写版本比较。
     */
    fun setLanguage(language: AppLanguage) {
        AppLocaleStore.write(context, language)
    }

    /** 写完语言后，界面要不要自己调用 `Activity.recreate()`。 */
    val requiresManualRecreate: Boolean get() = AppLocaleStore.requiresManualRecreate()
}
