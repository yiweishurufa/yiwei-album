package com.hark.shiguang
import org.junit.Assert.assertEquals
import org.junit.Test
class PlayerStatsTest {
    @Test fun full() = assertEquals("1920×1080 · 24 fps · 8.5 Mbps · 缓冲 12 秒", PlayerHints.stats(1920, 1080, 24f, 8_500_000, 12_300))
    @Test fun missing() = assertEquals("缓冲 0 秒", PlayerHints.stats(0, 0, 0f, 0, -5))
    @Test fun kbps() = assertEquals("640×360 · 23.98 fps · 800 kbps · 缓冲 3 秒", PlayerHints.stats(640, 360, 23.976f, 800_000, 3000))
}
