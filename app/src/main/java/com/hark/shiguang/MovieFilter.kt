package com.hark.shiguang

/**
 * 用户口述 1（2026-10-30）：影音搜索页的筛选逻辑（纯函数，便于测试）。
 * 分类 / 类型 / 年份的选项都从库里实际有的数据生成，没有数据的行不显示。
 * 地区：影视库目前没有存制片国家 / 地区，这一行暂不显示（存储格式不变）。
 */
object MovieFilter {
    const val ALL = "全部"
    val CATEGORY_ORDER = listOf("电视剧", "电影", "动漫", "综艺", "演唱会", "纪录片")
    val SORTS = listOf("最新更新", "最新上映", "影片评分")

    data class Query(
        val text: String = "",
        val category: String = ALL,
        val genre: String = ALL,
        val year: String = ALL,     // 全部 / 「2020年代」 / 「2024」
        val sort: Int = 0,
    )

    /** 一部片属于哪个大类：动画 → 动漫，纪录 → 纪录片，真人秀 / 脱口秀 → 综艺，片名带演唱会 → 演唱会，其余按电影 / 剧集。 */
    fun categoryOf(m: MovieItem): String {
        val g = m.genres
        val t = m.title + " " + m.originalTitle
        return when {
            g.any { it.contains("动画") } -> "动漫"
            g.any { it.contains("纪录") } -> "纪录片"
            g.any { it.contains("真人秀") || it.contains("脱口秀") || it.contains("综艺") } -> "综艺"
            t.contains("演唱会") || t.contains("concert", true) -> "演唱会"
            m.kind == "tv" -> "电视剧"
            else -> "电影"
        }
    }

    fun categories(items: List<MovieItem>): List<String> {
        val have = items.mapTo(HashSet()) { categoryOf(it) }
        return CATEGORY_ORDER.filter { it in have }
    }

    /** 类型（TMDB / NFO 的 genres），出现次数多的在前；动画 / 纪录这类已经是大类的也保留，方便组合筛。 */
    fun genres(items: List<MovieItem>): List<String> =
        items.flatMap { it.genres }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** 年份：先列年代（新的在前），再列最近的具体年份（最多 [maxYears] 个）。 */
    fun years(items: List<MovieItem>, maxYears: Int = 10): List<String> {
        val ys = items.mapNotNull { it.year }.filter { it in 1900..2100 }.distinct().sortedDescending()
        if (ys.isEmpty()) return emptyList()
        val decades = ys.map { it / 10 * 10 }.distinct().map { "${it}年代" }
        return decades.take(1) + ys.take(maxYears).map { "$it" } + decades.drop(1)
    }

    fun matchesYear(m: MovieItem, y: String): Boolean {
        if (y == ALL) return true
        val yr = m.year ?: return false
        return if (y.endsWith("年代")) y.removeSuffix("年代").toIntOrNull()?.let { yr in it..it + 9 } ?: false
        else y.toIntOrNull() == yr
    }

    fun matchesText(m: MovieItem, q: String): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return true
        return listOf(m.title, m.originalTitle, m.altQuery, m.query, m.year?.toString().orEmpty()).any { it.contains(t, ignoreCase = true) }
    }

    fun apply(items: List<MovieItem>, q: Query): List<MovieItem> {
        val l = items.filter { m ->
            matchesText(m, q.text) && (q.category == ALL || categoryOf(m) == q.category) &&
                (q.genre == ALL || q.genre in m.genres) && matchesYear(m, q.year)
        }
        return when (q.sort) {
            1 -> l.sortedWith(compareByDescending<MovieItem> { it.year ?: 0 }.thenByDescending { it.addedAt })
            2 -> l.sortedWith(compareByDescending<MovieItem> { it.rating ?: 0f }.thenByDescending { it.addedAt })
            else -> l.sortedByDescending { it.addedAt }
        }
    }

    /** 海报下的副标题：剧集「共 N 季」，电影显示年份。 */
    fun subtitle(m: MovieItem): String =
        if (m.kind == "tv") m.seasons.keys.count { it > 0 }.coerceAtLeast(if (m.seasons.isEmpty()) 0 else 1).let { if (it > 0) "共 $it 季" else "" }
        else m.year?.toString().orEmpty()
}

/** 影视搜索历史：和照片搜索分开存。 */
object MovieSearchHistory {
    private const val KEY = "movie.search.history"
    fun load(): List<String> = Store.getStr(KEY, "").split('\n').filter { it.isNotBlank() }.take(SearchHistory.MAX)
    fun add(q: String) { Store.putStr(KEY, SearchHistory.push(load(), q).joinToString("\n")) }
    fun clear() { Store.putStr(KEY, "") }
}
