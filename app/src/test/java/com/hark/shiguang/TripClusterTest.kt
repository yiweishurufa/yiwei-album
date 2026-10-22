package com.hark.shiguang

import com.hark.shiguang.TripCluster.Point
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TripClusterTest {
    // 青岛（常住地）、杭州（约 700 km）、崂山（约 30 km，算常住地附近）
    private val qd = 36.07 to 120.38
    private val hz = 30.27 to 120.17
    private val laoshan = 36.16 to 120.62

    private var seq = 0
    private fun pts(day: String, n: Int, at: Pair<Double, Double>, place: String = "", hour: Int = 9): List<Point> =
        (0 until n).map { i -> Point("p${seq++}", "$day ${"%02d".format(hour + i / 60)}:${"%02d".format(i % 60)}:00", at.first + i * 1e-4, at.second, place) }

    private fun homeBase() = pts("2025:09:01", 40, qd, "青岛") + pts("2025:09:20", 40, qd, "青岛")

    @Test fun `常住地是 GPS 点最多的格子`() {
        val h = TripCluster.home(homeBase() + pts("2025:10:01", 20, hz))!!
        assertEquals(36.07, h.lat, 0.05); assertEquals(120.38, h.lng, 0.05)
        assertEquals(80, h.count)
    }

    @Test fun `常住地附近 50 公里内不算旅行`() {
        val r = TripCluster.cluster(homeBase() + pts("2025:10:01", 30, laoshan, "青岛"))
        assertTrue(r.trips.isEmpty())
    }

    @Test fun `跨天的出行合成一次，按地名和日期给出`() {
        val r = TripCluster.cluster(homeBase() + pts("2025:10:01", 10, hz, "杭州") + pts("2025:10:02", 10, hz, "杭州") + pts("2025:10:03", 5, hz, "绍兴"))
        assertEquals(1, r.trips.size)
        val t = r.trips[0]
        assertEquals(LocalDate.of(2025, 10, 1), t.start); assertEquals(LocalDate.of(2025, 10, 3), t.end)
        assertEquals(3, t.days); assertEquals(25, t.keys.size)
        assertEquals("杭州、绍兴", t.place)
        assertTrue(t.coverKey in t.keys)
    }

    @Test fun `隔一天没拍照仍是同一次，间隔超过 2 天拆成两次`() {
        val same = TripCluster.cluster(homeBase() + pts("2025:10:01", 10, hz) + pts("2025:10:03", 10, hz))
        assertEquals(1, same.trips.size)
        val split = TripCluster.cluster(homeBase() + pts("2025:10:01", 16, hz) + pts("2025:10:05", 16, hz))
        assertEquals(2, split.trips.size)
        assertEquals(LocalDate.of(2025, 10, 5), split.trips[0].start)   // newest first
        assertEquals(LocalDate.of(2025, 10, 1), split.trips[1].end)
    }

    @Test fun `中间一天回到常住地就拆开`() {
        val r = TripCluster.cluster(homeBase() + pts("2025:10:01", 16, hz) + pts("2025:10:02", 5, qd) + pts("2025:10:03", 16, hz))
        assertEquals(2, r.trips.size)
    }

    @Test fun `照片不足 15 张不算旅行`() {
        assertTrue(TripCluster.cluster(homeBase() + pts("2025:10:01", 14, hz)).trips.isEmpty())
        assertEquals(1, TripCluster.cluster(homeBase() + pts("2025:10:01", 15, hz)).trips.size)
    }

    @Test fun `超过 30 天的不算旅行`() {
        val long = (0 until 31).flatMap { d -> pts(LocalDate.of(2025, 10, 1).plusDays(d.toLong()).toString().replace('-', ':'), 2, hz) }
        assertTrue(TripCluster.cluster(homeBase() + long).trips.isEmpty())
        val ok = (0 until 30).flatMap { d -> pts(LocalDate.of(2025, 10, 1).plusDays(d.toLong()).toString().replace('-', ':'), 2, hz) }
        assertEquals(30, TripCluster.cluster(homeBase() + ok).trips.single().days)
    }

    @Test fun `没有 GPS 的照片不参与`() {
        val noGps = pts("2025:10:01", 30, Double.NaN to Double.NaN) + (0 until 30).map { Point("z$it", "2025:10:01 10:00:00", 0.0, 0.0) } + pts("0000:00:00", 30, hz)
        assertNull(TripCluster.cluster(noGps).home)
        assertTrue(TripCluster.cluster(noGps).trips.isEmpty())
        val r = TripCluster.cluster(homeBase() + noGps + pts("2025:10:01", 15, hz))
        assertEquals(15, r.trips.single().keys.size)
    }

    @Test fun `没有地名时显示大致坐标区域`() {
        val r = TripCluster.cluster(homeBase() + pts("2025:10:01", 15, hz, "30.3°, 120.2°"))
        assertEquals("北纬 30.3° 东经 120.2° 附近", r.trips.single().place)
    }

    @Test fun `指纹与顺序无关，内容变化就变`() {
        val a = homeBase()
        assertEquals(TripCluster.fingerprint(a), TripCluster.fingerprint(a.reversed()))
        assertNotEquals(TripCluster.fingerprint(a), TripCluster.fingerprint(a.drop(1)))
        assertNotEquals(TripCluster.fingerprint(a), TripCluster.fingerprint(listOf(a[0].copy(lat = a[0].lat + 1)) + a.drop(1)))
    }

    @Test fun `日期范围文字`() {
        val today = LocalDate.of(2025, 12, 1)
        assertEquals("10月1日–5日", tripRange(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 5), today))
        assertEquals("9月30日–10月2日", tripRange(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 10, 2), today))
        assertEquals("2024年12月30日–2025年1月2日", tripRange(LocalDate.of(2024, 12, 30), LocalDate.of(2025, 1, 2), today))
        assertEquals("10月1日", tripRange(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), today))
    }
}
