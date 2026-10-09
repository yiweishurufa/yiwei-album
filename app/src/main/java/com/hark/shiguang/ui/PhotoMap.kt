package com.hark.shiguang.ui

import android.graphics.*
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hark.shiguang.Dav
import com.hark.shiguang.Hidden
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.data.MapBounds
import com.hark.shiguang.data.NasX
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.*

/** WGS-84 (GPS / EXIF) -> GCJ-02 (what 高德 tiles are drawn in). Outside mainland China nothing changes. */
object Gcj {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    fun outOfChina(lat: Double, lng: Double) = lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    private fun tLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }
    private fun tLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }
    fun fromWgs(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = tLat(lng - 105.0, lat - 35.0); var dLng = tLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat); magic = 1 - EE * magic * magic
        val sq = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sq) * PI)
        dLng = (dLng * 180.0) / (A / sq * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }
}

/** 高德 road tiles (Chinese labels, no API key). */
private class AmapTiles : OnlineTileSourceBase("AmapRoad", 3, 18, 256, ".png",
    arrayOf("https://webrd01.is.autonavi.com/appmaptile?", "https://webrd02.is.autonavi.com/appmaptile?", "https://webrd03.is.autonavi.com/appmaptile?", "https://webrd04.is.autonavi.com/appmaptile?"), "© 高德地图") {
    override fun getTileURLString(idx: Long): String =
        baseUrl + "lang=zh_cn&size=1&scale=1&style=8&x=${MapTileIndex.getX(idx)}&y=${MapTileIndex.getY(idx)}&z=${MapTileIndex.getZoom(idx)}"
}

/** A geotagged item: one photo (WebDAV) or one server-side cluster (飞牛). lat/lng in GCJ-02, wLat/wLng raw WGS-84. */
class MapPt(val lat: Double, val lng: Double, val wLat: Double, val wLng: Double, val count: Int, val cover: String?, val photo: Photo?)

class MapCluster(val lat: Double, val lng: Double, val count: Int, val cover: String?, val members: List<MapPt>)

private class ClusterOverlay(private val pts: List<MapPt>, private val density: Float, private val accent: Int, private val onTap: (MapCluster) -> Unit,
                             private val loadThumb: (String) -> Unit, private val thumbs: Map<String, Bitmap>) : Overlay() {
    private var zoom = -1
    private var clusters: List<MapCluster> = emptyList()
    private val drawn = ArrayList<Pair<RectF, MapCluster>>()
    private val pt = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun recluster(z: Int) {
        zoom = z
        val world = 256.0 * 2.0.pow(z) * density.coerceAtLeast(1f)
        val cell = 64.0 * density
        val grid = HashMap<Long, MutableList<MapPt>>()
        for (p in pts) {
            val x = (p.lng + 180.0) / 360.0 * world
            val s = sin(p.lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
            val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
            val k = (floor(x / cell).toLong() shl 32) or (floor(y / cell).toLong() and 0xffffffffL)
            grid.getOrPut(k) { mutableListOf() }.add(p)
        }
        clusters = grid.values.map { m ->
            val n = m.sumOf { it.count }
            MapCluster(m.sumOf { it.lat * it.count } / n, m.sumOf { it.lng * it.count } / n, n, m.maxByOrNull { it.count }?.cover ?: m.first().cover, m)
        }.sortedBy { it.count }
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val z = mv.zoomLevelDouble.roundToInt()
        if (z != zoom) recluster(z)
        drawn.clear()
        val proj = mv.projection
        val w = c.width; val h = c.height
        val side = 46f * density
        for (cl in clusters) {
            proj.toPixels(GeoPoint(cl.lat, cl.lng), pt)
            if (pt.x < -side || pt.y < -side || pt.x > w + side || pt.y > h + side) continue
            val r = RectF(pt.x - side / 2, pt.y - side / 2, pt.x + side / 2, pt.y + side / 2)
            c.drawRoundRect(RectF(r.left + 1.5f * density, r.top + 2.5f * density, r.right + 1.5f * density, r.bottom + 2.5f * density), 12f * density, 12f * density, shadowP)
            val bmp = cl.cover?.let { thumbs[it] }
            if (bmp != null) {
                c.save(); val path = Path().apply { addRoundRect(r, 12f * density, 12f * density, Path.Direction.CW) }; c.clipPath(path)
                c.drawBitmap(bmp, null, r, bmpP); c.restore()
            } else {
                c.drawRoundRect(r, 12f * density, 12f * density, fill)
                cl.cover?.let(loadThumb)
            }
            c.drawRoundRect(r, 12f * density, 12f * density, stroke)
            if (cl.count > 1) {
                val label = if (cl.count > 999) "999+" else "${cl.count}"
                val bw = max(20f * density, text.measureText(label) + 10f * density)
                val b = RectF(r.right - bw + 6f * density, r.top - 8f * density, r.right + 6f * density, r.top + 12f * density)
                c.drawRoundRect(b, 10f * density, 10f * density, fill)
                c.drawRoundRect(b, 10f * density, 10f * density, stroke.apply { strokeWidth = 1.5f * density })
                stroke.strokeWidth = 2.5f * density
                c.drawText(label, b.centerX(), b.centerY() + text.textSize * 0.36f, text)
            }
            drawn.add(r to cl)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mv: MapView): Boolean {
        val hit = drawn.lastOrNull { (r, _) -> r.contains(e.x, e.y) } ?: return false
        onTap(hit.second); return true
    }
}

/**
 * 照片地图 (1.0.9): osmdroid + 高德 tiles, photos clustered on a screen grid that is rebuilt per zoom level.
 * WebDAV: every analysed photo with EXIF GPS. 飞牛: the server's point list (previewAllPoint); a tap opens
 * the photos inside that cluster's bounds (map/photoList).
 */
@Composable
fun PhotoMapScreen(dav: Boolean) {
    val ctx = LocalContext.current
    var pts by remember { mutableStateOf<List<MapPt>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dav, Hidden.version) {
        pts = withContext(Dispatchers.Default) {
            if (dav) {
                val lib = Dav.lib() ?: return@withContext emptyList()
                Hidden.visible(lib.photos.toList()).mapNotNull { p ->
                    val m = lib.meta[p.cloudPath] ?: return@mapNotNull null
                    if (m.lat.isNaN() || (m.lat == 0.0 && m.lng == 0.0)) return@mapNotNull null
                    val (la, lo) = Gcj.fromWgs(m.lat, m.lng)
                    MapPt(la, lo, m.lat, m.lng, 1, p.thumbS, p)
                }
            } else runCatching { NasX.mapPoints(MapBounds(90.0, 180.0, -90.0, -180.0)) }.onFailure { err = it.message }.getOrDefault(emptyList()).map {
                val (la, lo) = Gcj.fromWgs(it.lat, it.lng); MapPt(la, lo, it.lat, it.lng, it.count.coerceAtLeast(1), it.cover, null)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        val list = pts
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(28.dp)) }
            list.isEmpty() -> StateMessage(LI.map(), if (err != null) "地图加载失败" else "还没有带位置的照片",
                err ?: if (dav) "本地分析读到 GPS 后，照片会出现在地图上。" else "拍照时开启定位，飞牛识别后会出现在这里。")
            else -> OsmMap(list) { cl -> openCluster(dav, cl) }
        }
        ExtTop("照片地图", list?.let { "${it.sumOf { p -> p.count }} 张带位置的照片" } ?: "")
        if (!list.isNullOrEmpty()) Text("点照片堆查看这一处的照片 · 双指缩放展开", color = C.Sub, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

private fun openCluster(dav: Boolean, cl: MapCluster) {
    if (dav) {
        val ps = cl.members.mapNotNull { it.photo }.sortedByDescending { it.time }
        Nav.push(Route.Collection("这里的照片", "${ps.size} 张", PhotoSource(pageSize = 100000) { o, _ -> if (o > 0) emptyList<Photo>() to false else ps to false }))
    } else {
        val d = 0.01
        val b = MapBounds(cl.members.maxOf { it.wLat } + d, cl.members.maxOf { it.wLng } + d, cl.members.minOf { it.wLat } - d, cl.members.minOf { it.wLng } - d)
        Nav.push(Route.Collection("这里的照片", "约 ${cl.count} 张", PhotoSource(pageSize = 100) { o, l -> NasX.mapPhotos(b, o, l) }))
    }
}

@Composable
private fun OsmMap(pts: List<MapPt>, onTap: (MapCluster) -> Unit) {
    val ctx = LocalContext.current
    val accent = C.Accent.toArgb()
    val scope = rememberCoroutineScope()
    val thumbs = remember { java.util.concurrent.ConcurrentHashMap<String, Bitmap>() }
    val pending = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    val tapCb by rememberUpdatedState(onTap)
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        Configuration.getInstance().apply {
            userAgentValue = c.packageName
            osmdroidBasePath = File(c.cacheDir, "osmdroid")
            osmdroidTileCache = File(c.cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        }
        MapView(c).apply {
            setTileSource(AmapTiles())
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0; maxZoomLevel = 18.0
            isVerticalMapRepetitionEnabled = false
            val dens = c.resources.displayMetrics.density
            overlays.add(ClusterOverlay(pts, dens, accent, { tapCb(it) }, { url ->
                if (pending.add(url) && pending.size < 400) scope.launch {
                    val r = runCatching { ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(160).allowHardware(false).build()) }.getOrNull()
                    val b = ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (b != null) { thumbs[url] = b; mapRef?.invalidate() }
                }
            }, thumbs))
            addOnFirstLayoutListener { _, _, _, _, _ ->
                val n = pts.maxOf { it.lat }; val s = pts.minOf { it.lat }; val e = pts.maxOf { it.lng }; val w = pts.minOf { it.lng }
                if (n - s < 0.01 && e - w < 0.01) { controller.setZoom(14.0); controller.setCenter(GeoPoint((n + s) / 2, (e + w) / 2)) }
                else zoomToBoundingBox(BoundingBox(n, e, s, w), false, (48 * dens).toInt())
            }
            onResume()
            mapRef = this
        }
    })
    DisposableEffect(Unit) { onDispose { mapRef?.onPause(); mapRef?.onDetach() } }
}
