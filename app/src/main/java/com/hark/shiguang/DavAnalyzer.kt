package com.hark.shiguang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.BatteryManager
import androidx.compose.runtime.*
import com.hark.shiguang.cloud.*
import com.hark.shiguang.data.Photo
import com.hark.shiguang.ui.CloudPhotos
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import com.hark.shiguang.index.IndexDiff
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

// 1.1.0 拆分自 Dav.kt（只移动代码，行为不变）：本地分析、扫描条件、扫描启动与网络状态。
    /** On-device analysis: EXIF GPS/time from the first 128 KB (HTTP Range), headMd5 (MD5 of that head) + size for duplicates, dHash from the EXIF thumbnail. */
object Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** B28: one job per account — analysing account A never cancels / blocks account B. */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val geoCache = HashMap<String, String>()

    /** Local analysis + faces: Wi-Fi and charging unless the user allowed otherwise. */
    fun canScan(c: Context): Boolean = (ScanPolicy.allowCellular || Net.isWifi(c)) && (ScanPolicy.allowNoCharge || Net.isCharging(c))
    val running: Boolean get() = jobs.values.any { it.isActive }

    /**
     * [manual] runs regardless of Wi-Fi/charging. Three phases, each only for what is still missing:
     * 1) photo analysis (EXIF/md5/dHash), 2) 1.0.10 one-time camera backfill for photos analysed before 1.0.9,
     * 3) 1.0.10 WebDAV video durations (MediaMetadataRetriever on the stream, 2 at a time).
     */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (jobs[lib.accountId]?.isActive == true) return
        if (!manual && !canScan(c)) return
        val needPhotos = lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.done != true }
        val needCam = !camFillDone(lib) && lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }
        val needDur = lib.photos.any { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 }
        val needMv = lib.photos.any { motionTodo(lib, it) }
        if (!needPhotos && !needCam && !needDur && !needMv) return
        ScanService.ensure(c)
        jobs[lib.accountId] = scope.launch {
            lib.analyzing = true
            withContext(Dispatchers.Main) { lib.analyzed = lib.meta.values.count { it.done } }
            try {
                val s = lib.src ?: return@launch
                val ok = { manual || canScan(c) }
                if (needPhotos) {
                    val todo = lib.photos.filter { !it.isVideo && lib.meta[it.cloudPath]?.done != true }
                    val gate = kotlinx.coroutines.sync.Semaphore(if (NetEnv.anySlow) 8 else 4)
                    for (chunk in todo.chunked(24)) {
                        if (!isActive || !ok()) break
                        // B16: one photo's failure must not abort the whole chunk — collect and continue.
                        chunk.map { p -> async { gate.acquire(); try { runCatching { analyze(c, s, p, lib) }.onFailure { Diag.log("DAV", "analyze ${p.cloudPath}: ${it.message}") } } finally { gate.release() } } }.awaitAll()
                        lib.analyzed = lib.meta.values.count { it.done }
                        lib.metaVersion++
                        lib.saveMeta()
                    }
                    withContext(Dispatchers.Main) { lib.retime() }
                }
                if (needCam && isActive && ok()) camBackfill(s, lib, ok)
                if (needMv && isActive && ok()) motionBackfill(s, lib, ok)
                if (needDur && isActive && ok()) videoDurations(lib, ok)
            } finally { lib.analyzing = false; jobs.remove(lib.accountId) }
        }
    }

    private fun camKey(lib: DavLib) = "dav.camfill.${lib.accountId}"
    private fun camFillDone(lib: DavLib) = Store.getStr(camKey(lib)) == "1"

    /** 1.0.10: Range bytes=0-131071 + ExifInterface Make/Model only; no AI / faces / hashes are redone. Done-flag persisted per account. */
    private suspend fun camBackfill(s: WebDavSource, lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        var stopped = false
        for (chunk in todo.chunked(30)) {
            if (!isActive || !ok()) { stopped = true; break }
            chunk.map { p -> async { gate.acquire(); try {
                runCatching {
                    val m = lib.meta[p.cloudPath] ?: return@runCatching
                    val head = s.headBytes(p.cloudPath) ?: return@runCatching // network error: retry on a later run
                    runCatching { m.cam = camOf(ExifInterface(ByteArrayInputStream(head))) }
                    m.camChecked = true
                }.onFailure { Diag.log("DAV", "camBackfill ${p.cloudPath}: ${it.message}") }
            } finally { gate.release() } } }.awaitAll()
            lib.metaVersion++
            lib.saveMeta()
        }
        if (!stopped && lib.photos.none { !it.isVideo && lib.meta[it.cloudPath]?.let { m -> m.done && !m.camChecked } == true }) Store.putStr(camKey(lib), "1")
    }

    /**
     * 1.0.1: photos analysed before 1.0.1 never had their XMP checked for Motion Photo. Re-reading 128 KB of every photo
     * would be expensive, so only names that Android cameras give motion photos (MVIMG_*, *.MP.jpg, *_MP.jpg, PXL_*) are re-checked.
     */
    private fun motionTodo(lib: DavLib, p: Photo): Boolean = !p.isVideo && LivePairing.motionNameHint(p.fileName) &&
        lib.meta[p.cloudPath]?.let { it.done && !it.mvChecked } == true

    private suspend fun motionBackfill(s: WebDavSource, lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { motionTodo(lib, it) }
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        for (chunk in todo.chunked(30)) {
            if (!isActive || !ok()) break
            chunk.map { p -> async { gate.acquire(); try {
                // B15: per-file runCatching; a network miss leaves mvChecked=false (untried, retried later),
                // only a successfully parsed head marks the photo tried.
                runCatching {
                    val m = lib.meta[p.cloudPath] ?: return@runCatching
                    val head = s.headBytes(p.cloudPath) ?: return@runCatching
                    m.mv = LivePairing.motionOffset(head); m.mvChecked = true
                }.onFailure { Diag.log("DAV", "motionBackfill ${p.cloudPath}: ${it.message}") }
            } finally { gate.release() } } }.awaitAll()
            lib.metaVersion++
            lib.saveMeta()
        }
        withContext(Dispatchers.Main) { lib.retime() }
    }

    private fun camOf(ex: ExifInterface): String {
        val make = ex.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty(); val model = ex.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        return if (model.startsWith(make, true)) model else "$make $model".trim()
    }

    /** H8: retrievers that hit the timeout and are still stuck in native code (they only hold their own thread). */
    private val stuckDur = java.util.concurrent.atomic.AtomicInteger(0)

    /** 1.0.10: duration of WebDAV videos for the advanced-search duration filter. 2 at a time, 25 s cap per file, failures marked -1 and skipped. */
    private suspend fun videoDurations(lib: DavLib, ok: () -> Boolean) = coroutineScope {
        val todo = lib.photos.filter { it.isVideo && (lib.meta[it.cloudPath]?.dur ?: 0) == 0 && it.video != null }
        var n = 0
        for (chunk in todo.chunked(2)) {
            // 2026-10-22: stop while a video plays (the rest is picked up on the next analysis run, nothing is marked failed)
            if (!isActive || !ok() || videoPlaying) { if (videoPlaying) Diag.log("DAV", "video durations paused: video playing"); break }
            chunk.map { p -> async(Dispatchers.IO) {
                runCatching {
                    val d = p.video?.let { durationOf(it) } ?: 0
                    val m = lib.meta[p.cloudPath] ?: Meta().also { lib.meta[p.cloudPath] = it }
                    m.dur = if (d > 0) d else -1
                }.onFailure { Diag.log("DAV", "videoDurations ${p.cloudPath}: ${it.message}") }
            } }.awaitAll()
            if (++n % 10 == 0) { lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ } }
        }
        lib.saveMeta(); withContext(Dispatchers.Main) { lib.retime(); lib.metaVersion++ }
    }

    /** Seconds, or 0 on failure / timeout. setDataSource can block on a slow host, so it runs on its own thread with a hard timeout. */
    fun durationOf(url: String): Int {
        // H8: the old fixed pool of 2 got filled by retrievers stuck in setDataSource (Future.cancel cannot interrupt
        // native code), after which every video timed out. Each lookup has its own daemon thread; the caller never waits past 25 s.
        // 2026-10-22 闪退修复: the retriever is created, used and released ONLY on its own thread. H8 used to release() it from
        // another thread while setDataSource was still blocked — MediaMetadataRetriever is not thread-safe, the native object
        // was freed under the running read and the process died with a native crash (no Java stack, no crash dialog),
        // typically while a WebDAV video was playing and starving these reads. A stuck one now just finishes on its own.
        if (stuckDur.get() >= 6) { Diag.log("DAV", "video duration: ${stuckDur.get()} retrievers still stuck, skipping"); return 0 }
        val result = java.util.concurrent.atomic.AtomicInteger(0)
        val lock = Any(); var finished = false; var timedOut = false
        val t = Thread({
            val mmr = android.media.MediaMetadataRetriever()
            try {
                val real = DavThumb.finalUrl(url)
                val h = if (real == url) (ImageAuth.headersFor(url) ?: emptyMap()) else emptyMap()
                if (TlsTrust.needsOkHttpSource(real)) mmr.setDataSource(OkHttpMediaSource(com.hark.shiguang.cloud.CloudHttp.client, real, h)) else mmr.setDataSource(real, h)
                result.set(((mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt())
            } catch (e: Throwable) {
                Diag.log("DAV", "video duration failed: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            } finally {
                runCatching { mmr.release() }
                synchronized(lock) { finished = true; if (timedOut) stuckDur.decrementAndGet() }
            }
        }, "yw-dur").apply { isDaemon = true }
        t.start()
        t.join(25_000)
        if (t.isAlive) {
            // S5: a timeout is logged and the caller moves on to the next file — it never blocks the queue.
            // Count it as stuck until its own thread finishes (it releases the retriever itself).
            val stuck = synchronized(lock) { if (!finished) { timedOut = true; stuckDur.incrementAndGet(); true } else false }
            if (!stuck) return result.get()
            Diag.log("DAV", "video duration timeout (${stuckDur.get()} stuck): ${url.take(120)}")
            return 0
        }
        return result.get()
    }

    /**
     * 2026-10-22 闪退修复: true while the 影音 player is open. Background duration reads (MediaMetadataRetriever over
     * WebDAV) pause meanwhile so they neither compete with the video for bandwidth nor pile up as stuck reads.
     */
    @Volatile var videoPlaying = false

    fun stop() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    /** B28: stop only one account's analysis job. */
    fun stop(accountId: String) { jobs.remove(accountId)?.cancel() }

    /**
     * B14/B16: the whole analysis is inside runCatching — a throw in one photo never kills the chunk.
     * [Meta.done] is set only when the EXIF block actually succeeded; an EXIF failure sets [Meta.failed]
     * (retried on the next pass) while the already-computed md5 is still kept ([Meta.hashDone]).
     */
    private fun analyze(c: Context, s: WebDavSource, p: Photo, lib: DavLib) {
        runCatching {
            val m = lib.meta[p.cloudPath] ?: Meta()
            val head = s.headBytes(p.cloudPath) ?: return
            m.headMd5 = MessageDigest.getInstance("MD5").digest(head).joinToString("") { "%02x".format(it) }.take(16)
            m.hashDone = true
            if (LivePairing.motionCandidateExt(p.fileName)) { m.mv = LivePairing.motionOffset(head); m.mvChecked = true }
            val exifOk = runCatching {
                val ex = ExifInterface(ByteArrayInputStream(head))
                val ll = FloatArray(2)
                if (ex.getLatLong(ll)) { m.lat = ll[0].toDouble(); m.lng = ll[1].toDouble(); m.city = city(c, m.lat, m.lng) }
                (ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME))?.takeIf { it.length >= 19 && !it.startsWith("0000") }?.let { m.time = it.take(19) }
                m.cam = camOf(ex); m.camChecked = true
                val tb = ex.thumbnailBytes
                val bmp = if (tb != null) BitmapFactory.decodeByteArray(tb, 0, tb.size) else BitmapFactory.decodeByteArray(head, 0, head.size)
                if (bmp != null) { m.dhash = dHash(bmp); m.hasHash = true }
            }.isSuccess
            m.done = exifOk
            m.failed = !exifOk
            lib.meta[p.cloudPath] = m
        }.onFailure { Diag.log("DAV", "analyze ${p.cloudPath}: ${it.message}") }
    }

    fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            fun lum(px: Int) = ((px shr 16 and 0xff) * 299 + (px shr 8 and 0xff) * 587 + (px and 0xff) * 114)
            if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    private fun city(c: Context, lat: Double, lng: Double): String {
        val k = "%.2f,%.2f".format(lat, lng)
        synchronized(geoCache) { geoCache[k]?.let { return it } }
        val name = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c, java.util.Locale.CHINA).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                (a.locality ?: a.subAdminArea ?: a.adminArea ?: a.countryName)?.removeSuffix("市")
            }
        }.getOrNull() ?: "%.1f°, %.1f°".format(lat, lng)
        synchronized(geoCache) { geoCache[k] = name }
        return name
    }
}

/**
 * Network + charging rules for everything that reads the library in the background: local analysis, faces,
 * AI labelling and phone backup (1.0.9: AI's own 「仅 Wi-Fi 且充电」 and backup's 「仅 Wi-Fi」 were merged into these two).
 */
object ScanPolicy {
    /** The user pressed 「暂停」 on the AI tab; in memory only, so the next app start resumes normally. */
    var paused by mutableStateOf(false)
    var allowCellular: Boolean get() = Store.getStr("scan.cell") == "1"; set(v) = Store.putStr("scan.cell", if (v) "1" else "")
    var allowNoCharge: Boolean get() = Store.getStr("scan.nocharge") == "1"; set(v) = Store.putStr("scan.nocharge", if (v) "1" else "")
    /**
     * 1.0.9 one-time migration of the two removed switches. Only when the user never touched the scan switches:
     * an explicit 「AI 不限 Wi-Fi/充电」(ai.wc=0) allowed both; backup on cellular (backupWifi=false with backup on) allowed cellular.
     */
    fun migrate() {
        if (Store.getStr("policy.v2") == "1") return
        val untouched = Store.getStr("scan.cell", "?") == "?" && Store.getStr("scan.nocharge", "?") == "?"
        if (untouched) {
            if (Store.getStr("ai.wc", "1") == "0") { allowCellular = true; allowNoCharge = true }
            if (Store.backupOn && !Store.backupWifiOnly) allowCellular = true
        }
        Store.putStr("policy.v2", "1")
    }
    fun waitingText(): String = when {
        !allowCellular && !allowNoCharge -> "连上 Wi-Fi 并充电时自动继续"
        !allowCellular -> "连上 Wi-Fi 时自动继续"
        !allowNoCharge -> "充电时自动继续"
        else -> "会在后台继续"
    }
    /** Single source for the two toggle wordings + the settings reference used by AI / backup pages. */
    const val SETTINGS_REF = "设置 → 网络与电量"
    const val CELLULAR_DESC = "本地分析、人脸识别、AI 分类和手机备份都会读取或上传照片，产生流量"
    const val NO_CHARGE_DESC = "关掉更省电：本地分析、人脸识别和 AI 分类只在充电时继续"
    /**
     * The one pause / resume pair (notification action, AI card toggle, AI tab). Callers keep their own
     * UI entry points; the coordination lives here so the notification and the card can never drift apart.
     * [resumeAll] is the manual path (user pressed 继续): Wi-Fi / charging rules do not hold it back.
     * [startAiDav] is [AiRunner.on] for both callers (B31: the notification must resume local-AI too,
     * not just a configured model). [nasPhotos] is only passed by the notification resume.
     */
    fun pauseAll() {
        paused = true
        runCatching { AiRunner.pause() }
        runCatching { Analyzer.stop() }
        runCatching { Faces.stop() }
        runCatching { OcrRunner.stop() }
    }
    fun resumeAll(c: Context, startAiDav: Boolean, nasPhotos: List<Photo> = emptyList()) {
        paused = false
        runCatching { AiRunner.resume() }
        val lib = runCatching { Dav.lib() }.getOrNull()
        if (lib != null && lib.photos.isNotEmpty()) {
            runCatching { Analyzer.start(c, lib, true) }
            runCatching { Faces.start(c, lib, true) }
            runCatching { OcrRunner.start(c, lib, true) }
            if (startAiDav) runCatching { AiRunner.startDav(c, lib, manual = true) }
        }
        if (nasPhotos.isNotEmpty() && AiConfig.nasToo && AiRunner.on)
            runCatching { AiRunner.startNas(c, nasPhotos.toList()) }
    }
}

/**
 * One starter for the analysis + faces + AI trio (+ 1.1.0 图中文字 OCR; automatic starts honour [ScanPolicy], manual ones
 * do not — [manual] must stay true for the AI card's 「立即继续」 or the Wi-Fi / charging rules are bypassed
 * for every caller). Call sites keep their own paused / empty guards; only the trio itself is shared.
 */
object ScanStarter {
    fun startAll(c: Context, lib: DavLib, manual: Boolean) {
        if (!manual && !Analyzer.canScan(c)) return
        runCatching { Analyzer.start(c, lib, manual) }
        runCatching { Faces.start(c, lib, manual) }
        runCatching { OcrRunner.start(c, lib, manual) }
        if (AiRunner.on) runCatching { AiRunner.startDav(c, lib, manual = manual) }
    }
}

object Net {
    fun isWifi(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    fun online(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun isCharging(c: Context): Boolean = c.getSystemService(BatteryManager::class.java)?.isCharging ?: false
}
