package com.hark.shiguang.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Extended fnOS (飞牛) Photos API layer.
 *
 * Endpoints and parameters were taken from the official web bundle (fnos-re/p/<chunk>.js). Where the
 * official bundle did not show something and it was only seen in the HarmonyOS reference client,
 * the line says "ref-only". Anything seen in neither is marked // UNVERIFIED.
 * All JSON calls go through FnClient.get/post (which prefix "/p" and sign with AuthX).
 */

// ---------------------------------------------------------------- models

/** One month bucket of the library timeline. */
data class MonthCount(val year: Int, val month: Int, val count: Int, val coverPhoto: Photo? = null) {
    /** "YYYY:MM" — the same key format Repo.monthEnd() takes. */
    val ym: String get() = "%04d:%02d".format(year, month)
    val start: String get() = "$ym:01 00:00:00"
    val end: String get() = Repo.monthEnd(ym)
}

/** One year bucket with its months (newest first, in timeline order). */
data class YearCount(val year: Int, val count: Int, val months: List<MonthCount>, val coverPhoto: Photo? = null)

/** A URL plus the headers the NAS needs to serve it (accesstoken + authx). */
data class DownloadSpec(val url: String, val headers: Map<String, String>)

/** Live/motion photo video source. [embedded]=true means the MP4 sits inside [url]'s bytes after [marker]. */
data class MotionSource(val url: String, val headers: Map<String, String>, val embedded: Boolean, val marker: String? = null)

/** A photo-library root folder (photo/folder/list), also the upload target list. */
data class LibraryFolder(val path: String, val isDefault: Boolean, val raw: JSONObject)

/** A sub-folder inside folder view (folder_view/getFolderList). */
data class FolderEntry(val path: String, val name: String, val raw: JSONObject)

/** Folder view page: sub-folders + one page of media. */
data class FolderPage(val folders: List<FolderEntry>, val photos: List<Photo>, val total: Int, val hasMore: Boolean)

/** NAS directory node from server/file_list (used by the target-folder picker). Shape // UNVERIFIED. */
data class NasDir(val path: String, val name: String, val isDir: Boolean, val raw: JSONObject)

/** Where an upload goes. */
sealed class UploadTarget {
    /** Official "temp" flow: server's default upload folder + photo/upload/notice (optionally into an album). */
    data class Library(val albumId: Int? = null) : UploadTarget()
    /** Explicit folder: file goes to [path]/<name> and folder_view/upload/notice registers it. */
    data class Folder(val path: String, val folderView: Boolean = true) : UploadTarget()
}

/** Result of one upload. [photoId] is 0 when the server did not return one. */
data class UploadResult(val photoId: Int, val originalName: String, val storedPath: String)

/** Smart (AI) category: scene/object ("scene") or text/information ("information"). */
data class SmartCategory(val name: String, val mainCategory: String, val count: Int, val coverId: Int, val coverUuid: String, val cover: String?)

/** Media-type category (media_category/list): photo, video, live_photo, gif, raw, 360, panorama, screenshot. */
data class MediaCategory(val type: Int, val key: String, val count: Int, val coverId: Int, val coverUuid: String, val cover: String?)

/** Duplicate / similar group. */
data class RepeatGroup(val id: String, val photos: List<Photo>, val datetimes: List<String>, val recommendedIndex: Int)

/** One page of duplicate groups. [strategy] is "similar" or "repeat". */
data class RepeatPage(val groups: List<RepeatGroup>, val count: Int, val strategy: String)

/** Map viewport in degrees. lonVert is the web's anti-meridian helper; 0 for normal viewports. */
data class MapBounds(val north: Double, val east: Double, val south: Double, val west: Double, val lonVert: Double = 0.0) {
    internal fun params(): MutableMap<String, Any?> =
        mutableMapOf("north" to north, "east" to east, "south" to south, "west" to west, "lonVert" to lonVert)
}

/** A clustered map point. */
data class MapPoint(val lat: Double, val lng: Double, val count: Int, val coverId: Int, val coverUuid: String, val cover: String?, val dateTime: String)

/** One filter dimension offered by search/filterlist (cascading: values -> sub options). */
data class SearchFilterOption(val name: String, val values: List<String>, val sub: List<SearchFilterOption>)

/**
 * A search filter. [values] is the cascade path, e.g. photo_location = [country, city],
 * photo_date = [year, month, day], file_dir = [path], person = [personId].
 */
data class SearchFilter(val key: String, val values: List<String>) {
    internal fun toJson(): JSONObject? {
        val v = values.filter { it.isNotBlank() }
        if (v.isEmpty()) return null
        fun node(name: String, rest: List<String>): JSONObject {
            val o = JSONObject().put("filterName", name).put("filterValue", rest[0])
            if (rest.size > 1) o.put("subFilters", JSONArray().put(node(rest[0], rest.drop(1))))
            return o
        }
        return node(key, v)
    }

    companion object {
        fun person(personId: Int) = SearchFilter("person", listOf(personId.toString()))
        /** key: photo | video | live_photo | gif | raw | 360 | panorama | screenshot */
        fun fileType(key: String) = SearchFilter("file_type", listOf(key))
        fun location(country: String, city: String? = null) = SearchFilter("photo_location", listOfNotNull(country, city))
        /** Value format of the date cascade is taken from search/filterlist; plain numbers here are // UNVERIFIED. */
        fun date(year: String, month: String? = null, day: String? = null) = SearchFilter("photo_date", listOfNotNull(year, month, day))
        fun folder(path: String) = SearchFilter("file_dir", listOf(path))
        fun album(name: String) = SearchFilter("album_name", listOf(name))
        fun tag(name: String) = SearchFilter("photo_tag", listOf(name))
        fun camera(makeModel: List<String>) = SearchFilter("make_model", makeModel)
        fun collected() = SearchFilter("is_collect", listOf("1")) // value "1" UNVERIFIED
        fun noAlbum() = SearchFilter("not_album", listOf("1"))    // value "1" UNVERIFIED
        fun noTag() = SearchFilter("not_tag", listOf("1"))        // value "1" UNVERIFIED
        fun noGeo() = SearchFilter("not_geo", listOf("1"))        // value "1" UNVERIFIED

        /** Every filter key the official search form knows. */
        val KEYS = listOf(
            "is_collect", "not_album", "not_tag", "not_geo", "file_type", "photo_date", "photo_location", "album_name",
            "photo_tag", "make_model", "exposure_time", "ISO_speed_ratings", "focal_length", "fumber", "file_dir", "person",
        )
    }
}

/** Result of /api/v2/search/results. */
data class SearchResult(val photos: List<Photo>, val timeline: List<DayCount>)

// ---------------------------------------------------------------- helpers (file-private)

private fun JSONObject.dataObj(): JSONObject? = optJSONObject("data")
private fun JSONObject.dataList(): JSONArray = optJSONObject("data")?.optJSONArray("list") ?: optJSONArray("data") ?: JSONArray()
private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray.strs(): List<String> = (0 until length()).mapNotNull { opt(it)?.toString() }.filter { it != "null" }
private fun JSONObject.str(vararg keys: String): String {
    for (k in keys) { val v = optString(k); if (v.isNotEmpty() && v != "null") return v }
    return ""
}
private fun ids(list: List<Int>): JSONArray = JSONArray().apply { list.forEach { put(it) } }
private fun thumb(id: Int, uuid: String, size: String): String? =
    if (id <= 0 || uuid.isBlank()) null else FnClient.abs("/p/api/v1/stream/p/t/$id/$size/$uuid")

// ---------------------------------------------------------------- API

object NasX {

    /**
     * Base64 HMAC key used for the upload Trim-Sign header (the web keeps it in localStorage "fnos-Secret").
     * It is the login response's "secret" field AES-CBC-decrypted with the login AES key/iv; see [decodeLoginSecret].
     * When empty, uploads are sent without Trim-Sign (the web does the same when it has no secret).
     */
    @Volatile var signSecretB64: String = ""

    private val rnd = SecureRandom()

    private val uploadHttp by lazy {
        FnClient.http.newBuilder().writeTimeout(0, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
    }

    // ============================================================ timeline summaries (derived, no new endpoint)

    /** Month buckets derived from Repo.timeline(); covers cost one getList call per month when [covers] is true. */
    suspend fun monthSummary(collect: Boolean = false, covers: Boolean = false): List<MonthCount> {
        val days = Repo.timeline(collect)
        val months = LinkedHashMap<Pair<Int, Int>, Int>()
        days.forEach { d -> val k = d.year to d.month; months[k] = (months[k] ?: 0) + d.count }
        return months.map { (k, c) ->
            val m = MonthCount(k.first, k.second, c)
            if (covers) m.copy(coverPhoto = monthCover(m.year, m.month, collect)) else m
        }
    }

    /** Year buckets derived from Repo.timeline(). */
    suspend fun yearSummary(collect: Boolean = false, covers: Boolean = false): List<YearCount> {
        val months = monthSummary(collect, false)
        return months.groupBy { it.year }.map { (y, ms) ->
            YearCount(y, ms.sumOf { it.count }, ms, if (covers) ms.firstOrNull()?.let { monthCover(it.year, it.month, collect) } else null)
        }
    }

    /** Newest photo of a month (gallery/getList limit 1). */
    suspend fun monthCover(year: Int, month: Int, collect: Boolean = false): Photo? = runCatching {
        val ym = "%04d:%02d".format(year, month)
        Repo.photos("$ym:01 00:00:00", Repo.monthEnd(ym), 0, 1, collect).first.firstOrNull()
    }.getOrNull()

    // ============================================================ recycle bin

    /** Move photos to the recycle bin: POST /api/v1/recycle-bin/add {ids}. */
    suspend fun delete(ids: List<Int>) {
        if (ids.isEmpty()) return
        FnClient.post("/api/v1/recycle-bin/add", JSONObject().put("ids", ids(ids)))
    }

    /** Recycle bin page: GET /api/v1/recycle-bin/list {offset,limit,start_time,end_time}. */
    suspend fun recycleList(offset: Int, limit: Int, start: String = Repo.FAR_START, end: String = Repo.FAR_END): List<Photo> =
        FnClient.get("/api/v1/recycle-bin/list", mapOf("offset" to offset, "limit" to limit, "start_time" to start, "end_time" to end))
            .dataList().objs().map(Photo::parse)

    /** Recycle bin day buckets: GET /api/v1/recycle-bin/timeline (response assumed same shape as gallery/timeline // UNVERIFIED). */
    suspend fun recycleTimeline(): List<DayCount> =
        FnClient.get("/api/v1/recycle-bin/timeline").dataList().objs().map {
            DayCount(it.optInt("year"), it.optInt("month"), it.optInt("day"), it.optInt("itemCount"))
        }

    /** Restore from recycle bin: POST /api/v1/recycle-bin/restore {ids}. */
    suspend fun recycleRestore(ids: List<Int>) {
        if (ids.isEmpty()) return
        FnClient.post("/api/v1/recycle-bin/restore", JSONObject().put("ids", ids(ids)))
    }

    /** Delete forever: POST /api/v1/recycle-bin/delete {ids}. */
    suspend fun recycleDeleteForever(ids: List<Int>) {
        if (ids.isEmpty()) return
        FnClient.post("/api/v1/recycle-bin/delete", JSONObject().put("ids", ids(ids)))
    }

    /** Empty the recycle bin: POST /api/v1/recycle-bin/clean-all with no body (signed over ""), like the web. */
    suspend fun recycleClear() {
        signedRaw("POST", "/api/v1/recycle-bin/clean-all", null)
    }

    // ============================================================ download / original / live photo

    /** Headers the NAS needs for a /p/... GET (accesstoken + authx over path and sorted raw query). */
    fun streamHeaders(url: String): Map<String, String> {
        val u = runCatching { URI(url) }.getOrNull()
        val path = u?.rawPath ?: url.substringBefore('?')
        val query = u?.rawQuery.orEmpty()
        val raw = if (query.isEmpty()) "" else query.split("&").filter { it.isNotEmpty() }.map {
            val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            k to v
        }.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
        return mapOf("accesstoken" to FnClient.token, "authx" to AuthX.header(path, raw))
    }

    /** Full-resolution stream of one photo (web formatter originalUrl: <prefix>p/t/{id}/o/{uuid}). */
    fun originalDownloadUrl(p: Photo): String = p.original

    /** [originalDownloadUrl] with headers. For videos this is the original-size still; use [videoDownload] for the movie. */
    fun originalDownload(p: Photo): DownloadSpec = DownloadSpec(p.original, streamHeaders(p.original))

    /** Video stream (web formatter videoUrl: <prefix>v/{id}). */
    fun videoDownload(p: Photo): DownloadSpec? = p.video?.let { DownloadSpec(it, streamHeaders(it)) }

    /**
     * Exact original file(s) as stored on the NAS (the web "download" action):
     * POST /api/v1/preview/download {ids, down_type} -> data.uuid, then GET /api/v1/preview/download?uuid=...
     * downType: "original" is the web default. Several ids most likely come back as one archive // UNVERIFIED.
     * The web follows the GET link with cookies; header auth on that GET is // UNVERIFIED.
     */
    suspend fun fileDownload(ids: List<Int>, downType: String = "original"): DownloadSpec {
        val j = FnClient.post("/api/v1/preview/download", JSONObject().put("ids", ids(ids)).put("down_type", downType))
        val uuid = j.dataObj()?.str("uuid").orEmpty()
        if (uuid.isEmpty()) throw ApiException(-1, "下载失败")
        val url = "${FnClient.baseUrl}/p/api/v1/preview/download?uuid=${enc(uuid)}"
        return DownloadSpec(url, streamHeaders(url))
    }

    /** Whole album download link: GET /api/v1/album/download/all?album_id=&down_type= (web opens it as a link). */
    fun albumDownload(albumId: Int, downType: String = "original"): DownloadSpec {
        val url = "${FnClient.baseUrl}/p/api/v1/album/download/all?album_id=$albumId&down_type=${enc(downType)}"
        return DownloadSpec(url, streamHeaders(url))
    }

    /** Raw item from GET /api/v1/gallery/getOne {id}. */
    suspend fun getOne(id: Int): JSONObject? = FnClient.get("/api/v1/gallery/getOne", mapOf("id" to id)).dataObj()

    /** isLive type of a photo (0 none, 1/2/3 live variants) via gallery/getOne. 1.0.1: list items keep it in Photo.liveType. */
    suspend fun liveType(id: Int): Int = getOne(id)?.optInt("isLive") ?: 0

    /**
     * Where the moving part of a live/motion photo lives.
     * Official player: types 2 and 3 are Motion Photos — the MP4 is embedded in the original bytes, starting 4 bytes
     * before "ftypmp42" (type 2) or "ftypisom" (type 3). Other live types play the separate video stream (videoUrl);
     * which types take that branch is decided by a helper not traced here, so the type-1 -> videoUrl mapping is // UNVERIFIED.
     */
    suspend fun motionVideo(p: Photo, liveType: Int? = null): MotionSource? {
        val t = liveType ?: p.liveType.takeIf { it > 0 } ?: liveType(p.id)
        if (t <= 0) return null
        return when (t) {
            2 -> MotionSource(p.original, streamHeaders(p.original), true, "ftypmp42")
            3 -> MotionSource(p.original, streamHeaders(p.original), true, "ftypisom")
            else -> {
                val v = p.video ?: FnClient.abs("/p/api/v1/stream/v/${p.id}")!! // UNVERIFIED for live type 1 without thumbnail.videoUrl
                MotionSource(v, streamHeaders(v), false)
            }
        }
    }

    /** Slice the embedded MP4 out of a Motion Photo's bytes (marker search like the official worker). */
    fun extractEmbeddedMp4(bytes: ByteArray, marker: String = "ftypmp42"): ByteArray? {
        val m = marker.toByteArray()
        outer@ for (i in 0..(bytes.size - m.size)) {
            for (k in m.indices) if (bytes[i + k] != m[k]) continue@outer
            val start = i - 4
            return if (start >= 0) bytes.copyOfRange(start, bytes.size) else null
        }
        return null
    }

    /** Download + extract the motion MP4 when it is embedded; returns null when not a Motion Photo. */
    suspend fun motionMp4Bytes(p: Photo, liveType: Int? = null): ByteArray? {
        val src = motionVideo(p, liveType) ?: return null
        if (!src.embedded) return null
        val bytes = rawGet(src.url, src.headers)
        return extractEmbeddedMp4(bytes, src.marker ?: "ftypmp42")
    }

    // ============================================================ upload

    /** Default upload folder: GET /api/v2/photo/upload/path -> data.upload.path (falls back to v1). */
    suspend fun uploadPath(): String {
        val v2 = runCatching { FnClient.get("/api/v2/photo/upload/path") }.getOrNull()
        val p2 = v2?.dataObj()?.optJSONObject("upload")?.str("path").orEmpty()
        if (p2.isNotEmpty()) return p2
        // v1 shape not traced (web only does transformResponse e => e.data) // UNVERIFIED
        val v1 = runCatching { FnClient.get("/api/v1/photo/upload/path") }.getOrNull() ?: return ""
        val d = v1.opt("data")
        return when (d) {
            is String -> d
            is JSONObject -> d.optJSONObject("upload")?.str("path")?.ifEmpty { null } ?: d.str("path", "uploadPath", "folderPath")
            else -> ""
        }
    }

    /** Whether the user may upload (data.upload.hasAccess of /api/v2/photo/upload/path). */
    suspend fun uploadAllowed(): Boolean = runCatching {
        FnClient.get("/api/v2/photo/upload/path").dataObj()?.optJSONObject("upload")?.optBoolean("hasAccess", false) ?: false
    }.getOrDefault(false)

    /** Upload-able extensions: GET /api/v1/photo/support/list (web: data.list). */
    suspend fun uploadSupportedTypes(): List<String> = runCatching {
        FnClient.get("/api/v1/photo/support/list").dataList().strs().map { it.lowercase() }
    }.getOrDefault(emptyList())

    /** Library root folders = upload targets: GET /api/v1/photo/folder/list -> data.list[{folderPath,isDefault}]. */
    suspend fun libraryFolders(): List<LibraryFolder> =
        FnClient.get("/api/v1/photo/folder/list").dataList().objs().map {
            LibraryFolder(it.str("folderPath", "path"), it.optBoolean("isDefault", false), it)
        }.filter { it.path.isNotEmpty() }

    /** NAS directory browser for picking a target: POST /api/v1/server/file_list {path, isAll}. Response shape // UNVERIFIED. */
    suspend fun nasDirs(path: String, isAll: Boolean = false): List<NasDir> {
        val j = FnClient.post("/api/v1/server/file_list", JSONObject().put("path", path).put("isAll", isAll))
        val arr = j.optJSONArray("data") ?: j.dataObj()?.optJSONArray("list") ?: j.dataObj()?.optJSONArray("files") ?: JSONArray()
        return arr.objs().map {
            val p = it.str("path", "fullPath", "filePath")
            val n = it.str("name", "fileName").ifEmpty { p.trimEnd('/').substringAfterLast('/') }
            NasDir(p, n, it.optInt("dir", if (it.optBoolean("isDir", false)) 1 else 0) == 1, it)
        }
    }

    /** Create a folder: POST /api/v1/photo/folder/new {path, folderName}. */
    suspend fun createFolder(parentPath: String, name: String) {
        FnClient.post("/api/v1/photo/folder/new", JSONObject().put("path", parentPath).put("folderName", name))
    }

    /**
     * Upload one file exactly like the official upload worker (no chunking, no hashing):
     * 1) multipart POST {baseUrl}/upload, part "trim-upload-file", headers Trim-Path (encodeURI(target)),
     *    Trim-Overwrite, Trim-Mtim (seconds), Trim-Token, Trim-Sign (HMAC-SHA256(secret, Trim-Path), base64), Trim-Flags=1;
     * 2) notice: Library -> POST /api/v1/photo/upload/notice {files:[{file_create_time,original_name,file_name}], album_id}
     *            Folder  -> POST /api/v1/folder_view/upload/notice {folder_path, file_list:[...]}.
     * In Library mode the stored name is "<stem>_<taskId>.<ext>"; in folder-view mode it is ".<stem>_<taskId>.<ext>".
     * [onProgress] gets (bytesSent, totalBytes) during step 1.
     */
    suspend fun upload(
        fileName: String,
        size: Long,
        mime: String,
        open: () -> InputStream,
        target: UploadTarget = UploadTarget.Library(),
        lastModifiedMs: Long = System.currentTimeMillis(),
        overwrite: Int = 1,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        val taskId = nanoId()
        val (dir, stored) = when (target) {
            is UploadTarget.Library -> uploadPath() to storedName(fileName, taskId, false)
            is UploadTarget.Folder -> target.path.trimEnd('/') to storedName(fileName, taskId, target.folderView)
        }
        if (dir.isEmpty()) throw ApiException(-1, "未找到上传目录，请先在 NAS 相册里设置备份位置")
        val targetPath = "$dir/$stored"
        val trimPath = encodeURI(targetPath)

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("trim-upload-file", fileName, StreamBody(mime, size, open, onProgress))
            .build()
        val rb = Request.Builder().url("${FnClient.baseUrl}/upload").post(body)
            .header("Trim-Path", trimPath)
            .header("Trim-Overwrite", overwrite.toString())
            .header("Trim-Mtim", (lastModifiedMs / 1000).toString())
            .header("Trim-Token", FnClient.token)
            .header("Trim-Flags", "1")
            // The browser also carries its session cookie; name taken from the web's cookie read "fnos-token". // UNVERIFIED as required
            .header("Cookie", "fnos-token=${FnClient.token}")
        val secret = signSecretB64
        if (secret.isNotEmpty()) rb.header("Trim-Sign", hmacB64(trimPath, secret))
        uploadHttp.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, if (r.code == 403 && signSecretB64.isEmpty()) "上传失败（403）：请在账号里退出后重新登录一次，以获取上传签名" else "上传失败（HTTP ${r.code}）")
            val j = runCatching { JSONObject(text) }.getOrNull()
            if (j != null && j.has("code") && j.optInt("code") != 0) throw ApiException(j.optInt("code"), j.str("msg").ifEmpty { "上传失败" })
        }
        onProgress(size, size)

        val info = JSONObject()
            .put("file_create_time", utcExif(lastModifiedMs))
            .put("original_name", fileName)
            .put("file_name", stored)
        val temp = target is UploadTarget.Library && overwrite == 1
        val resp = if (temp) {
            val b = JSONObject().put("files", JSONArray().put(info))
            (target as UploadTarget.Library).albumId?.let { b.put("album_id", it) }
            FnClient.post("/api/v1/photo/upload/notice", b)
        } else {
            FnClient.post("/api/v1/folder_view/upload/notice", JSONObject().put("folder_path", dir).put("file_list", JSONArray().put(info)))
        }
        val first = resp.dataList().objs().let { l -> l.firstOrNull { it.str("fileName", "file_name") == stored } ?: l.firstOrNull() }
        val err = first?.str("error").orEmpty()
        if (err.isNotEmpty()) throw ApiException(-1, err)
        UploadResult(first?.optInt("id") ?: 0, fileName, targetPath)
    }

    /** Batch helper: uploads sequentially, reporting (index, sent, total). Stops on the first failure. */
    suspend fun uploadAll(
        items: List<Triple<String, Long, () -> InputStream>>,
        mimeOf: (String) -> String,
        target: UploadTarget = UploadTarget.Library(),
        onProgress: (index: Int, sent: Long, total: Long) -> Unit = { _, _, _ -> },
    ): List<UploadResult> = items.mapIndexed { i, (name, size, open) ->
        upload(name, size, mimeOf(name), open, target, onProgress = { s, t -> onProgress(i, s, t) })
    }

    /**
     * Turns the login response's "secret" field into the Base64 HMAC key for Trim-Sign:
     * AES-256-CBC/PKCS7 decrypt with the same AES key (the 32-char string) and IV used to encrypt the login request
     * (matches the reference client; FnClient.login must keep aesKey/iv and call this, then set [signSecretB64]).
     */
    fun decodeLoginSecret(secretCipherB64: String, aesKey: String, iv: ByteArray): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey.toByteArray(), "AES"), IvParameterSpec(iv))
        val raw = c.doFinal(Base64.decode(secretCipherB64, Base64.DEFAULT))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    // ============================================================ albums

    /** Create album: POST /api/v1/album/create {album_name, ids} -> data.albumId. */
    suspend fun createAlbum(name: String, photoIds: List<Int> = emptyList()): Int =
        FnClient.post("/api/v1/album/create", JSONObject().put("album_name", name).put("ids", ids(photoIds)))
            .dataObj()?.optInt("albumId") ?: 0

    /** Add photos: POST /api/v1/gallery/addTo/album {ids, album_id, album_name?}. */
    suspend fun addToAlbum(albumId: Int, photoIds: List<Int>, albumName: String? = null) {
        val b = JSONObject().put("ids", ids(photoIds)).put("album_id", albumId)
        if (albumName != null) b.put("album_name", albumName)
        FnClient.post("/api/v1/gallery/addTo/album", b)
    }

    /** Remove photos from album: POST /api/v1/album/photo/remove {album_id, ids}. */
    suspend fun removeFromAlbum(albumId: Int, photoIds: List<Int>) {
        FnClient.post("/api/v1/album/photo/remove", JSONObject().put("album_id", albumId).put("ids", ids(photoIds)))
    }

    /** Rename: POST /api/v1/album/update/name {album_id, album_name}. */
    suspend fun renameAlbum(albumId: Int, name: String) {
        FnClient.post("/api/v1/album/update/name", JSONObject().put("album_id", albumId).put("album_name", name))
    }

    /** Delete album (photos stay): POST /api/v1/album/delete {album_id}. */
    suspend fun deleteAlbum(albumId: Int) {
        FnClient.post("/api/v1/album/delete", JSONObject().put("album_id", albumId))
    }

    /** Set album cover: POST /api/v1/album/update/poster — body keys {album_id, id} // UNVERIFIED (web passes the body through). */
    suspend fun setAlbumCover(albumId: Int, photoId: Int) {
        FnClient.post("/api/v1/album/update/poster", JSONObject().put("album_id", albumId).put("id", photoId))
    }

    // ============================================================ smart / media categories

    /** AI categories: GET /api/v1/ai-smart-rec/categories {limit:-1, mainCategory: "scene"|"information"}. */
    suspend fun smartCategories(mainCategory: String = "scene"): List<SmartCategory> =
        FnClient.get("/api/v1/ai-smart-rec/categories", mapOf("limit" to -1, "mainCategory" to mainCategory))
            .dataList().objs().map {
                val poster = it.optJSONObject("poster")
                val pid = poster?.optInt("photoId") ?: 0
                val uuid = poster?.str("uuid", "photoUUID").orEmpty()
                // count field not displayed by the web; names below are a guess // UNVERIFIED
                SmartCategory(it.str("name"), mainCategory, it.optInt("count", it.optInt("itemCount")), pid, uuid, thumb(pid, uuid, "s"))
            }.filter { it.name.isNotEmpty() }

    /** Both groups the web shows (scene + information). */
    suspend fun allSmartCategories(): List<SmartCategory> = smartCategories("scene") + smartCategories("information")

    /**
     * Photos of a smart category. The web opens AI search with the category name as keyword:
     * POST /api/v1/magic-search/do {keyword, antiFilters}. No server paging, so offset/limit slice client-side.
     */
    suspend fun categoryPhotos(name: String, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<Photo> {
        val all = FnClient.post("/api/v1/magic-search/do", JSONObject().put("keyword", name).put("antiFilters", JSONArray()))
            .dataList().objs().map(Photo::parse)
        return all.drop(offset).take(limit)
    }

    /** Media-type categories: GET /api/v1/media_category/list -> data.list[{category,id,uuid,count}]. */
    suspend fun mediaCategories(): List<MediaCategory> =
        FnClient.get("/api/v1/media_category/list").dataList().objs().mapNotNull {
            val type = it.optInt("category")
            val key = MEDIA_KEYS[type] ?: return@mapNotNull null
            val id = it.optInt("id"); val uuid = it.str("uuid")
            MediaCategory(type, key, it.optInt("count"), id, uuid, thumb(id, uuid, "m"))
        }

    /** Photos of a media category = filtered search with file_type (as the web does). */
    suspend fun mediaCategoryPhotos(key: String): List<Photo> = search("", listOf(SearchFilter.fileType(key))).photos

    private val MEDIA_KEYS = mapOf(
        1 to "photo", 2 to "video", 3 to "live_photo", 4 to "gif", 5 to "raw", 6 to "360", 7 to "panorama", 8 to "screenshot",
    )

    // ============================================================ duplicates / similar

    /** Duplicate groups page: GET /api/v1/repeat-photo/list {offset, size} -> data{list, count, strategy}. */
    suspend fun repeatGroups(offset: Int = 0, size: Int = 30): RepeatPage {
        val d = FnClient.get("/api/v1/repeat-photo/list", mapOf("offset" to offset, "size" to size)).dataObj()
        return RepeatPage(
            d?.optJSONArray("list")?.objs()?.map(::parseGroup).orEmpty(),
            d?.optInt("count") ?: 0,
            d?.str("strategy")?.ifEmpty { "similar" } ?: "similar",
        )
    }

    /** One group (null when it no longer exists): GET /api/v1/repeat-photo/group {groupId}. */
    suspend fun repeatGroup(groupId: String): RepeatGroup? = runCatching {
        FnClient.get("/api/v1/repeat-photo/group", mapOf("groupId" to groupId)).dataObj()?.let(::parseGroup)
    }.getOrNull()

    /** Start a scan: POST /api/v1/repeat-photo/check {checkSimilarity}. Progress shows in /api/v1/task-panel/list. */
    suspend fun repeatCheck(similarity: Boolean) {
        FnClient.post("/api/v1/repeat-photo/check", JSONObject().put("checkSimilarity", similarity))
    }

    /** Raw task panel (scan progress etc.): GET /api/v1/task-panel/list -> data.list. */
    suspend fun taskPanel(): List<JSONObject> = FnClient.get("/api/v1/task-panel/list").dataList().objs()

    /** Change the recommended keeper: POST /api/v1/repeat-photo/keep {photoId} (body ref-only). */
    suspend fun repeatKeep(photoId: Int) {
        FnClient.post("/api/v1/repeat-photo/keep", JSONObject().put("photoId", photoId))
    }

    /** Take photos out of their group ("keep both"): POST /api/v1/repeat-photo/exclude {type:"PhotoId", photoIds}. */
    suspend fun repeatExclude(photoIds: List<Int>) {
        FnClient.post("/api/v1/repeat-photo/exclude", JSONObject().put("type", "PhotoId").put("photoIds", ids(photoIds)))
    }

    /** Dismiss a whole group: POST /api/v1/repeat-photo/exclude {type:"Group", groupId}. */
    suspend fun repeatExcludeGroup(groupId: String) {
        FnClient.post("/api/v1/repeat-photo/exclude", JSONObject().put("type", "Group").put("groupId", jsonId(groupId)))
    }

    /** Merge (keep recommended, bin the rest): POST /api/v1/repeat-photo/merge {type:"Group",groupId} or {type:"All"}. */
    suspend fun repeatMerge(groupId: String? = null) {
        val b = if (groupId == null) JSONObject().put("type", "All") else JSONObject().put("type", "Group").put("groupId", jsonId(groupId))
        FnClient.post("/api/v1/repeat-photo/merge", b)
    }

    /** Visually similar photos: GET /api/v1/magic-search/similar {id, distance?, compareSelf:"1"}. */
    suspend fun similarSearch(photoId: Int, distance: String? = null): List<Photo> =
        FnClient.get("/api/v1/magic-search/similar", mapOf("id" to photoId, "distance" to distance, "compareSelf" to "1"))
            .dataList().objs().map(Photo::parse)

    private fun parseGroup(o: JSONObject) = RepeatGroup(
        o.str("id", "groupId"),
        o.optJSONArray("photos")?.objs()?.map(Photo::parse).orEmpty(),
        o.optJSONArray("datetimes")?.strs().orEmpty(),
        o.optInt("recommendedIndex"),
    )

    private fun jsonId(s: String): Any = s.toLongOrNull() ?: s

    // ============================================================ map

    /** Clustered points in a viewport: GET /api/v1/map/previewAllPoint {north,east,south,west,lonVert}. */
    suspend fun mapPoints(b: MapBounds): List<MapPoint> =
        FnClient.get("/api/v1/map/previewAllPoint", b.params()).dataList().objs().mapNotNull {
            val lat = it.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = (it.optString("lon").ifEmpty { it.optString("lng") }).toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val p = it.optJSONObject("poster")
            val id = p?.optInt("id") ?: 0; val uuid = p?.str("photoUUID").orEmpty()
            MapPoint(lat, lng, it.optInt("count"), id, uuid, thumb(id, uuid, "xs"), p?.str("dateTime").orEmpty())
        }

    /** Day buckets for an area: GET /api/v1/map/timeLine {bounds} (response assumed gallery-timeline shape // UNVERIFIED). */
    suspend fun mapTimeline(b: MapBounds): List<DayCount> =
        FnClient.get("/api/v1/map/timeLine", b.params()).dataList().objs().map {
            DayCount(it.optInt("year"), it.optInt("month"), it.optInt("day"), it.optInt("itemCount"))
        }

    /** Photos in an area: GET /api/v1/map/photoList {bounds..., startTime, endTime, offset, limit}. */
    suspend fun mapPhotos(b: MapBounds, offset: Int, limit: Int, start: String = Repo.FAR_START, end: String = Repo.FAR_END): Pair<List<Photo>, Boolean> {
        val p = b.params().apply { put("startTime", start); put("endTime", end); put("offset", offset); put("limit", limit) }
        val l = FnClient.get("/api/v1/map/photoList", p).dataList().objs().map(Photo::parse)
        return l to (l.size >= limit)
    }

    /** Initial map centre: GET /api/v1/map/preview/initCenter (raw data). */
    suspend fun mapCenter(): JSONObject? = runCatching { FnClient.get("/api/v1/map/preview/initCenter").dataObj() }.getOrNull()

    /** Place search on the map: GET /api/v1/map/searchLocation {keyword} (raw list). */
    suspend fun mapSearchLocation(keyword: String): List<JSONObject> =
        FnClient.get("/api/v1/map/searchLocation", mapOf("keyword" to keyword)).dataList().objs()

    // ============================================================ folders

    /** Sub-folders: GET /api/v1/folder_view/getFolderList {folderPath, desc, orderBy}. orderBy=2 default from ref. */
    suspend fun subFolders(path: String, desc: Boolean = false, orderBy: Int = 2): List<FolderEntry> =
        FnClient.get("/api/v1/folder_view/getFolderList", mapOf("folderPath" to path, "desc" to desc, "orderBy" to orderBy))
            .dataList().objs().map {
                val p = it.str("folderPath", "dirPath", "path")
                FolderEntry(p, it.str("name", "dirName").ifEmpty { p.trimEnd('/').substringAfterLast('/') }, it)
            }

    /** Media in a folder: GET /api/v1/folder_view/getFileList {folderPath, desc, orderBy, limit, offset} -> (list, total). */
    suspend fun folderPhotos(path: String, offset: Int, limit: Int = 100, desc: Boolean = false, orderBy: Int = 2): Pair<List<Photo>, Int> {
        val d = FnClient.get(
            "/api/v1/folder_view/getFileList",
            mapOf("folderPath" to path, "desc" to desc, "orderBy" to orderBy, "limit" to limit, "offset" to offset),
        ).dataObj()
        return d?.optJSONArray("list")?.objs()?.map(Photo::parse).orEmpty() to (d?.optInt("total") ?: 0)
    }

    /** Folder view in one call: sub-folders (first page only) + one page of media. */
    suspend fun browseFolder(path: String, offset: Int = 0, limit: Int = 100, desc: Boolean = false, orderBy: Int = 2): FolderPage {
        val folders = if (offset == 0) subFolders(path, desc, orderBy) else emptyList()
        val (photos, total) = folderPhotos(path, offset, limit, desc, orderBy)
        return FolderPage(folders, photos, total, offset + photos.size < total)
    }

    /** Folder covers: POST /api/v1/folder_view/cover — body shape // UNVERIFIED ({folderPaths}); returns raw list. */
    suspend fun folderCovers(paths: List<String>): List<JSONObject> =
        FnClient.post("/api/v1/folder_view/cover", JSONObject().put("folderPaths", JSONArray(paths))).dataList().objs()

    // ============================================================ search

    /** Filter options: GET /api/v1/search/filterlist {show_shooting_information} -> data.list[{filterName, filterValues, subFilter}]. */
    suspend fun searchFilterOptions(showShootingInfo: Boolean = true): List<SearchFilterOption> =
        FnClient.get("/api/v1/search/filterlist", mapOf("show_shooting_information" to if (showShootingInfo) "1" else "0"))
            .dataList().objs().map(::parseOption)

    /** Keyword -> suggested filters: GET /api/v1/search/suggest/filter {keyword, show_shooting_information:"1"}. */
    suspend fun suggestFilters(keyword: String): List<SearchFilter> =
        FnClient.get("/api/v1/search/suggest/filter", mapOf("keyword" to keyword, "show_shooting_information" to "1"))
            .dataList().objs().mapNotNull { node ->
                val vals = mutableListOf<String>()
                var n: JSONObject? = node
                while (n != null) {
                    val cur: JSONObject = n
                    val v = cur.str("filterBindingValue").ifEmpty { cur.str("filterValue") }
                    if (v.isEmpty()) break
                    vals += v
                    n = cur.optJSONArray("subFilters")?.optJSONObject(0)
                }
                val key = node.str("filterName")
                if (key.isEmpty() || vals.isEmpty()) null else SearchFilter(key, vals)
            }

    /** Filtered search: POST /api/v2/search/results {keyword, filters, antiFilters} -> data{list, timeline}. */
    suspend fun search(keyword: String, filters: List<SearchFilter>, antiFilters: List<SearchFilter> = emptyList()): SearchResult {
        val body = JSONObject()
            .put("keyword", keyword)
            .put("filters", JSONArray().apply { filters.mapNotNull { it.toJson() }.forEach { put(it) } })
            .put("antiFilters", JSONArray().apply { antiFilters.mapNotNull { it.toJson() }.forEach { put(it) } })
        val d = FnClient.post("/api/v2/search/results", body).dataObj()
        return SearchResult(
            d?.optJSONArray("list")?.objs()?.map(Photo::parse).orEmpty(),
            d?.optJSONArray("timeline")?.objs()?.map { DayCount(it.optInt("year"), it.optInt("month"), it.optInt("day"), it.optInt("itemCount")) }.orEmpty(),
        )
    }

    private fun parseOption(o: JSONObject): SearchFilterOption = SearchFilterOption(
        o.str("filterName"),
        o.optJSONArray("filterValues")?.strs().orEmpty(),
        o.optJSONArray("subFilter")?.objs()?.map(::parseOption).orEmpty(),
    )

    // ============================================================ batch collect

    /** Favourite / unfavourite many: POST /api/v1/preview/collect | /api/v1/preview/collect/cancel {ids}. */
    suspend fun collect(ids: List<Int>, on: Boolean) {
        if (ids.isEmpty()) return
        FnClient.post(if (on) "/api/v1/preview/collect" else "/api/v1/preview/collect/cancel", JSONObject().put("ids", ids(ids)))
    }

    // ============================================================ private plumbing

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Signed request for cases FnClient.get/post cannot express (e.g. POST with no body, signed over ""). */
    private suspend fun signedRaw(method: String, path: String, body: String?): JSONObject = withContext(Dispatchers.IO) {
        val full = "/p$path"
        val rb = Request.Builder().url(FnClient.baseUrl + full)
            .header("accesstoken", FnClient.token)
            .header("authx", AuthX.header(full, body ?: ""))
        val rbody = (body ?: "").toRequestBody(if (body != null) "application/json; charset=utf-8".toMediaType() else null)
        when (method) {
            "POST" -> rb.post(rbody)
            "PUT" -> rb.put(rbody)
            "DELETE" -> if (body != null) rb.delete(rbody) else rb.delete()
            else -> rb.get()
        }
        FnClient.http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrNull()
            val api = j?.optInt("code", 0) ?: 0
            if (r.code !in 200..299 || j == null) throw ApiException(r.code, "请求失败（HTTP ${r.code}）")
            if (api != 0) throw ApiException(api, j.str("msg").ifEmpty { "服务返回错误 $api" })
            j
        }
    }

    private suspend fun rawGet(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        uploadHttp.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "下载失败（HTTP ${r.code}）")
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun hmacB64(msg: String, keyB64: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.decode(keyB64, Base64.DEFAULT), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(msg.toByteArray()), Base64.NO_WRAP)
    }

    /** Same naming rule as the web worker: "<stem>_<id><.ext>", hidden with a leading dot in folder view. */
    private fun storedName(name: String, id: String, hidden: Boolean): String {
        val dot = name.lastIndexOf('.')
        val n = if (dot > 0) "${name.substring(0, dot)}_$id${name.substring(dot)}" else "${name}_$id"
        return if (hidden) ".$n" else n
    }

    private fun nanoId(len: Int = 21): String {
        val a = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
        return (1..len).map { a[rnd.nextInt(64)] }.joinToString("")
    }

    private fun utcExif(ms: Long): String =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** JavaScript encodeURI(): keeps A-Z a-z 0-9 ;,/?:@&=+$-_.!~*'()# and percent-encodes the rest as UTF-8. */
    internal fun encodeURI(s: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/?:@&=+$-_.!~*'()#"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c < 0x80 && keep.indexOf(c.toChar()) >= 0) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    /** Streams the file from [open] and reports progress; reopened if OkHttp needs to resend. */
    private class StreamBody(
        private val mime: String,
        private val size: Long,
        private val open: () -> InputStream,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        override fun contentLength(): Long = if (size >= 0) size else -1
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            var last = 0L
            open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 200 || sent == size) { last = now; onProgress(sent, size) }
                }
            }
        }
    }
}
