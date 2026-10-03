package com.multisuperplayer.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Context 上找到真正的 Activity。
 *
 * Compose 给的 `LocalContext` 可能是包了好几层的 `ContextWrapper`（主题包装、
 * `ContextThemeWrapper`、`ComponentActivity` 自己的包装），一层 `as? Activity`
 * 会静默地拿到 null——症状就是「切了语言没反应」，但不报错。
 *
 * 曾经它是 `AppearanceSettingsScreen.kt` 里的 `private` 函数，只有「切语言后重建」
 * 用得上。权限页需要**更多**：`shouldShowRequestPermissionRationale` 只有 `Activity`
 * 有，而它决定了「被拒过一次」和「已被永久拒绝」要不要分开说——那是一句会引导用户
 * 去别的地方的话，不能因为没有 Activity 就退化成前后矛盾的文案。
 * 两个调用方各有各的理由，但要做的是同一件事（拿到那个 Activity），
 * 所以放在这里共用，而不是各写一份。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
