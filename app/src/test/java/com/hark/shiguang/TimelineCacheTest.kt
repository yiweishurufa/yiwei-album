package com.hark.shiguang

import com.hark.shiguang.data.DayCount
import com.hark.shiguang.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.7.24 首页离线缓存：存了什么就读回什么；地址按当前连接补全；坏文件当作没有缓存。 */
class TimelineCacheTest {
    private val lan = "http://192.168.1.5:5666"
    private val wan = "https://nas.example.com"
    private fun photo(id: Int, base: String) = Photo(id, "uu$id", id % 2 == 0, true, "IMG_$id.jpg", "2025:03:0$id 10:00:00", 4000, 3000, 12, true,
        "$base/p/api/v1/stream/p/t/$id/s/uu$id", "$base/p/api/v1/stream/p/t/$id/m/uu$id", "$base/p/api/v1/stream/p/t/$id/o/uu$id",
        if (id % 2 == 0) "$base/p/api/v1/stream/v/$id" else null, "Xiaomi", "Xiaomi 13", "1.8", "1/100", "100", "5.4", 123456L, "上海", "/photos/IMG_$id.jpg", liveType = 2)

    @Test fun roundTripSwitchesHost() {
        val snap = TimelineCache.Snapshot(listOf(DayCount(2025, 3, 1, 2), DayCount(2024, 12, 31, 5)), 7 to 1, listOf(photo(1, lan), photo(2, lan)), 42L)
        val text = TimelineCache.encode(snap) { TimelineCache.relOf(it, lan) }
        val back = TimelineCache.decode(text) { if (it.startsWith("/p/")) wan + it else it }!!
        assertEquals(snap.days, back.days)
        assertEquals(7 to 1, back.stats)
        assertEquals(42L, back.savedAt)
        assertEquals(listOf(photo(1, wan), photo(2, wan)), back.photos)
    }

    @Test fun keepsAtMostMax() {
        val many = (1..(TimelineCache.MAX + 50)).map { photo(it, lan) }
        val text = TimelineCache.encode(TimelineCache.Snapshot(emptyList(), 0 to 0, many, 0L)) { it }
        assertEquals(TimelineCache.MAX, TimelineCache.decode(text) { it }!!.photos.size)
    }

    @Test fun brokenOrOtherVersionIsNoCache() {
        assertNull(TimelineCache.decode("{not json") { it })
        assertNull(TimelineCache.decode("{\"v\":99,\"photos\":[]}") { it })
    }
}
