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
import com.hark.shiguang.index.IndexDiff
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
        // H7: continue an interrupted pass from where it stopped (folders listed last time are not requested again)
        val resume = withContext(Dispatchers.IO) { com.hark.shiguang.index.ScanResume.open(accountId, roots, explicitFull = full) }
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
                        val res = s.scanIncremental(root, MAX_ITEMS, previous, old, doFull, prog, resume, onBatch)
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
        Diag.log("DAV", "scan ${if (doFull) "full" else "incremental"} roots=${roots.size} resumed=${resume?.size ?: 0}: ${prog.listed.get()} folders (${prog.skipped.get()} unchanged/resumed, skipped), ${all.size} media, complete=$complete, ${System.currentTimeMillis() - t0} ms")
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
                withContext(Dispatchers.IO) { com.hark.shiguang.index.ScanResume.finish(accountId) }
                Store.putStr("dav.lastScan.$accountId", System.currentTimeMillis().toString())
                if (doFull) Store.putStr("dav.lastFull.$accountId", System.currentTimeMillis().toString())
                setPhotos(CloudPhotos.toPhotos(accountId, all))
            } else {
                error = "索引保存失败，时间戳未更新"
                Diag.log("DAV", "scan: writeIndex failed, timestamps NOT updated")
            }
        } else if (!complete && error == null) {
            error = "扫描未完成（超出上限被截断），索引未覆盖，下次扫描会继续"
            withContext(Dispatchers.IO) { com.hark.shiguang.index.ScanResume.finish(accountId) }   // resuming would only hit the cap again
            Diag.log("DAV", "scan truncated: index NOT overwritten, timestamps NOT updated")
        }
        scanning = false
        // 第 4 批：扫描完成自动触发后续整理（TODO #3「AI 随扫描自动启动、失败自动续跑」）。
        // 以前新照片扫完可能长期未分析，直到 ScanWatch 或手动点；现在 listing 一结束就接力。
        // 各 runner 自己做门控（ScanPolicy.paused / Wi-Fi·充电 / AiRunner.on），且 job 已在跑时直接返回，不会重复启动。
        if (error == null) ScanFollowUp.afterScan(App.ctx, this)
    }

    suspend fun refreshFolders() { runCatching { (src ?: error("WebDAV 账户不可用")).list("/").filter { it.isDir && !it.name.startsWith(".") } }.onSuccess { folders = it }.onFailure { if (it is CancellationException) throw it; if (folders == null) folders = emptyList() } }

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

object Dav {
    var currentId by mutableStateOf(Store.davId)
    /** S18: libs is read from UI + IO threads — a plain HashMap could corrupt under concurrent getOrPut. */
    private val libs = java.util.concurrent.ConcurrentHashMap<String, DavLib>()
    val accounts: List<CloudAccount> get() = CloudAccounts.all.filter { it.kind == CloudKind.WEBDAV }
    val current: CloudAccount? get() = accounts.firstOrNull { it.id == currentId } ?: accounts.firstOrNull()
    fun lib(): DavLib? = current?.let { a -> libs.getOrPut(a.id) { DavLib(a.id) } }
    fun lib(id: String): DavLib = libs.getOrPut(id) { DavLib(id) }
    fun select(id: String) { currentId = id; Store.davId = id }
    /** Libraries currently in memory (for cache trimming). */
    fun loadedLibs(): List<DavLib> = libs.values.toList()
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
