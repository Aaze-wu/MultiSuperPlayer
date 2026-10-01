package com.multisuperplayer.core.data.settings

/**
 * 主题相关的用户偏好。
 *
 * ## 为什么这里存的是「字符串 id」和「可空布尔」而不是枚举
 *
 * 枚举（`MspBaseTheme` / `MspAccent`）定义在 `:core:ui` 里，因为它们同时携带
 * 颜色值——那是纯 UI 的东西。如果让 `:core:data` 直接引用它们，数据层就反向
 * 依赖了界面层，以后想换掉 Compose 都得跟着改数据层。
 *
 * 所以数据层只存**不透明的字符串 id**，怎么解释由 UI 层决定。
 *
 * 字段可空 = 「用户从没设置过」，由 UI 层填自己的默认值。这样**默认值的唯一来源
 * 是 UI 层那个枚举自己的 `DEFAULT`**，数据层不需要知道「默认是靛蓝」——
 * 否则一旦枚举改了 id，数据层这份字面量就会悄悄过期，表现为「全新安装的用户
 * 主题不是默认主题」。
 *
 * 反过来，`false` 一定是「用户手动关掉了」，不会被当成「没设置过」。
 */
data class ThemeSettings(
    /** @see com.multisuperplayer.core.ui.theme.MspBaseTheme.id */
    val baseThemeId: String? = null,
    /** @see com.multisuperplayer.core.ui.theme.MspAccent.id */
    val accentId: String? = null,
    /** 是否启用系统「莫奈取色」。null = 没设置过。 */
    val useDynamicColor: Boolean? = null,
    /**
     * 是否用当前封面取色。null = 没设置过。
     *
     * 开了它之后，[accentId] 和 [useDynamicColor] 都会被盖住（优先级见
     * `MspTheme`），但**两个字段仍然要原样保留**：用户关掉这个开关之后，
     * 应当回到他之前亲手选的强调色，而不是回到默认值。
     */
    val colorFromArtwork: Boolean? = null,
)
