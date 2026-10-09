package com.hark.shiguang

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import com.hark.shiguang.data.FnClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 1.0.9 minimal DLNA / UPnP AV cast:
 *  SSDP M-SEARCH (MediaRenderer) → device description → AVTransport SetAVTransportURI / Play / Pause / Seek / Stop,
 *  GetPositionInfo for the remote. WebDAV / 飞牛 streams need auth headers a TV can't send, so [CastProxy] serves
 *  them on the phone's LAN IP and forwards Range requests with the headers. Not tested against real TVs.
 */
data class DlnaDevice(val name: String, val location: String, val avTransport: String, val rendering: String?)

object Dlna {
    private val http by lazy { FnClient.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }

    suspend fun discover(c: Context, timeoutMs: Int = 3500): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("yiwei-dlna")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true; s.soTimeout = 600; s.bind(InetSocketAddress(0))
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.0 YiWei/1.0\r\n\r\n".toByteArray()
                    repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it.trim() }
                }
            }
        } finally { runCatching { lock?.release() } }
        locations.map { async { runCatching { describe(it) }.getOrNull() } }.awaitAll().filterNotNull().distinctBy { it.avTransport }
    }

    private fun describe(location: String): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val name = Regex("<friendlyName>([^<]*)</friendlyName>").find(xml)?.groupValues?.get(1)?.trim() ?: "电视"
        fun control(type: String): String? {
            val svc = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.firstOrNull { it.contains(type) } ?: return null
            val ctl = Regex("<controlURL>([^<]*)</controlURL>").find(svc)?.groupValues?.get(1)?.trim() ?: return null
            val base = Regex("<URLBase>([^<]*)</URLBase>").find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: location
            return URL(URL(base), ctl).toString()
        }
        val av = control("AVTransport") ?: return null
        return DlnaDevice(name.replace("&amp;", "&"), location, av, control("RenderingControl"))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(url: String, service: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(url).header("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Exception("电视拒绝了 $action（HTTP ${r.code}）" + (Regex("<errorDescription>([^<]*)").find(t)?.groupValues?.get(1)?.let { "：$it" } ?: ""))
            t
        }
    }

    fun play(d: DlnaDevice, url: String, title: String, mime: String) {
        val meta = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\">${esc(url)}</res></item></DIDL-Lite>"
        runCatching { soap(d.avTransport, "AVTransport", "Stop", "<InstanceID>0</InstanceID>") }
        soap(d.avTransport, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>")
        soap(d.avTransport, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }
    fun resume(d: DlnaDevice) { soap(d.avTransport, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
    fun pause(d: DlnaDevice) { soap(d.avTransport, "AVTransport", "Pause", "<InstanceID>0</InstanceID>") }
    fun stop(d: DlnaDevice) { soap(d.avTransport, "AVTransport", "Stop", "<InstanceID>0</InstanceID>") }
    fun seek(d: DlnaDevice, ms: Long) {
        val t = ms.coerceAtLeast(0) / 1000
        soap(d.avTransport, "AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>%d:%02d:%02d</Target>".format(t / 3600, t / 60 % 60, t % 60))
    }
    /** (position, duration) in ms. */
    fun position(d: DlnaDevice): Pair<Long, Long> {
        val x = soap(d.avTransport, "AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>")
        fun t(tag: String) = Regex("<$tag>(\\d+):(\\d{2}):(\\d{2})").find(x)?.groupValues?.let { (it[1].toLong() * 3600 + it[2].toLong() * 60 + it[3].toLong()) * 1000 } ?: 0L
        return t("RelTime") to t("TrackDuration")
    }

    fun mimeOf(name: String) = when (NameParser.ext(name)) {
        "mkv" -> "video/x-matroska"; "mp4", "m4v" -> "video/mp4"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"; "webm" -> "video/webm"; "wmv" -> "video/x-ms-wmv"; "flv" -> "video/x-flv"; "rmvb" -> "application/vnd.rn-realmedia-vbr"
        else -> "video/*"
    }
}

/** Tiny HTTP server on the LAN IP that forwards GET/HEAD (with Range) to an authenticated upstream. */
object CastProxy {
    private var server: ServerSocket? = null
    private val routes = ConcurrentHashMap<String, Pair<String, Map<String, String>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { FnClient.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build() }

    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** Registers [url] (+ [headers]) and returns the LAN url a TV can open. */
    fun serve(url: String, headers: Map<String, String>, name: String): String {
        val ip = lanIp() ?: throw Exception("没连上局域网（Wi-Fi）")
        val s = server ?: ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")).also { server = it; loop(it) }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        routes[id] = url to headers
        val ext = NameParser.ext(name).ifEmpty { "mp4" }
        return "http://$ip:${s.localPort}/v/$id.$ext"
    }

    /** 1.0.10: same proxy on loopback, for the in-app libVLC fallback player (VLC can't send our auth headers itself). */
    fun serveLocal(url: String, headers: Map<String, String>, name: String): String {
        val s = server ?: ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")).also { server = it; loop(it) }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        routes[id] = url to headers
        val ext = NameParser.ext(name).ifEmpty { "mp4" }
        return "http://127.0.0.1:${s.localPort}/v/$id.$ext"
    }

    fun stop() { runCatching { server?.close() }; server = null; routes.clear() }

    private fun loop(ss: ServerSocket) = scope.launch {
        while (!ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: break
            launch { runCatching { handle(sock) }; runCatching { sock.close() } }
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val ins = BufferedInputStream(sock.getInputStream())
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = ins.read(); if (b < 0) return
            if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); sb.setLength(0); if (l.isEmpty()) break; lines += l } else sb.append(b.toChar())
            if (lines.size > 100) return
        }
        val first = lines.firstOrNull()?.split(' ') ?: return
        val method = first.getOrNull(0) ?: return
        val id = first.getOrNull(1)?.substringAfter("/v/")?.substringBefore('.') ?: ""
        val out = sock.getOutputStream()
        val route = routes[id] ?: return respond(out, "404 Not Found")
        val range = lines.firstOrNull { it.startsWith("range:", true) }?.substringAfter(':')?.trim()
        val rb = Request.Builder().url(route.first).apply { route.second.forEach { (k, v) -> header(k, v) }; if (range != null) header("Range", range) }
        if (method == "HEAD") rb.head()
        http.newCall(rb.build()).execute().use { r ->
            val h = StringBuilder("HTTP/1.1 ${r.code} ${r.message.ifEmpty { "OK" }}\r\n")
            listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified").forEach { k -> r.header(k)?.let { h.append("$k: $it\r\n") } }
            if (r.header("Accept-Ranges") == null) h.append("Accept-Ranges: bytes\r\n")
            h.append("transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\nConnection: close\r\n\r\n")
            out.write(h.toString().toByteArray())
            if (method != "HEAD") r.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    private fun respond(out: OutputStream, status: String) { out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
}

/** What is on the TV right now (one cast at a time). */
object CastState {
    var device by mutableStateOf<DlnaDevice?>(null)
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
}
