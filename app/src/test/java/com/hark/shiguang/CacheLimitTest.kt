package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheLimitTest {
    @Test fun invalidFallsBackToDefault() {
        assertEquals(300, CacheLimit.normalize(0))
        assertEquals(300, CacheLimit.normalize(123))
        assertEquals(500, CacheLimit.normalize(500))
    }
    @Test fun bytesAndLabel() {
        assertEquals(300L * 1024 * 1024, CacheLimit.bytes(-1))
        assertEquals(2000L * 1024 * 1024, CacheLimit.bytes(2000))
        assertEquals("100 MB", CacheLimit.label(100))
        assertEquals("1 GB", CacheLimit.label(1000))
        assertEquals("2 GB", CacheLimit.label(2000))
    }
}
