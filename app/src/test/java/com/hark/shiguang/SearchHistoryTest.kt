package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {
    @Test fun dedupAndOrder() {
        assertEquals(listOf("b", "a"), SearchHistory.push(listOf("a", "b"), " b "))
    }
    @Test fun blankIgnoredAndCapped() {
        assertEquals(listOf("a"), SearchHistory.push(listOf("a"), "  "))
        val big = (1..10).map { "q$it" }
        val r = SearchHistory.push(big, "new")
        assertEquals(10, r.size); assertEquals("new", r[0])
    }
}
