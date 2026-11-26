package com.hark.shiguang.ui

import org.junit.Assert.*
import org.junit.Test

/** 1.7.0 环境光晕取色：灰图交给强调色；彩色图取到同色相、明度 / 饱和度被拉到光晕范围。 */
class AmbientMathTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `全灰的图返回 null`() {
        assertNull(AmbientMath.pick(IntArray(64) { rgb(120, 120, 120) }))
        assertNull(AmbientMath.pick(IntArray(0)))
    }

    @Test fun `偏蓝的图取到蓝色相，明度饱和度在范围内`() {
        val px = IntArray(100) { if (it < 70) rgb(20, 60, 160) else rgb(200, 200, 200) }
        val c = AmbientMath.pick(px)!!
        val hsv = FloatArray(3)
        AmbientMath.rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
        assertTrue("hue ${hsv[0]}", hsv[0] in 205f..235f)
        assertTrue(hsv[1] in 0.44f..0.86f)
        assertTrue(hsv[2] in 0.69f..0.96f)
    }

    @Test fun `HSV 往返`() {
        val hsv = FloatArray(3)
        for (c in listOf(rgb(255, 0, 0), rgb(0, 200, 100), rgb(30, 40, 250), rgb(250, 160, 20))) {
            AmbientMath.rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
            val back = AmbientMath.hsvToRgb(hsv)
            for (sh in listOf(16, 8, 0)) assertTrue(kotlin.math.abs(((back shr sh) and 0xFF) - ((c shr sh) and 0xFF)) <= 2)
        }
    }
}
