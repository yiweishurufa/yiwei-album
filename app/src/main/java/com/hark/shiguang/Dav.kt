package com.hark.shiguang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.BatteryManager
import androidx.compose.runtime.*
import com.hark.shiguang.cloud.*
import com.hark.shiguang.data.Photo
import com.hark.shiguang.ui.CloudPhotos
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Per-photo results of on-device analysis (+ optional AI labels). */
data class Meta(
    var time: String = "", var lat: Double = Double.NaN, var lng: Double = Double.NaN, var city: String = "",
    var md5: String = "", var dhash: Long = 0L, var hasHash: Boolean = false, var done: Boolean = false,
    var aiCat: String = "", var aiTags: List<String> = emptyList(), var aiCaption: String = "", var aiFaces: Int = -1,
    /** Camera "Make Model" from EXIF (1.0.9; photos analysed before that have none). */
    var cam: String = "",
    /** 1.0.10: camera EXIF was read (new analyses, or the one-time backfill) — so an empty [cam] means "no camera tag". */
    var camChecked: Boolean = false,
    /** 1.0.10: video duration in seconds (WebDAV videos, read with MediaMetadataRetriever); 0 = unknown, -1 = tried and failed. */
    var dur: Int = 0,
    /** 1.0.1 Android Motion Photo: 0 = none / unknown, >0 = MP4 length counted from the end of the file (XMP), -1 = motion photo, offset unknown. */
    var mv: Long = 0L,
    /** 1.0.1: the motion-photo XMP check ran for this photo (new analyses, or the name-based backfill). */
    var mvChecked: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("t", time).put("c", city).put("m", md5).put("d", done).put("ac", aiCat).put("ap", aiCaption)
        .put("ag", JSONArray(aiTags)).put("af", aiFaces).apply { if (cam.isNotEmpty()) put("cm", cam); if (camChecked) put("ck", true); if (dur != 0) put("du", dur); if (mv != 0L) put("mv", mv); if (mvChecked) put("mk", true) }.apply { if (!lat.isNaN()) { put("la", lat); put("lo", lng) }; if (hasHash) put("h", dhash) }
    companion object {
        fun of(o: JSONObject) = Meta(o.optString("t"), o.optDouble("la", Double.NaN), o.optDouble("lo", Double.NaN), o.optString("c"), o.optString("m"),
            o.optLong("h", 0L), o.has("h"), o.optBoolean("d"), o.optString("ac"),
            o.optJSONArray("ag")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), o.optString("ap"), o.optInt("af", -1), o.optString("cm"), o.optBoolean("ck"), o.optInt("du", 0), o.optLong("mv", 0L), o.optBoolean("mk"))
    }
}

data class VAlbum(val id: String, val name: String, val cover: String, val items: List<String>, val created: Long)

/** One WebDAV account's library: media index, folders, virtual albums, analysis. */
class DavLib(val accountId: String) {
    val photos = mutableStateListOf<Photo>()
    var folders by mutableStateOf<List<CloudEntry>?>(null)
    var albums by mutableStateOf<List<VAlbum>?>(null)
    var scanning by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var analyzing by mutableStateOf(false)
    var analyzed by mutableIntStateOf(0)
    var metaVersion by mutableIntStateOf(0)
    val meta = java.util.concurrent.ConcurrentHashMap<String, Meta>()
    private val dir: File get() = File(App.ctx.filesDir, "dav/$accountId").apply { mkdirs() }
    val src: WebDavSource? get() = (CloudPhotos.source(accountId) as? WebDavSource)?.also { NetEnv.prime(it.account.url) }
    private var loaded = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun imagesCount() = photos.count { !it.isVideo }

    /** True once every index segment is in [photos] (cold start shows the newest months first). */
    var fullyLoaded by mutableStateOf(false)

    suspend fun ensure() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) { loadMeta() }
        // 1.0.10: the index is stored per month; the newest few months go on screen first, the rest follows.
        val months = withContext(Dispatchers.IO) { IndexStore.months(dir) }
        var cachedCount = 0
        if (months.isNotEmpty()) {
            val head = months.take(RECENT_MONTHS)
            val first = withContext(Dispatchers.IO) { IndexStore.read(dir, head) }
            cachedCount = first.size
            if (first.isNotEmpty()) setPhotos(CloudPhotos.toPhotos(accountId, first))
            val rest = months.drop(RECENT_MONTHS)
            if (rest.isNotEmpty()) {
                val more = withContext(Dispatchers.IO) { IndexStore.read(dir, rest) }
                cachedCount += more.size
                if (more.isNotEmpty() && !scanning) setPhotos(photos.toList() + CloudPhotos.toPhotos(accountId, more))
            }
        }
        fullyLoaded = true
        val last = Store.getStr("dav.lastScan.$accountId").toLongOrNull() ?: 0L
        // 1.0.8: a full re-list only when there is no index yet or it is older than 6 h; pull-to-refresh / 重新扫描 still forces one.
        val stale = cachedCount == 0 || System.currentTimeMillis() - last > 6 * 3600_000L
        coroutineScope {
            launch { refreshFolders() }
            launch { refreshAlbums() }
            if (stale) launch { scan() }
        }
    }

    private fun setPhotos(l: List<Photo>) {
        val fixed = l.map { p ->
            val m = meta[p.cloudPath] ?: return@map p
            var q = p
            if (m.time.isNotEmpty()) q = q.copy(time = m.time)
            if (p.isVideo && m.dur > 0 && p.duration <= 0) q = q.copy(duration = m.dur)
            if (!p.isVideo && m.mv != 0L && !p.isLive) q = q.copy(isLive = true, liveType = 2)
            q
        }
        photos.clear(); photos.addAll(LivePairing.pair(fixed, meta).sortedByDescending { it.time })
    }

    fun retime() { setPhotos(photos.toList()) }

    /** After a delete on the server: drop the files from the index, analysis, faces and virtual albums. */
    suspend fun removeLocal(paths: List<String>) {
        val set = paths.toSet()
        withContext(Dispatchers.Main) { photos.removeAll { it.cloudPath in set }; metaVersion++ }
        set.forEach { meta.remove(it) }
        withContext(Dispatchers.IO) {
            saveMeta()
            // 1.0.10: from the stored index (not [photos], which may still be partial during a cold start)
            writeIndex(readIndex().filter { it.path !in set })
            val fl = Faces.lib(accountId)
            set.forEach { fl.faces.remove(it); fl.scanned.remove(it) }
            fl.save()
        }
        withContext(Dispatchers.Main) { Faces.lib(accountId).cluster(this@DavLib) }
        if (albums.orEmpty().any { a -> a.items.any { it in set } }) runCatching { editAlbums { l -> l.replaceAll { a -> a.copy(items = a.items - set, cover = if (a.cover in set) "" else a.cover) } } }
    }

    /** A file this app just uploaded (e.g. an edited copy): into the index without a full re-scan. */
    suspend fun addLocal(e: CloudEntry) {
        val ps = CloudPhotos.toPhotos(accountId, listOf(e))
        withContext(Dispatchers.Main) { photos.removeAll { it.cloudPath == e.path }; setPhotos(photos.toList() + ps) }
        withContext(Dispatchers.IO) { writeIndex(readIndex().filter { it.path != e.path } + e) }
    }

    /** 1.0.1 scan progress (folders listed / discovered, media found) for the notification and the header. */
    var scanListed by mutableIntStateOf(0)
    var scanKnown by mutableIntStateOf(0)
    var scanFound by mutableIntStateOf(0)

    /**
     * 1.0.1: incremental by default — unchanged leaf folders (same getetag/getlastmodified as last time) are taken from the
     * stored index without a PROPFIND (see [scanIncremental]). [full] ignores the folder tags; it is also forced when there
     * is no folder cache yet or the last full listing is older than 7 days. Only listing happens here: EXIF/AI/faces run
     * afterwards from their own queues (Analyzer / AiRunner / Faces), so the timeline is complete as soon as listing ends.
     */
    suspend fun scan(full: Boolean = false) {
        if (scanning) return
        val s = src ?: run { error = "WebDAV 账户不存在"; return }
        scanning = true; error = null
        scanListed = 0; scanKnown = 1; scanFound = 0
        runCatching { ScanService.ensure(App.ctx) }
        val all = ArrayList<CloudEntry>()
        var lastShow = 0L
        val folderFile = File(dir, "folders.json")
        val lastFull = Store.getStr("dav.lastFull.$accountId").toLongOrNull() ?: 0L
        val doFull = full || !folderFile.exists() || System.currentTimeMillis() - lastFull > 7 * 24 * 3600_000L
        val (previous, old) = withContext(Dispatchers.IO) { (if (doFull) emptyList() else readIndex()) to (if (doFull) DavFolderCache() else DavFolderCache.load(folderFile)) }
        val prog = DavScanProgress()
        val ticker = uiScope.launch { while (isActive) { scanListed = prog.listed.get(); scanKnown = prog.known.get(); scanFound = prog.found.get(); delay(700) } }
        var cache: DavFolderCache? = null
        val t0 = System.currentTimeMillis()
        runCatching {
            cache = withContext(Dispatchers.IO) {
                s.scanIncremental("/", MAX_ITEMS, previous, old, doFull, prog) { b ->
                    val snap: List<CloudEntry>? = synchronized(all) {
                        all.addAll(b)
                        val now = System.currentTimeMillis()
                        // show progress: at most every 1.5 s (only if nothing cached yet or it grew past the cache)
                        if (now - lastShow > 1500 && all.size >= photos.size) { lastShow = now; ArrayList(all) } else null
                    }
                    if (snap != null) uiScope.launch { val ps = CloudPhotos.toPhotos(accountId, snap); if (scanning) setPhotos(ps) }
                }
            }
        }.onFailure { error = it.message }
        ticker.cancel()
        scanListed = prog.listed.get(); scanKnown = prog.known.get(); scanFound = prog.found.get()
        Diag.log("DAV", "scan ${if (doFull) "full" else "incremental"}: ${prog.listed.get()} folders (${prog.skipped.get()} unchanged, skipped), ${all.size} media, ${System.currentTimeMillis() - t0} ms")
        if (all.isNotEmpty() || error == null) {
            withContext(Dispatchers.IO) {
                writeIndex(all)
                cache?.let { c -> runCatching { folderFile.writeText(c.json()) } }
            }
            if (error == null) {
                Store.putStr("dav.lastScan.$accountId", System.currentTimeMillis().toString())
                if (doFull) Store.putStr("dav.lastFull.$accountId", System.currentTimeMillis().toString())
            }
            setPhotos(CloudPhotos.toPhotos(accountId, all))
        }
        scanning = false
    }

    suspend fun refreshFolders() { runCatching { src!!.list("/").filter { it.isDir && !it.name.startsWith(".") } }.onSuccess { folders = it }.onFailure { if (folders == null) folders = emptyList() } }

    // ---------------------------------------------------------------- index cache (1.0.10: per-month segments, see IndexStore)
    private fun readIndex(): List<CloudEntry> = IndexStore.read(dir, IndexStore.months(dir))

    private fun writeIndex(l: List<CloudEntry>) = runCatching { IndexStore.write(dir, l) }

    private fun loadMeta() = runCatching {
        val o = JSONObject(File(dir, "meta.json").readText())
        o.keys().forEach { k -> meta[k] = Meta.of(o.getJSONObject(k)) }
    }

    fun saveMeta() = runCatching {
        val o = JSONObject(); meta.forEach { (k, v) -> o.put(k, v.json()) }
        File(dir, "meta.json").writeText(o.toString())
    }

    fun clearCache() { File(dir, "folders.json").delete(); IndexStore.clear(dir); fullyLoaded = false; File(dir, "meta.json").delete(); meta.clear(); photos.clear(); loaded = false }

    // ---------------------------------------------------------------- virtual albums (manifest in WebDAV root)
    suspend fun refreshAlbums() {
        runCatching { readAlbums() }.onSuccess { albums = it }.onFailure { if (albums == null) albums = emptyList(); error = it.message }
    }

    private suspend fun readAlbums(): List<VAlbum> {
        val t = src?.readText(MANIFEST) ?: return emptyList()
        val a = runCatching { JSONObject(t).optJSONArray("albums") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            VAlbum(o.optString("id"), o.optString("name"), o.optString("cover"),
                o.optJSONArray("items")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList(), o.optLong("created"))
        } }
    }

    /** Read-modify-write so another phone's edits are not lost. */
    suspend fun editAlbums(f: (MutableList<VAlbum>) -> Unit) {
        val s = src ?: error("WebDAV 账户不存在")
        val l = readAlbums().toMutableList(); f(l)
        val arr = JSONArray()
        l.forEach { a -> arr.put(JSONObject().put("id", a.id).put("name", a.name).put("cover", a.cover).put("created", a.created).put("items", JSONArray(a.items))) }
        s.writeText(MANIFEST, JSONObject().put("app", "一维相册").put("version", 1).put("albums", arr).toString(2))
        albums = l
    }

    suspend fun createAlbum(name: String, items: List<String> = emptyList()): VAlbum {
        val a = VAlbum(java.util.UUID.randomUUID().toString().take(12), name, items.firstOrNull() ?: "", items, System.currentTimeMillis())
        editAlbums { it.add(a) }; return a
    }
    suspend fun renameAlbum(id: String, name: String) = editAlbums { l -> l.replaceAll { if (it.id == id) it.copy(name = name) else it } }
    suspend fun deleteAlbum(id: String) = editAlbums { l -> l.removeAll { it.id == id } }
    suspend fun setCover(id: String, path: String) = editAlbums { l -> l.replaceAll { if (it.id == id) it.copy(cover = path) else it } }
    suspend fun addTo(id: String, paths: List<String>) = editAlbums { l -> l.replaceAll { if (it.id == id) it.copy(items = (it.items + paths).distinct(), cover = it.cover.ifEmpty { paths.firstOrNull() ?: "" }) else it } }
    suspend fun removeFrom(id: String, paths: List<String>) = editAlbums { l -> l.replaceAll { a -> if (a.id == id) a.copy(items = a.items - paths.toSet(), cover = if (a.cover in paths) "" else a.cover) else a } }

    fun photosOf(a: VAlbum): List<Photo> { val set = a.items.toSet(); return photos.filter { it.cloudPath in set } }
    fun photoAt(path: String): Photo? = photos.firstOrNull { it.cloudPath == path }

    // ---------------------------------------------------------------- groups for 发现
    fun places(): List<Pair<String, List<Photo>>> = Hidden.visible(photos).filter { meta[it.cloudPath]?.city?.isNotEmpty() == true }
        .groupBy { meta[it.cloudPath]!!.city }.toList().sortedByDescending { it.second.size }

    fun duplicates(): List<List<Photo>> = photos.filter { !it.isVideo && meta[it.cloudPath]?.md5?.isNotEmpty() == true }
        .groupBy { meta[it.cloudPath]!!.md5 + ":" + it.size }.values.filter { it.size > 1 }.sortedByDescending { it.size }

    fun similar(maxDist: Int = 6): List<List<Photo>> {
        val items = photos.filter { !it.isVideo && meta[it.cloudPath]?.hasHash == true }.sortedBy { it.time }
        val h = items.map { meta[it.cloudPath]!!.dhash }
        val md = items.map { meta[it.cloudPath]!!.md5 + it.size }
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in items.indices) for (j in i + 1 until minOf(items.size, i + 400)) {
            if (md[i] != md[j] && java.lang.Long.bitCount(h[i] xor h[j]) <= maxDist) parent[find(i)] = find(j)
        }
        return items.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { items[it] } }.sortedByDescending { it.size }
    }

    fun aiCategories(): List<Pair<String, List<Photo>>> = Hidden.visible(photos).filter { meta[it.cloudPath]?.aiCat?.isNotEmpty() == true }
        .groupBy { meta[it.cloudPath]!!.aiCat }.toList().sortedByDescending { it.second.size }

    /**
     * [concepts]: every concept must be matched (AND), any synonym inside a concept counts (OR).
     * Returns (photos ranked by score, exact). When nothing matches every concept, returns the best partial matches with exact=false.
     */
    fun aiSearch(concepts: List<List<String>>): Pair<List<Photo>, Boolean> {
        val cs = concepts.map { g -> g.map { it.trim() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        if (cs.isEmpty()) return emptyList<Photo>() to true
        fun termScore(m: Meta, p: Photo, t: String): Int = when {
            m.aiTags.any { it.equals(t, true) } -> 4
            m.aiCat == t -> 3
            m.city.isNotEmpty() && (m.city.contains(t) || t.contains(m.city)) -> 4
            m.aiTags.any { it.contains(t, true) } -> 3
            m.aiCaption.contains(t, true) -> 2
            p.fileName.contains(t, true) -> 1
            else -> 0
        }
        val scored = photos.mapNotNull { p ->
            val m = meta[p.cloudPath] ?: return@mapNotNull null
            val per = cs.map { g -> g.withIndex().maxOf { (i, t) -> termScore(m, p, t) * (if (i == 0) 3 else 2) } }
            val hit = per.count { it > 0 }
            if (hit == 0) null else Triple(p, hit, per.sum())
        }
        val exact = scored.filter { it.second == cs.size }
        val pick = if (exact.isNotEmpty()) exact else scored.filter { it.second >= maxOf(1, cs.size - 1) }
        return pick.sortedWith(compareByDescending<Triple<Photo, Int, Int>> { it.second }.thenByDescending { it.third }).map { it.first } to exact.isNotEmpty()
    }

    fun aiDone(): Int = photos.count { !it.isVideo && meta[it.cloudPath]?.aiCat?.isNotEmpty() == true }

    companion object { const val MANIFEST = "/.yiwei-albums.json"; const val RECENT_MONTHS = 3; const val MAX_ITEMS = 60000 }
}

/**
 * 1.0.10 large-library index: `index/<yyyy-MM>.json` per month of the file's modified time + `index/months.json`
 * (newest first). Only changed segments are rewritten. The pre-1.0.10 single `index.json` is migrated on first read.
 */
object IndexStore {
    private const val NONE = "0000-00"
    private val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
    private fun seg(dir: File) = File(dir, "index").apply { mkdirs() }
    private fun monthOf(ms: Long) = if (ms <= 0) NONE else synchronized(fmt) { fmt.format(java.util.Date(ms)) }

    @Synchronized fun months(dir: File): List<String> {
        migrate(dir)
        return runCatching { JSONArray(File(seg(dir), "months.json").readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrElse { seg(dir).listFiles()?.map { it.name }?.filter { it.endsWith(".json") && it != "months.json" }?.map { it.removeSuffix(".json") }?.sortedDescending() ?: emptyList() }
    }

    @Synchronized fun read(dir: File, months: List<String>): List<CloudEntry> {
        val out = ArrayList<CloudEntry>()
        months.forEach { m -> out.addAll(parse(File(seg(dir), "$m.json"))) }
        return out
    }

    @Synchronized fun write(dir: File, l: List<CloudEntry>) {
        val d = seg(dir)
        val groups = l.groupBy { monthOf(it.modified) }
        groups.forEach { (m, es) ->
            val text = encode(es)
            val f = File(d, "$m.json")
            if (!f.exists() || f.length() != text.length.toLong() || f.readText() != text) { val t = File(d, "$m.json.tmp"); t.writeText(text); t.renameTo(f) }
        }
        d.listFiles()?.forEach { f -> val m = f.name.removeSuffix(".json"); if (f.name.endsWith(".json") && f.name != "months.json" && m !in groups) f.delete() }
        File(d, "months.json").writeText(JSONArray(groups.keys.sortedDescending()).toString())
    }

    @Synchronized fun clear(dir: File) { seg(dir).deleteRecursively(); File(dir, "index.json").delete() }

    private fun migrate(dir: File) {
        val old = File(dir, "index.json")
        if (!old.exists()) return
        val l = parse(old)
        if (l.isNotEmpty()) write(dir, l)
        old.delete()
    }

    private fun parse(f: File): List<CloudEntry> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            CloudEntry(o.getString("n"), o.getString("p"), false, o.optLong("s"), o.optLong("m"), null, !o.optBoolean("v"), o.optBoolean("v"))
        } }
    }.getOrDefault(emptyList())

    private fun encode(l: List<CloudEntry>): String {
        val a = JSONArray(); l.sortedBy { it.path }.forEach { a.put(JSONObject().put("n", it.name).put("p", it.path).put("s", it.size).put("m", it.modified).put("v", it.isVideo)) }
        return a.toString()
    }
}

object Dav {
    var currentId by mutableStateOf(Store.davId)
    private val libs = HashMap<String, DavLib>()
    val accounts: List<CloudAccount> get() = CloudAccounts.all.filter { it.kind == CloudKind.WEBDAV }
    val current: CloudAccount? get() = accounts.firstOrNull { it.id == currentId } ?: accounts.firstOrNull()
    fun lib(): DavLib? = current?.let { a -> libs.getOrPut(a.id) { DavLib(a.id) } }
    fun lib(id: String): DavLib = libs.getOrPut(id) { DavLib(id) }
    fun select(id: String) { currentId = id; Store.davId = id }
    fun forget(id: String) { libs.remove(id); CloudPhotos.forget(id) }
}

/** On-device analysis: EXIF GPS/time from the first 128 KB (HTTP Range), md5 of that head + size for duplicates, dHash from the EXIF thumbnail. */
object Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val geoCache = HashMap<String, String>()

    fun wifiAndCharging(c: Context): Boolean = Net.isWifi(c) && Net.isCharging(c)
    /** Local analysis + faces: Wi-Fi and charging unless the user allowed otherwise. */
    fun canScan(c: Context): Boolean = (ScanPolicy.allowCellular || Net.isWifi(c)) && (ScanPolicy.allowNoCharge || Net.isCharging(c))
    val running: Boolean get() = job?.isActive == true

    /**
     * [manual] runs regardless of Wi-Fi/charging. Three phases, each only for what is still missing:
     * 1) photo analysis (EXIF/md5/dHash), 2) 1.0.10 one-time camera backfill for photos analysed before 1.0.9,
     * 3) 1.0.10 WebDAV video durations (MediaMetadataRetriever on the stream, 2 at a time).
     */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (job?.isActive == true) return
        if (!manual && !canScan(c)) return
        val needPhotos = lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.done != true }
        val needCam = !camFillDone(lib) && lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }
        val needDur = lib.photos.any { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 }
        val needMv = lib.photos.any { motionTodo(lib, it) }
        if (!needPhotos && !needCam && !needDur && !needMv) return
        ScanService.ensure(c)
        job = scope.launch {
            lib.analyzing = true
            withContext(Dispatchers.Main) { lib.analyzed = lib.meta.values.count { it.done } }
            try {
                val s = lib.src ?: return@launch
                val ok = { manual || canScan(c) }
                if (needPhotos) {
                    val todo = lib.photos.filter { !it.isVideo && lib.meta[it.cloudPath]?.done != true }
                    val gate = kotlinx.coroutines.sync.Semaphore(if (NetEnv.anySlow) 8 else 4)
                    for (chunk in todo.chunked(24)) {
                        if (!isActive || !ok()) break
                        chunk.map { p -> async { gate.acquire(); try { analyze(c, s, p, lib) } finally { gate.release() } } }.awaitAll()
                        lib.analyzed = lib.meta.values.count { it.done }
                        lib.metaVersion++
                        lib.saveMeta()
                    }
                    withContext(Dispatchers.Main) { lib.retime() }
                }
                if (needCam && isActive && ok()) camBackfill(s, lib, ok)
                if (needMv && isActive && ok()) motionBackfill(s, lib, ok)
                if (needDur && isActive && ok()) videoDurations(lib, ok)
            } finally { lib.analyzing = false }
        }
    }

    private fun camKey(lib: DavLib) = "dav.camfill.${lib.accountId}"
    private fun camFillDone(lib: DavLib) = Store.getStr(camKey(lib)) == "1"

    /** 1.0.10: Range bytes=0-131071 + ExifInterface Make/Model only; no AI / faces / hashes are redone. Done-flag persisted per account. */
    private suspend fun camBackfill(s: WebDavSource, lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        var stopped = false
        for (chunk in todo.chunked(30)) {
            if (!isActive || !ok()) { stopped = true; break }
            chunk.map { p -> async { gate.acquire(); try {
                val m = lib.meta[p.cloudPath] ?: return@async
                val head = s.headBytes(p.cloudPath) ?: return@async // network error: retry on a later run
                runCatching { m.cam = camOf(ExifInterface(ByteArrayInputStream(head))) }
                m.camChecked = true
            } finally { gate.release() } } }.awaitAll()
            lib.metaVersion++
            lib.saveMeta()
        }
        if (!stopped && lib.photos.none { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }) Store.putStr(camKey(lib), "1")
    }

    /**
     * 1.0.1: photos analysed before 1.0.1 never had their XMP checked for Motion Photo. Re-reading 128 KB of every photo
     * would be expensive, so only names that Android cameras give motion photos (MVIMG_*, *.MP.jpg, *_MP.jpg, PXL_*) are re-checked.
     */
    private fun motionTodo(lib: DavLib, p: Photo): Boolean = !p.isVideo && LivePairing.motionNameHint(p.fileName) &&
        lib.meta[p.cloudPath]?.let { it.done && !it.mvChecked } == true

    private suspend fun motionBackfill(s: WebDavSource, lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { motionTodo(lib, it) }
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        for (chunk in todo.chunked(30)) {
            if (!isActive || !ok()) break
            chunk.map { p -> async { gate.acquire(); try {
                val m = lib.meta[p.cloudPath] ?: return@async
                val head = s.headBytes(p.cloudPath) ?: return@async
                m.mv = LivePairing.motionOffset(head); m.mvChecked = true
            } finally { gate.release() } } }.awaitAll()
            lib.metaVersion++
            lib.saveMeta()
        }
        withContext(Dispatchers.Main) { lib.retime() }
    }

    private fun camOf(ex: ExifInterface): String {
        val make = ex.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty(); val model = ex.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        return if (model.startsWith(make, true)) model else "$make $model".trim()
    }

    /** Cached pool: a retriever stuck on a dead host keeps its own thread and never blocks the next file. */
    private val durPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "yw-dur").apply { isDaemon = true } }

    /** 1.0.10: duration of WebDAV videos for the advanced-search duration filter. 2 at a time, 25 s cap per file, failures marked -1 and skipped. */
    private suspend fun videoDurations(lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 && it.video != null }
        var n = 0
        for (chunk in todo.chunked(2)) {
            if (!isActive || !ok()) break
            chunk.map { p -> async(Dispatchers.IO) {
                val d = durationOf(p.video!!)
                val m = lib.meta[p.cloudPath] ?: Meta().also { lib.meta[p.cloudPath] = it }
                m.dur = if (d > 0) d else -1
            } }.awaitAll()
            if (++n % 10 == 0) { lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ } }
        }
        lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ }
    }

    /** Seconds, or 0 on failure / timeout. setDataSource can block on a slow host, so it runs on its own thread with a hard timeout. */
    fun durationOf(url: String): Int {
        val f = durPool.submit<Int> {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                val real = DavThumb.finalUrl(url)
                val h = if (real == url) (ImageAuth.headersFor(url) ?: emptyMap()) else emptyMap()
                mmr.setDataSource(real, h)
                ((mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
            } finally { runCatching { mmr.release() } }
        }
        return try { f.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { f.cancel(true); 0 }
    }

    fun stop() { job?.cancel() }

    private fun analyze(c: Context, s: WebDavSource, p: Photo, lib: DavLib) {
        val m = lib.meta[p.cloudPath] ?: Meta()
        val head = s.headBytes(p.cloudPath) ?: return
        m.md5 = MessageDigest.getInstance("MD5").digest(head).joinToString("") { "%02x".format(it) }.take(16)
        if (LivePairing.motionCandidateExt(p.fileName)) { m.mv = LivePairing.motionOffset(head); m.mvChecked = true }
        runCatching {
            val ex = ExifInterface(ByteArrayInputStream(head))
            val ll = FloatArray(2)
            if (ex.getLatLong(ll)) { m.lat = ll[0].toDouble(); m.lng = ll[1].toDouble(); m.city = city(c, m.lat, m.lng) }
            (ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME))?.takeIf { it.length >= 19 && !it.startsWith("0000") }?.let { m.time = it.take(19) }
            m.cam = camOf(ex); m.camChecked = true
            val tb = ex.thumbnailBytes
            val bmp = if (tb != null) BitmapFactory.decodeByteArray(tb, 0, tb.size) else BitmapFactory.decodeByteArray(head, 0, head.size)
            if (bmp != null) { m.dhash = dHash(bmp); m.hasHash = true }
        }
        m.done = true
        lib.meta[p.cloudPath] = m
    }

    fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            fun lum(px: Int) = ((px shr 16 and 0xff) * 299 + (px shr 8 and 0xff) * 587 + (px and 0xff) * 114)
            if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    private fun city(c: Context, lat: Double, lng: Double): String {
        val k = "%.2f,%.2f".format(lat, lng)
        synchronized(geoCache) { geoCache[k]?.let { return it } }
        val name = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c, java.util.Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                (a.locality ?: a.subAdminArea ?: a.adminArea ?: a.countryName)?.removeSuffix("市")
            }
        }.getOrNull() ?: "%.1f°, %.1f°".format(lat, lng)
        synchronized(geoCache) { geoCache[k] = name }
        return name
    }
}

/**
 * Network + charging rules for everything that reads the library in the background: local analysis, faces,
 * AI labelling and phone backup (1.0.9: AI's own 「仅 Wi-Fi 且充电」 and backup's 「仅 Wi-Fi」 were merged into these two).
 */
object ScanPolicy {
    /** The user pressed 「暂停」 on the AI tab; in memory only, so the next app start resumes normally. */
    var paused by mutableStateOf(false)
    var allowCellular: Boolean get() = Store.getStr("scan.cell") == "1"; set(v) = Store.putStr("scan.cell", if (v) "1" else "")
    var allowNoCharge: Boolean get() = Store.getStr("scan.nocharge") == "1"; set(v) = Store.putStr("scan.nocharge", if (v) "1" else "")
    /**
     * 1.0.9 one-time migration of the two removed switches. Only when the user never touched the scan switches:
     * an explicit 「AI 不限 Wi-Fi/充电」(ai.wc=0) allowed both; backup on cellular (backupWifi=false with backup on) allowed cellular.
     */
    fun migrate() {
        if (Store.getStr("policy.v2") == "1") return
        val untouched = Store.getStr("scan.cell", "?") == "?" && Store.getStr("scan.nocharge", "?") == "?"
        if (untouched) {
            if (Store.getStr("ai.wc", "1") == "0") { allowCellular = true; allowNoCharge = true }
            if (Store.backupOn && !Store.backupWifiOnly) allowCellular = true
        }
        Store.putStr("policy.v2", "1")
    }
    fun waitingText(): String = when {
        !allowCellular && !allowNoCharge -> "连上 Wi-Fi 并充电时自动继续"
        !allowCellular -> "连上 Wi-Fi 时自动继续"
        !allowNoCharge -> "充电时自动继续"
        else -> "会在后台继续"
    }
}

object Net {
    fun isWifi(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    fun online(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun isCharging(c: Context): Boolean = c.getSystemService(BatteryManager::class.java)?.isCharging ?: false
}
