package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-24 反馈：连续快进时闪退 / 卡顿 —— 连续跳转合并成一次。 */
class PlayerSeekTest {
    private fun co() = SeekCoalescer(quietMs = 350, maxHoldMs = 1_200)

    @Test fun `空闲时单击立刻跳，不会变慢`() {
        val c = co()
        assertEquals(SeekCoalescer.Seek(0, 15_000, 1), c.request(0, 15_000, now = 10_000))
        assertFalse(c.hasPending)
        // 过了安静期再点，也是立刻跳
        assertEquals(SeekCoalescer.Seek(0, 30_000, 1), c.request(0, 30_000, now = 10_400))
    }

    @Test fun `连点快进只在停手后跳一次到最后的位置`() {
        val c = co()
        assertNotNull(c.request(0, 15_000, 0))           // 第一下立刻跳
        assertNull(c.request(0, 30_000, 100))
        assertNull(c.request(0, 45_000, 200))
        assertNull(c.request(0, 60_000, 300))
        assertEquals(60_000L, c.pendingPosition(0))       // 进度条立刻显示最终目标
        assertNull(c.due(500, 0))                         // 最后一下后还没安静 350ms
        assertEquals(650L, c.nextCheckAt())
        assertEquals(SeekCoalescer.Seek(0, 60_000, 3), c.due(650, 0))
        assertFalse(c.hasPending)
        assertNull(c.due(2_000, 0))
    }

    @Test fun `一直不停手时最多每 1_2 秒跳一次`() {
        val c = co()
        c.request(0, 0, 0)
        var t = 100L; var pos = 0L
        var applied: SeekCoalescer.Seek? = null
        while (t <= 1_300 && applied == null) {
            pos += 10_000
            c.request(0, pos, t)
            applied = c.due(t, 0)
            t += 100
        }
        assertNotNull(applied)
        assertTrue("held ${t}", t - 100 <= 1_300)
        assertTrue(applied!!.merged > 5)
    }

    @Test fun `切到别的集后丢弃上一集没执行的跳转`() {
        val c = co()
        c.request(0, 15_000, 0)
        c.request(0, 90_000, 100)
        assertNull(c.pendingPosition(1))
        assertNull(c.due(1_000, currentIndex = 1))
        assertFalse(c.hasPending)
    }

    @Test fun `flush 立刻取出待跳转`() {
        val c = co()
        c.request(0, 1_000, 0)
        c.request(0, 2_000, 50)
        assertEquals(SeekCoalescer.Seek(0, 2_000, 1), c.flush(60, 0))
        assertNull(c.flush(70, 0))
    }

    @Test fun `负数位置夹到 0`() {
        assertEquals(0L, co().request(0, -5_000, 0)!!.positionMs)
    }

    @Test fun `快进快退按钮在合并目标上累加并夹在时长内`() {
        assertEquals(25_000L, SeekCoalescer.step(10_000, 15_000, 3_600_000))
        assertEquals(0L, SeekCoalescer.step(3_000, -5_000, 3_600_000))
        assertEquals(3_600_000L, SeekCoalescer.step(3_590_000, 15_000, 3_600_000))
        assertEquals(3_605_000L, SeekCoalescer.step(3_590_000, 15_000, 0))   // 时长未知只夹下限
    }
}
