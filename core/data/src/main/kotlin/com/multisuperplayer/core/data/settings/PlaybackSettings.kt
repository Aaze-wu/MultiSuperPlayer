package com.multisuperplayer.core.data.settings

/**
 * 播放内核相关的用户偏好。
 *
 * ## 为什么字段是 `Boolean?` 而不是 `Boolean`
 *
 * 沿用 [ThemeSettings] 的约定：可空 = 「用户从没设置过」，由消费者（这里是
 * `PlaybackController` 自己的默认值）决定初值。
 *
 * 这样做的意义在于：**默认值的唯一来源只有一处**。如果这里写成
 * `val forceSoftwareDecoding: Boolean = false`，数据层就有了一个副本，
 * 哪天内核把默认改成「硬件优先」之外的策略，这个副本不会跟着变，
 * 而症状是「全新安装的用户和升级上来的用户行为不一样」——极难排查。
 */
data class PlaybackSettings(
    /**
     * 是否强制改用 FFmpeg 软件解码。null = 没设置过（等价于 false，即让内核自己选）。
     *
     * ⚠️ 注意这个开关**不当**「让更多文件能放」用：内核默认就是
     * 「系统解码器优先，解不了/解失败自动换 FFmpeg」。它解决的是另一类问题——
     * 硬件解码器不报错，但画面花屏、变色、音画不同步。所以它默认关、且只对
     * 明确知道自己在做什么的用户有意义。
     */
    val forceSoftwareDecoding: Boolean? = null,
)
