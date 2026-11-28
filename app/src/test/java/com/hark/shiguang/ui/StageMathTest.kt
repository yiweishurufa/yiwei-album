package com.hark.shiguang.ui

import org.junit.Assert.*
import org.junit.Test

/** 1.7.36 正在播放页取色背景：任何封面色在深 / 浅主题下文字对比度都过线；色相衍生；漂移有界且可停。 */
class StageMathTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val seeds = listOf(rgb(255, 255, 255), rgb(0, 0, 0), rgb(255, 230, 0), rgb(0, 255, 255), rgb(255, 0, 0), rgb(30, 40, 250),
        rgb(120, 120, 120), rgb(250, 160, 20), rgb(140, 255, 140), rgb(255, 180, 220))

    @Test fun `深色主题：正文 7 比 1、次要文字 4_5 比 1 以上`() {
        for (s in seeds) for (c in StageMath.palette(s, dark = true)) {
            assertTrue("text ${Integer.toHexString(s)}", StageMath.contrast(StageMath.DARK_TEXT, c) >= 7.0)
            assertTrue("sub ${Integer.toHexString(s)}", StageMath.contrast(StageMath.DARK_SUB, c) >= 4.5)
        }
    }

    @Test fun `浅色主题同样过线`() {
        for (s in seeds) for (c in StageMath.palette(s, dark = false)) {
            assertTrue("text ${Integer.toHexString(s)}", StageMath.contrast(StageMath.LIGHT_TEXT, c) >= 7.0)
            assertTrue("sub ${Integer.toHexString(s)}", StageMath.contrast(StageMath.LIGHT_SUB, c) >= 4.5)
        }
    }

    @Test fun `衍生色色相偏开约 25 度`() {
        val p = StageMath.palette(rgb(30, 90, 220), dark = true)
        val hsv = FloatArray(3)
        val hues = p.map { c -> AmbientMath.rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv); hsv[0] }
        fun d(a: Float, b: Float) = kotlin.math.abs(((a - b + 540f) % 360f) - 180f)
        assertTrue(d(hues[0], hues[1]) in 18f..32f)
        assertTrue(d(hues[0], hues[2]) in 18f..32f)
    }

    @Test fun `色块漂移有界，同一时刻结果固定`() {
        for (i in 0..2) for (t in listOf(0f, 3.3f, 9f, 60f, 3600f)) {
            val (x, y) = StageMath.center(i, t)
            assertTrue(x in -0.1f..1.1f && y in -0.1f..1.1f)
            assertEquals(StageMath.center(i, t), StageMath.center(i, t))
        }
    }
}
