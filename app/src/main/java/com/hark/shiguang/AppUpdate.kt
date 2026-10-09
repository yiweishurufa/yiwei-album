package com.hark.shiguang

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import com.hark.shiguang.data.FnClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.0.9 in-app update from the developer's 123云盘 share (no token, public share API):
 *  folder 「一维相册」 holds 一维相册-<ver>.apk and optionally 更新说明-<ver>.txt.
 *  List:     GET  {host}/b/api/share/get?...&ParentFileId=<id>   (headers platform: web, App-Version: 3)
 *  Download: POST {host}/b/api/share/download/info {ShareKey,FileID,S3keyFlag,Size,Etag} → data.DownloadURL
 *            (anonymous call answered code 5112 「需要登录」 for a folder in testing — UNVERIFIED for files; on 5112 or
 *            any other failure the share page opens in the browser instead, and the dialog says so up front).
 *  Hosts: only www.123684.com / www.123865.com — www.123pan.com answers these API paths with an HTML page. Never use it.
 */
object AppUpdate {
    const val SHARE_KEY = "Qpc7Vv-89Ywh"
    const val SHARE_PAGE = "https://1814107608.share.123pan.cn/123pan/Qpc7Vv-89Ywh"
    private const val FOLDER_NAME = "一维相册"
    private const val FOLDER_ID_HINT = 129194314L
    private val HOSTS = listOf("https://www.123684.com", "https://www.123865.com")
    private val http by lazy { FnClient.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build() }

    data class Entry(val id: Long, val name: String, val size: Long, val etag: String, val s3: String, val type: Int)
    data class Release(val version: String, val apk: Entry, val notes: String, val code: Int = 0) {
        /** Key for 「以后再说」. */
        val skipKey: String get() = "$version-$code"
    }

    /** Shown by [com.hark.shiguang.ui.UpdateDialog]. */
    var offer by mutableStateOf<Release?>(null)
    var progress by mutableFloatStateOf(-1f)
    var checking by mutableStateOf(false)
    /** Last download/info answer code (5112 = 「需要登录」: the share only downloads in the browser). */
    @Volatile var lastCode: Int = 0
    const val LOGIN_REQUIRED = 5112
    /** Shown in the update dialog so the browser hop is not a surprise. */
    const val FALLBACK_TEXT = "123 云盘分享不允许免登录下载：本机添加过 123 云盘 WebDAV 账户（分享者本人）时会直接下载；否则改为在浏览器打开分享页，登录 123 云盘后下载安装包，点开安装即可。"

    private fun Request.Builder.web() = header("platform", "web").header("App-Version", "3").header("User-Agent", "Mozilla/5.0 (Linux; Android) YiWei/${BuildInfo.version}")

    private fun list(parent: Long): List<Entry> {
        var last: Exception? = null
        for (h in HOSTS) {
            try {
                val out = ArrayList<Entry>()
                var page = 1
                while (page < 10) {
                    val url = "$h/b/api/share/get?limit=100&next=1&orderBy=file_name&orderDirection=asc&shareKey=$SHARE_KEY&SharePwd=&ParentFileId=$parent&Page=$page&event=homeListFile&operateType=1"
                    val j = http.newCall(Request.Builder().url(url).web().build()).execute().use { JSONObject(it.body?.string().orEmpty()) }
                    if (j.optInt("code", -1) != 0) throw Exception(j.optString("message", "列表失败"))
                    val d = j.getJSONObject("data"); val a = d.optJSONArray("InfoList")
                    for (i in 0 until (a?.length() ?: 0)) a!!.getJSONObject(i).let {
                        out += Entry(it.optLong("FileId"), it.optString("FileName"), it.optLong("Size"), it.optString("Etag"), it.optString("S3KeyFlag"), it.optInt("Type"))
                    }
                    if (d.optString("Next") == "-1" || d.optString("Next").isEmpty() || (a?.length() ?: 0) < 100) break
                    page++
                }
                return out
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("列表失败")
    }

    private fun text(e: Entry): String = runCatching {
        val u = downloadUrl(e) ?: return@runCatching ""
        http.newCall(Request.Builder().url(u).build()).execute().use { if (it.isSuccessful) it.body?.string().orEmpty().take(4000) else "" }
    }.getOrDefault("")

    /** "1.0.10" > "1.0.9"; non-digits ignored. */
    fun cmp(a: String, b: String): Int {
        val x = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }

    /**
     * 1.0.0 (公测): versionName restarted at 1.0.0 while versionCode keeps growing (11), so names alone can no longer be
     * compared ("1.0.10" > "1.0.0" but is OLDER). Releases are compared by versionCode, which must be in the file name:
     *   一维相册-1.0.0-11.apk / 一维相册-1.0.0(11).apk / 一维相册_1.0.0_vc11.apk
     * A name without a code is a pre-公测 build (≤ 1.0.10 = versionCode ≤ 10) and is never offered over versionCode ≥ 11;
     * a name without a code whose version is above 1.0.10 (e.g. 1.1.0) is compared by name as a fallback.
     */
    private val APK = Regex("^(?:一维相册|YiWei|yiwei)[-_ ]?v?(\\d+(?:\\.\\d+)+)(?:(?:[-_ ]|[-_ ]?[(（])(?:vc|build|b)?(\\d+)[)）]?)?\\.apk$", RegexOption.IGNORE_CASE)
    private val NOTE = Regex("^(?:更新说明|YiWei|yiwei)[-_ ]?(?:更新说明|notes?)?[-_ ]?v?(\\d+(?:\\.\\d+)+)(?:(?:[-_ ]|[-_ ]?[(（])(?:vc|build|b)?(\\d+)[)）]?)?\\.txt$", RegexOption.IGNORE_CASE)

    /** versionCode of a release file name; legacy names without a code count as ≤ 10. */
    /**
     * 1.0.3: the share actually holds names like 「YiWei-1.0.3.apk」 (no versionCode), which the old rule treated as
     * pre-公测 builds and never offered — that is why 自动更新 found nothing. Now a name without a code is a 公测 build
     * compared by versionName (public names 1.0.0, 1.0.1… only grow); the downloaded APK's real versionCode is still
     * checked before installing, so an old build can never be installed over a newer one.
     */
    fun codeOf(name: String, code: String?): Int = code?.toIntOrNull() ?: -1

    fun isNewer(name: String, code: Int): Boolean = when {
        code >= 0 -> code > BuildInfo.code
        else -> cmp(name, BuildInfo.version) > 0
    }

    /** Latest release newer than the installed one (by versionCode), or null. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val root = runCatching { list(0) }.getOrNull()
        val folder = root?.firstOrNull { it.type == 1 && it.name == FOLDER_NAME }?.id ?: FOLDER_ID_HINT
        val files = list(folder)
        val cands = files.mapNotNull { f -> APK.find(f.name)?.let { m -> Triple(m.groupValues[1], codeOf(m.groupValues[1], m.groupValues[2].ifEmpty { null }), f) } }
            .filter { isNewer(it.first, it.second) }
        val best = cands.maxWithOrNull(compareBy<Triple<String, Int, Entry>> { it.second }.thenComparator { a, b -> cmp(a.first, b.first) }) ?: return@withContext null
        val note = files.firstOrNull { f -> NOTE.find(f.name)?.let { it.groupValues[1] == best.first && (it.groupValues[2].isEmpty() || it.groupValues[2].toIntOrNull() == best.second) } == true }?.let { text(it) }.orEmpty()
        Release(best.first, best.third, note, best.second)
    }

    /** At most once per 12 h, silently. */
    suspend fun autoCheck() {
        val last = Store.getStr("upd.last").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        Store.putStr("upd.last", System.currentTimeMillis().toString())
        runCatching { latest() }.onSuccess { r -> if (r != null && Store.getStr("upd.skip") != r.skipKey) offer = r }
            .onFailure { Diag.log("UPD", "check failed: ${it.message}") }
    }

    /** From the 关于 row: returns a message for a toast when there is nothing to offer. A failed check opens the 123 share page. */
    suspend fun manualCheck(): String? {
        checking = true
        try {
            val r = latest() ?: return "已是最新版本 ${BuildInfo.label}"
            offer = r; return null
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { openSharePage(App.ctx) }
            return "检查失败（${e.message ?: "网络错误"}），已在浏览器打开 123 云盘分享页"
        } finally { checking = false }
    }

    private fun downloadUrl(e: Entry): String? {
        val body = JSONObject().put("ShareKey", SHARE_KEY).put("FileID", e.id).put("S3keyFlag", e.s3).put("Size", e.size).put("Etag", e.etag)
        for (h in HOSTS) {
            val j = runCatching {
                http.newCall(Request.Builder().url("$h/b/api/share/download/info").web()
                    .post(body.toString().toRequestBody("application/json;charset=UTF-8".toMediaType())).build())
                    .execute().use { JSONObject(it.body?.string().orEmpty()) }
            }.getOrNull() ?: continue
            lastCode = j.optInt("code", -1)
            if (lastCode == LOGIN_REQUIRED) { Diag.log("UPD", "download/info 5112 需要登录 → share page"); return null } // same answer on every host
            if (lastCode != 0) { Diag.log("UPD", "download/info $lastCode ${j.optString("message")}"); continue }
            val u = j.optJSONObject("data")?.let { it.optString("DownloadURL").ifEmpty { it.optString("DownloadUrl") } }.orEmpty()
            if (u.isEmpty()) continue
            return resolve(u)
        }
        return null
    }

    /**
     * 123 answers the anonymous share download with 5112 「您需要注册登录或付费后下载」 (verified 2026-10-09 for the apk files).
     * When this phone has a 123 云盘 WebDAV account (the share owner's own drive), fetch the same file through WebDAV instead:
     * look for folder 「一维相册」 at the root or one level down, and a file with the same name and size.
     * Returns (url, headers) or null.
     */
    private fun viaWebDav(e: Entry): Pair<String, Map<String, String>>? {
        val accs = runCatching { com.hark.shiguang.cloud.CloudAccounts.all }.getOrDefault(emptyList()).filter { it.url.contains("123pan", true) || it.url.contains("123684") || it.url.contains("123865") }
        for (a in accs) runCatching {
            val src = com.hark.shiguang.cloud.WebDavSource(a)
            val root = src.listBlocking("/")
            val folders = root.filter { it.isDir && it.name == FOLDER_NAME } +
                root.filter { it.isDir && it.name != FOLDER_NAME }.take(30).flatMap { d -> runCatching { src.listBlocking(d.path) }.getOrDefault(emptyList()).filter { it.isDir && it.name == FOLDER_NAME } }
            for (f in folders) {
                val hit = src.listBlocking(f.path).firstOrNull { !it.isDir && it.name == e.name && (e.size <= 0 || it.size == e.size) } ?: continue
                val url = kotlinx.coroutines.runBlocking { src.rawUrl(hit) }
                Diag.log("UPD", "share needs login → using WebDAV account ${a.title}")
                return url to src.headers(url)
            }
        }.onFailure { Diag.log("UPD", "webdav fallback ${a.title}: ${it.message}") }
        return null
    }

    /** 123 often returns a web redirect page (…?params=base64) that answers JSON {data:{redirect_url}}; follow it once. */
    private fun resolve(u: String): String = runCatching {
        val params = Uri.parse(u).getQueryParameter("params")
        if (params != null) {
            val dec = runCatching { String(android.util.Base64.decode(params, android.util.Base64.DEFAULT)) }.getOrNull()
            if (dec != null && dec.startsWith("http")) return@runCatching dec
        }
        val r = http.newBuilder().followRedirects(false).build().newCall(Request.Builder().url(u).web().header("Range", "bytes=0-0").build()).execute()
        r.use {
            it.header("Location")?.let { loc -> return@runCatching loc }
            val ct = it.header("Content-Type").orEmpty()
            if (ct.contains("json")) JSONObject(it.body?.string().orEmpty()).optJSONObject("data")?.optString("redirect_url")?.takeIf { s -> s.startsWith("http") } ?: u else u
        }
    }.getOrDefault(u)

    fun openSharePage(c: Context) {
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHARE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Downloads with a progress notification and opens the installer. Falls back to the share page. */
    suspend fun downloadAndInstall(c: Context, r: Release): Boolean = withContext(Dispatchers.IO) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel("update") == null)
            nm?.createNotificationChannel(NotificationChannel("update", "应用更新", NotificationManager.IMPORTANCE_LOW))
        fun note(p: Int, text: String, ongoing: Boolean) {
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, "update") else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            runCatching { nm?.notify(4109, b.setSmallIcon(R.drawable.ic_notify).setContentTitle("一维相册 ${r.version}").setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true)
                .apply { if (p in 0..100 && ongoing) setProgress(100, p, false) }.build()) }
        }
        val ready = File(c.cacheDir, "update/YiWei-${r.version}.apk")
        if (ready.exists() && (r.apk.size <= 0 || ready.length() == r.apk.size)) { withContext(Dispatchers.Main) { install(c, ready) }; return@withContext true }
        try {
            lastCode = 0
            var hdr: Map<String, String> = emptyMap()
            val url = downloadUrl(r.apk) ?: viaWebDav(r.apk)?.also { hdr = it.second }?.first
                ?: throw Exception(if (lastCode == LOGIN_REQUIRED) "123 云盘分享要求登录才能下载，本机也没有可用的 123 云盘 WebDAV 账户" else "拿不到下载地址")
            val dir = File(c.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "YiWei-${r.version}.apk")
            progress = 0f; note(0, "正在下载", true)
            // WebDAV GET answers 302 to a CDN URL; OkHttp drops Authorization on the cross-host hop by itself
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").apply { hdr.forEach { (k, v) -> header(k, v) } }.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("空响应")
                val total = body.contentLength().takeIf { it > 0 } ?: r.apk.size
                var got = 0L; var lastPct = -1
                body.byteStream().use { ins -> out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; progress = pct / 100f; if (pct % 5 == 0) note(pct, "正在下载 $pct%", true) } }
                } }
                if (r.apk.size > 0 && got != r.apk.size) throw Exception("文件不完整")
            }
            // a 123 「请登录」 HTML page would not start with the ZIP magic
            val head = out.inputStream().use { val b = ByteArray(2); it.read(b); b }
            if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) throw Exception("下载的不是安装包")
            // never hand an older build to the installer (it would fail with a downgrade error)
            val vc = runCatching { @Suppress("DEPRECATION") c.packageManager.getPackageArchiveInfo(out.path, 0)?.versionCode ?: -1 }.getOrDefault(-1)
            if (vc in 0..BuildInfo.code) { out.delete(); throw Exception("网盘里的安装包版本号（$vc）不比当前（${BuildInfo.code}）新") }
            note(100, "下载完成，点安装", false); progress = -1f
            withContext(Dispatchers.Main) { install(c, out) }
            true
        } catch (e: Exception) {
            Diag.log("UPD", "download failed: ${e.message}")
            progress = -1f; runCatching { nm?.cancel(4109) }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(c, "应用内下载失败（${e.message}），已在浏览器打开 123 云盘分享页，请在那里下载安装包", android.widget.Toast.LENGTH_LONG).show()
                openSharePage(c)
            }
            false
        }
    }

    fun install(c: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(c, "请允许「一维相册」安装应用，返回后再点一次更新", android.widget.Toast.LENGTH_LONG).show()
            runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", apk)
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { openSharePage(c) }
    }
}
