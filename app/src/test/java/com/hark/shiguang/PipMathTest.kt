package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class PipMathTest {
    @Test fun `普通视频用自己的宽高比`() {
        assertEquals(1920 to 1080, PipMath.aspect(1920, 1080))
        assertEquals(1080 to 1920, PipMath.aspect(1080, 1920))
        assertEquals(1440 to 1080, PipMath.aspect(1440, 1080))
    }

    @Test fun `还不知道尺寸时用 16 比 9`() {
        assertEquals(16 to 9, PipMath.aspect(0, 0))
        assertEquals(16 to 9, PipMath.aspect(1920, 0))
    }

    @Test fun `超宽和超高的比例收进系统允许的范围`() {
        assertEquals(239 to 100, PipMath.aspect(3840, 1200))   // 3.2:1
        assertEquals(100 to 239, PipMath.aspect(500, 2000))
        assertEquals(2390 to 1000, PipMath.aspect(2390, 1000))  // exactly 2.39:1 is allowed
    }
}
