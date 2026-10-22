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

// 1.1.0 拆分自 FnExtra.kt（只移动代码，行为不变）：飞牛扩展接口的数据模型。
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

/** Live/motion photo video source. [embedded]=true means the MP4 sits inside [url]'s bytes after [marker]. [verified]=false means the type → stream mapping was never confirmed against a real device (第 4 批：目前只有飞牛实况 type-1 走这条分支，见 [NasX.motionVideo])，调用方应在界面上标注「未验证」，不要宣称已验证。 */
data class MotionSource(val url: String, val headers: Map<String, String>, val embedded: Boolean, val marker: String? = null, val verified: Boolean = true)

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
