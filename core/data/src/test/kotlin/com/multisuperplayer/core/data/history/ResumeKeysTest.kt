package com.multisuperplayer.core.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ResumeKeys` 的测试。
 *
 * 这个对象只做两件事：判一个键属不属于续播记录表、从键名里取出媒体 id。
 * 值得单独测是因为**删错了不可逆**：
 *
 * - 少认一个键（前缀写短、id 判空漏了）→ 用户点了「清空」之后磁盘上还剩记录，
 *   而界面上是空的，于是那条记录会在下一次拿到权限时自己冒回来。
 * - 多认一个键 → 清空会顺手删掉本表之外的键。
 *
 * 这两种都是「运行时不报错、看不出来」的错，只能靠这里钉住形状。
 */
class ResumeKeysTest {

    @Test
    fun `resume 开头的键是本表记录`() {
        assertTrue(ResumeKeys.isResumeKey("resume.content://media/audio/34"))
        assertTrue(ResumeKeys.isResumeKey("resume.x"))
    }

    @Test
    fun `前缀只差一个字符的键不算本表记录`() {
        // 删错不可逆，所以这里宁可窄：把 "resumex.a" 认成记录，
        // 「清空最近播放」就会删掉一个它根本不认识的键。
        assertFalse(ResumeKeys.isResumeKey("resumex.a"))
        assertFalse(ResumeKeys.isResumeKey("resume"))
        assertFalse(ResumeKeys.isResumeKey("resume-extra.a"))
    }

    @Test
    fun `别的键一个都不算`() {
        assertFalse(ResumeKeys.isResumeKey("theme.mode"))
        assertFalse(ResumeKeys.isResumeKey(""))
        assertFalse(ResumeKeys.isResumeKey("Resume.a"))
    }

    @Test
    fun `id 里的点和冒号不影响取值`() {
        // 媒体 id 是 content:// / file:/ 开头的 uri，里面全是冒号和点。
        // 用 split 或者按第一个分隔符切会在这里出错。
        assertEquals(
            "content://media/external/audio/media/34",
            ResumeKeys.mediaIdOf("resume.content://media/external/audio/media/34"),
        )
        assertEquals("file:/storage/1/2 首歌.mp3", ResumeKeys.mediaIdOf("resume.file:/storage/1/2 首歌.mp3"))
    }

    @Test
    fun `id 是空串的键算坏值`() {
        // `resume.` 这种键读出来是一条没有名字的记录，列在界面上就是一行空白。
        assertTrue(ResumeKeys.isResumeKey("resume."))
        assertNull(ResumeKeys.mediaIdOf("resume."))
    }

    @Test
    fun `不是本表记录就取不出 id`() {
        assertNull(ResumeKeys.mediaIdOf("resumex.a"))
        assertNull(ResumeKeys.mediaIdOf("theme.mode"))
    }
}
