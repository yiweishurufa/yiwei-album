package com.hark.shiguang

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 1.0.1 #1 TMDB 自动网络：China-first.
 *
 * Probes api.themoviedb.org / image.tmdb.org directly (3 s). When a direct endpoint does not answer with TMDB content,
 * picks a proxy: the user's own 接口代理 / 图片代理 first, then the built-in candidates below (each verified at runtime,
 * never trusted blindly). Overseas (direct works) keeps the original endpoints. The decision is cached in [Store] and
 * re-checked every 24 h, on network change, and when a request through the chosen base fails.
 *
 * Built-in candidates (checked 2026-10-09 from an overseas sandbox, so "reachable in mainland China" is from public
 * reports, not measured):
 *  - https://api.tmdb.org — TMDB's own alternate API host; answers /3/configuration with TMDB JSON (401 status_code 7
 *    without a key). Reported reachable without VPN in most of mainland China (sjtuross/StrmAssistant wiki
 *    「替代 TMDB 配置」, MoviePilot docs TMDB_API_DOMAIN).
 *  - https://tmdb.movie-pilot.org — MoviePilot's public relay (MoviePilot docs). Answers with TMDB JSON, but from the
 *    sandbox it presented a Cloudflare *Origin* certificate (not publicly trusted) — kept as the last candidate; the probe
 *    only accepts it when TLS validates on the device.
 *  - images: https://images.tmdb.org (alternate CDN host of image.tmdb.org, same paths) and
 *    https://wsrv.nl/?url=https://image.tmdb.org (images.weserv.nl public image CDN, suggested by the StrmAssistant wiki;
 *    re-encodes the image, works by plain prefixing "/t/p/<size>/<file>").
 */
object TmdbNet {
    const val DIRECT_API = "https://api.themoviedb.org"
    const val DIRECT_IMG = "https://image.tmdb.org"
    val API_CANDIDATES = listOf("https://api.tmdb.org", "https://tmdb.movie-pilot.org")
    val IMG_CANDIDATES = listOf("https://images.tmdb.org", "https://wsrv.nl/?url=https://image.tmdb.org")
    private const val IMG_PROBE = "/t/p/w92/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"   // TMDB's own logo, tiny
    private const val RECHECK_MS = 24 * 3600_000L

    /** UI line: 「直连」/「已自动使用代理 xxx」/「检测中…」/… */
    var status by mutableStateOf("")
    var checking by mutableStateOf(false)

    @Volatile private var api: String = ""
    @Volatile private var img: String = ""
    @Volatile private var at: Long = 0
    @Volatile private var stale = false
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).followRedirects(true).build()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tmdbnet").apply { isDaemon = true } }
    private var started = false

    /** App start: load the cached decision, watch network changes, recheck in background when old. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        load()
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var last: String? = null
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val kind = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        else -> "other"
                    } + (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "+vpn" else "")
                    val id = "$network|$kind"
                    if (last != null && last != id) { stale = true; recheckAsync() }
                    last = id
                }
            })
        }
        if (Tmdb.configured && (api.isEmpty() || System.currentTimeMillis() - at > RECHECK_MS)) recheckAsync()
    }

    private fun load() {
        runCatching {
            val o = JSONObject(Store.getStr("tmdb.net"))
            api = o.optString("api"); img = o.optString("img"); at = o.optLong("at")
            status = describe()
        }
    }

    private fun save() {
        Store.putStr("tmdb.net", JSONObject().put("api", api).put("img", img).put("at", at).toString())
    }

    /** Current API base (no trailing /). Blocks for the probe when nothing is decided yet — call off the main thread. */
    fun apiBase(): String { ensure(); return api.ifEmpty { Tmdb.apiBase.ifEmpty { DIRECT_API } } }

    /** Current image base, without probing (image urls are built on the main thread too). */
    fun imgBase(): String = img.ifEmpty { if (api.isNotEmpty() && api != DIRECT_API) Tmdb.imgBase.ifEmpty { IMG_CANDIDATES.last() } else Tmdb.imgBase.ifEmpty { DIRECT_IMG } }

    /** Maps a canonical https://image.tmdb.org/t/p/… url (as stored in library.json) onto the current image base. */
    fun imgUrl(u: String?): String? {
        if (u == null) return null
        val b = imgBase()
        return if (u.startsWith(DIRECT_IMG) && b != DIRECT_IMG) b + u.removePrefix(DIRECT_IMG) else u
    }

    /** Re-probe when never decided, older than 24 h, or the network changed. */
    @Synchronized fun ensure(force: Boolean = false) {
        if (!force && api.isNotEmpty() && !stale && System.currentTimeMillis() - at < RECHECK_MS) return
        probe()
    }

    fun recheckAsync() { pool.execute { runCatching { ensure(force = true) } } }

    /** Called by [Tmdb] when a request through the chosen base failed at the network level. Returns true when the base changed. */
    fun onFailure(): Boolean { val before = api; ensure(force = true); return api != before }

    private fun probe() {
        checking = true; status = "检测中…"
        try {
            val userApi = Tmdb.apiBase.trimEnd('/')
            val userImg = Tmdb.imgBase.trimEnd('/')
            api = if (okApi(DIRECT_API)) DIRECT_API else firstOk((listOf(userApi).filter { it.isNotEmpty() && it != DIRECT_API } + API_CANDIDATES).distinct(), ::okApi) ?: ""
            img = if (okImg(DIRECT_IMG)) DIRECT_IMG else firstOk((listOf(userImg).filter { it.isNotEmpty() && it != DIRECT_IMG } + IMG_CANDIDATES).distinct(), ::okImg) ?: ""
            at = System.currentTimeMillis(); stale = false
            if (api.isEmpty()) at = System.currentTimeMillis() - RECHECK_MS + 10 * 60_000L   // nothing works: retry in 10 min
            save()
            Diag.log("TMDB", "net: api=$api img=$img")
        } finally {
            status = describe(); checking = false
        }
    }

    /** Probes candidates in parallel, returns the first one (in list order) that passed. */
    private fun firstOk(list: List<String>, ok: (String) -> Boolean): String? {
        if (list.isEmpty()) return null
        val fs: List<Pair<String, Future<Boolean>>> = list.map { b -> b to pool.submit<Boolean> { runCatching { ok(b) }.getOrDefault(false) } }
        return fs.firstOrNull { (_, f) -> runCatching { f.get(6, TimeUnit.SECONDS) }.getOrDefault(false) }?.first
    }

    /** TMDB JSON: either the configuration (valid key) or TMDB's own error object (401 status_code 7 / 34). */
    private fun okApi(base: String): Boolean = runCatching {
        val key = Tmdb.key
        val u = "$base/3/configuration" + if (key.isNotEmpty() && !(key.startsWith("eyJ") || key.length > 40)) "?api_key=$key" else ""
        val rb = Request.Builder().url(u).header("Accept", "application/json")
        if (key.startsWith("eyJ") || key.length > 40) rb.header("Authorization", "Bearer $key")
        http.newCall(rb.build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty())
            o.has("images") || o.has("status_code") && o.has("status_message")
        }
    }.getOrDefault(false)

    private fun okImg(base: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(base + IMG_PROBE).build()).execute().use { r ->
            r.isSuccessful && (r.body?.contentType()?.type == "image") && (r.body?.bytes()?.size ?: 0) > 200
        }
    }.getOrDefault(false)

    private fun short(b: String) = b.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')

    fun describe(): String = when {
        api.isEmpty() && at == 0L -> "未检测"
        api.isEmpty() -> "直连和代理都连不上 TMDB，稍后自动重试；也可以填自己的代理地址"
        api == DIRECT_API && (img == DIRECT_IMG || img.isEmpty()) -> "直连"
        api == DIRECT_API -> "接口直连 · 图片已自动使用代理 ${short(img)}"
        else -> "已自动使用代理 ${short(api)}" + if (img.isNotEmpty() && img != DIRECT_IMG && short(img) != short(api)) " · 图片 ${short(img)}" else if (img == DIRECT_IMG) " · 图片直连" else ""
    }
}
