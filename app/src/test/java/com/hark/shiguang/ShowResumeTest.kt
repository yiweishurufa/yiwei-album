package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowResumeTest {
    private val eps = listOf("s1e1", "s1e2", "s1e3")
    private fun prog(vararg p: Pair<String, Progress>): (String) -> Progress? = p.toMap()::get

    @Test fun `没看过从第一集开始`() {
        val r = ShowResume.pick(eps, prog(), null)!!
        assertEquals("s1e1", r.path); assertNull(r.progress); assertFalse(r.finished)
    }

    @Test fun `记下的那一集看到一半就从那里续播`() {
        val p2 = Progress(600_000, 2_400_000, 10)
        val r = ShowResume.pick(eps, prog("s1e1" to Progress(2_400_000, 2_400_000, 50), "s1e2" to p2), "s1e2")!!
        assertEquals("s1e2", r.path); assertEquals(p2, r.progress)
    }

    @Test fun `记下的那一集看完了就播下一集`() {
        val r = ShowResume.pick(eps, prog("s1e2" to Progress(2_300_000, 2_400_000, 10)), "s1e2")!!
        assertEquals("s1e3", r.path); assertNull(r.progress)
    }

    @Test fun `下一集之前看过一半也接着那里`() {
        val p3 = Progress(100_000, 2_400_000, 5)
        val r = ShowResume.pick(eps, prog("s1e2" to Progress(2_400_000, 2_400_000, 10), "s1e3" to p3), "s1e2")!!
        assertEquals("s1e3", r.path); assertEquals(p3, r.progress)
    }

    @Test fun `最后一集看完标记为看完`() {
        val p3 = Progress(2_400_000, 2_400_000, 10)
        val r = ShowResume.pick(eps, prog("s1e3" to p3), "s1e3")!!
        assertEquals("s1e3", r.path); assertTrue(r.finished)
    }

    @Test fun `旧数据没有记录时用最近有进度的那一集`() {
        val p1 = Progress(300_000, 2_400_000, 99)
        val r = ShowResume.pick(eps, prog("s1e1" to p1, "s1e2" to Progress(100_000, 2_400_000, 20)), null)!!
        assertEquals("s1e1", r.path); assertEquals(p1, r.progress)
    }

    @Test fun `记录的文件已不在剧集里时回退旧规则`() {
        val r = ShowResume.pick(eps, prog("s1e2" to Progress(100_000, 2_400_000, 20)), "old/path.mkv")!!
        assertEquals("s1e2", r.path)
    }

    @Test fun `没有剧集返回空`() { assertNull(ShowResume.pick(emptyList(), prog(), "x")) }
}
