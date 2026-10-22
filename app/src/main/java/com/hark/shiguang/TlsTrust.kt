package com.hark.shiguang

import android.annotation.SuppressLint
import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * H19：手机里用户自己安装的 CA 证书（抓包工具、自签名 NAS 证书）只对用户指定的主机生效。
 *
 * - res/xml/network.xml 现在只信任系统证书；
 * - 所有 OkHttpClient 都从 FnClient.http 派生，FnClient.http 用这里的 [trustManager]：先按系统证书校验，
 *   失败时只有主机在「信任用户证书的主机」名单里，才再用用户安装的 CA 校验；
 * - 名单存在 Store 的 "tls.userCaHosts"（逗号分隔，小写主机名）。默认空；
 *   升级迁移（[migrate]）：已保存账户里的 https 主机自动加入名单，之前能连上的自签名 NAS 升级后照常可用。
 */
object TlsTrust {
    private const val KEY = "tls.userCaHosts"
    private const val MIGRATED = "tls.userCaHosts.migrated"
    /** Shown for SSL failures: the likely cause and where to fix it. */
    const val SSL_HINT = "SSL 连接失败：证书不受信任或已过期。若是自签名证书，请先在手机里安装证书，再到「设置 → 隐私与存储 → 信任用户证书的主机」打开这个地址"

    @Volatile private var hosts: Set<String> = emptySet()

    fun allowedHosts(): Set<String> = hosts
    fun isAllowed(host: String?): Boolean = host != null && host.lowercase() in hosts

    fun load() { hosts = Store.getStr(KEY).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() }

    fun setAllowed(host: String, on: Boolean) {
        val h = host.trim().lowercase(); if (h.isEmpty()) return
        hosts = if (on) hosts + h else hosts - h
        Store.putStr(KEY, hosts.sorted().joinToString(","))
        Diag.log("TLS", "user CA ${if (on) "allowed" else "removed"} for a host (count=${hosts.size})")
    }

    /** Hosts of every saved account (飞牛 + WebDAV), for the settings list. */
    fun accountHosts(): List<String> {
        val urls = NasAccounts.list.flatMap { listOf(it.url, it.lanUrl, it.wanUrl) } + com.hark.shiguang.cloud.CloudAccounts.all.map { it.url }
        return urls.filter { it.startsWith("https://", true) }.mapNotNull { hostOf(it) }.distinct().sorted()
    }

    /** One-time upgrade step (see class doc). */
    fun migrate() {
        load()
        if (Store.getStr(MIGRATED) == "1") return
        runCatching { accountHosts().forEach { if (it !in hosts) hosts = hosts + it } }
        Store.putStr(KEY, hosts.sorted().joinToString(","))
        Store.putStr(MIGRATED, "1")
        if (hosts.isNotEmpty()) Diag.log("TLS", "migrated ${hosts.size} https account host(s) to the user-CA list")
    }

    fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun tmOf(ks: KeyStore?): X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    /** System (network-security-config) trust: with network.xml this is system CAs only. */
    private val system: X509TrustManager by lazy { tmOf(null) ?: error("no system trust manager") }

    /** Only the CAs the user installed in Android settings (AndroidCAStore aliases "user:…"). */
    private val user: X509TrustManager? by lazy {
        runCatching {
            val src = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            var n = 0
            for (a in src.aliases()) if (a.startsWith("user:")) { ks.setCertificateEntry(a, src.getCertificate(a)); n++ }
            if (n == 0) null else tmOf(ks)
        }.onFailure { Diag.log("TLS", "user CA store unreadable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    @SuppressLint("CustomX509TrustManager")
    val trustManager: X509TrustManager = object : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>, authType: String, host: String?) {
            try {
                @Suppress("UNCHECKED_CAST")
                system.checkServerTrusted(chain as Array<X509Certificate>, authType)
            } catch (e: CertificateException) {
                val u = user
                if (u == null || !isAllowed(host)) throw e
                @Suppress("UNCHECKED_CAST")
                u.checkServerTrusted(chain as Array<X509Certificate>, authType)
            }
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            check(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            check(chain, authType, engine?.handshakeSession?.peerHost ?: engine?.peerHost)
        // no host known → system trust only
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = check(chain, authType, null)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = system.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = system.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + (user?.acceptedIssuers ?: emptyArray())
    }

    val socketFactory: SSLSocketFactory by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory }

    /** Applies the per-host trust to an OkHttp builder (used once, by FnClient.http). Falls back to the platform default on error. */
    fun apply(b: OkHttpClient.Builder): OkHttpClient.Builder = runCatching { b.sslSocketFactory(socketFactory, trustManager) }
        .onFailure { Diag.log("TLS", "custom trust unavailable: ${it.javaClass.simpleName}") }.getOrDefault(b)

    /**
     * MediaMetadataRetriever uses the platform HTTP stack (system CAs only now). For a host in the user-CA list it reads through
     * this OkHttp-backed source instead, so video thumbnails / durations on a self-signed NAS keep working.
     */
    fun needsOkHttpSource(url: String): Boolean = url.startsWith("https://", true) && isAllowed(hostOf(url))
}

/** Random-access reads over HTTP Range through OkHttp (256 KB read-ahead), for MediaMetadataRetriever. */
class OkHttpMediaSource(private val client: OkHttpClient, private val url: String, private val headers: Map<String, String>) : MediaDataSource() {
    private var size = -2L
    private var bufStart = -1L
    private var buf = ByteArray(0)

    private fun req(range: String) = Request.Builder().url(url).header("Range", range).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    override fun getSize(): Long {
        if (size != -2L) return size
        size = runCatching {
            client.newCall(req("bytes=0-0")).execute().use { r ->
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength()?.takeIf { r.code == 200 } ?: -1L
            }
        }.getOrDefault(-1L)
        return size
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (len == 0) return 0
        val total = getSize()
        if (total in 0..position) return -1
        if (bufStart < 0 || position < bufStart || position >= bufStart + buf.size) {
            val want = maxOf(len, 256 * 1024)
            val end = if (total > 0) minOf(total - 1, position + want - 1) else position + want - 1
            buf = client.newCall(req("bytes=$position-$end")).execute().use { r ->
                if (r.code != 206) throw java.io.IOException("range not supported (HTTP ${r.code})")
                r.body?.bytes() ?: ByteArray(0)
            }
            bufStart = position
            if (buf.isEmpty()) return -1
        }
        val from = (position - bufStart).toInt()
        val n = minOf(len, buf.size - from)
        System.arraycopy(buf, from, buffer, offset, n)
        return n
    }

    override fun close() { buf = ByteArray(0) }
}
