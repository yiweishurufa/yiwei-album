package com.hark.shiguang.index

import com.hark.shiguang.Diag
import com.hark.shiguang.cloud.CloudEntry
import org.json.JSONArray

/**
 * H7（1.1.0）：WebDAV 扫描中间结果持久化。每列完一个文件夹就把它的子文件夹（含标签）和媒体写进数据库；
 * 扫描被打断（切走、进程被杀、断网）后，下一次扫描直接复用这些已列过的文件夹，不再重新 PROPFIND，从断点继续。
 * 只有扫描完整且索引写成功（或因上限被截断）才清掉进度。
 */
class ScanResume private constructor(
    private val account: String,
    /** dir → (its own tag, child dirs with tags) */
    val dirs: Map<String, Pair<String?, List<Pair<String, String?>>>>,
    val media: Map<String, List<CloudEntry>>,
) {
    @Volatile private var writeFailed = false

    /** Called from IO threads after a folder was listed successfully. Never throws. */
    fun onListed(dir: String, tag: String?, kids: List<Pair<String, String?>>, items: List<CloudEntry>) {
        if (writeFailed) return
        runCatching {
            val k = JSONArray().apply { kids.forEach { (p, t) -> put(JSONArray().put(p).put(t ?: org.json.JSONObject.NULL)) } }
            PhotoIndexDb.get().dao().saveListed(account, ScanDirRow(account, dir, tag, k.toString()),
                items.map { ScanMediaRow(account, dir, it.path, it.name, it.size, it.modified, it.isVideo) })
        }.onFailure { writeFailed = true; Diag.log("DAV", "scan progress save failed (resume disabled this pass): ${it.message?.take(160)}") }
    }

    val size get() = dirs.size

    companion object {
        /** A pass older than this is not resumed (folders may have changed a lot). */
        const val MAX_AGE_MS = 3L * 24 * 3600_000

        /**
         * Opens the progress of [account] for this pass. Same [roots] + not too old + not an explicit full rescan
         * → the folders listed last time are reused; otherwise the old progress is dropped and a new pass begins.
         * Returns null when the database is unusable (the scan then simply runs without resume).
         */
        fun open(account: String, roots: List<String>, explicitFull: Boolean): ScanResume? = runCatching {
            val dao = PhotoIndexDb.get().dao()
            val key = roots.joinToString("\n")
            val st = dao.scanState(account)
            val now = System.currentTimeMillis()
            if (st != null && st.roots == key && !explicitFull && now - st.startedAt in 0..MAX_AGE_MS) {
                val dirs = dao.scanDirs(account).associate { r -> r.dir to (r.tag to parseKids(r.kids)) }
                val media = dao.scanMedia(account).groupBy({ it.dir }, { it.toEntry() })
                Diag.log("DAV", "scan resume: ${dirs.size} folders / ${media.values.sumOf { it.size }} media from the unfinished pass")
                ScanResume(account, dirs, media)
            } else {
                dao.clearScan(account)
                dao.putScanState(ScanStateRow(account, key, now, explicitFull))
                ScanResume(account, emptyMap(), emptyMap())
            }
        }.getOrElse { Diag.log("DAV", "scan resume unavailable: ${it.message?.take(160)}"); null }

        /** The pass is over (complete, or truncated by caps): drop its progress. */
        fun finish(account: String) { runCatching { PhotoIndexDb.get().dao().clearScan(account) } }

        fun parseKids(s: String): List<Pair<String, String?>> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> p.getString(0) to (if (p.isNull(1)) null else p.optString(1).ifEmpty { null }) } }
        }.getOrDefault(emptyList())
    }
}
