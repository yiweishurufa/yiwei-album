package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

/** 优化 14（用户 2026-11-02）：多版本同一部电影合并成一张海报。 */
class MovieMergeTest {
    private fun m(id: String, title: String, year: Int?, tmdb: Int?, poster: String? = null, kind: String = "movie") =
        MovieItem(id, kind, title, year, tmdb, poster, null, null, null, emptyList(), listOf(MediaFile("/$id.mkv", "$id.mkv", 1)), emptyMap(), 0L, tmdb != null)

    @Test fun `同 TMDB id 合并，带海报的当代表`() {
        val g = MovieMerge.group(listOf(m("a", "盗梦空间", 2010, 27205), m("b", "Inception", 2010, 27205, poster = "/p.jpg"), m("c", "星际穿越", 2014, 157336)))
        assertEquals(2, g.size)
        assertEquals(listOf("b", "a"), g[0].map { it.id })
        assertEquals(listOf("c"), g[1].map { it.id })
    }

    @Test fun `没有 TMDB 时同名同年合并，标点空格不影响`() {
        val g = MovieMerge.group(listOf(m("a", "蝙蝠侠：黑暗骑士", 2008, null), m("b", "蝙蝠侠 黑暗骑士", 2008, null)))
        assertEquals(1, g.size)
    }

    @Test fun `没有年份或年份不同不合并`() {
        assertEquals(2, MovieMerge.group(listOf(m("a", "家", null, null), m("b", "家", null, null))).size)
        assertEquals(2, MovieMerge.group(listOf(m("a", "沙丘", 1984, null), m("b", "沙丘", 2021, null))).size)
    }

    @Test fun `剧集不合并`() = assertEquals(2, MovieMerge.group(listOf(m("a", "三体", 2023, 1, kind = "tv"), m("b", "三体", 2023, 1, kind = "tv"))).size)

    @Test fun `兄弟条目`() {
        val all = listOf(m("a", "x", 2000, 5), m("b", "y", 2000, 5), m("c", "z", 2000, 6))
        assertEquals(listOf("b"), MovieMerge.siblings(all, all[0]).map { it.id })
    }
}
