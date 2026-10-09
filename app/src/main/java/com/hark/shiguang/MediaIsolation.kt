package com.hark.shiguang

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.data.Photo
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.0.1 #2 视频与影视隔离 — the 影视 library folders of each source, so the 照片 / 视频 tabs can leave them out.
 * 1.0.3 #4: the 音乐 folders (filesDir/{dav|fn}/<id>/music/config.json, same format) are left out too.
 *
 * Source keys are the same as [Movies]: "dav:<WebDAV account id>" and "fn:<飞牛 account id>".
 * Roots come straight from filesDir/{dav|fn}/<id>/movies/config.json (no library load); [MovieLib.saveDirs] calls
 * [invalidate], which bumps [version] so Compose lists that read it recompute.
 *
 * Path matching: both sides are normalized — "fm:" (fnOS 文件管理 paths) and leading "/" stripped, "volN/<uid>/" dropped
 * (飞牛 album paths may come as /vol1/1000/影视/… or as a display path without the volume) — then compared as folder prefixes.
 * The 飞牛 form of Photo.path (showFilePath vs filePath) is UNVERIFIED on a real NAS.
 *
 * Usage:
 *   MediaIsolation.isMediaPath("dav:$accountId", photo.cloudPath)
 *   MediaIsolation.davVisible(accountId, photos) / MediaIsolation.fnVisible(photos)
 */
object MediaIsolation {
    /** Read inside derivedStateOf / remember keys to refresh when the folders change. */
    var version by mutableIntStateOf(0)
        private set
    private val cache = ConcurrentHashMap<String, List<String>>()

    fun davKey(accountId: String) = "dav:$accountId"
    fun fnKey(): String = "fn:${NasAccounts.currentId}"

    /** Normalized library root folders of [sourceKey] (empty when none are set): 影视 folders + 1.0.3 音乐 folders. */
    fun roots(sourceKey: String): List<String> = cache.getOrPut(sourceKey) { (read(sourceKey, "movies") + read(sourceKey, "music")).distinct() }

    /** 1.0.3: only the 音乐 folders — the 影视 scan skips them. */
    fun musicRoots(sourceKey: String): List<String> = cache.getOrPut("music|$sourceKey") { read(sourceKey, "music") }

    private fun read(sourceKey: String, sub: String): List<String> {
        val kind = sourceKey.substringBefore(':'); val id = sourceKey.substringAfter(':')
        val f = File(App.ctx.filesDir, (if (kind == "dav") "dav" else "fn") + "/$id/$sub/config.json")
        return runCatching { JSONObject(f.readText()).optJSONArray("dirs")?.objs().orEmpty().map { norm(it.optString("path")) }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
    }

    /** 1.0.3: true when [path] lies inside one of the 音乐 folders of [sourceKey]. */
    fun isMusicPath(sourceKey: String, path: String): Boolean {
        val r = musicRoots(sourceKey)
        if (r.isEmpty() || path.isEmpty()) return false
        val p = norm(path)
        return r.any { root -> p == root || p.startsWith("$root/") }
    }

    /** True when [path] lies inside one of the 影视 library folders of [sourceKey]. */
    fun isMediaPath(sourceKey: String, path: String): Boolean {
        val r = roots(sourceKey)
        if (r.isEmpty() || path.isEmpty()) return false
        val p = norm(path)
        return r.any { root -> p == root || p.startsWith("$root/") }
    }

    fun <T> exclude(sourceKey: String, list: List<T>, path: (T) -> String): List<T> =
        if (roots(sourceKey).isEmpty()) list else list.filterNot { isMediaPath(sourceKey, path(it)) }

    fun davVisible(accountId: String, photos: List<Photo>): List<Photo> = exclude(davKey(accountId), photos) { it.cloudPath }
    fun fnVisible(photos: List<Photo>): List<Photo> = exclude(fnKey(), photos) { it.path }

    fun invalidate(sourceKey: String? = null) { if (sourceKey == null) cache.clear() else { cache.remove(sourceKey); cache.remove("music|$sourceKey") }; version++ }

    private val VOL = Regex("^vol\\d+/\\d+(?:/|$)")
    private fun norm(p: String): String {
        var s = p.removePrefix("fm:").replace('\\', '/').trim().trim('/')
        s = VOL.replace(s, "")
        return s.trimEnd('/')
    }
}
