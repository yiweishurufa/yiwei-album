package com.hark.shiguang

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-24 要求继续优化：色彩传输特性 → 动态范围，只有 PQ / HLG 是 HDR。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DynamicRangeTest {
    @Test fun `PQ 和 HLG 是 HDR`() {
        assertEquals("HDR10", MediaBadges.dynamicRange(C.COLOR_TRANSFER_ST2084))
        assertEquals("HLG", MediaBadges.dynamicRange(C.COLOR_TRANSFER_HLG))
        assertTrue(MediaBadges.isHdr(C.COLOR_TRANSFER_ST2084))
        assertTrue(MediaBadges.isHdr(C.COLOR_TRANSFER_HLG))
        assertEquals("HDR10", MediaBadges.hdrBadge(C.COLOR_TRANSFER_ST2084))
        assertEquals("HLG", MediaBadges.hdrBadge(C.COLOR_TRANSFER_HLG))
    }

    @Test fun `SDR、sRGB、Gamma 2_2、线性、未标注都按 SDR，不打 HDR 角标`() {
        for (t in listOf(C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_SRGB, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_LINEAR, Format.NO_VALUE)) {
            assertEquals("transfer=$t", "SDR", MediaBadges.dynamicRange(t))
            assertFalse(MediaBadges.isHdr(t))
            assertNull(MediaBadges.hdrBadge(t))
        }
    }

    @Test fun `不认识的取值按 SDR`() {
        assertEquals("SDR", MediaBadges.dynamicRange(999))
        assertNull(MediaBadges.hdrBadge(999))
    }
}
