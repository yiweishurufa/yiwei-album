package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerHintsTest {
    @Test fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", PlayerHints.clock(0))
        assertEquals("32:10", PlayerHints.clock(32 * 60_000L + 10_000))
        assertEquals("1:02:03", PlayerHints.clock(3_723_000))
        assertEquals("0:00", PlayerHints.clock(-5))
    }

    @Test fun resumeOnlyAfterFiveSeconds() {
        assertNull(PlayerHints.resume(0))
        assertNull(PlayerHints.resume(5_000))
        assertEquals("已从 0:06 继续播放", PlayerHints.resume(6_000))
        assertEquals("已从 32:10 继续播放", PlayerHints.resume(1_930_000))
    }

    @Test fun sleepLabelCoversAllModes() {
        assertEquals("关", PlayerHints.sleepLabel(0, false))
        assertEquals("30 分钟后", PlayerHints.sleepLabel(30, false))
        assertEquals("本集播完后", PlayerHints.sleepLabel(0, true))
        assertEquals("本集播完后", PlayerHints.sleepLabel(30, true))
    }
}
