package com.hark.shiguang

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.Photo
import org.json.JSONObject
import java.io.File

/**
 * 隐藏相册 (1.0.9): a local list per source. WebDAV keys = file path, 飞牛 keys = photo id (飞牛 has no
 * hidden-album API we know of, so the list stays on this phone). Hidden photos are left out of the
 * timeline, albums, search, persons, places, memories and the map; they only show in the hidden album,
 * which asks for fingerprint / screen lock first.
 */
object Hidden {
    var version by mutableIntStateOf(0)
    private val sets = HashMap<String, LinkedHashMap<String, JSONObject>>()

    private fun dir() = File(App.ctx.filesDir, "hidden").apply { mkdirs() }
    private fun fnSrc() = "fn_" + NasAccounts.currentId.ifEmpty { "default" }
    private fun davSrc(acc: String) = "dav_$acc"
    fun srcOf(p: Photo) = if (p.isCloud) davSrc(p.source) else fnSrc()
    private fun itemKey(p: Photo) = if (p.isCloud) p.cloudPath else p.id.toString()

    @Synchronized private fun set(src: String): LinkedHashMap<String, JSONObject> = sets.getOrPut(src) {
        val m = LinkedHashMap<String, JSONObject>()
        runCatching {
            val o = JSONObject(File(dir(), "$src.json").readText())
            o.keys().forEach { m[it] = o.getJSONObject(it) }
        }
        m
    }

    @Synchronized private fun save(src: String) = runCatching {
        val o = JSONObject(); set(src).forEach { (k, v) -> o.put(k, v) }
        File(dir(), "$src.json").writeText(o.toString())
    }

    fun has(p: Photo): Boolean = set(srcOf(p)).containsKey(itemKey(p))
    fun hasDav(acc: String, path: String): Boolean = set(davSrc(acc)).containsKey(path)
    fun count(): Int = set(currentSrc()).size
    fun currentSrc(): String = if (com.hark.shiguang.ui.HomeState.source == "dav") davSrc(Dav.current?.id ?: "") else fnSrc()

    fun hide(ps: List<Photo>, on: Boolean) {
        ps.groupBy { srcOf(it) }.forEach { (src, l) ->
            val s = set(src)
            l.forEach { p -> if (on) s[itemKey(p)] = toJson(p) else s.remove(itemKey(p)) }
            save(src)
        }
        version++
        // persons are rebuilt without hidden photos
        ps.filter { it.isCloud }.map { it.source }.distinct().forEach { acc -> runCatching { Faces.lib(acc).cluster(Dav.lib(acc)) } }
    }

    /** Hidden photos of the source the home screen shows. */
    fun photos(): List<Photo> {
        val src = currentSrc()
        val s = set(src)
        return if (src.startsWith("dav_")) {
            val lib = Dav.lib() ?: return emptyList()
            lib.photos.filter { s.containsKey(it.cloudPath) }
        } else s.values.mapNotNull { runCatching { fromJson(it) }.getOrNull() }.sortedByDescending { it.time }
    }

    /** Drops hidden photos from [l]. Reads [version] so composables recompute after a change. */
    fun visible(l: List<Photo>): List<Photo> {
        version
        if (l.isEmpty()) return l
        // fast path for large indexes: nothing hidden in any source these photos come from
        val srcs = HashSet<String>(); for (p in l) { srcs.add(srcOf(p)); if (srcs.size > 3) break }
        if (srcs.all { set(it).isEmpty() }) return l
        return l.filter { !has(it) }
    }

    private fun toJson(p: Photo) = JSONObject().put("id", p.id).put("u", p.uuid).put("v", p.isVideo).put("l", p.isLive).put("n", p.fileName)
        .put("t", p.time).put("w", p.width).put("h", p.height).put("d", p.duration).put("s", p.size).put("p", p.path)
        .put("mk", p.make).put("md", p.model).put("cp", p.cloudPath).put("src", p.source)

    private fun fromJson(o: JSONObject): Photo {
        val id = o.getInt("id"); val uuid = o.optString("u"); val video = o.optBoolean("v")
        val base = "/p/api/v1/stream/p/t/$id"
        return Photo(id = id, uuid = uuid, isVideo = video, isLive = o.optBoolean("l"), fileName = o.optString("n"), time = o.optString("t"),
            width = o.optInt("w"), height = o.optInt("h"), duration = o.optInt("d"), collected = false,
            thumbS = FnClient.abs("$base/s/$uuid")!!, thumbM = FnClient.abs("$base/m/$uuid")!!, original = FnClient.abs("$base/o/$uuid")!!,
            video = if (video) FnClient.abs("/p/api/v1/stream/v/$id") else null,
            make = o.optString("mk"), model = o.optString("md"), fNumber = "", exposure = "", iso = "", focal = "", size = o.optLong("s"), geo = "", path = o.optString("p"))
    }
}
