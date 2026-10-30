package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieFilterTest {
    private fun m(id: String, kind: String = "movie", title: String = id, year: Int? = null, genres: List<String> = emptyList(),
                  rating: Float? = null, added: Long = 0, seasons: Map<Int, List<Episode>> = emptyMap()) =
        MovieItem(id, kind, title, year, null, null, null, null, rating, genres, emptyList(), seasons, added, true)

    @Test fun categories() {
        assertEquals("动漫", MovieFilter.categoryOf(m("a", genres = listOf("动画", "喜剧"))))
        assertEquals("纪录片", MovieFilter.categoryOf(m("b", kind = "tv", genres = listOf("纪录"))))
        assertEquals("电视剧", MovieFilter.categoryOf(m("c", kind = "tv")))
        assertEquals("演唱会", MovieFilter.categoryOf(m("d", title = "周杰伦演唱会")))
        assertEquals(listOf("电视剧", "电影"), MovieFilter.categories(listOf(m("x"), m("y", kind = "tv"))))
    }

    @Test fun yearsOnlyFromLibrary() {
        val l = listOf(m("a", year = 2024), m("b", year = 2019), m("c", year = 2024), m("d"))
        assertEquals(listOf("2020年代", "2024", "2019", "2010年代"), MovieFilter.years(l))
        assertTrue(MovieFilter.years(listOf(m("e"))).isEmpty())
        assertTrue(MovieFilter.matchesYear(m("f", year = 2015), "2010年代"))
    }

    @Test fun filterAndSort() {
        val l = listOf(m("a", title = "武林外传", kind = "tv", year = 2006, rating = 9.6f, added = 1, genres = listOf("喜剧")),
            m("b", title = "玩具总动员", year = 2026, rating = 8f, added = 3, genres = listOf("动画")),
            m("c", title = "老九门", kind = "tv", year = 2016, rating = 7.2f, added = 2))
        assertEquals(listOf("b", "c", "a"), MovieFilter.apply(l, MovieFilter.Query()).map { it.id })
        assertEquals(listOf("a", "b", "c"), MovieFilter.apply(l, MovieFilter.Query(sort = 2)).map { it.id })
        assertEquals(listOf("a"), MovieFilter.apply(l, MovieFilter.Query(text = "武林")).map { it.id })
        assertEquals(listOf("c", "a"), MovieFilter.apply(l, MovieFilter.Query(category = "电视剧")).map { it.id })
        assertEquals(listOf("a"), MovieFilter.apply(l, MovieFilter.Query(genre = "喜剧")).map { it.id })
    }

    @Test fun subtitle() {
        assertEquals("共 2 季", MovieFilter.subtitle(m("a", kind = "tv", seasons = mapOf(1 to emptyList(), 2 to emptyList()))))
        assertEquals("2026", MovieFilter.subtitle(m("b", year = 2026)))
    }
}
