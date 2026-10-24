package com.hark.shiguang.ui

import com.hark.shiguang.NavEntry
import com.hark.shiguang.Route
import org.junit.Assert.*
import org.junit.Test

/** 用户 2026-10-24 要求继续优化：页面状态保存只存小数据（改过的勾选 key），并且按栈条目区分。 */
class PageStateTest {
    @Test fun `勾选改动编码再解码不变`() {
        val m = linkedMapOf("dav:1:/a.jpg" to true, "dav:2:/b 1.jpg" to false, "0x" to true)
        assertEquals(m, SelOverrides.decode(SelOverrides.encode(m)))
    }

    @Test fun `超过上限只保存前 MAX_SAVED 条，防止 Bundle 过大`() {
        val m = (0 until SelOverrides.MAX_SAVED + 500).associate { "k$it" to (it % 2 == 0) }
        val enc = SelOverrides.encode(m)
        assertEquals(SelOverrides.MAX_SAVED, enc.size)
        assertEquals(SelOverrides.MAX_SAVED, SelOverrides.decode(enc).size)
    }

    @Test fun `坏数据被忽略`() {
        assertTrue(SelOverrides.decode(listOf("", "1", "xkey", "2abc")).isEmpty())
        assertEquals(mapOf("ok" to false), SelOverrides.decode(listOf("0ok", "z")))
    }

    @Test fun `NavEntry 按条目 key 判等，不比较路由内容`() {
        assertEquals(NavEntry(Route.Settings, "3:Settings"), NavEntry(Route.Backup, "3:Settings"))
        assertNotEquals(NavEntry(Route.Settings, "3:Settings"), NavEntry(Route.Settings, "4:Settings"))
        assertEquals(NavEntry(Route.Settings, "k").hashCode(), NavEntry(Route.Search, "k").hashCode())
    }

    @Test fun `首页 tab 状态 key 区分来源和照片页视频筛选`() {
        assertEquals("tab0-dav", homeTabKey(0, false, "dav"))
        assertEquals("tab0v-dav", homeTabKey(0, true, "dav"))
        assertEquals("tab3-fn", homeTabKey(3, true, "fn"))   // 视频筛选只影响照片 tab
        assertNotEquals(homeTabKey(3, false, "fn"), homeTabKey(3, false, "dav"))
    }
}
