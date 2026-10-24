package com.hark.shiguang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-24 要求继续优化：飞牛转码会话不漏发 media.quit。 */
class FnTvSessionsTest {
    @Test fun `正常开会话，关播放器时 quit 它`() {
        val s = FnTvSessions()
        val (t, old) = s.begin()
        assertNull(old)
        assertTrue(s.opened(t, "L1"))
        assertEquals("L1", s.stop())
        assertNull(s.stop())   // 不会重复 quit
    }

    @Test fun `换档时先 quit 旧会话`() {
        val s = FnTvSessions()
        val (t1, _) = s.begin(); s.opened(t1, "L1")
        val (t2, old) = s.begin()
        assertEquals("L1", old)
        assertTrue(s.opened(t2, "L2"))
        assertEquals("L2", s.link)
    }

    @Test fun `会话还在开的时候退出播放器，开好后要立刻 quit`() {
        val s = FnTvSessions()
        val (t, _) = s.begin()
        assertNull(s.stop())             // 退出时还不知道 play_link
        assertFalse(s.opened(t, "L1"))   // 回来了：票已作废 → 调用方 quit L1
        assertNull(s.link)
        assertNull(s.stop())
    }

    @Test fun `上一次开会话的结果晚到，不会覆盖新会话`() {
        val s = FnTvSessions()
        val (t1, _) = s.begin()
        s.stop()
        val (t2, _) = s.begin()
        assertTrue(s.opened(t2, "L2"))
        assertFalse(s.opened(t1, "L1"))
        assertEquals("L2", s.link)
    }
}
