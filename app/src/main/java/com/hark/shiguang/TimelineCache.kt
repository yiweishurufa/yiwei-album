package com.hark.shiguang

import com.hark.shiguang.data.DayCount
import com.hark.shiguang.data.Photo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.7.24 断网时首页先显示上次的内容（参考 Immich 3.4「离线不阻塞启动」）。
 *
 * 飞牛的时间线以前每次打开都从网络现拉：断网 / NAS 关机 / 外网地址不通时首页只有一个「加载失败」。
 * 现在每次成功加载后把「日期索引 + 照片统计 + 最前面 [MAX] 张」存一份到 fn/<账号 id>/timeline-cache.json（新文件，
 * 不动任何已有存储）；下次打开先显示它，后台照常去拉新的，拉到了再整体换上，拉不到就保留缓存并提示离线。
 * 缩略图地址只存路径（去掉内网 / 外网前缀），读回来时按当前连接补全；缩略图本身走 Coil 磁盘缓存（1.7.17 稳定缓存键）。
 */
object TimelineCache {
    const val MAX = 600
    private const val VER = 1

    class Snapshot(val days: List<DayCount>, val stats: Pair<Int, Int>, val photos: List<Photo>, val savedAt: Long)

    fun file(accountId: String): File = File(App.ctx.filesDir, "fn/$accountId/timeline-cache.json")

    fun encode(s: Snapshot, rel: (String) -> String): String {
        val o = JSONObject().put("v", VER).put("at", s.savedAt).put("pc", s.stats.first).put("vc", s.stats.second)
        o.put("days", JSONArray().apply { s.days.forEach { d -> put(JSONArray().put(d.year).put(d.month).put(d.day).put(d.count)) } })
        o.put("photos", JSONArray().apply { s.photos.take(MAX).forEach { put(photoJson(it, rel)) } })
        return o.toString()
    }

    /** 版本不对 / 内容坏了 → null（当作没有缓存，照常联网加载）。 */
    fun decode(text: String, abs: (String) -> String): Snapshot? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v") != VER) return null
        val days = o.optJSONArray("days") ?: JSONArray()
        val ds = (0 until days.length()).mapNotNull { i -> days.optJSONArray(i)?.let { a -> DayCount(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3)) } }
        val ps = o.optJSONArray("photos") ?: JSONArray()
        val photos = (0 until ps.length()).mapNotNull { i -> ps.optJSONObject(i)?.let { photoOf(it, abs) } }
        Snapshot(ds, o.optInt("pc") to o.optInt("vc"), photos, o.optLong("at"))
    }.getOrNull()

    private fun photoJson(p: Photo, rel: (String) -> String) = JSONObject().put("id", p.id).put("u", p.uuid).put("v", p.isVideo).put("l", p.isLive).put("lt", p.liveType)
        .put("n", p.fileName).put("t", p.time).put("w", p.width).put("h", p.height).put("d", p.duration).put("c", p.collected)
        .put("ts", rel(p.thumbS)).put("tm", rel(p.thumbM)).put("o", rel(p.original)).put("vu", p.video?.let(rel) ?: "")
        .put("mk", p.make).put("md", p.model).put("f", p.fNumber).put("e", p.exposure).put("i", p.iso).put("fl", p.focal)
        .put("s", p.size).put("g", p.geo).put("p", p.path)

    private fun photoOf(o: JSONObject, abs: (String) -> String): Photo? {
        if (!o.has("id")) return null
        return Photo(id = o.optInt("id"), uuid = o.optString("u"), isVideo = o.optBoolean("v"), isLive = o.optBoolean("l"), liveType = o.optInt("lt"),
            fileName = o.optString("n"), time = o.optString("t"), width = o.optInt("w"), height = o.optInt("h"), duration = o.optInt("d"),
            collected = o.optBoolean("c"), thumbS = abs(o.optString("ts")), thumbM = abs(o.optString("tm")), original = abs(o.optString("o")),
            video = o.optString("vu").ifEmpty { null }?.let(abs),
            make = o.optString("mk"), model = o.optString("md"), fNumber = o.optString("f"), exposure = o.optString("e"), iso = o.optString("i"),
            focal = o.optString("fl"), size = o.optLong("s"), geo = o.optString("g"), path = o.optString("p"))
    }

    /** 当前连接的地址前缀去掉（内网 / 外网切换后照样能用）。 */
    fun relOf(url: String, base: String): String = if (base.isNotEmpty() && url.startsWith(base)) url.removePrefix(base) else url

    fun save(accountId: String, s: Snapshot) {
        if (accountId.isEmpty() || s.photos.isEmpty()) return
        val base = com.hark.shiguang.data.FnClient.baseUrl
        runCatching {
            val f = file(accountId); f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(s) { relOf(it, base) })
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { Diag.log("HOME", "timeline cache save failed: ${it.javaClass.simpleName}") }
    }

    fun load(accountId: String): Snapshot? {
        if (accountId.isEmpty()) return null
        val f = file(accountId)
        if (!f.exists()) return null
        return runCatching { decode(f.readText()) { com.hark.shiguang.data.FnClient.absOf(it) } }.getOrNull()
    }

    fun clear(accountId: String) { runCatching { file(accountId).delete() } }
}
