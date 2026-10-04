package com.multisuperplayer.feature.player

import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelProgress
import com.multisuperplayer.core.asr.AsrProgress
import com.multisuperplayer.core.asr.AsrService
import com.multisuperplayer.core.model.text.MspText

/**
 * 「生成字幕」（语音识别）在界面上要表达的状态。
 *
 * ## 为什么进度可以缺
 *
 * 两个阶段的前几步都拿不到可算的分母：
 *
 * - 下载前要先问「缺哪些文件」，[AsrModelProgress] 在第一个文件开始写之前没有值；
 * - 识别前要先探测音轨、加载模型（几秒），[AsrProgress] 在音轨探测出总时长之前没有值。
 *
 * 与其填一个假 `0`（进度条会先显示 0% 再跳，而且用户会以为卡住了），
 * 不如让进度是 `null`，界面画不确定态进度条。
 *
 * ## 为什么失败要留在状态里，而不是弹一次提示
 *
 * 识别失败的原因（没人说话 / 音频解不开 / 模型没下好 / 存储空间不足）**下一步动作
 * 各不相同**，用户需要读完整句话。弹一下就没的提示在手机上经常只被余光扫到，
 * 而这里失败的是「刚才那几分钟的等待」。所以失败一直留到用户开始下一次或主动关掉。
 */
sealed interface AsrUiState {

    /**
     * 没在跑。
     *
     * ## 为什么拆成两条形状，而不是「模型 + installed + 可空的云端服务」
     *
     * 合并成一条会让「云端 + `installed = false`」变成**能构造出来**的状态，而界面
     * 会老老实实把它画成「下载模型并生成（78.1 MB）」——一个按下去什么都
     * 不会下的按钮。云端根本不存在「模型要下多少」这回事，所以那个字段也不该存在。
     */
    sealed interface Idle : AsrUiState {

        /**
         * 本机识别。
         *
         * [installed] 决定按钮是「生成字幕」还是「下载模型并生成」——模型没下好时
         * 必须先让用户看见**要下多少字节**，否则就是在他没同意的情况下用流量下几十上百 MB。
         */
        data class OnDevice(val model: AsrModelInfo, val installed: Boolean) : Idle

        /**
         * 云端识别。
         *
         * [addressReady] 为假 = 连一个合法地址都还不是（自建/中转站刚选上、还没填完）。
         * 按钮据此变灰，而**不**让用户按下去再收一个「连不上」——同一个判定
         * （`AsrSettings.cloudAddressLooksValid`）与开连接前那道 `checkEndpointUsable` 一致。
         */
        data class Cloud(val service: AsrService, val addressReady: Boolean) : Idle
    }

    /** 正在下模型。[progress] 为 null = 还没开始写第一个文件。 */
    data class Downloading(val model: AsrModelInfo, val progress: AsrModelProgress? = null) :
        AsrUiState

    /** 正在识别。[progress] 为 null = 还在探测音轨 / 加载模型。 */
    data class Transcribing(val progress: AsrProgress? = null) : AsrUiState

    /** 上一次失败，[message] 已经是给人看的一句话。 */
    data class Failed(val message: MspText) : AsrUiState

    /** 正在跑（下模型或识别）。按钮据此变成「停止」。 */
    val isRunning: Boolean get() = this is Downloading || this is Transcribing
}
