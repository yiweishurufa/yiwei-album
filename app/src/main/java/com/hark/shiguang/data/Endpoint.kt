package com.hark.shiguang.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.Diag
import com.hark.shiguang.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/** LAN / WAN address handling, port probing, latency and automatic switching. */
object Endpoint {
    private val probeClient by lazy {
        FnClient.http.newBuilder().connectTimeout(2500, TimeUnit.MILLISECONDS).readTimeout(3000, TimeUnit.MILLISECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    var active by mutableStateOf("")      // "内网" / "外网" / ""
    var latency by mutableLongStateOf(-1L) // ms of the last successful probe
    var online by mutableStateOf(true)

    private fun isIp(host: String) = host.matches(Regex("^[0-9.]+$")) || host.startsWith("[") || host == "localhost"
    fun isPrivate(url: String) = Regex("://(10|127|192\\.168|172\\.(1[6-9]|2[0-9]|3[01]))\\.").containsMatchIn(url) || url.contains("://localhost")

    /** Returns the final base url (scheme://host:port) that answered, or null. */
    private fun probe(base: String): String? = runCatching {
        probeClient.newCall(Request.Builder().url("$base/").get().build()).execute().use { r ->
            val u = r.request.url
            "${u.scheme}://${u.host}:${u.port}"
        }
    }.getOrNull()

    private fun clean(u: String) = u.replace(Regex(":443$"), "").let { if (it.startsWith("http://") && it.endsWith(":80")) it.removeSuffix(":80") else it }

    /**
     * Candidate base urls for what the user typed. [port] (may be blank) always wins over a port in the text.
     * Bare domain: https:443, https:5667, http:5666, http/https:8000, http:80. Bare IP: http:5666, https:5667.
     * A plain word without dots is treated as an FN ID → <id>.fnos.net (FN Connect relay; // UNVERIFIED domain).
     */
    fun candidates(input: String, port: String = ""): List<String> {
        var s = input.trim().trimEnd('/')
        val hadScheme = s.startsWith("http://") || s.startsWith("https://")
        if (!hadScheme) s = "http://$s"
        val scheme = s.substringBefore("://")
        var host = s.substringAfter("://").substringBefore("/")
        val hasPort = if (host.startsWith("[")) host.contains("]:") else host.contains(":")
        var typedPort = ""
        if (hasPort) { typedPort = host.substringAfterLast(":"); host = host.substringBeforeLast(":") }
        if (!host.contains('.') && !host.contains(':') && host != "localhost") host = "$host.fnos.net"
        val p = port.trim().ifEmpty { typedPort }
        return when {
            p.isNotEmpty() -> if (hadScheme) listOf("$scheme://$host:$p") else listOf("https://$host:$p", "http://$host:$p")
            isIp(host) -> if (hadScheme) listOf(if (scheme == "https") "https://$host:5667" else "http://$host:5666", "$scheme://$host:8000", "$scheme://$host") else listOf("http://$host:5666", "https://$host:5667", "http://$host:8000", "https://$host:8000")
            hadScheme -> listOf("$scheme://$host", if (scheme == "https") "https://$host:5667" else "http://$host:5666", "$scheme://$host:8000")
            else -> listOf("https://$host", "https://$host:5667", "http://$host:5666", "http://$host:8000", "https://$host:8000", "http://$host")
        }
    }

    /**
     * Login with auto-detected scheme/port: probes every candidate in parallel, then tries the
     * WebSocket login on reachable ones first (in priority order), then the rest. Stops at the first
     * success or at a real credential / 2FA error. Returns base url to token.
     */
    suspend fun loginAuto(input: String, port: String, u: String, p: String, otp: String): Pair<String, String> = coroutineScope {
        val cands = candidates(input, port)
        val probes = cands.map { c -> async(Dispatchers.IO) { val t0 = System.currentTimeMillis(); probe(c)?.let { clean(it) to (System.currentTimeMillis() - t0) } } }.awaitAll()
        val reachable = probes.filterNotNull().map { it.first }.distinct()
        cands.forEachIndexed { i, c -> Diag.log("NET", "probe $c -> ${probes[i]?.first ?: "fail"}") }
        val order = (reachable + cands).distinct()
        var last: Throwable? = null
        for (base in order) {
            val r = runCatching { kotlinx.coroutines.withTimeout(12_000) { FnClient.login(base, u, p, otp) } }
            r.onSuccess { tk -> latency = probes.filterNotNull().firstOrNull { it.first == base }?.second ?: latency; return@coroutineScope base to tk }
            val e = r.exceptionOrNull()!!
            Diag.log("LOGIN", "try $base -> ${e.message}")
            if (e is ApiException && e.code != -1) throw e   // NAS answered: wrong password / 2FA
            last = e
        }
        throw ApiException(-1, "连不上 NAS（已试 ${order.joinToString("、") { it.substringAfter("://") }}）。请检查域名，或在端口里手动填写外网端口。${last?.message?.let { "\n$it" } ?: ""}")
    }

    /** Turns what the user typed (+ optional port) into a reachable base url. */
    suspend fun resolve(input: String, port: String = ""): String = withContext(Dispatchers.IO) {
        val cands = candidates(input, port)
        for (c in cands) {
            val t0 = System.currentTimeMillis()
            val r = probe(c)
            Diag.log("NET", "probe $c -> ${r ?: "fail"}")
            if (r != null) { latency = System.currentTimeMillis() - t0; return@withContext clean(r) }
        }
        cands.first()
    }

    /** Measures latency to the current base url. */
    suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val b = FnClient.baseUrl
        if (b.isEmpty()) return@withContext -1L
        val t0 = System.currentTimeMillis()
        val ok = probe(b) != null
        online = ok
        (if (ok) System.currentTimeMillis() - t0 else -1L).also { latency = it }
    }

    // ------------------------------------------------------------ 1.0.0 speed test (内网 / 外网)

    data class Speed(val pingMs: Long, val mbps: Double, val bytes: Long, val secs: Double) {
        fun line(): String = "延迟 $pingMs ms" + if (mbps > 0) " · 下载 %.1f Mbps（%.1f 秒 %.1f MB）".format(java.util.Locale.US, mbps, secs, bytes / 1048576.0) else " · 下载测速没有可用文件"
    }

    private val speedClient by lazy { FnClient.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build() }

    /**
     * Latency = median of 3 GETs of "$base/"; throughput = ~3 s of repeated downloads of [samples] (photo thumbnail
     * urls of the current account, re-pointed at [base]; signed with accesstoken/authx — the signature only covers the
     * path, so it holds on either address) plus the fnOS web UI's own js/css bundles. Throws when the address does not answer.
     */
    suspend fun speedTest(base: String, samples: List<String>, onProgress: (Float) -> Unit = {}): Speed = withContext(Dispatchers.IO) {
        if (base.isBlank()) throw Exception("没有设置这个地址")
        val b = base.trimEnd('/')
        val pings = ArrayList<Long>()
        var html = ""
        var lastErr: String? = null
        repeat(3) { i ->
            val t0 = System.nanoTime()
            runCatching {
                probeClient.newCall(Request.Builder().url("$b/").header("Cache-Control", "no-cache").build()).execute().use { r ->
                    val body = r.body?.string().orEmpty(); if (i == 0) html = body
                }
                pings += (System.nanoTime() - t0) / 1_000_000
            }.onFailure { lastErr = it.message }
            onProgress((i + 1) / 10f)
        }
        if (pings.isEmpty()) throw Exception("连不上 ${b.substringAfter("://")}" + (lastErr?.let { "（$it）" } ?: ""))
        val ping = pings.sorted()[pings.size / 2]
        // download candidates
        val cur = FnClient.baseUrl.trimEnd('/')
        val urls = ArrayList<Pair<String, Boolean>>()   // url to needsAuth
        samples.take(24).forEach { u -> if (cur.isNotEmpty() && u.startsWith(cur)) urls += (b + u.removePrefix(cur)) to true else if (u.startsWith(b)) urls += u to true }
        Regex("(?:src|href)=\"(/[^\"]+\\.(?:js|css))\"").findAll(html).map { it.groupValues[1] }.distinct().take(8).forEach { urls += (b + it) to false }
        if (urls.isEmpty()) return@withContext Speed(ping, 0.0, 0, 0.0)
        val t0 = System.nanoTime()
        val limit = 3_000_000_000L
        var bytes = 0L; var i = 0; var fails = 0
        val buf = ByteArray(64 * 1024)
        while (System.nanoTime() - t0 < limit && fails < 6) {
            val (u, auth) = urls[i++ % urls.size]
            val ok = runCatching {
                val rb = Request.Builder().url(u).header("Cache-Control", "no-cache")
                if (auth) { com.hark.shiguang.data.NasX.streamHeaders(u).forEach { (k, v) -> rb.header(k, v) } }
                speedClient.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    r.body?.byteStream()?.use { ins -> while (true) { val n = ins.read(buf); if (n < 0) break; bytes += n
                        if (System.nanoTime() - t0 >= limit) break } }
                    true
                }
            }.getOrDefault(false)
            if (!ok) fails++
            onProgress(0.3f + 0.7f * ((System.nanoTime() - t0).toFloat() / limit).coerceAtMost(1f))
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val mbps = if (bytes > 0 && secs > 0) bytes * 8 / secs / 1e6 else 0.0
        Speed(ping, mbps, bytes, secs)
    }

    /** Remembers which address measured faster (auto mode uses it while both answer, for 12 h). */
    fun rememberFaster(lan: Speed?, wan: Speed?) {
        if (lan == null || wan == null) return
        val pick = when {
            lan.mbps > 0 && wan.mbps > 0 -> if (wan.mbps > lan.mbps * 1.3) "wan" else "lan"
            else -> if (wan.pingMs * 2 < lan.pingMs) "wan" else "lan"
        }
        Store.putStr("net.faster.${com.hark.shiguang.NasAccounts.currentId}", pick + "@" + System.currentTimeMillis())
    }

    private fun preferWan(): Boolean {
        val v = Store.getStr("net.faster.${com.hark.shiguang.NasAccounts.currentId}")
        val t = v.substringAfter("@", "0").toLongOrNull() ?: 0L
        return v.startsWith("wan") && System.currentTimeMillis() - t < 12 * 3600_000L
    }

    /** Applies the network mode (auto / lan / wan). Updates FnClient.baseUrl. */
    suspend fun autoSelect(): String? = coroutineScope {
        val lan = Store.lanUrl; val wan = Store.wanUrl
        val mode = Store.netMode
        val chosen: String? = when {
            mode == "lan" && lan.isNotEmpty() -> lan
            mode == "wan" && wan.isNotEmpty() -> wan
            lan.isNotEmpty() && wan.isNotEmpty() -> {
                val l = async(Dispatchers.IO) { probe(lan) }
                if (l.await() == null) wan
                else if (preferWan() && withContext(Dispatchers.IO) { probe(wan) } != null) wan   // last speed test: 外网 was faster
                else lan
            }
            else -> null
        }
        if (chosen == null) {
            active = if (FnClient.baseUrl.isNotEmpty()) (if (isPrivate(FnClient.baseUrl)) "内网" else "外网") else ""
            ping(); return@coroutineScope null
        }
        active = if (chosen == lan) "内网" else "外网"
        if (FnClient.baseUrl != chosen) {
            Diag.log("NET", "switch to $active")
            FnClient.baseUrl = chosen; Store.url = chosen
        }
        ping()
        chosen
    }

    /** Scans the phone's /24 LAN for hosts answering on 5666 (fnOS web port). */
    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val ips = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }
                .filter { isPrivate("http://$it") }
        }.getOrDefault(emptyList())
        val prefixes = ips.map { it.substringBeforeLast('.') }.distinct().take(2)
        prefixes.flatMap { pre ->
            (1..254).map { i ->
                async {
                    val h = "$pre.$i"
                    val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress(h, 5666), 350); true } }.getOrDefault(false)
                    if (ok) "http://$h:5666" else null
                }
            }
        }.awaitAll().filterNotNull()
    }
}
