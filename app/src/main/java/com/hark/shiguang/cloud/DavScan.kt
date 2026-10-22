package com.hark.shiguang.cloud

import com.hark.shiguang.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.0.1 incremental WebDAV scan.
 *
 * Every folder's tag (getetag, else the raw getlastmodified) is read from its PARENT's Depth:1 listing, so the
 * comparison is always "what the parent reported last time" vs "what it reports now". A folder is skipped (its media
 * taken from the previous index, no PROPFIND) only when it is a LEAF last time (no sub folders), its tag is known and
 * unchanged, and the sibling tags look trustworthy (not all identical — some drive mounts report one fixed time for
 * every folder). Non-leaf folders are always listed, because that is the only way to learn their children's fresh tags.
 * A folder whose listing fails keeps its previous media instead of silently dropping out of the index.
 */
class DavFolderCache(val dirs: MutableMap<String, Entry> = ConcurrentHashMap()) {
    class Entry(val tag: String?, val subdirs: List<String>)

    /** B13: dirs whose listing failed this pass — the caller keeps their old index + old cache, never drops them. */
    val unknown = ConcurrentHashMap.newKeySet<String>()

    fun json(): String {
        val o = JSONObject()
        dirs.forEach { (k, v) -> o.put(k, JSONObject().apply { v.tag?.let { put("t", it) }; put("d", JSONArray(v.subdirs)) }) }
        if (unknown.isNotEmpty()) o.put("u", JSONArray(unknown.toList()))
        return o.toString()
    }

    companion object {
        fun load(f: File): DavFolderCache = runCatching {
            val o = JSONObject(f.readText())
            val m = ConcurrentHashMap<String, Entry>()
            o.keys().forEach { k ->
                if (k == "u") return@forEach
                val e = o.getJSONObject(k)
                val d = e.optJSONArray("d")
                m[k] = Entry(e.optString("t").ifEmpty { null }, if (d == null) emptyList() else (0 until d.length()).map { d.getString(it) })
            }
            DavFolderCache(m).also { c ->
                o.optJSONArray("u")?.let { u -> for (i in 0 until u.length()) c.unknown.add(u.optString(i)) }
            }
        }.getOrDefault(DavFolderCache())
    }
}

class DavScanProgress {
    val listed = AtomicInteger(0)     // folders done (listed or reused)
    val known = AtomicInteger(1)      // folders discovered so far
    val skipped = AtomicInteger(0)    // unchanged leaves reused without a request
    val found = AtomicInteger(0)      // media entries emitted
}

/**
 * Result of [scanIncremental]: the new folder cache plus whether the listing covered the whole tree.
 * [complete] = false when the scan hit a cap ([maxItems] media, [CloudMedia.MAX_SCAN_DIRS] folders or
 * [CloudMedia.MAX_SCAN_DEPTH] depth) with folders still unvisited — the caller must NOT overwrite the stored
 * index, the folder cache or the last-scan timestamps with a truncated result (see B1).
 */
data class DavScanResult(val cache: DavFolderCache, val complete: Boolean)

/**
 * Lists [root] breadth-first, [CloudMedia.SCAN_PARALLEL] folders at a time, emitting each folder's media as soon as it
 * is known. [previous] = the old index (for reuse), [old] = folder tags of the last scan, [full] = ignore tags.
 * Returns the new folder cache (complete only when the root listing succeeded; a root failure is thrown).
 */
suspend fun WebDavSource.scanIncremental(
    root: String = "/",
    maxItems: Int,
    previous: List<CloudEntry>,
    old: DavFolderCache,
    full: Boolean,
    progress: DavScanProgress = DavScanProgress(),
    /** H7: folders already listed in an unfinished earlier pass (reused without a request) + progress sink. */
    resume: com.hark.shiguang.index.ScanResume? = null,
    onBatch: (List<CloudEntry>) -> Unit,
): DavScanResult {
    val byDir: Map<String, List<CloudEntry>> = previous.groupBy { CloudPath.parent(it.path) }
    val out = DavFolderCache()
    val emitted = AtomicInteger(0)
    val lock = Any()
    fun emit(media: List<CloudEntry>) {
        if (media.isEmpty()) return
        synchronized(lock) {
            val room = maxItems - emitted.get()
            if (room <= 0) return
            val b = if (media.size > room) media.subList(0, room) else media
            emitted.addAndGet(b.size); progress.found.addAndGet(b.size)
            onBatch(b)
        }
    }

    /** Children dirs of a listing, with their tags; tags dropped when all siblings share one value (untrustworthy). */
    fun childDirs(items: List<CloudEntry>, depth: Int): List<Pair<String, String?>> {
        val dirs = items.filter { it.isDir && !it.name.startsWith(".") }
        if (depth + 1 > CloudMedia.MAX_SCAN_DEPTH) return emptyList()
        val sameTag = dirs.size >= 3 && dirs.map { it.tag }.distinct().size == 1
        return dirs.map { it.path to if (sameTag) null else it.tag }
    }

    // root: always listed (a failure here is thrown)
    val rootNorm = CloudPath.norm(root)
    var level: List<Pair<String, String?>>
    val rootResumed = resume?.dirs?.get(rootNorm)
    if (rootResumed != null) {
        emit(resume.media[rootNorm].orEmpty())
        level = rootResumed.second
    } else {
        val rootItems = listBlocking(rootNorm)
        val media = rootItems.filter { !it.isDir && (it.isImage || it.isVideo) }
        emit(media)
        level = childDirs(rootItems, 0)
        resume?.onListed(rootNorm, null, level, media)
    }
    progress.listed.incrementAndGet()
    out.dirs[rootNorm] = DavFolderCache.Entry(null, level.map { it.first })
    progress.known.addAndGet(level.size)
    var depth = 1
    var visited = 1
    val sem = Semaphore(CloudMedia.SCAN_PARALLEL)
    while (level.isNotEmpty() && emitted.get() < maxItems && visited < CloudMedia.MAX_SCAN_DIRS && depth <= CloudMedia.MAX_SCAN_DEPTH) {
        val dirs = level.take(CloudMedia.MAX_SCAN_DIRS - visited)
        visited += dirs.size
        val next = java.util.Collections.synchronizedList(ArrayList<Pair<String, String?>>())
        val d = depth
        coroutineScope {
            dirs.forEach { (dir, tag) ->
                val prev = old.dirs[dir]
                // H7: listed in the unfinished earlier pass → reuse it (its media, tag and sub folders), no request
                val resumed = resume?.dirs?.get(dir)
                if (resumed != null) {
                    out.dirs[dir] = DavFolderCache.Entry(resumed.first, resumed.second.map { it.first })
                    emit(resume.media[dir].orEmpty())
                    next.addAll(resumed.second); progress.known.addAndGet(resumed.second.size)
                    progress.skipped.incrementAndGet(); progress.listed.incrementAndGet()
                    return@forEach
                }
                // S1: periodic full spot-check — ~1 in 20 unchanged leaves is listed anyway, so a server
                // that silently changes files without changing the folder tag is caught. The subdir-count
                // check stays: a leaf that gained sub folders is never skipped.
                val spotCheck = (dir.hashCode() and 0x7fffffff) % 20 == 0
                if (spotCheck) Diag.log("DAV", "spot-check leaf $dir (tag unchanged, listing anyway)")
                if (!full && !spotCheck && tag != null && prev != null && prev.tag == tag && prev.subdirs.isEmpty() && byDir.containsKey(dir)) {
                    // unchanged leaf: no request
                    out.dirs[dir] = prev
                    emit(byDir[dir].orEmpty())
                    progress.skipped.incrementAndGet(); progress.listed.incrementAndGet()
                    return@forEach
                }
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        if (emitted.get() >= maxItems) return@launch
                        val items = runCatching { listBlocking(dir) }.getOrNull()
                        if (items == null) {
                            // B13: listing failed — keep the old media, walk the old sub folders (tags unknown),
                            // and mark the branch unknown so the caller keeps the old index/cache for it.
                            // When there is no old cache entry at all (prev == null) we cannot even know the
                            // sub folders: the branch is still marked unknown and the caller merges the old
                            // index by parent path instead of dropping the photos.
                            emit(byDir[dir].orEmpty())
                            out.unknown.add(dir)
                            prev?.let { p -> out.dirs[dir] = DavFolderCache.Entry(null, p.subdirs); next.addAll(p.subdirs.map { it to null }); progress.known.addAndGet(p.subdirs.size) }
                        } else {
                            val media = items.filter { !it.isDir && (it.isImage || it.isVideo) }
                            emit(media)
                            val kids = childDirs(items, d)
                            out.dirs[dir] = DavFolderCache.Entry(tag, kids.map { it.first })
                            resume?.onListed(dir, tag, kids, media)
                            next.addAll(kids); progress.known.addAndGet(kids.size)
                        }
                        progress.listed.incrementAndGet()
                    } finally { sem.release() }
                }
            }
        }
        level = ArrayList(next); depth++
    }
    // B1: the loop can also exit with folders still unvisited (media / folder / depth cap). Treat hitting the
    // media cap as truncated even when every folder was visited: emit() drops media past the cap, so the
    // index would be missing entries from already-listed folders.
    val truncated = emitted.get() >= maxItems || level.isNotEmpty()
    return DavScanResult(out, complete = !truncated)
}
