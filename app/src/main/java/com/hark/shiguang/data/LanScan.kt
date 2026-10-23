package com.hark.shiguang.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 用户 2026-10-23 反馈 #1：登录页「搜索局域网里的飞牛」搜不到。
 *
 * 旧实现（5e545dc）的问题（静态分析，未真机复现）：
 *  - socket 无条件绑到 Wi-Fi 网络。开着「不允许绕过」的 VPN 时 bindSocket 报 EPERM，254 个 connect 全部失败 → 永远 0 台；
 *  - 5666 回 3xx 跳到 https:5667（开了「强制 HTTPS」）时 OkHttp 跟随跳转，飞牛自签名证书校验失败 → 判成不是飞牛；
 *  - 只扫 /24、TCP 超时 350 ms（Wi-Fi 省电时首包 ARP 常超过）、只认 5666 端口、没有 mDNS；
 *  - 搜不到只有一句固定提示，没有原因，也没有日志能看出断在哪一段。
 * 现在：
 *  1. 网段取自 ConnectivityManager 的 Wi-Fi/以太网 LinkProperties（带真实前缀长度），拿不到再退回 NetworkInterface；
 *  2. 先试绑定 Wi-Fi 网络；VPN 不允许绕过就退回系统默认路由（后面有 HTTP 特征校验，不会再把整段 IP 当成飞牛）；
 *  3. 并行：mDNS 查询（持 MulticastLock，QU 单播回复）+ 子网扫 5666/5667（TCP 700 ms，并发 48）；
 *  4. 对有应答的主机 HTTP 校验：不自动跟随跳转；跳到 https 时用仅用于识别的宽松 TLS 再看页面和证书里的 fnOS 特征；
 *  5. [phase]/[done]/[total] 给界面显示进度，[lastReason] 给搜不到时的原因；每段写 Diag「LAN」。
 */
object LanScan {
    var phase by mutableStateOf("")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var lastReason by mutableStateOf("")

    private val SWEEP_PORTS = listOf(5666, 5667)
    /** mDNS 找到的主机多试几个常见端口（改过端口的、只开 80/443 重定向的）。 */
    private val MDNS_PORTS = listOf(5666, 5667, 8000, 80, 443)
    private const val CONNECT_MS = 700
    private const val PARALLEL = 48
    @Volatile private var verbose = true

    private data class Subnet(val ip: String, val prefix: Int, val iface: String)

    @Suppress("DEPRECATION")
    private fun lanNetworks(cm: ConnectivityManager?): List<Network> = runCatching {
        cm?.allNetworks?.filter { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@filter false
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.orEmpty()
    }.getOrDefault(emptyList())

    private val NOT_LAN = listOf("tun", "ppp", "ipsec", "rmnet", "ccmni", "clat", "v4-", "dummy", "veth", "docker", "wg", "utun", "lo")

    private fun subnets(cm: ConnectivityManager?, nets: List<Network>): List<Subnet> {
        val fromCm = nets.flatMap { n ->
            val lp = runCatching { cm?.getLinkProperties(n) }.getOrNull() ?: return@flatMap emptyList()
            lp.linkAddresses.mapNotNull { la ->
                val a = la.address as? Inet4Address ?: return@mapNotNull null
                val ip = a.hostAddress ?: return@mapNotNull null
                if (LanLogic.isPrivate(ip)) Subnet(ip, la.prefixLength, lp.interfaceName.orEmpty()) else null
            }
        }
        if (fromCm.isNotEmpty()) return fromCm.distinctBy { it.ip }.take(2)
        // 退路：没有 ACCESS_NETWORK_STATE 结果时按网卡名过滤（不知道前缀，按 /24）
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && NOT_LAN.none { p -> it.name.lowercase().startsWith(p) } }
                .flatMap { nif -> nif.interfaceAddresses.mapNotNull { ia -> (ia.address as? Inet4Address)?.hostAddress?.takeIf { LanLogic.isPrivate(it) }?.let { Subnet(it, ia.networkPrefixLength.toInt().takeIf { p -> p in 8..30 } ?: 24, nif.name) } } }
        }.getOrDefault(emptyList()).distinctBy { it.ip }.take(2)
    }

    private fun canBind(net: Network?): Boolean = net != null && runCatching { Socket().use { net.bindSocket(it) }; true }.getOrElse {
        Diag.log("LAN", "bind to Wi-Fi failed (${it.javaClass.simpleName}: ${it.message}) → scan via default route"); false
    }

    /** 主入口；返回找到的飞牛（按地址排序），搜不到时 [lastReason] 有原因。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun run(ctx: Context): List<Endpoint.Found> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        lastReason = ""; done = 0; total = 0; phase = "准备…"
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val nets = lanNetworks(cm)
            val vpn = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }.getOrDefault(false)
            val subs = subnets(cm, nets)
            val net = nets.firstOrNull()
            val bind = canBind(net)
            val sf: SocketFactory = if (bind) net!!.socketFactory else SocketFactory.getDefault()
            Diag.log("LAN", "start: subnets=${subs.joinToString { "${it.iface}:${mask(it.ip)}/${it.prefix}" }} vpn=$vpn wifiNet=${net != null} bind=$bind")
            val subnetLabel = subs.firstOrNull()?.let { it.ip.substringBeforeLast('.') + ".x" } ?: "局域网"
            if (subs.isEmpty()) {
                lastReason = LanLogic.reason(LanLogic.Stats(0, subnetLabel, vpn, !bind, 0, 0, 0))
                Diag.log("LAN", "no Wi-Fi/Ethernet IPv4 → stop"); return@withContext emptyList()
            }
            val hosts = subs.flatMap { LanLogic.hosts(it.ip, it.prefix) }.distinct()
            val io = Dispatchers.IO.limitedParallelism(PARALLEL)
            total = hosts.size * SWEEP_PORTS.size; phase = "正在扫描 $subnetLabel"
            val counter = AtomicInteger()
            val (mdns, open) = coroutineScope {
                val m = async(Dispatchers.IO) { mdnsHosts(ctx, if (bind) net else null) }
                val o = hosts.flatMap { h -> SWEEP_PORTS.map { p -> h to p } }.map { (h, p) ->
                    async(io) {
                        val ok = tcpOpen(sf, h, p)
                        done = counter.incrementAndGet()
                        if (ok) h else null
                    }
                }.awaitAll().filterNotNull().distinct()
                m.await() to o
            }
            Diag.log("LAN", "sweep: ${hosts.size} hosts × ${SWEEP_PORTS} → ${open.size} open (${System.currentTimeMillis() - t0} ms); mDNS → ${mdns.size} ${mdns.entries.joinToString { mask(it.key) + "=" + it.value }}")
            if (open.size > 40) Diag.log("LAN", "suspicious: most of the subnet answers (VPN?) — HTTP check decides")

            // HTTP 校验：mDNS 主机多试几个端口；其余只试默认端口
            val targets = (mdns.keys.map { it to MDNS_PORTS } + open.filter { it !in mdns }.map { it to SWEEP_PORTS }).distinctBy { it.first }
            phase = "正在确认哪台是飞牛"; done = 0; total = targets.size
            val c2 = AtomicInteger()
            verbose = targets.size <= 40   // VPN 接管时几百台都「有应答」，不逐台写日志
            val plain = baseClient(sf)
            val loose = looseTls(plain)
            val found = targets.map { (h, ports) ->
                async(io) { fnosCheck(plain, loose, h, ports, mdns[h].orEmpty()).also { done = c2.incrementAndGet() } }
            }.awaitAll().filterNotNull().distinctBy { it.host }.sortedBy { LanLogic.ipToInt(it.host)?.toLong()?.and(0xffffffffL) ?: 0L }
            Diag.log("LAN", "done: ${found.size} fnOS of ${targets.size} probed in ${System.currentTimeMillis() - t0} ms")
            if (found.isEmpty()) {
                lastReason = LanLogic.reason(LanLogic.Stats(subs.size, subnetLabel, vpn, !bind, open.size, mdns.size, targets.size))
                Diag.log("LAN", "none found: $lastReason")
            }
            found
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) {
            Diag.e("LAN", e)
            lastReason = "搜索出错：${e.message ?: e.javaClass.simpleName}。可以手动输入地址。"
            emptyList()
        } finally { phase = "" }
    }

    /** 日志里只留最后一段（同一网段排查够用，不写完整内网地址）。 */
    private fun mask(ip: String) = "x." + ip.substringAfterLast('.')

    private fun tcpOpen(sf: SocketFactory, h: String, port: Int): Boolean = try {
        sf.createSocket().use { s -> s.connect(InetSocketAddress(h, port), CONNECT_MS); true }
    } catch (_: SocketTimeoutException) { false } catch (_: Exception) { false }

    /** mDNS：发一次 PTR 查询（QU 单播回复），收 2.5 s，返回 ip → 主机名（可能为空）。 */
    private fun mdnsHosts(ctx: Context, net: Network?): Map<String, String> {
        val wifi = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java) }.getOrNull()
        val lock = runCatching { wifi?.createMulticastLock("yiwei-lan")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val out = LinkedHashMap<String, String>()
        try {
            DatagramSocket().use { s ->
                if (net != null) runCatching { net.bindSocket(s) }
                s.soTimeout = 300
                val q = LanLogic.mdnsQuery()
                val group = InetAddress.getByName("224.0.0.251")
                repeat(2) { runCatching { s.send(DatagramPacket(q, q.size, group, 5353)) } }
                val buf = ByteArray(9000)
                val end = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val src = (p.address as? Inet4Address)?.hostAddress ?: continue
                    val hits = LanLogic.parseMdnsA(buf, p.length)
                    hits.filter { LanLogic.isPrivate(it.ip) }.forEach { out[it.ip] = out[it.ip].orEmpty().ifEmpty { it.name } }
                    if (LanLogic.isPrivate(src)) out.putIfAbsent(src, hits.firstOrNull { it.ip == src }?.name.orEmpty())
                }
            }
        } catch (e: Exception) { Diag.log("LAN", "mDNS failed: ${e.javaClass.simpleName}: ${e.message}") }
        finally { runCatching { lock?.release() } }
        return out
    }

    private fun baseClient(sf: SocketFactory): OkHttpClient = FnClient.http.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS).callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).socketFactory(sf).build()

    /**
     * 只用于「识别」：飞牛默认是自签名证书，正常校验必然失败。这里不发送任何账号、token，只 GET 首页看特征；
     * 登录时仍走 FnClient 的正常证书策略（TlsTrust）。
     */
    private fun looseTls(base: OkHttpClient): OkHttpClient? = runCatching {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        base.newBuilder().sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }.getOrNull()

    private class Page(val code: Int, val text: String, val title: String, val location: String?)

    private fun get(client: OkHttpClient, url: String): Page? = runCatching {
        client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            val body = runCatching { r.peekBody(64 * 1024).string() }.getOrDefault("")
            val cert = (r.handshake?.peerCertificates?.firstOrNull() as? X509Certificate)?.let { it.subjectX500Principal.name + " " + it.issuerX500Principal.name }.orEmpty()
            val hay = body + " " + r.headers.joinToString(" ") { it.first + ":" + it.second } + " " + cert
            Page(r.code, hay, LanLogic.titleOf(body), r.header("Location"))
        }
    }.onFailure { if (verbose) Diag.log("LAN", "GET ${url.substringAfter("://").let { mask(it.substringBefore(':')) + ":" + it.substringAfter(':', "") }} → ${it.javaClass.simpleName}") }.getOrNull()

    private fun fnosCheck(plain: OkHttpClient, loose: OkHttpClient?, h: String, ports: List<Int>, mdnsName: String): Endpoint.Found? {
        for (port in ports) {
            val https = port == 5667 || port == 443
            val base = (if (https) "https" else "http") + "://$h:$port"
            val page = get(if (https) loose ?: plain else plain, "$base/") ?: continue
            var url = base; var p = page
            if (page.code in 300..399) {
                val tgt = LanLogic.httpsTarget(h, page.location)
                if (tgt != null && loose != null) get(loose, "$tgt/")?.let { url = if (tgt.substringAfter("://").contains(':')) tgt else "$tgt:443"; p = it }
            }
            if (LanLogic.looksLikeFnos(p.text) || (p !== page && LanLogic.looksLikeFnos(page.text))) {
                val name = LanLogic.nameFromTitle(p.title).ifEmpty { mdnsName.removeSuffix("(SMB)").trim() }
                Diag.log("LAN", "fnOS at ${mask(h)}:$port → ${url.substringBefore("://")} (code ${p.code})")
                return Endpoint.Found(url, name)
            }
            if (verbose) Diag.log("LAN", "${mask(h)}:$port answers (code ${page.code}) but is not fnOS")
        }
        return null
    }
}
