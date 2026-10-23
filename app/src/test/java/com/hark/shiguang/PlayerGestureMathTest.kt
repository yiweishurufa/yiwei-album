package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureMathTest {
    @Test fun `满屏宽对应的时长`() {
        assertEquals(180_000L, PlayerGestureMath.seekSpanMs(106 * 60_000L))   // 电影：3 分钟
        assertEquals(60_000L, PlayerGestureMath.seekSpanMs(5 * 60_000L))      // 5 分钟短片：至少 1 分钟
        assertEquals(30_000L, PlayerGestureMath.seekSpanMs(30_000L))          // 不超过总时长
        assertEquals(90_000L, PlayerGestureMath.seekSpanMs(0L))
    }

    @Test fun `横滑目标位置夹在 0 到时长之间`() {
        val dur = 106 * 60_000L
        assertEquals(60_000L + 90_000L, PlayerGestureMath.seekTarget(60_000L, 500f, 1000, dur))
        assertEquals(0L, PlayerGestureMath.seekTarget(10_000L, -1000f, 1000, dur))
        assertEquals(dur, PlayerGestureMath.seekTarget(dur - 1000, 1000f, 1000, dur))
        assertEquals(5_000L, PlayerGestureMath.seekTarget(5_000L, 100f, 0, dur))
    }

    @Test fun `竖滑调节`() {
        assertEquals(1f, PlayerGestureMath.level(0.5f, 400f, 1000), 0.001f)
        assertEquals(0.75f, PlayerGestureMath.level(0.5f, 200f, 1000), 0.001f)
        assertEquals(0f, PlayerGestureMath.level(0.2f, -1000f, 1000), 0.001f)
    }

    @Test fun `预览文字`() {
        assertEquals("+01:23 / 12:00", PlayerGestureMath.seekLabel(83_000L, 720_000L))
        assertEquals("-00:30 / 1:46:44", PlayerGestureMath.seekLabel(-30_000L, 6_404_000L))
    }
}
