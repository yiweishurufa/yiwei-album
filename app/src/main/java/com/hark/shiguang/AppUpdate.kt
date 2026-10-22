package com.hark.shiguang

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import com.hark.shiguang.data.FnClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线更新：只走 GitHub（用户 2026-10-22 要求，彻底停用 123 网盘检测；以后禁止再接网盘）。
 *
 * 版本信息：CI（.github/workflows/android.yml）每次发布 android-nightly 时随 APK 一起上传 version.json：
 *   {versionCode, versionName, build(=CI run number), notes, apk, sha256, size, commit}
 * 读不到 version.json 时退而读 GitHub API releases/tags/android-nightly 的 assets（digest = "sha256:…"）。
 *
 * 国内不翻墙：每个 GitHub 地址按 上次成功的源 → ghfast.top → gh-proxy.com → GitHub 直连 依次尝试（同 yiwei-ime
 * helper/Features/Mirrors.cs），短超时，失败自动换下一个。代理只是转发，安装包的正确性由 SHA-256 + 包名 + 签名把关。
 * 只信任 https://github.com/yiweishurufa/ 下的地址；version.json 里给出别的地址一律拒绝。
 */
object AppUpdate {
    private const val TRUSTED = "https://github.com/yiweishurufa/"
    private const val REPO = "${TRUSTED}yiwei-album"
    const val APK_URL = "$REPO/releases/download/android-nightly/yiwei-album.apk"
    const val VERSION_URL = "$REPO/releases/download/android-nightly/version.json"
    private const val API_URL = "https://api.github.com/repos/yiweishurufa/yiwei-album/releases/tags/android-nightly"

    /** One way to reach a GitHub url. [map] returns null when this source cannot serve it. */
    private class Mirror(val id: String, val name: String, val map: (String) -> String?)
    private val MIRRORS = listOf(
        Mirror("ghfast", "ghfast.top 加速") { u -> if (u.startsWith("https://github.com/")) "https://ghfast.top/$u" else null },  // ghfast 不转发 api.github.com
        Mirror("ghproxy", "gh-proxy.com 加速") { u -> "https://gh-proxy.com/$u" },
        Mirror("github", "GitHub 直连") { u -> u },
    )
    /** Last source that worked goes first next time. */
    private fun ordered(): List<Mirror> { val last = Store.getStr("upd.mirror"); return MIRRORS.sortedBy { if (it.id == last) 0 else 1 } }

    // short connect timeout so a dead mirror is skipped quickly; inherits NetEnv dns/socketFactory
    private val http by lazy { FnClient.http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build() }
    private val small by lazy { http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    data class Release(val version: String, val code: Int, val build: Int, val notes: String, val apkUrl: String, val sha256: String, val size: Long) {
        /** Key for 「以后再说」. */
        val skipKey: String get() = "$version-$code-$build"
    }

    /** Shown by [com.hark.shiguang.ui.UpdateDialog]. */
    var offer by mutableStateOf<Release?>(null)
    /** B5: set when a downloaded APK's signer differs from the installed app — shown as a dialog, installer never launched. */
    var signatureMismatch by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f)
    var checking by mutableStateOf(false)
    /** Shown in the update dialog. */
    const val FALLBACK_TEXT = "安装包来自 GitHub（国内自动走 ghfast.top / gh-proxy.com 加速），下载后校验 SHA-256、包名和签名再安装。应用内下载失败时可点「浏览器下载」。"

    private fun Request.Builder.ua() = header("User-Agent", "YiWei-Album/${BuildInfo.version} (Android)")

    /** GET a small text through the mirror chain; throws with every source's failure listed. */
    private fun fetchText(url: String, accept: String? = null): String {
        val errs = ArrayList<String>()
        for (m in ordered()) {
            val u = m.map(url) ?: continue
            try {
                small.newCall(Request.Builder().url(u).ua().apply { accept?.let { header("Accept", it) } }.build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    val t = r.body?.string().orEmpty()
                    if (t.isBlank()) throw Exception("空响应")
                    Store.putStr("upd.mirror", m.id)
                    return t
                }
            } catch (e: Exception) { errs += "${m.name}：${e.message ?: e.javaClass.simpleName}"; Diag.log("UPD", "fetch ${m.id} failed: ${e.message}") }
        }
        throw Exception(errs.joinToString("；").ifEmpty { "没有可用的下载源" })
    }

    private fun trusted(u: String) = UpdateRules.trusted(u)

    /** version.json written by CI. */
    private fun fromVersionJson(): Release {
        val j = JSONObject(fetchText(VERSION_URL))
        val apk = j.optString("apk").ifEmpty { APK_URL }
        if (!trusted(apk)) throw Exception("版本信息里的下载地址不在 github.com/yiweishurufa/ 下，已拒绝")
        return Release(j.optString("versionName"), j.optInt("versionCode", -1), j.optInt("build", 0), j.optString("notes"), apk,
            j.optString("sha256").lowercase().trim(), j.optLong("size", 0L))
    }

    /** Fallback: GitHub API release assets (digest "sha256:…"); versionName / CI run read from the release notes. */
    private fun fromApi(): Release {
        val j = JSONObject(fetchText(API_URL, "application/vnd.github+json"))
        val assets = j.optJSONArray("assets")?.objs().orEmpty()
        val a = assets.firstOrNull { it.optString("name") == "yiwei-album.apk" } ?: throw Exception("GitHub 发布里没有 yiwei-album.apk")
        val body = j.optString("body")
        val name = Regex("测试版\\s*(\\d+(?:\\.\\d+)+)").find(body)?.groupValues?.get(1) ?: BuildInfo.version
        val run = Regex("CI\\s*#(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        // the API does not carry versionCode: same name = same code; a higher name counts as newer (the APK's real code is still checked before installing)
        val code = when { cmp(name, BuildInfo.version) > 0 -> BuildInfo.code + 1; cmp(name, BuildInfo.version) < 0 -> BuildInfo.code - 1; else -> BuildInfo.code }
        return Release(name, code, run, body.take(2000), APK_URL, a.optString("digest").removePrefix("sha256:").lowercase().trim(), a.optLong("size"))
    }

    /** "1.0.10" > "1.0.9"; non-digits ignored. */
    fun cmp(a: String, b: String): Int = UpdateRules.cmp(a, b)

    /** Newer = higher versionCode, or the same versionCode from a later CI run (nightlies share a versionCode). */
    fun isNewer(r: Release): Boolean = UpdateRules.isNewer(r.code, r.build, BuildInfo.code, BuildInfo.build)

    /** Latest release newer than the installed one, or null. Throws 「第 1 步 获取版本信息失败：…」. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val r = try { fromVersionJson() } catch (e: Exception) {
            Diag.log("UPD", "version.json failed: ${e.message} → GitHub API")
            try { fromApi() } catch (e2: Exception) { throw Exception("第 1 步 获取版本信息失败：${e.message}") }
        }
        if (isNewer(r)) r else null
    }

    /** At most once per 12 h, silently. */
    suspend fun autoCheck() {
        val last = Store.getStr("upd.last").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        Store.putStr("upd.last", System.currentTimeMillis().toString())
        runCatching { latest() }.onSuccess { r -> if (r != null && Store.getStr("upd.skip") != r.skipKey) offer = r }
            .onFailure { Diag.log("UPD", "check failed: ${it.message}") }
    }

    /** From the 关于 row: returns a message for a toast when there is nothing to offer. */
    suspend fun manualCheck(): String? {
        checking = true
        try {
            val r = latest() ?: return "已是最新版本 ${BuildInfo.label}"
            offer = r; return null
        } catch (e: Exception) {
            return "检查更新失败。${e.message ?: "网络错误"}"
        } finally { checking = false }
    }

    /** Browser fallback: the fixed APK link through the first mirror (github.com itself is often unreachable in 大陆). */
    fun openInBrowser(c: Context) {
        val u = MIRRORS.first().map(APK_URL) ?: APK_URL
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Downloads through the mirror chain with a progress notification, verifies, opens the installer. */
    suspend fun downloadAndInstall(c: Context, r: Release): Boolean = withContext(Dispatchers.IO) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel("update") == null)
            nm?.createNotificationChannel(NotificationChannel("update", "应用更新", NotificationManager.IMPORTANCE_LOW))
        fun note(p: Int, text: String, ongoing: Boolean) {
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, "update") else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            runCatching { nm?.notify(4109, b.setSmallIcon(R.drawable.ic_notify).setContentTitle("一维相册 ${r.version}").setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true)
                .apply { if (p in 0..100 && ongoing) setProgress(100, p, false) }.build()) }
        }
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "YiWei-${r.version}-${r.code}-${r.build}.apk")
        try {
            if (!trusted(r.apkUrl)) throw Exception("下载地址不在 github.com/yiweishurufa/ 下，已拒绝")
            if (!UpdateRules.isSha256Hex(r.sha256)) throw Exception("第 2 步 下载前检查失败：GitHub 版本信息里没有 SHA-256，无法校验，已取消")
            // B37 + B5: a reused download is re-verified before the installer sees it
            if (!(out.exists() && verifyApk(c, out, r) == null)) {
                dir.listFiles()?.forEach { it.delete() }
                progress = 0f; note(0, "正在从 GitHub 下载", true)
                val errs = ArrayList<String>(); var ok = false
                for (m in ordered()) {
                    val u = m.map(r.apkUrl) ?: continue
                    try {
                        http.newCall(Request.Builder().url(u).ua().build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            val body = resp.body ?: throw Exception("空响应")
                            val ct = resp.header("Content-Type").orEmpty()
                            if (ct.contains("text/html")) throw Exception("返回的是网页不是安装包")
                            val total = body.contentLength().takeIf { it > 0 } ?: r.size
                            var got = 0L; var lastPct = -1
                            body.byteStream().use { ins -> out.outputStream().use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); got += n
                                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                                    if (pct != lastPct) { lastPct = pct; progress = pct / 100f; if (pct % 5 == 0) note(pct, "正在下载 $pct%（${m.name}）", true) } }
                            } }
                            if (r.size > 0 && got != r.size) throw Exception("文件不完整（$got / ${r.size}）")
                        }
                        Store.putStr("upd.mirror", m.id); ok = true; break
                    } catch (e: Exception) {
                        out.delete(); errs += "${m.name}：${e.message ?: e.javaClass.simpleName}"; Diag.log("UPD", "download ${m.id} failed: ${e.message}")
                    }
                }
                if (!ok) throw Exception("第 2 步 下载安装包失败：${errs.joinToString("；")}")
                verifyApk(c, out, r)?.let { out.delete(); throw Exception("第 3 步 校验安装包失败：$it") }
            }
            checkSignature(c, out)
            note(100, "下载完成，点安装", false); progress = -1f
            withContext(Dispatchers.Main) { install(c, out) }
            true
        } catch (e: SignatureMismatch) {
            Diag.log("UPD", "download failed: signature mismatch, installer NOT launched")
            progress = -1f; runCatching { nm?.cancel(4109) }; runCatching { out.delete() }
            signatureMismatchAbort(c, e.message ?: "签名不一致，需先卸载旧版")
        } catch (e: Exception) {
            Diag.log("UPD", "update failed: ${e.message}")
            progress = -1f; runCatching { nm?.cancel(4109) }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(c, "应用内更新失败。${e.message}\n可在更新窗口点「浏览器下载」。", android.widget.Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    /** Thrown when a downloaded APK is signed by a different key than the installed app. */
    class SignatureMismatch(msg: String = "签名不一致，需先卸载旧版") : Exception(msg)

    /**
     * 一维相册发布用过的签名证书 SHA-256。
     * - 84ff9c2e…：CI 测试签名（signing/yiwei-album-test.p12，公开密钥）。TODO(正式签名启用后)：过渡期结束时移除，见 TODO-用户反馈-2026-10-22.md。
     * - 04812323…：1.0.2 及以前用户自己的正式签名（yiwei-release.jks，不在仓库里）。
     * 换成新的正式签名时，把新证书指纹加进来。
     */
    private val TRUSTED_SIGNERS = setOf(
        "84ff9c2e3dcc19685117a31f77fa1ff530131663c4fda58a9a60660ee8880fe7",
        "04812323e88a940c72008ab5c5ba171ab8c094376d366627501b55ef3f0bc4c3",
    )

    /** SHA-256 of the first signer certificate of an APK file, or null when it cannot be read. */
    private fun apkSignerSha256(c: Context, apk: File): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return@runCatching null
        val certs = info.signingInfo?.apkContentsSigners ?: return@runCatching null
        if (certs.isEmpty()) return@runCatching null
        java.security.MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** SHA-256 of the first signer certificate of the installed app, or null when it cannot be read. */
    private fun currentSignerSha256(c: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info.signingInfo?.apkContentsSigners ?: return@runCatching null
        if (certs.isEmpty()) return@runCatching null
        java.security.MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * B5: the downloaded APK must be signed by the same key as the installed app, or the installer
     * would refuse (or a swapped package could install). Fail-closed: an unreadable signature counts
     * as a mismatch — the installer is never launched.
     */
    private fun checkSignature(c: Context, apk: File) {
        val want = currentSignerSha256(c)
        val got = apkSignerSha256(c, apk)
        when (UpdateRules.classifySigner(want, got, TRUSTED_SIGNERS)) {
            UpdateRules.Signer.SAME -> return
            UpdateRules.Signer.TRUSTED_OTHER -> {
                Diag.log("UPD", "apk signed by the other yiwei key (test <-> release), installer NOT launched")
                throw SignatureMismatch("新版本换了签名（测试签名 ↔ 正式签名），系统不允许直接覆盖。请先在设置里「同步设置到其他手机」备份设置，卸载旧版后再用「浏览器下载」安装")
            }
            UpdateRules.Signer.UNKNOWN -> {
                Diag.log("UPD", "signature unknown (currentReadable=${want != null}, apkReadable=${got != null}), installer NOT launched")
                throw SignatureMismatch(if (want == null || got == null) "读不出安装包签名，为安全起见已取消安装" else "安装包签名不是一维相册发布用的签名，可能被篡改，已拒绝安装")
            }
        }
    }

    /** B5: record + surface a signature mismatch (dialog via [signatureMismatch]); returns false for the caller. */
    private suspend fun signatureMismatchAbort(c: Context, msg: String): Boolean {
        signatureMismatch = msg
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }

    /**
     * B37: verifies a downloaded APK — ZIP magic ("PK"), size, SHA-256 (from GitHub version info, required),
     * package name, and a versionCode not older than the installed one. Returns null when OK.
     */
    fun verifyApk(c: Context, f: File, r: Release): String? {
        UpdateRules.checkFile(f, r.size, r.sha256)?.let { return it }
        @Suppress("DEPRECATION") val info = runCatching { c.packageManager.getPackageArchiveInfo(f.path, 0) }.getOrNull() ?: return "读不出安装包信息"
        if (info.packageName != c.packageName) return "包名不对（${info.packageName}）"
        @Suppress("DEPRECATION") val vc = info.versionCode
        // never hand an older build to the installer (it would fail with a downgrade error)
        if (vc < BuildInfo.code) return "GitHub 上的安装包版本号（$vc）比当前（${BuildInfo.code}）旧"
        return null
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
        }.onFailure { openInBrowser(c) }
    }
}
