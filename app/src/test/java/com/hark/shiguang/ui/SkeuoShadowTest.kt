package com.hark.shiguang.ui

import org.junit.Assert.*
import org.junit.Test

/** 用户 2026-10-24 要求继续优化：API 26/27 改画柔和的暖色阴影，参数要「贴边深、外缘淡、整体比纯黑轻」。 */
class SkeuoShadowTest {
    @Test fun `只有 API 28 起用硬件阴影颜色`() {
        assertFalse(SkeuoShadow.nativeColors(26))
        assertFalse(SkeuoShadow.nativeColors(27))
        assertTrue(SkeuoShadow.nativeColors(28))
        assertTrue(SkeuoShadow.nativeColors(35))
    }

    @Test fun `层由内到外逐渐外扩、逐渐变淡`() {
        val l = SkeuoShadow.layers(12f, 0.3f)
        assertEquals(SkeuoShadow.LAYERS, l.size)
        for (i in 1 until l.size) {
            assertTrue(l[i].spread > l[i - 1].spread)
            assertTrue(l[i].alpha < l[i - 1].alpha)
        }
        assertEquals(12f * 0.9f, l.last().spread, 1e-4f)
        assertTrue(l.all { it.dy > 0f })
    }

    @Test fun `叠加后的最深处也比基础透明度轻`() {
        val base = 0.7f   // 深色主题的 spot
        val l = SkeuoShadow.layers(8f, base)
        val combined = 1f - l.fold(1f) { acc, x -> acc * (1f - x.alpha) }
        assertTrue("combined=$combined", combined < base * 0.7f)
        assertTrue(combined > 0.05f)
    }

    @Test fun `高度或透明度为 0 时不画`() {
        assertTrue(SkeuoShadow.layers(0f, 0.3f).isEmpty())
        assertTrue(SkeuoShadow.layers(4f, 0f).isEmpty())
    }
}
