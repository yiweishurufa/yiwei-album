package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeBoostTest {
    @Test fun cyclesThroughAllLevelsAndBackToOff() {
        assertEquals(1, VolumeBoost.next(0)); assertEquals(2, VolumeBoost.next(1))
        assertEquals(3, VolumeBoost.next(2)); assertEquals(0, VolumeBoost.next(3))
    }

    @Test fun outOfRangeLevelsAreClamped() {
        assertEquals(0, VolumeBoost.gainMb(-1)); assertEquals(900, VolumeBoost.gainMb(99))
        assertEquals(1, VolumeBoost.next(-5)); assertEquals(0, VolumeBoost.next(99))
    }

    @Test fun labelsAreReadable() {
        assertEquals("关", VolumeBoost.label(0)); assertEquals("+3 dB", VolumeBoost.label(1))
        assertEquals("+6 dB", VolumeBoost.label(2)); assertEquals("+9 dB", VolumeBoost.label(3))
    }
}
