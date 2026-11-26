package com.hark.shiguang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HeroTextTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test fun noMemoryKeepsTimelineTitle() {
        val l = HeroText.labels(null, 0, null, today, "11月", "2026年 · 19,412 项")
        assertNull(l.kicker)
        assertEquals("11月", l.title)
        assertEquals("2026年 · 19,412 项", l.sub)
    }

    @Test fun memoryCoverDescribesThatDay() {
        val l = HeroText.labels(MemoryGroup(1, 2025, emptyList()), 2, "2025:11:26 21:03:11", today, "11月", "2026年 · 19,412 项")
        assertEquals("一年前的今天", l.kicker)
        assertEquals("11月26日", l.title)
        assertEquals("2025年 · 2 张", l.sub)
    }

    @Test fun yearsAgoAndThousandsSeparator() {
        val l = HeroText.labels(MemoryGroup(5, 2021, emptyList()), 1234, "2021:03:07 08:00:00", today, "11月", "")
        assertEquals("5 年前的今天", l.kicker)
        assertEquals("3月7日", l.title)
        assertEquals("2021年 · 1,234 张", l.sub)
    }

    @Test fun unreadableTimeFallsBackToToday() {
        val l = HeroText.labels(MemoryGroup(2, 2024, emptyList()), 3, "", today, "11月", "")
        assertEquals("11月26日", l.title)
    }
}
