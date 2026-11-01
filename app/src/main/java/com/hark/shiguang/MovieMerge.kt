package com.hark.shiguang

/**
 * 优化 14（用户 2026-11-02）：同一部电影在片库里出现多次（放在不同文件夹、不同清晰度、不同来源目录）时合并成一张海报。
 * 只在展示层合并，不改片库存储格式：按 TMDB id 合并；没有 TMDB id 时按「规范化片名 + 年份」合并（没有年份不合并，防止误并）。
 * 只合并电影；剧集的同名不同季另有季 / 集结构，不在这里合并。纯逻辑，见 MovieMergeTest。
 */
object MovieMerge {
    fun key(m: MovieItem): String? {
        if (m.kind != "movie") return null
        m.tmdbId?.let { return "t:$it" }
        val y = m.year ?: return null
        val t = norm(m.title).ifEmpty { return null }
        return "n:$t|$y"
    }

    /** 片名规范化：去空白、标点和大小写差异（「蝙蝠侠：黑暗骑士」=「蝙蝠侠 黑暗骑士」）。 */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Groups [items] (order kept by each group's first member); the representative is moved to the front of its group. */
    fun group(items: List<MovieItem>): List<List<MovieItem>> {
        val byKey = LinkedHashMap<String, MutableList<MovieItem>>()
        val out = ArrayList<MutableList<MovieItem>>()
        for (m in items) {
            val k = key(m)
            if (k == null) { out.add(mutableListOf(m)); continue }
            val g = byKey[k]
            if (g == null) { val n = mutableListOf(m); byKey[k] = n; out.add(n) } else g.add(m)
        }
        return out.map { g -> if (g.size == 1) g else listOf(representative(g)) + (g - representative(g)) }
    }

    /** The member whose poster / details the merged card shows: matched with a poster first, then the most files. */
    fun representative(g: List<MovieItem>): MovieItem =
        g.maxWithOrNull(compareBy<MovieItem>({ it.poster != null }, { it.matched }, { it.overview != null }, { it.files.size })) ?: g.first()

    /** Other library items merged with [m] (empty when it stands alone). */
    fun siblings(items: List<MovieItem>, m: MovieItem): List<MovieItem> {
        val k = key(m) ?: return emptyList()
        return items.filter { it.id != m.id && key(it) == k }
    }
}
