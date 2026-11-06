package com.hark.shiguang

/**
 * 全面审查 A2：音乐「我喜欢」和「最近播放」。
 * 每个来源各存一份（filesDir/{dav|fn}/<id>/music/favorites.json、recent.json，内容是曲目 id 数组，新的在前），
 * 由 [MusicLib] 读写；这里只放纯逻辑（单元测试：MusicMarksTest）。
 */
object MusicMarks {
    /** 最近播放最多记多少首。 */
    const val RECENT_MAX = 100

    /** 把 [id] 放到最前面（已在列表里就挪上来），超出 [max] 的旧条目丢掉。 */
    fun push(list: List<String>, id: String, max: Int = RECENT_MAX): List<String> =
        if (id.isEmpty()) list else (listOf(id) + list.filter { it != id }).take(max)

    /** 喜欢 / 取消喜欢：在列表里就移除，不在就加到最前面。 */
    fun toggle(list: List<String>, id: String): List<String> =
        if (id in list) list.filter { it != id } else listOf(id) + list
}
