package com.hark.shiguang

import com.hark.shiguang.cloud.DavFolderCache
import com.hark.shiguang.cloud.DavScanRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.21：后台反复扫描耗电——失败退避、自动续跑门控、增量判断。 */
class ScanBackoffTest {
    private val h = 3_600_000L

    // ---------------------------------------------------------------- RetryBackoff

    @Test fun neverFailedIsEligible() {
        assertTrue(RetryBackoff.eligible(null, 0L))
    }

    @Test fun waitsOneSixTwentyFourHoursThenGivesUp() {
        var e = RetryBackoff.failed(null, 0L)
        assertFalse(RetryBackoff.eligible(e, h - 1))
        assertTrue(RetryBackoff.eligible(e, h))
        e = RetryBackoff.failed(e, h)
        assertFalse(RetryBackoff.eligible(e, h + 6 * h - 1))
        assertTrue(RetryBackoff.eligible(e, h + 6 * h))
        e = RetryBackoff.failed(e, 7 * h)
        assertFalse(RetryBackoff.eligible(e, 7 * h + 24 * h - 1))
        assertTrue(RetryBackoff.eligible(e, 7 * h + 24 * h))
        e = RetryBackoff.failed(e, 31 * h)
        assertTrue(RetryBackoff.gaveUp(e))
        assertFalse(RetryBackoff.eligible(e, 31 * h + 365 * 24 * h))   // 放弃后不再自动试
    }

    @Test fun clockMovedBackAllowsRetry() {
        val e = RetryBackoff.failed(null, 10 * h)
        assertTrue(RetryBackoff.eligible(e, 5 * h))
    }

    @Test fun attemptsAreCapped() {
        var e: RetryBackoff.Entry? = null
        repeat(10) { e = RetryBackoff.failed(e, 0L) }
        assertEquals(RetryBackoff.MAX_ATTEMPTS, e!!.attempts)
    }

    // ---------------------------------------------------------------- RetryBook

    @Test fun bookFailOkAndManual() {
        val b = RetryBook(null)
        b.fail("/a.jpg", now = 0L)
        assertFalse(b.eligible("/a.jpg", now = 10L))
        assertTrue(b.eligible("/a.jpg", manual = true, now = 10L))   // 手动刷新不看退避
        assertTrue(b.eligible("/b.jpg", now = 10L))
        assertEquals(1, b.waiting(now = 10L))
        b.ok("/a.jpg")
        assertTrue(b.eligible("/a.jpg", now = 10L))
        assertEquals(0, b.size)
    }

    @Test fun bookResetAndRetain() {
        val b = RetryBook(null)
        b.fail("x", 0L); b.fail("y", 0L); b.fail("gone", 0L)
        b.retain(setOf("x", "y"))
        assertEquals(2, b.size)
        b.reset()
        assertEquals(0, b.size)
        assertTrue(b.eligible("x", now = 1L))
    }

    @Test fun bookJsonRoundTrip() {
        val b = RetryBook(null)
        b.fail("/照片/1.jpg", 100L); b.fail("/照片/1.jpg", 200L); b.fail("on:abc", 300L)
        val c = RetryBook(null)
        c.fromJson(b.toJson())
        assertEquals(2, c.attempts("/照片/1.jpg"))
        assertEquals(1, c.attempts("on:abc"))
        assertFalse(c.eligible("/照片/1.jpg", now = 200L + h))          // 第 2 次失败后要等 6 小时
        assertTrue(c.eligible("/照片/1.jpg", now = 200L + 6 * h))
    }

    @Test fun bookIgnoresBadEntries() {
        val c = RetryBook(null)
        c.fromJson("""{"a":[0,5],"b":"x","c":[99,7]}""")
        assertEquals(0, c.attempts("a"))
        assertEquals(0, c.attempts("b"))
        assertEquals(RetryBackoff.MAX_ATTEMPTS, c.attempts("c"))
    }

    // ---------------------------------------------------------------- ResumeGate

    @Test fun signalChangesDoNotResume() {
        // 条件一直满足：信号强度 / 带宽变化引起的回调不再续跑
        assertFalse(ResumeGate.shouldResume(wasAllowed = true, allowed = true, lastResumeAt = 0L, now = 10 * h))
    }

    @Test fun resumesWhenConditionsComeBack() {
        assertTrue(ResumeGate.shouldResume(wasAllowed = false, allowed = true, lastResumeAt = 0L, now = 10 * h))
        assertTrue(ResumeGate.shouldResume(wasAllowed = null, allowed = true, lastResumeAt = 0L, now = 10 * h))
        assertFalse(ResumeGate.shouldResume(wasAllowed = true, allowed = false, lastResumeAt = 0L, now = 10 * h))
    }

    @Test fun resumeHasMinimumGap() {
        val t = 10 * h
        assertFalse(ResumeGate.shouldResume(false, true, lastResumeAt = t, now = t + ResumeGate.MIN_GAP_MS - 1))
        assertTrue(ResumeGate.shouldResume(false, true, lastResumeAt = t, now = t + ResumeGate.MIN_GAP_MS))
    }

    // ---------------------------------------------------------------- 增量判断

    private fun track(size: Long = 100, mtime: Long = 5, scanned: Boolean = true, tagged: Boolean = true, art: String? = "a.jpg", tagVer: Int = Music.TAG_VER) =
        Track("id", "/m/a.mp3", "a.mp3", size, mtime, "歌", "人", "专辑", artPath = art, tagged = tagged, scanned = scanned, tagVer = tagVer)

    @Test fun musicUnchangedFileIsNotReread() {
        assertTrue(Music.keepCached(track(), 100, 5, rescrape = false))
        assertTrue(Music.keepCached(track(), 100, 0, rescrape = false))      // 服务器不报修改时间：只比大小
    }

    @Test fun musicChangedOrUnreadFileIsReread() {
        assertFalse(Music.keepCached(track(), 101, 5, rescrape = false))
        assertFalse(Music.keepCached(track(), 100, 6, rescrape = false))
        assertFalse(Music.keepCached(track(scanned = false), 100, 5, rescrape = false))
        assertFalse(Music.keepCached(track(), 100, 5, rescrape = true))
    }

    @Test fun tagVerMigrationRunsOnlyOnce() {
        // 旧版读过、没标签没封面 → 重读一次
        val old = track(tagged = false, art = null, tagVer = 2)
        assertTrue(old.staleTags)
        assertFalse(Music.keepCached(old, 100, 5, rescrape = false))
        // 新版读过（真没标签）→ 之后不再重读
        val now = track(tagged = false, art = null, tagVer = Music.TAG_VER)
        assertFalse(now.staleTags)
        assertTrue(Music.keepCached(now, 100, 5, rescrape = false))
    }

    @Test fun davUnchangedLeafIsSkipped() {
        val leaf = DavFolderCache.Entry("etag1", emptyList())
        assertTrue(DavScanRules.canSkipLeaf(false, false, "etag1", leaf, hasOldMedia = true))
    }

    @Test fun davChangedOrUnknownFolderIsListed() {
        val leaf = DavFolderCache.Entry("etag1", emptyList())
        assertFalse(DavScanRules.canSkipLeaf(false, false, "etag2", leaf, true))     // 标签变了
        assertFalse(DavScanRules.canSkipLeaf(false, false, null, leaf, true))        // 服务器没给标签
        assertFalse(DavScanRules.canSkipLeaf(false, false, "etag1", null, true))     // 上次没见过
        assertFalse(DavScanRules.canSkipLeaf(false, false, "etag1", DavFolderCache.Entry("etag1", listOf("/a/b")), true))   // 不是叶子
        assertFalse(DavScanRules.canSkipLeaf(false, false, "etag1", leaf, false))    // 旧索引里没有它
        assertFalse(DavScanRules.canSkipLeaf(true, false, "etag1", leaf, true))      // 全量
        assertFalse(DavScanRules.canSkipLeaf(false, true, "etag1", leaf, true))      // 抽查
    }

    @Test fun scanLogLineHasReasonScopeTimeAndCount() {
        val l = ScanLog.line("音乐", "下拉刷新", "dav 2 个文件夹", 12_345, 3, 1, 2)
        assertTrue(l.contains("reason=下拉刷新")); assertTrue(l.contains("took=12.3s")); assertTrue(l.contains("new=3"))
        assertTrue(l.contains("failed=1")); assertTrue(l.contains("backoff=2"))
    }
}
