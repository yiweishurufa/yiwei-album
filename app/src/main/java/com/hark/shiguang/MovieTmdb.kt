package com.hark.shiguang

import androidx.compose.runtime.*
import com.hark.shiguang.cloud.CloudEntry
import com.hark.shiguang.cloud.WebDavSource
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import com.hark.shiguang.ui.CloudPhotos
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// 1.1.0 拆分自 Movies.kt（只移动代码，行为不变）：TMDB 刮削接口。
// ============================================================ TMDB

object Tmdb {
    // H9: derived from FnClient.http — shared connection pool, inherits NetEnv dns/socketFactory
    val http: OkHttpClient = com.hark.shiguang.data.FnClient.http.newBuilder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    var key: String get() = Store.getStr("tmdb.key"); set(v) = Store.putStr("tmdb.key", v.trim())
    var apiBase: String get() = Store.getStr("tmdb.api"); set(v) = Store.putStr("tmdb.api", v.trim().trimEnd('/'))
    var imgBase: String get() = Store.getStr("tmdb.img"); set(v) = Store.putStr("tmdb.img", v.trim().trimEnd('/'))
    val configured: Boolean get() = key.isNotBlank()
    /** 1.0.1: the base is chosen by [TmdbNet] (直连 or an automatically verified proxy). */
    private val api: String get() = TmdbNet.apiBase()
    /** v4 read-access tokens are JWTs ("eyJ…"); v3 keys are 32 hex chars. */
    private val bearer: Boolean get() = key.startsWith("eyJ") || key.length > 40

    /** Canonical image.tmdb.org url (stored as is); [TmdbNet.imgUrl] maps it onto the current image base when loading. */
    fun image(path: String?, size: String): String? = path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "${TmdbNet.DIRECT_IMG}/t/p/$size$it" }

    private fun get(path: String, q: Map<String, String> = emptyMap()): JSONObject = try { get0(path, q) } catch (e: java.io.IOException) {
        // network-level failure through the current base: re-probe once (direct ↔ proxy) and retry when it changed
        if (TmdbNet.onFailure()) get0(path, q) else throw java.io.IOException("连不上 TMDB（${TmdbNet.describe()}）：${e.message ?: "网络错误"}")
    }

    private fun get0(path: String, q: Map<String, String> = emptyMap()): JSONObject {
        val u = ("$api/3$path").toHttpUrlOrNull()?.newBuilder() ?: error("TMDB 地址无效")
        q.forEach { (k, v) -> u.addQueryParameter(k, v) }
        u.addQueryParameter("language", "zh-CN")
        if (!bearer) u.addQueryParameter("api_key", key)
        val rb = Request.Builder().url(u.build()).header("Accept", "application/json")
        if (bearer) rb.header("Authorization", "Bearer $key")
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(rb.build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (r.code == 401) throw IllegalStateException("TMDB 密钥无效")
                    if (r.code == 429) { Thread.sleep(1200L * (attempt + 1)); throw java.io.IOException("TMDB 请求过快") }
                    if (r.code in 400..499) throw IllegalArgumentException("TMDB HTTP ${r.code}")
                    if (!r.isSuccessful) throw java.io.IOException("TMDB HTTP ${r.code}")
                    return JSONObject(body)
                }
            } catch (e: IllegalStateException) { throw e } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { last = e; Thread.sleep(400L * (attempt + 1)) }
        }
        throw java.io.IOException(last?.message ?: "网络错误")
    }

    data class Hit(val id: Int, val kind: String, val title: String, val original: String, val year: Int?, val poster: String?, val overview: String, val vote: Float, val popularity: Double) {
        fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("t", title).put("o", original).put("y", year ?: 0).put("p", poster ?: "").put("pop", popularity)
        companion object {
            fun of(o: JSONObject) = Hit(o.optInt("id"), o.optString("k", "movie"), o.optString("t"), o.optString("o"), o.optInt("y").takeIf { it > 0 },
                o.optString("p").ifEmpty { null }, "", 0f, o.optDouble("pop", 0.0))
        }
    }

    fun search(kind: String, query: String, year: Int?): List<Hit> {
        val tv = kind == "tv"
        val q = HashMap<String, String>().apply { put("query", query); put("include_adult", "false"); if (year != null) put(if (tv) "first_air_date_year" else "year", "$year") }
        val r = get(if (tv) "/search/tv" else "/search/movie", q).optJSONArray("results") ?: JSONArray()
        return r.objs().map { o ->
            Hit(o.optInt("id"), kind, o.optString(if (tv) "name" else "title"), o.optString(if (tv) "original_name" else "original_title"),
                o.optString(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull(), o.optString("poster_path").ifEmpty { null },
                o.optString("overview"), o.optDouble("vote_average", 0.0).toFloat(), o.optDouble("popularity", 0.0))
        }
    }

    /** Other titles TMDB knows (translations / AKAs), for similarity. */
    fun altTitles(kind: String, id: Int): List<String> = runCatching {
        val o = get(if (kind == "tv") "/tv/$id/alternative_titles" else "/movie/$id/alternative_titles")
        (o.optJSONArray("titles") ?: o.optJSONArray("results"))?.objs().orEmpty().map { it.optString("title") }.filter { it.isNotBlank() }.take(30)
    }.getOrDefault(emptyList())

    data class Scored(val hit: Hit, val score: Double, val sim: Double, val yearOk: Boolean)

    /**
     * 1.0.1 #6: candidates for [queries] (Chinese / English / whole), year-filtered first, scored by
     * normalized title similarity (title, original_title, alternative titles of the top few), year proximity and popularity.
     */
    fun rank(kind: String, queries: List<String>, year: Int?): List<Scored> {
        val pool = LinkedHashMap<Int, Hit>()
        for (q in queries) {
            val hs = search(kind, q, year).take(8)
            hs.forEach { pool.putIfAbsent(it.id, it) }
            if (hs.any { h -> year != null && h.year == year && queries.any { NameParser.similarity(it, h.title) >= 0.95 || NameParser.similarity(it, h.original) >= 0.95 } }) break
        }
        if (year != null && pool.size < 3) for (q in queries.take(2)) search(kind, q, null).take(8).forEach { pool.putIfAbsent(it.id, it) }
        fun simOf(h: Hit, extra: List<String> = emptyList()) = queries.maxOfOrNull { q -> (listOf(h.title, h.original) + extra).maxOf { NameParser.similarity(q, it) } } ?: 0.0
        fun yearScore(h: Hit) = when {
            year == null || h.year == null -> 0.0
            h.year == year -> 0.2
            kotlin.math.abs(h.year - year) == 1 -> 0.1      // festival vs. release year, first air vs. season year
            else -> -0.3
        }
        fun pop(h: Hit) = minOf(kotlin.math.log10(h.popularity + 1.0) / 3.0, 1.0) * 0.05
        var scored = pool.values.map { h -> val sim = simOf(h); Scored(h, sim + yearScore(h) + pop(h), sim, year != null && h.year != null && kotlin.math.abs(h.year - year) <= 1) }
            .sortedByDescending { it.score }
        // weak title similarity on the top few: try the alternative titles (e.g. Chinese file name vs. English original)
        if (scored.isNotEmpty() && scored.first().sim < 0.9) {
            scored = (scored.take(3).map { sc ->
                val alt = altTitles(kind, sc.hit.id)
                val sim = maxOf(sc.sim, simOf(sc.hit, alt))
                sc.copy(score = sim + yearScore(sc.hit) + pop(sc.hit), sim = sim)
            } + scored.drop(3)).sortedByDescending { it.score }
        }
        return scored
    }

    /**
     * Auto-accept only a clear winner. Returns (accepted, best guess).
     * S13: the clear-winner margin is 0.10 (was 0.05), and a same-title hit whose year is off by > 1 is
     * never auto-accepted — it goes to pending for the user to confirm (remake / re-release, same title).
     */
    fun decide(scored: List<Scored>, year: Int? = null): Pair<Hit?, Hit?> {
        val best = scored.firstOrNull() ?: return null to null
        val second = scored.getOrNull(1)
        val yearMismatch = year != null && best.hit.year != null && kotlin.math.abs(best.hit.year - year) > 1
        val clear = second == null || best.score - second.score >= 0.10 ||
            (best.sim >= 0.95 && best.yearOk) || (best.sim >= 0.95 && best.hit.popularity > 5 * second.hit.popularity)
        val ok = best.score >= 0.80 && best.sim >= 0.6 && clear && !yearMismatch
        return (if (ok) best.hit else null) to best.hit
    }

    /** Kept for callers of the 1.0.0 API: best hit or null. */
    fun match(kind: String, title: String, year: Int?): Hit? = decide(rank(kind, NameParser.queries(title), year), year).first

    data class Detail(val title: String, val original: String, val year: Int?, val overview: String, val rating: Float?, val genres: List<String>,
                      val poster: String?, val backdrop: String?, val runtime: Int, val cast: List<Cast>, val seasons: List<Int>)

    fun detail(kind: String, id: Int): Detail {
        val tv = kind == "tv"
        val o = get(if (tv) "/tv/$id" else "/movie/$id", mapOf("append_to_response" to "credits"))
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.objs().orEmpty().take(16).map {
            Cast(it.optString("name"), it.optString("character"), image(it.optString("profile_path").ifEmpty { null }, "w185"))
        }
        return Detail(
            o.optString(if (tv) "name" else "title"), o.optString(if (tv) "original_name" else "original_title"),
            o.optString(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull(), o.optString("overview"),
            o.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f }, o.optJSONArray("genres")?.objs().orEmpty().map { it.optString("name") },
            image(o.optString("poster_path").ifEmpty { null }, "w342"), image(o.optString("backdrop_path").ifEmpty { null }, "w780"),
            if (tv) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime"), cast,
            if (tv) o.optJSONArray("seasons")?.objs().orEmpty().map { it.optInt("season_number") } else emptyList(),
        )
    }

    /** episode number → (title, still url). */
    fun season(id: Int, n: Int): Map<Int, Pair<String, String?>> =
        get("/tv/$id/season/$n").optJSONArray("episodes")?.objs().orEmpty().associate {
            it.optInt("episode_number") to (it.optString("name") to image(it.optString("still_path").ifEmpty { null }, "w300"))
        }

    fun test(): String { TmdbNet.ensure(force = true); get("/configuration"); return "连接正常 · " + TmdbNet.describe() }
}
