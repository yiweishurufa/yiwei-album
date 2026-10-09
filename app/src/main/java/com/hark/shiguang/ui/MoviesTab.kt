package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*

/** Lets a header inside a padded grid use the full screen width. */
fun Modifier.bleed(h: androidx.compose.ui.unit.Dp) = this.layout { m, c ->
    val extra = (h * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-h.roundToPx(), 0) }
}

object MoviesState {
    var seg by mutableIntStateOf(0)        // 0 电影, 1 剧集
    // 1.0.3 #4: 0 电影剧集, 1 音乐 (the switch under the 影音 title)
    var area by mutableIntStateOf(Store.getStr("movies.area", "0").toIntOrNull() ?: 0)
    var sort by mutableIntStateOf(Store.getStr("movies.sort", "0").toIntOrNull() ?: 0)
    val sorts = listOf("最近添加", "名称", "年份", "评分")
}

private fun sorted(l: List<MovieItem>, s: Int): List<MovieItem> = when (s) {
    1 -> l.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.title })
    2 -> l.sortedByDescending { it.year ?: 0 }
    3 -> l.sortedByDescending { it.rating ?: 0f }
    else -> l.sortedByDescending { it.addedAt }
}

@Composable
fun MoviesTab() {
    if (MoviesState.area == 1) { MusicTab(); return }
    val key = Movies.keyOf()
    if (key == null) { Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.film(), "先添加一个来源", "影视库跟着左上角的来源走", "添加 WebDAV") { Nav.push(Route.DavAccounts) } } }; return }
    val lib = remember(key) { Movies.lib(key) }
    val grid = rememberMemGrid("movies-$key-${MoviesState.seg}")
    LaunchedEffect(key) {
        // first visit after a restart: pick up new files quietly (only new ones are scraped)
        if (lib.dirs.isNotEmpty() && !lib.scanning && System.currentTimeMillis() - (Store.getStr("movies.last.$key").toLongOrNull() ?: 0L) > 6 * 3600_000L) {
            Store.putStr("movies.last.$key", System.currentTimeMillis().toString()); lib.scan(); ScanService.ensure(App.ctx)
        }
    }
    val v = lib.version
    val kind = if (MoviesState.seg == 0) "movie" else "tv"
    val shown = remember(v, lib.items.size, kind, MoviesState.sort) { sorted(lib.items.filter { it.kind == kind }, MoviesState.sort) }
    val cont = remember(v, lib.items.size) { lib.continueWatching() }
    val counts = remember(v, lib.items.size) { lib.items.count { it.kind == "movie" } to lib.items.count { it.kind == "tv" } }

    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(3) }) { Box(Modifier.bleed(14.dp)) { TopSwitch() } }
        item(span = { GridItemSpan(3) }) {
            Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("影音", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                if (lib.dirs.isNotEmpty()) {
                    IconButton(onClick = { lib.scan(); ScanService.ensure(App.ctx) }, enabled = !lib.scanning) { Icon(LI.refresh(), null, tint = if (lib.scanning) C.Faint else C.Text, modifier = Modifier.size(21.dp)) }
                }
                IconButton(onClick = { Nav.push(Route.MovieLibrarySettings) }) { Icon(LI.settings(), null, tint = C.Text, modifier = Modifier.size(22.dp)) }
            }
        }
        item(span = { GridItemSpan(3) }) { Row(Modifier.padding(start = 2.dp)) { MediaAreaSwitch() } }
        if (lib.dirs.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().height(460.dp)) {
                    StateMessage(LI.film(), "还没有影视目录", "电影和剧集通常不和照片放在一起。选好它们所在的文件夹，我来扫描并配上海报和简介。", "选择影视文件夹") { Nav.push(Route.MovieLibrarySettings) }
                }
            }
            return@LazyVerticalGrid
        }
        item(span = { GridItemSpan(3) }) {
            Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("电影 ${counts.first.takeIf { it > 0 } ?: ""}".trim(), "剧集 ${counts.second.takeIf { it > 0 } ?: ""}".trim()), MoviesState.seg) { MoviesState.seg = it }
                Spacer(Modifier.weight(1f))
                SortMenu()
            }
        }
        if (lib.scanning || lib.error != null) item(span = { GridItemSpan(3) }) { ScanBar(lib) }
        if (cont.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    Text("继续观看", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(cont, key = { it.first.id }) { (m, ep) -> ContinueCard(lib, m, ep) }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            if (lib.scanning) items(9) { PosterSkeleton() }
            else item(span = { GridItemSpan(3) }) {
                Text(if (kind == "movie") "这些文件夹里还没找到电影。剧集文件夹可以在设置里标成「剧集」。" else "还没找到剧集。文件名里带 S01E02、第1集 这类写法就能认出来。",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp))
            }
        } else items(shown, key = { it.id }) { m -> PosterCard(m) { Nav.push(Route.MovieDetail(key, m.id)) } }
    }
}

@Composable
fun Segmented(items: List<String>, sel: Int, onPick: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(3.dp)) {
        items.forEachIndexed { i, t ->
            val on = i == sel
            Text(t, color = if (on) C.Text else C.Sub, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) C.Surface2 else Color.Transparent).clickable { onPick(i) }.padding(horizontal = 16.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun SortMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LI.sort(), null, tint = C.Sub, modifier = Modifier.size(17.dp))
            Text(MoviesState.sorts[MoviesState.sort], color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Surface)) {
            MoviesState.sorts.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s, color = C.Text, fontWeight = if (i == MoviesState.sort) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; MoviesState.sort = i; Store.putStr("movies.sort", "$i") })
            }
        }
    }
}

@Composable
private fun ScanBar(lib: MovieLib) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (lib.scanning) (lib.phase.ifEmpty { "正在扫描" }) else "扫描遇到问题", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lib.scanning && lib.total > 0) Text("${lib.done}/${lib.total}", color = C.Sub, fontSize = 12.sp)
        }
        if (lib.error != null && !lib.scanning) Text(lib.error!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (lib.scanning) {
            val p = if (lib.total > 0) lib.done / lib.total.toFloat() else null
            if (p == null) LinearProgressIndicator(color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            else LinearProgressIndicator(progress = { p }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}

/** Poster file path or url → something Coil loads. */
fun posterModel(p: String?): String? = p?.let { if (it.startsWith("/")) "file://$it" else TmdbNet.imgUrl(it) }

@Composable
fun PosterCard(m: MovieItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
            if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (m.kind == "tv") LI.tv() else LI.film(), null, tint = C.Faint, modifier = Modifier.size(26.dp))
                Text(m.title, color = C.Sub, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            m.rating?.let { r ->
                Text("%.1f".format(r), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
            if (!m.matched && m.source != "local") Text(if (m.pending) "待确认" else "未匹配", color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp))
        }
        Text(m.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(listOfNotNull(m.year?.toString(), if (m.kind == "tv") "${m.episodes.size} 集" else null).joinToString("  "), color = C.Sub, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PosterSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(shimmerBrush()))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.8f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(C.Surface2))
        Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.4f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(C.Surface2))
    }
}

@Composable
private fun ContinueCard(lib: MovieLib, m: MovieItem, ep: Episode?) {
    val path = ep?.path ?: m.files.maxByOrNull { lib.progressOf(it.path)?.at ?: 0L }?.path ?: return
    val pr = lib.progressOf(path)
    Column(Modifier.width(220.dp).clickable { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, path)) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
            val img = ep?.still ?: m.backdrop ?: m.poster
            if (img != null) NetImage(posterModel(img), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xAA000000))))
            Icon(LI.play(true), null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
            if (pr != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(pr.ratio).height(3.dp).background(C.Accent))
        }
        Text(m.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(if (ep != null) "第${ep.season}季 第${ep.episode}集" + (pr?.let { if (it.watched) "" else " · 剩 ${fmtLeft(it)}" } ?: " · 下一集") else pr?.let { "剩 ${fmtLeft(it)}" } ?: "",
            color = C.Sub, fontSize = 11.sp, maxLines = 1)
    }
}

private fun fmtLeft(p: Progress): String { val m = ((p.dur - p.pos) / 60000).coerceAtLeast(1); return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }
