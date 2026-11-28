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
 * 第 4 批：两者都有上限 + LRU（[MusicLib.pruneCaches]：封面 200MB、歌词 50MB，只删曲库没引用的旧文件）。
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
    /** 标签读取器版本（1.4.0 起为 [Music.TAG_VER]）；旧库没有这个字段时为 0。 */
    val tagVer: Int = 0,
    /** 1.7.32：联网补全用的匹配规则版本（[Music.ONLINE_VER]）；旧库没有这个字段时为 0。只是多一个可选键，旧版本读到会忽略。 */
    val onVer: Int = 0,
    /**
     * 1.7.33：高分联网匹配给的歌手（标签里是「未知艺术家」一类占位、或歌手栏只有「编号 + 名字」时才记）。可选键 "oa"，
     * 只用于显示，标签里的原值 [artist] 不动；旧版本读到会忽略。
     */
    val onlineArtist: String = "",
) {
    /** 1.7.32：清洗后的歌名（去音轨号、「歌手 - 」、[FLAC] 等，见 [MusicName]）。只用于显示和联网匹配，[title] 保持原样。 */
    private val parsed: MusicName.Parsed by lazy {
        MusicName.clean(title.ifBlank { NameParser.stem(name) }, listOf(artist, albumArtist).filter { it.isNotBlank() }, trackNo, fromFileName = !tagged)
    }
    /** 1.7.33：歌手名规范化的输入（见 [MusicArtist.In]）。 */
    val nameInput: MusicArtist.In by lazy { MusicArtist.In(id, artist, albumArtist, parsed.artist, parsed.title, tagged, onlineArtist) }
    /** 1.7.33：规范后的歌手（和整个曲库一起算的结果，见 [MusicNames]）；歌手栏和歌名栏对调时 [MusicArtist.Fix.title] 是新歌名。 */
    val nameFix: MusicArtist.Fix get() = MusicNames.fix(this)
    val cleanTitle: String get() = nameFix.title ?: parsed.title.ifBlank { title.ifBlank { NameParser.stem(name) } }
    /** 从歌名里拆出来的歌手（标签里没有歌手时用来显示和匹配）。 */
    val parsedArtist: String get() = parsed.artist
    /** 界面上显示的歌名：设置里「自动规范歌名」开着时是 [cleanTitle]，关掉时是原始标题。 */
    val displayTitle: String get() = if (MusicPrefs.cleanNames) cleanTitle else title.ifBlank { name }
    /** 联网匹配时用的歌手（标签歌手、专辑歌手、歌名里拆出的）。 */
    /** 1.7.33：用规范后的歌手（「01张杰」→「张杰」、对调后的歌手），不再拿「08素颜」去搜。 */
    val queryArtists: List<String> get() = (nameFix.artists + listOf(MusicArtist.clean(albumArtist))).filter { it.isNotBlank() && it != MusicArtist.UNKNOWN }.distinctBy { MusicArtist.key(it) }
    /** 用户 2026-11-02 反馈：旧版本把有损的 WAV INFO（中文已变成 ??）存进了曲库。 */
    val garbled: Boolean get() = MusicTags.isGarbled(title) || MusicTags.isGarbled(artist) || MusicTags.isGarbled(album)
    /**
     * 这条缓存要不要按新版读取器重读：旧版读出来、且歌名 / 歌手 / 专辑是乱码；或（1.7.16）旧版读的时候一个字节都没读到
     * ——那时文件头拿不到（播放地址没查到 / 网络或 NAS 暂时不通）也被当成「没有标签」永久记为已读，于是一直只有文件名、没有封面。
     * 只能从结果判断：没读到标签、也没有封面的，升级后重读一次（真没标签的文件读完照旧，之后不再重复）。
     */
    val staleTags: Boolean get() = scanned && tagVer < Music.TAG_VER && (garbled || (!tagged && artPath == null))
    val ext: String get() = NameParser.ext(name)
    /**
     * 1.7.33：显示的歌手 / 专辑都经过 [MusicArtist] 规范（去编号、年份、首尾装饰符，占位统一成「未知歌手」，同名合并）。
     * 设置「自动规范歌名和歌手」关掉时按标签原样显示（只按 / & 拆多歌手）。
     */
    val artists: List<String> get() = if (MusicPrefs.cleanNames) nameFix.artists else Music.splitArtists(artist.ifBlank { albumArtist }.ifBlank { parsedArtist })
    val displayArtist: String get() = artists.joinToString(" / ")
    /** 规范后的专辑名（空 = 没有专辑 / 占位）。 */
    val cleanAlbum: String get() = if (MusicPrefs.cleanNames) MusicArtist.cleanAlbum(album) else album.trim()
    val displayAlbum: String get() = cleanAlbum.ifBlank { MusicArtist.UNKNOWN_ALBUM }
    /** 专辑分组键：规范后的专辑名 + 专辑歌手（「(2002)时光·漫步」和「时光·漫步」是同一张）；没有专辑按文件夹。 */
    val albumKey: String get() {
        val al = cleanAlbum
        val aa = MusicArtist.clean(albumArtist).takeIf { it.isNotEmpty() && it != MusicArtist.UNKNOWN } ?: if (al.isEmpty()) "" else artists.first()
        return (if (al.isEmpty()) folder else MusicArtist.key(al)) + "\u0001" + MusicArtist.key(aa)
    }
    /** 专辑卡片上的歌手。 */
    val displayAlbumArtist: String get() = MusicArtist.clean(albumArtist).takeIf { it.isNotEmpty() && it != MusicArtist.UNKNOWN } ?: displayArtist

    fun json(): JSONObject = JSONObject().put("id", id).put("p", path).put("n", name).put("s", size).put("m", mtime).put("t", title).put("ar", artist)
        .put("al", album).put("aa", albumArtist).put("y", year).put("tn", trackNo).put("dn", discNo).put("d", durationMs).put("c", codec)
        .put("art", artPath ?: "").put("lrc", lrcPath ?: "").put("f", folder).put("tg", tagged).put("sc", scanned).put("on", online).put("tv", tagVer).apply { if (onVer > 0) put("ov", onVer); if (onlineArtist.isNotBlank()) put("oa", onlineArtist) }
    companion object {
        fun of(o: JSONObject) = Track(o.optString("id"), o.optString("p"), o.optString("n"), o.optLong("s"), o.optLong("m"), o.optString("t"),
            o.optString("ar"), o.optString("al"), o.optString("aa"), o.optInt("y"), o.optInt("tn"), o.optInt("dn"), o.optLong("d"), o.optString("c"),
            o.optString("art").ifEmpty { null }, o.optString("lrc").ifEmpty { null }, o.optString("f"), o.optBoolean("tg"), o.optBoolean("sc"), o.optBoolean("on"), o.optInt("tv"), o.optInt("ov"), o.optString("oa"))
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
    /** A2：「我喜欢」「最近播放」的曲目 id，新的在前（[MusicMarks]）。随库在 IO 线程读入，改动后在 IO 线程写盘。 */
    val favs = mutableStateListOf<String>()
    val recent = mutableStateListOf<String>()
    /** 用户 2026-10-22 反馈 #2 / H4 / H5：库文件在 IO 线程读完、在主线程装进 [tracks] 后才置 true（加载完成才置位，线程安全）。 */
    var ready by mutableStateOf(false)
        private set
    /** 用户 2026-11-02 反馈：库里有旧版读成 ?? 的歌，打开音乐页时立刻安静地重读这些（只重读它们）。 */
    var needsRetag = false
    /**
     * 1.7.33：歌手照片，键 = [MusicArtist.key]（同一歌手的各种写法合并后共用一张）。值 = 封面目录里的文件，"" = 查过没有。
     * 存在 artists.json（新文件，曲库格式不变）；查过没有的 30 天后再查。
     */
    private val artistPhotos = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val artistChecked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 歌手照片文件（没有 / 文件已被清掉 = null）。任意线程。 */
    fun artistPhoto(name: String): String? = artistPhotos[MusicArtist.key(name)]?.takeIf { it.isNotEmpty() && File(it).exists() }
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loadedSignal = CompletableDeferred<Unit>()
    @Volatile private var dirsTouched = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kind: String get() = sourceKey.substringBefore(':')
    val accountId: String get() = sourceKey.substringAfter(':')
    val dir: File get() = File(App.ctx.filesDir, (if (kind == "dav") "dav" else "fn") + "/$accountId/music").apply { mkdirs() }
    val artDir: File get() = File(dir, "art").apply { mkdirs() }
    val lyricsDir: File get() = File(dir, "lyrics").apply { mkdirs() }
    val fs: MovieFs get() = if (kind == "dav") DavFs(accountId) else FnFs()

    /** Starts loading config.json / library.json on the IO scope (once). Never blocks the caller. */
    fun ensure() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val d = runCatching { JSONObject(File(dir, "config.json").readText()).optJSONArray("dirs")?.objs().orEmpty().map { MusicDir(it.optString("path"), it.optString("name")) } }.getOrNull()
            // H1: older libraries may hold the same song twice (nested music folders) — duplicate ids crash the LazyColumn keys.
            val l = runCatching { JSONArray(File(dir, "library.json").readText()).objs().map(Track::of).distinctBy { it.id } }.getOrDefault(emptyList())
            val fv = readIds("favorites.json"); val rc = readIds("recent.json")
            loadArtistPhotos()
            runCatching { MusicNames.update(l) }.onFailure { Diag.e("MUSIC", it) }
            withContext(Dispatchers.Main) {
                if (d != null && !dirsTouched) dirs = d
                if (tracks.isEmpty()) tracks.addAll(l)
                if (favs.isEmpty()) favs.addAll(fv)
                if (recent.isEmpty()) recent.addAll(rc)
                needsRetag = l.any { it.staleTags }
                version++; ready = true
            }
            loadedSignal.complete(Unit)
        }
    }

    /** Suspends until [ensure]'s load has finished (the player resolving a cold-start track, scans). */
    suspend fun awaitReady() { ensure(); loadedSignal.await() }

    fun saveDirs(l: List<MusicDir>) {
        dirsTouched = true
        dirs = l
        File(dir, "config.json").writeText(JSONObject().put("dirs", JSONArray().apply { l.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) } }).toString(2))
        MediaIsolation.invalidate(sourceKey)   // 照片 / 视频 tabs and the 影视 scan leave these folders out
        // tracks of removed folders disappear at once
        val keep = tracks.filter { t -> l.any { inDir(t.path, it.path) } }
        if (keep.size != tracks.size) { tracks.clear(); tracks.addAll(keep); version++; saveLib() }
    }

    private fun loadArtistPhotos() {
        runCatching {
            val o = JSONObject(File(dir, "artists.json").readText())
            o.keys().forEach { k -> o.optJSONObject(k)?.let { e -> artistPhotos[k] = e.optString("p"); artistChecked[k] = e.optLong("t") } }
        }
    }

    @Synchronized private fun saveArtistPhotos() {
        runCatching {
            val o = JSONObject()
            artistPhotos.forEach { (k, p) -> o.put(k, JSONObject().put("p", p).put("t", artistChecked[k] ?: 0L)) }
            File(dir, "artists.json").writeText(o.toString())
        }.onFailure { Diag.e("MUSIC", it) }
    }

    /**
     * 1.7.33：给歌手页补照片（网易云歌手照片）。[seed]：这次联网补全时已经下好的（歌手名 → 文件）。
     * 每次扫描最多查 [ARTIST_PHOTO_BATCH] 位；网络出错的不记，下次再查。
     */
    private suspend fun fetchArtistPhotos(seed: Map<String, String>) {
        seed.forEach { (name, f) -> if (f.isNotEmpty()) { val k = MusicArtist.key(name); artistPhotos[k] = f; artistChecked[k] = System.currentTimeMillis() } }
        val now = System.currentTimeMillis()
        val names = tracks.toList().flatMap { it.artists }.filter { it != MusicArtist.UNKNOWN }.distinctBy { MusicArtist.key(it) }
        val todo = names.filter { n ->
            val k = MusicArtist.key(n); val p = artistPhotos[k]
            p == null || (p.isEmpty() && now - (artistChecked[k] ?: 0L) > 30L * 24 * 3600_000) || (p.isNotEmpty() && !File(p).exists())
        }.take(ARTIST_PHOTO_BATCH)
        if (todo.isEmpty()) { if (seed.isNotEmpty()) saveArtistPhotos(); return }
        withContext(Dispatchers.Main) { total = todo.size; done = 0; phase = "正在补全歌手照片" }
        var errors = 0
        for (n in todo) {
            currentCoroutineContext().ensureActive()
            try {
                val f = MusicOnline.artistPhoto(n)?.let { MusicOnline.saveUrl(this, it) } ?: ""
                val k = MusicArtist.key(n); artistPhotos[k] = f; artistChecked[k] = System.currentTimeMillis()
            } catch (e: java.io.IOException) { if (++errors <= 2) Diag.log("MUSIC", "artist photo failed (retry next scan): ${e.javaClass.simpleName} ${e.message?.take(80)}") }
            withContext(Dispatchers.Main) { done++ }
        }
        saveArtistPhotos()
        withContext(Dispatchers.Main) { version++ }
    }

    @Synchronized private fun saveLib() { runCatching { File(dir, "library.json").writeText(JSONArray().apply { tracks.toList().forEach { put(it.json()) } }.toString()) } }

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ A2 我喜欢 / 最近播放

    private fun readIds(name: String): List<String> =
        runCatching { JSONArray(File(dir, name).readText()).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } }.getOrDefault(emptyList())
    /** Writes the list as it is when the write runs, so an older write finishing late never leaves stale ids on disk. */
    private fun writeIds(name: String, ids: () -> List<String>) {
        scope.launch { synchronized(this@MusicLib) { runCatching { File(dir, name).writeText(JSONArray(ids()).toString()) }.onFailure { Diag.e("MUSIC", it) } } }
    }
    /** Main thread. Returns true when the track is now a favourite. */
    fun toggleFav(id: String): Boolean {
        val n = MusicMarks.toggle(favs.toList(), id)
        favs.clear(); favs.addAll(n); writeIds("favorites.json") { favs.toList() }
        return id in n
    }
    /** Main thread: the player moved to [id]. */
    fun markPlayed(id: String) {
        if (recent.firstOrNull() == id) return
        val n = MusicMarks.push(recent.toList(), id)
        recent.clear(); recent.addAll(n); writeIds("recent.json") { recent.toList() }
    }
    fun clearRecent() { recent.clear(); writeIds("recent.json") { recent.toList() } }

    /**
     * 第 4 批：封面/歌词缓存上限 + LRU 清理。封面 art/ 200MB、歌词 lyrics/ 50MB（软上限）。
     * 按最后修改时间从旧到新删，只删曲库没引用的文件（封面 artPath / 歌词 <id>.lrc|.none）；
     * 如果全被引用、删无可删，就不硬删（曲库本身需要它们），只记一条日志。默认 10 分钟最多清一次。
     */
    fun pruneCaches(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastPrune < 10 * 60_000L) return
        lastPrune = System.currentTimeMillis()
        pruneDir(artDir, ART_CAP, (tracks.mapNotNull { it.artPath } + artistPhotos.values.filter { it.isNotEmpty() }).map { File(it).name }.toSet())
        pruneDir(lyricsDir, LYRICS_CAP, tracks.flatMap { listOf("${it.id}.lrc", "${it.id}.net.lrc", "${it.id}.none") }.toSet())
    }

    private var lastPrune = 0L

    private fun pruneDir(dir: File, cap: Long, keep: Set<String>) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        var freed = 0L
        files.filter { it.name !in keep }.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= cap) return@forEach
            val len = f.length()
            if (f.delete()) { total -= len; freed += len }
        }
        if (freed > 0) Diag.log("MUSIC", "prune ${dir.name}: freed ${freed / 1024} KB, now ${total / 1024} KB / cap ${cap / 1024} KB")
    }

    fun clearCache() {
        job?.cancel(); tracks.clear(); File(dir, "library.json").delete(); listingFile.delete(); dirSnaps.clear(); artDir.deleteRecursively(); lyricsDir.deleteRecursively()
        artistPhotos.clear(); artistChecked.clear(); File(dir, "artists.json").delete(); version++
    }

    /** The player learnt the real length (tags had none). */
    fun setDuration(id: String, ms: Long) {
        val i = tracks.indexOfFirst { it.id == id }
        if (i < 0 || ms <= 0 || kotlin.math.abs(tracks[i].durationMs - ms) < 1500) return
        tracks[i] = tracks[i].copy(durationMs = ms); version++
        scope.launch { saveLib() }
    }

    /** H1: the outermost folders only — a folder inside another selected one adds nothing to walk. */
    private fun rootDirs(l: List<MusicDir>): List<MusicDir> {
        val out = ArrayList<MusicDir>()
        l.sortedBy { MediaIsolation.normPath(it.path).length }.forEach { d -> if (out.none { MediaIsolation.isUnder(d.path, it.path) }) out.add(d) }
        return out
    }

    private fun inDir(p: String, d: String): Boolean { val a = p.trimEnd('/'); val b = d.trimEnd('/'); return b.isEmpty() || a == b || a.startsWith("$b/") }

    // ---------------------------------------------------------------- scan

    /**
     * 优化 11（用户 2026-11-02）：自动刷新（[quiet]）时界面只在顶部显示一行小字「正在更新」，列表照常可用，不出大进度条。
     * 优化 12：刷新失败（离线 / 登录过期）时列表保持上次的内容。
     */
    var quiet by mutableStateOf(false)
        private set

    /**
     * 优化 8（用户 2026-11-02）：增量扫描。叶子文件夹（上次列出时没有子文件夹）的修改时间和上次一样时，直接用上次的文件列表，
     * 不再对它发 PROPFIND；文件夹里增删文件、增删子文件夹都会改它的修改时间。服务器不报修改时间（0）的文件夹照常列。
     * 自动刷新用缓存；手动点刷新（[quiet] = false）每个文件夹都重新列，作为兜底。
     * 歌曲标签本来就只读新增 / 改过（路径 + 修改时间 + 大小）的文件。
     */
    private class DirSnap(val mtime: Long, val files: List<FsEntry>)
    private val dirSnaps = java.util.concurrent.ConcurrentHashMap<String, DirSnap>()
    private val listingFile: File get() = File(dir, "listing.json")
    @Volatile private var snapsLoaded = false

    private fun loadSnaps() {
        if (snapsLoaded) return
        snapsLoaded = true
        runCatching {
            val o = JSONObject(listingFile.readText())
            o.keys().forEach { k ->
                val d = o.getJSONObject(k)
                val files = d.optJSONArray("f")?.objs().orEmpty().map { e -> FsEntry(e.optString("n"), e.optString("p"), false, e.optLong("s"), mtime = e.optLong("m")) }
                dirSnaps[k] = DirSnap(d.optLong("m"), files)
            }
        }
    }

    private fun saveSnaps() {
        runCatching {
            val o = JSONObject()
            dirSnaps.forEach { (k, v) ->
                o.put(k, JSONObject().put("m", v.mtime).put("f", JSONArray().apply { v.files.forEach { e -> put(JSONObject().put("n", e.name).put("p", e.path).put("s", e.size).put("m", e.mtime)) } }))
            }
            listingFile.writeText(o.toString())
        }
    }

    /**
     * 1.7.21（用户 2026-11-27「一直在后台扫描、每次都重新扫描」）：读不到的歌（1.7.16 起保持未读）和联网补全出错的歌，
     * 以前每次扫描都全部重试，网盘不通时每次都要把它们挨个请求一遍、读条永远走不完。现在按 [RetryBackoff] 退避
     * （1h / 6h / 24h，第 4 次后等手动刷新），记录在 music/retry.json（不改 library.json 格式）。
     * [manual] = 用户手动（下拉刷新 / 立即扫描 / 重新刮削）：不看退避、清掉旧的失败记录。
     */
    val retryBook: RetryBook get() = RetryBooks.of(File(dir, "retry.json"))

    /** [rescrape]: read every file's tags again (设置 → 重新刮削). */
    fun scan(rescrape: Boolean = false, quiet: Boolean = false, manual: Boolean = !quiet || rescrape, reason: String = if (manual) "手动" else "自动") {
        if (job?.isActive == true) return
        ensure()
        job = scope.launch {
            awaitReady()
            withContext(Dispatchers.Main) { scanning = true; this@MusicLib.quiet = quiet; error = null; done = 0; total = 0; found = 0; phase = "正在列出音乐文件" }
            val t0 = System.currentTimeMillis()
            val book = retryBook
            if (manual) book.reset()
            var newFiles = 0; var readOk = 0; var readFail = 0; var deferred = 0
            try {
                val files = ArrayList<Pair<FsEntry, String>>()           // audio file, folder
                val folders = HashMap<String, List<FsEntry>>()          // folder → its files (covers, lrc)
                // H1: nested folders (/Music and /Music/周杰伦) are walked once, from the outermost one,
                // and every file is kept once by its normalized path — the same song twice means two
                // tracks with the same id, which crashes the song list (duplicate LazyColumn key).
                loadSnaps()
                val stats = intArrayOf(0, 0)   // listed, reused
                for (d in rootDirs(dirs)) walk(d.path, files, folders, useSnaps = quiet, stats = stats)
                saveSnaps()
                Diag.log("MUSIC", "list ${files.size} files in ${System.currentTimeMillis() - t0} ms: listed ${stats[0]} folders, reused ${stats[1]} unchanged (quiet=$quiet)")
                val seen = HashSet<String>()
                val unique = files.filter { (e, _) -> seen.add(MediaIsolation.normPath(e.path)) }
                val old = tracks.associateBy { it.path }
                newFiles = unique.count { (e, _) -> old[e.path] == null }
                val fresh = unique.map { (e, folder) ->
                    val o = old[e.path]
                    val lrc = sidecarLrc(e, folders[folder].orEmpty())
                    if (o != null && Music.keepCached(o, e.size, e.mtime, rescrape)) o.copy(lrcPath = lrc ?: o.lrcPath, folder = folder)
                    else guess(e, folder).copy(lrcPath = lrc)
                }.distinctBy { it.id }.sortedWith(compareBy({ it.folder }, { it.discNo }, { it.trackNo }, { it.name }))
                withContext(Dispatchers.Main) { tracks.clear(); tracks.addAll(fresh); version++ }
                saveLib()

                // embedded tags (only new / changed files); 1.7.21: files that failed recently wait (backoff)
                book.retain(fresh.mapTo(HashSet()) { it.id } + fresh.mapTo(HashSet()) { "on:" + it.id })
                val now = System.currentTimeMillis()
                val unread = fresh.filter { !it.scanned }
                val todo = unread.filter { book.eligible(it.id, manual, now) }
                deferred = unread.size - todo.size
                if (deferred > 0) Diag.log("MUSIC", "tags: $deferred unreadable files wait for their retry time (backoff), ${todo.size} to read now")
                withContext(Dispatchers.Main) { total = todo.size; done = 0; phase = if (todo.isEmpty()) "" else "正在读取歌曲信息" }
                val gate = Semaphore(3)
                val failed = java.util.concurrent.atomic.AtomicInteger(0)
                val coverCache = java.util.concurrent.ConcurrentHashMap<String, String>()   // folder → cover file ("" = none)
                val batch = Batch()
                coroutineScope {
                    todo.forEach { t ->
                        launch {
                            gate.withPermit {
                                // 1.7.16：读失败（网络 / 地址）时保持未读（scanned = false），下次扫描重试；以前记成已读，永远不再刮削
                                val n = runCatching { readTags(t, folders[t.folder].orEmpty(), coverCache) }
                                    .onSuccess { book.ok(t.id) }
                                    .getOrElse { e -> book.fail(t.id); if (failed.incrementAndGet() <= 3) Diag.log("MUSIC", "tag read failed, retry after backoff (attempt ${book.attempts(t.id)}): ${e.javaClass.simpleName} ${e.message?.take(80)}"); t }
                                batch.add(n)
                            }
                        }
                    }
                }
                batch.flush(true)
                book.save()
                readFail = failed.get(); readOk = todo.size - readFail
                if (failed.get() > 0) Diag.log("MUSIC", "tags: ${failed.get()} of ${todo.size} files could not be read this time (kept unread, retried after backoff)")

                // online completion: only what is missing, never overwrites tags; failures are silent
                if (MusicPrefs.online) {
                    // 1.7.33：先按整个曲库算好规范歌手名，联网时用「张杰」而不是「01张杰」去搜
                    MusicNames.update(tracks.toList())
                    val nowOn = System.currentTimeMillis()
                    // 1.7.32：旧规则查过但没有封面的歌（多半是歌名带「07-」之类没对上），按新规则再查一次（onVer 记下，之后不再重复）
                    val need = tracks.filter { Music.needsOnline(it) && book.eligible("on:" + it.id, manual, nowOn) }
                    var seedPhotos: Map<String, String> = emptyMap()
                    if (need.isNotEmpty()) {
                        withContext(Dispatchers.Main) { total = need.size; done = 0; phase = "正在联网补全封面和信息" }
                        val g2 = Semaphore(2)
                        val albumArt = java.util.concurrent.ConcurrentHashMap<String, String>()
                        val urlArt = java.util.concurrent.ConcurrentHashMap<String, String>()
                        val artistArt = java.util.concurrent.ConcurrentHashMap<String, String>()
                        val b2 = Batch()
                        coroutineScope {
                            need.forEach { t ->
                                launch {
                                    g2.withPermit {
                                        // 1.7.16：联网出异常时不记为「已补全」，下次扫描再试（查无结果才记）
                                        val n = runCatching { MusicOnline.complete(this@MusicLib, t, albumArt, urlArt, artistArt) }
                                            .fold({ book.ok("on:" + t.id); it.copy(online = true, onVer = Music.ONLINE_VER) }, { e -> book.fail("on:" + t.id); Diag.log("MUSIC", "online completion failed, retry after backoff: ${e.javaClass.simpleName} ${e.message?.take(80)}"); t })
                                        b2.add(n)
                                    }
                                }
                            }
                        }
                        b2.flush(true)
                        book.save()
                        seedPhotos = artistArt
                    }
                    MusicNames.update(tracks.toList())
                    runCatching { fetchArtistPhotos(seedPhotos) }.onFailure { if (it is CancellationException) throw it; Diag.e("MUSIC", it) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Diag.log("MUSIC", "scan failed: ${e.javaClass.simpleName} ${e.message?.take(120)} (keeping ${tracks.size} cached tracks)")
                withContext(Dispatchers.Main) { error = e.message ?: "扫描失败" }
            }
            withContext(Dispatchers.Main) { scanning = false; this@MusicLib.quiet = false; phase = "" }
            book.save()
            ScanLog.log("音乐", reason, "$kind ${dirs.size} 个文件夹 ${tracks.size} 首", System.currentTimeMillis() - t0, newFiles, readFail, deferred, "tagsRead=$readOk")
            pruneCaches(force = true)
        }
        // 1.7.30：扫描被取消（清除缓存、账号移除）时上面那行复位不会执行，scanning 一直是 true：下拉刷新失效、页内一直显示「正在扫描」。
        // 只在异常结束、且没有新的扫描接上时复位。
        val j = job
        j?.invokeOnCompletion { e -> if (e != null) android.os.Handler(android.os.Looper.getMainLooper()).post { if (job === j || job?.isActive != true) { scanning = false; this@MusicLib.quiet = false; phase = "" } } }
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

    private suspend fun walk(root: String, out: MutableList<Pair<FsEntry, String>>, folders: MutableMap<String, List<FsEntry>>, useSnaps: Boolean = false, stats: IntArray = IntArray(2)) {
        // (folder, its modification time as reported by the parent listing; 0 = unknown / root)
        var level = listOf(root to 0L); var depth = 0
        val gate = Semaphore(com.hark.shiguang.cloud.CloudMedia.SCAN_PARALLEL)
        while (level.isNotEmpty() && depth <= 10) {
            val next = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
            coroutineScope {
                level.forEach { (d, dm) ->
                    launch {
                        gate.withPermit {
                            val snap = dirSnaps[d]
                            val reuse = useSnaps && dm > 0 && snap != null && snap.mtime == dm
                            val l = if (reuse) { synchronized(stats) { stats[1]++ }; snap!!.files } else {
                                synchronized(stats) { stats[0]++ }
                                runCatching { fs.list(d) }.getOrElse { if (d == root) throw it else emptyList() }.also { fresh ->
                                    // only leaf folders are reusable: a sub folder's own changes don't touch this folder's time
                                    if (dm > 0 && fresh.none { it.isDir }) dirSnaps[d] = DirSnap(dm, fresh) else dirSnaps.remove(d)
                                }
                            }
                            val files = l.filter { !it.isDir }
                            synchronized(folders) { folders[d] = files }
                            // B36: audio files that live inside a 影视 folder are not music.
                            val audio = files.filter { Music.isAudio(it.name) && !it.name.startsWith("._") && !MediaIsolation.isMoviePath(sourceKey, it.path) }
                            synchronized(out) { audio.forEach { out.add(it to d) } }
                            // B36: never descend into 影视 folders — a music dir inside / next to a movie
                            // library must not pull movie soundtracks into the music library.
                            next.addAll(l.filter { it.isDir && !it.name.startsWith(".") && !it.name.startsWith("@") && !it.name.startsWith("#") && !MediaIsolation.isMoviePath(sourceKey, it.path) }.map { it.path to it.mtime })
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

    private fun idOf(p: String) = HashUtil.md5("$sourceKey|$p").take(16)

    /** Track from the file and folder name alone: "歌手 - 歌名", "01. 歌名", folder = album ("CD1" → parent). */
    private fun guess(e: FsEntry, folder: String): Track {
        // 1.7.32：文件名解析交给 MusicName（音轨号、「歌手 - 歌名」、[FLAC] / 320k 等杂项）
        val p = MusicName.clean(NameParser.stem(e.name), fromFileName = true)
        val no = p.trackNo; val artist = p.artist; val title = p.title
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
        /** 1.7.16：真正从服务器拿到过字节没有。没有（地址查不到、请求全失败）= 这次读失败，不是「文件没标签」。 */
        var gotBytes = false
        val reachable: Boolean get() = url != null
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
            if (b.isNotEmpty()) gotBytes = true
            if (blocks.sumOf { it.second.size } + b.size <= 2 * MusicTagsMax) blocks.add(off to b)
            return b
        }
        var big = false
        fun release() { blocks.clear(); if (big) { big = false; BIG_GATE.release() } }
    }

    private suspend fun readTags(t0: Track, siblings: List<FsEntry>, covers: MutableMap<String, String>): Track {
        var t = t0.copy(scanned = true, tagVer = Music.TAG_VER)
        val src = RemoteSrc(t0)
        val tags = try { withContext(Dispatchers.IO) { MusicTags.read(src, t0.ext).also { tg -> tg.picture = tg.picture?.takeIf { it.size > 200 }?.let { shrinkArt(it) } } } } finally { src.release() }
        // 1.7.16：文件一个字节都没读到（播放地址没查到 / 网络、NAS 暂时不通）→ 这是读失败，不能当「没有标签」记为已读，
        // 否则这首歌以后的扫描都会跳过它，永远只有文件名、没有歌手 / 专辑 / 封面。抛出去，由 scan 保持未读、下次重试。
        if (Music.readFailed(tags.hasAny, t0.size, src.reachable, src.gotBytes)) throw java.io.IOException(if (!src.reachable) "no play url" else "no bytes read")
        if (tags.hasAny) t = t.copy(tagged = true)
        else if (t0.ext == "wav") Diag.log("MUSIC", "wav without usable tags (missing or garbled ??), using the file name")
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
        val f = File(artDir, HashUtil.md5(b).take(20) + ".img")
        if (!f.exists()) runCatching { f.writeBytes(b) }.onFailure { return null }
        pruneCaches()
        return f.absolutePath
    }

    // ---------------------------------------------------------------- lyrics

    /**
     * [t] 的歌词文本。顺序（1.7.32）：内嵌 / 同目录 .lrc（读过后缓存在 <id>.lrc）→ 同目录 .lrc → 联网缓存 <id>.net.lrc → 联网（开关开着时）。
     * 查无：写 <id>.none（内容 "v2"，7 天后再查；旧版写的空 .none 当作过期，按新规则再查一次）。网络出错不写 .none，下次打开再试。
     */
    suspend fun lyrics(t: Track): String? = withContext(Dispatchers.IO) {
        val f = File(lyricsDir, "${t.id}.lrc")
        if (f.exists() && f.length() > 0) { runCatching { f.setLastModified(System.currentTimeMillis()) }; return@withContext f.readText() }
        t.lrcPath?.let { p -> runCatching { fs.bytes(p) }.getOrNull()?.let { MusicTags.decodeText(it) }?.takeIf { it.isNotBlank() }?.let { f.writeText(it); pruneCaches(); return@withContext it } }
        val net = File(lyricsDir, "${t.id}.net.lrc")
        if (net.exists() && net.length() > 0) { runCatching { net.setLastModified(System.currentTimeMillis()) }; return@withContext net.readText() }
        val none = File(lyricsDir, "${t.id}.none")
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 86400_000L && runCatching { none.readText() }.getOrNull() == NONE_V) return@withContext null
        if (!MusicPrefs.online) return@withContext null
        val r = try { MusicOnline.lyrics(t) } catch (e: java.io.IOException) { Diag.log("MUSIC", "online lyrics failed (will retry next time): ${e.message?.take(80)}"); return@withContext null }
            catch (e: Exception) { Diag.e("MUSIC", e); return@withContext null }
        if (r == null || r.text.isBlank()) { runCatching { none.writeText(NONE_V) }; null }
        else { runCatching { net.writeText(r.text) }; none.delete(); pruneCaches(); Diag.log("MUSIC", "online lyrics from ${r.from}, synced=${r.synced}"); r.text }
    }

    /** 把曲库里 [id] 这首换成 [n]（主线程），写盘。 */
    private suspend fun replaceTrack(n: Track) {
        withContext(Dispatchers.Main) { val i = tracks.indexOfFirst { it.id == n.id }; if (i >= 0) { tracks[i] = n; version++ } }
        withContext(Dispatchers.IO) { saveLib() }
    }

    private fun dropOnlineLyrics(id: String) { File(lyricsDir, "$id.net.lrc").delete(); File(lyricsDir, "$id.none").delete() }

    /**
     * 1.7.32 歌曲菜单「重新匹配」：重读这首的标签（拿回内嵌封面 / 歌词），丢掉之前联网拿的封面和歌词，按现在的规则重新联网补全并取歌词。
     * 不看退避。返回新的 Track；读不到文件、网络不通时抛出（界面提示）。
     */
    suspend fun rematch(t: Track): Track = withContext(Dispatchers.IO) {
        dropOnlineLyrics(t.id)
        File(lyricsDir, "${t.id}.lrc").delete()   // 内嵌 / 同目录歌词下面重读时会再写回来
        val siblings = runCatching { fs.list(t.folder) }.getOrDefault(emptyList()).filter { !it.isDir }
        var n = readTags(t.copy(artPath = null, online = false, onVer = 0), siblings, HashMap())
        if (MusicPrefs.online) {
            n = MusicOnline.complete(this@MusicLib, n, HashMap()).copy(online = true, onVer = Music.ONLINE_VER)
            retryBook.ok("on:" + t.id); retryBook.save()
        }
        replaceTrack(n)
        n
    }

    /** 1.7.32 歌曲菜单「手动搜索」：用户选定的结果——封面换成它的，歌词存为联网歌词（内嵌 / 同目录歌词仍优先）。 */
    suspend fun applyPick(t: Track, c: MusicMatch.Cand): Track = withContext(Dispatchers.IO) {
        val (n0, l) = MusicOnline.applyPick(this@MusicLib, t, c)
        val n = n0.copy(online = true, onVer = Music.ONLINE_VER)
        dropOnlineLyrics(t.id)
        if (l != null && l.text.isNotBlank()) File(lyricsDir, "${t.id}.net.lrc").writeText(l.text) else File(lyricsDir, "${t.id}.none").writeText(NONE_V)
        replaceTrack(n)
        n
    }

    companion object {
        /** 第 4 批：封面缓存上限（见 [MusicLib.pruneCaches]）。 */
        const val ART_CAP = 200L * 1024 * 1024
        /** 第 4 批：歌词缓存上限（见 [MusicLib.pruneCaches]）。 */
        const val LYRICS_CAP = 50L * 1024 * 1024
        /** 1.7.32：<id>.none 的内容；旧版写的空文件视为过期（按新规则再查一次歌词）。 */
        const val NONE_V = "v2"
        /** 1.7.33：每次扫描最多查几位歌手的照片（网易云同一来源请求间隔 350ms，60 位约 25 秒）。 */
        const val ARTIST_PHOTO_BATCH = 60
    }
}

/**
 * 1.7.33：整个曲库一起算出来的歌手显示名（[MusicArtist.index]）：「01张杰」…「07张杰」合并成「张杰」，「08素颜」这种拆反了的对调。
 * 按曲目 id 记住结果；某首歌的信息变了（[MusicArtist.Fix.sig] 对不上）或还没算过时，退回只看这一首的规则（[MusicArtist.resolve]）。
 * 打开音乐页（[update] 在后台线程算）、读完曲库、联网补全前都会重算；只改显示。
 */
object MusicNames {
    @Volatile private var fixes: Map<String, MusicArtist.Fix> = emptyMap()

    fun update(all: List<Track>) {
        if (all.isEmpty()) return
        val idx = MusicArtist.index(all.map { it.nameInput })
        fixes = HashMap(fixes).apply { putAll(idx.fixes) }
    }

    fun fix(t: Track): MusicArtist.Fix {
        val i = t.nameInput
        val f = fixes[t.id]
        return if (f != null && f.sig == i.sig) f else MusicArtist.resolve(i)
    }
}

object MusicPrefs {
    /** 优化 9 / 17（用户 2026-11-02）：预加载下一首 + 无缝播放，默认开（设置里可关，网络流量会提前用掉一首）。 */
    var gapless: Boolean
        get() = Store.getStr("music.gapless", "1") == "1"
        set(v) = Store.putStr("music.gapless", if (v) "1" else "0")
    /** 1.7.32：自动规范歌名（去音轨号、「歌手 - 」、[FLAC] 等，只改显示），默认开。 */
    private val cleanState by lazy { mutableStateOf(Store.getStr("music.cleanNames", "1") == "1") }   // Compose 读它，开关一改歌名立刻重画
    var cleanNames: Boolean
        get() = cleanState.value
        set(v) { Store.putStr("music.cleanNames", if (v) "1" else "0"); cleanState.value = v }
    /** 联网补全（网易云音乐、QQ 音乐公开接口 + LRCLIB），设置里可关。 */
    var online: Boolean
        get() = Store.getStr("music.online", "1") == "1"
        set(v) = Store.putStr("music.online", if (v) "1" else "0")
}

object Music {
    /** 标签读取器版本：2 = 1.4.0（WAV 优先 id3 块、?? 乱码当作没有标签）。升高后旧的乱码缓存会重读。 */
    /** 1.7.16：这次读标签算不算「读失败」（要重试），而不是「文件没有标签」。有标签、空文件都不算失败。 */
    fun readFailed(hasTags: Boolean, size: Long, reachable: Boolean, gotBytes: Boolean): Boolean = !hasTags && size > 0 && (!reachable || !gotBytes)
    /**
     * 增量判断：上次读过的这首歌能不能直接沿用（不重读标签）。同一路径、大小相同、修改时间相同（任一方不知道时不比），
     * 已读过且不是要按新版读取器重读的旧缓存。1.7.21 抽出来便于单测。
     */
    fun keepCached(o: Track, size: Long, mtime: Long, rescrape: Boolean): Boolean =
        !rescrape && o.size == size && (mtime == 0L || o.mtime == 0L || o.mtime == mtime) && o.scanned && !o.staleTags

    /**
     * 联网匹配规则版本。2 = 1.7.32 歌名先清洗、加 QQ 音乐、翻唱封面规则；3 = 1.7.33 歌手名也先规范（「01张杰」按「张杰」搜、
     * 「08素颜 / 许嵩」对调后按「素颜 / 许嵩」搜），「未知艺术家」高分匹配时记下联网歌手。
     */
    const val ONLINE_VER = 3
    /**
     * 这首歌要不要联网补全：没查过、且缺封面 / 专辑 / 标签；或者旧规则查过却没拿到封面（升级后按新规则只再查一次）；
     * 或者（1.7.33）旧规则查过、歌手还是「未知」的（只再查一次，onVer 记下后不再重复）。
     */
    fun needsOnline(t: Track): Boolean {
        val missing = !t.tagged || t.artPath == null || t.album.isBlank()
        if (!t.online) return missing
        if (t.onVer >= ONLINE_VER) return false
        return t.artPath == null || (t.onlineArtist.isBlank() && MusicArtist.isUnknown(t.artist) && MusicArtist.isUnknown(t.albumArtist) && t.parsedArtist.isBlank())
    }

    const val TAG_VER = 3   // 3 = 1.7.16：读不到文件时不再记为已读；旧库里「没标签也没封面」的重读一次
    /** Extensions the scanner collects. */
    val AUDIO = setOf("mp3", "flac", "ape", "wav", "m4a", "aac", "alac", "ogg", "oga", "opus", "wma", "dsf", "dff", "aiff", "aif", "aifc", "wv", "m4b")
    /** media3 extractors (with the Jellyfin FFmpeg decoder for ALAC / Opus / Vorbis fallbacks). */
    val EXO = setOf("mp3", "flac", "wav", "m4a", "aac", "alac", "ogg", "oga", "opus", "m4b")
    /** No media3 extractor (APE, ASF/WMA, DSD, WavPack, AIFF in 1.3.1). 1.1.0: libVLC removed — offered 「用其他播放器打开」 instead. */
    val UNSUPPORTED = setOf("ape", "wma", "dsf", "dff", "wv", "aiff", "aif", "aifc")

    fun isAudio(name: String) = NameParser.ext(name) in AUDIO

    private val libs = HashMap<String, MusicLib>()
    fun lib(): MusicLib? = Movies.keyOf()?.let { lib(it) }
    fun lib(key: String): MusicLib = synchronized(libs) { libs.getOrPut(key) { MusicLib(key) } }.also { it.ensure() }
    /** 全面审查 B5：移除来源时丢掉内存里的库（磁盘文件由调用方删）。 */
    fun forget(key: String) { synchronized(libs) { libs.remove(key) } }

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
