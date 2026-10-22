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

// 1.1.0 拆分自 Dav.kt（只移动代码，行为不变）：相似缓存与照片索引存储（Room + JSON 回退）。
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
 * 1.1.0 照片索引入口：数据在 Room（[com.hark.shiguang.index.PhotoIndexDb]），按 [IndexDiff] 增量写入。
 * - 第一次启动：把旧版 JSON 分段索引（[IndexJson]）整份导入 Room，核对条数一致后写标记文件 `index.room`；
 *   导入失败就继续用 JSON（旧文件从不删除），下次启动再试。
 * - 运行中 Room 读失败：本次进程改读 JSON（可能是导入时的旧快照，下次扫描会补齐）；
 *   写失败：改写 JSON 并删掉标记，下次启动从这份最新的 JSON 重新导入，所以数据不会丢。
 * 对外接口（months / read / write / clear）和 1.0.10 完全一样，调用方不用改。
 */
object IndexStore {
    private fun acc(dir: File) = dir.name
    private fun marker(dir: File) = File(dir, "index.room")
    /** Room failed in this process → JSON only until the next start. */
    @Volatile private var broken = false
    private fun dao() = com.hark.shiguang.index.PhotoIndexDb.get().dao()

    private fun fail(what: String, e: Throwable) {
        broken = true
        Diag.log("INDEX", "Room $what failed, falling back to JSON: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
    }

    /** True when this account's index lives in Room (imports the JSON index the first time). */
    @Synchronized private fun room(dir: File): Boolean {
        if (broken) return false
        if (marker(dir).exists()) return true
        return runCatching {
            val t0 = System.currentTimeMillis()
            val l = IndexJson.read(dir, IndexJson.months(dir))
            val rows = l.distinctBy { it.path }.map { com.hark.shiguang.index.DavEntryRow.of(acc(dir), it) }
            dao().replaceAll(acc(dir), rows)
            dao().clearScan(acc(dir))
            val n = dao().count(acc(dir))
            check(n == rows.size) { "count mismatch: json=${rows.size} room=$n" }
            marker(dir).writeText("1")
            Diag.log("INDEX", "migrated ${rows.size} entries from JSON to Room in ${System.currentTimeMillis() - t0} ms")
            true
        }.getOrElse { fail("migrate", it); false }
    }

    @Synchronized fun months(dir: File): List<String> {
        if (room(dir)) runCatching { return dao().months(acc(dir)) }.onFailure { fail("months", it) }
        return IndexJson.months(dir)
    }

    @Synchronized fun read(dir: File, months: List<String>): List<CloudEntry> {
        if (months.isEmpty()) return emptyList()
        if (room(dir)) runCatching {
            val out = ArrayList<CloudEntry>()
            months.chunked(400).forEach { ms -> dao().byMonths(acc(dir), ms).mapTo(out) { it.toEntry() } }
            return out
        }.onFailure { fail("read", it) }
        return IndexJson.read(dir, months)
    }

    /** Incremental: only changed / new rows are written and removed paths deleted, in one transaction. */
    @Synchronized fun write(dir: File, l: List<CloudEntry>) {
        if (room(dir)) {
            val ok = runCatching {
                val t0 = System.currentTimeMillis()
                val a = acc(dir)
                val old = dao().all(a).associate { it.path to IndexDiff.Key(it.name, it.size, it.modified, it.isVideo, it.isDir) }
                val d = IndexDiff.diff(old, l)
                if (!d.isEmpty) dao().apply(a, d.upserts.map { com.hark.shiguang.index.DavEntryRow.of(a, it) }, d.deletes)
                Diag.log("INDEX", "write ${l.size}: +${d.upserts.size} -${d.deletes.size} in ${System.currentTimeMillis() - t0} ms")
            }.onFailure { fail("write", it) }.isSuccess
            if (ok) return
            // Room write failed: the JSON becomes the newest copy; drop the marker so the next start re-imports it.
            IndexJson.write(dir, l)
            marker(dir).delete()
            return
        }
        IndexJson.write(dir, l)
    }

    @Synchronized fun clear(dir: File) {
        // Room forbids the main thread; deleting the marker is enough there — the next start re-imports the
        // (now empty) JSON, which replaces this account's rows and clears its scan progress.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            runCatching { dao().clear(acc(dir)); dao().clearScan(acc(dir)) }.onFailure { Diag.log("INDEX", "clear failed: ${it.message}") }
        marker(dir).delete()
        IndexJson.clear(dir)
    }
}

/**
 * 1.0.10 large-library index (JSON): `index/<yyyy-MM>.json` per month of the file's modified time + `index/months.json`
 * (newest first). Only changed segments are rewritten. The pre-1.0.10 single `index.json` is migrated on first read.
 * 1.1.0: kept as the import source and the fallback of [IndexStore] (Room).
 */
object IndexJson {
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
