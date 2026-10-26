package com.hark.shiguang

/** 搜索历史：最近 10 条，去重，新的在前。存 Store 一个字符串（换行分隔）。 */
object SearchHistory {
    const val MAX = 10
    fun push(list: List<String>, q: String): List<String> {
        val t = q.trim().replace('\n', ' ')
        if (t.isEmpty()) return list
        return (listOf(t) + list.filter { it != t }).take(MAX)
    }
    fun load(): List<String> = Store.getStr("search.history", "").split('\n').filter { it.isNotBlank() }.take(MAX)
    fun add(q: String) { Store.putStr("search.history", push(load(), q).joinToString("\n")) }
    fun clear() { Store.putStr("search.history", "") }
}
