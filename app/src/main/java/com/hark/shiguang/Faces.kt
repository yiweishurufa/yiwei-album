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
    fun reset() { conf = DEF_CONF; minPhotos = DEF_MIN; diff = DEF_DIFF; save() }
}

class FaceRec(val id: String, val path: String, val conf: Float, val emb: FloatArray)

/** [group]: id of a manual group (merge / hide / remove edits), empty for a plain cluster. */
data class FacePerson(val key: String, val name: String, val cover: String, val faces: List<FaceRec>, val photos: List<Photo>, val group: String = "")

/** One WebDAV account's faces: detected faces (saved), persons (re-clustered from the saved faces whenever a setting changes). */
class FaceLib(val accountId: String) {
    private val dir: File get() = File(App.ctx.filesDir, "dav/$accountId/faces").apply { mkdirs() }
    val faces = java.util.concurrent.ConcurrentHashMap<String, MutableList<FaceRec>>()   // photo path -> faces
    val scanned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
    private var loaded = false

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
        File(dir, "faces.json").writeText(JSONObject().put("scanned", JSONArray(scanned.toList())).put("names", nm).put("faces", fa).put("v", ALG).toString())
    }

    fun rename(p: FacePerson, name: String) {
        p.faces.forEach { names.remove(it.id) }
        if (name.isNotBlank()) names[p.key] = name.trim()
        save(); persons = persons.map { if (it.key == p.key) it.copy(name = name.trim()) else it }
    }

    fun clear() { faces.clear(); scanned.clear(); names.clear(); persons = emptyList(); hiddenPersons = emptyList(); edits.clear(); dir.deleteRecursively() }

    /**
     * Clustering on the saved faces with the current three settings:
     * 1) greedy pass in confidence order, 2) merge clusters whose centres are close (one person seen in
     * different light / ages was split before), 3) one refinement pass reassigning every face to its nearest centre.
     */
    fun cluster(lib: DavLib) {
        val byPath = lib.photos.associateBy { it.cloudPath }
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
    private var job: Job? = null
    private var interp: Interpreter? = null
    private var inShape = intArrayOf(1, 112, 112, 3)
    private var outDim = 192

    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.06f).build())
    }

    private fun model(c: Context): Interpreter {
        interp?.let { return it }
        val fd = c.assets.openFd("mobilefacenet.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val i = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        inShape = i.getInputTensor(0).shape()
        outDim = i.getOutputTensor(0).shape().last()
        interp = i
        return i
    }

    fun stop() { job?.cancel() }
    val running: Boolean get() = job?.isActive == true

    /** Scans every photo not scanned yet, then clusters. [manual] ignores the Wi-Fi + charging rule. */
    fun start(c: Context, lib: DavLib, manual: Boolean) {
        if (job?.isActive == true) return
        val fl = lib(lib.accountId)
        if (!manual && !Analyzer.canScan(c)) { fl.cluster(lib); return }
        if (lib.photos.none { !it.isVideo && it.cloudPath !in fl.scanned }) { fl.cluster(lib); return }
        ScanService.ensure(c)
        job = scope.launch {
            fl.running = true; fl.status = ""
            val s = lib.src ?: run { fl.running = false; return@launch }
            val skipCats = setOf("文档", "截图", "美食", "风景", "建筑", "植物", "交通")
            val todo = lib.photos.filter { p ->
                if (p.isVideo || p.cloudPath in fl.scanned) return@filter false
                val m = lib.meta[p.cloudPath]
                !(m != null && (m.aiFaces == 0 || m.aiCat in skipCats))
            }
            fl.total = todo.size; fl.done = 0
            runCatching { model(c) }.onFailure { fl.status = "人脸模型加载失败：${it.message}"; fl.running = false; return@launch }
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            todo.chunked(12).forEach { chunk ->
                if (!isActive) return@forEach
                if (!manual && !Analyzer.canScan(c)) { fl.status = ScanPolicy.waitingText(); return@forEach }
                chunk.map { p -> async {
                    gate.acquire()
                    try { runCatching { scanOne(c, s, p, fl) } } finally { gate.release() }
                    fl.scanned.add(p.cloudPath)
                } }.awaitAll()
                fl.done += chunk.size
                if (fl.done % 48 < 12) { fl.save(); withContext(Dispatchers.Main) { fl.cluster(lib) } }
            }
            fl.save()
            withContext(Dispatchers.Main) { fl.cluster(lib) }
            fl.running = false
        }
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

    private fun scanOne(c: Context, s: com.hark.shiguang.cloud.WebDavSource, p: Photo, fl: FaceLib) {
        if (p.size > 40_000_000) return
        val bytes = s.allBytes(p.cloudPath) ?: return
        val bmp = decode(bytes, 1280) ?: return
        val found = detect(bmp)
        if (found.isEmpty()) return
        val list = mutableListOf<FaceRec>()
        found.forEachIndexed { i, (box, roll, conf) ->
            val crop = (markMap.remove(box)?.let { alignByMarks(bmp, it) } ?: align(bmp, box, roll)) ?: return@forEachIndexed
            // test-time flip: averaging the mirrored face makes the vector steadier across poses
            val e1 = embed(c, crop)
            val flipped = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postScale(-1f, 1f, crop.width / 2f, crop.height / 2f) }, true)
            val e2 = embed(c, flipped)
            val emb = FloatArray(e1.size) { e1[it] + e2[it] }.also { FaceLib.normalize(it) }
            val id = (p.cloudPath.hashCode().toLong() and 0xffffffffL).toString(36) + "_" + i
            runCatching { FileOutputStream(fl.cropFile(id)).use { Bitmap.createScaledBitmap(crop, 160, 160, true).compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            list.add(FaceRec(id, p.cloudPath, conf, emb))
        }
        if (list.isNotEmpty()) fl.faces[p.cloudPath] = list
    }

    /** Eye / mouth landmarks in bitmap pixels (null when the detector gave none). */
    private class Marks(val le: android.graphics.PointF, val re: android.graphics.PointF, val mouth: android.graphics.PointF?)
    private val markMap = java.util.concurrent.ConcurrentHashMap<RectF, Marks>()

    /** Returns (box, roll degrees, confidence 0..1). Confidence = how usable the face is: frontal, sharp-sized, landmarks found. */
    private fun detect(b: Bitmap): List<Triple<RectF, Float, Float>> {
        val r = runCatching {
            val fs = Tasks.await(detector.process(InputImage.fromBitmap(b, 0)))
            fs.map { f ->
                val bb = f.boundingBox
                val w = bb.width().toFloat()
                val frontal = (1f - (abs(f.headEulerAngleY) / 50f + abs(f.headEulerAngleX) / 50f) / 2f).coerceIn(0f, 1f)
                val size = ((w - 32f) / 96f).coerceIn(0f, 1f)
                val marks = listOf(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE), f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE),
                    f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)).count { it != null } / 3f
                val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).let { if (it.isEmpty()) 0.7f else it.average().toFloat() }
                val conf = (0.45f * frontal + 0.3f * size + 0.15f * marks + 0.1f * eyes).coerceIn(0f, 1f)
                val box = RectF(bb)
                val l = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                val r = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                val ml = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
                val mr = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position
                val mb = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
                if (l != null && r != null) {
                    // ML Kit's LEFT_EYE is the subject's left = image right
                    val (imgL, imgR) = if (l.x > r.x) r to l else l to r
                    val mouth = if (ml != null && mr != null) android.graphics.PointF((ml.x + mr.x) / 2, (ml.y + mr.y) / 2) else mb
                    markMap[box] = Marks(android.graphics.PointF(imgL.x, imgL.y), android.graphics.PointF(imgR.x, imgR.y), mouth)
                }
                // profile faces embed badly: count them less
                Triple(box, f.headEulerAngleZ, if (abs(f.headEulerAngleY) > 35f) conf * 0.7f else conf)
            }
        }
        r.getOrNull()?.let { return it }
        // fallback without Play services: Android's built-in detector (eyes only)
        val rgb = b.copy(Bitmap.Config.RGB_565, false).let { if (it.width % 2 == 1) Bitmap.createBitmap(it, 0, 0, it.width - 1, it.height) else it }
        val arr = arrayOfNulls<android.media.FaceDetector.Face>(10)
        val n = android.media.FaceDetector(rgb.width, rgb.height, 10).findFaces(rgb, arr)
        return (0 until n).mapNotNull { i ->
            val f = arr[i] ?: return@mapNotNull null
            val pt = android.graphics.PointF(); f.getMidPoint(pt); val e = f.eyesDistance()
            val box = RectF(pt.x - e * 1.1f, pt.y - e * 0.9f, pt.x + e * 1.1f, pt.y + e * 1.6f)
            val conf = (f.confidence() * 1.3f).coerceIn(0f, 1f) * ((e * 2.2f - 32f) / 96f).coerceIn(0.3f, 1f)
            Triple(box, 0f, conf)
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
