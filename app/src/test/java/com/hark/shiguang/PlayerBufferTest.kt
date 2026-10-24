package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-24 反馈：WebDAV 观看卡顿 —— 缓冲参数。 */
class PlayerBufferTest {
    private val mb = 1024L * 1024

    @Test fun `字节上限按堆的四分之一并夹在 32 到 160 MB`() {
        assertEquals(128 * mb.toInt(), PlayerBuffer.forHeap(512 * mb).targetBytes)
        assertEquals(64 * mb.toInt(), PlayerBuffer.forHeap(256 * mb).targetBytes)
        assertEquals(32 * mb.toInt(), PlayerBuffer.forHeap(64 * mb).targetBytes)
        assertEquals(160 * mb.toInt(), PlayerBuffer.forHeap(2048 * mb).targetBytes)
        assertEquals(160 * mb.toInt(), PlayerBuffer.forHeap(Long.MAX_VALUE).targetBytes)
    }

    @Test fun `时长参数满足 media3 的约束`() {
        val p = PlayerBuffer.forHeap(512 * mb)
        // DefaultLoadControl.Builder 要求 playMs <= minMs、rebufferMs <= minMs、minMs <= maxMs
        assertTrue(p.playMs <= p.minMs && p.rebufferMs <= p.minMs && p.minMs <= p.maxMs)
        assertTrue("卡顿后要比起播多攒一些，避免反复卡", p.rebufferMs > p.playMs)
        assertTrue("比旧值（卡顿后 2.5 s）更稳", p.rebufferMs > 2_500)
        assertTrue("跳转后起播不比旧值（1.2 s）慢太多", p.playMs <= 2_000)
    }

    @Test fun `能缓冲的秒数由码率和字节上限决定`() {
        val p = PlayerBuffer.forHeap(512 * mb)   // 128 MB
        assertEquals(90, PlayerBuffer.secondsFor(p, 8_000_000))     // 1080p 8 Mbps：到 90 s 上限
        assertEquals(13, PlayerBuffer.secondsFor(p, 80_000_000))    // 4K 原盘 80 Mbps：约 13 s
        assertEquals(-1, PlayerBuffer.secondsFor(p, 0))
    }
}
