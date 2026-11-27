package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.23：影视刮削失败的分类与退避（与照片 / 音乐共用 RetryBackoff）。 */
class ScrapeFailureTest {
    @Test fun keyErrorIsConfigNotItemFailure() {
        assertTrue(ScrapeFailure.isConfig(IllegalStateException("TMDB 密钥无效")))
        assertFalse(ScrapeFailure.isConfig(java.io.IOException("连不上 TMDB（直连）：timeout")))
        assertFalse(ScrapeFailure.isConfig(IllegalArgumentException("TMDB HTTP 404")))
    }

    @Test fun autoScanSkipsBackedOffItemManualDoesNot() {
        val book = RetryBook(null)
        val t = 1_000_000_000L
        book.fail("movie1", t)
        assertFalse(book.eligible("movie1", manual = false, now = t + 30 * 60_000L))
        assertTrue(book.eligible("movie1", manual = false, now = t + 3_600_000L))
        assertTrue(book.eligible("movie1", manual = true, now = t + 1))
        book.ok("movie1")
        assertEquals(0, book.attempts("movie1"))
    }
}
