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

    @Test fun fitCycles() {
        assertEquals(1, PlayerHints.nextFit(0)); assertEquals(2, PlayerHints.nextFit(1)); assertEquals(0, PlayerHints.nextFit(2))
        assertEquals("适应", PlayerHints.fitLabel(0))
    }

    @Test fun newPlayerLabels() {
        assertEquals("00:01:42", PlayerHints.longClock(102_000))
        assertEquals("01:05:26", PlayerHints.longClock(3_926_000))
        assertEquals("00:00:00", PlayerHints.longClock(-5))
        assertEquals("1.0x", PlayerHints.speedLabel(1f))
        assertEquals("1.5x", PlayerHints.speedLabel(1.5f))
        assertEquals("1.25x", PlayerHints.speedLabel(1.25f))
    }
}
