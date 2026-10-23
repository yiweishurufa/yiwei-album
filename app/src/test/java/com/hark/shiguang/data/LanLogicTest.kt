package com.hark.shiguang.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-23 反馈 #1：局域网搜索飞牛的可测逻辑。 */
class LanLogicTest {
    @Test fun slash24ExcludesNetworkBroadcastAndSelf() {
        val h = LanLogic.hosts("192.168.1.23", 24)
        assertEquals(253, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
        assertFalse(h.contains("192.168.1.23"))
        assertFalse(h.contains("192.168.1.0"))
        assertFalse(h.contains("192.168.1.255"))
    }

    @Test fun slash23CoversBothHalves() {
        val h = LanLogic.hosts("192.168.0.200", 23)
        assertEquals(509, h.size)                       // 510 可用 - 自己
        assertTrue(h.contains("192.168.1.5"))           // NAS 在另一半也能扫到（旧实现只扫 /24 会漏）
        assertTrue(h.contains("192.168.0.1"))
        assertEquals("192.168.1.254", h.last())
    }

    @Test fun largeSubnetIsCappedAroundPhone() {
        val h = LanLogic.hosts("10.0.5.10", 16)
        assertEquals(LanLogic.MAX_HOSTS - 1, h.size)
        assertTrue(h.contains("10.0.5.9")); assertTrue(h.contains("10.0.5.11"))
        assertTrue(h.contains("10.0.4.1")); assertTrue(h.contains("10.0.6.250"))
        assertFalse(h.contains("10.0.0.1"))
    }

    @Test fun capClampsAtSubnetStart() {
        val h = LanLogic.hosts("10.0.0.3", 16, cap = 100)
        assertEquals("10.0.0.1", h.first()); assertEquals(99, h.size)
    }

    @Test fun publicOrPointToPointNotScanned() {
        assertTrue(LanLogic.hosts("8.8.8.8", 24).isEmpty())
        assertTrue(LanLogic.hosts("192.168.1.2", 31).isEmpty())
        assertTrue(LanLogic.hosts("bad", 24).isEmpty())
    }

    @Test fun privateRanges() {
        assertTrue(LanLogic.isPrivate("10.1.2.3")); assertTrue(LanLogic.isPrivate("172.16.0.1")); assertTrue(LanLogic.isPrivate("172.31.9.9"))
        assertTrue(LanLogic.isPrivate("192.168.0.1"))
        assertFalse(LanLogic.isPrivate("172.32.0.1")); assertFalse(LanLogic.isPrivate("192.169.0.1")); assertFalse(LanLogic.isPrivate("100.64.0.1"))
    }

    @Test fun fnosPageRecognised() {
        assertTrue(LanLogic.looksLikeFnos("<html><head><title>MyNAS - 飞牛 fnOS</title></head></html>"))
        assertTrue(LanLogic.looksLikeFnos("<noscript>请开启 JavaScript 以使用飞牛私有云</noscript>"))
        assertTrue(LanLogic.looksLikeFnos("Server:nginx CN=fnOS, O=fnOS"))          // 自签名证书
        assertFalse(LanLogic.looksLikeFnos("<title>TP-LINK 路由器</title>"))
        assertFalse(LanLogic.looksLikeFnos("<title>Synology DiskStation</title>"))
        assertFalse(LanLogic.looksLikeFnos(""))
    }

    @Test fun nameFromTitle() {
        assertEquals("MyNAS", LanLogic.nameFromTitle("MyNAS - 飞牛 fnOS"))
        assertEquals("", LanLogic.nameFromTitle("飞牛 fnOS"))
        assertEquals("", LanLogic.nameFromTitle("fnOS"))
        assertEquals("家里", LanLogic.nameFromTitle("家里 | fnOS"))
    }

    @Test fun httpsRedirectFollowedOnlyToSameHost() {
        assertEquals("https://192.168.1.5:5667", LanLogic.httpsTarget("192.168.1.5", "https://192.168.1.5:5667/login"))
        assertEquals("https://192.168.1.5", LanLogic.httpsTarget("192.168.1.5", "https://192.168.1.5/"))
        assertNull(LanLogic.httpsTarget("192.168.1.5", "https://192.168.1.9:5667/"))
        assertNull(LanLogic.httpsTarget("192.168.1.5", "/login"))
        assertNull(LanLogic.httpsTarget("192.168.1.5", null))
    }

    @Test fun mdnsQueryShape() {
        val q = LanLogic.mdnsQuery(listOf("_smb._tcp.local"))
        assertEquals(1, q[5].toInt())                                    // QDCOUNT = 1
        assertEquals(4, q[12].toInt()); assertEquals('_'.code, q[13].toInt())
        val tail = q.takeLast(4).map { it.toInt() and 255 }
        assertEquals(listOf(0, 12, 0x80, 1), tail)                       // PTR, IN + QU 位
    }

    @Test fun mdnsAnswerParsedWithCompression() {
        // 响应：0 个问题，2 条回答：PTR _smb._tcp.local -> fnos._smb._tcp.local；A fnos.local -> 192.168.1.5（名字用压缩指针）
        val b = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v ushr 8); b.write(v and 255) }
        fun name(vararg l: String) { l.forEach { b.write(it.length); b.write(it.toByteArray()) }; b.write(0) }
        u16(0); u16(0x8400); u16(0); u16(2); u16(0); u16(0)
        val ptrName = b.size(); name("_smb", "_tcp", "local")
        u16(12); u16(1); u16(0); u16(120)
        u16(7); b.write(4); b.write("fnos".toByteArray()); b.write(0xC0); b.write(ptrName)
        val hostOff = b.size(); b.write(4); b.write("fnos".toByteArray()); b.write(0xC0); b.write(ptrName + 10)  // fnos + "local"
        u16(1); u16(0x8001); u16(0); u16(120); u16(4); b.write(192); b.write(168); b.write(1); b.write(5)
        val hits = LanLogic.parseMdnsA(b.toByteArray())
        assertEquals(1, hits.size)
        assertEquals("192.168.1.5", hits[0].ip)
        assertEquals("fnos", hits[0].name)
        assertTrue(hostOff > 0)
    }

    @Test fun mdnsGarbageIsSafe() {
        assertTrue(LanLogic.parseMdnsA(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(LanLogic.parseMdnsA(ByteArray(40) { 0xC0.toByte() }).isEmpty())
        assertTrue(LanLogic.parseMdnsA(LanLogic.mdnsQuery()).isEmpty())   // 查询包不是响应
    }

    @Test fun reasons() {
        fun s(l: Int = 1, vpn: Boolean = false, bf: Boolean = false, open: Int = 0, md: Int = 0, pr: Int = 0) = LanLogic.Stats(l, "192.168.1.x", vpn, bf, open, md, pr)
        assertTrue(LanLogic.reason(s(l = 0)).contains("没连 Wi-Fi"))
        assertTrue(LanLogic.reason(s(vpn = true, bf = true)).contains("VPN"))
        assertTrue(LanLogic.reason(s(vpn = true, open = 200, pr = 200)).contains("VPN"))
        assertTrue(LanLogic.reason(s()).contains("5666"))
        assertTrue(LanLogic.reason(s(open = 2, pr = 2)).contains("不是飞牛"))
    }
}
