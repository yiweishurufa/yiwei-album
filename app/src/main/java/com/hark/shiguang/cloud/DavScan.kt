package com.hark.shiguang.cloud

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

    fun json(): String {
        val o = JSONObject()
        dirs.forEach { (k, v) -> o.put(k, JSONObject().apply { v.tag?.let { put("t", it) }; put("d", JSONArray(v.subdirs)) }) }
        return o.toString()
    }

    companion object {
        fun load(f: File): DavFolderCache = runCatching {
            val o = JSONObject(f.readText())
            val m = ConcurrentHashMap<String, Entry>()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val d = e.optJSONArray("d")
                m[k] = Entry(e.optString("t").ifEmpty { null }, if (d == null) emptyList() else (0 until d.length()).map { d.getString(it) })
            }
            DavFolderCache(m)
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
    onBatch: (List<CloudEntry>) -> Unit,
): DavFolderCache {
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
    val rootItems = listBlocking(rootNorm)
    progress.listed.incrementAndGet()
    emit(rootItems.filter { !it.isDir && (it.isImage || it.isVideo) })
    var level = childDirs(rootItems, 0)
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
                if (!full && tag != null && prev != null && prev.tag == tag && prev.subdirs.isEmpty() && byDir.containsKey(dir)) {
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
                            // keep what we had: old media of this folder, and walk its old sub folders (tags unknown)
                            emit(byDir[dir].orEmpty())
                            prev?.let { p -> out.dirs[dir] = DavFolderCache.Entry(null, p.subdirs); next.addAll(p.subdirs.map { it to null }); progress.known.addAndGet(p.subdirs.size) }
                        } else {
                            emit(items.filter { !it.isDir && (it.isImage || it.isVideo) })
                            val kids = childDirs(items, d)
                            out.dirs[dir] = DavFolderCache.Entry(tag, kids.map { it.first })
                            next.addAll(kids); progress.known.addAndGet(kids.size)
                        }
                        progress.listed.incrementAndGet()
                    } finally { sem.release() }
                }
            }
        }
        level = ArrayList(next); depth++
    }
    return out
}
