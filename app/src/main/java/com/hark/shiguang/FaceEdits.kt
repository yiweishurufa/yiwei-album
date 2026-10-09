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
 *  - [hidden]: hidden groups (路人).
 */
class FaceEdits(private val dir: File) {
    val group = HashMap<String, String>()
    val excluded = HashSet<String>()
    val hidden = HashSet<String>()
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(File(dir, "edits.json").readText())
            o.optJSONObject("g")?.let { g -> g.keys().forEach { group[it] = g.getString(it) } }
            o.optJSONArray("x")?.let { a -> for (i in 0 until a.length()) excluded.add(a.getString(i)) }
            o.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) hidden.add(a.getString(i)) }
        }
    }

    @Synchronized fun save() = runCatching {
        dir.mkdirs()
        val g = JSONObject(); group.forEach { (k, v) -> g.put(k, v) }
        File(dir, "edits.json").writeText(JSONObject().put("g", g).put("x", JSONArray(excluded.toList())).put("h", JSONArray(hidden.toList())).toString())
    }

    @Synchronized fun clear() { group.clear(); excluded.clear(); hidden.clear(); loaded = true }

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

    /** Removes the faces of [p] found in [paths] from that person. */
    @Synchronized fun removePhotos(p: FacePerson, paths: Set<String>) {
        load()
        ensureGroup(p)
        p.faces.filter { it.path in paths }.forEach { excluded.add(it.id); group.remove(it.id) }
        save()
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
            val major = gs.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
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

/** 飞牛 persons: only hiding is local (the NAS has no documented merge / remove API). */
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
