package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 媒体库的 UI 状态。
 *
 * ## 「空」和「没权限」必须是两个状态
 *
 * 这是刻意做的一处区分。把两者都表示成 `List<MediaEntry>` 的空列表，
 * 用户看到的就是「还没有扫描到媒体」——而真正的原因是他从没被问过权限，
 * 于是他会一直等，或者以为应用坏了。**同一句话不能同时表示两种原因**，
 * 所以这里让「需要授权」成为一个独立分支，UI 才能给出一个按钮而不是一句安慰。
 */
sealed interface MediaLibraryState {

    /** 第一次扫描尚未结束。 */
    data object Loading : MediaLibraryState

    /**
     * 扫描完成。
     *
     * @param partial 只有部分权限（例如允许了「音乐」但拒绝了「视频」，或
     *   Android 14 的「仅选择部分照片/视频」）。此时列表是**残缺的**，
     *   UI 需要提示「可能还有内容没显示」，否则用户会以为文件丢了。
     */
    data class Ready(val entries: List<MediaEntry>, val partial: Boolean = false) : MediaLibraryState

    /** 一个权限都没有，列表无法扫描。 */
    data object NeedsPermission : MediaLibraryState

    /** 扫描本身失败（数据库异常等）。 */
    data class Error(val message: String) : MediaLibraryState
}
