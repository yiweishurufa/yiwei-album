package com.hark.shiguang.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import com.hark.shiguang.data.Photo
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "s")
    val x by t.animateFloat(0f, 1400f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(
        listOf(C.Surface, C.Surface2, C.Surface), start = Offset(x - 400f, 0f), end = Offset(x, 400f)
    )
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, crossfade: Boolean = true) {
    // H11: plain AsyncImage over a static placeholder color — no subcomposition and no endless shimmer per cell,
    // which made poster grids and song rows drop frames. shimmerBrush() stays for first-screen skeletons only.
    val ctx = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(if (failed) C.Surface2 else C.Surface), contentAlignment = Alignment.Center) {
        coil.compose.AsyncImage(
            model = ImageRequest.Builder(ctx).data(url).crossfade(crossfade).build(),
            contentDescription = null, contentScale = scale, modifier = Modifier.matchParentSize(),
            onState = { failed = it is coil.compose.AsyncImagePainter.State.Error },
        )
        if (failed) Icon(Icons.Rounded.BrokenImage, null, tint = C.Faint, modifier = Modifier.size(22.dp))
    }
}

fun fmtDuration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun Thumb(p: Photo, corner: Dp, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(C.Surface).clickable(onClick = onClick)
    ) {
        NetImage(p.thumbS, Modifier.fillMaxSize(), crossfade = !small)
        if (!small && (p.isVideo || p.isLive || p.collected)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
        }
        if (!small) Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.collected) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (!small) Row(Modifier.align(Alignment.BottomEnd).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.isVideo) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (p.duration > 0) Text(fmtDuration(p.duration), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            } else if (p.isLive) {
                Icon(Icons.Rounded.MotionPhotosOn, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

sealed class Entry {
    data class Header(val key: String, val title: String, val sub: String) : Entry()
    data class Cell(val photo: Photo, val index: Int) : Entry()
}

private val zh = Locale.SIMPLIFIED_CHINESE

fun dayTitle(day: String): Pair<String, String> {
    // day: "YYYY:MM:DD"
    val parts = day.split(':', '-')
    val d = runCatching { LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
        ?: return (day to "")
    val today = LocalDate.now()
    val title = when (d) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日" else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
    }
    return title to d.dayOfWeek.getDisplayName(JTextStyle.SHORT, zh)
}

/** Where a photo was taken, for date headers: NAS geo text or the WebDAV on-device analysis. */
fun placeOf(p: Photo): String = if (p.isCloud) com.hark.shiguang.Dav.lib(p.source).meta[p.cloudPath]?.city.orEmpty()
    else p.geo.split(',', '，', ' ').map { it.trim() }.lastOrNull { it.isNotEmpty() && it.any { c -> !c.isDigit() && c != '.' && c != '-' } }.orEmpty()

fun buildEntries(photos: List<Photo>, grouped: Boolean): List<Entry> {
    if (!grouped) return photos.mapIndexed { i, p -> Entry.Cell(p, i) }
    val out = ArrayList<Entry>(photos.size + 64)
    val places = HashMap<String, String>()
    photos.groupBy { it.day }.forEach { (d, l) -> places[d] = l.asSequence().map { placeOf(it) }.filter { it.isNotEmpty() }.distinct().take(2).joinToString("、") }
    var last = ""
    photos.forEachIndexed { i, p ->
        val d = p.day
        if (d != last) {
            val (t, s) = dayTitle(d); val pl = places[d].orEmpty()
            out += Entry.Header("h$d$i", t, if (pl.isEmpty()) s else "$s · $pl"); last = d
        }
        out += Entry.Cell(p, i)
    }
    return out
}

@Composable
fun SkeletonGrid(columns: Int, top: Dp) {
    val b = shimmerBrush()
    Column(Modifier.fillMaxSize().padding(top = top + 22.dp, start = 2.dp, end = 2.dp)) {
        Box(Modifier.padding(start = 12.dp, bottom = 10.dp).size(110.dp, 16.dp).clip(RoundedCornerShape(6.dp)).background(b))
        repeat(7) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(b)) }
            }
        }
    }
}

@Composable
fun StateMessage(icon: ImageVector, title: String, sub: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            GradientButton(action, Modifier.width(160.dp), onClick = onAction)
        }
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(C.Accent).clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = C.OnAccent, strokeWidth = 2.5.dp)
        else Text(text, color = C.OnAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = C.Text, bg: Color = C.Chip, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).border(0.5.dp, C.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/**
 * 第 4 批：影视 / 音乐共用的扫描进度卡（ScanStatusCard 式统一进度卡）。
 * 以前 MoviesTab.ScanBar 和 MusicTab.MusicScanBar 是两份几乎一样的实现（标题行 + 错误行 + 进度条），
 * 现在都调这个；样式保持原样（Surface 底、14dp 圆角、3dp 进度条），只抽公共部分，不改行为。
 * [progress] 为 null = 不确定进度（转圈条）；[error] 只在非扫描时显示。
 */
@Composable
fun ScanProgressCard(
    title: String,
    countText: String?,
    scanning: Boolean,
    error: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (countText != null) Text(countText, color = C.Sub, fontSize = 12.sp)
        }
        if (error != null && !scanning) Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (scanning) {
            if (progress == null) LinearProgressIndicator(color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            else LinearProgressIndicator(progress = { progress }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}
