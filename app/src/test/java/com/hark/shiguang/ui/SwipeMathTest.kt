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

/** 1.7.36 迷你条展开：封面起止位置、内容淡入时机。 */
class ExpandMathTest {
    @Test fun `开始时封面在迷你条的位置，结束时回到原位`() {
        val (x0, y0, s0) = ExpandMath.art(20f, 1800f, 100f, 80f, 300f, 900f, 0f)
        assertEquals(-60f, x0, 0.001f); assertEquals(1500f, y0, 0.001f); assertEquals(100f / 900f, s0, 0.001f)
        val (x1, y1, s1) = ExpandMath.art(20f, 1800f, 100f, 80f, 300f, 900f, 1f)
        assertEquals(0f, x1, 0.001f); assertEquals(0f, y1, 0.001f); assertEquals(1f, s1, 0.001f)
    }

    @Test fun `没有量到尺寸时不变形`() {
        assertEquals(Triple(0f, 0f, 1f), ExpandMath.art(0f, 0f, 0f, 0f, 0f, 900f, 0.3f))
    }

    @Test fun `内容过半才淡入`() {
        assertEquals(0f, ExpandMath.contentAlpha(0.5f), 0f)
        assertEquals(1f, ExpandMath.contentAlpha(1f), 0f)
        assertTrue(ExpandMath.contentAlpha(0.8f) in 0.4f..0.7f)
    }
}
