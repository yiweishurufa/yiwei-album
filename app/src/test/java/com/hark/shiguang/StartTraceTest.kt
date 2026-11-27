package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class StartTraceTest {
    @Test fun eachStepIsDeltaFromPreviousAndTotalFromProcessStart() {
        val s = StartTrace.summary(1000, listOf("Application" to 1035L, "读取设置" to 1115L, "首帧" to 1640L))
        assertEquals("Application +35ms · 读取设置 +80ms · 首帧 +525ms · 共 640ms", s)
    }

    @Test fun clockGoingBackwardsNeverPrintsNegative() {
        val s = StartTrace.summary(2000, listOf("Application" to 1990L, "首帧" to 2100L))
        assertEquals("Application +0ms · 首帧 +110ms · 共 100ms", s)
    }

    @Test fun emptyMarks() {
        assertEquals("无数据", StartTrace.summary(0, emptyList()))
    }
}
