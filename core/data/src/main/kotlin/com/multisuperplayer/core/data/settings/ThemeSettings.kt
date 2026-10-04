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
    /**
     * 是否启用系统「莫奈取色」。null = 没设置过，默认值见
     * `MspThemeDefaults.USE_DYNAMIC_COLOR`（关）。
     *
     * 存成可空布尔而不是布尔，是为了让「用户手动关掉」和「从没设置过」保持
     * 两种状态：后者跟着默认值走，前者不跟。
     */
    val useDynamicColor: Boolean? = null,
    /**
     * 是否用当前封面取色。null = 没设置过，默认值见
     * `MspThemeDefaults.COLOR_FROM_ARTWORK`（关）。
     *
     * 开了它之后，[accentId] 和 [useDynamicColor] 都会被盖住（优先级见
     * `MspTheme`），但**两个字段仍然要原样保留**：用户关掉这个开关之后，
     * 应当回到他之前亲手选的强调色，而不是回到默认值。
     *
     * 用户「选中某个强调色」时，这个开关会被自动关掉——见
     * [ThemeSettingsRepository.selectAccent] 里为什么这与「打开封面取色」
     * 是不对称的。
     */
    val colorFromArtwork: Boolean? = null,
    /**
     * 用户自定义的强调色（三根滑块的位置）。null = 从没自定义过，此时强调色走
     * [accentId] 那个预设。
     *
     * 它与 [accentId] 是**互补**关系而不是覆盖关系，所以两个字段都要留着：
     * 自定义只影响「当前用哪个颜色」，一旦用户选择「回到预设强调色」，
     * 他之前选的预设还在（见 [ThemeSettingsRepository.clearCustomAccent]）。
     * 同理，用户「选中某个预设」时也只会删掉这个字段，另两个取色开关的处置
     * 与选中预设完全一致（见 [ThemeSettingsRepository.selectCustomAccent]）。
     *
     * 这里存的是三根滑块的位置而不是一个色值，理由见 [CustomAccent]。
     */
    val customAccent: CustomAccent? = null,
)
