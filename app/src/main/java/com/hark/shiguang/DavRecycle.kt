package com.hark.shiguang

import com.hark.shiguang.cloud.CloudEntry
import com.hark.shiguang.cloud.CloudPath
import com.hark.shiguang.cloud.WebDavSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 优化 5（用户 2026-11-02）：WebDAV / 网盘照片「删除先进回收站」。
 *
 * NAS（飞牛）照片原本就进飞牛回收站（发现 → 回收站）。WebDAV 以前是直接 DELETE，删错了找不回来。现在：
 *  - 先在网盘根目录建 /.yiwei-recycle（点开头，照片 / 影视 / 音乐扫描都跳过），把文件 MOVE 进去，文件名前加删除时间；
 *  - 服务器不支持 MOVE（或移动失败）时退一步：文件先不动，记为「待删除」，App 里立刻不再显示，
 *    30 天后（或在「最近删除」里点彻底删除）才真正 DELETE，期间随时可以恢复 —— 至少是延迟删除 + 可撤销；
 *  - 记录在 filesDir/dav/<账号>/recycle.json（新文件，旧版本没有时视为空）；保留 30 天，打开「最近删除」或再删除时清理过期的。
 * 本机相册里的照片本 App 不删除，不涉及系统 MediaStore 回收站。
 */
object DavRecycle {
    const val DIR = "/.yiwei-recycle"
    const val KEEP_MS = 30L * 24 * 3600_000L

    data class Entry(val orig: String, val trash: String, val deletedAt: Long, val name: String, val size: Long, val video: Boolean) {
        /** The file is still at its original place (the server could not move it). */
        val pending: Boolean get() = trash.isEmpty()
        fun daysLeft(now: Long = System.currentTimeMillis()): Int = (((deletedAt + KEEP_MS) - now) / 86_400_000L).toInt().coerceAtLeast(0)
        fun json(): JSONObject = JSONObject().put("o", orig).put("t", trash).put("d", deletedAt).put("n", name).put("s", size).put("v", video)
        companion object {
            fun of(o: JSONObject) = Entry(o.optString("o"), o.optString("t"), o.optLong("d"), o.optString("n"), o.optLong("s"), o.optBoolean("v"))
        }
    }

    /** 设置里可关（关掉 = 以前的直接删除）。默认开。 */
    var enabled: Boolean
        get() = Store.getStr("dav.recycle", "1") == "1"
        set(v) = Store.putStr("dav.recycle", if (v) "1" else "0")

    private val cache = HashMap<String, List<Entry>>()
    private fun file(acc: String) = File(App.ctx.filesDir, "dav/$acc/recycle.json").apply { parentFile?.mkdirs() }

    @Synchronized fun list(acc: String): List<Entry> = cache.getOrPut(acc) {
        runCatching { JSONArray(file(acc).readText()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Entry::of) } } }.getOrDefault(emptyList())
    }

    @Synchronized private fun save(acc: String, l: List<Entry>) {
        cache[acc] = l
        runCatching { file(acc).writeText(JSONArray().apply { l.forEach { put(it.json()) } }.toString()) }
    }

    /** Paths still at their original place but deleted in the app (hidden from the timeline). */
    fun pendingPaths(acc: String): Set<String> = list(acc).filter { it.pending }.map { it.orig }.toSet()

    /** Name inside the recycle folder: "<time>_<name>" keeps two deleted files with the same name apart. */
    fun trashName(name: String, now: Long): String = "${now}_$name"

    /** Moves [e] into the recycle folder (or marks it pending when the server cannot move it). */
    suspend fun trash(acc: String, src: WebDavSource, e: CloudEntry) {
        val now = System.currentTimeMillis()
        runCatching { src.mkdir("/", DIR.removePrefix("/")) }   // 405 = already there
        val target = CloudPath.join(DIR, trashName(e.name, now))
        val moved = runCatching { src.move(e.path, target) }
            .onFailure { Diag.log("DAV", "recycle move failed (${it.message}), deleting later instead") }.isSuccess
        synchronized(this) { save(acc, list(acc).filter { it.orig != e.path || !it.pending } + Entry(e.path, if (moved) target else "", now, e.name, e.size, e.isVideo)) }
        purgeExpired(acc, src)
    }

    /** Puts [en] back where it was. */
    suspend fun restore(acc: String, src: WebDavSource, en: Entry) {
        if (!en.pending) src.move(en.trash, en.orig)
        synchronized(this) { save(acc, list(acc) - en) }
        runCatching { Dav.lib(acc).addLocal(CloudEntry(en.name, en.orig, false, en.size, System.currentTimeMillis(), null, !en.video, en.video)) }
    }

    /** Deletes [en] for good. */
    suspend fun purge(acc: String, src: WebDavSource, en: Entry) {
        src.delete(CloudEntry(en.name, if (en.pending) en.orig else en.trash, false, en.size, 0, null, !en.video, en.video))
        synchronized(this) { save(acc, list(acc) - en) }
        if (en.pending) runCatching { Dav.lib(acc).removeLocal(listOf(en.orig)) }
    }

    /** Entries older than 30 days are deleted for good (failures stay and are retried next time). */
    suspend fun purgeExpired(acc: String, src: WebDavSource) {
        val now = System.currentTimeMillis()
        expired(list(acc), now).forEach { en -> runCatching { purge(acc, src, en) }.onFailure { Diag.log("DAV", "recycle purge failed: ${it.message}") } }
    }

    fun expired(l: List<Entry>, now: Long): List<Entry> = l.filter { now - it.deletedAt >= KEEP_MS }
}
