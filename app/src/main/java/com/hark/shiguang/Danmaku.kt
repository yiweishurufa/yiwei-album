package com.hark.shiguang

import android.net.ConnectivityManager
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 1.0.1 #11 弹幕 — 弹弹play 开放弹幕网络 (api.dandanplay.net, reachable in mainland China without VPN).
 *
 * Auth (mandatory since 2025-02, doc.dandanplay.com/open「签名验证模式」): headers X-AppId, X-Timestamp (unix seconds, UTC),
 * X-Signature = base64(sha256(AppId + Timestamp + Path + AppSecret)); Path = the request path only (no host, no query),
 * lowercase recommended. AppId/AppSecret are the user's own (申请：dandanplay.com 开发者中心 → 应用管理), empty by default.
 *
 * Endpoints (api.dandanplay.net/swagger/v2/swagger.json, checked 2026-10-09):
 *  - POST /api/v2/match {fileName (no extension), fileHash (MD5 of the first 16 MiB), fileSize, videoDuration (s), matchMode}
 *    → {success, isMatched, matches:[{episodeId, animeId, animeTitle, episodeTitle, type, shift}]}
 *  - GET /api/v2/search/episodes?anime=&tmdbId=&tmdbIdType=(0 tv / 1 movie)&episode= → {animes:[{animeId, animeTitle, episodes:[{episodeId, episodeTitle}]}]}
 *  - GET /api/v2/comment/{episodeId}?withRelated=true&chConvert=1 (302 to the comment CDN) → {count, comments:[{cid, p:"秒,模式,颜色,用户", m}]}
 *    模式 1 滚动 / 4 底部 / 5 顶部；颜色 = R*65536 + G*256 + B.
 */
object DanmakuPrefs {
    var appId: String get() = Store.getStr("dm.appId"); set(v) = Store.putStr("dm.appId", v.trim())
    var appSecret: String get() = Store.getStr("dm.appSecret"); set(v) = Store.putStr("dm.appSecret", v.trim())
    val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
    var enabled: Boolean get() = Store.getStr("dm.on", "1") == "1"; set(v) = Store.putStr("dm.on", if (v) "1" else "0")
    var opacity: Float get() = f("dm.alpha", 0.85f); set(v) = Store.putStr("dm.alpha", "$v")
    var size: Float get() = f("dm.size", 1f); set(v) = Store.putStr("dm.size", "$v")
    /** Share of the screen height danmaku may use: 0.25 / 0.5 / 0.75 / 1. */
    var area: Float get() = f("dm.area", 0.5f); set(v) = Store.putStr("dm.area", "$v")
    var speed: Float get() = f("dm.speed", 1f); set(v) = Store.putStr("dm.speed", "$v")
    var scroll: Boolean get() = Store.getStr("dm.scroll", "1") == "1"; set(v) = Store.putStr("dm.scroll", if (v) "1" else "0")
    var top: Boolean get() = Store.getStr("dm.top", "1") == "1"; set(v) = Store.putStr("dm.top", if (v) "1" else "0")
    var bottom: Boolean get() = Store.getStr("dm.bottom", "1") == "1"; set(v) = Store.putStr("dm.bottom", if (v) "1" else "0")
    var colors: Boolean get() = Store.getStr("dm.color", "1") == "1"; set(v) = Store.putStr("dm.color", if (v) "1" else "0")
    /** File fingerprint (16 MiB download) also on metered networks. */
    var hashOnCell: Boolean get() = Store.getStr("dm.hashCell") == "1"; set(v) = Store.putStr("dm.hashCell", if (v) "1" else "")
    private fun f(k: String, d: Float) = Store.getStr(k).toFloatOrNull() ?: d
    const val HINT = "需在弹弹play开放平台申请 AppId（dev.dandanplay.com 开发者中心 → 应用管理 → 创建应用，审核通过后获得 AppId / AppSecret），填到 设置 → 影视 → 弹幕"
}

/** One comment: [t] ms from the start of the video, mode 1 scroll / 4 bottom / 5 top, RGB color. */
data class Dm(val t: Long, val mode: Int, val color: Int, val text: String)

data class DmMatch(val episodeId: Long, val anime: String, val episode: String, val shift: Double = 0.0) {
    val label: String get() = listOf(anime, episode).filter { it.isNotBlank() }.joinToString(" · ")
}

class DanmakuNotConfigured : Exception(DanmakuPrefs.HINT)

object DanDan {
    private const val BASE = "https://api.dandanplay.net"
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun signHeaders(path: String, appId: String = DanmakuPrefs.appId, secret: String = DanmakuPrefs.appSecret, now: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val sig = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest("$appId$now$path$secret".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return mapOf("X-AppId" to appId, "X-Timestamp" to "$now", "X-Signature" to sig)
    }

    private fun call(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        if (!DanmakuPrefs.configured) throw DanmakuNotConfigured()
        val u = "$BASE$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = Request.Builder().url(u).header("Accept", "application/json").header("User-Agent", "YiWeiAlbum/${BuildConfig.VERSION_NAME}")
        signHeaders(path.lowercase()).forEach { (k, v) -> rb.header(k, v) }
        if (body != null) rb.post(body.toString().toRequestBody(JSON))
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (r.code == 401 || r.code == 403) throw Exception("弹弹play 拒绝了请求（${r.code}）：AppId / AppSecret 不对，或应用还没通过审核" +
                (r.header("X-Error-Message") ?: o?.optString("errorMessage"))?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty())
            if (!r.isSuccessful) throw Exception("弹弹play HTTP ${r.code}")
            o ?: throw Exception("弹弹play 返回的不是 JSON")
            if (o.has("success") && !o.optBoolean("success", true)) throw Exception("弹弹play：" + o.optString("errorMessage").ifBlank { "错误码 ${o.optInt("errorCode")}" })
            return o
        }
    }

    fun test(): String { call("/api/v2/search/episodes", mapOf("anime" to "葬送的芙莉莲")); return "连接正常" }

    fun match(fileName: String, hash: String?, size: Long, durationSec: Int): Pair<Boolean, List<DmMatch>> {
        val b = JSONObject().put("fileName", fileName).put("fileSize", size).put("videoDuration", durationSec)
            .put("matchMode", if (hash != null) "hashAndFileName" else "fileNameOnly")
        if (hash != null) b.put("fileHash", hash) else b.put("fileHash", "00000000000000000000000000000000")
        val o = call("/api/v2/match", body = b)
        val l = o.optJSONArray("matches")?.objs().orEmpty().map { DmMatch(it.optLong("episodeId"), it.optString("animeTitle"), it.optString("episodeTitle"), it.optDouble("shift", 0.0)) }
        return o.optBoolean("isMatched") to l
    }

    /** anime → its episodes. */
    fun search(anime: String?, tmdbId: Int? = null, tmdbMovie: Boolean = false, episode: Int? = null): List<Pair<String, List<DmMatch>>> {
        val q = HashMap<String, String>()
        anime?.takeIf { it.length >= 2 }?.let { q["anime"] = it }
        tmdbId?.let { q["tmdbId"] = "$it"; q["tmdbIdType"] = if (tmdbMovie) "1" else "0" }
        episode?.let { q["episode"] = "$it" }
        if (q["anime"] == null && q["tmdbId"] == null) return emptyList()
        val o = call("/api/v2/search/episodes", q)
        return o.optJSONArray("animes")?.objs().orEmpty().map { a ->
            val title = a.optString("animeTitle")
            title to a.optJSONArray("episodes")?.objs().orEmpty().map { e -> DmMatch(e.optLong("episodeId"), title, e.optString("episodeTitle")) }
        }
    }

    /** Comments of an episode, cached 6 h in cacheDir/danmaku. */
    fun comments(episodeId: Long): List<Dm> {
        val f = File(App.ctx.cacheDir, "danmaku/$episodeId.json").apply { parentFile?.mkdirs() }
        val raw = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) f.readText()
            else call("/api/v2/comment/$episodeId", mapOf("withRelated" to "true", "chConvert" to "1")).toString().also { runCatching { f.writeText(it) } }
        val o = JSONObject(raw)
        return o.optJSONArray("comments")?.objs().orEmpty().mapNotNull { c ->
            val p = c.optString("p").split(',')
            val t = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val text = c.optString("m").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Dm((t * 1000).toLong(), p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2)?.toIntOrNull() ?: 0xFFFFFF, text)
        }.sortedBy { it.t }
    }

    /** MD5 of the first 16 MiB (dandanplay's file fingerprint) through a Range request; null when it can not be read. */
    fun fileHash(url: String, headers: Map<String, String>): String? = runCatching {
        val rb = Request.Builder().url(url).header("Range", "bytes=0-16777215")
        headers.forEach { (k, v) -> rb.header(k, v) }
        http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024); var left = 16L * 1024 * 1024
            val ins = r.body?.byteStream() ?: return null
            while (left > 0) { val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break; md.update(buf, 0, n); left -= n }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun metered(): Boolean = runCatching { App.ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
    private fun key(sourceKey: String, path: String) = MessageDigest.getInstance("MD5").digest("$sourceKey|$path".toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun remembered(sourceKey: String, path: String): DmMatch? = runCatching {
        val o = JSONObject(Store.getStr("dm.ep." + key(sourceKey, path)))
        DmMatch(o.getLong("id"), o.optString("a"), o.optString("e"), o.optDouble("s", 0.0))
    }.getOrNull()
    fun remember(sourceKey: String, path: String, m: DmMatch) =
        Store.putStr("dm.ep." + key(sourceKey, path), JSONObject().put("id", m.episodeId).put("a", m.anime).put("e", m.episode).put("s", m.shift).toString())
    fun forget(sourceKey: String, path: String) = Store.putStr("dm.ep." + key(sourceKey, path), "")

    private val epNo = Regex("第\\s*(\\d+)\\s*[话話集]|(?i)(?:ep|e|#)\\s*(\\d+)|^\\s*(\\d+)\\s*$|\\s(\\d{1,4})\\s*$")
    private fun epOf(title: String): Int? = epNo.find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    /**
     * Finds the comment library of one file: remembered choice → /match (hash when allowed + file name) → TMDB id search →
     * title search. Returns null when nothing fits well enough (the player offers 手动匹配).
     */
    fun auto(lib: MovieLib, item: MovieItem, f: MediaFile, path: String, durationMs: Long): DmMatch? {
        remembered(lib.sourceKey, path)?.let { return it }
        val ep = item.episodes.firstOrNull { it.path == path }
        val names = listOf(item.title, item.originalTitle, item.query).filter { it.isNotBlank() }
        fun animeOk(t: String) = names.any { NameParser.similarity(it, t) >= 0.6 }
        // 1) dandanplay file match (fingerprint only on unmetered networks unless allowed — it downloads 16 MiB)
        val hash = if (!metered() || DanmakuPrefs.hashOnCell) {
            val hk = "dm.h." + key(lib.sourceKey, path)
            Store.getStr(hk).ifEmpty { null } ?: lib.fs.playUrl(f)?.let { u -> fileHash(u, lib.fs.headers(u)) }?.also { Store.putStr(hk, it) }
        } else null
        val (exact, ms) = runCatching { match(NameParser.stem(f.name), hash, f.size, (durationMs / 1000).toInt()) }.getOrElse { if (it is DanmakuNotConfigured) throw it; false to emptyList() }
        val pick = when {
            exact && ms.isNotEmpty() -> ms.first()
            else -> ms.firstOrNull { m -> animeOk(m.anime) && (ep == null || epOf(m.episode) == ep.episode) }
        }
        if (pick != null) { remember(lib.sourceKey, path, pick); return pick }
        // 2) by TMDB id (dandanplay links many works to TMDB) 3) by title
        val byTmdb = item.tmdbId?.let { id -> runCatching { search(null, id, item.kind == "movie", ep?.episode) }.getOrDefault(emptyList()) }.orEmpty()
        val byTitle = if (byTmdb.isEmpty()) names.take(2).firstNotNullOfOrNull { n -> runCatching { search(n, null, false, ep?.episode) }.getOrNull()?.filter { animeOk(it.first) }?.takeIf { it.isNotEmpty() } }.orEmpty() else emptyList()
        val found = (byTmdb + byTitle).firstNotNullOfOrNull { (_, eps) ->
            if (ep == null) eps.firstOrNull() else eps.firstOrNull { epOf(it.episode) == ep.episode } ?: eps.singleOrNull()
        } ?: return null
        remember(lib.sourceKey, path, found)
        return found
    }
}
