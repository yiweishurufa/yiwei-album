package com.hark.shiguang.ui

import com.hark.shiguang.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.7.23「年」视图：按年分组与点年份后的定位。 */
class YearViewTest {
    private var seq = 0
    private fun photo(time: String) = Photo(seq, "u$seq", false, false, "p$seq.jpg", time, 100, 100, 0, false, "", "", "", null,
        "", "", "", "", "", "", 1000L, "", "/p${seq++}.jpg", source = "acc", cloudPath = "/p$seq.jpg")

    @Test fun `按年分组，倒序，每年第一张当封面`() {
        val a = photo("2025:03:01 10:00:00"); val b = photo("2025:01:02 10:00:00"); val c = photo("2023:12:31 10:00:00")
        val cards = yearCardsOf(listOf(a, b, c, photo("bad")))
        assertEquals(listOf(2025, 2023), cards.map { it.year })
        assertEquals(2, cards[0].count)
        assertEquals(a, cards[0].cover)
        assertEquals(c, cards[1].cover)
    }

    @Test fun `点年份落在该年第一个月的标题上`() {
        val ps = listOf(photo("2025:03:01 10:00:00"), photo("2024:11:05 10:00:00"), photo("2024:10:05 10:00:00"))
        val e = buildEntriesBy(ps, Level.MONTH, true)
        // [标题 2025-03, 格, 标题 2024-11, 格, 标题 2024-10, 格]
        assertEquals(0, firstIndexOfYear(e, 2025))
        assertEquals(2, firstIndexOfYear(e, 2024))
        assertEquals(-1, firstIndexOfYear(e, 2019))
    }
}
