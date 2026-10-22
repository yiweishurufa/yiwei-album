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

// 1.1.0 拆分自 Movies.kt（只移动代码，行为不变）：影视数据模型与各来源的文件系统访问。
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

/**
 * 1.1.0 剧集续播：一部剧「看到第几集、看到哪里」。
 * [last] 是 shows.json 里记下的最后在看的那一集（1.1.0 起每次播放剧集都会记）；旧数据没有这条记录时，
 * 回退到以前的办法：所有集里最近一次有进度的那一集。位置始终来自每个文件自己的进度（progress.json，格式不变）。
 */
object ShowResume {
    /** [path] to resume (or start), its progress (null = from the start); [finished] = the last episode was watched to the end. */
    data class Pick(val path: String, val progress: Progress?, val finished: Boolean)

    fun pick(episodes: List<String>, progressOf: (String) -> Progress?, last: String?): Pick? {
        if (episodes.isEmpty()) return null
        val cur = last?.takeIf { it in episodes }
            ?: episodes.mapNotNull { e -> progressOf(e)?.let { e to it } }.maxByOrNull { it.second.at }?.first
            ?: return Pick(episodes.first(), null, false)
        val p = progressOf(cur)
        if (p?.watched == true) {
            val next = episodes.getOrNull(episodes.indexOf(cur) + 1) ?: return Pick(cur, p, true)
            return Pick(next, progressOf(next)?.takeIf { !it.watched }, false)
        }
        return Pick(cur, p, false)
    }
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
    override suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) { src?.allBytes(path, 32L * 1024 * 1024) }
    override fun playUrl(f: MediaFile): String? = runCatching { src?.fileUrl(f.path) }.getOrNull()
    override fun headers(url: String): Map<String, String> = src?.headers(url) ?: emptyMap()
}

/**
 * 飞牛. Two kinds of paths:
 *  - "fm:vol1/1000/影视/…" — any NAS folder through fnOS 文件管理 ([FnFiles], 1.0.9; UNVERIFIED on a real NAS): lists
 *    videos plus nfo/posters/subtitles; streams through the photo-library id when the folder is also in 相册,
 *    otherwise through /multiple-download (B4 降级实现：未经抓包确认，失败时提示“飞牛下载需抓包确认”).
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
