package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.text.MspText

/**
 * 语音识别走哪条路：本机还是云端。
 *
 * ## 为什么缺省是 [ON_DEVICE]，而且不该改成云端
 *
 * 这是一个**隐私开关**，不是性能开关。默认云端意味着：用户什么都没选，一段私人录音
 * 就已经被上传到某家公司的服务器上了——那是一次用户没有做过的决定。而反过来选错的
 * 代价很小（本机识别慢、要下模型，用户自己会去切）。
 *
 * 所以：[CLOUD] 只能由用户在设置页主动选出来，任何「检测到有密钥就自动切云端」的
 * 逻辑都不能加。
 *
 * ## 为什么存字符串 id 而不是序号 / 布尔值
 *
 * 存 `Boolean` 的话，「以后想加第三条路（比如局域网上的自建服务）」就要迁移一次数据；
 * 存序号则会在枚举里插一项时整体错位。字符串 id 是这三种里唯一能在加值时不迁移的，
 * 也让人能直接看懂盘上是什么。
 *
 * @param id 持久化契约，改名等于把所有人已选的路清空（旧值读不出来 ⇒ 回默认 = 本机）。
 * @param displayName 给用户看的名字（要跟语言变，所以是 [MspText.Res]）。
 */
enum class AsrRoute(val id: String, val displayName: MspText) {
    ON_DEVICE("on_device", MspText.Res(R.string.msp_asr_route_on_device)),
    CLOUD("cloud", MspText.Res(R.string.msp_asr_route_cloud)),
    ;

    companion object {
        /** 没选过时用本机。见类注释：这条默认值是隐私口径，不是偏好。 */
        val DEFAULT: AsrRoute = ON_DEVICE

        /**
         * 认不出来一律回 [DEFAULT]，绝不抛异常。
         *
         * 老版本留下的值、被手工改过的配置、以后删掉的路——读取侧全都要能活下来。
         * 回落到「本机」还有一个额外好处：坏值是**不会上传任何东西**的那一边。
         *
         * ⚠️ 刻意**不做 trim / 忽略大小写**（与 `AsrModelCatalog.byId` 的宽松口径不同）：
         * 那一边宽松点只是「模型选错了」，用户能看出来；这一边宽松点意味着
         * `"cloud "` 也被当成云端 ⇒ 一个多出来的空格就把音频送出去了。写入口
         * （`AsrSettingsRepository.setRoute`）会把值先归一化，所以盘上不会出现这种值；
         * 真出现了就当成坏值，退回不上传的那一边。
         */
        fun byId(raw: String?): AsrRoute = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}
