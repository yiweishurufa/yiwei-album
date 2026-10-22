package com.hark.shiguang.cloud

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * WebDAV (RFC 4918). account.url = server base incl. path (e.g. https://dav.example.com/remote.php/dav/files/me/),
 * user/pass = Basic auth (may be empty). CloudEntry.path is relative to the base, "/" = base.
 */
class WebDavSource(a: CloudAccount) : CloudSource {
    override var account: CloudAccount = a
        private set

    private val base: String = CloudHttp.normalizeBase(a.url).let { if (it.endsWith("/")) it else "$it/" }
    private val baseHost: String = base.toHttpUrlOrNull()?.host ?: ""
    /** Decoded base path with trailing slash, e.g. "/remote.php/dav/files/me/". */
    private val basePath: String = (base.toHttpUrlOrNull()?.encodedPath ?: "/").let {
        val d = CloudHttp.pctDecode(it)
        if (d.endsWith("/")) d else "$d/"
    }
    private val auth: String? =
        if (a.user.isEmpty() && a.pass.isEmpty()) null else Credentials.basic(a.user, a.pass, StandardCharsets.UTF_8)

    private fun urlOf(path: String, dir: Boolean): String {
        val p = CloudPath.norm(path)
        if (p == "/") return base
        val u = base + CloudHttp.encPath(p.trimStart('/'))
        return if (dir) "$u/" else u
    }

    private fun req(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        auth?.let { b.header("Authorization", it) }
        return b
    }

    private fun check(code: Int, what: String) {
        when {
            code in 200..299 -> return
            code == 401 -> throw Exception("WebDAV 用户名或密码错误")
            code == 403 -> throw Exception("WebDAV 无权限$what")
            code == 404 -> throw Exception("路径不存在")
            code == 405 -> throw Exception("服务器不支持该操作或目标已存在")
            code == 507 -> throw Exception("服务器空间不足")
            else -> throw Exception("WebDAV $what 失败（HTTP $code）")
        }
    }

    override suspend fun connect(): CloudAccount {
        val r = CloudHttp.call(propfind(base, "0"))
        if (r.code == 207 || r.code in 200..299) return account
        check(r.code, "连接")
        return account
    }

    private fun propfind(url: String, depth: String): Request = req(url)
        .header("Depth", depth)
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(CloudHttp.XML))
        .build()

    override suspend fun list(path: String): List<CloudEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { listBlocking(path) }

    /**
     * 1.0.1: PROPFIND Depth:1 parsed straight from the response stream (no String / DOM copy of big folders).
     * Network failures become the same short Chinese messages as [CloudHttp.exec].
     */
    fun listBlocking(path: String): List<CloudEntry> {
        val dir = CloudPath.norm(path)
        val rq = propfind(urlOf(dir, true), "1")
        try {
            CloudHttp.client.newCall(rq).execute().use { r ->
                if (r.code != 207 && r.code !in 200..299) check(r.code, "列目录")
                val ins = r.body?.byteStream() ?: return emptyList()
                return parseMultiStatus(ins, dir)
            }
        } catch (e: java.net.UnknownHostException) { throw Exception("无法解析服务器地址")
        } catch (e: java.net.SocketTimeoutException) { throw Exception("连接超时")
        } catch (e: java.net.ConnectException) { throw Exception("无法连接服务器")
        } catch (e: javax.net.ssl.SSLException) { throw Exception(com.hark.shiguang.TlsTrust.SSL_HINT)
        } catch (e: java.io.IOException) { throw Exception("网络错误：" + (e.message ?: e.javaClass.simpleName)) }
    }

    private class Item {
        var href: String = ""
        var isDir = false
        var size = 0L
        var modified = 0L
        var type: String? = null
        var tag: String? = null
    }

    internal fun parseMultiStatus(xml: String, dir: String): List<CloudEntry> = parseMultiStatus(xml.byteInputStream(), dir)

    internal fun parseMultiStatus(input: InputStream, dir: String): List<CloudEntry> {
        val items = ArrayList<Item>()
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = true
        val p = f.newPullParser()
        try {
            p.setInput(input, null)
            var cur: Item? = null
            // values collected inside the current <propstat>, applied only if its status is 2xx
            var psDir = false
            var psSize = -1L
            var psMod = 0L
            var psType: String? = null
            var psTag: String? = null
            var psModRaw: String? = null
            // S3: a propstat WITHOUT a <status> is not applied (a missing status used to default to true,
            // so a 404 propstat's stale values could overwrite the good ones from another propstat).
            var psOk = false
            var psHasStatus = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name.lowercase()) {
                        "response" -> cur = Item()
                        "href" -> if (cur != null && cur.href.isEmpty()) cur.href = p.nextText().trim()
                        "propstat" -> {
                            psDir = false; psSize = -1L; psMod = 0L; psType = null; psTag = null; psModRaw = null; psOk = false; psHasStatus = false
                        }
                        "status" -> {
                            val s = p.nextText()
                            psHasStatus = true
                            val code = Regex("""\s(\d{3})""").find(s)?.groupValues?.get(1)?.toIntOrNull()
                            psOk = code in 200..299
                        }
                        "collection" -> psDir = true
                        "getcontentlength" -> psSize = p.nextText().trim().toLongOrNull() ?: -1L
                        "getlastmodified" -> { val t = p.nextText().trim(); psModRaw = t; psMod = CloudTime.rfc1123(t) }
                        "getetag" -> psTag = p.nextText().trim().trim('"').ifEmpty { null }
                        "getcontenttype" -> psType = p.nextText().trim().ifEmpty { null }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name.lowercase()) {
                        "propstat" -> cur?.let { c ->
                            if (psHasStatus && psOk) {
                                if (psDir) c.isDir = true
                                if (psSize >= 0) c.size = psSize
                                if (psMod > 0) c.modified = psMod
                                if (psType != null) c.type = psType
                                // S3: tags carry their source — an etag and a lastmodified that happen to
                                // look alike must never compare equal across scans.
                                (psTag?.let { "etag:$it" } ?: psModRaw?.ifEmpty { null }?.let { "lastmodified:$it" })?.let { c.tag = it }
                            }
                        }
                        "response" -> {
                            cur?.let { if (it.href.isNotEmpty()) items.add(it) }
                            cur = null
                        }
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            throw Exception("WebDAV 响应解析失败")
        }

        val out = ArrayList<CloudEntry>()
        val self = CloudPath.norm(dir)
        for (item in items) {
            val hrefDir = item.href.endsWith("/")
            val rel = relPath(item.href, self) ?: continue
            if (rel == self) continue          // the folder itself
            val name = CloudPath.name(rel)
            if (name.isEmpty()) continue
            val isDir = item.isDir || (hrefDir && item.type == null && item.size == 0L)
            val t = item.type?.lowercase() ?: ""
            val img = !isDir && (CloudMedia.isImage(name) || (t.startsWith("image/") && !t.contains("svg")))
            val vid = !isDir && !img && (CloudMedia.isVideo(name) || t.startsWith("video/"))
            out.add(
                CloudEntry(
                    name = name, path = rel, isDir = isDir, size = if (isDir) 0L else item.size,
                    modified = item.modified, thumbUrl = null, isImage = img, isVideo = vid, tag = item.tag
                )
            )
        }
        return out.sortedWith(compareBy<CloudEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** href (absolute URL or absolute path, percent-encoded) -> path relative to base ("/x/y"). */
    private fun relPath(href: String, listedDir: String): String? {
        var raw = href
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw = raw.toHttpUrlOrNull()?.encodedPath ?: return null
        } else {
            raw = raw.substringBefore('?').substringBefore('#')
        }
        val decoded = CloudHttp.pctDecode(raw)
        // B21: longest match of the fully decoded path against basePath; a double-encoded href gets one
        // more decode attempt before it is treated as a mismatch.
        val effective = if (decoded.startsWith(basePath)) decoded
            else CloudHttp.pctDecode(decoded).takeIf { it.startsWith(basePath) } ?: decoded
        val noSlash = effective.trimEnd('/')
        return when {
            (noSlash + "/") == basePath || noSlash.isEmpty() && basePath == "/" -> "/"
            effective.startsWith(basePath) -> CloudPath.norm("/" + effective.substring(basePath.length))
            !effective.startsWith("/") -> CloudPath.join(listedDir, noSlash)  // relative href
            else -> {
                // B21: a href that does not match the base path is DROPPED (never guessed from the last
                // segment — that could map a file into the wrong folder). The drop is in the diag log.
                com.hark.shiguang.Diag.log("WEBDAV", "href prefix mismatch, dropped: $href (base=$basePath dir=$listedDir)")
                null
            }
        }
    }

    override suspend fun rawUrl(e: CloudEntry): String = urlOf(e.path, false)

    /** Same as [rawUrl] without suspending (WebDAV urls are built locally, no network). */
    fun fileUrl(path: String): String = urlOf(path, false)

    override fun headers(url: String): Map<String, String> {
        val a = auth ?: return emptyMap()
        val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
        return if (host.equals(baseHost, true)) mapOf("Authorization" to a) else emptyMap()
    }

    override suspend fun upload(dir: String, name: String, size: Long, open: () -> InputStream) {
        val url = urlOf(CloudPath.join(dir, name), false)
        val r = CloudHttp.call(req(url).put(StreamBody(mediaTypeOf(name), size, open)).build())
        check(r.code, "上传")
    }

    override suspend fun delete(e: CloudEntry) {
        val r = CloudHttp.call(req(urlOf(e.path, e.isDir)).delete().build())
        if (r.code == 404) return
        check(r.code, "删除")
    }

    override suspend fun mkdir(parent: String, name: String) {
        val r = CloudHttp.call(req(urlOf(CloudPath.join(parent, name), true)).method("MKCOL", null).build())
        if (r.code == 405) throw Exception("文件夹已存在")
        check(r.code, "新建文件夹")
    }

    /** Reads a small text file; null when it does not exist. */
    suspend fun readText(path: String): String? {
        val r = CloudHttp.call(req(urlOf(path, false)).get().build())
        if (r.code == 404) return null
        check(r.code, "读取")
        return r.body
    }

    /** Creates or overwrites a small text file (PUT). */
    suspend fun writeText(path: String, text: String) {
        val r = CloudHttp.call(req(urlOf(path, false)).put(text.toByteArray().toRequestBody(CloudHttp.JSON)).build())
        check(r.code, "保存")
    }

    /** First [n] bytes of a file (HTTP Range; follows the CDN redirect). */
    fun headBytes(path: String, n: Int = 131072): ByteArray? = runCatching {
        val rq = req(urlOf(path, false)).header("Range", "bytes=0-${n - 1}").get().build()
        CloudHttp.client.newCall(rq).execute().use { r ->
            if (!r.isSuccessful) return null
            // S2: a server that ignores Range answers 200 with the whole file — only trust that when the
            // whole file fits in the head; otherwise require 206 + a Content-Range starting at byte 0.
            if (r.code == 200) {
                val len = r.body?.contentLength() ?: -1L
                if (len < 0 || len > n) return null
            } else if (r.code != 206) return null
            else if (!r.header("Content-Range").orEmpty().startsWith("bytes 0-")) return null
            val ins = r.body?.byteStream() ?: return null
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var t = 0
            while (t < n) { val k = ins.read(buf); if (k < 0) break; out.write(buf, 0, minOf(k, n - t)); t += k }
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * B6: stream a byte range into memory with a hard cap. The old code trusted Content-Length
     * (len == -1 bypassed the check) and then called `body.bytes()` unbounded — a lying / chunked
     * server could OOM the app. Now the stream is copied with a running total and aborted past [max].
     */
    private fun readCapped(ins: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0L
        while (true) {
            val k = ins.read(buf)
            if (k < 0) break
            total += k
            if (total > max) return null
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    /** 1.0.1: bytes [from]..end of a file (HTTP Range "bytes=from-"), e.g. the MP4 at the end of a Motion Photo. Null when the server ignores Range, or the tail exceeds [max]. */
    fun tailBytes(path: String, from: Long, max: Int = 64 * 1024 * 1024): ByteArray? = runCatching {
        val rq = req(urlOf(path, false)).header("Range", "bytes=$from-").get().build()
        CloudHttp.client.newCall(rq).execute().use { r ->
            if (r.code != 206) return@runCatching null
            val body = r.body ?: return@runCatching null
            // B6: Content-Length is advisory only (chunked answers -1); the stream itself is capped.
            if (body.contentLength() > max) return@runCatching null
            body.byteStream().use { readCapped(it, max.toLong()) }
        }
    }.getOrNull()

    /**
     * B6: whole file bytes, capped at [maxBytes] (streamed with a running total, never `body.bytes()`).
     * Callers must pass an explicit cap for their use; null = unreadable, over the cap, or failed.
     */
    fun allBytes(path: String, maxBytes: Long = 64L * 1024 * 1024): ByteArray? = runCatching {
        CloudHttp.client.newCall(req(urlOf(path, false)).get().build()).execute().use { r ->
            if (!r.isSuccessful) { lastError = "HTTP ${r.code}"; return@runCatching null }
            val body = r.body ?: return@runCatching null
            if (body.contentLength() > maxBytes) { lastError = "文件超过上限"; return@runCatching null }
            val bytes = body.byteStream().use { readCapped(it, maxBytes) }
            if (bytes == null) lastError = "文件超过上限"
            else lastError = ""
            bytes
        }
    }.onFailure { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": " + m.take(60) } ?: "") }.getOrNull()

    /** Why the last [allBytes] returned null (HTTP code or exception), for the AI tab's pause note. */
    @Volatile var lastError: String = ""

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:resourcetype/><d:getcontentlength/>" +
                "<d:getlastmodified/><d:getcontenttype/><d:getetag/>" +
                "</d:prop></d:propfind>"
    }
}
