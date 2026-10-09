package com.hark.shiguang

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 1.0.3 #4: fills what the files do not carry (album, artist, year, cover, lyrics). Only MISSING fields are filled.
 *
 * Sources (all unofficial, failures are silent and never block scanning or playback):
 *  1. 网易云音乐 public web API, reachable from mainland China without a VPN:
 *     search  GET https://music.163.com/api/cloudsearch/pc?s=…&type=1&limit=8   (songs[].{id,name,ar[],al{name,picUrl},dt,publishTime})
 *     lyrics  GET https://music.163.com/api/song/lyric?id=…&lv=1&tv=-1           (lrc.lyric, tlyric.lyric)
 *     (/api/search/get/web now returns an encrypted body and /api/search/pc asks for a phone binding — do not use them.)
 *  2. LRCLIB (lrclib.net, overseas, may be slow from China) for lyrics only, as a fallback.
 * Switch: 设置 → 影音 → 音乐 → 联网补全 ([MusicPrefs.online]).
 */
object MusicOnline {
    private val http by lazy {
        com.hark.shiguang.data.FnClient.http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private fun get(url: String, referer: String? = "https://music.163.com/"): String? = runCatching {
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { rb.header("Referer", it) }
        http.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    data class Hit(val id: Long, val title: String, val artists: List<String>, val album: String, val pic: String?, val durationMs: Long, val year: Int, val score: Double)

    private fun norm(s: String) = s.lowercase().replace(Regex("[(（\\[【].*?[)）\\]】]"), "").replace(Regex("[\\s\\p{Punct}·・，。！？、]"), "")

    private fun search(title: String, artist: String, album: String, durationMs: Long): Hit? {
        val q = listOf(title, artist.takeIf { it.isNotBlank() && it != "未知歌手" }).filterNotNull().joinToString(" ")
        if (q.isBlank()) return null
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder().addQueryParameter("s", q).addQueryParameter("type", "1")
            .addQueryParameter("limit", "8").addQueryParameter("offset", "0").build().toString()
        val body = get(url) ?: return null
        val songs = runCatching { JSONObject(body).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return null
        val nt = norm(title); val na = norm(artist); val nal = norm(album)
        var best: Hit? = null
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val name = s.optString("name")
            val ars = s.optJSONArray("ar")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
            val al = s.optJSONObject("al")
            val dt = s.optLong("dt")
            val nn = norm(name)
            var sc = when { nn == nt -> 1.0; nn.isNotEmpty() && nt.isNotEmpty() && (nn.contains(nt) || nt.contains(nn)) -> 0.7; else -> 0.0 }
            if (sc == 0.0) continue
            val artistOk = na.isNotEmpty() && ars.any { a -> val x = norm(a); x.isNotEmpty() && (x == na || na.contains(x) || x.contains(na)) }
            if (artistOk) sc += 0.6 else if (na.isNotEmpty()) sc -= 0.4
            if (durationMs > 0 && dt > 0) sc += if (kotlin.math.abs(dt - durationMs) < 3000) 0.4 else if (kotlin.math.abs(dt - durationMs) > 20000) -0.3 else 0.0
            if (nal.isNotEmpty() && al != null && norm(al.optString("name")) == nal) sc += 0.3
            val year = s.optLong("publishTime").takeIf { it > 0 }?.let { java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR) } ?: 0
            val h = Hit(s.optLong("id"), name, ars, al?.optString("name").orEmpty(), al?.optString("picUrl")?.ifEmpty { null }?.replace("http://", "https://"), dt, year, sc)
            if (best == null || h.score > best.score) best = h
        }
        // confident only: title matches and (artist matches, or nothing to compare with but the length agrees)
        return best?.takeIf { it.score >= 1.5 || (na.isEmpty() && it.score >= 1.3) }
    }

    /** Fills missing album / artist / year / cover of [t]. [albumArt]: album key → saved cover (one download per album). */
    fun complete(lib: MusicLib, t: Track, albumArt: MutableMap<String, String>): Track {
        var n = t
        val cached = albumArt[t.albumKey]
        if (t.tagged && t.album.isNotBlank() && t.artPath == null && cached != null) return n.copy(artPath = cached.ifEmpty { null })
        val hit = search(t.title, t.artist, t.album, t.durationMs) ?: return n
        if (n.artist.isBlank()) n = n.copy(artist = hit.artists.joinToString("/"))
        if (n.album.isBlank() && hit.album.isNotBlank()) n = n.copy(album = hit.album)
        if (n.year == 0 && hit.year in 1900..2100) n = n.copy(year = hit.year)
        if (n.durationMs == 0L && hit.durationMs > 0) n = n.copy(durationMs = hit.durationMs)
        if (n.artPath == null && hit.pic != null) {
            val got = albumArt[n.albumKey] ?: run {
                val b = runCatching { http.newCall(Request.Builder().url(hit.pic + "?param=600y600").header("User-Agent", UA).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
                (b?.let { lib.saveArt(it) } ?: "").also { albumArt[n.albumKey] = it }
            }
            if (got.isNotEmpty()) n = n.copy(artPath = got)
        }
        return n
    }

    /** Synced lyrics for [t]: 网易云 (with translation lines), then LRCLIB. */
    fun lyrics(t: Track): String? {
        search(t.title, t.artist, t.album, t.durationMs)?.let { h ->
            get("https://music.163.com/api/song/lyric?id=${h.id}&lv=1&tv=-1")?.let { b ->
                val j = runCatching { JSONObject(b) }.getOrNull()
                val lrc = j?.optJSONObject("lrc")?.optString("lyric").orEmpty()
                val tr = j?.optJSONObject("tlyric")?.optString("lyric").orEmpty()
                if (lrc.isNotBlank() && !lrc.contains("纯音乐，请欣赏")) return if (tr.isNotBlank()) lrc + "\n" + tr else lrc
            }
        }
        if (t.title.isBlank()) return null
        val u = "https://lrclib.net/api/get".toHttpUrl().newBuilder().addQueryParameter("track_name", t.title)
            .apply { if (t.artist.isNotBlank()) addQueryParameter("artist_name", t.artist); if (t.album.isNotBlank()) addQueryParameter("album_name", t.album)
                if (t.durationMs > 0) addQueryParameter("duration", (t.durationMs / 1000).toString()) }.build().toString()
        val b = get(u, referer = null) ?: return null
        val j = runCatching { JSONObject(b) }.getOrNull() ?: return null
        return j.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: j.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }
}
