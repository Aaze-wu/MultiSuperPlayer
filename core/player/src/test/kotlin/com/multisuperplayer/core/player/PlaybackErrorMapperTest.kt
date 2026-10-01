package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 错误文案是「用户能不能自己解决问题」的唯一线索，所以要锁住三件事：
 * 不同原因不能共用一套文案、文案里要有可执行的建议、未知错误码不能丢信息。
 */
class PlaybackErrorMapperTest {

    @Test
    fun `文件不存在给出可理解的说明`() {
        val text = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertTrue(text.contains("找不到"), "实际：$text")
    }

    @Test
    fun `权限问题提示重新授权而不是重试`() {
        val text = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertTrue(text.contains("权限"), "实际：$text")
        assertTrue(text.contains("重新选择"), "权限问题应当给出可执行的动作，实际：$text")
    }

    @Test
    fun `解码器缺失的文案必须指出下一步动作`() {
        // 用户的第一诉求是「播放所有格式」，这条错误会是最常见的。
        // 只写「播放失败」等于没写，所以断言它点名了 FFmpeg。
        val text = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        assertTrue(text.contains("解码器"), "实际：$text")
        assertTrue(text.contains("FFmpeg"), "应当给出可执行的建议，实际：$text")
    }

    @Test
    fun `不同失败原因不能共用同一句文案`() {
        val format = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val network = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val permission = PlaybackErrorMapper.describe(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertEquals(3, setOf(format, network, permission).size, "三类错误必须给出三种不同文案")
    }

    @Test
    fun `格式问题不提供重试网络类问题才提供`() {
        assertTrue(PlaybackErrorMapper.isFormatProblem(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))

        assertTrue(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(PlaybackErrorMapper.isFormatProblem(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))

        // 本地文件找不到：既不是格式问题，重试也没用。
        assertFalse(PlaybackErrorMapper.isRetryable(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun `未知错误码仍保留原始错误码`() {
        val text = PlaybackErrorMapper.describe(errorCode = 987_654)
        assertTrue(text.contains("播放失败"), "实际：$text")
        assertTrue(text.contains("987654"), "未知错误必须保留错误码供用户反馈，实际：$text")
    }

    @Test
    fun `具体原因会追加到文案里`() {
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            causeName = "UnknownHostException",
        )
        assertTrue(text.contains("UnknownHostException"), "实际：$text")
    }

    @Test
    fun `没有信息量的通用原因不追加`() {
        // cause 就是 PlaybackException 本身时，追加只会让文案更啰嗦。
        val text = PlaybackErrorMapper.describe(
            errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            causeName = "androidx.media3.common.PlaybackException",
        )
        assertFalse(text.contains("androidx.media3"), "实际：$text")
    }
}
