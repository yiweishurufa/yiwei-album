package com.hark.shiguang

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.hark.shiguang.data.Photo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manual person adjustments for one WebDAV account (1.0.9). Stored next to faces.json as edits.json.
 * Face ids are deterministic (photo path hash + index), so the edits keep working after a re-scan.
 *  - [group]: face id -> manual group id. Merging two persons puts all their faces in one group; a
 *    cluster that contains grouped faces follows the group of the majority, so newly detected faces of
 *    a merged person join it automatically. A grouped face always lands in its own group (manual wins).
 *  - [excluded]: faces removed from a person ("不是这个人"); never shown in any person again.
 *  - [excludedFrom]: 第 4 批：被移出的脸属于哪个手动分组（移出时记下），「撤销移出」时按它恢复。
 *    没有它，移出就是永久的（旧行为）。组 id 被合并改写后，恢复仍回到移出那一刻的组 id。
 *  - [hidden]: hidden groups (路人).
 */
class FaceEdits(private val dir: File) {
    val group = HashMap<String, String>()
    val excluded = HashSet<String>()
    val excludedFrom = HashMap<String, String>()
    val hidden = HashSet<String>()
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(File(dir, "edits.json").readText())
            o.optJSONObject("g")?.let { g -> g.keys().forEach { group[it] = g.getString(it) } }
            o.optJSONArray("x")?.let { a -> for (i in 0 until a.length()) excluded.add(a.getString(i)) }
            o.optJSONObject("xf")?.let { x -> x.keys().forEach { excludedFrom[it] = x.getString(it) } }
            o.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) hidden.add(a.getString(i)) }
        }
    }

    @Synchronized fun save() = runCatching {
        dir.mkdirs()
        val g = JSONObject(); group.forEach { (k, v) -> g.put(k, v) }
        val xf = JSONObject(); excludedFrom.forEach { (k, v) -> xf.put(k, v) }
        File(dir, "edits.json").writeText(JSONObject().put("g", g).put("x", JSONArray(excluded.toList())).put("xf", xf).put("h", JSONArray(hidden.toList())).toString())
    }

    @Synchronized fun clear() { group.clear(); excluded.clear(); excludedFrom.clear(); hidden.clear(); loaded = true }

    private fun newId() = "g" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

    /** Gives [p] a manual group (if it has none) so it keeps its identity through later edits. */
    @Synchronized fun ensureGroup(p: FacePerson): String {
        load()
        val g = p.group.ifEmpty { p.faces.firstNotNullOfOrNull { group[it.id] } ?: newId() }
        p.faces.forEach { if (it.id !in excluded) group[it.id] = g }
        return g
    }

    /** Merges [from] into [into]. Returns the group id of the merged person. */
    @Synchronized fun merge(into: FacePerson, from: FacePerson): String {
        load()
        val g = ensureGroup(into)
        val old = from.group
        from.faces.forEach { group[it.id] = g }
        if (old.isNotEmpty()) group.replaceAll { _, v -> if (v == old) g else v }
        if (old in hidden && into.group !in hidden) hidden.remove(old)
        save(); return g
    }

    /** Removes the faces of [p] found in [paths] from that person. 第 4 批：移出时记下原分组，[unexclude] 可撤销。 */
    @Synchronized fun removePhotos(p: FacePerson, paths: Set<String>) {
        load()
        ensureGroup(p)
        p.faces.filter { it.path in paths }.forEach { excluded.add(it.id); excludedFrom[it.id] = group[it.id].orEmpty(); group.remove(it.id) }
        save()
    }

    /** 第 4 批：撤销移出——把 [faceIds] 恢复到它们被移出时的分组。返回实际恢复的张数。 */
    @Synchronized fun unexclude(faceIds: List<String>): Int {
        load()
        var n = 0
        faceIds.forEach { id ->
            if (excluded.remove(id)) {
                excludedFrom.remove(id)?.takeIf { it.isNotEmpty() }?.let { group[id] = it }
                n++
            }
        }
        if (n > 0) save()
        return n
    }

    /** 第 4 批：从 [groupId] 分组里被移出的脸 id（供人物页「撤销移出（N 张）」用）。 */
    @Synchronized fun excludedForGroup(groupId: String): List<String> {
        load()
        if (groupId.isEmpty()) return emptyList()
        return excluded.filter { excludedFrom[it] == groupId }
    }

    /**
     * 第 4 批：拆分人物——把 [p] 里照片路径在 [paths] 中的脸拆成一个新人物（新分组 id）。
     * 全选/空选时返回 0（不拆分）；拆完需调 cluster() 重新归类。移出（removePhotos）是「这张不是 TA」，
     * 拆分是「这是另一个人」：被拆的脸仍属于某个人物，只是换了组。
     */
    @Synchronized fun splitFrom(p: FacePerson, paths: Set<String>): Int {
        load()
        ensureGroup(p)
        val sel = p.faces.filter { it.path in paths && it.id !in excluded }
        if (sel.isEmpty() || sel.size >= p.faces.size) return 0
        val ng = newId()
        sel.forEach { group[it.id] = ng }
        save()
        return sel.size
    }

    @Synchronized fun hide(p: FacePerson, on: Boolean) {
        load()
        val g = ensureGroup(p)
        if (on) hidden.add(g) else hidden.remove(g)
        save()
    }

    /**
     * Applies the edits to fresh clusters. Returns (visible persons, hidden persons).
     * Manual groups are shown whatever the minimum-photos setting says.
     */
    @Synchronized fun build(
        clusters: List<List<FaceRec>>, byPath: Map<String, Photo>, names: Map<String, String>, isHiddenPhoto: (String) -> Boolean,
    ): Pair<List<FacePerson>, List<FacePerson>> {
        load()
        val groups = LinkedHashMap<String, MutableList<FaceRec>>()
        val plain = ArrayList<List<FaceRec>>()
        for (c0 in clusters) {
            val c = c0.filter { it.id !in excluded }
            if (c.isEmpty()) continue
            val gs = c.mapNotNull { group[it.id] }
            if (gs.isEmpty()) { plain.add(c); continue }
            val major = gs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: gs.first()
            c.forEach { f -> groups.getOrPut(group[f.id] ?: major) { mutableListOf() }.add(f) }
        }
        fun person(m: List<FaceRec>, g: String): FacePerson? {
            val sorted = m.sortedByDescending { it.conf }
            val ps = sorted.map { it.path }.distinct().filter { !isHiddenPhoto(it) }
            if (ps.isEmpty()) return null
            val anchor = sorted.firstOrNull { names.containsKey(it.id) } ?: sorted.first()
            return FacePerson(anchor.id, names[anchor.id] ?: "", sorted.first().id, sorted, ps.mapNotNull { byPath[it] }.sortedByDescending { it.time }, g)
        }
        val vis = ArrayList<FacePerson>(); val hid = ArrayList<FacePerson>()
        groups.forEach { (g, m) -> person(m, g)?.let { if (g in hidden) hid.add(it) else vis.add(it) } }
        plain.forEach { m ->
            if (m.map { it.path }.distinct().size < FaceConfig.minPhotos) return@forEach
            person(m, "")?.let { vis.add(it) }
        }
        val order = compareBy<FacePerson>({ it.name.isEmpty() }, { -it.photos.size })
        return vis.sortedWith(order) to hid.sortedWith(order)
    }
}

/**
 * 飞牛 persons: only hiding is local (the NAS has no documented merge / remove API).
 * 第 4 批：维持现状，不硬编接口。飞牛官方接口只覆盖人物列表/照片/隐藏相关调用，
 * 没有找到「合并人物」「把某张照片移出人物」的公开接口；抓包确认前不在 App 里编一个假调用。
 * 人物菜单里因此只给「隐藏（路人）」，并在「合并 / 移出」入口上用一句话说明原因，而不是假装能点。
 */
object FnPersonHide {
    private fun key() = "fn.hiddenPersons." + NasAccounts.currentId
    var version by androidx.compose.runtime.mutableIntStateOf(0)
    fun ids(): Set<Int> = Store.getStr(key()).split(',').mapNotNull { it.toIntOrNull() }.toSet()
    fun has(id: Int) = id in ids()
    fun set(id: Int, hide: Boolean) {
        val s = ids().toMutableSet(); if (hide) s.add(id) else s.remove(id)
        Store.putStr(key(), s.joinToString(",")); version++
    }
}
