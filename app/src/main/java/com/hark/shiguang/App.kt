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
        if (Store.insecure) Diag.log("SEC", "encrypted prefs unavailable, using plain prefs (${Store.insecureReason})")   // H18
        CrashLog.install(this)
        Thread { CrashLog.recordSystemExit(this) }.apply { priority = Thread.MIN_PRIORITY }.start()   // 2026-10-22: native crash / ANR
        runCatching { com.hark.shiguang.cloud.CloudAccounts.init(this) }
        NasAccounts.init()
        runCatching { TlsTrust.migrate() }   // H19: keep already-working https accounts trusted after the upgrade
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
            // #94: 上限可在 设置 → 清理缓存 → 缩略图缓存上限 里改，默认仍是 300 MB，重启 App 后生效
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(CacheLimit.bytes(Store.thumbCacheMb)).build() }
            .respectCacheHeaders(false)
            .crossfade(180)
            .build()
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    /** H18: true when the encrypted store could not be opened and passwords / tokens sit in plain prefs. Settings warns. */
    @Volatile var insecure: Boolean = false
        private set
    /** H18: why the encrypted store failed (logged once Diag is up). */
    @Volatile var insecureReason: String = ""
        private set
    /** H18: keys holding passwords / tokens / API keys — sealed with [SecureBox] when the plain fallback store is in use. */
    fun isSecretKey(k: String): Boolean = k == "pass" || k == "token" || k == "nasAccounts" || k == "dm.appSecret" || k == "tmdb.key" || k == "tmdb.api" ||
        k == "ai.key" || k.startsWith("ai.key.") || k.startsWith("sign.")
    /** Non-null only while the plain fallback store is in use. */
    private var box: SecretPrefs? = null

    fun init(c: Context) {
        val enc = runCatching {
            EncryptedSharedPreferences.create(
                c, "secure", MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        val plain = c.getSharedPreferences("plain", Context.MODE_PRIVATE)
        sp = enc.getOrElse { e ->
            // H18: fall back to plain prefs, but seal every secret with an Android Keystore key (old plain secrets are rewritten)
            insecureReason = "Store: ${e.javaClass.simpleName}"
            val b = SecretPrefs(plain, ::isSecretKey); box = b
            val left = runCatching { b.migratePlain() }.getOrDefault(1)
            insecure = left > 0 || !SecureBox.available
            if (insecure) insecureReason += "; Keystore: ${SecureBox.error.ifEmpty { "unavailable" }}"
            plain
        }
        // H18: the encrypted store works again after an earlier fallback → move the old plain/sealed values in and wipe the plain file
        if (enc.isSuccess && plain.all.isNotEmpty()) runCatching {
            val e = sp.edit()
            for ((k, v) in plain.all) when (v) {
                is String -> (if (SecureBox.isSealed(v)) SecureBox.open(v) else v)?.let { if (!sp.contains(k)) e.putString(k, it) }
                is Boolean -> if (!sp.contains(k)) e.putBoolean(k, v)
                is Int -> if (!sp.contains(k)) e.putInt(k, v)
                is Long -> if (!sp.contains(k)) e.putLong(k, v)
                is Float -> if (!sp.contains(k)) e.putFloat(k, v)
            }
            if (e.commit()) plain.edit().clear().commit()
        }
    }
    private fun str(k: String, d: String = ""): String = box?.get(k, d) ?: (sp.getString(k, d) ?: d)
    private fun setStr(k: String, v: String) { val e = sp.edit(); box?.put(e, k, v) ?: e.putString(k, v); e.apply() }
    var url: String get() = str("url", ""); set(v) = setStr("url", v)
    var user: String get() = str("user", ""); set(v) = setStr("user", v)
    var pass: String get() = str("pass", ""); set(v) = setStr("pass", v)
    var token: String get() = str("token", ""); set(v) = setStr("token", v)
    var thumbCacheMb: Int get() = CacheLimit.normalize(sp.getInt("thumbCacheMb", CacheLimit.DEFAULT_MB)); set(v) = sp.edit().putInt("thumbCacheMb", CacheLimit.normalize(v)).apply()
    var columns: Int get() = sp.getInt("cols", 4); set(v) = sp.edit().putInt("cols", v).apply()
    var themeMode: String get() = str("themeMode", "DARK"); set(v) = setStr("themeMode", v)
    var accent: Int get() = sp.getInt("accent", 0); set(v) = sp.edit().putInt("accent", v).apply()
    var gridSquare: Boolean get() = sp.getBoolean("gridSquare", true); set(v) = sp.edit().putBoolean("gridSquare", v).apply()
    /** "fn" or "dav": which world the home screen shows. */
    var source: String get() = str("source", "fn"); set(v) = setStr("source", v)
    var davId: String get() = str("davId", ""); set(v) = setStr("davId", v)
    var nasAccounts: String get() = str("nasAccounts", "[]"); set(v) = setStr("nasAccounts", v)
    var nasCurrent: String get() = str("nasCurrent", ""); set(v) = setStr("nasCurrent", v)
    /** auto / lan / wan */
    var netMode: String get() = str("netMode", "auto"); set(v) = setStr("netMode", v)
    var lockOn: Boolean get() = sp.getBoolean("lockOn", false); set(v) = sp.edit().putBoolean("lockOn", v).apply()
    var lanUrl: String get() = str("lan", ""); set(v) = setStr("lan", v)
    var wanUrl: String get() = str("wan", ""); set(v) = setStr("wan", v)
    var autoSwitch: Boolean get() = sp.getBoolean("autoSwitch", true); set(v) = sp.edit().putBoolean("autoSwitch", v).apply()
    var backupOn: Boolean get() = sp.getBoolean("backupOn", false); set(v) = sp.edit().putBoolean("backupOn", v).apply()
    var backupWifiOnly: Boolean get() = sp.getBoolean("backupWifi", true); set(v) = sp.edit().putBoolean("backupWifi", v).apply()
    var backupSince: Long get() = sp.getLong("backupSince", 0L); set(v) = sp.edit().putLong("backupSince", v).apply()
    var backupTarget: String get() = str("backupTarget", ""); set(v) = setStr("backupTarget", v)
    var timelineLevel: Int get() = sp.getInt("tlLevel", 2); set(v) = sp.edit().putInt("tlLevel", v).apply()
    fun getStr(k: String, d: String = ""): String = str(k, d)
    fun putStr(k: String, v: String) = setStr(k, v)
    fun removeKey(k: String) = sp.edit().remove(k).apply()
    /**
     * B11: this clears ONLY the login credentials (pass/token) — NOT all settings, and NOT the
     * per-account signing secrets (`sign.<user>@<url>`). Those are removed by [NasAccounts.remove]
     * when an account is logged out / removed; do not rely on [clear] to wipe them.
     */
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
        // 用户 2026-10-23：回忆短片已删除，旧版本生成的 mp4 一并清掉
        java.io.File(c.cacheDir, "memory").takeIf { it.exists() }?.deleteRecursively()
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
