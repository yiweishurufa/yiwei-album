package com.hark.shiguang.ui

import org.junit.Assert.*
import org.junit.Test

/** 1.7.34 滑动切歌 / 下拉收起的阈值判定。 */
class SwipeMathTest {
    private val w = 1000f

    @Test fun `位移不到三成且慢慢松手不切`() {
        assertEquals(0, SwipeMath.skipDecision(-250f, -100f, w, canPrev = true, canNext = true))
        assertEquals(0, SwipeMath.skipDecision(250f, 100f, w, canPrev = true, canNext = true))
    }

    @Test fun `位移超过三成按方向切`() {
        assertEquals(1, SwipeMath.skipDecision(-310f, 0f, w, canPrev = true, canNext = true))
        assertEquals(-1, SwipeMath.skipDecision(310f, 0f, w, canPrev = true, canNext = true))
    }

    @Test fun `位移短但甩得快也切，往回甩不切`() {
        assertEquals(1, SwipeMath.skipDecision(-60f, -900f, w, canPrev = true, canNext = true))
        assertEquals(0, SwipeMath.skipDecision(-60f, 900f, w, canPrev = true, canNext = true))
    }

    @Test fun `方向上没有歌就回弹并加阻尼`() {
        assertEquals(0, SwipeMath.skipDecision(-600f, -2000f, w, canPrev = true, canNext = false))
        assertEquals(0, SwipeMath.skipDecision(600f, 2000f, w, canPrev = false, canNext = true))
        assertEquals(-35f, SwipeMath.skipOffset(-100f, canPrev = true, canNext = false), 0.01f)
        assertEquals(100f, SwipeMath.skipOffset(100f, canPrev = true, canNext = false), 0.01f)
    }

    @Test fun `宽度未知时不切`() {
        assertEquals(0, SwipeMath.skipDecision(-600f, -2000f, 0f, canPrev = true, canNext = true))
    }

    @Test fun `下拉超过四分之一或甩得快收起，上推不收`() {
        assertFalse(SwipeMath.shouldDismiss(400f, 0f, 2000f))
        assertTrue(SwipeMath.shouldDismiss(501f, 0f, 2000f))
        assertTrue(SwipeMath.shouldDismiss(80f, 1300f, 2000f))
        assertFalse(SwipeMath.shouldDismiss(-300f, 3000f, 2000f))
        assertEquals(1f, SwipeMath.dismissProgress(900f, 2000f), 0f)
        assertEquals(0.5f, SwipeMath.dismissProgress(250f, 2000f), 0.001f)
    }

    @Test fun `首尾与循环模式下能否切歌`() {
        assertFalse(SwipeMath.canGo(1, 4, 5, repeatAll = false, shuffle = false))
        assertTrue(SwipeMath.canGo(-1, 4, 5, repeatAll = false, shuffle = false))
        assertFalse(SwipeMath.canGo(-1, 0, 5, repeatAll = false, shuffle = false))
        assertTrue(SwipeMath.canGo(1, 4, 5, repeatAll = true, shuffle = false))
        assertTrue(SwipeMath.canGo(-1, 0, 5, repeatAll = false, shuffle = true))
        assertFalse(SwipeMath.canGo(1, 0, 1, repeatAll = true, shuffle = true))
    }
}
