package com.hark.shiguang

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hark.shiguang.data.FnClient

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ScanPolicy.migrate()
        Diag.init(this)
        CrashLog.install(this)
        runCatching { com.hark.shiguang.cloud.CloudAccounts.init(this) }
        NasAccounts.init()
        ctx = this
        ScanWatch.init(this)
        TmdbNet.start(this)   // 1.0.1: TMDB 直连 / 自动代理
        NetEnv.start(this)    // 1.0.2: VPN 下国内网盘/飞牛自动绕过直连
        // keep caches bounded without touching anything the UI needs right away
        Thread { runCatching { CacheTrim.run(this) } }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
    companion object { lateinit var ctx: App }

    override fun newImageLoader(): ImageLoader {
        val client = FnClient.http.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val u = req.url.toString()
            val b = req.newBuilder()
            if (FnClient.token.isNotEmpty() && FnClient.baseUrl.isNotEmpty() && u.startsWith(FnClient.baseUrl)) b.header("accesstoken", FnClient.token)
            else ImageAuth.headersFor(u)?.forEach { (k, v) -> b.header(k, v) }
            chain.proceed(b.build())
        }.build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .components { add(DavThumb.Factory()); add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
            // 1.0.8: 800 MB -> 300 MB. Grid thumbnails are tiny; what filled the cache were full-size originals from the viewer.
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(300L * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .crossfade(180)
            .build()
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = runCatching {
            EncryptedSharedPreferences.create(
                c, "secure", MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { c.getSharedPreferences("plain", Context.MODE_PRIVATE) }
    }
    var url: String get() = sp.getString("url", "") ?: ""; set(v) = sp.edit().putString("url", v).apply()
    var user: String get() = sp.getString("user", "") ?: ""; set(v) = sp.edit().putString("user", v).apply()
    var pass: String get() = sp.getString("pass", "") ?: ""; set(v) = sp.edit().putString("pass", v).apply()
    var token: String get() = sp.getString("token", "") ?: ""; set(v) = sp.edit().putString("token", v).apply()
    var columns: Int get() = sp.getInt("cols", 4); set(v) = sp.edit().putInt("cols", v).apply()
    var themeMode: String get() = sp.getString("themeMode", "DARK") ?: "DARK"; set(v) = sp.edit().putString("themeMode", v).apply()
    var accent: Int get() = sp.getInt("accent", 0); set(v) = sp.edit().putInt("accent", v).apply()
    var gridSquare: Boolean get() = sp.getBoolean("gridSquare", true); set(v) = sp.edit().putBoolean("gridSquare", v).apply()
    /** "fn" or "dav": which world the home screen shows. */
    var source: String get() = sp.getString("source", "fn") ?: "fn"; set(v) = sp.edit().putString("source", v).apply()
    var davId: String get() = sp.getString("davId", "") ?: ""; set(v) = sp.edit().putString("davId", v).apply()
    var nasAccounts: String get() = sp.getString("nasAccounts", "[]") ?: "[]"; set(v) = sp.edit().putString("nasAccounts", v).apply()
    var nasCurrent: String get() = sp.getString("nasCurrent", "") ?: ""; set(v) = sp.edit().putString("nasCurrent", v).apply()
    /** auto / lan / wan */
    var netMode: String get() = sp.getString("netMode", "auto") ?: "auto"; set(v) = sp.edit().putString("netMode", v).apply()
    var lockOn: Boolean get() = sp.getBoolean("lockOn", false); set(v) = sp.edit().putBoolean("lockOn", v).apply()
    var lanUrl: String get() = sp.getString("lan", "") ?: ""; set(v) = sp.edit().putString("lan", v).apply()
    var wanUrl: String get() = sp.getString("wan", "") ?: ""; set(v) = sp.edit().putString("wan", v).apply()
    var autoSwitch: Boolean get() = sp.getBoolean("autoSwitch", true); set(v) = sp.edit().putBoolean("autoSwitch", v).apply()
    var backupOn: Boolean get() = sp.getBoolean("backupOn", false); set(v) = sp.edit().putBoolean("backupOn", v).apply()
    var backupWifiOnly: Boolean get() = sp.getBoolean("backupWifi", true); set(v) = sp.edit().putBoolean("backupWifi", v).apply()
    var backupSince: Long get() = sp.getLong("backupSince", 0L); set(v) = sp.edit().putLong("backupSince", v).apply()
    var backupTarget: String get() = sp.getString("backupTarget", "") ?: ""; set(v) = sp.edit().putString("backupTarget", v).apply()
    var timelineLevel: Int get() = sp.getInt("tlLevel", 2); set(v) = sp.edit().putInt("tlLevel", v).apply()
    fun getStr(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    /** All non-secret settings, for QR / WebDAV sync. */
    /** All non-secret settings, for QR sync. */
    fun exportSettings(): org.json.JSONObject = org.json.JSONObject().apply {
        put("themeMode", themeMode); put("accent", accent); put("cols", columns); put("lan", lanUrl); put("wan", wanUrl); put("url", url); put("user", user)
        put("autoSwitch", autoSwitch); put("scanCell", getStr("scan.cell")); put("scanNoCharge", getStr("scan.nocharge")); put("tlLevel", timelineLevel)
    }
    fun importSettings(j: org.json.JSONObject) {
        j.optString("themeMode").takeIf { it.isNotEmpty() }?.let { themeMode = it }
        if (j.has("accent")) accent = j.optInt("accent", 0)
        if (j.has("cols")) columns = j.optInt("cols", 4)
        j.optString("lan").takeIf { it.isNotEmpty() }?.let { lanUrl = it }
        j.optString("wan").takeIf { it.isNotEmpty() }?.let { wanUrl = it }
        if (url.isEmpty()) j.optString("url").takeIf { it.isNotEmpty() }?.let { url = it }
        if (user.isEmpty()) j.optString("user").takeIf { it.isNotEmpty() }?.let { user = it }
        if (j.has("autoSwitch")) autoSwitch = j.optBoolean("autoSwitch")
        if (j.has("scanCell")) putStr("scan.cell", j.optString("scanCell"))
        if (j.has("scanNoCharge")) putStr("scan.nocharge", j.optString("scanNoCharge"))
        if (j.has("tlLevel")) timelineLevel = j.optInt("tlLevel", 2)
    }
    fun clear() = sp.edit().remove("pass").remove("token").apply()
}

/** Extra request headers for images/videos from non-NAS sources (cloud drives). */
object ImageAuth {
    private val providers = java.util.concurrent.CopyOnWriteArrayList<(String) -> Map<String, String>?>()
    fun register(p: (String) -> Map<String, String>?) { providers.add(p) }
    fun headersFor(url: String): Map<String, String>? { for (p in providers) { val h = p(url); if (h != null) return h }; return null }
}


/** Trims the caches Coil does not manage: WebDAV thumbnails (LRU by last use), subtitles, logs, shares. */
object CacheTrim {
    private const val THUMBS_MAX = 160L * 1024 * 1024
    fun run(c: Context) {
        trimLru(java.io.File(c.cacheDir, "davthumb"), THUMBS_MAX)
        olderThan(java.io.File(c.cacheDir, "subs"), 30)
        olderThan(java.io.File(c.cacheDir, "share"), 1)
        java.io.File(c.cacheDir, "davthumb").listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
    fun trimLru(dir: java.io.File, max: Long) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var sum = 0L
        files.forEach { f -> sum += f.length(); if (sum > max) f.delete() }
    }
    private fun olderThan(dir: java.io.File, days: Int) {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        dir.walkBottomUp().filter { it.isFile && it.lastModified() < cut }.forEach { it.delete() }
    }
}
