package com.hark.shiguang

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 音乐联网补全（1.0.3 #4 起；1.7.32 重写匹配与来源）。只补文件里「缺」的东西（专辑、歌手、年份、封面、歌词），不改写已有标签。
 *
 * 来源（都免密钥、国内直连；都是非官方的公开网页接口，失败时安静跳过，不挡扫描和播放）：
 *  1. 网易云音乐（music.163.com）
 *     搜索  GET https://music.163.com/api/cloudsearch/pc?s=…&type=1&limit=10   songs[].{id,name,ar[],al{name,picUrl},dt,publishTime}
 *     歌手  GET 同上 type=100                                                   artists[].{name,picUrl,img1v1Url}
 *     歌词  GET https://music.163.com/api/song/lyric?id=…&lv=1&tv=-1           lrc.lyric / tlyric.lyric
 *     （/api/search/get/web 返回加密内容、/api/search/pc 要绑手机，都不要用。）
 *  2. QQ 音乐（y.qq.com）—— 1.7.32 新增，翻唱 / 电台版本收得比网易云全（例：许飞《寂寞的门》只有 QQ 音乐有）
 *     搜索  GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=…&format=json&n=10&p=1&cr=1   data.song.list[].{songname,songmid,singer[],albumname,albummid,interval(秒)}
 *     歌词  GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=…&format=json&nobase64=1&g_tk=5381（要 Referer: https://y.qq.com/）
 *     封面  https://y.gtimg.cn/music/photo_new/T002R500x500M000<albummid>.jpg；歌手照片 T001R500x500M000<singermid>.jpg
 *  3. LRCLIB（lrclib.net，开放的歌词库，公开 API，无需密钥）：只做歌词兜底（/api/get 按歌名 + 歌手 + 时长）。海外服务器，国内可能慢。
 *  没用：iTunes Search（2026-11-28 实测本机房 403）、MusicBrainz / Cover Art Archive（实测 503 繁忙，且国内慢）。
 *
 * 规则（[MusicMatch]）：歌名先按 [MusicName] 清洗（去音轨号、「歌手 - 」等），再按「歌名 + 歌手 + 时长」打分。
 * 翻唱：歌词可以用原唱版本（时长差 > 3 秒时只给纯文本，不滚动）；封面只用同一歌手的发行版，绝不用原唱的专辑图，找不到就用歌手照片或保持占位。
 * 网络出错时抛 [IOException]（调用方按 1.7.21 的退避重试，不记为「已补全」）；请求按来源限速（同一来源两次请求至少隔 [GAP_MS]）。
 * 开关：设置 → 影音 → 音乐 → 联网补全（[MusicPrefs.online]）。数据只存本机。
 */
object MusicOnline {
    private val http by lazy {
        com.hark.shiguang.data.FnClient.http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    /** 同一来源两次请求的最小间隔。 */
    const val GAP_MS = 350L
    private val lastAt = HashMap<String, Long>()

    private fun throttle(host: String) {
        val wait: Long
        synchronized(lastAt) {
            val now = System.currentTimeMillis()
            val next = maxOf(now, (lastAt[host] ?: 0L) + GAP_MS)
            lastAt[host] = next
            wait = next - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    /** 正文；404 = null（查无此物）；网络错误 / 5xx / 429 抛 IOException。 */
    private fun get(url: String, referer: String? = null): String? {
        val host = runCatching { url.toHttpUrl().host }.getOrDefault("")
        throttle(host)
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        referer?.let { rb.header("Referer", it) }
        http.newCall(rb.build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $host")
            return r.body?.string()
        }
    }

    private fun bytes(url: String): ByteArray? {
        val host = runCatching { url.toHttpUrl().host }.getOrDefault("")
        throttle(host)
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $host")
            return r.body?.bytes()
        }
    }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ------------------------------------------------------------------ sources

    fun searchNetease(q: String, limit: Int = 10): List<MusicMatch.Cand> {
        if (q.isBlank()) return emptyList()
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder().addQueryParameter("s", q).addQueryParameter("type", "1")
            .addQueryParameter("limit", "$limit").addQueryParameter("offset", "0").build().toString()
        val body = get(url, "https://music.163.com/") ?: return emptyList()
        val songs = runCatching { JSONObject(body).optJSONObject("result")?.optJSONArray("songs") }.getOrNull()
        return songs.objs().map { s ->
            val al = s.optJSONObject("al")
            val year = s.optLong("publishTime").takeIf { it > 0 }?.let { java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR) } ?: 0
            MusicMatch.Cand("netease", s.optLong("id").toString(), s.optString("name"), s.optJSONArray("ar").objs().map { it.optString("name") }.filter { it.isNotBlank() },
                al?.optString("name").orEmpty(), s.optLong("dt"),
                al?.optString("picUrl")?.takeIf { it.isNotBlank() && it != "null" }?.replace("http://", "https://")?.let { "$it?param=600y600" }, year)
        }
    }

    fun searchQq(q: String, limit: Int = 10): List<MusicMatch.Cand> {
        if (q.isBlank()) return emptyList()
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder().addQueryParameter("w", q).addQueryParameter("format", "json")
            .addQueryParameter("n", "$limit").addQueryParameter("p", "1").addQueryParameter("cr", "1").build().toString()
        val body = get(url, "https://y.qq.com/") ?: return emptyList()
        val list = runCatching { JSONObject(body).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }.getOrNull()
        return list.objs().map { s ->
            val amid = s.optString("albummid").takeIf { it.isNotBlank() }
            MusicMatch.Cand("qq", s.optString("songmid"), s.optString("songname"), s.optJSONArray("singer").objs().map { it.optString("name") }.filter { it.isNotBlank() },
                s.optString("albumname"), s.optLong("interval") * 1000, amid?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" })
        }.filter { it.id.isNotBlank() }
    }

    /** 某个候选的歌词（LRC 原文）；没有 = null。 */
    fun lyricsOf(c: MusicMatch.Cand): String? = when (c.source) {
        "netease" -> get("https://music.163.com/api/song/lyric?id=${c.id}&lv=1&tv=-1", "https://music.163.com/")?.let { b ->
            val j = runCatching { JSONObject(b) }.getOrNull()
            val lrc = j?.optJSONObject("lrc")?.optString("lyric").orEmpty()
            val tr = j?.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            if (MusicMatch.isPlaceholderLyrics(lrc)) null else if (tr.isNotBlank()) lrc + "\n" + tr else lrc
        }
        "qq" -> get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${c.id}&format=json&nobase64=1&g_tk=5381", "https://y.qq.com/")?.let { b ->
            val j = runCatching { JSONObject(b.substringAfter('(', b).removeSuffix(")")) }.getOrNull()
            val lrc = unescape(j?.optString("lyric").orEmpty())
            val tr = unescape(j?.optString("trans").orEmpty())
            if (MusicMatch.isPlaceholderLyrics(lrc)) null else if (tr.isNotBlank()) lrc + "\n" + tr else lrc
        }
        else -> null
    }

    /** QQ 音乐歌词里的 HTML 实体（&#58; &amp; &apos; …）。 */
    internal fun unescape(s: String): String = Regex("&(#\\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; e == "apos" -> "'"; else -> " "
        }
    }

    private fun lrclib(title: String, artist: String, album: String, durationMs: Long): String? {
        if (title.isBlank()) return null
        val u = "https://lrclib.net/api/get".toHttpUrl().newBuilder().addQueryParameter("track_name", title)
            .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist); if (album.isNotBlank()) addQueryParameter("album_name", album)
                if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString()) }.build().toString()
        val b = get(u) ?: return null
        val j = runCatching { JSONObject(b) }.getOrNull() ?: return null
        return j.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: j.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 歌手照片（网易云歌手搜索）；没有 = null。名字完全一致的优先；只是去标点后一致时（「柒蕊zero」↔ 网易云「-柒蕊zero-」）
     * 要求我们名字里的符号对方也有——「.K」不能拿到另一位歌手「K」的照片。
     */
    fun artistPhoto(name: String): String? {
        if (name.isBlank() || MusicArtist.isUnknown(name)) return null
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder().addQueryParameter("s", name).addQueryParameter("type", "100")
            .addQueryParameter("limit", "10").addQueryParameter("offset", "0").build().toString()
        val body = get(url, "https://music.163.com/") ?: return null
        val l = runCatching { JSONObject(body).optJSONObject("result")?.optJSONArray("artists") }.getOrNull().objs()
        val marks = name.filter { !it.isLetterOrDigit() && !it.isWhitespace() }
        val a = l.firstOrNull { MusicName.normalizeWidth(it.optString("name")).equals(name, ignoreCase = true) }
            ?: l.firstOrNull { c -> val n = c.optString("name"); MusicName.key(n) == MusicName.key(name) && marks.all { it in n } } ?: return null
        val pic = a.optString("picUrl").takeIf { it.isNotBlank() && it != "null" }
            ?: a.optString("img1v1Url").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return pic.replace("http://", "https://") + "?param=600y600"
    }

    // ------------------------------------------------------------------ matching

    fun queryOf(t: Track): MusicMatch.Query = MusicMatch.Query(t.cleanTitle, t.queryArtists, t.album, t.durationMs)

    /**
     * 候选：先「歌名 歌手」在网易云、QQ 音乐各搜一次，补信息用的已经找到就停；否则再只用歌名各搜一次（翻唱 / 歌手名写法不同）。
     * 所有来源都出网络错误时抛出（退避重试）；有来源正常回答（哪怕没结果）就算查过了。
     */
    private fun candidates(q: MusicMatch.Query, forLyrics: Boolean): List<MusicMatch.Cand> {
        if (q.title.isBlank()) return emptyList()
        val artist = q.artists.firstOrNull { it.isNotBlank() && it != "未知歌手" }.orEmpty()
        val out = ArrayList<MusicMatch.Cand>()
        var answered = 0; var lastErr: IOException? = null
        fun run(f: () -> List<MusicMatch.Cand>) { try { out.addAll(f()); answered++ } catch (e: IOException) { lastErr = e } }
        fun enough() = if (forLyrics) MusicMatch.bestForLyrics(q, out)?.artistMatch == true else MusicMatch.bestForMeta(q, out) != null
        val withArtist = listOf(q.title, artist).filter { it.isNotBlank() }.joinToString(" ")
        run { searchNetease(withArtist) }
        if (!enough()) run { searchQq(withArtist) }
        if (!enough() && artist.isNotBlank()) { run { searchQq(q.title) }; if (!enough()) run { searchNetease(q.title) } }
        if (answered == 0) throw lastErr ?: IOException("no source answered")
        return out
    }

    /** 补 [t] 缺的专辑 / 歌手 / 年份 / 封面。[albumArt]：专辑 → 已存封面；[urlArt]：封面地址 → 已存文件；[artistArt]：歌手 → 照片文件（"" = 没有）。 */
    fun complete(lib: MusicLib, t: Track, albumArt: MutableMap<String, String>, urlArt: MutableMap<String, String> = HashMap(), artistArt: MutableMap<String, String> = HashMap()): Track {
        var n = t
        val cached = albumArt[t.albumKey]
        if (t.tagged && t.album.isNotBlank() && t.artPath == null && cached != null && t.artist.isNotBlank()) return n.copy(artPath = cached.ifEmpty { null })
        val q = queryOf(t)
        val hit = MusicMatch.bestForMeta(q, candidates(q, forLyrics = false))
        if (hit != null) {
            val c = hit.cand
            if (n.artist.isBlank() && n.parsedArtist.isBlank()) n = n.copy(artist = c.artists.joinToString("/"))
            // 1.7.33：标签里是「未知艺术家 / (未知Artist)」这类占位：高分匹配（不知道歌手时要求歌名完全一致、时长差 ≤2 秒）的歌手记到
            // onlineArtist，只用于显示，标签原值不动
            else if (MusicArtist.isUnknown(n.artist) && MusicArtist.isUnknown(n.albumArtist) && n.parsedArtist.isBlank() && c.artists.isNotEmpty())
                n = n.copy(onlineArtist = c.artists.joinToString("/"))
            if (n.album.isBlank() && c.album.isNotBlank()) n = n.copy(album = c.album)
            if (n.year == 0 && c.year in 1900..2100) n = n.copy(year = c.year)
            if (n.durationMs == 0L && c.durationMs > 0) n = n.copy(durationMs = c.durationMs)
            if (n.artPath == null) when (MusicMatch.coverUse(q, hit)) {
                MusicMatch.CoverUse.ALBUM -> {
                    val got = albumArt[n.albumKey] ?: save(lib, c.cover!!, urlArt).also { albumArt[n.albumKey] = it }
                    if (got.isNotEmpty()) n = n.copy(artPath = got)
                }
                MusicMatch.CoverUse.TRACK -> save(lib, c.cover!!, urlArt).takeIf { it.isNotEmpty() }?.let { n = n.copy(artPath = it) }
                MusicMatch.CoverUse.NONE -> {}
            }
        }
        // 没有可信的同歌手封面（翻唱、只查到原唱、专辑对不上）：用歌手照片，没有就保持占位
        if (n.artPath == null) {
            val who = n.nameFix.primary
            if (who.isNotBlank() && !MusicArtist.isUnknown(who)) {
                val f = artistArt[who] ?: (artistPhoto(who)?.let { save(lib, it, urlArt) } ?: "").also { artistArt[who] = it }
                if (f.isNotEmpty()) n = n.copy(artPath = f)
            }
        }
        return n
    }

    /** 下载 [url] 存进封面目录，返回文件路径（"" = 没有）；网络错误抛 IOException。 */
    fun saveUrl(lib: MusicLib, url: String): String = bytes(url)?.let { lib.saveArt(it) } ?: ""

    private fun save(lib: MusicLib, url: String, urlArt: MutableMap<String, String>): String =
        urlArt[url] ?: (bytes(url)?.let { lib.saveArt(it) } ?: "").also { urlArt[url] = it }

    /** 联网歌词结果：[text] LRC 或纯文本；[synced] = 能按时间轴滚动。 */
    data class Lyrics(val text: String, val synced: Boolean, val from: String)

    /** [t] 的联网歌词：网易云 / QQ 音乐里最合适的版本，再 LRCLIB。查无 = null；全部网络出错时抛 IOException。 */
    fun lyrics(t: Track): Lyrics? {
        val q = queryOf(t)
        if (q.title.isBlank()) return null
        var err: IOException? = null; var answered = false
        try {
            val cands = candidates(q, forLyrics = true); answered = true
            val best = MusicMatch.bestForLyrics(q, cands)
            if (best != null) {
                // 先试最佳；它没有歌词再试同样合格的下一个（最多 3 个）
                val ordered = listOf(best) + cands.mapNotNull { MusicMatch.score(q, it) }.filter { it.cand != best.cand && it.titleExact && (it.artistMatch || best.artistMatch.not()) }
                    .sortedByDescending { it.score }.take(2)
                for (s in ordered) {
                    val l = try { lyricsOf(s.cand) } catch (e: IOException) { err = e; null } ?: continue
                    val sync = MusicMatch.keepSync(s) && Lrc.parse(l).size >= 3
                    return Lyrics(if (sync) l else MusicMatch.toPlain(l), sync, s.cand.source)
                }
            }
        } catch (e: IOException) { err = e }
        try {
            lrclib(q.title, q.artists.firstOrNull().orEmpty(), t.album, t.durationMs)?.let { l -> answered = true; return Lyrics(l, Lrc.parse(l).size >= 3, "lrclib") }
            answered = true
        } catch (e: IOException) { if (err == null) err = e }
        if (!answered) throw err ?: IOException("lyrics sources unreachable")
        return null
    }

    // ------------------------------------------------------------------ manual search (歌曲菜单 → 手动搜索)

    /** 手动搜索：网易云 + QQ 音乐合并，按和 [t] 的匹配度排序（没对上歌名的排后面）。 */
    fun manualSearch(query: String, t: Track): List<MusicMatch.Cand> {
        val out = ArrayList<MusicMatch.Cand>(); var err: IOException? = null; var ok = 0
        try { out.addAll(searchNetease(query, 15)); ok++ } catch (e: IOException) { err = e }
        try { out.addAll(searchQq(query, 15)); ok++ } catch (e: IOException) { err = e }
        if (ok == 0) throw err ?: IOException("no source answered")
        val q = queryOf(t)
        return out.distinctBy { "${it.source}|${it.id}" }.sortedByDescending { MusicMatch.score(q, it)?.score ?: -1.0 }
    }

    /** 用户选定的候选：下载封面、取歌词（用户亲自选的，按原样保留时间轴，除非时长差太多）。 */
    fun applyPick(lib: MusicLib, t: Track, c: MusicMatch.Cand): Pair<Track, Lyrics?> {
        var n = t
        c.cover?.let { u -> bytes(u)?.let { lib.saveArt(it) }?.let { n = n.copy(artPath = it) } }
        if (n.album.isBlank() && c.album.isNotBlank()) n = n.copy(album = c.album)
        if (n.year == 0 && c.year in 1900..2100) n = n.copy(year = c.year)
        val l = lyricsOf(c)?.let { raw ->
            val far = t.durationMs > 0 && c.durationMs > 0 && kotlin.math.abs(t.durationMs - c.durationMs) > 5000
            val sync = !far && Lrc.parse(raw).size >= 3
            Lyrics(if (sync) raw else MusicMatch.toPlain(raw), sync, c.source)
        }
        return n to l
    }
}
