package com.hark.shiguang.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.DavLib
import com.hark.shiguang.Diag
import com.hark.shiguang.Hidden
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.Store
import com.hark.shiguang.TripCluster
import com.hark.shiguang.data.Photo
import com.hark.shiguang.tripRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One trip with its photos resolved (oldest first). */
class TripAlbum(val trip: TripCluster.Trip, val photos: List<Photo>, val cover: Photo)

/**
 * 1.1.0 自动旅行相册（WebDAV：照片的 EXIF 拍摄时间 + GPS + 本地分析时存下的城市名，不新增联网）。
 * 聚类在 Dispatchers.Default 上算；结果按账号缓存，键是输入指纹（每张照片的路径、时间、坐标、地名 + 隐藏状态），
 * 所以 AI 标签、人脸、哈希这类不影响聚类的分析进度不会触发重算，只有照片集或位置真的变了才失效。
 */
object TravelAlbums {
    private const val KEY = "discover.trips"
    private var on by mutableStateOf(Store.getStr(KEY, "1") == "1")
    /** 设置 → 照片 →「自动旅行相册」，默认开。 */
    var enabled: Boolean
        get() = on
        set(v) { on = v; Store.putStr(KEY, if (v) "1" else "0") }

    private class Entry(val fingerprint: Long, val albums: List<TripAlbum>)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun cached(accountId: String): List<TripAlbum>? = cache[accountId]?.albums

    suspend fun compute(lib: DavLib): List<TripAlbum> = withContext(Dispatchers.Default) {
        val stills = Hidden.visible(lib.photos.toList()).filter { !it.isVideo }
        val byKey = HashMap<String, Photo>(stills.size * 2)
        val pts = ArrayList<TripCluster.Point>()
        for (p in stills) {
            val m = lib.meta[p.cloudPath] ?: continue
            if (!TripCluster.hasGps(m.lat, m.lng)) continue
            byKey[p.cloudPath] = p
            pts += TripCluster.Point(p.cloudPath, p.time, m.lat, m.lng, m.city)
        }
        val fp = TripCluster.fingerprint(pts)
        cache[lib.accountId]?.takeIf { it.fingerprint == fp }?.let { return@withContext it.albums }
        val t0 = System.currentTimeMillis()
        val res = TripCluster.cluster(pts)
        val albums = res.trips.mapNotNull { t ->
            val ps = t.keys.mapNotNull { byKey[it] }
            if (ps.isEmpty()) null else TripAlbum(t, ps, byKey[t.coverKey] ?: ps[ps.size / 2])
        }
        Diag.log("TRIPS", "${pts.size} gps photos -> ${albums.size} trips in ${System.currentTimeMillis() - t0} ms")
        cache[lib.accountId] = Entry(fp, albums)
        albums
    }

    fun open(a: TripAlbum) {
        val ps = a.photos
        Nav.push(Route.Collection(a.trip.place, "${tripRange(a.trip.start, a.trip.end)} · ${a.trip.days} 天 · ${ps.size} 张",
            PhotoSource(pageSize = 100000, grouped = true) { o, _ -> if (o > 0) emptyList<Photo>() to false else ps to false }))
    }
}

/** 发现 → 回忆 → 旅行：横向卡片（封面、地点、日期范围、天数、张数），点开是按天分组的网格。 */
@Composable
fun TravelAlbumsRow(lib: DavLib, metaVersion: Int) {
    if (!TravelAlbums.enabled) return
    var albums by remember(lib) { mutableStateOf(TravelAlbums.cached(lib.accountId)) }
    LaunchedEffect(lib, metaVersion, lib.photos.size, Hidden.version) {
        // metaVersion ticks every few photos while analysing: wait for a quiet moment before re-checking
        if (albums != null) delay(1500)
        try { albums = TravelAlbums.compute(lib) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Diag.e("TRIPS", e) }   // keep showing the last result
    }
    val list = albums ?: return
    if (list.isEmpty()) return   // 用户 2026-10-23：没有旅行就不显示这一块，不再放说明段落
    Column(Modifier.padding(bottom = Space.s)) {
        // 1.6.2：与人物 / 地点同一款分区标题与封面卡规格（C3）
        ContentHeader("旅行", "${list.size} 次")
        LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(CoverSize.gap)) {
            itemsIndexed(list, key = { _, a -> a.trip.id }) { i, a -> TripCard(a, i) }
        }
    }
}

@Composable
private fun TripCard(a: TripAlbum, index: Int) {
    val t = a.trip
    val range = tripRange(t.start, t.end)
    Box(Modifier.enterRise(index).size(CoverSize.cardW, CoverSize.cardH).pressScale(Motion.PRESS_SCALE_LARGE).skeuoPhoto(RoundedCornerShape(Radius.l))
        .clickable(role = androidx.compose.ui.semantics.Role.Button) { TravelAlbums.open(a) }
        .semantics(mergeDescendants = true) { contentDescription = "旅行相册：${t.place}，$range，${a.photos.size} 张" }) {
        NetImage(a.cover.thumbM, Modifier.fillMaxSize())
        // 1.7.10 v2：和「那年今天」同一款——地点压在底部的一枚玻璃标签里（照片上的深色玻璃，白字 ≥ 7:1），天数 / 张数是标签右上的小胶囊
        Text("${t.days} 天", color = Color.White, style = Type.badge.copy(fontSize = 12.sp, lineHeight = 16.sp), modifier = Modifier.align(Alignment.TopEnd).padding(Space.s)
            .glass(CircleShape, null, tint = Color.Black.copy(alpha = 0.55f), shadow = 0.dp, highlight = 0.6f, onDark = true).padding(horizontal = 10.dp, vertical = 4.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Space.s).glass(RoundedCornerShape(Radius.l - Space.s), null, tint = Color.Black.copy(alpha = 0.55f), shadow = 0.dp, highlight = 0.6f, onDark = true)
            .padding(horizontal = Space.m, vertical = Space.s)) {
            Text(t.place, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$range · ${a.photos.size} 张", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
