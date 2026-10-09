package com.hark.shiguang

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * 1.0.2 #2 网络环境自适应 (China-first, the mirror image of [TmdbNet]).
 *
 * Problem: with a VPN / 翻墙 app on, domestic services (123 云盘 WebDAV, 坚果云, the 飞牛 NAS, …) are routed abroad and back,
 * e.g. 6 s for one WebDAV request. What we do:
 *  1. Detect the environment: VPN transport on the default network, a system HTTP proxy, and the real underlying
 *     Wi-Fi / cellular network.
 *  2. For every host our photo / NAS clients talk to, measure the round trip both ways (through the VPN as the system
 *     routes it, and bound to the underlying network) and remember the faster one. "Bypass" works only when the VPN app
 *     allows it (Android VpnService.allowBypass, which Clash / v2rayNG / sing-box style apps normally allow); when it
 *     does not, binding fails with EPERM and we stay on the VPN route.
 *  3. All app HTTP clients (FnClient.http and everything derived from it, Coil, DavThumb) use [dns] + [socketFactory],
 *     which bind sockets for bypass hosts to the underlying network. Nothing changes when no VPN is on.
 *  4. When a host stays slow (no bypass possible), [slow] tells callers to be patient (longer timeouts, fewer retries) and
 *     the UI shows a hint: set the host to DIRECT in the proxy app or exclude 一维相册 (分应用代理).
 * Re-evaluated on every network change; per-host results expire after 30 min.
 */
object NetEnv {
    data class Probe(val host: String, val viaVpnMs: Long, val directMs: Long, val bypass: Boolean, val at: Long)

    var vpn by mutableStateOf(false)
    var proxy by mutableStateOf(false)
    /** short UI line, e.g. 「检测到 VPN · webdav.123pan.cn 已绕过直连 85 ms」 */
    var status by mutableStateOf("")
    var version by mutableStateOf(0)

    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var underlying: Network? = null
    private val probes = ConcurrentHashMap<String, Probe>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** IPs resolved for bypass hosts -> connect() binds these to [underlying]. */
    private val bypassIps = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "netenv").apply { isDaemon = true } }
    private const val TTL = 30 * 60_000L
    private const val SLOW_MS = 1500L

    fun start(ctx: Context) {
        val c = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        cm = c
        refreshEnv()
        runCatching {
            c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onChange() }
                override fun onLost(network: Network) { onChange() }
            })
        }
    }

    private fun onChange() {
        val was = vpn to underlying
        refreshEnv()
        if (was != (vpn to underlying)) { probes.clear(); bypassIps.clear(); version++; status = summary() }
    }

    @Suppress("DEPRECATION")
    private fun refreshEnv() {
        val c = cm ?: return
        val caps = runCatching { c.getNetworkCapabilities(c.activeNetwork) }.getOrNull()
        vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        proxy = !System.getProperty("http.proxyHost").isNullOrEmpty() || runCatching { c.defaultProxy != null }.getOrDefault(false)
        underlying = if (!vpn) null else runCatching {
            c.allNetworks.firstOrNull { n ->
                val nc = c.getNetworkCapabilities(n) ?: return@firstOrNull false
                !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        }.getOrNull()
        status = summary()
    }

    fun summary(): String = when {
        !vpn && !proxy -> ""
        probes.isEmpty() -> if (vpn) "检测到 VPN，国内网盘会自动尝试绕过直连" else "检测到系统代理"
        else -> {
            val by = probes.values.filter { it.bypass }; val slow = probes.values.filter { !it.bypass && it.viaVpnMs >= SLOW_MS }
            listOfNotNull(
                if (vpn) "检测到 VPN" else "检测到系统代理",
                by.takeIf { it.isNotEmpty() }?.let { l -> l.joinToString("、") { it.host } + " 已绕过直连 ${l.minOf { it.directMs }} ms" },
                slow.takeIf { it.isNotEmpty() }?.let { l -> l.joinToString("、") { it.host } + " 无法绕过（${l.maxOf { it.viaVpnMs }} ms），建议在代理软件里设为直连" },
            ).joinToString(" · ")
        }
    }

    /** true when requests to [url]'s host are slow and cannot be bypassed: callers raise timeouts, lower retry pressure. */
    fun slow(url: String): Boolean = hostOf(url)?.let { h -> probes[h]?.let { !it.bypass && it.viaVpnMs >= SLOW_MS } } ?: false

    /** Some photo/NAS host is slow and cannot be bypassed: scanners raise parallelism (latency-bound work). */
    val anySlow: Boolean get() = probes.values.any { !it.bypass && it.viaVpnMs >= SLOW_MS }

    /** Last measured RTT for the route actually used, or -1. */
    fun rtt(url: String): Long = hostOf(url)?.let { h -> probes[h]?.let { if (it.bypass) it.directMs else it.viaVpnMs } } ?: -1

    fun bypassing(url: String): Boolean = hostOf(url)?.let { probes[it]?.bypass } ?: false

    /** Hint for the connection test / settings, or "" when nothing to say. */
    fun hint(url: String): String {
        val h = hostOf(url) ?: return ""
        val p = probes[h] ?: return if (vpn) "检测到 VPN" else ""
        return when {
            p.bypass -> "检测到 VPN，已自动绕过直连（${p.directMs} ms，经 VPN ${if (p.viaVpnMs < 0) "超时" else "${p.viaVpnMs} ms"}）"
            p.viaVpnMs >= SLOW_MS -> "检测到 VPN，延迟高且 VPN 不允许绕过。请在代理软件里把 $h 设为直连，或把一维相册加入分应用代理的绕过列表"
            else -> "检测到 VPN，经 VPN 访问 ${p.viaVpnMs} ms"
        }
    }

    /** Ask for a measurement of [url]'s host (async, cached). Call when an account is used / tested. */
    fun prime(url: String) {
        val h = hostOf(url) ?: return
        if (!vpn) return
        val p = probes[h]
        if (p != null && System.currentTimeMillis() - p.at < TTL) return
        if (!inFlight.add(h)) return
        pool.execute { try { measure(url, h) } finally { inFlight.remove(h) } }
    }

    /** Blocking variant for the connection test (≤ ~8 s). */
    fun measureNow(url: String): Probe? { val h = hostOf(url) ?: return null; if (!vpn) return null; return measure(url, h) }

    private fun measure(url: String, host: String): Probe {
        val base = url.substringBefore('?').let { if (it.count { c -> c == '/' } >= 3) it else "$it/" }
        val via = time(base, null)
        val net = underlying
        val direct = if (net != null) time(base, net) else -1
        // bypass when the direct route answers and is clearly better (or the VPN route failed)
        val bypass = direct >= 0 && (via < 0 || direct * 1.3 + 50 < via)
        val p = Probe(host, via, direct, bypass, System.currentTimeMillis())
        probes[host] = p
        if (bypass && net != null) runCatching { net.getAllByName(host).forEach { bypassIps.add(it.hostAddress ?: "") } }
        else runCatching { InetAddress.getAllByName(host).forEach { bypassIps.remove(it.hostAddress ?: "") } }
        version++; status = summary()
        return p
    }

    /** Time to first response byte of a HEAD (any HTTP status counts as an answer), -1 on failure. Best of 2. */
    private fun time(url: String, net: Network?): Long {
        val b = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).retryOnConnectionFailure(false)
        if (net != null) b.socketFactory(net.socketFactory).dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = net.getAllByName(hostname).toList() })
        val c = b.build()
        var best = -1L
        repeat(2) {
            val t0 = System.nanoTime()
            val ok = runCatching { c.newCall(Request.Builder().url(url).head().build()).execute().use { true } }.getOrDefault(false)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ok && (best < 0 || ms < best)) best = ms
        }
        c.connectionPool.evictAll()
        return best
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ plug-ins for OkHttp

    /** Resolves bypass hosts through the underlying network's DNS (domestic DNS gives domestic CDN nodes). */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val net = underlying
            if (vpn && net != null && probes[hostname.lowercase()]?.bypass == true) {
                runCatching { net.getAllByName(hostname).toList() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { l ->
                    l.forEach { bypassIps.add(it.hostAddress ?: "") }; return l
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Plain sockets that bind themselves to the underlying network when connecting to a bypass IP. */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = BypassSocket()
        override fun createSocket(host: String, port: Int): Socket = BypassSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            BypassSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = BypassSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            BypassSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    private class BypassSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val ip = (endpoint as? InetSocketAddress)?.address?.hostAddress
            val net = underlying
            if (vpn && net != null && ip != null && ip in bypassIps) runCatching { net.bindSocket(this) }
            super.connect(endpoint, timeout)
        }
    }

    /** Apply to any OkHttp builder used for domestic services. */
    fun OkHttpClient.Builder.adaptive(): OkHttpClient.Builder = dns(dns).socketFactory(socketFactory)
}
