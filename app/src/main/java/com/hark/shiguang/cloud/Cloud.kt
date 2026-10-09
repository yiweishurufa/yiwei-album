package com.hark.shiguang.cloud

import com.hark.shiguang.data.FnClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ───────────────────────── Contract (see ARCH.md) ─────────────────────────

enum class CloudKind { WEBDAV }

data class CloudAccount(
    val id: String,
    val kind: CloudKind,
    val title: String,
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    val extra: Map<String, String> = emptyMap()   // tokens, client ids, root path...
)

data class CloudEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long /* epoch ms, 0 if unknown */,
    val thumbUrl: String?, /* null => use rawUrl for images */
    val isImage: Boolean,
    val isVideo: Boolean,
    val id: String = path /* provider file id */,
    /** 1.0.1: WebDAV getetag (or raw getlastmodified) — used for folders to skip unchanged ones on a rescan. */
    val tag: String? = null,
)

interface CloudSource {
    val account: CloudAccount
    /** validates login, returns account with refreshed tokens in extra */
    suspend fun connect(): CloudAccount
    /** root = "/" */
    suspend fun list(path: String): List<CloudEntry>
    /** streamable/downloadable URL */
    suspend fun rawUrl(e: CloudEntry): String
    /** headers needed to GET thumb/raw url (auth, UA, referer) */
    fun headers(url: String): Map<String, String>
    suspend fun upload(dir: String, name: String, size: Long, open: () -> InputStream)
    suspend fun delete(e: CloudEntry)
    suspend fun mkdir(parent: String, name: String)
}

object CloudSources {
    fun create(a: CloudAccount): CloudSource = when (a.kind) {
        CloudKind.WEBDAV -> WebDavSource(a)
    }
}

/**
 * Recursive media scan for timeline: walks folders breadth-first, emits media entries
 * (one batch per folder that contains media), stops after [maxItems] media or depth [CloudMedia.MAX_SCAN_DEPTH].
 * A failure on the root folder is thrown; failures on sub folders are skipped.
 */
suspend fun CloudSource.scanMedia(root: String = "/", maxItems: Int = 5000, onBatch: (List<CloudEntry>) -> Unit) {
    // Parallel breadth-first scan: some WebDAV servers (123pan) take ~10 s per PROPFIND,
    // so folders are listed [CloudMedia.SCAN_PARALLEL] at a time and each batch is emitted immediately.
    val rootItems = list(root)   // a failure on the root folder is thrown
    val emitted = java.util.concurrent.atomic.AtomicInteger(0)
    val lock = Any()
    fun handle(items: List<CloudEntry>, depth: Int, next: MutableList<String>) {
        val media = ArrayList<CloudEntry>()
        for (e in items) {
            if (e.isDir) {
                if (depth + 1 <= CloudMedia.MAX_SCAN_DEPTH && !e.name.startsWith(".")) next.add(e.path)
            } else if (e.isImage || e.isVideo) media.add(e)
        }
        if (media.isEmpty()) return
        synchronized(lock) {
            val room = maxItems - emitted.get()
            if (room <= 0) return
            val b = if (media.size > room) media.subList(0, room) else media
            emitted.addAndGet(b.size)
            onBatch(b)
        }
    }
    var level = ArrayList<String>()
    handle(rootItems, 0, level)
    var depth = 1
    var visited = 1
    val sem = kotlinx.coroutines.sync.Semaphore(CloudMedia.SCAN_PARALLEL)
    while (level.isNotEmpty() && emitted.get() < maxItems && visited < CloudMedia.MAX_SCAN_DIRS && depth <= CloudMedia.MAX_SCAN_DEPTH) {
        val dirs = level.take(CloudMedia.MAX_SCAN_DIRS - visited)
        visited += dirs.size
        val next = java.util.Collections.synchronizedList(ArrayList<String>())
        val d = depth
        kotlinx.coroutines.coroutineScope {
            dirs.forEach { dir ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    sem.acquire()
                    try {
                        if (emitted.get() < maxItems) {
                            val items = runCatching { list(dir) }.getOrDefault(emptyList())
                            val n = ArrayList<String>(); handle(items, d, n); next.addAll(n)
                        }
                    } finally { sem.release() }
                }
            }
        }
        level = ArrayList(next); depth++
    }
}

// ───────────────────────── Media type helpers ─────────────────────────

object CloudMedia {
    const val MAX_SCAN_DEPTH = 8
    const val MAX_SCAN_DIRS = 5000
    /** 1.0.1: 6 folders at a time (8 made some drives such as 123pan answer 429/503). */
    /** 1.0.2: latency-bound when the route is slow (VPN, no bypass) — more folders in flight hides the round trip. */
    val SCAN_PARALLEL: Int get() = if (com.hark.shiguang.NetEnv.anySlow) 12 else 6
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng")
    val VIDEO_EXT = setOf("mp4", "mov", "m4v", "mkv", "avi", "3gp", "webm")

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase(Locale.ROOT)
    }

    fun isImage(name: String): Boolean = ext(name) in IMAGE_EXT
    fun isVideo(name: String): Boolean = ext(name) in VIDEO_EXT

    fun mime(name: String): String = when (ext(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}

// ───────────────────────── Shared HTTP / path utils (internal) ─────────────────────────

internal object CloudHttp {
    val client: OkHttpClient by lazy {
        FnClient.http.newBuilder()
            // 1.0.1: keep-alive pool large enough for the parallel PROPFIND scan (+ thumbnails); OkHttp negotiates HTTP/2 via ALPN on https.
            .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    val FORM: MediaType = "application/x-www-form-urlencoded".toMediaType()
    val XML: MediaType = "application/xml; charset=utf-8".toMediaType()

    class Resp(val code: Int, val body: String)

    /** Blocking execute; call from Dispatchers.IO. Network failures become a short Chinese message. */
    fun exec(req: Request, client: OkHttpClient = this.client): Resp {
        try {
            client.newCall(req).execute().use { r ->
                return Resp(r.code, r.body?.string() ?: "")
            }
        } catch (e: java.net.UnknownHostException) {
            throw Exception("无法解析服务器地址")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("连接超时")
        } catch (e: java.net.ConnectException) {
            throw Exception("无法连接服务器")
        } catch (e: javax.net.ssl.SSLException) {
            throw Exception("SSL 连接失败")
        } catch (e: java.io.IOException) {
            throw Exception("网络错误：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun call(req: Request): Resp = withContext(Dispatchers.IO) { exec(req) }

    fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw Exception("服务器返回格式错误")
    }

    fun jsonBody(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON)

    fun formBody(fields: Map<String, String>): RequestBody =
        fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toRequestBody(FORM)

    /** URL-encode a single component (space -> %20). */
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Encode every segment of a "/"-separated path, keeping the slashes. */
    fun encPath(p: String): String = p.split('/').joinToString("/") { if (it.isEmpty()) it else enc(it) }

    /** Percent-decode without turning '+' into space. */
    fun pctDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    fun normalizeBase(url: String): String {
        var s = url.trim()
        if (s.isEmpty()) throw Exception("请填写服务器地址")
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }
}

internal object CloudPath {
    fun norm(p: String): String {
        var s = p.trim().replace('\\', '/')
        if (!s.startsWith("/")) s = "/$s"
        while (s.contains("//")) s = s.replace("//", "/")
        if (s.length > 1) s = s.trimEnd('/')
        return if (s.isEmpty()) "/" else s
    }

    fun join(dir: String, name: String): String {
        val d = norm(dir)
        return if (d == "/") "/$name" else "$d/$name"
    }

    fun parent(p: String): String {
        val s = norm(p)
        val i = s.lastIndexOf('/')
        return if (i <= 0) "/" else s.substring(0, i)
    }

    fun name(p: String): String = norm(p).substringAfterLast('/')
}

internal object CloudTime {
    private val HUMAN = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)?.*?\(UTC([+-]\d{2}):?(\d{2})\)""", RegexOption.IGNORE_CASE)
    private val PLAIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** RFC1123 (WebDAV getlastmodified). */
    fun rfc1123(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            iso(s)
        }
    }

    /** ISO-8601 / RFC3339 with offset, also the human form "Sun 2025-12-21 7:10 PM CST (UTC+08:00)". */
    fun iso(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        try {
            val v = OffsetDateTime.parse(t).toInstant().toEpochMilli()
            return if (v < 0) 0 else v   // Go zero time "0001-01-01T00:00:00Z"
        } catch (e: Exception) {
            // fall through to the human-readable form
        }
        val m = HUMAN.find(t) ?: return 0
        return try {
            val g = m.groupValues
            var hour = g[4].toInt()
            val ap = g[7].uppercase(Locale.ROOT)
            if (ap == "PM" && hour < 12) hour += 12
            if (ap == "AM" && hour == 12) hour = 0
            val sec = if (g[6].isEmpty()) 0 else g[6].toInt()
            val sign = if (g[8].startsWith("-")) -1 else 1
            val offH = g[8].trimStart('+', '-').toInt()
            val off = ZoneOffset.ofHoursMinutes(sign * offH, sign * g[9].toInt())
            OffsetDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), hour, g[5].toInt(), sec, 0, off)
                .toInstant().toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }

    /** "yyyy-MM-dd HH:mm:ss" interpreted at the given offset (123云盘 returns UTC+8 without zone). */
    fun plain(s: String?, offsetHours: Int = 8): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            java.time.LocalDateTime.parse(s.trim(), PLAIN).toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }
}

/** Streams the provider's InputStream as a request body; re-opens on retry. */
internal class StreamBody(
    private val type: MediaType?,
    private val size: Long,
    private val open: () -> InputStream
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = if (size >= 0) size else -1L
    override fun writeTo(sink: BufferedSink) {
        open().source().use { src -> sink.writeAll(src) }
    }
}

internal fun mediaTypeOf(name: String): MediaType? = CloudMedia.mime(name).toMediaTypeOrNull()

/** Tiny time-bounded cache for raw URLs (avoids hammering download-info APIs). */
internal class UrlCache(private val ttlMs: Long) {
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    fun get(k: String): String? {
        val v = map[k] ?: return null
        return if (System.currentTimeMillis() - v.second < ttlMs) v.first else {
            map.remove(k); null
        }
    }

    fun put(k: String, url: String) {
        map[k] = url to System.currentTimeMillis()
    }

    fun remove(k: String) {
        map.remove(k)
    }
}
