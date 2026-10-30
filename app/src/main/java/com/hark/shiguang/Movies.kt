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
    /** 1.1.0: show id → path of the episode last played (shows.json; missing for data from older versions, see [ShowResume]). */
    private val lastEp = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** 用户 2026-10-22 反馈 #2 / H4：库文件在 IO 线程读完才置 true；界面在此之前显示「正在读取」，不再在主线程读盘。 */
    var ready by mutableStateOf(false)
        private set
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loadedSignal = CompletableDeferred<Unit>()
    @Volatile private var dirsTouched = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kind: String get() = sourceKey.substringBefore(':')
    val accountId: String get() = sourceKey.substringAfter(':')
    val dir: File get() = File(App.ctx.filesDir, (if (kind == "dav") "dav" else "fn") + "/$accountId/movies").apply { mkdirs() }
    private val imgDir: File get() = File(dir, "img").apply { mkdirs() }
    val fs: MovieFs get() = if (kind == "dav") DavFs(accountId) else FnFs()

    /** Starts loading library.json / progress.json / fixes.json on the IO scope (once). Never blocks the caller. */
    fun ensure() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            loadFixes()
            val d = runCatching { JSONObject(File(dir, "config.json").readText()).optJSONArray("dirs")?.objs().orEmpty().map { MovieDir(it.optString("path"), it.optString("name"), it.optString("kind", "auto")) } }.getOrNull()
            val l = runCatching { JSONArray(File(dir, "library.json").readText()).objs().map(MovieItem::of).distinctBy { it.id } }.getOrDefault(emptyList())
            runCatching {
                val o = JSONObject(File(dir, "progress.json").readText())
                o.keys().forEach { k -> o.optJSONObject(k)?.let { progress.putIfAbsent(k, Progress(it.optLong("p"), it.optLong("d"), it.optLong("t"))) } }
            }
            runCatching {
                val o = JSONObject(File(dir, "shows.json").readText())
                o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { lastEp.putIfAbsent(k, it) } }
            }
            withContext(Dispatchers.Main) {
                if (d != null && !dirsTouched) dirs = d
                if (items.isEmpty()) items.addAll(l)
                version++; ready = true
            }
            loadedSignal.complete(Unit)
        }
    }

    /** Suspends until [ensure]'s load has finished (scans and lookups off the UI thread use this). */
    suspend fun awaitReady() { ensure(); loadedSignal.await() }

    fun saveDirs(l: List<MovieDir>) {
        dirsTouched = true
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

    /** 全面审查 A1：标为已看（一次写盘）。没播过的文件不知道时长，用 1/1 记成「看完」。 */
    fun markWatched(paths: List<String>) {
        val now = System.currentTimeMillis()
        paths.forEach { p -> val d = progress[p]?.dur?.takeIf { it > 0 } ?: 1L; progress[p] = Progress(d, d, now) }
        saveProgress(); version++
    }
    /** A1：标为未看 = 清掉这些文件的进度（一次写盘）。 */
    fun markUnwatched(paths: List<String>) { paths.forEach { progress.remove(it) }; saveProgress(); version++ }

    /** A1：从「继续观看」移除。只记移除时间（Store，不动 progress.json）；之后再播放会重新出现。 */
    private val cwHideKey get() = "movies.cwHide.$sourceKey"
    private fun cwHidden(): Map<String, Long> = Store.getStr(cwHideKey).split('\n').mapNotNull { l ->
        l.substringBefore('=').takeIf { it.isNotEmpty() }?.let { it to (l.substringAfter('=').toLongOrNull() ?: 0L) } }.toMap()
    fun hideFromContinue(id: String) {
        val m = cwHidden().toMutableMap(); m[id] = System.currentTimeMillis()
        Store.putStr(cwHideKey, m.entries.toList().takeLast(200).joinToString("\n") { "${it.key}=${it.value}" }); version++
    }

    /** 1.1.0: remembers which episode of show [showId] was played last (separate file, progress.json keeps its format). */
    fun setLastEpisode(showId: String, path: String) {
        if (showId.isEmpty() || path.isEmpty() || lastEp[showId] == path) return
        lastEp[showId] = path
        runCatching { File(dir, "shows.json").writeText(JSONObject().apply { lastEp.forEach { (k, v) -> put(k, v) } }.toString()) }
        version++
    }

    /** 1.1.0: what 播放 / 继续播放 starts for a show (see [ShowResume]); null for a show without episodes. */
    fun resumeShow(m: MovieItem): ShowResume.Pick? { version; return ShowResume.pick(m.episodes.map { it.path }, { progress[it] }, lastEp[m.id]) }

    /** Items with an unfinished file, most recent first, paired with what to resume. */
    fun continueWatching(): List<Pair<MovieItem, Episode?>> {
        version
        val out = ArrayList<Triple<MovieItem, Episode?, Long>>()
        items.forEach { m ->
            if (m.kind == "movie") {
                m.files.mapNotNull { f -> progress[f.path]?.takeIf { !it.watched && it.pos > 30_000 } }.maxByOrNull { it.at }?.let { out.add(Triple(m, null, it.at)) }
            } else {
                val eps = m.episodes
                // only shows that were started (an episode has progress) appear here
                val at = eps.mapNotNull { e -> progress[e.path]?.at }.maxOrNull() ?: return@forEach
                val pick = resumeShow(m)?.takeIf { !it.finished } ?: return@forEach
                eps.firstOrNull { it.path == pick.path }?.let { out.add(Triple(m, it, at)) }
            }
        }
        val hidden = cwHidden()
        return out.filter { (m, _, at) -> (hidden[m.id] ?: 0L) < at }.sortedByDescending { it.third }.map { it.first to it.second }.take(12)
    }

    fun item(id: String): MovieItem? = items.firstOrNull { it.id == id }

    fun clearCache() { job?.cancel(); items.clear(); File(dir, "library.json").delete(); imgDir.deleteRecursively(); version++ }

    // ---------------------------------------------------------------- scan

    fun scan(rescrapeUnmatched: Boolean = false) {
        if (job?.isActive == true) return
        ensure()
        job = scope.launch {
            awaitReady()
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

    // inline: the loader may call suspend functions (no runBlocking inside the scraper)
    private inline fun cacheImage(key: String, load: () -> ByteArray?): String? {
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
            posterE?.let { e -> cacheImage("$sourceKey|${e.path}") { f.bytes(e.path) } }?.let { m = m.copy(poster = it, source = m.source.ifEmpty { "local" }) }
            fanE?.let { e -> cacheImage("$sourceKey|${e.path}") { f.bytes(e.path) } }?.let { m = m.copy(backdrop = it) }
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
            var (hit, g) = withContext(Dispatchers.IO) { Tmdb.decide(Tmdb.rank(m.kind, NameParser.queries(m.query.ifEmpty { m.title }, m.altQuery), m.year), m.year) }
            if (hit == null && AiConfig.configured && (m.files.isNotEmpty() || m.kind == "tv")) {
                aiParse(m)?.let { (t, y) ->
                    val (h2, g2) = withContext(Dispatchers.IO) { Tmdb.decide(Tmdb.rank(m.kind, NameParser.queries(t), y ?: m.year), y ?: m.year) }
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
    fun lib(key: String): MovieLib = synchronized(libs) { libs.getOrPut(key) { MovieLib(key) } }.also { it.ensure() }
    fun forget(key: String) { libs.remove(key) }
}
