package com.hark.shiguang.index

import com.hark.shiguang.cloud.CloudEntry

/**
 * 1.1.0 增量写索引：只算出「要新增/改动的行」和「要删掉的路径」，不再整份重写。
 * 纯逻辑（单元测试：IndexDiffTest），Room 和 JSON 两条存储路径共用。
 */
object IndexDiff {
    /** What a stored row looks like for the comparison (only fields that are persisted). */
    data class Key(val name: String, val size: Long, val modified: Long, val isVideo: Boolean, val isDir: Boolean)

    data class Result(val upserts: List<CloudEntry>, val deletes: List<String>) {
        val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
    }

    fun keyOf(e: CloudEntry) = Key(e.name, e.size, e.modified, e.isVideo && !e.isDir, e.isDir)

    /**
     * [old] = what is stored now (path → key), [new] = the full new index. Duplicate paths in [new] keep the last
     * one (same as a map put), so the stored index never holds a path twice.
     */
    fun diff(old: Map<String, Key>, new: List<CloudEntry>): Result {
        val latest = LinkedHashMap<String, CloudEntry>(new.size * 2)
        new.forEach { latest[it.path] = it }
        val ups = ArrayList<CloudEntry>()
        latest.values.forEach { e -> if (old[e.path] != keyOf(e)) ups.add(e) }
        val dels = old.keys.filter { it !in latest }
        return Result(ups, dels)
    }

    private val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
    const val NO_MONTH = "0000-00"

    /** Segment key = month of the file's modified time (same as the 1.0.10 JSON segments). */
    fun monthOf(ms: Long): String = if (ms <= 0) NO_MONTH else synchronized(fmt) { fmt.format(java.util.Date(ms)) }
}
