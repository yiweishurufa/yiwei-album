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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Per-photo results of on-device analysis (+ optional AI labels). */
data class Meta(
    var time: String = "", var lat: Double = Double.NaN, var lng: Double = Double.NaN, var city: String = "",
    /** B2: MD5 of the first 128 KB (head) only — NOT a full-file hash. Grouping on it is a head-only match. */
    var headMd5: String = "", var dhash: Long = 0L, var hasHash: Boolean = false, var done: Boolean = false,
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
    /** B14: head md5 was computed (dedupe key is usable even when EXIF parsing failed). */
    var hashDone: Boolean = false,
    /** B14: the last analysis failed (EXIF parse threw) — not done, retried on the next pass. */
    var failed: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("t", time).put("c", city).put("m", headMd5).put("d", done).put("ac", aiCat).put("ap", aiCaption)
        .put("ag", JSONArray(aiTags)).put("af", aiFaces).apply { if (cam.isNotEmpty()) put("cm", cam); if (camChecked) put("ck", true); if (dur != 0) put("du", dur); if (mv != 0L) put("mv", mv); if (mvChecked) put("mk", true); if (hashDone) put("hd", true); if (failed) put("fl", true) }.apply { if (!lat.isNaN()) { put("la", lat); put("lo", lng) }; if (hasHash) put("h", dhash) }
    companion object {
        fun of(o: JSONObject) = Meta(o.optString("t"), o.optDouble("la", Double.NaN), o.optDouble("lo", Double.NaN), o.optString("c"), o.optString("m"),
            o.optLong("h", 0L), o.has("h"), o.optBoolean("d"), o.optString("ac"),
            o.optJSONArray("ag")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), o.optString("ap"), o.optInt("af", -1), o.optString("cm"), o.optBoolean("ck"), o.optInt("du", 0), o.optLong("mv", 0L), o.optBoolean("mk"), o.optBoolean("hd"), o.optBoolean("fl"))
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
    /** B17: load state machine — a failed ensure() lands in FAILED (retryable), never in a stuck "loaded". */
    private enum class LoadState { NOT_LOADED, LOADING, LOADED, FAILED }
    @Volatile private var loadState = LoadState.NOT_LOADED
    private val ensureMutex = Mutex()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /**
     * H7: loading and scanning run here, not in the caller's (page) scope — switching tabs used to cancel the
     * first full scan, record it as FAILED「…was cancelled」and start over next time. Callers only wait for it.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ensureJob: Deferred<Unit>? = null
    private var scanJob: Job? = null

    /** B18: cancels this lib's UI scope (progress ticker etc.) and its load / scan work. Called from [Dav.forget]. */
    fun close() { uiScope.cancel(); workScope.cancel() }

    fun imagesCount() = photos.count { !it.isVideo }

    /** True once every index segment is in [photos] (cold start shows the newest months first). */
    var fullyLoaded by mutableStateOf(false)

    /** Loads the index (and runs the first scan when there is none). Leaving the page does not stop it (H7). */
    suspend fun ensure() {
        if (loadState == LoadState.LOADED) return
        val j = synchronized(this) { ensureJob?.takeIf { it.isActive } ?: workScope.async { ensureWork() }.also { ensureJob = it } }
        j.await()
    }

    private suspend fun ensureWork() {
        ensureMutex.withLock {
            if (loadState == LoadState.LOADED) return
            loadState = LoadState.LOADING
            try {
                ensureLocked()
                loadState = LoadState.LOADED
            } catch (e: CancellationException) {
                loadState = LoadState.NOT_LOADED   // H7: cancelled is not failed — retried silently next time
                throw e
            } catch (e: Exception) {
                loadState = LoadState.FAILED
                error = e.message
                Diag.log("DAV", "ensure failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureLocked() {
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
        // 首次扫描后不再自动扫：只有缓存为空（第一次）才自动扫；之后只手动触发（下拉刷新 / 重新扫描）。
        val needFirstScan = cachedCount == 0
        coroutineScope {
            launch { refreshFolders() }
            launch { refreshAlbums() }
            if (needFirstScan) launch { scan() }
        }
    }

    /** Bumped by every [setPhotos]; a slower, older computation never overwrites a newer list. */
    private val photosGen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * H2: meta fix-up + 实况配对 + 按时间排序 (up to 60 000 items) run on Dispatchers.Default;
     * the main thread only swaps the finished list in.
     */
    private suspend fun setPhotos(l: List<Photo>) {
        val my = photosGen.incrementAndGet()
        val sorted = withContext(Dispatchers.Default) {
            val fixed = l.map { p ->
                val m = meta[p.cloudPath] ?: return@map p
                var q = p
                if (m.time.isNotEmpty()) q = q.copy(time = m.time)
                if (p.isVideo && m.dur > 0 && p.duration <= 0) q = q.copy(duration = m.dur)
                if (!p.isVideo && m.mv != 0L && !p.isLive) q = q.copy(isLive = true, liveType = 2)
                q
            }
            LivePairing.pair(fixed, meta).sortedByDescending { it.time }
        }
        withContext(Dispatchers.Main) {
            if (my != photosGen.get()) return@withContext
            photos.clear(); photos.addAll(sorted)
        }
    }

    private var retimeJob: Job? = null
    /** H2: called often (duration backfill every 10 videos, analyzer) — coalesced to one re-sort per ~1.5 s. Main thread. */
    fun retime() {
        if (retimeJob?.isActive == true) return
        retimeJob = uiScope.launch { delay(1500); setPhotos(photos.toList()) }
    }

    /** After a delete on the server: drop the files from the index, analysis, faces and virtual albums. */
    suspend fun removeLocal(paths: List<String>) {
        val set = paths.toSet()
        // B20: the removed photos' thumbnails must go too, or the grid keeps showing deleted photos.
        DavThumb.invalidateUrls(photos.filter { it.cloudPath in set }.flatMap { listOf(it.thumbS, it.thumbM, it.original) })
        withContext(Dispatchers.Main) { photos.removeAll { it.cloudPath in set }; metaVersion++ }
        set.forEach { meta.remove(it) }
        withContext(Dispatchers.IO) {
            saveMeta()
            // 1.0.10: from the stored index (not [photos], which may still be partial during a cold start)
            writeIndex(readIndex().filter { it.path !in set })
            val fl = Faces.lib(accountId)
            set.forEach { fl.faces.remove(it); fl.scanned.remove(it); fl.failed.remove(it) }
            fl.save()
        }
        withContext(Dispatchers.Main) { Faces.lib(accountId).cluster(this@DavLib) }
        if (albums.orEmpty().any { a -> a.items.any { it in set } }) runCatching { editAlbums { l -> l.replaceAll { a -> a.copy(items = a.items - set, cover = if (a.cover in set) "" else a.cover) } } }
    }

    /** A file this app just uploaded (e.g. an edited copy): into the index without a full re-scan. */
    suspend fun addLocal(e: CloudEntry) {
        // B20: a replaced file (e.g. an edited copy) must not keep the old thumbnail.
        DavThumb.invalidateUrls(photos.filter { it.cloudPath == e.path }.flatMap { listOf(it.thumbS, it.thumbM, it.original) })
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
        val j = synchronized(this) {
            scanJob?.takeIf { it.isActive } ?: workScope.launch {
                try { scanWork(full) } finally { if (scanning) scanning = false }   // H7: always reset, even when cancelled
            }.also { scanJob = it }
        }
        j.join()
    }

    private suspend fun scanWork(full: Boolean) {
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
        // B13: the old index is always loaded — even for a full scan — so a branch whose listing fails can
        // keep its old media instead of dropping out (scanIncremental only reuses it on failure / skip).
        val (previous, old) = withContext(Dispatchers.IO) { readIndex() to (if (doFull) DavFolderCache() else DavFolderCache.load(folderFile)) }
        val prog = DavScanProgress()
        val ticker = uiScope.launch { while (isActive) { scanListed = prog.listed.get(); scanKnown = prog.known.get(); scanFound = prog.found.get(); delay(700) } }
        var cache: DavFolderCache? = null
        var complete = false
        val t0 = System.currentTimeMillis()
        val selected = getSelectedDirs()
        val roots = if (selected.isEmpty()) listOf("/") else topSelectedDirs(selected)
        val shownCount = photos.size   // read once on the calling thread; onBatch runs on IO
        val onBatch: (List<CloudEntry>) -> Unit = { b ->
            val snap: List<CloudEntry>? = synchronized(all) {
                all.addAll(b)
                val now = System.currentTimeMillis()
                // H2: show progress at most every 5 s (only if nothing cached yet or it grew past the cache);
                // the conversion and the sort run off the main thread
                if (now - lastShow > SCAN_SHOW_MS && all.size >= shownCount) { lastShow = now; ArrayList(all) } else null
            }
            if (snap != null) uiScope.launch { val ps = withContext(Dispatchers.Default) { CloudPhotos.toPhotos(accountId, snap) }; if (scanning) setPhotos(ps) }
        }
        runCatching {
            cache = withContext(Dispatchers.IO) {
                val merged = DavFolderCache()
                var allComplete = true
                for (root in roots) {
                    runCatching {
                        val res = s.scanIncremental(root, MAX_ITEMS, previous, old, doFull, prog, onBatch)
                        merged.dirs.putAll(res.cache.dirs)
                        if (!res.complete) allComplete = false
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        // 单个选中根目录列表失败：保留该目录的旧索引，避免该目录照片突然消失；多根时继续扫其余目录。
                        allComplete = false
                        val fallback = previous.filter { it.path == root || (root != "/" && it.path.startsWith(root + "/")) }
                        if (fallback.isNotEmpty()) onBatch(fallback)
                        if (roots.size == 1) throw e else Diag.log("DAV", "scan root $root failed: ${e.message}")
                    }
                }
                complete = allComplete
                merged
            }
        }.onFailure { if (it is CancellationException) { ticker.cancel(); throw it }; error = it.message }
        ticker.cancel()
        scanListed = prog.listed.get(); scanKnown = prog.known.get(); scanFound = prog.found.get()
        Diag.log("DAV", "scan ${if (doFull) "full" else "incremental"} roots=$roots: ${prog.listed.get()} folders (${prog.skipped.get()} unchanged, skipped), ${all.size} media, complete=$complete, ${System.currentTimeMillis() - t0} ms")
        // B13: branches whose listing failed keep their old media + old folder tags — the new index/cache
        // never silently drops them.
        val unknown = cache?.unknown.orEmpty()
        if (unknown.isNotEmpty()) {
            Diag.log("DAV", "scan: ${unknown.size} branches failed, keeping old media for them: ${unknown.sorted().take(5).joinToString()}")
            val have = all.map { it.path }.toSet()
            val keep = previous.filter { e -> e.path !in have && unknown.any { u -> e.path.startsWith(u.trimEnd('/') + "/") || CloudPath.parent(e.path) == u } }
            if (keep.isNotEmpty()) synchronized(all) { all.addAll(keep) }
            cache?.let { c -> unknown.forEach { u -> old.dirs[u]?.let { c.dirs[u] = it } } }
        }
        // B1: only a complete, error-free scan may overwrite the index / folder cache / timestamps. A truncated
        // scan (caps hit) or a failed scan keeps the previous index; the next scan re-lists from it. A failed
        // index write also blocks the timestamp update and is reported via [error].
        if (complete && error == null) {
            val written = withContext(Dispatchers.IO) {
                val ok = writeIndex(all)
                if (ok) cache?.let { c -> runCatching { folderFile.writeText(c.json()) } }
                ok
            }
            if (written) {
                Store.putStr("dav.lastScan.$accountId", System.currentTimeMillis().toString())
                if (doFull) Store.putStr("dav.lastFull.$accountId", System.currentTimeMillis().toString())
                setPhotos(CloudPhotos.toPhotos(accountId, all))
            } else {
                error = "索引保存失败，时间戳未更新"
                Diag.log("DAV", "scan: writeIndex failed, timestamps NOT updated")
            }
        } else if (!complete && error == null) {
            error = "扫描未完成（超出上限被截断），索引未覆盖，下次扫描会继续"
            Diag.log("DAV", "scan truncated: index NOT overwritten, timestamps NOT updated")
        }
        scanning = false
        // 第 4 批：扫描完成自动触发后续整理（TODO #3「AI 随扫描自动启动、失败自动续跑」）。
        // 以前新照片扫完可能长期未分析，直到 ScanWatch 或手动点；现在 listing 一结束就接力。
        // 各 runner 自己做门控（ScanPolicy.paused / Wi-Fi·充电 / AiRunner.on），且 job 已在跑时直接返回，不会重复启动。
        if (error == null) ScanFollowUp.afterScan(App.ctx, this)
    }

    suspend fun refreshFolders() { runCatching { src!!.list("/").filter { it.isDir && !it.name.startsWith(".") } }.onSuccess { folders = it }.onFailure { if (it is CancellationException) throw it; if (folders == null) folders = emptyList() } }

    // ---------------------------------------------------------------- WebDAV 照片文件夹选择（空 = 全部文件夹）
    /** 用户选中的 WebDAV 照片文件夹（规范化后的绝对路径）；空列表表示全部文件夹。 */
    fun getSelectedDirs(): List<String> = runCatching {
        val raw = Store.getStr("dav.selectedDirs.$accountId")
        if (raw.isBlank()) emptyList()
        else JSONArray(raw).let { a -> (0 until a.length()).map { a.optString(it) } }
            .map { CloudPath.norm(it) }.filter { it.isNotEmpty() }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun setSelectedDirs(dirs: List<String>) {
        val norm = dirs.map { CloudPath.norm(it) }.filter { it.isNotEmpty() }.distinct().sorted()
        Store.putStr("dav.selectedDirs.$accountId", JSONArray(norm).toString())
    }

    /** 去掉被父目录覆盖的子目录（选了 /照片 就不用再单独选 /照片/2024）。 */
    private fun topSelectedDirs(selected: List<String>): List<String> {
        val sorted = selected.sortedBy { it.length }
        return sorted.filter { d -> sorted.none { other -> other != d && d.startsWith(other + "/") } }
    }

    // ---------------------------------------------------------------- index cache (1.0.10: per-month segments, see IndexStore)
    private fun readIndex(): List<CloudEntry> = IndexStore.read(dir, IndexStore.months(dir))

    /** B1: returns false when the index could not be persisted — the caller must not update timestamps then. */
    private fun writeIndex(l: List<CloudEntry>): Boolean = runCatching { IndexStore.write(dir, l) }.isSuccess

    private fun loadMeta() = runCatching {
        val o = JSONObject(File(dir, "meta.json").readText())
        o.keys().forEach { k -> meta[k] = Meta.of(o.getJSONObject(k)) }
    }

    fun saveMeta() = runCatching {
        val o = JSONObject(); meta.forEach { (k, v) -> o.put(k, v.json()) }
        File(dir, "meta.json").writeText(o.toString())
    }

    fun clearCache() { File(dir, "folders.json").delete(); IndexStore.clear(dir); fullyLoaded = false; File(dir, "meta.json").delete(); meta.clear(); photos.clear(); loadState = LoadState.NOT_LOADED }

    // ---------------------------------------------------------------- virtual albums (manifest in WebDAV root)
    suspend fun refreshAlbums() {
        runCatching { readAlbums() }.onSuccess { albums = it }.onFailure { if (it is CancellationException) throw it; if (albums == null) albums = emptyList(); error = it.message }
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
    /** B12: snapshot (photo, city) pairs — no meta[..]!! that can NPE when the meta was removed mid-group. */
    fun places(): List<Pair<String, List<Photo>>> = Hidden.visible(photos).mapNotNull { p ->
        meta[p.cloudPath]?.city?.takeIf { it.isNotEmpty() }?.let { p to it }
    }.groupBy({ it.second }, { it.first }).toList().sortedByDescending { it.second.size }

    /** B2 + B12: groups by head-MD5 + size (head-only match), snapshot — no meta[..]!!. Deletion must pass [fullSha256] secondary confirmation first. */
    fun duplicates(): List<List<Photo>> = photos.mapNotNull { p ->
        if (p.isVideo) null else meta[p.cloudPath]?.headMd5?.takeIf { it.isNotEmpty() }?.let { p to (it + ":" + p.size) }
    }.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }.sortedByDescending { it.size }

    /**
     * B2: full-file SHA-256 of one photo — the secondary confirmation before deleting a "duplicate".
     * Returns null when the file cannot be read or exceeds [maxBytes]: unverified, never "equal".
     */
    suspend fun fullSha256(path: String, maxBytes: Long = 256L * 1024 * 1024): String? = withContext(Dispatchers.IO) {
        val bytes = src?.allBytes(path, maxBytes) ?: return@withContext null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * B22: the O(n·400) union-find only runs when the analysis actually changed. The result is cached on
     * disk (dav/<id>/similar.json) and keyed by [metaVersion] + the photo set; a repeat call with the same
     * key rebuilds the groups from paths without recomputing any hash distance.
     */
    fun similar(maxDist: Int = 6): List<List<Photo>> {
        val items = photos.mapNotNull { p ->
            if (p.isVideo) null else meta[p.cloudPath]?.takeIf { it.hasHash }?.let { p to it }
        }.sortedBy { it.first.time }
        val paths = items.map { it.first.cloudPath }
        SimilarCache.load(dir, metaVersion, paths)?.let { cached ->
            val byPath = photos.associateBy { it.cloudPath }
            val groups = cached.mapNotNull { g -> g.mapNotNull { byPath[it] }.takeIf { it.size > 1 } }
            if (groups.isNotEmpty() || items.isEmpty()) return groups.sortedByDescending { it.size }
        }
        val h = items.map { it.second.dhash }
        val md = items.map { it.second.headMd5 + it.first.size }
        val plain = items.map { it.first }
        val parent = IntArray(plain.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in plain.indices) for (j in i + 1 until minOf(plain.size, i + 400)) {
            if (md[i] != md[j] && java.lang.Long.bitCount(h[i] xor h[j]) <= maxDist) parent[find(i)] = find(j)
        }
        val groups = plain.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { plain[it] } }.sortedByDescending { it.size }
        SimilarCache.save(dir, metaVersion, groups.map { g -> g.map { it.cloudPath } }, paths)
        return groups
    }

    /** S16: the single data-layer exit for "photos the 照片/视频 tabs may show" — 影视/音乐 folders excluded. */
    fun visiblePhotos(): List<Photo> = MediaIsolation.davVisible(accountId, photos)

    /** B12: snapshot (photo, aiCat) pairs — no meta[..]!!. */
    fun aiCategories(): List<Pair<String, List<Photo>>> = Hidden.visible(photos).mapNotNull { p ->
        meta[p.cloudPath]?.aiCat?.takeIf { it.isNotEmpty() }?.let { p to it }
    }.groupBy({ it.second }, { it.first }).toList().sortedByDescending { it.second.size }

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

    companion object { const val MANIFEST = "/.yiwei-albums.json"; const val RECENT_MONTHS = 3; const val MAX_ITEMS = 60000; /** H2: timeline refresh interval while scanning. */ const val SCAN_SHOW_MS = 5000L }
}

/**
 * B22: on-disk cache for [DavLib.similar]. Key = (metaVersion, photo-set hash). A stale key (analysis
 * changed, photos added/removed) forces a recompute; the stored groups are paths, so a cache hit still
 * resolves against the live [DavLib.photos] and can never resurrect a deleted photo.
 */
object SimilarCache {
    fun load(dir: File, metaVersion: Int, paths: List<String>): List<List<String>>? = runCatching {
        val o = JSONObject(File(dir, "similar.json").readText())
        if (o.optInt("mv") != metaVersion) return null
        if (o.optInt("hash") != paths.sorted().hashCode()) return null
        val a = o.optJSONArray("g") ?: return null
        (0 until a.length()).map { i -> val g = a.optJSONArray(i) ?: JSONArray(); (0 until g.length()).map { g.optString(it) } }
    }.getOrNull()

    fun save(dir: File, metaVersion: Int, groups: List<List<String>>, paths: List<String>) = runCatching {
        val a = JSONArray(); groups.forEach { g -> a.put(JSONArray(g)) }
        File(dir, "similar.json").writeText(JSONObject().put("mv", metaVersion).put("hash", paths.sorted().hashCode()).put("g", a).toString())
    }
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
        // B19: write the new segments first (tmp + atomic rename, copy fallback), then months.json, then
        // delete stale segments — a crash mid-write leaves either the old or the new complete index, never a mix.
        groups.forEach { (m, es) ->
            val text = encode(es)
            val f = File(d, "$m.json")
            if (!f.exists() || f.length() != text.length.toLong() || f.readText() != text) atomicWrite(f, text)
        }
        atomicWrite(File(d, "months.json"), JSONArray(groups.keys.sortedDescending()).toString())
        d.listFiles()?.forEach { f ->
            val m = f.name.removeSuffix(".json")
            if (f.name.endsWith(".json") && f.name != "months.json" && !f.name.endsWith(".tmp") && m !in groups) f.delete()
        }
    }

    /** tmp + renameTo; when rename fails (e.g. cross-filesystem) fall back to copy + delete tmp. */
    private fun atomicWrite(f: File, text: String) {
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeText(text)
        if (!t.renameTo(f)) {
            t.copyTo(f, overwrite = true)
            t.delete()
        }
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
            // B19: isDir round-trips now (it used to be dropped — every parsed entry came back as a file).
            val isDir = o.optBoolean("d")
            val isVideo = o.optBoolean("v") && !isDir
            CloudEntry(o.getString("n"), o.getString("p"), isDir, o.optLong("s"), o.optLong("m"), null, !isDir && !isVideo, isVideo)
        } }
    }.getOrDefault(emptyList())

    private fun encode(l: List<CloudEntry>): String {
        val a = JSONArray(); l.sortedBy { it.path }.forEach { e ->
            a.put(JSONObject().put("n", e.name).put("p", e.path).put("s", e.size).put("m", e.modified).put("v", e.isVideo).apply { if (e.isDir) put("d", true) })
        }
        return a.toString()
    }
}

object Dav {
    var currentId by mutableStateOf(Store.davId)
    /** S18: libs is read from UI + IO threads — a plain HashMap could corrupt under concurrent getOrPut. */
    private val libs = java.util.concurrent.ConcurrentHashMap<String, DavLib>()
    val accounts: List<CloudAccount> get() = CloudAccounts.all.filter { it.kind == CloudKind.WEBDAV }
    val current: CloudAccount? get() = accounts.firstOrNull { it.id == currentId } ?: accounts.firstOrNull()
    fun lib(): DavLib? = current?.let { a -> libs.getOrPut(a.id) { DavLib(a.id) } }
    fun lib(id: String): DavLib = libs.getOrPut(id) { DavLib(id) }
    fun select(id: String) { currentId = id; Store.davId = id }
    /**
     * B25: dropping an account repairs [Store.davId] (next remaining account, or empty) so [current] never
     * silently falls back to another account. The on-disk index/meta/faces are kept by default — pass
     * [clearDisk] = true to delete them as well (callers that mean "forget everything").
     */
    fun forget(id: String, clearDisk: Boolean = false) {
        libs.remove(id)?.close()
        CloudPhotos.forget(id)
        if (currentId == id) {
            currentId = accounts.firstOrNull { it.id != id }?.id ?: ""
            Store.davId = currentId
        }
        if (clearDisk) runCatching { File(App.ctx.filesDir, "dav/$id").deleteRecursively() }
    }
}

    /**
     * 第 4 批：扫描完成后的接力（TODO #3）。[DavLib.scan] 结束时调一次；各 runner 自带门控与「已在跑则返回」，
     * 所以 ScanWatch / 页面 LaunchedEffect 再调也不会重复启动。失败不抛：任何一个 runner 挂了都不影响其余两个。
     */
    object ScanFollowUp {
        fun afterScan(c: Context, lib: DavLib) {
            if (ScanPolicy.paused || lib.photos.isEmpty()) return
            runCatching { Analyzer.start(c, lib, false) }
            runCatching { Faces.start(c, lib, false) }
            if (AiRunner.on) runCatching { AiRunner.startDav(c, lib) }
        }
    }

    /** On-device analysis: EXIF GPS/time from the first 128 KB (HTTP Range), headMd5 (MD5 of that head) + size for duplicates, dHash from the EXIF thumbnail. */
object Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** B28: one job per account — analysing account A never cancels / blocks account B. */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val geoCache = HashMap<String, String>()

    /** Local analysis + faces: Wi-Fi and charging unless the user allowed otherwise. */
    fun canScan(c: Context): Boolean = (ScanPolicy.allowCellular || Net.isWifi(c)) && (ScanPolicy.allowNoCharge || Net.isCharging(c))
    val running: Boolean get() = jobs.values.any { it.isActive }

    /**
     * [manual] runs regardless of Wi-Fi/charging. Three phases, each only for what is still missing:
     * 1) photo analysis (EXIF/md5/dHash), 2) 1.0.10 one-time camera backfill for photos analysed before 1.0.9,
     * 3) 1.0.10 WebDAV video durations (MediaMetadataRetriever on the stream, 2 at a time).
     */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (jobs[lib.accountId]?.isActive == true) return
        if (!manual && !canScan(c)) return
        val needPhotos = lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.done != true }
        val needCam = !camFillDone(lib) && lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }
        val needDur = lib.photos.any { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 }
        val needMv = lib.photos.any { motionTodo(lib, it) }
        if (!needPhotos && !needCam && !needDur && !needMv) return
        ScanService.ensure(c)
        jobs[lib.accountId] = scope.launch {
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
                        // B16: one photo's failure must not abort the whole chunk — collect and continue.
                        chunk.map { p -> async { gate.acquire(); try { runCatching { analyze(c, s, p, lib) }.onFailure { Diag.log("DAV", "analyze ${p.cloudPath}: ${it.message}") } } finally { gate.release() } } }.awaitAll()
                        lib.analyzed = lib.meta.values.count { it.done }
                        lib.metaVersion++
                        lib.saveMeta()
                    }
                    withContext(Dispatchers.Main) { lib.retime() }
                }
                if (needCam && isActive && ok()) camBackfill(s, lib, ok)
                if (needMv && isActive && ok()) motionBackfill(s, lib, ok)
                if (needDur && isActive && ok()) videoDurations(lib, ok)
            } finally { lib.analyzing = false; jobs.remove(lib.accountId) }
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
                runCatching {
                    val m = lib.meta[p.cloudPath] ?: return@runCatching
                    val head = s.headBytes(p.cloudPath) ?: return@runCatching // network error: retry on a later run
                    runCatching { m.cam = camOf(ExifInterface(ByteArrayInputStream(head))) }
                    m.camChecked = true
                }.onFailure { Diag.log("DAV", "camBackfill ${p.cloudPath}: ${it.message}") }
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
                // B15: per-file runCatching; a network miss leaves mvChecked=false (untried, retried later),
                // only a successfully parsed head marks the photo tried.
                runCatching {
                    val m = lib.meta[p.cloudPath] ?: return@runCatching
                    val head = s.headBytes(p.cloudPath) ?: return@runCatching
                    m.mv = LivePairing.motionOffset(head); m.mvChecked = true
                }.onFailure { Diag.log("DAV", "motionBackfill ${p.cloudPath}: ${it.message}") }
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

    /** H8: retrievers that hit the timeout and are still stuck in native code (they only hold their own thread). */
    private val stuckDur = java.util.concurrent.atomic.AtomicInteger(0)

    /** 1.0.10: duration of WebDAV videos for the advanced-search duration filter. 2 at a time, 25 s cap per file, failures marked -1 and skipped. */
    private suspend fun videoDurations(lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 && it.video != null }
        var n = 0
        for (chunk in todo.chunked(2)) {
            // 2026-10-22: stop while a video plays (the rest is picked up on the next analysis run, nothing is marked failed)
            if (!isActive || !ok() || videoPlaying) { if (videoPlaying) Diag.log("DAV", "video durations paused: video playing"); break }
            chunk.map { p -> async(Dispatchers.IO) {
                runCatching {
                    val d = durationOf(p.video!!)
                    val m = lib.meta[p.cloudPath] ?: Meta().also { lib.meta[p.cloudPath] = it }
                    m.dur = if (d > 0) d else -1
                }.onFailure { Diag.log("DAV", "videoDurations ${p.cloudPath}: ${it.message}") }
            } }.awaitAll()
            if (++n % 10 == 0) { lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ } }
        }
        lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ }
    }

    /** Seconds, or 0 on failure / timeout. setDataSource can block on a slow host, so it runs on its own thread with a hard timeout. */
    fun durationOf(url: String): Int {
        // H8: the old fixed pool of 2 got filled by retrievers stuck in setDataSource (Future.cancel cannot interrupt
        // native code), after which every video timed out. Each lookup has its own daemon thread; the caller never waits past 25 s.
        // 2026-10-22 闪退修复: the retriever is created, used and released ONLY on its own thread. H8 used to release() it from
        // another thread while setDataSource was still blocked — MediaMetadataRetriever is not thread-safe, the native object
        // was freed under the running read and the process died with a native crash (no Java stack, no crash dialog),
        // typically while a WebDAV video was playing and starving these reads. A stuck one now just finishes on its own.
        if (stuckDur.get() >= 6) { Diag.log("DAV", "video duration: ${stuckDur.get()} retrievers still stuck, skipping"); return 0 }
        val result = java.util.concurrent.atomic.AtomicInteger(0)
        val lock = Any(); var finished = false; var timedOut = false
        val t = Thread({
            val mmr = android.media.MediaMetadataRetriever()
            try {
                val real = DavThumb.finalUrl(url)
                val h = if (real == url) (ImageAuth.headersFor(url) ?: emptyMap()) else emptyMap()
                mmr.setDataSource(real, h)
                result.set(((mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt())
            } catch (e: Throwable) {
                Diag.log("DAV", "video duration failed: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            } finally {
                runCatching { mmr.release() }
                synchronized(lock) { finished = true; if (timedOut) stuckDur.decrementAndGet() }
            }
        }, "yw-dur").apply { isDaemon = true }
        t.start()
        t.join(25_000)
        if (t.isAlive) {
            // S5: a timeout is logged and the caller moves on to the next file — it never blocks the queue.
            // Count it as stuck until its own thread finishes (it releases the retriever itself).
            val stuck = synchronized(lock) { if (!finished) { timedOut = true; stuckDur.incrementAndGet(); true } else false }
            if (!stuck) return result.get()
            Diag.log("DAV", "video duration timeout (${stuckDur.get()} stuck): ${url.take(120)}")
            return 0
        }
        return result.get()
    }

    /**
     * 2026-10-22 闪退修复: true while the 影音 player is open. Background duration reads (MediaMetadataRetriever over
     * WebDAV) pause meanwhile so they neither compete with the video for bandwidth nor pile up as stuck reads.
     */
    @Volatile var videoPlaying = false

    fun stop() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    /** B28: stop only one account's analysis job. */
    fun stop(accountId: String) { jobs.remove(accountId)?.cancel() }

    /**
     * B14/B16: the whole analysis is inside runCatching — a throw in one photo never kills the chunk.
     * [Meta.done] is set only when the EXIF block actually succeeded; an EXIF failure sets [Meta.failed]
     * (retried on the next pass) while the already-computed md5 is still kept ([Meta.hashDone]).
     */
    private fun analyze(c: Context, s: WebDavSource, p: Photo, lib: DavLib) {
        runCatching {
            val m = lib.meta[p.cloudPath] ?: Meta()
            val head = s.headBytes(p.cloudPath) ?: return
            m.headMd5 = MessageDigest.getInstance("MD5").digest(head).joinToString("") { "%02x".format(it) }.take(16)
            m.hashDone = true
            if (LivePairing.motionCandidateExt(p.fileName)) { m.mv = LivePairing.motionOffset(head); m.mvChecked = true }
            val exifOk = runCatching {
                val ex = ExifInterface(ByteArrayInputStream(head))
                val ll = FloatArray(2)
                if (ex.getLatLong(ll)) { m.lat = ll[0].toDouble(); m.lng = ll[1].toDouble(); m.city = city(c, m.lat, m.lng) }
                (ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME))?.takeIf { it.length >= 19 && !it.startsWith("0000") }?.let { m.time = it.take(19) }
                m.cam = camOf(ex); m.camChecked = true
                val tb = ex.thumbnailBytes
                val bmp = if (tb != null) BitmapFactory.decodeByteArray(tb, 0, tb.size) else BitmapFactory.decodeByteArray(head, 0, head.size)
                if (bmp != null) { m.dhash = dHash(bmp); m.hasHash = true }
            }.isSuccess
            m.done = exifOk
            m.failed = !exifOk
            lib.meta[p.cloudPath] = m
        }.onFailure { Diag.log("DAV", "analyze ${p.cloudPath}: ${it.message}") }
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
    /** Single source for the two toggle wordings + the settings reference used by AI / backup pages. */
    const val SETTINGS_REF = "设置 → 网络与电量"
    const val CELLULAR_DESC = "本地分析、人脸识别、AI 分类和手机备份都会读取或上传照片，产生流量"
    const val NO_CHARGE_DESC = "关掉更省电：本地分析、人脸识别和 AI 分类只在充电时继续"
    /**
     * The one pause / resume pair (notification action, AI card toggle, AI tab). Callers keep their own
     * UI entry points; the coordination lives here so the notification and the card can never drift apart.
     * [resumeAll] is the manual path (user pressed 继续): Wi-Fi / charging rules do not hold it back.
     * [startAiDav] is [AiRunner.on] for both callers (B31: the notification must resume local-AI too,
     * not just a configured model). [nasPhotos] is only passed by the notification resume.
     */
    fun pauseAll() {
        paused = true
        runCatching { AiRunner.pause() }
        runCatching { Analyzer.stop() }
        runCatching { Faces.stop() }
    }
    fun resumeAll(c: Context, startAiDav: Boolean, nasPhotos: List<Photo> = emptyList()) {
        paused = false
        runCatching { AiRunner.resume() }
        val lib = runCatching { Dav.lib() }.getOrNull()
        if (lib != null && lib.photos.isNotEmpty()) {
            runCatching { Analyzer.start(c, lib, true) }
            runCatching { Faces.start(c, lib, true) }
            if (startAiDav) runCatching { AiRunner.startDav(c, lib, manual = true) }
        }
        if (nasPhotos.isNotEmpty() && AiConfig.nasToo && AiRunner.on)
            runCatching { AiRunner.startNas(c, nasPhotos.toList()) }
    }
}

/**
 * One starter for the analysis + faces + AI trio (automatic starts honour [ScanPolicy], manual ones
 * do not — [manual] must stay true for the AI card's 「立即继续」 or the Wi-Fi / charging rules are bypassed
 * for every caller). Call sites keep their own paused / empty guards; only the trio itself is shared.
 */
object ScanStarter {
    fun startAll(c: Context, lib: DavLib, manual: Boolean) {
        if (!manual && !Analyzer.canScan(c)) return
        runCatching { Analyzer.start(c, lib, manual) }
        runCatching { Faces.start(c, lib, manual) }
        if (AiRunner.on) runCatching { AiRunner.startDav(c, lib, manual = manual) }
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
