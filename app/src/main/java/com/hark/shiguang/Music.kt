package com.hark.shiguang

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 1.0.3 #4 影音 · 音乐.
 *
 * One [MusicLib] per source, keyed like [Movies.keyOf] ("dav:<WebDAV account id>" / "fn:<飞牛 account id>").
 * Files: filesDir/{dav|fn}/<id>/music/
 *   config.json   {"dirs":[{"path","name"}]}  — same shape as movies/config.json; [MediaIsolation] reads both
 *   library.json  the tracks
 *   art/          cover images (embedded pictures, folder cover.jpg, online covers), named by md5 of the bytes
 *   lyrics/       <track id>.lrc — embedded, sidecar or online lyrics once read; <id>.none = nothing found (retry after 7 days)
 * Art and lyrics live under filesDir rather than cacheDir so a system cache purge does not blank the library.
 *
 * Access goes through [MovieFs] ([DavFs] / [FnFs]) — no separate WebDAV / 飞牛 code. On 飞牛 only folders from
 * 文件管理 ("fm:" paths) list audio files; 相册 folders only return photos and videos.
 */
data class Track(
    val id: String,
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val durationMs: Long = 0,
    val codec: String = "",
    val artPath: String? = null,     // local file
    val lrcPath: String? = null,     // sidecar .lrc on the source
    val folder: String = "",
    val tagged: Boolean = false,     // embedded tags were read (title etc. not only from the file name)
    val scanned: Boolean = false,    // tag reading attempted
    val online: Boolean = false,     // online completion attempted
) {
    val ext: String get() = NameParser.ext(name)
    val displayArtist: String get() = artist.ifBlank { albumArtist }.ifBlank { "未知歌手" }
    val displayAlbum: String get() = album.ifBlank { "未知专辑" }
    val albumKey: String get() = (album.ifBlank { folder }) + "\u0001" + albumArtist.ifBlank { if (album.isBlank()) "" else artist }
    val artists: List<String> get() = Music.splitArtists(artist.ifBlank { albumArtist })

    fun json(): JSONObject = JSONObject().put("id", id).put("p", path).put("n", name).put("s", size).put("m", mtime).put("t", title).put("ar", artist)
        .put("al", album).put("aa", albumArtist).put("y", year).put("tn", trackNo).put("dn", discNo).put("d", durationMs).put("c", codec)
        .put("art", artPath ?: "").put("lrc", lrcPath ?: "").put("f", folder).put("tg", tagged).put("sc", scanned).put("on", online)
    companion object {
        fun of(o: JSONObject) = Track(o.optString("id"), o.optString("p"), o.optString("n"), o.optLong("s"), o.optLong("m"), o.optString("t"),
            o.optString("ar"), o.optString("al"), o.optString("aa"), o.optInt("y"), o.optInt("tn"), o.optInt("dn"), o.optLong("d"), o.optString("c"),
            o.optString("art").ifEmpty { null }, o.optString("lrc").ifEmpty { null }, o.optString("f"), o.optBoolean("tg"), o.optBoolean("sc"), o.optBoolean("on"))
    }
}

data class MusicDir(val path: String, val name: String)

private const val BIG = 512 * 1024
private const val MusicTagsMax = 6 * 1024 * 1024
/** One big buffer (cover art, moov, folder image) in flight across all music scans. */
private val BIG_GATE = java.util.concurrent.Semaphore(1)

class MusicLib(val sourceKey: String) {
    val tracks = mutableStateListOf<Track>()
    var dirs by mutableStateOf<List<MusicDir>>(emptyList())
    var scanning by mutableStateOf(false)
    var phase by mutableStateOf("")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var found by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    private var loaded = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kind: String get() = sourceKey.substringBefore(':')
    val accountId: String get() = sourceKey.substringAfter(':')
    val dir: File get() = File(App.ctx.filesDir, (if (kind == "dav") "dav" else "fn") + "/$accountId/music").apply { mkdirs() }
    val artDir: File get() = File(dir, "art").apply { mkdirs() }
    val lyricsDir: File get() = File(dir, "lyrics").apply { mkdirs() }
    val fs: MovieFs get() = if (kind == "dav") DavFs(accountId) else FnFs()

    fun ensure() {
        if (loaded) return
        loaded = true
        runCatching { dirs = JSONObject(File(dir, "config.json").readText()).optJSONArray("dirs")?.objs().orEmpty().map { MusicDir(it.optString("path"), it.optString("name")) } }
        runCatching { tracks.addAll(JSONArray(File(dir, "library.json").readText()).objs().map(Track::of)) }
    }

    fun saveDirs(l: List<MusicDir>) {
        dirs = l
        File(dir, "config.json").writeText(JSONObject().put("dirs", JSONArray().apply { l.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) } }).toString(2))
        MediaIsolation.invalidate(sourceKey)   // 照片 / 视频 tabs and the 影视 scan leave these folders out
        // tracks of removed folders disappear at once
        val keep = tracks.filter { t -> l.any { inDir(t.path, it.path) } }
        if (keep.size != tracks.size) { tracks.clear(); tracks.addAll(keep); version++; saveLib() }
    }

    @Synchronized private fun saveLib() { runCatching { File(dir, "library.json").writeText(JSONArray().apply { tracks.toList().forEach { put(it.json()) } }.toString()) } }

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun clearCache() {
        job?.cancel(); tracks.clear(); File(dir, "library.json").delete(); artDir.deleteRecursively(); lyricsDir.deleteRecursively(); version++
    }

    /** The player learnt the real length (tags had none). */
    fun setDuration(id: String, ms: Long) {
        val i = tracks.indexOfFirst { it.id == id }
        if (i < 0 || ms <= 0 || kotlin.math.abs(tracks[i].durationMs - ms) < 1500) return
        tracks[i] = tracks[i].copy(durationMs = ms); version++
        scope.launch { saveLib() }
    }

    private fun inDir(p: String, d: String): Boolean { val a = p.trimEnd('/'); val b = d.trimEnd('/'); return b.isEmpty() || a == b || a.startsWith("$b/") }

    // ---------------------------------------------------------------- scan

    /** [rescrape]: read every file's tags again (设置 → 重新刮削). */
    fun scan(rescrape: Boolean = false) {
        if (job?.isActive == true) return
        ensure()
        job = scope.launch {
            withContext(Dispatchers.Main) { scanning = true; error = null; done = 0; total = 0; found = 0; phase = "正在列出音乐文件" }
            try {
                val files = ArrayList<Pair<FsEntry, String>>()           // audio file, folder
                val folders = HashMap<String, List<FsEntry>>()          // folder → its files (covers, lrc)
                for (d in dirs) walk(d.path, files, folders)
                val old = tracks.associateBy { it.path }
                val fresh = files.map { (e, folder) ->
                    val o = old[e.path]
                    val lrc = sidecarLrc(e, folders[folder].orEmpty())
                    if (o != null && !rescrape && o.size == e.size && (e.mtime == 0L || o.mtime == 0L || o.mtime == e.mtime) && o.scanned) o.copy(lrcPath = lrc ?: o.lrcPath, folder = folder)
                    else guess(e, folder).copy(lrcPath = lrc)
                }.sortedWith(compareBy({ it.folder }, { it.discNo }, { it.trackNo }, { it.name }))
                withContext(Dispatchers.Main) { tracks.clear(); tracks.addAll(fresh); version++ }
                saveLib()

                // embedded tags (only new / changed files)
                val todo = fresh.filter { !it.scanned }
                withContext(Dispatchers.Main) { total = todo.size; done = 0; phase = if (todo.isEmpty()) "" else "正在读取歌曲信息" }
                val gate = Semaphore(3)
                val coverCache = java.util.concurrent.ConcurrentHashMap<String, String>()   // folder → cover file ("" = none)
                val batch = Batch()
                coroutineScope {
                    todo.forEach { t ->
                        launch {
                            gate.withPermit {
                                val n = runCatching { readTags(t, folders[t.folder].orEmpty(), coverCache) }.getOrElse { t.copy(scanned = true) }
                                batch.add(n)
                            }
                        }
                    }
                }
                batch.flush(true)

                // online completion: only what is missing, never overwrites tags; failures are silent
                if (MusicPrefs.online) {
                    val need = tracks.filter { !it.online && (!it.tagged || it.artPath == null || it.album.isBlank()) }
                    if (need.isNotEmpty()) {
                        withContext(Dispatchers.Main) { total = need.size; done = 0; phase = "正在联网补全封面和信息" }
                        val g2 = Semaphore(2)
                        val albumArt = java.util.concurrent.ConcurrentHashMap<String, String>()
                        val b2 = Batch()
                        coroutineScope {
                            need.forEach { t ->
                                launch {
                                    g2.withPermit {
                                        val n = runCatching { MusicOnline.complete(this@MusicLib, t, albumArt) }.getOrDefault(t).copy(online = true)
                                        b2.add(n)
                                    }
                                }
                            }
                        }
                        b2.flush(true)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message ?: "扫描失败" } }
            withContext(Dispatchers.Main) { scanning = false; phase = "" }
        }
    }

    /** Collects scanned tracks and applies them to [tracks] about once a second (one recomposition per batch, not per song). */
    private inner class Batch {
        private val q = java.util.concurrent.ConcurrentLinkedQueue<Track>()
        @Volatile private var last = System.currentTimeMillis()
        @Volatile private var lastSave = System.currentTimeMillis()
        suspend fun add(t: Track) { q.add(t); if (System.currentTimeMillis() - last > 1000) flush(false) }
        suspend fun flush(end: Boolean) {
            last = System.currentTimeMillis()
            val l = ArrayList<Track>(); while (true) l.add(q.poll() ?: break)
            if (l.isNotEmpty()) withContext(Dispatchers.Main) {
                val idx = HashMap<String, Int>(tracks.size * 2); tracks.forEachIndexed { i, x -> idx[x.id] = i }
                l.forEach { n -> idx[n.id]?.let { tracks[it] = n } }
                done += l.size; version++
            }
            if (end || System.currentTimeMillis() - lastSave > 20_000) { lastSave = System.currentTimeMillis(); withContext(Dispatchers.IO) { saveLib() } }
        }
    }

    private suspend fun walk(root: String, out: MutableList<Pair<FsEntry, String>>, folders: MutableMap<String, List<FsEntry>>) {
        var level = listOf(root); var depth = 0
        val gate = Semaphore(com.hark.shiguang.cloud.CloudMedia.SCAN_PARALLEL)
        while (level.isNotEmpty() && depth <= 10) {
            val next = java.util.Collections.synchronizedList(ArrayList<String>())
            coroutineScope {
                level.forEach { d ->
                    launch {
                        gate.withPermit {
                            val l = runCatching { fs.list(d) }.getOrElse { if (d == root) throw it else emptyList() }
                            val files = l.filter { !it.isDir }
                            synchronized(folders) { folders[d] = files }
                            val audio = files.filter { Music.isAudio(it.name) && !it.name.startsWith("._") }
                            synchronized(out) { audio.forEach { out.add(it to d) } }
                            next.addAll(l.filter { it.isDir && !it.name.startsWith(".") && !it.name.startsWith("@") && !it.name.startsWith("#") }.map { it.path })
                            if (audio.isNotEmpty()) withContext(Dispatchers.Main) { found += audio.size; phase = "正在列出音乐文件 · 已找到 $found 首" }
                        }
                    }
                }
            }
            level = next.toList(); depth++
        }
    }

    private fun sidecarLrc(e: FsEntry, siblings: List<FsEntry>): String? {
        val stem = NameParser.stem(e.name)
        return siblings.firstOrNull { NameParser.ext(it.name) == "lrc" && NameParser.stem(it.name).equals(stem, true) }?.path
    }

    private fun idOf(p: String) = md5("$sourceKey|$p").take(16)

    /** Track from the file and folder name alone: "歌手 - 歌名", "01. 歌名", folder = album ("CD1" → parent). */
    private fun guess(e: FsEntry, folder: String): Track {
        var stem = NameParser.stem(e.name).replace('_', ' ').trim()
        var no = 0
        Regex("^(\\d{1,3})\\s*[.\\-、)]?\\s+(.+)$").find(stem)?.let { m -> no = m.groupValues[1].toInt(); stem = m.groupValues[2] }
            ?: Regex("^(\\d{1,3})[.\\-、](.+)$").find(stem)?.let { m -> no = m.groupValues[1].toInt(); stem = m.groupValues[2].trim() }
        var artist = ""; var title = stem
        val parts = stem.split(Regex("\\s+[-–—]\\s+|\\s*[–—－]\\s*"), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            if (parts[0].trim().all { it.isDigit() }) { no = parts[0].trim().toInt(); title = parts[1].trim() } else { artist = parts[0].trim(); title = parts[1].trim() }
        }
        val fName = folder.trimEnd('/').substringAfterLast('/')
        var disc = 0
        val discM = Regex("^(?:cd|disc|disk)\\s*(\\d+)$", RegexOption.IGNORE_CASE).find(fName.trim())
        val albumName = if (discM != null) { disc = discM.groupValues[1].toInt(); folder.trimEnd('/').substringBeforeLast('/').substringAfterLast('/') } else fName
        val dirIsRoot = dirs.any { it.path.trimEnd('/') == folder.trimEnd('/') }
        return Track(idOf(e.path), e.path, e.name, e.size, e.mtime, title, artist, if (dirIsRoot) "" else albumName.removePrefix("fm:"),
            trackNo = no, discNo = disc, folder = folder, codec = NameParser.ext(e.name))
    }

    /** Range-reading view of one file for [MusicTags]. Caches the blocks it fetched. */
    private inner class RemoteSrc(val t: Track) : MusicTags.Src {
        override val size: Long = t.size
        private val url: String? = runCatching { fs.playUrl(MediaFile(t.path, t.name, t.size)) }.getOrNull()
        private val headers: Map<String, String> = url?.let { runCatching { fs.headers(it) }.getOrDefault(emptyMap()) }.orEmpty()
        private val blocks = ArrayList<Pair<Long, ByteArray>>()
        var requests = 0
        override fun at(off: Long, len: Int): ByteArray? {
            val u = url ?: return null
            if (off < 0) return null
            blocks.firstOrNull { (o, b) -> off >= o && off + len <= o + b.size || (off >= o && o + b.size == size && off < o + b.size) }?.let { (o, b) ->
                val s = (off - o).toInt(); return if (s == 0 && len >= b.size) b else b.copyOfRange(s, minOf(b.size, s + len))
            }
            if (++requests > 8) return null
            val want = if (size > 0) minOf(len.toLong(), size - off).toInt() else len
            if (want <= 0) return ByteArray(0)
            // big reads (large cover art / moov): only one file at a time holds one, so 3 parallel scans can't fill the heap
            if (want > BIG && !big) { BIG_GATE.acquire(); big = true }
            val b = PlayHttp.range(u, headers, off, want) ?: return null
            if (blocks.sumOf { it.second.size } + b.size <= 2 * MusicTagsMax) blocks.add(off to b)
            return b
        }
        var big = false
        fun release() { blocks.clear(); if (big) { big = false; BIG_GATE.release() } }
    }

    private suspend fun readTags(t0: Track, siblings: List<FsEntry>, covers: MutableMap<String, String>): Track {
        var t = t0.copy(scanned = true)
        val src = RemoteSrc(t0)
        val tags = try { withContext(Dispatchers.IO) { MusicTags.read(src, t0.ext).also { tg -> tg.picture = tg.picture?.takeIf { it.size > 200 }?.let { shrinkArt(it) } } } } finally { src.release() }
        if (tags.hasAny) t = t.copy(tagged = true)
        t = t.copy(
            title = tags.title.ifBlank { t.title }, artist = tags.artist.ifBlank { t.artist }, album = tags.album.ifBlank { t.album },
            albumArtist = tags.albumArtist, year = tags.year, trackNo = if (tags.track > 0) tags.track else t.trackNo,
            discNo = if (tags.disc > 0) tags.disc else t.discNo, durationMs = tags.durationMs, codec = tags.codec.ifBlank { t.codec },
        )
        tags.picture?.takeIf { it.size > 200 }?.let { saveArt(it) }?.let { t = t.copy(artPath = it) }
        if (t.artPath == null) {
            val c = covers.getOrPut(t.folder) { folderCover(siblings) ?: "" }
            if (c.isNotEmpty()) t = t.copy(artPath = c)
        }
        if (tags.lyrics.isNotBlank() && t.lrcPath == null) runCatching { File(lyricsDir, "${t.id}.lrc").writeText(tags.lyrics) }
        return t
    }

    private suspend fun folderCover(siblings: List<FsEntry>): String? {
        val imgs = siblings.filter { NameParser.ext(it.name) in setOf("jpg", "jpeg", "png", "webp") }
        val prefer = listOf("cover", "folder", "front", "albumart", "album", "封面")
        val pick = imgs.firstOrNull { i -> prefer.any { NameParser.stem(i.name).lowercase().startsWith(it) } } ?: imgs.singleOrNull() ?: return null
        if (pick.size > 12L * 1024 * 1024) return null   // a huge scan isn't worth the memory
        val b = withContext(Dispatchers.IO) {
            BIG_GATE.acquire()
            try { runCatching { fs.bytes(pick.path) }.getOrNull()?.let { shrinkArt(it) } } finally { BIG_GATE.release() }
        } ?: return null
        return saveArt(b)
    }

    /** Re-encodes cover art bigger than ~600 px / 300 KB as a 600 px JPEG; decodes subsampled so a 3000 px cover never becomes a 36 MB bitmap. */
    private fun shrinkArt(b: ByteArray): ByteArray = runCatching {
        if (b.size < 300 * 1024) return b
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return b
        var ss = 1; while (o.outWidth / (ss * 2) >= 600 && o.outHeight / (ss * 2) >= 600) ss *= 2
        val bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) ?: return b
        val sc = 600f / maxOf(bm.width, bm.height)
        val out = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * sc).toInt().coerceAtLeast(1), (bm.height * sc).toInt().coerceAtLeast(1), true).also { if (it !== bm) bm.recycle() } else bm
        java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it); out.recycle() }.toByteArray()
    }.getOrDefault(b)

    fun saveArt(b: ByteArray): String? {
        if (b.size < 200) return null
        val f = File(artDir, md5(b).take(20) + ".img")
        if (!f.exists()) runCatching { f.writeBytes(b) }.onFailure { return null }
        return f.absolutePath
    }

    // ---------------------------------------------------------------- lyrics

    /** LRC text of [t]: cached → sidecar .lrc → online (when allowed). Null when there are none. */
    suspend fun lyrics(t: Track): String? = withContext(Dispatchers.IO) {
        val f = File(lyricsDir, "${t.id}.lrc")
        if (f.exists() && f.length() > 0) return@withContext f.readText()
        t.lrcPath?.let { p -> runCatching { fs.bytes(p) }.getOrNull()?.let { MusicTags.decodeText(it) }?.takeIf { it.isNotBlank() }?.let { f.writeText(it); return@withContext it } }
        val none = File(lyricsDir, "${t.id}.none")
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 86400_000L) return@withContext null
        if (!MusicPrefs.online) return@withContext null
        val l = runCatching { MusicOnline.lyrics(t) }.getOrNull()
        if (l.isNullOrBlank()) { runCatching { none.writeText("") }; null } else { f.writeText(l); l }
    }

    companion object {
        fun md5(s: String) = md5(s.toByteArray())
        fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}

object MusicPrefs {
    /** 联网补全（网易云音乐公开接口 + LRCLIB），设置里可关。 */
    var online: Boolean
        get() = Store.getStr("music.online", "1") == "1"
        set(v) = Store.putStr("music.online", if (v) "1" else "0")
}

object Music {
    /** Extensions the scanner collects. */
    val AUDIO = setOf("mp3", "flac", "ape", "wav", "m4a", "aac", "alac", "ogg", "oga", "opus", "wma", "dsf", "dff", "aiff", "aif", "aifc", "wv", "m4b")
    /** media3 extractors (with the Jellyfin FFmpeg decoder for ALAC / Opus / Vorbis fallbacks). */
    val EXO = setOf("mp3", "flac", "wav", "m4a", "aac", "alac", "ogg", "oga", "opus", "m4b")
    /** No media3 extractor (APE, ASF/WMA, DSD, WavPack, AIFF in 1.3.1): played by libVLC, already in the APK for rmvb/wmv. */
    val VLC = setOf("ape", "wma", "dsf", "dff", "wv", "aiff", "aif", "aifc")

    fun isAudio(name: String) = NameParser.ext(name) in AUDIO

    private val libs = HashMap<String, MusicLib>()
    fun lib(): MusicLib? = Movies.keyOf()?.let { lib(it) }
    fun lib(key: String): MusicLib = synchronized(libs) { libs.getOrPut(key) { MusicLib(key) } }.also { it.ensure() }

    private val SPLIT = Regex("\\s*(?:/|;|；|、|&|＆|,|，| feat\\.? | ft\\. | x )\\s*", RegexOption.IGNORE_CASE)
    fun splitArtists(s: String): List<String> = if (s.isBlank()) listOf("未知歌手") else s.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(s.trim()) }

    fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
}

/** Parsed LRC: lines sorted by time. Handles several time tags per line, [offset:], and "translation" lines with the same time. */
object Lrc {
    data class Line(val ms: Long, val text: String, val sub: String = "")
    private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    fun parse(s: String): List<Line> {
        var offset = 0L
        Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(s)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0 }
        val out = ArrayList<Pair<Long, String>>()
        s.lineSequence().forEach { raw ->
            val tags = TAG.findAll(raw).toList()
            if (tags.isEmpty()) return@forEach
            val text = raw.substring(tags.last().range.last + 1).replace(Regex("<\\d+:\\d+(?:\\.\\d+)?>"), "").trim()
            tags.forEach { m ->
                val frac = m.groupValues[3]
                val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + when (frac.length) { 0 -> 0; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add((ms - offset).coerceAtLeast(0) to text)
            }
        }
        // merge same-time lines (original + translation)
        val merged = ArrayList<Line>()
        out.sortedBy { it.first }.forEach { (t, x) ->
            val last = merged.lastOrNull()
            if (last != null && last.ms == t && x.isNotEmpty()) merged[merged.size - 1] = if (last.text.isEmpty()) last.copy(text = x) else last.copy(sub = if (last.sub.isEmpty()) x else last.sub)
            else merged.add(Line(t, x))
        }
        return merged.filter { it.text.isNotEmpty() || merged.size < 3 }
    }
    /** Plain lyrics without timestamps (USLT) as one untimed list. */
    fun plain(s: String): List<Line> = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Line(-1, it) }
}
