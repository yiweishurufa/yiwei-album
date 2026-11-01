package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 优化 18（用户 2026-11-02）：全局搜索的匹配。 */
class GlobalSearchTest {
    private fun t(title: String, artist: String, album: String = "") = Track("1", "/a.mp3", "a.mp3", 1, 1, title, artist, album)
    private fun m(title: String, orig: String = "") = MovieItem("1", "movie", title, 2010, null, null, null, null, null, emptyList(), emptyList(), emptyMap(), 0L, false, originalTitle = orig)

    @Test fun `歌名歌手都能搜，多个词都要命中`() {
        val ws = GlobalSearch.words("周杰伦 晴天")
        assertTrue(GlobalSearch.track(t("晴天", "周杰伦", "叶惠美"), ws))
        assertFalse(GlobalSearch.track(t("稻香", "周杰伦"), ws))
    }

    @Test fun `不分大小写、忽略标点`() {
        assertTrue(GlobalSearch.movie(m("蝙蝠侠：黑暗骑士", "The Dark Knight"), GlobalSearch.words("dark knight")))
        assertTrue(GlobalSearch.movie(m("蝙蝠侠：黑暗骑士"), GlobalSearch.words("黑暗骑士")))
    }

    @Test fun `照片按文件名和地点`() {
        assertTrue(GlobalSearch.photo("IMG_2024.JPG", "杭州 西湖", GlobalSearch.words("西湖")))
        assertFalse(GlobalSearch.photo("IMG_2024.JPG", "", GlobalSearch.words("西湖")))
    }

    @Test fun `空查询不命中`() = assertFalse(GlobalSearch.track(t("a", "b"), GlobalSearch.words("  ")))

    @Test fun `完全相同的片名排前面`() {
        val l = listOf(m("星际穿越前传"), m("星际穿越"))
        assertEquals("星际穿越", GlobalSearch.rank(l, GlobalSearch.words("星际穿越")) { it.title }.first().title)
    }
}
