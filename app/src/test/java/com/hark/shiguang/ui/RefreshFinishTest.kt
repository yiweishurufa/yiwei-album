package com.hark.shiguang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.26 下拉刷新收尾曲线：先弹大到 1.12，再缩到 0.6 并淡出；超出 0–1 的输入夹住。 */
class RefreshFinishTest {
    @Test fun startsAtRest() {
        assertEquals(1f, RefreshFinish.scale(0f), 1e-4f)
        assertEquals(1f, RefreshFinish.alpha(0f), 1e-4f)
    }

    @Test fun peaksThenShrinks() {
        assertEquals(1.12f, RefreshFinish.scale(0.35f), 1e-3f)
        assertTrue(RefreshFinish.scale(0.7f) < RefreshFinish.scale(0.35f))
        assertEquals(0.6f, RefreshFinish.scale(1f), 1e-3f)
    }

    @Test fun fadesOnlyAfterPeak() {
        assertEquals(1f, RefreshFinish.alpha(0.3f), 1e-4f)
        assertEquals(0f, RefreshFinish.alpha(1f), 1e-4f)
        assertTrue(RefreshFinish.alpha(0.6f) in 0f..1f)
    }

    @Test fun clampsOutOfRange() {
        assertEquals(RefreshFinish.scale(1f), RefreshFinish.scale(3f), 1e-4f)
        assertEquals(RefreshFinish.alpha(0f), RefreshFinish.alpha(-1f), 1e-4f)
    }
}
