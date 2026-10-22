package com.hark.shiguang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.runtime.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.hark.shiguang.data.Photo
import com.hark.shiguang.index.OcrRow
import com.hark.shiguang.index.PhotoIndexDao
import com.hark.shiguang.index.PhotoIndexDb
import com.hark.shiguang.ui.CloudPhotos
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * 1.1.0「识别图中文字（用于搜索）」：WebDAV 照片的端上 OCR。
 *
 * - 模型：ML Kit 文字识别 v2 中文版，**打包进 APK 的 bundled 版**（`com.google.mlkit:text-recognition-chinese`），
 *   不依赖 Google Play 服务，国内手机离线可用；同时认中文和拉丁字母。
 * - 只用小图，**从不下原图**：先用已缓存的网格缩略图（≤480 px，[DavThumb.cached]），没有就读文件头 128 KB 里的
 *   EXIF 缩略图（JPEG）。两样都没有的（如 PNG 截图还没在网格里出现过）记为「没有小图」，等缩略图缓存后再试。
 *   代价：小图上的小字认不出来，只适合招牌、海报、截图标题这类大字。
 * - 结果存进照片索引数据库的 dav_ocr 表（[OcrRow]），只处理新照片、没识别过的和文件被替换的。
 * - 什么时候跑跟 [Analyzer] / [Faces] 一样：[ScanPolicy] 的网络与充电规则、[ScanStarter] 启动、WorkManager 定时。
 */
object OcrRunner {
    var enabled by mutableStateOf(Store.getStr("ocr.on", "1") == "1")
        private set

    fun set(c: Context, on: Boolean) {
        enabled = on; Store.putStr("ocr.on", if (on) "1" else "0")
        if (!on) stop() else if (!ScanPolicy.paused) Dav.lib()?.let { start(c, it, false) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    val running: Boolean get() = jobs.values.any { it.isActive }

    /** 当前账户：已识别张数 / 照片总数 / 其中认出文字的张数（设置页、通知栏显示）。 */
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var withText by mutableIntStateOf(0)
    /** 暂时没有小图、等缩略图缓存后再识别的张数。 */
    var noImage by mutableIntStateOf(0)
    /** 进度对应的账户（切换账户后先显示 0，再由 [refresh] 补上）。 */
    var countsFor by mutableStateOf("")

    private const val CHUNK = 16
    private const val PARALLEL = 2
    /** EXIF 缩略图短于这个边长就不识别（160×120 的缩略图上几乎认不出字）。 */
    private const val MIN_SIDE = 240

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    private fun dao(): PhotoIndexDao? = runCatching { PhotoIndexDb.get().dao() }
        .onFailure { Diag.log("OCR", "db unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    fun stop() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    fun stop(accountId: String) { jobs.remove(accountId)?.cancel() }

    private fun modifiedOf(p: Photo): Long = CloudPhotos.entryOf(p)?.modified ?: 0L

    private fun thumbUrl(p: Photo): String? = p.thumbS.takeIf { it.endsWith(DavThumb.MARK) }?.removeSuffix(DavThumb.MARK)

    /** 只读计数（设置页打开时调用）。 */
    suspend fun refresh(lib: DavLib) = withContext(Dispatchers.IO) {
        val d = dao() ?: return@withContext
        runCatching {
            val n = d.ocrDone(lib.accountId); val t = d.ocrWithText(lib.accountId); val ni = d.ocrNoImage(lib.accountId)
            withContext(Dispatchers.Main) { done = n; withText = t; noImage = ni; total = lib.imagesCount(); countsFor = lib.accountId }
        }
    }

    /** [manual] 不看 Wi-Fi / 充电条件（「立即继续」）。 */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (!enabled) return
        if (jobs[lib.accountId]?.isActive == true) return
        if (!manual && !Analyzer.canScan(c)) return
        if (lib.photos.none { !it.isVideo }) return
        jobs[lib.accountId] = scope.launch {
            try { run(c, lib, manual) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { Diag.log("OCR", "run failed: ${e.javaClass.simpleName} ${e.message?.take(80)}") }
            finally { jobs.remove(lib.accountId) }
        }
    }

    private suspend fun run(c: Context, lib: DavLib, manual: Boolean) = coroutineScope {
        val d = dao() ?: return@coroutineScope
        val acc = lib.accountId
        val images = lib.photos.toList().filter { !it.isVideo }
        val states = d.ocrStates(acc).associateBy { it.path }
        // 照片已从网盘删除：只在索引完整加载且没在扫描时清理，避免冷启动只载入最近几个月时误删
        if (lib.fullyLoaded && !lib.scanning) {
            val live = images.mapTo(HashSet(images.size * 2)) { it.cloudPath }
            val gone = states.keys.filter { it !in live }
            if (gone.isNotEmpty()) { d.deleteOcrPaths(acc, gone); Diag.log("OCR", "removed ${gone.size} deleted photos") }
        }
        val now = System.currentTimeMillis()
        val todo = images.filter { p ->
            val s = states[p.cloudPath]
            if (s == null) true
            else OcrText.needsOcr(s.state, s.size, s.modified, s.at, p.size, modifiedOf(p),
                hasThumb = s.state == OcrText.STATE_NO_IMAGE && thumbUrl(p)?.let { DavThumb.cached(it) } != null, now = now)
        }
        val n0 = d.ocrDone(acc); val t0w = d.ocrWithText(acc); val ni0 = d.ocrNoImage(acc)
        withContext(Dispatchers.Main) { total = images.size; done = n0; withText = t0w; noImage = ni0; countsFor = acc }
        if (todo.isEmpty()) return@coroutineScope
        val src = lib.src ?: return@coroutineScope
        ScanService.ensure(c)
        Diag.log("OCR", "start: ${todo.size} of ${images.size} photos")
        val t0 = System.currentTimeMillis()
        val gate = Semaphore(PARALLEL)
        var miss = 0
        val chunks = todo.chunked(CHUNK)
        var ci = 0
        while (ci < chunks.size) {
            ensureActive()
            if (!enabled || ScanPolicy.paused && !manual) break
            // Wi-Fi / 充电条件丢了：在这里等（同 Faces），不跳过照片
            if (!manual && !Analyzer.canScan(c)) { delay(20_000); continue }
            val rows = chunks[ci].map { p -> async { gate.withPermit { runCatching { one(src, p, acc) }.onFailure { Diag.log("OCR", "photo failed: ${it.javaClass.simpleName}") }.getOrNull() } } }.awaitAll()
            val ok = rows.filterNotNull()
            if (ok.isNotEmpty()) runCatching { d.putOcr(ok) }.onFailure { Diag.log("OCR", "db write failed: ${it.javaClass.simpleName}") }
            val missed = rows.count { it == null }
            miss = if (ok.isEmpty()) miss + missed else 0
            val n = d.ocrDone(acc); val t = d.ocrWithText(acc); val ni = d.ocrNoImage(acc)
            withContext(Dispatchers.Main) { done = n; withText = t; noImage = ni }
            // 连续几十张都读不到（断网、账户失效）：先停，下次调度再来
            if (miss >= 48) { Diag.log("OCR", "too many read failures, stop for now"); break }
            ci++
        }
        Diag.log("OCR", "run done: ${done}/${total}, with text ${withText}, ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /** 识别一张；返回 null = 网络读不到（不写库，下次重试）。 */
    private suspend fun one(src: com.hark.shiguang.cloud.WebDavSource, p: Photo, acc: String): OcrRow? {
        val now = System.currentTimeMillis()
        fun row(state: Int, text: String) = OcrRow(acc, p.cloudPath, p.size, modifiedOf(p), state, text, OcrText.normalize(text), now)
        val img = smallImage(src, p) ?: return null
        val (bmp, rot) = img.image ?: return row(OcrText.STATE_NO_IMAGE, "")
        return try {
            val text = withContext(Dispatchers.Default) {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, rot)), 30, TimeUnit.SECONDS).text
            }
            row(OcrText.STATE_DONE, OcrText.clip(text))
        } finally { bmp.recycle() }
    }

    /** image = null：没有可用的小图（不是网络错误）。 */
    private class Small(val image: Pair<Bitmap, Int>?)

    /** 只用缩略图：已缓存的网格缩略图 → JPEG 文件头里的 EXIF 缩略图。返回 null = 网络读不到。 */
    private fun smallImage(src: com.hark.shiguang.cloud.WebDavSource, p: Photo): Small? {
        thumbUrl(p)?.let { DavThumb.cached(it) }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { return Small(it to 0) }   // 缓存缩略图已按 EXIF 方向转正
        }
        val n = p.fileName.lowercase()
        if (!(n.endsWith(".jpg") || n.endsWith(".jpeg"))) return Small(null)
        val head = src.headBytes(p.cloudPath) ?: return null
        val ex = runCatching { ExifInterface(ByteArrayInputStream(head)) }.getOrNull() ?: return Small(null)
        val t = ex.thumbnailBytes ?: return Small(null)
        val b = BitmapFactory.decodeByteArray(t, 0, t.size) ?: return Small(null)
        if (maxOf(b.width, b.height) < MIN_SIDE) { b.recycle(); return Small(null) }
        val rot = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
        }
        return Small(b to rot)
    }

    /**
     * 搜索：照片里的文字包含 [query] 的每个词（归一化后比较）。数据库先用最长的词粗筛，再在内存里精确匹配。
     * 返回的照片按 [DavLib.photos] 的顺序（时间线顺序）。
     */
    suspend fun search(lib: DavLib, query: String): List<Photo> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val terms = OcrText.terms(query)
        val pre = OcrText.prefilterTerm(terms) ?: return@withContext emptyList()
        val d = dao() ?: return@withContext emptyList()
        val hits = runCatching { d.ocrCandidates(lib.accountId, OcrText.likePattern(pre)) }
            .onFailure { Diag.log("OCR", "search failed: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
            .filter { OcrText.matches(it.norm, terms) }.mapTo(HashSet()) { it.path }
        if (hits.isEmpty()) emptyList() else lib.photos.toList().filter { !it.isVideo && it.cloudPath in hits }
    }
}
