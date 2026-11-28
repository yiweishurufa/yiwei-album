package com.hark.shiguang.ui

import org.junit.Assert.*
import org.junit.Test

/** 1.7.35 歌词 v2：透明度 / 模糊分级、字号上限、错开落位、跳转判定、间奏点。 */
class LyricMathTest {
    @Test fun `当前句满色，越远越淡但不低于 0_4，手动滑动时统一`() {
        assertEquals(1f, LyricMath.alpha(0, true), 0f)
        assertTrue(LyricMath.alpha(1, true) > LyricMath.alpha(2, true))
        assertEquals(0.4f, LyricMath.alpha(9, true), 0f)
        assertEquals(0.75f, LyricMath.alpha(0, false), 0f)
        assertEquals(0.75f, LyricMath.alpha(5, false), 0f)
        // 浅色主题整体抬高，最远行也不低于 0.5
        assertTrue(LyricMath.alpha(9, true, light = true) >= 0.5f)
        assertTrue(LyricMath.alpha(1, true, light = true) > LyricMath.alpha(1, true))
    }

    @Test fun `只模糊附近 1 到 4 行，最多 3dp`() {
        assertEquals(0f, LyricMath.blurDp(0), 0f)
        assertEquals(0f, LyricMath.blurDp(5), 0f)
        for (d in 1..4) assertTrue(LyricMath.blurDp(d) in 0.1f..3f)
        assertTrue(LyricMath.blurDp(4) > LyricMath.blurDp(1))
    }

    @Test fun `字号随字体放大但有上限`() {
        assertEquals(28f, LyricMath.fontSp(1f), 0.001f)
        assertEquals(28f, LyricMath.fontSp(1.2f), 0.001f)
        assertEquals(34f, LyricMath.fontSp(1.3f) * 1.3f, 0.01f)
        assertEquals(34f, LyricMath.fontSp(2f) * 2f, 0.01f)
        assertEquals(28f, LyricMath.fontSp(Float.NaN), 0.001f)
    }

    @Test fun `上面的行先动，往下逐行延后，有上限`() {
        assertEquals(0, LyricMath.delayMs(3, 5, step = 32, maxSteps = 6))
        assertEquals(32, LyricMath.delayMs(5, 5, step = 32, maxSteps = 6))
        assertEquals(64, LyricMath.delayMs(6, 5, step = 32, maxSteps = 6))
        assertEquals(192, LyricMath.delayMs(40, 5, step = 32, maxSteps = 6))
    }

    @Test fun `落位从整段偏移缓出到 0`() {
        assertEquals(100f, LyricMath.lag(100f, 0f, 64), 0.001f)
        assertEquals(100f, LyricMath.lag(100f, 64f, 64), 0.001f)
        val mid = LyricMath.lag(100f, 64f + LyricMath.LAG_MS / 2f, 64)
        assertTrue(mid in 0f..50f)          // 缓出：一半时间已走完大半
        assertEquals(0f, LyricMath.lag(100f, 10_000f, 64), 0.001f)
        assertEquals(1f, LyricMath.settle(5f, 0, durMs = 0), 0f)
    }

    @Test fun `跳得远或第一次显示时直接定位`() {
        assertTrue(LyricMath.isJump(-1, 3))
        assertFalse(LyricMath.isJump(3, 4))
        assertFalse(LyricMath.isJump(4, 6))
        assertTrue(LyricMath.isJump(4, 9))
        assertTrue(LyricMath.isJump(9, 2))
    }

    @Test fun `间奏三颗点依次亮`() {
        assertEquals(0f, LyricMath.dot(0, 0f), 0f)
        assertEquals(1f, LyricMath.dot(0, 0.34f), 0f)
        assertEquals(0f, LyricMath.dot(2, 0.5f), 0f)
        assertEquals(1f, LyricMath.dot(2, 1f), 0f)
    }
}
