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

// ============================================================ model

/** A folder the user picked as a 影视 root. kind: movie / tv / auto. */
data class MovieDir(val path: String, val name: String, val kind: String = "auto")

/** One playable file. [subs] are sibling subtitle paths. [fnId] is the photo-library id on 飞牛 (0 on WebDAV). */
data class MediaFile(val path: String, val name: String, val size: Long, val subs: List<String> = emptyList(), val fnId: Int = 0, val duration: Int = 0) {
    fun json(): JSONObject = JSONObject().put("p", path).put("n", name).put("s", size).put("sub", JSONArray(subs)).put("fid", fnId).put("d", duration)
    companion object {
        fun of(o: JSONObject) = MediaFile(o.optString("p"), o.optString("n"), o.optLong("s"), o.optJSONArray("sub").strs(), o.optInt("fid"), o.optInt("d"))
    }
}

data class Episode(val season: Int, val episode: Int, val path: String, val title: String? = null, val still: String? = null, val file: MediaFile? = null) {
    fun json(): JSONObject = JSONObject().put("s", season).put("e", episode).put("p", path).put("t", title ?: "").put("st", still ?: "").apply { file?.let { put("f", it.json()) } }
    companion object {
        fun of(o: JSONObject) = Episode(o.optInt("s"), o.optInt("e"), o.optString("p"), o.optString("t").ifEmpty { null }, o.optString("st").ifEmpty { null },
            o.optJSONObject("f")?.let { MediaFile.of(it) })
    }
}

data class Cast(val name: String, val role: String, val img: String?) {
    fun json(): JSONObject = JSONObject().put("n", name).put("r", role).put("i", img ?: "")
    companion object { fun of(o: JSONObject) = Cast(o.optString("n"), o.optString("r"), o.optString("i").ifEmpty { null }) }
}

data class MovieItem(
    val id: String,
    val kind: String,                 // movie / tv
    val title: String,
    val year: Int?,
    val tmdbId: Int?,
    val poster: String?,              // local file path (cached) or remote url
    val backdrop: String?,
    val overview: String?,
    val rating: Float?,
    val genres: List<String>,
    val files: List<MediaFile>,       // movie: versions
    val seasons: Map<Int, List<Episode>>,
    val addedAt: Long,
    val matched: Boolean,
    val folder: String = "",          // show folder (tv) / movie folder
    val query: String = "",           // parsed title used for matching
    val runtime: Int = 0,
    val cast: List<Cast> = emptyList(),
    val originalTitle: String = "",
    val source: String = "",          // nfo / local / tmdb / none
    val scraped: Boolean = false,     // TMDB attempted
    // 1.0.1 #6: low-confidence match → 「待确认」 with the best guess kept for one-tap confirm; manual = user picked it
    val pending: Boolean = false,
    val guess: Tmdb.Hit? = null,
    val manual: Boolean = false,
    val altQuery: String = "",        // English title found next to a Chinese one
) {
    val episodes: List<Episode> get() = seasons.toSortedMap().values.flatten()
    fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("t", title).put("y", year ?: 0).put("tm", tmdbId ?: 0)
        .put("po", poster ?: "").put("bd", backdrop ?: "").put("ov", overview ?: "").put("r", (rating ?: 0f).toDouble())
        .put("g", JSONArray(genres)).put("f", JSONArray().apply { files.forEach { put(it.json()) } })
        .put("se", JSONObject().apply { seasons.forEach { (s, l) -> put(s.toString(), JSONArray().apply { l.forEach { put(it.json()) } }) } })
        .put("a", addedAt).put("m", matched).put("fo", folder).put("q", query).put("rt", runtime)
        .put("c", JSONArray().apply { cast.forEach { put(it.json()) } }).put("ot", originalTitle).put("src", source).put("sc", scraped)
        .put("pd", pending).put("mn", manual).put("aq", altQuery).apply { guess?.let { put("gs", it.json()) } }
    companion object {
        fun of(o: JSONObject): MovieItem {
            val se = HashMap<Int, List<Episode>>()
            o.optJSONObject("se")?.let { s -> s.keys().forEach { k -> se[k.toInt()] = s.getJSONArray(k).objs().map(Episode::of) } }
            return MovieItem(o.optString("id"), o.optString("k", "movie"), o.optString("t"), o.optInt("y").takeIf { it > 0 }, o.optInt("tm").takeIf { it > 0 },
                o.optString("po").ifEmpty { null }, o.optString("bd").ifEmpty { null }, o.optString("ov").ifEmpty { null },
                o.optDouble("r", 0.0).toFloat().takeIf { it > 0f }, o.optJSONArray("g").strs(),
                o.optJSONArray("f")?.objs()?.map(MediaFile::of).orEmpty(), se, o.optLong("a"), o.optBoolean("m"), o.optString("fo"), o.optString("q"),
                o.optInt("rt"), o.optJSONArray("c")?.objs()?.map(Cast::of).orEmpty(), o.optString("ot"), o.optString("src"), o.optBoolean("sc"),
                o.optBoolean("pd"), o.optJSONObject("gs")?.let { Tmdb.Hit.of(it) }, o.optBoolean("mn"), o.optString("aq"))
        }
    }
}

data class Progress(val pos: Long, val dur: Long, val at: Long) {
    val ratio: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val watched: Boolean get() = ratio >= 0.9f
}

internal fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
internal fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

// ============================================================ file system per source

data class FsEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val fnId: Int = 0, val duration: Int = 0, val mtime: Long = 0)

/** What the scanner needs from a source. */
interface MovieFs {
    suspend fun list(path: String): List<FsEntry>
    suspend fun readText(path: String): String?
    suspend fun bytes(path: String): ByteArray?
    /** Playable url + headers for the original host (redirect targets get no auth). */
    fun playUrl(f: MediaFile): String?
    fun headers(url: String): Map<String, String>
    val canReadSidecars: Boolean
}

class DavFs(private val accountId: String) : MovieFs {
    private val src: WebDavSource? get() = CloudPhotos.source(accountId) as? WebDavSource
    override val canReadSidecars = true
    override suspend fun list(path: String): List<FsEntry> {
        val s = src ?: error("WebDAV 账户不存在")
        return s.list(path).map { FsEntry(it.name, it.path, it.isDir, it.size, mtime = it.modified) }
    }
    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) { runCatching { src?.readText(path) }.getOrNull() }
    override suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) { src?.allBytes(path) }
    override fun playUrl(f: MediaFile): String? = runBlocking { runCatching { src?.rawUrl(CloudEntry(f.name, f.path, false, f.size, 0, null, false, true)) }.getOrNull() }
    override fun headers(url: String): Map<String, String> = src?.headers(url) ?: emptyMap()
}

/**
 * 飞牛. Two kinds of paths:
 *  - "fm:vol1/1000/影视/…" — any NAS folder through fnOS 文件管理 ([FnFiles], 1.0.9; UNVERIFIED on a real NAS): lists
 *    videos plus nfo/posters/subtitles; streams through the photo-library id when the folder is also in 相册,
 *    otherwise through /multiple-download (UNVERIFIED).
 *  - "/vol1/…" — folders inside the 相册 library (folder_view, the 1.0.8 behaviour and the fallback): videos only.
 */
class FnFs : MovieFs {
    override val canReadSidecars = true
    override suspend fun list(path: String): List<FsEntry> {
        if (path.isEmpty() || path == "/") {
            // root: NAS folders (文件管理) first, then the 相册 library folders as before
            val fm = runCatching { com.hark.shiguang.data.FnFiles.ls("").filter { it.isDir }.map { FsEntry(it.name, "fm:" + it.path, true, 0) } }
                .onFailure { com.hark.shiguang.Diag.log("FNFILE", "root ls failed: ${it.message}") }.getOrDefault(emptyList())
            val lib = runCatching { NasX.libraryFolders().map { FsEntry("相册 · " + it.path.trimEnd('/').substringAfterLast('/').ifEmpty { it.path }, it.path, true, 0) } }.getOrDefault(emptyList())
            if (fm.isEmpty() && lib.isEmpty()) error("读不到飞牛文件夹：" + com.hark.shiguang.data.FnFiles.lastError.ifEmpty { "请检查登录" })
            return fm + lib
        }
        if (path.startsWith("fm:")) {
            val p = path.removePrefix("fm:")
            val l = com.hark.shiguang.data.FnFiles.ls(p)
            // if this folder is also part of 相册, borrow its stream ids (known to work) for the videos
            val ids = HashMap<String, Pair<Int, Int>>()
            if (l.any { !it.isDir && NameParser.ext(it.name) in NameParser.VIDEO }) runCatching {
                var off = 0
                while (off < 3000) {
                    val (ps, total) = NasX.folderPhotos("/" + p.trimStart('/'), off, 200)
                    ps.filter { it.isVideo }.forEach { ids[it.fileName] = it.id to it.duration }
                    off += ps.size; if (ps.isEmpty() || off >= total) break
                }
            }
            return l.map { e -> val id = ids[e.name]; FsEntry(e.name, "fm:" + e.path, e.isDir, e.size, id?.first ?: 0, id?.second ?: 0) }
        }
        val dirs = NasX.subFolders(path).map { FsEntry(it.name, it.path, true, 0) }
        val files = ArrayList<FsEntry>()
        var off = 0
        while (off < 5000) {
            val (l, total) = NasX.folderPhotos(path, off, 200)
            l.filter { it.isVideo }.forEach { files.add(FsEntry(it.fileName, path.trimEnd('/') + "/" + it.fileName, false, it.size, it.id, it.duration)) }
            off += l.size
            if (l.isEmpty() || off >= total) break
        }
        return dirs + files
    }
    override suspend fun readText(path: String): String? = if (path.startsWith("fm:")) com.hark.shiguang.data.FnFiles.readText(path.removePrefix("fm:")) else null
    override suspend fun bytes(path: String): ByteArray? = if (path.startsWith("fm:")) com.hark.shiguang.data.FnFiles.bytes(path.removePrefix("fm:")) else null
    override fun playUrl(f: MediaFile): String? = when {
        f.fnId > 0 -> FnClient.abs("/p/api/v1/stream/v/${f.fnId}")
        f.path.startsWith("fm:") -> com.hark.shiguang.data.FnFiles.downloadUrl(f.path.removePrefix("fm:"))
        else -> null
    }
    override fun headers(url: String): Map<String, String> {
        if (FnClient.baseUrl.isEmpty() || !url.startsWith(FnClient.baseUrl)) return emptyMap()
        if (url.contains("/multiple-download")) return com.hark.shiguang.data.FnFiles.headers()
        val path = url.removePrefix(FnClient.baseUrl).substringBefore("?")
        return mapOf("accesstoken" to FnClient.token, "authx" to com.hark.shiguang.data.AuthX.header(path, ""))
    }
}

// ============================================================ file name parsing

object NameParser {
    val VIDEO = setOf("mkv", "mp4", "avi", "mov", "ts", "m2ts", "rmvb", "rm", "wmv", "asf", "flv", "webm", "m4v", "iso")
    val SUBS = setOf("srt", "ass", "ssa", "vtt")
    val IMG = setOf("jpg", "jpeg", "png", "webp")
    /** Containers ExoPlayer can not play: listed, not played. */
    /** 1.0.10: rmvb/rm/wmv/asf play through the libVLC fallback (ui/VlcPlayer.kt); only disc images are left. */
    val UNPLAYABLE = setOf("iso")

    // 1.0.1 #6: release-name cleaner. \b does not work around CJK, so Latin tags and Chinese tags are separate regexes.
    private val NOISE = Regex(
        "(?i)(?<![a-z0-9])(2160p|1440p|1080p|1080i|720p|576p|480p|4k|8k|uhd|hdr10\\+?|hdr|hlg|dv|dovi|sdr|bluray|blu-ray|bdrip|brrip|bdremux|bdmv|remux|web-?dl|webrip|hdtv|hdrip|dvdrip|dvdscr|dvd|hdcam|camrip|tc|ts|hd|fhd|bd|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|av1|vp9|10bit|8bit|hi10p|60fps|120fps|aac(2\\.0)?|ac3|eac3|dts(-hd)?(\\.ma)?|dts-x|truehd|atmos|flac|opus|lpcm|ddp?\\d?\\.?\\d?|" +
            "5\\.1|7\\.1|2\\.0|proper|repack|extended|unrated|uncut|directors?\\.?cut|imax|internal|limited|complete|multi|dual|dubbed|subbed|chs|cht|chi|eng|jpn|gb|big5|" +
            "nf|amzn|dsnp|hmax|atvp|itunes|iqiyi|youku|tencent|mgtv|bilibili|60帧|120帧)(?![a-z0-9])"
    )
    private val CN_NOISE = Regex(
        "(中英双字|中英字幕|中日双语|中英双语|国粤双语|国英双语|国日双语|双语字幕|双语|简繁英字幕|简繁英|简繁字幕|简繁|简英|繁英|简日|简中|繁中|简体|繁体|" +
            "内封字幕|内嵌字幕|内封|内嵌|外挂字幕|特效字幕|硬字幕|官方字幕|中文字幕|字幕|中字|英字|国语中字|国语|粤语|台配|国配|日语|英语|韩语|原声|" +
            "无删减|未删减|删减版|完整版|加长版|导演剪辑版|剪辑版|修复版|重制版|收藏版|珍藏版|特别版|高清版|高清|超清|标清|蓝光原盘|蓝光|原盘|杜比视界|杜比|全景声|" +
            "无水印|去水印|首发|网络版|抢先版|枪版|电影版|剧场版合集|全集|合集|完结|全\\d+集|更新至?第?\\d+集|第?\\d+集全|\\d+帧)"
    )
    /** Words that mark a bracket or a chunk as a site watermark / release group, not a title. */
    private val SITE_WORDS = Regex("(?i)(电影天堂|阳光电影|飘花|高清影视之家|影视之家|人人影视|字幕组|字幕社|压制|发布|下载|论坛|bt之家|btbt|迅雷|种子|www|\\.com|\\.cn|\\.net|\\.cc|\\.xyz|\\.top|\\.vip|\\.la)")
    private val SITE_NAMES = Regex("(?i)(电影天堂|阳光电影|飘花电影|飘花|高清影视之家|影视之家|人人影视|BT之家|龙部落|迅雷电影天堂|6v电影|首发于|发布于)(发布|首发)?")
    private val DOMAIN = Regex("(?i)(?:https?://)?(?:www\\.)[a-z0-9-]+(?:\\.[a-z0-9-]+)+|(?<![a-z0-9])[a-z0-9-]{2,}\\.(?:com|cn|cc|xyz|top|vip|la)(?:\\.[a-z]{2})?(?![a-z0-9])")
    private val TMDB_TAG = Regex("(?i)[\\[\\{(【]?\\s*tmdb(?:id)?\\s*[=\\-:_ ]\\s*(\\d{1,8})\\s*[\\]\\})】]?")
    private val JUNK = setOf("movie", "video", "film", "main", "feature", "bdmv", "stream", "cd1", "cd2", "disc1", "disc2", "part1", "part2", "sample", "index", "output", "未命名")

    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    fun stem(n: String) = n.substringBeforeLast('.')

    /** `{tmdb-12345}`, `[tmdbid=12345]`, `tmdbid-12345` (Jellyfin / Emby naming) → 12345. */
    fun tmdbIdIn(name: String): Int? = TMDB_TAG.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** Title-ish text with tags removed. Brackets: groups, sites and pure tags are dropped; a bracket is kept as the title only when nothing else is left (anime style "[Group][Title][01]"). */
    fun clean(raw: String): String {
        var s = TMDB_TAG.replace(raw, " ")
        s = DOMAIN.replace(s, " ")
        val segs = ArrayList<String>()
        s = Regex("\\[([^\\]]*)\\]|【([^】]*)】|\\{([^\\}]*)\\}").replace(s) { m -> segs.add(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()); " " }
        s = SITE_NAMES.replace(s, " ")
        var out = tidy(s)
        if (out.isEmpty() || NoiseOnly(out) && segs.any { tidy(it).isNotEmpty() && !NoiseOnly(tidy(it)) }) {
            val keep = segs.withIndex().filter { (i, t) ->
                val c = tidy(t)
                c.isNotEmpty() && !SITE_WORDS.containsMatchIn(t) && !c.all { it.isDigit() || it == ' ' } && !(i == 0 && segs.size >= 3)
            }
            out = keep.map { tidy(it.value) }.maxByOrNull { it.length } ?: out
        }
        return out
    }
    private fun NoiseOnly(s: String) = s.all { it.isDigit() || it == ' ' || it == '-' }

    private fun tidy(s: String): String {
        var t = s.replace('.', ' ').replace('_', ' ').replace('+', ' ')
        t = SITE_WORDS.let { w -> if (w.containsMatchIn(t) && t.length < 12) "" else t }
        t = NOISE.replace(t, " ")
        t = CN_NOISE.replace(t, " ")
        t = t.replace(Regex("(?<![a-zA-Z])-\\s*[A-Za-z0-9]+$"), "")       // trailing -GROUP
        t = t.replace(Regex("[()（）\\[\\]【】\\{\\}《》]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '.', '&', ',', '，')
        return t
    }

    /** title + year; [alt] is an English title found after the year in "中文名.2019.English.Name.1080p". */
    data class MovieName(val title: String, val year: Int?, val alt: String = "")

    private val maxYear: Int get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

    fun movie(fileOrFolder: String): MovieName {
        val s = stem(fileOrFolder).let { if (ext(fileOrFolder) in VIDEO) it else fileOrFolder }.let { DOMAIN.replace(TMDB_TAG.replace(it, " "), " ") }
        // the last plausible year that has a title in front of it ("Blade.Runner.2049.2017" → 2017, "2012.2009" → 2009)
        val ys = Regex("(?<![0-9])((?:19|20)\\d{2})(?![0-9]|[pP](?![a-zA-Z]))").findAll(s).toList()
        for (m in ys.asReversed()) {
            val y = m.groupValues[1].toInt()
            if (y > maxYear) continue
            val title = clean(s.substring(0, m.range.first))
            if (title.isEmpty()) continue
            val tail = clean(s.substring(m.range.last + 1))
            val alt = if (Regex("[\\u3400-\\u9fff]").containsMatchIn(title) && !Regex("[\\u3400-\\u9fff]").containsMatchIn(tail) &&
                Regex("[A-Za-z]{3,}").containsMatchIn(tail) && episodeIn(tail) == null) tail else ""
            return MovieName(title, y, alt)
        }
        return MovieName(clean(s), null)
    }

    /** File names that carry no title ("movie.mkv", "CD1", "01.mp4") — use the folder instead. */
    fun junk(title: String): Boolean = title.length < 2 || norm(title) in JUNK || (title.all { it.isDigit() || it == ' ' } && title.trim().length <= 2)

    data class Ep(val season: Int?, val episode: Int)

    private val CN = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10, '零' to 0, '〇' to 0)
    private fun cnNum(s: String): Int? = s.toIntOrNull() ?: run {
        if (s.isEmpty()) return null
        if (s.contains('百')) { val h = s.substringBefore('百'); val r = s.substringAfter('百').trimStart('零'); return (CN[h.firstOrNull() ?: '一'] ?: 1) * 100 + (cnNum(r) ?: 0) }
        if (s == "十") return 10
        if (s.startsWith("十")) return 10 + (CN[s[1]] ?: 0)
        if (s.length == 2 && s[1] == '十') return (CN[s[0]] ?: 0) * 10
        if (s.length == 3 && s[1] == '十') return (CN[s[0]] ?: 0) * 10 + (CN[s[2]] ?: 0)
        CN[s[0]]
    }

    private fun episodeIn(s: String): Ep? {
        Regex("(?i)s(\\d{1,2})[ ._-]?e[p]?(\\d{1,4})(?![0-9])").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("(?i)season[ ._-]?(\\d{1,2})[ ._-]*episode[ ._-]?(\\d{1,4})").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*季.*?第\\s*([0-9零〇一二三四五六七八九十百]+)\\s*[集话話期]").find(s)?.let { return Ep(cnNum(it.groupValues[1]), cnNum(it.groupValues[2]) ?: return null) }
        Regex("(?i)(?<![0-9a-z])(\\d{1,2})x(\\d{2,3})(?![0-9])").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("第\\s*([0-9零〇一二三四五六七八九十百]+)\\s*[集话話期]").find(s)?.let { return Ep(null, cnNum(it.groupValues[1]) ?: return null) }
        Regex("(?i)(?:^|[ ._\\-\\[(])(?:ep?|episode)[ ._-]?(\\d{1,4})(?![\\dpP])").find(s)?.let { return Ep(null, it.groupValues[1].toInt()) }
        Regex("\\s-\\s(\\d{1,4})(?:v\\d)?(?=[\\s\\[(.]|$)").find(s)?.let { return Ep(null, it.groupValues[1].toInt()) }        // "[Group] Title - 01 [1080p]"
        Regex("[\\[【](\\d{2,4})(?:v\\d)?(?:end|完)?[\\]】]", RegexOption.IGNORE_CASE).findAll(s).firstOrNull { it.groupValues[1].toInt() !in setOf(480, 576, 720, 1080, 2160) && !(it.groupValues[1].length == 4 && it.groupValues[1].startsWith("19") || it.groupValues[1].length == 4 && it.groupValues[1].startsWith("20")) }
            ?.let { return Ep(null, it.groupValues[1].toInt()) }
        return null
    }

    fun episode(name: String): Ep? {
        val s = DOMAIN.replace(stem(name), " ")
        episodeIn(s)?.let { return it }
        Regex("(?:^|[ ._-])(\\d{2,3})(?:[ ._-]|$)").find(s)?.takeIf { s.length <= 12 }?.let { return Ep(null, it.groupValues[1].toInt()) }
        Regex("^(\\d{1,3})$").find(s.trim())?.let { return Ep(null, it.groupValues[1].toInt()) }
        return null
    }

    /** "Season 2" / "S02" / "第二季" / "Specials" → 2 / 0. */
    fun seasonFolder(name: String): Int? {
        val n = name.trim()
        Regex("(?i)^(?:season|series)[ ._-]?(\\d{1,2})$").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)^s(\\d{1,2})$").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("^第\\s*([0-9一二三四五六七八九十]+)\\s*季$").find(n)?.let { return cnNum(it.groupValues[1]) }
        if (n.equals("specials", true) || n.equals("sp", true) || n == "特别篇") return 0
        return null
    }

    /** Season number carried by a show folder name ("权力的游戏 第二季", "Show.S02.1080p") — null when none. */
    fun seasonInName(name: String): Int? {
        Regex("(?i)(?:^|[ ._\\-\\[(])s(\\d{1,2})(?![0-9e])").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)season[ ._-]?(\\d{1,2})").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*季").find(name)?.let { return cnNum(it.groupValues[1]) }
        return null
    }

    fun showTitle(folderName: String): MovieName {
        val cleaned = folderName
            .replace(Regex("(?i)[ ._\\-\\[(]*(season|series)[ ._-]?\\d{1,2}.*$"), "")
            .replace(Regex("第\\s*[0-9一二三四五六七八九十]+\\s*季.*$"), "")
            .replace(Regex("(?i)[ ._\\-\\[(]s\\d{1,2}(?:e\\d{1,4})?(?![0-9]).*$"), "")
            .replace(Regex("(?i)[ ._\\-\\[(]e[p]?\\d{1,4}(?![0-9pP]).*$"), "")
        return movie(cleaned.ifBlank { folderName })
    }

    /** "奥本海默 Oppenheimer" → ["奥本海默", "Oppenheimer", whole]: release names often carry both. */
    fun queries(t: String, alt: String = ""): List<String> {
        val cjk = Regex("[\\u3400-\\u9fff][\\u3400-\\u9fff0-9：:·\\s]*").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val latin = t.replace(Regex("[\\u3400-\\u9fff：·]+"), " ").replace(Regex("\\s+"), " ").trim()
        // "复仇者联盟4：终局之战" → also "复仇者联盟4" (the part before a subtitle colon) as a looser query
        val head = cjk.split('：', ':').first().trim()
        return listOf(cjk, latin, alt, t, head).map { it.trim() }.filter { it.length >= 2 && !(it.length < 3 && it.all { c -> c.isDigit() }) }.distinct()
    }

    /** Normalized for similarity: lowercase, no punctuation / spaces. */
    fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·：，。！？、“”‘’《》]"), "")

    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..y.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = t
            }
        }
        val lev = 1.0 - d[y.length].toDouble() / maxOf(x.length, y.length)
        val contains = if (x.contains(y) || y.contains(x)) 0.85 * minOf(x.length, y.length) / maxOf(x.length, y.length) + 0.15 else 0.0
        return maxOf(lev, contains)
    }
}

// ============================================================ NFO

data class Nfo(
    val title: String?, val original: String?, val year: Int?, val plot: String?, val rating: Float?, val tmdbId: Int?,
    val genres: List<String>, val poster: String?, val fanart: String?, val cast: List<Cast>, val runtime: Int,
    val season: Int? = null, val episode: Int? = null,
)

object NfoParser {
    fun parse(xml: String): Nfo? = runCatching {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml.substring(xml.indexOf('<').coerceAtLeast(0))))
        var title: String? = null; var orig: String? = null; var year: Int? = null; var plot: String? = null
        var rating: Float? = null; var tmdb: Int? = null; var poster: String? = null; var fanart: String? = null
        var runtime = 0; var season: Int? = null; var ep: Int? = null
        val genres = ArrayList<String>(); val cast = ArrayList<Cast>()
        val stack = ArrayList<String>()
        var uniqueType: String? = null
        var thumbAspect: String? = null
        var actorName = ""; var actorRole = ""; var actorThumb: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase(); stack.add(n)
                    if (n == "uniqueid") uniqueType = p.getAttributeValue(null, "type")?.lowercase()
                    if (n == "thumb") thumbAspect = p.getAttributeValue(null, "aspect")?.lowercase()
                    if (n == "actor") { actorName = ""; actorRole = ""; actorThumb = null }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty() && stack.isNotEmpty()) {
                        val cur = stack.last(); val parent = stack.getOrNull(stack.size - 2)
                        when {
                            parent == "actor" -> when (cur) { "name" -> actorName = t; "role" -> actorRole = t; "thumb" -> actorThumb = t }
                            stack.size == 2 -> when (cur) {
                                "title" -> title = t
                                "originaltitle" -> orig = t
                                "year" -> year = t.take(4).toIntOrNull()
                                "premiered", "aired", "releasedate" -> if (year == null) year = t.take(4).toIntOrNull()
                                "plot", "outline" -> if (plot.isNullOrEmpty()) plot = t
                                "rating" -> rating = t.toFloatOrNull()
                                "tmdbid" -> tmdb = t.toIntOrNull()
                                "uniqueid" -> if (uniqueType == "tmdb") tmdb = t.toIntOrNull()
                                "genre" -> genres.add(t)
                                "runtime" -> runtime = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                                "season" -> season = t.toIntOrNull()
                                "episode" -> ep = t.toIntOrNull()
                                "thumb" -> if (thumbAspect == null || thumbAspect == "poster") { if (poster == null) poster = t } else if (thumbAspect == "fanart" && fanart == null) fanart = t
                            }
                            cur == "value" && stack.contains("ratings") -> if (rating == null) rating = t.toFloatOrNull()
                            cur == "thumb" && parent == "fanart" -> if (fanart == null) fanart = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "actor" && actorName.isNotEmpty() && cast.size < 20) cast.add(Cast(actorName, actorRole, actorThumb))
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
            ev = p.next()
        }
        if (title == null && tmdb == null && plot == null) null
        else Nfo(title, orig, year, plot, rating?.takeIf { it > 0f }, tmdb, genres, poster, fanart, cast, runtime, season, ep)
    }.getOrNull()
}

// ============================================================ TMDB

object Tmdb {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
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

    /** Auto-accept only a clear winner. Returns (accepted, best guess). */
    fun decide(scored: List<Scored>): Pair<Hit?, Hit?> {
        val best = scored.firstOrNull() ?: return null to null
        val second = scored.getOrNull(1)
        val clear = second == null || best.score - second.score >= 0.05 ||
            (best.sim >= 0.95 && best.yearOk) || (best.sim >= 0.95 && best.hit.popularity > 5 * second.hit.popularity)
        val ok = best.score >= 0.80 && best.sim >= 0.6 && clear
        return (if (ok) best.hit else null) to best.hit
    }

    /** Kept for callers of the 1.0.0 API: best hit or null. */
    fun match(kind: String, title: String, year: Int?): Hit? = decide(rank(kind, NameParser.queries(title), year)).first

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

// ============================================================ library per source

class MovieLib(val sourceKey: String) {
    val items = mutableStateListOf<MovieItem>()
    var dirs by mutableStateOf<List<MovieDir>>(emptyList())
    var scanning by mutableStateOf(false)
    var phase by mutableStateOf("")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Progress>()
    private var loaded = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kind: String get() = sourceKey.substringBefore(':')
    val accountId: String get() = sourceKey.substringAfter(':')
    val dir: File get() = File(App.ctx.filesDir, (if (kind == "dav") "dav" else "fn") + "/$accountId/movies").apply { mkdirs() }
    private val imgDir: File get() = File(dir, "img").apply { mkdirs() }
    val fs: MovieFs get() = if (kind == "dav") DavFs(accountId) else FnFs()

    fun ensure() {
        if (loaded) return
        loaded = true
        loadFixes()
        runCatching { dirs = JSONObject(File(dir, "config.json").readText()).optJSONArray("dirs")?.objs().orEmpty().map { MovieDir(it.optString("path"), it.optString("name"), it.optString("kind", "auto")) } }
        runCatching { items.addAll(JSONArray(File(dir, "library.json").readText()).objs().map(MovieItem::of)) }
        runCatching {
            val o = JSONObject(File(dir, "progress.json").readText())
            o.keys().forEach { k -> o.optJSONObject(k)?.let { progress[k] = Progress(it.optLong("p"), it.optLong("d"), it.optLong("t")) } }
        }
    }

    fun saveDirs(l: List<MovieDir>) {
        dirs = l
        File(dir, "config.json").writeText(JSONObject().put("dirs", JSONArray().apply { l.forEach { put(JSONObject().put("path", it.path).put("name", it.name).put("kind", it.kind)) } }).toString(2))
        MediaIsolation.invalidate(sourceKey)   // 1.0.1 #2: 照片/视频 tabs leave these folders out
    }

    @Synchronized private fun saveLib() { runCatching { File(dir, "library.json").writeText(JSONArray().apply { items.toList().forEach { put(it.json()) } }.toString()) } }

    private fun saveProgress() = runCatching {
        val o = JSONObject(); progress.forEach { (k, v) -> o.put(k, JSONObject().put("p", v.pos).put("d", v.dur).put("t", v.at)) }
        File(dir, "progress.json").writeText(o.toString())
    }

    fun progressOf(path: String): Progress? { version; return progress[path] }
    fun setProgress(path: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        progress[path] = Progress(pos, dur, System.currentTimeMillis()); saveProgress(); version++
    }
    fun clearProgress(path: String) { progress.remove(path); saveProgress(); version++ }

    /** Items with an unfinished file, most recent first, paired with what to resume. */
    fun continueWatching(): List<Pair<MovieItem, Episode?>> {
        version
        val out = ArrayList<Triple<MovieItem, Episode?, Long>>()
        items.forEach { m ->
            if (m.kind == "movie") {
                m.files.mapNotNull { f -> progress[f.path]?.takeIf { !it.watched && it.pos > 30_000 } }.maxByOrNull { it.at }?.let { out.add(Triple(m, null, it.at)) }
            } else {
                val eps = m.episodes
                val last = eps.mapNotNull { e -> progress[e.path]?.let { e to it } }.maxByOrNull { it.second.at } ?: return@forEach
                val (e, p) = last
                val next = if (p.watched) eps.getOrNull(eps.indexOf(e) + 1) else e
                if (next != null) out.add(Triple(m, next, p.at))
            }
        }
        return out.sortedByDescending { it.third }.map { it.first to it.second }.take(12)
    }

    fun item(id: String): MovieItem? = items.firstOrNull { it.id == id }

    fun clearCache() { job?.cancel(); items.clear(); File(dir, "library.json").delete(); imgDir.deleteRecursively(); version++ }

    // ---------------------------------------------------------------- scan

    fun scan(rescrapeUnmatched: Boolean = false) {
        if (job?.isActive == true) return
        ensure()
        job = scope.launch {
            withContext(Dispatchers.Main) { scanning = true; error = null; done = 0; total = 0; phase = "正在列出文件" }
            try {
                val found = ArrayList<Pair<MovieDir, Walked>>()
                for (d in dirs) {
                    val w = walk(d.path) { n -> launch(Dispatchers.Main) { phase = "正在列出文件 · 已找到 $n 个视频" } }
                    found.add(d to w)
                }
                val built = build(found)
                // merge: keep scraped metadata for items we already have
                val old = items.associateBy { it.id }
                val merged = built.map { n ->
                    val o = old[n.id] ?: return@map n
                    // a {tmdb-123} tag added to the name since the last scan wins over an automatic match (not over a manual one)
                    if (n.tmdbId != null && n.tmdbId != o.tmdbId && !o.manual) return@map n.copy(addedAt = o.addedAt)
                    val keepSeasons = n.seasons.mapValues { (s, eps) -> eps.map { e -> o.seasons[s]?.firstOrNull { it.episode == e.episode }?.let { oe -> e.copy(title = oe.title ?: e.title, still = oe.still ?: e.still) } ?: e } }
                    o.copy(files = n.files, seasons = keepSeasons, folder = n.folder)
                }
                withContext(Dispatchers.Main) { items.clear(); items.addAll(merged); version++ }
                saveLib()
                val todo = merged.filter { it.source.isEmpty() || (rescrapeUnmatched && !it.matched) || (!it.scraped && Tmdb.configured && it.source != "nfo") }
                withContext(Dispatchers.Main) { total = todo.size; done = 0; phase = if (todo.isEmpty()) "" else "正在匹配海报和简介" }
                val gate = Semaphore(4)
                coroutineScope {
                    todo.forEach { m ->
                        launch {
                            gate.withPermit {
                                val n = runCatching { scrape(m, found) }.onFailure { if (error == null) error = it.message }.getOrDefault(m)
                                withContext(Dispatchers.Main) {
                                    val i = items.indexOfFirst { it.id == n.id }; if (i >= 0) items[i] = n
                                    done++; version++
                                }
                                if (done % 6 == 0) saveLib()
                            }
                        }
                    }
                }
                saveLib()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "扫描失败" }
            withContext(Dispatchers.Main) { scanning = false; phase = "" }
        }
    }

    /** All files under a root, grouped by folder. */
    class Walked(val byDir: Map<String, List<FsEntry>>, val subdirs: Map<String, List<String>>)

    private suspend fun walk(root: String, onCount: (Int) -> Unit): Walked {
        val byDir = java.util.concurrent.ConcurrentHashMap<String, List<FsEntry>>()
        val sub = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        var level = listOf(root); var depth = 0
        val gate = Semaphore(6)
        var videos = 0
        while (level.isNotEmpty() && depth <= 8) {
            val next = java.util.Collections.synchronizedList(ArrayList<String>())
            coroutineScope {
                level.forEach { d ->
                    launch {
                        gate.withPermit {
                            val l = runCatching { fs.list(d) }.getOrElse { if (d == root) throw it else emptyList() }
                            val files = l.filter { !it.isDir }
                            byDir[d] = files
                            // 1.0.3 #4: 音乐 folders belong to the music library, not to 影视
                            val dirsHere = l.filter { it.isDir && !it.name.startsWith(".") && !it.name.startsWith("@") && !MediaIsolation.isMusicPath(sourceKey, it.path) }.map { it.path }
                            sub[d] = dirsHere; next.addAll(dirsHere)
                            synchronized(this@MovieLib) { videos += files.count { NameParser.ext(it.name) in NameParser.VIDEO } }
                            onCount(videos)
                        }
                    }
                }
            }
            level = next.toList(); depth++
        }
        return Walked(byDir, sub)
    }

    private fun idOf(s: String) = MessageDigest.getInstance("MD5").digest("$sourceKey|$s".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun parent(p: String) = p.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    private fun base(p: String) = p.trimEnd('/').substringAfterLast('/')

    private fun subsFor(video: FsEntry, siblings: List<FsEntry>): List<String> {
        val stem = NameParser.stem(video.name)
        val subs = siblings.filter { NameParser.ext(it.name) in NameParser.SUBS }
        val own = subs.filter { it.name.startsWith(stem) }
        val videosHere = siblings.count { NameParser.ext(it.name) in NameParser.VIDEO }
        return (if (own.isNotEmpty()) own else if (videosHere == 1) subs else emptyList()).map { it.path }
    }

    private fun build(found: List<Pair<MovieDir, Walked>>): List<MovieItem> {
        val movies = LinkedHashMap<String, MovieItem>()
        val shows = LinkedHashMap<String, MovieItem>()
        val now = System.currentTimeMillis()
        val oldAdded = items.associate { it.id to it.addedAt }
        for ((d, w) in found) {
            for ((folder, files) in w.byDir) {
                val vids = files.filter { NameParser.ext(it.name) in NameParser.VIDEO && !it.name.contains("sample", true) && !it.name.contains("trailer", true) }
                if (vids.isEmpty()) continue
                for (v in vids) {
                    val ep = NameParser.episode(v.name)
                    val isTv = when (d.kind) { "tv" -> true; "movie" -> false; else -> ep != null && (vids.size > 1 || NameParser.seasonFolder(base(folder)) != null) }
                    val mf = MediaFile(v.path, v.name, v.size, subsFor(v, files), v.fnId, v.duration)
                    if (isTv) {
                        val sf = NameParser.seasonFolder(base(folder))
                        val showFolder = if (sf != null && folder != d.path) parent(folder) else folder
                        // episodes lying directly in the library root: the show name comes from the file name
                        val atRoot = showFolder == d.path && sf == null && folder == d.path
                        val fileShow = if (atRoot) NameParser.showTitle(NameParser.stem(v.name)) else null
                        val parsed = fileShow?.takeIf { !NameParser.junk(it.title) } ?: NameParser.showTitle(base(showFolder))
                        val showName = parsed.title.ifEmpty { base(showFolder) }
                        val season = ep?.season ?: sf ?: NameParser.seasonInName(base(folder)) ?: NameParser.seasonInName(v.name) ?: 1
                        val epNo = ep?.episode ?: (vids.sortedBy { it.name }.indexOf(v) + 1)
                        val key = if (atRoot) idOf("tv|$showFolder|" + NameParser.norm(showName)) else idOf("tv|$showFolder")
                        val nameTmdb = NameParser.tmdbIdIn(base(showFolder)) ?: NameParser.tmdbIdIn(base(folder))
                        val cur = shows[key] ?: MovieItem(key, "tv", showName, parsed.year, nameTmdb, null, null, null, null, emptyList(), emptyList(),
                            emptyMap(), oldAdded[key] ?: now, false, folder = showFolder, query = parsed.title, altQuery = parsed.alt)
                        val eps = (cur.seasons[season].orEmpty().filter { it.episode != epNo } + Episode(season, epNo, v.path, null, null, mf)).sortedBy { it.episode }
                        shows[key] = cur.copy(seasons = cur.seasons + (season to eps))
                    } else {
                        // movie in its own folder: folder name usually carries the clean "名称 (年份)"
                        val ownFolder = folder != d.path && vids.size <= 3
                        val fromFile = NameParser.movie(v.name)
                        val fromDir = if (ownFolder) NameParser.movie(base(folder)).takeIf { !NameParser.junk(it.title) } else null
                        // 1.0.1: a title-less file name ("movie.mkv", "CD1") borrows the folder name even when the folder holds more files
                        val pick = when {
                            fromDir != null && (fromDir.year != null || fromFile.year == null || NameParser.junk(fromFile.title)) -> fromDir.copy(alt = fromDir.alt.ifEmpty { fromFile.alt })
                            NameParser.junk(fromFile.title) && folder != d.path -> NameParser.movie(base(folder))
                            else -> fromFile
                        }
                        val nameTmdb = NameParser.tmdbIdIn(v.name) ?: (if (folder != d.path) NameParser.tmdbIdIn(base(folder)) else null)
                        val key = idOf("movie|" + if (ownFolder) folder else NameParser.norm(pick.title) + "|" + (pick.year ?: 0))
                        val cur = movies[key] ?: MovieItem(key, "movie", pick.title.ifEmpty { NameParser.stem(v.name) }, pick.year, nameTmdb, null, null, null, null, emptyList(),
                            emptyList(), emptyMap(), oldAdded[key] ?: now, false, folder = folder, query = pick.title, altQuery = pick.alt)
                        movies[key] = cur.copy(files = (cur.files + mf).sortedByDescending { it.size })
                    }
                }
            }
        }
        return movies.values.toList() + shows.values.toList()
    }

    // ---------------------------------------------------------------- scrape

    private fun cacheImage(key: String, load: () -> ByteArray?): String? {
        val f = File(imgDir, MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg")
        if (f.exists() && f.length() > 0) return f.absolutePath
        val b = runCatching { load() }.getOrNull() ?: return null
        if (b.size < 200) return null
        f.writeBytes(b); return f.absolutePath
    }

    private fun httpBytes(url: String): ByteArray? = runCatching {
        Tmdb.http.newCall(Request.Builder().url(TmdbNet.imgUrl(url) ?: url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    }.getOrNull()

    private suspend fun sidecar(folder: String, files: List<FsEntry>, names: List<String>): FsEntry? {
        val lower = files.associateBy { it.name.lowercase() }
        return names.firstNotNullOfOrNull { lower[it.lowercase()] }
    }

    suspend fun scrape(m0: MovieItem, found: List<Pair<MovieDir, MovieLib.Walked>>): MovieItem {
        var m = m0
        val f = fs
        val folderFiles = found.firstNotNullOfOrNull { it.second.byDir[m.folder] }.orEmpty()
        // 1) local NFO
        if (f.canReadSidecars && m.source.isEmpty()) {
            val stem = m.files.firstOrNull()?.let { NameParser.stem(it.name) }
            val nfoEntry = if (m.kind == "tv") sidecar(m.folder, folderFiles, listOf("tvshow.nfo"))
                else sidecar(m.folder, folderFiles, listOfNotNull(stem?.let { "$it.nfo" }, "movie.nfo")) ?: folderFiles.firstOrNull { it.name.endsWith(".nfo", true) && folderFiles.count { x -> NameParser.ext(x.name) in NameParser.VIDEO } <= 1 }
            nfoEntry?.let { e -> f.readText(e.path)?.let(NfoParser::parse) }?.let { n ->
                m = m.copy(title = n.title ?: m.title, originalTitle = n.original ?: "", year = n.year ?: m.year, overview = n.plot, rating = n.rating,
                    tmdbId = n.tmdbId ?: m.tmdbId, genres = n.genres.ifEmpty { m.genres }, cast = n.cast.ifEmpty { m.cast }, runtime = n.runtime, source = "nfo", matched = true)
                n.poster?.takeIf { it.startsWith("http") }?.let { u -> m = m.copy(poster = cacheImage(u) { httpBytes(u) } ?: u) }
                n.fanart?.takeIf { it.startsWith("http") }?.let { u -> m = m.copy(backdrop = u) }
            }
            // 2) local poster / fanart images
            val stemNames = stem?.let { listOf("$it-poster.jpg", "$it-poster.png", "$it.jpg") }.orEmpty()
            val posterE = sidecar(m.folder, folderFiles, stemNames + listOf("poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg", "cover.png", "movie.jpg"))
            val fanE = sidecar(m.folder, folderFiles, listOfNotNull(stem?.let { "$it-fanart.jpg" }) + listOf("fanart.jpg", "fanart.png", "backdrop.jpg", "background.jpg", "landscape.jpg"))
            posterE?.let { e -> cacheImage("$sourceKey|${e.path}") { runBlocking { f.bytes(e.path) } } }?.let { m = m.copy(poster = it, source = m.source.ifEmpty { "local" }) }
            fanE?.let { e -> cacheImage("$sourceKey|${e.path}") { runBlocking { f.bytes(e.path) } } }?.let { m = m.copy(backdrop = it) }
            // episode NFOs (titles) for tv
            if (m.kind == "tv" && m.source == "nfo") m = m.copy(seasons = m.seasons.mapValues { (_, eps) -> eps.map { e ->
                val dirFiles = found.firstNotNullOfOrNull { it.second.byDir[parent(e.path)] }.orEmpty()
                val en = sidecar(parent(e.path), dirFiles, listOf(NameParser.stem(base(e.path)) + ".nfo"))
                en?.let { x -> f.readText(x.path)?.let(NfoParser::parse) }?.title?.let { e.copy(title = it) } ?: e
            } })
        }
        // 3) TMDB
        if (Tmdb.configured && m.source != "nfo" || (Tmdb.configured && m.source == "nfo" && m.tmdbId != null && (m.poster == null || m.backdrop == null))) {
            m = runCatching { tmdbFill(m) }.getOrElse { e -> if (e.message?.contains("密钥") == true) throw e; m }.copy(scraped = true)
        }
        if (m.source.isEmpty()) m = m.copy(source = "none")
        return m
    }

    private suspend fun tmdbFill(m0: MovieItem, forceId: Int? = null): MovieItem {
        var m = m0
        // 1.0.1 #6 order: manual fix > forced > {tmdb-id} in the name / NFO <tmdbid>/<uniqueid> > scored search
        val fix = fixes[m.id]
        if (forceId == null && fix != null && fix.first != m.kind) m = m.copy(kind = fix.first)
        var guess: Tmdb.Hit? = null
        val id = forceId ?: fix?.second ?: m.tmdbId ?: run {
            var (hit, g) = withContext(Dispatchers.IO) { Tmdb.decide(Tmdb.rank(m.kind, NameParser.queries(m.query.ifEmpty { m.title }, m.altQuery), m.year)) }
            if (hit == null && AiConfig.configured && (m.files.isNotEmpty() || m.kind == "tv")) {
                aiParse(m)?.let { (t, y) ->
                    val (h2, g2) = withContext(Dispatchers.IO) { Tmdb.decide(Tmdb.rank(m.kind, NameParser.queries(t), y ?: m.year)) }
                    hit = h2; if (g == null) g = g2
                }
            }
            guess = g
            hit?.id
        } ?: return m.copy(matched = m.source == "nfo", pending = m.source != "nfo" && guess != null, guess = guess)
        val d = withContext(Dispatchers.IO) { Tmdb.detail(m.kind, id) }
        val keepLocalPoster = m.source == "local" || m.source == "nfo"
        val poster = if (keepLocalPoster && m.poster != null) m.poster else d.poster?.let { u -> cacheImage(u) { httpBytes(u) } ?: u }
        m = m.copy(tmdbId = id, title = if (m.source == "nfo") m.title else d.title.ifEmpty { m.title }, originalTitle = d.original, year = d.year ?: m.year,
            overview = m.overview?.takeIf { it.isNotBlank() } ?: d.overview, rating = m.rating ?: d.rating, genres = m.genres.ifEmpty { d.genres },
            poster = poster, backdrop = m.backdrop ?: d.backdrop, runtime = if (m.runtime > 0) m.runtime else d.runtime, cast = m.cast.ifEmpty { d.cast },
            matched = true, source = if (m.source == "nfo") "nfo" else "tmdb", pending = false, guess = null)
        if (m.kind == "tv") {
            val seasons = m.seasons.mapValues { (s, eps) ->
                val info = runCatching { withContext(Dispatchers.IO) { Tmdb.season(id, s) } }.getOrDefault(emptyMap())
                eps.map { e -> info[e.episode]?.let { (t, st) -> e.copy(title = e.title ?: t.ifEmpty { null }, still = e.still ?: st) } ?: e }
            }
            m = m.copy(seasons = seasons)
        }
        return m
    }

    /** Last resort for messy names: ask the configured model for {title, year}. */
    private suspend fun aiParse(m: MovieItem): Pair<String, Int?>? = runCatching {
        val name = if (m.kind == "tv") base(m.folder) else m.files.first().name
        val r = AiClient.chat("从这个${if (m.kind == "tv") "剧集文件夹" else "电影文件"}名里提取作品名和年份，只回复 JSON：{\"title\":\"\",\"year\":0}。名字：$name", maxTokens = 80)
        val j = JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1))
        j.optString("title").takeIf { it.isNotBlank() }?.let { it to j.optInt("year").takeIf { y -> y in 1900..2100 } }
    }.getOrNull()

    /** 修正匹配: apply a chosen TMDB hit. */
    suspend fun applyMatch(item: MovieItem, hit: Tmdb.Hit, manual: Boolean = true) {
        if (manual) { fixes[item.id] = hit.kind to hit.id; saveFixes() }
        val base = item.copy(kind = hit.kind, manual = manual, tmdbId = null, poster = if (item.source == "local") item.poster else null, backdrop = null, overview = null, rating = null,
            genres = emptyList(), cast = emptyList(), runtime = 0, source = if (item.source == "local") "local" else "", title = hit.title,
            seasons = item.seasons.mapValues { (_, l) -> l.map { it.copy(title = null, still = null) } })
        val n = tmdbFill(base, hit.id).copy(scraped = true)
        withContext(Dispatchers.Main) { val i = items.indexOfFirst { it.id == item.id }; if (i >= 0) items[i] = n; version++ }
        withContext(Dispatchers.IO) { saveLib() }
    }

    /** 「重新识别」: forget the manual fix, run the automatic match again with the current cleaner/scorer. */
    suspend fun rematch(item: MovieItem): MovieItem {
        fixes.remove(item.id); saveFixes()
        val base = item.copy(tmdbId = if (item.source == "nfo") item.tmdbId else NameParser.tmdbIdIn(item.folder.substringAfterLast('/')) ?: item.files.firstNotNullOfOrNull { NameParser.tmdbIdIn(it.name) },
            manual = false, pending = false, guess = null, matched = false,
            poster = if (item.source == "local" || item.source == "nfo") item.poster else null, backdrop = if (item.source == "nfo") item.backdrop else null,
            overview = if (item.source == "nfo") item.overview else null, rating = if (item.source == "nfo") item.rating else null,
            genres = if (item.source == "nfo") item.genres else emptyList(), cast = if (item.source == "nfo") item.cast else emptyList(),
            source = if (item.source == "nfo" || item.source == "local") item.source else "")
        val n = withContext(Dispatchers.IO) { tmdbFill(base) }.copy(scraped = true).let { if (it.source.isEmpty()) it.copy(source = "none") else it }
        withContext(Dispatchers.Main) { val i = items.indexOfFirst { it.id == item.id }; if (i >= 0) items[i] = n; version++ }
        withContext(Dispatchers.IO) { saveLib() }
        return n
    }

    // manual fixes survive 清空影视库 / rescans: item id → (kind, tmdb id)
    private val fixes = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    private fun loadFixes() = runCatching {
        val o = JSONObject(File(dir, "fixes.json").readText())
        o.keys().forEach { k -> o.optJSONObject(k)?.let { fixes[k] = it.optString("k", "movie") to it.optInt("id") } }
    }
    private fun saveFixes() = runCatching {
        File(dir, "fixes.json").writeText(JSONObject().apply { fixes.forEach { (k, v) -> put(k, JSONObject().put("k", v.first).put("id", v.second)) } }.toString())
    }
}

object Movies {
    private val libs = HashMap<String, MovieLib>()
    fun keyOf(): String? = if (com.hark.shiguang.ui.HomeState.source == "dav") Dav.current?.let { "dav:${it.id}" } else NasAccounts.current?.let { "fn:${it.id}" }
    fun lib(): MovieLib? = keyOf()?.let { lib(it) }
    fun lib(key: String): MovieLib = libs.getOrPut(key) { MovieLib(key) }.also { it.ensure() }
    fun forget(key: String) { libs.remove(key) }
}
