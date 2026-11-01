package com.hark.shiguang

/**
 * 优化 18（用户 2026-11-02）：发现页一个搜索框同时搜照片、影视、歌曲。纯逻辑（见 GlobalSearchTest）。
 * 匹配：不分大小写、忽略空格和标点；多个词（空格分开）都要命中；影视看片名 / 原名 / 文件名解析出的名字 / 演员，
 * 歌曲看歌名 / 歌手 / 专辑 / 文件名，照片看文件名和地点。
 */
object GlobalSearch {
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    fun words(q: String): List<String> = q.trim().split(Regex("[\\s,，、]+")).map(::norm).filter { it.isNotEmpty() }

    private fun hit(fields: List<String>, ws: List<String>): Boolean {
        if (ws.isEmpty()) return false
        val hay = fields.joinToString("\u0001") { norm(it) }
        return ws.all { hay.contains(it) }
    }

    fun movie(m: MovieItem, ws: List<String>): Boolean = hit(listOf(m.title, m.originalTitle, m.query) + m.cast.take(8).map { it.name }, ws)
    fun track(t: Track, ws: List<String>): Boolean = hit(listOf(t.title, t.artist, t.album, t.albumArtist, t.name), ws)
    fun photo(fileName: String, place: String, ws: List<String>): Boolean = hit(listOf(fileName, place), ws)

    /** Exact title matches first, then the rest in library order. */
    fun <T> rank(l: List<T>, ws: List<String>, title: (T) -> String): List<T> {
        val q = ws.joinToString("")
        return l.sortedBy { x -> val t = norm(title(x)); when { t == q -> 0; t.startsWith(q) -> 1; else -> 2 } }
    }
}
