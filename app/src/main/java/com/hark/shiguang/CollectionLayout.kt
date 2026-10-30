package com.hark.shiguang

/**
 * 用户口述 3（2026-10-30）：照片「合集」页的分区顺序和折叠状态，存在本机（Store），不上传。
 * 纯函数部分放这里方便测试。
 */
object CollectionLayout {
    /** 默认顺序：回忆（那年今日 / 旅行）置顶，然后相册、人物、地点、事物场景、资料记录、标签、媒体类型。 */
    val DEFAULT = listOf("memories", "albums", "people", "places", "scene", "info", "tags", "media")
    val TITLES = mapOf(
        "memories" to "回忆", "albums" to "相册", "people" to "人物", "places" to "地点",
        "scene" to "事物场景", "info" to "资料记录", "tags" to "标签", "media" to "媒体类型",
    )

    /** 按保存的顺序排，保存里没有的新分区按默认位置补在后面，不认识的丢掉。 */
    fun order(saved: String, all: List<String> = DEFAULT): List<String> {
        val s = saved.split(',').map { it.trim() }.filter { it in all }.distinct()
        return s + all.filter { it !in s }
    }

    /** 把 [id] 往前（[delta] < 0）或往后挪一位；到头了不动。 */
    fun move(list: List<String>, id: String, delta: Int): List<String> {
        val i = list.indexOf(id); val j = i + delta
        if (i < 0 || j !in list.indices) return list
        return list.toMutableList().apply { removeAt(i); add(j, id) }
    }

    fun parseSet(saved: String): Set<String> = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private const val ORDER_KEY = "collections.order"
    private const val FOLD_KEY = "collections.folded"
    fun loadOrder(): List<String> = order(Store.getStr(ORDER_KEY))
    fun saveOrder(l: List<String>) = Store.putStr(ORDER_KEY, l.joinToString(","))
    fun loadFolded(): Set<String> = parseSet(Store.getStr(FOLD_KEY))
    fun saveFolded(s: Set<String>) = Store.putStr(FOLD_KEY, s.joinToString(","))
}
