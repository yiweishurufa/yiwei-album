package com.hark.shiguang

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 优化 5（用户 2026-11-02）：网盘「最近删除」。 */
class DavRecycleTest {
    private val day = 86_400_000L

    @Test fun `30 天后过期`() {
        val now = 100 * day
        val l = listOf(DavRecycle.Entry("/a.jpg", "/.yiwei-recycle/1_a.jpg", now - 31 * day, "a.jpg", 1, false),
            DavRecycle.Entry("/b.jpg", "", now - 3 * day, "b.jpg", 1, false))
        assertEquals(listOf("/a.jpg"), DavRecycle.expired(l, now).map { it.orig })
        assertEquals(27, l[1].daysLeft(now))
    }

    @Test fun `移动失败的记为待删除`() {
        assertTrue(DavRecycle.Entry("/b.jpg", "", 0, "b.jpg", 1, false).pending)
        assertFalse(DavRecycle.Entry("/b.jpg", "/.yiwei-recycle/1_b.jpg", 0, "b.jpg", 1, false).pending)
    }

    @Test fun `记录可往返`() {
        val e = DavRecycle.Entry("/相册/晴天.mp4", "/.yiwei-recycle/5_晴天.mp4", 5, "晴天.mp4", 99, true)
        assertEquals(e, DavRecycle.Entry.of(JSONObject(e.json().toString())))
    }

    @Test fun `回收站文件名带删除时间`() = assertEquals("123_IMG 1.JPG", DavRecycle.trashName("IMG 1.JPG", 123))
}
