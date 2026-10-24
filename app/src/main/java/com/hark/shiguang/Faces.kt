package com.hark.shiguang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.runtime.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * WebDAV 人脸识别。参数语义参照飞牛相册：
 *  - 人脸置信度阈值：低于它的人脸（侧脸、模糊、太小）不参与归类。
 *  - 生成人物的最少照片数：一个人至少出现在这么多张照片里才生成人物。
 *  - 人物相似度差异阈值：两张脸的差异（1 - 余弦相似度）小于它才算同一个人；越小越严格。
 * 检测用 ML Kit（本地），特征用 MobileFaceNet（本地，192 维），大模型只做辅助：
 * AI 整理时会顺带数人脸，标为「无人脸」的照片直接跳过，省流量。
 */
object FaceConfig {
    const val DEF_CONF = 0.65f
    const val DEF_MIN = 5
    const val DEF_DIFF = 0.40f
    var conf by mutableFloatStateOf(Store.getStr("face.conf", "$DEF_CONF").toFloatOrNull() ?: DEF_CONF)
    var minPhotos by mutableIntStateOf(Store.getStr("face.min", "$DEF_MIN").toIntOrNull() ?: DEF_MIN)
    // 0.36 was the old default; with aligned + flipped embeddings 0.40 keeps one person together without merging strangers
    var diff by mutableFloatStateOf((Store.getStr("face.diff", "$DEF_DIFF").toFloatOrNull() ?: DEF_DIFF).let { if (it == 0.36f) DEF_DIFF else it })
    fun save() {
        Store.putStr("face.conf", "%.2f".format(java.util.Locale.US, conf))
        Store.putStr("face.min", "$minPhotos")
        Store.putStr("face.diff", "%.2f".format(java.util.Locale.US, diff))
    }

}

class FaceRec(val id: String, val path: String, val conf: Float, val emb: FloatArray)

/** [group]: id of a manual group (merge / hide / remove edits), empty for a plain cluster. */
data class FacePerson(val key: String, val name: String, val cover: String, val faces: List<FaceRec>, val photos: List<Photo>, val group: String = "")

/** One WebDAV account's faces: detected faces (saved), persons (re-clustered from the saved faces whenever a setting changes). */
class FaceLib(val accountId: String) {
    private val dir: File get() = File(App.ctx.filesDir, "dav/$accountId/faces").apply { mkdirs() }
    val faces = java.util.concurrent.ConcurrentHashMap<String, MutableList<FaceRec>>()   // photo path -> faces
    val scanned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** B9: photos whose detection failed (download / decode), with the failure time — retried on the next start, never marked scanned. */
    val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val names = HashMap<String, String>()     // face id (anchor) -> name
    var persons by mutableStateOf<List<FacePerson>>(emptyList())
    /** Persons the user hid (路人). */
    var hiddenPersons by mutableStateOf<List<FacePerson>>(emptyList())
    /** Manual adjustments; they survive every re-clustering (see FaceEdits.kt). */
    val edits: FaceEdits by lazy { FaceEdits(File(App.ctx.filesDir, "dav/$accountId/faces")) }
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var status by mutableStateOf("")
    /** 用户 2026-10-23 反馈 #7：出错原因（模型加载失败、任务异常），非空时界面显示原因 + 重试；重试或成功启动时清空。 */
    var error by mutableStateOf("")
    private var loaded = false
    private var lastClusterLog = ""

    fun cropFile(faceId: String) = File(dir, "$faceId.jpg")

    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(File(dir, "faces.json").readText())
            o.optJSONObject("names")?.let { n -> n.keys().forEach { names[it] = n.getString(it) } }
            // embeddings from before 1.0.8 used a loose crop; they must be recomputed with the aligned crop
            if (o.optInt("v", 1) < ALG) return@runCatching
            o.optJSONArray("scanned")?.let { a -> for (i in 0 until a.length()) scanned.add(a.getString(i)) }
            o.optJSONObject("failed")?.let { f -> f.keys().forEach { failed[it] = f.optLong(it) } }
            o.optJSONObject("names")?.let { n -> n.keys().forEach { names[it] = n.getString(it) } }
            val fa = o.optJSONArray("faces") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                val bytes = android.util.Base64.decode(f.getString("e"), android.util.Base64.NO_WRAP)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val emb = FloatArray(bb.remaining()).also { bb.get(it) }
                val r = FaceRec(f.getString("i"), f.getString("p"), f.getDouble("c").toFloat(), emb)
                faces.getOrPut(r.path) { mutableListOf() }.add(r)
            }
        }
    }

    @Synchronized fun save() = runCatching {
        val fa = JSONArray()
        faces.values.flatten().forEach { r ->
            val bb = ByteBuffer.allocate(r.emb.size * 4).order(ByteOrder.LITTLE_ENDIAN); r.emb.forEach { bb.putFloat(it) }
            fa.put(JSONObject().put("i", r.id).put("p", r.path).put("c", r.conf.toDouble()).put("e", android.util.Base64.encodeToString(bb.array(), android.util.Base64.NO_WRAP)))
        }
        val nm = JSONObject(); names.forEach { (k, v) -> nm.put(k, v) }
        val fl = JSONObject(); failed.forEach { (k, v) -> fl.put(k, v) }
        // 用户 2026-10-24 要求排查：先写临时文件再改名。以前直接覆盖 faces.json，整理中途被系统杀掉会留下半个文件，
        // 下次读不出来就静默丢掉全部人脸结果（命名也没了），要从头识别。
        val text = JSONObject().put("scanned", JSONArray(scanned.toList())).put("names", nm).put("faces", fa).put("failed", fl).put("v", ALG).toString()
        val tmp = File(dir, "faces.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, "faces.json"))) { File(dir, "faces.json").writeText(text); tmp.delete() }
    }

    @Synchronized fun rename(p: FacePerson, name: String) {
        p.faces.forEach { names.remove(it.id) }
        if (name.isNotBlank()) names[p.key] = name.trim()
        save(); persons = persons.map { if (it.key == p.key) it.copy(name = name.trim()) else it }
    }

    fun clear() { faces.clear(); scanned.clear(); failed.clear(); names.clear(); persons = emptyList(); hiddenPersons = emptyList(); edits.clear(); dir.deleteRecursively() }

    /**
     * Clustering on the saved faces with the current three settings:
     * 1) greedy pass in confidence order, 2) merge clusters whose centres are close (one person seen in
     * different light / ages was split before), 3) one refinement pass reassigning every face to its nearest centre.
     */
    @Synchronized fun cluster(lib: DavLib) {
        // 用户 2026-10-24 要求排查：聚类会在后台线程跑（见 Faces.scanAll），照片列表取一个只读快照再遍历，
        // 避免主线程同时增删照片时遍历 SnapshotStateList 抛 ConcurrentModificationException。
        val photos = androidx.compose.runtime.snapshots.Snapshot.takeSnapshot().let { snap -> try { snap.enter { lib.photos.toList() } } finally { snap.dispose() } }
        val byPath = photos.associateBy { it.cloudPath }
        val all = faces.values.flatten().filter { it.conf >= FaceConfig.conf && byPath.containsKey(it.path) }.sortedByDescending { it.conf }
        val thr = FaceConfig.diff
        var cents = ArrayList<FloatArray>(); var members = ArrayList<MutableList<FaceRec>>()
        for (f in all) {
            var best = -1; var bestD = Float.MAX_VALUE
            for (i in cents.indices) { val d = 1f - dot(cents[i], f.emb); if (d < bestD) { bestD = d; best = i } }
            if (best >= 0 && bestD < thr) {
                members[best].add(f)
                val c = cents[best]; val n = members[best].size
                for (k in c.indices) c[k] = c[k] + (f.emb[k] - c[k]) / n
                normalize(c)
            } else { cents.add(f.emb.copyOf()); members.add(mutableListOf(f)) }
        }
        // merge: average-linkage on centres, a little looser than the join threshold
        val mergeThr = thr + 0.07f
        var merged = true
        while (merged && cents.size > 1) {
            merged = false
            var bi = -1; var bj = -1; var bd = Float.MAX_VALUE
            for (i in cents.indices) for (j in i + 1 until cents.size) {
                if (members[i].size < 2 && members[j].size < 2) continue
                val d = 1f - dot(cents[i], cents[j]); if (d < bd) { bd = d; bi = i; bj = j }
            }
            if (bi >= 0 && bd < mergeThr) {
                val ni = members[bi].size; val nj = members[bj].size
                val c = FloatArray(cents[bi].size) { (cents[bi][it] * ni + cents[bj][it] * nj) / (ni + nj) }; normalize(c)
                cents[bi] = c; members[bi].addAll(members[bj]); cents.removeAt(bj); members.removeAt(bj); merged = true
            }
        }
        // refine: everyone to the nearest centre (keeps outliers from dragging a cluster)
        if (cents.isNotEmpty()) {
            val re = cents.map { mutableListOf<FaceRec>() }
            for (f in all) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (i in cents.indices) { val d = 1f - dot(cents[i], f.emb); if (d < bestD) { bestD = d; best = i } }
                if (bestD < mergeThr) re[best].add(f) else members.firstOrNull { f in it }?.let { m -> re[members.indexOf(m)].add(f) }
            }
            members = ArrayList(re.map { it.sortedByDescending { r -> r.conf }.toMutableList() }.filter { it.isNotEmpty() })
        }
        val built = edits.build(members.map { it.toList() }, byPath, names) { com.hark.shiguang.Hidden.hasDav(accountId, it) }
        persons = built.first; hiddenPersons = built.second
        // FACE 诊断：只在结果变化时记一行（cluster 会被页面频繁调用）
        val small = members.count { m -> m.map { it.path }.distinct().size < FaceConfig.minPhotos }
        val line = "cluster: ${faces.values.sumOf { it.size }} faces in ${scanned.size} scanned photos, ${all.size} pass conf ${FaceConfig.conf} → " +
            "${members.size} groups, $small below ${FaceConfig.minPhotos} photos → ${persons.size} persons (+${hiddenPersons.size} hidden), diff ${FaceConfig.diff}"
        if (line != lastClusterLog) { lastClusterLog = line; Diag.log("FACE", line) }
    }

    companion object {
        const val ALG = 2
        fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
        fun normalize(v: FloatArray) { var s = 0f; v.forEach { s += it * it }; val n = sqrt(s).coerceAtLeast(1e-6f); for (i in v.indices) v[i] /= n }
    }
}

object Faces {
    private val libs = HashMap<String, FaceLib>()
    fun lib(id: String): FaceLib = synchronized(libs) { libs.getOrPut(id) { FaceLib(id).also { it.load() } } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** B28: one job per account — scanning account A never cancels account B. */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var interp: Interpreter? = null
    private var inShape = intArrayOf(1, 112, 112, 3)
    private var outDim = 192
    @Volatile private var mlkitWarned = false
    @Volatile private var lastStartLog = ""
    /** start() 被页面和后台频繁调用：同样的「为什么没开始」只记一次。 */
    private fun logOnce(msg: String) { if (msg != lastStartLog) { lastStartLog = msg; Diag.log("FACE", msg) } }

    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.06f).build())
    }

    /** 用户 2026-10-24 要求排查：两个账号同时开始识别时不再各建一个解释器；映射完就关掉文件句柄（映射本身仍有效）。 */
    @Synchronized private fun model(c: Context): Interpreter {
        interp?.let { return it }
        val buf = c.assets.openFd(MODEL).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        val i = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        inShape = i.getInputTensor(0).shape()
        outDim = i.getOutputTensor(0).shape().last()
        interp = i
        return i
    }

    fun stop() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    /** B28: stop only one account's face job. */
    fun stop(accountId: String) { jobs.remove(accountId)?.cancel() }
    val running: Boolean get() = jobs.values.any { it.isActive }

    /** 人脸模型（MobileFaceNet）是否打包进了 APK。用户 2026-10-23 反馈 #7 的根因就是它缺失。 */
    fun modelPresent(c: Context): Boolean = runCatching { c.assets.openFd(MODEL).close(); true }.getOrDefault(false)
    private const val MODEL = "mobilefacenet.tflite"

    /** AI 整理已判定「无人脸」或属于无人类别的照片，人脸识别跳过（省流量）。规则在 [DiscoverCalc.skipFaceByAi]。 */
    fun skipByAi(lib: DavLib, p: Photo): Boolean = DiscoverCalc.skipFaceByAi(lib.meta[p.cloudPath])
    /** 还需要做人脸识别的图片数（不含视频和被 AI 跳过的）。 */
    fun pending(lib: DavLib, fl: FaceLib): Int = DiscoverCalc.facePending(lib.photos, { lib.meta[it] }) { it in fl.scanned }

    /** start() 常在主线程（页面 LaunchedEffect）被调用：聚类挪到后台，不卡界面。用户 2026-10-24 要求排查。 */
    private fun clusterInBackground(fl: FaceLib, lib: DavLib) {
        scope.launch { runCatching { fl.cluster(lib) }.onFailure { Diag.e("FACE", it) } }
    }

    /** Scans every photo not scanned yet, then clusters. [manual] ignores the Wi-Fi + charging rule. */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (jobs[lib.accountId]?.isActive == true) return
        val fl = lib(lib.accountId)
        val left = pending(lib, fl)
        if (!manual && !Analyzer.canScan(c)) {
            fl.status = if (left > 0) ScanPolicy.waitingText() else ""
            logOnce("skip start: wifi=${Net.isWifi(c)} charging=${Net.isCharging(c)} cell=${ScanPolicy.allowCellular} noCharge=${ScanPolicy.allowNoCharge}, $left photos left")
            clusterInBackground(fl, lib); return
        }
        if (left == 0) { fl.status = ""; logOnce("nothing new (${fl.scanned.size} scanned)"); clusterInBackground(fl, lib); return }
        if (manual) fl.error = ""
        ScanService.ensure(c)
        jobs[lib.accountId] = scope.launch {
            fl.running = true; fl.status = ""
            try { scanAll(c, lib, fl, manual) }
            catch (e: CancellationException) { Diag.log("FACE", "stopped at ${fl.done}/${fl.total}"); throw e }
            catch (e: Throwable) {
                // 以前异常会让 running 一直为 true、界面静默；现在记下原因给界面显示
                Diag.e("FACE", e); fl.error = "人脸识别出错：${e.message ?: e.javaClass.simpleName}"; fl.status = fl.error
            } finally {
                runCatching { fl.save() }
                fl.running = false
                jobs.remove(lib.accountId)
            }
        }
    }

    private suspend fun CoroutineScope.scanAll(c: Context, lib: DavLib, fl: FaceLib, manual: Boolean) {
        val s = lib.src ?: run { Diag.log("FACE", "no WebDAV source → stop"); return }
        var skippedByAi = 0
        val todo = lib.photos.filter { p ->
            if (p.isVideo || p.cloudPath in fl.scanned) return@filter false
            if (skipByAi(lib, p)) { skippedByAi++; return@filter false }
            true
        }
        fl.total = todo.size; fl.done = 0
        Diag.log("FACE", "start manual=$manual: ${lib.photos.size} items, ${fl.scanned.size} scanned, ${todo.size} to do, skipped $skippedByAi (AI: no face / scenery category)")
        runCatching { model(c) }.onFailure {
            fl.error = if (!modelPresent(c)) "人脸模型文件缺失（安装包不完整），请更新到最新版" else "人脸模型加载失败：${it.message ?: it.javaClass.simpleName}"
            fl.status = fl.error
            Diag.log("FACE", "model load failed: ${it.javaClass.simpleName}: ${it.message} (present=${modelPresent(c)})")
            return
        }
        fl.error = ""
        Diag.log("FACE", "model ok: in=${inShape.joinToString("x")} out=$outDim")
        var facesTotal = 0; var failedTotal = 0; var noFaceTotal = 0
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        val chunks = todo.chunked(12)
        var ci = 0
        while (ci < chunks.size) {
            if (!isActive) break
            // B29: Wi-Fi / charging lost mid-run → wait here (like AiRunner.loop) instead of running
            // dry through the queue; the chunk is retried after the wait, nothing is skipped.
            if (!manual && !Analyzer.canScan(c)) {
                if (fl.status.isEmpty()) Diag.log("FACE", "waiting at ${fl.done}/${fl.total}: wifi=${Net.isWifi(c)} charging=${Net.isCharging(c)}")
                fl.status = ScanPolicy.waitingText(); delay(20_000); continue
            }
            fl.status = ""
            val chunk = chunks[ci]
            val got = chunk.map { p -> async {
                gate.acquire()
                // B9: only a completed detection attempt marks the photo scanned. Download / decode
                // failures go to failed (+failedAt) and are retried on the next start.
                val n = try { runCatching { scanOne(c, s, p, fl) }.onFailure { e -> if (e is CancellationException) throw e; Diag.log("FACE", "photo failed: ${e.javaClass.simpleName}: ${e.message}") }.getOrDefault(-1) } finally { gate.release() }
                if (n >= 0) { fl.scanned.add(p.cloudPath); fl.failed.remove(p.cloudPath) }
                else fl.failed[p.cloudPath] = System.currentTimeMillis()
                n
            } }.awaitAll()
            facesTotal += got.filter { it > 0 }.sum(); failedTotal += got.count { it < 0 }; noFaceTotal += got.count { it == 0 }
            fl.done += chunk.size
            if (fl.done % 48 < 12) {
                Diag.log("FACE", "progress ${fl.done}/${fl.total}: $facesTotal faces, $noFaceTotal photos without face, $failedTotal failed (download/decode)")
                // 用户 2026-10-24 要求排查：聚类是 O(簇数²) 的合并循环，人脸多时要几百毫秒到几秒，
                // 以前切到主线程跑，整理期间每 48 张卡一次界面（照片网格滚动卡顿、严重时 ANR）。改在后台算。
                fl.save(); withContext(Dispatchers.Default) { fl.cluster(lib) }
            }
            ci++
        }
        Diag.log("FACE", "done ${fl.done}/${fl.total}: $facesTotal faces, $noFaceTotal photos without face, $failedTotal failed")
        fl.save()
        withContext(Dispatchers.Default) { fl.cluster(lib) }
    }

    private fun decode(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        // honour EXIF orientation
        runCatching {
            val ori = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            val deg = when (ori) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            if (deg != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        }
        return b
    }

    /** Number of faces kept (0 when none), or -1 on download / decode failure (retried next start). */
    private fun scanOne(c: Context, s: com.hark.shiguang.cloud.WebDavSource, p: Photo, fl: FaceLib): Int {
        if (p.size > 40_000_000) return -1
        val bytes = s.allBytes(p.cloudPath, 40_000_000L) ?: return -1
        val bmp = decode(bytes, 1280) ?: return -1
        val found = detect(bmp)
        if (found.isEmpty()) return 0
        val list = mutableListOf<FaceRec>()
        found.forEachIndexed { i, f ->
            // S7: the landmarks travel with the detection result — no shared RectF-keyed map.
            val crop = (f.marks?.let { alignByMarks(bmp, it) } ?: align(bmp, f.box, f.roll)) ?: return@forEachIndexed
            // test-time flip: averaging the mirrored face makes the vector steadier across poses
            val e1 = embed(c, crop)
            val flipped = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postScale(-1f, 1f, crop.width / 2f, crop.height / 2f) }, true)
            val e2 = embed(c, flipped)
            val emb = FloatArray(e1.size) { e1[it] + e2[it] }.also { FaceLib.normalize(it) }
            val id = (p.cloudPath.hashCode().toLong() and 0xffffffffL).toString(36) + "_" + i
            runCatching { FileOutputStream(fl.cropFile(id)).use { Bitmap.createScaledBitmap(crop, 160, 160, true).compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            list.add(FaceRec(id, p.cloudPath, f.conf, emb))
        }
        if (list.isNotEmpty()) fl.faces[p.cloudPath] = list
        return list.size
    }

    /** Eye / mouth landmarks in bitmap pixels (null when the detector gave none). */
    private class Marks(val le: android.graphics.PointF, val re: android.graphics.PointF, val mouth: android.graphics.PointF?)
    /** S7: one detection result — box, roll, confidence + its own landmarks (no shared map keyed by RectF). */
    private data class DetectedFace(val box: RectF, val roll: Float, val conf: Float, val marks: Marks?)

    /** Returns detected faces. Confidence = how usable the face is: frontal, sharp-sized, landmarks found. */
    private fun detect(b: Bitmap): List<DetectedFace> {
        val r = runCatching {
            // S8: ML Kit never hangs the queue — 30 s timeout, then the fallback detector below takes over.
            val fs = Tasks.await(detector.process(InputImage.fromBitmap(b, 0)), 30, java.util.concurrent.TimeUnit.SECONDS)
            fs.map { f ->
                val bb = f.boundingBox
                val w = bb.width().toFloat()
                val frontal = (1f - (abs(f.headEulerAngleY) / 50f + abs(f.headEulerAngleX) / 50f) / 2f).coerceIn(0f, 1f)
                val size = ((w - 32f) / 96f).coerceIn(0f, 1f)
                val landmarkScore = listOf(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE), f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE),
                    f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)).count { it != null } / 3f
                val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).let { if (it.isEmpty()) 0.7f else it.average().toFloat() }
                val conf = (0.45f * frontal + 0.3f * size + 0.15f * landmarkScore + 0.1f * eyes).coerceIn(0f, 1f)
                val box = RectF(bb)
                val l = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                val r = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                val ml = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
                val mr = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position
                val mb = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
                val marks = if (l != null && r != null) {
                    // ML Kit's LEFT_EYE is the subject's left = image right
                    val (imgL, imgR) = if (l.x > r.x) r to l else l to r
                    val mouth = if (ml != null && mr != null) android.graphics.PointF((ml.x + mr.x) / 2, (ml.y + mr.y) / 2) else mb
                    Marks(android.graphics.PointF(imgL.x, imgL.y), android.graphics.PointF(imgR.x, imgR.y), mouth)
                } else null
                // profile faces embed badly: count them less
                DetectedFace(box, f.headEulerAngleZ, if (abs(f.headEulerAngleY) > 35f) conf * 0.7f else conf, marks)
            }
        }
        r.getOrNull()?.let { return it }
        if (!mlkitWarned) { mlkitWarned = true; Diag.log("FACE", "ML Kit detector failed (${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}) → Android FaceDetector fallback") }
        // fallback without Play services: Android's built-in detector (eyes only)
        val rgb = b.copy(Bitmap.Config.RGB_565, false).let { if (it.width % 2 == 1) Bitmap.createBitmap(it, 0, 0, it.width - 1, it.height) else it }
        val arr = arrayOfNulls<android.media.FaceDetector.Face>(10)
        val n = android.media.FaceDetector(rgb.width, rgb.height, 10).findFaces(rgb, arr)
        return (0 until n).mapNotNull { i ->
            val f = arr[i] ?: return@mapNotNull null
            val pt = android.graphics.PointF(); f.getMidPoint(pt); val e = f.eyesDistance()
            val box = RectF(pt.x - e * 1.1f, pt.y - e * 0.9f, pt.x + e * 1.1f, pt.y + e * 1.6f)
            val conf = (f.confidence() * 1.3f).coerceIn(0f, 1f) * ((e * 2.2f - 32f) / 96f).coerceIn(0.3f, 1f)
            DetectedFace(box, 0f, conf, null)
        }
    }

    /**
     * ArcFace-style alignment: maps both eyes (and the mouth centre when known) onto the standard
     * 112×112 template MobileFaceNet was trained on. This is what keeps one person in one cluster.
     */
    private fun alignByMarks(b: Bitmap, m: Marks): Bitmap? = runCatching {
        val src = if (m.mouth != null) floatArrayOf(m.le.x, m.le.y, m.re.x, m.re.y, m.mouth.x, m.mouth.y) else floatArrayOf(m.le.x, m.le.y, m.re.x, m.re.y)
        val dst = if (m.mouth != null) floatArrayOf(38.29f, 51.70f, 73.53f, 51.50f, 56.14f, 92.28f) else floatArrayOf(38.29f, 51.70f, 73.53f, 51.50f)
        val eyeDist = kotlin.math.hypot((m.re.x - m.le.x).toDouble(), (m.re.y - m.le.y).toDouble())
        if (eyeDist < 12) return null
        val mat = Matrix()
        if (!mat.setPolyToPoly(src, 0, dst, 0, src.size / 2)) return null
        val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(b, mat, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        out
    }.getOrNull()

    private fun align(b: Bitmap, box: RectF, roll: Float): Bitmap? = runCatching {
        val cx = box.centerX(); val cy = box.centerY()
        val side = maxOf(box.width(), box.height()) * 1.1f
        val m = Matrix().apply { postRotate(roll, cx, cy) }   // ML Kit Z angle is counter-clockwise
        val rot = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        val pts = floatArrayOf(cx, cy); m.mapPoints(pts)
        val ncx = pts[0]; val ncy = pts[1]
        // createBitmap with a matrix shifts the result so its bounds start at 0,0
        val bounds = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(bounds)
        val x = (ncx - bounds.left - side / 2).toInt().coerceIn(0, rot.width - 1)
        val y = (ncy - bounds.top - side / 2).toInt().coerceIn(0, rot.height - 1)
        val w = side.toInt().coerceAtMost(rot.width - x); val h = side.toInt().coerceAtMost(rot.height - y)
        if (w < 24 || h < 24) return null
        Bitmap.createScaledBitmap(Bitmap.createBitmap(rot, x, y, w, h), 112, 112, true)
    }.getOrNull()

    @Synchronized private fun embed(c: Context, face: Bitmap): FloatArray {
        val i = model(c)
        val batch = inShape[0].coerceAtLeast(1); val hh = inShape[1]; val ww = inShape[2]
        val img = if (face.width != ww || face.height != hh) Bitmap.createScaledBitmap(face, ww, hh, true) else face
        val buf = ByteBuffer.allocateDirect(batch * hh * ww * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(ww * hh); img.getPixels(px, 0, ww, 0, 0, ww, hh)
        repeat(batch) { px.forEach { v -> buf.putFloat(((v shr 16 and 0xff) - 127.5f) / 128f); buf.putFloat(((v shr 8 and 0xff) - 127.5f) / 128f); buf.putFloat(((v and 0xff) - 127.5f) / 128f) } }
        buf.rewind()
        val out = Array(batch) { FloatArray(outDim) }
        i.run(buf, out)
        return out[0].also { FaceLib.normalize(it) }
    }
}
