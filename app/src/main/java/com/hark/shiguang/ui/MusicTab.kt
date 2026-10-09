@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hark.shiguang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.hark.shiguang.*
import kotlinx.coroutines.delay

/**
 * 1.0.3 #4 影音 · 音乐: 歌曲 / 专辑 / 歌手 / 文件夹, search, 全部播放 / 随机播放, the mini bar above the bottom navigation,
 * the full screen player with scrolling lyrics, and the 音乐 settings page.
 */
object MusicUi {
    var sub by mutableIntStateOf(Store.getStr("music.sub", "0").toIntOrNull() ?: 0)   // 0 歌曲 1 专辑 2 歌手 3 文件夹
    var query by mutableStateOf("")
}

private val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)

/** 电影剧集 | 音乐 at the top of the 影音 tab. */
@Composable
fun MediaAreaSwitch() {
    Segmented(listOf("电影剧集", "音乐"), MoviesState.area) { MoviesState.area = it; Store.putStr("movies.area", "$it") }
}

/** Little square cover (local file) with a note icon when there is none. */
@Composable
fun MusicArt(path: String?, modifier: Modifier, corner: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(C.Surface2), contentAlignment = Alignment.Center) {
        if (path != null) NetImage("file://$path", Modifier.fillMaxSize())
        else Icon(Icons.Rounded.MusicNote, null, tint = C.Faint, modifier = Modifier.fillMaxSize(0.42f))
    }
}

private fun fmtCount(n: Int) = "$n 首"

@Composable
fun MusicTab() {
    val key = Movies.keyOf()
    val ctx = LocalContext.current
    if (key == null) { Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(Icons.Rounded.MusicNote, "先添加一个来源", "音乐库跟着左上角的来源走", "添加 WebDAV") { Nav.push(Route.DavAccounts) } } }; return }
    val lib = remember(key) { Music.lib(key) }
    LaunchedEffect(key) {
        // first visit after a restart: pick up new files quietly (only new / changed files are read)
        if (lib.dirs.isNotEmpty() && !lib.scanning && System.currentTimeMillis() - (Store.getStr("music.last.$key").toLongOrNull() ?: 0L) > 6 * 3600_000L) {
            Store.putStr("music.last.$key", System.currentTimeMillis().toString()); lib.scan(); ScanService.ensure(App.ctx)
        }
    }
    val v = lib.version
    val q = MusicUi.query.trim()
    val all = remember(v, lib.tracks.size) { lib.tracks.toList() }
    val shown = remember(v, lib.tracks.size, q) {
        val base = if (q.isEmpty()) all else all.filter { t -> listOf(t.title, t.artist, t.album, t.albumArtist, t.name).any { it.contains(q, true) } }
        base.sortedWith(compareBy(collator) { it.title.ifBlank { it.name } })
    }
    val albums = remember(shown) {
        shown.groupBy { it.albumKey }.map { (k, l) -> MusicGroup("album", k, l.first().displayAlbum, l.first().albumArtist.ifBlank { l.first().displayArtist }, l.firstNotNullOfOrNull { it.artPath }, l.size) }
            .sortedWith(compareBy(collator) { it.title })
    }
    val artists = remember(shown) {
        val m = LinkedHashMap<String, MutableList<Track>>()
        shown.forEach { t -> t.artists.forEach { a -> m.getOrPut(a) { ArrayList() }.add(t) } }
        m.map { (a, l) -> MusicGroup("artist", a, a, "", l.firstNotNullOfOrNull { it.artPath }, l.size) }.sortedWith(compareBy(collator) { it.title })
    }
    val folders = remember(shown) {
        shown.groupBy { it.folder }.map { (f, l) -> MusicGroup("folder", f, f.trimEnd('/').substringAfterLast('/').removePrefix("fm:").ifEmpty { f }, f.removePrefix("fm:"), l.firstNotNullOfOrNull { it.artPath }, l.size) }
            .sortedWith(compareBy(collator) { it.title })
    }
    val list = rememberMemList("music-$key-${MusicUi.sub}")

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 170.dp)) {
        item { TopSwitch() }
        item {
            Row(Modifier.padding(top = 6.dp, start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("影音", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                if (lib.dirs.isNotEmpty()) IconButton(onClick = { lib.scan(); ScanService.ensure(App.ctx) }, enabled = !lib.scanning) {
                    Icon(LI.refresh(), null, tint = if (lib.scanning) C.Faint else C.Text, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = { Nav.push(Route.MusicSettings) }) { Icon(LI.settings(), null, tint = C.Text, modifier = Modifier.size(22.dp)) }
            }
        }
        item { Row(Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)) { MediaAreaSwitch() } }
        if (lib.dirs.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(440.dp)) {
                    StateMessage(Icons.Rounded.MusicNote, "还没有音乐文件夹", if (lib.kind == "fn") "选好飞牛「文件管理」里放音乐的文件夹，我来读取歌名、歌手、专辑和封面。" else "选好网盘里放音乐的文件夹，我来读取歌名、歌手、专辑和封面。音乐文件夹不会出现在照片和视频里。",
                        "选择音乐文件夹") { Nav.push(Route.MusicSettings) }
                }
            }
            return@LazyColumn
        }
        if (lib.scanning || lib.error != null) item { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { MusicScanBar(lib) } }
        item { SearchBox() }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Rounded.PlayArrow, "全部播放", Modifier.weight(1f), primary = true) {
                    if (shown.isEmpty()) toast(ctx, "还没有歌曲") else MusicState.play(key, shown, 0, shuffled = false)
                }
                PillButton(Icons.Rounded.Shuffle, "随机播放", Modifier.weight(1f)) {
                    if (shown.isEmpty()) toast(ctx, "还没有歌曲") else MusicState.play(key, shown, -1, shuffled = true)
                }
            }
        }
        item {
            Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)) {
                Segmented(listOf("歌曲 ${shown.size.takeIf { it > 0 } ?: ""}".trim(), "专辑", "歌手", "文件夹"), MusicUi.sub) { MusicUi.sub = it; Store.putStr("music.sub", "$it") }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(if (lib.scanning) "正在扫描…" else if (q.isNotEmpty()) "没有找到「$q」" else "这些文件夹里还没找到音乐。支持 mp3、flac、ape、wav、m4a、ogg、opus、wma、dsf、aiff、wv。",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp))
            }
            return@LazyColumn
        }
        when (MusicUi.sub) {
            0 -> itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> TrackRow(t, key) { MusicState.play(key, shown, i) } }
            1 -> items(albums.chunked(2), key = { it.first().key }) { row ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { g -> AlbumCard(g, Modifier.weight(1f)) { Nav.push(Route.MusicList("album", g.key, g.title)) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            2 -> items(artists, key = { "a" + it.key }) { g -> GroupRow(g, round = true) { Nav.push(Route.MusicList("artist", g.key, g.title)) } }
            else -> items(folders, key = { "f" + it.key }) { g -> GroupRow(g) { Nav.push(Route.MusicList("folder", g.key, g.title)) } }
        }
    }
}

data class MusicGroup(val kind: String, val key: String, val title: String, val sub: String, val art: String?, val count: Int)

@Composable
private fun SearchBox() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(LI.search(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (MusicUi.query.isEmpty()) Text("搜索歌名、歌手、专辑", color = C.Faint, fontSize = 14.sp)
            BasicTextField(MusicUi.query, { MusicUi.query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
        }
        if (MusicUi.query.isNotEmpty()) Icon(LI.close(), null, tint = C.Sub, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { MusicUi.query = "" })
    }
}

@Composable
private fun PillButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Row(modifier.height(42.dp).clip(RoundedCornerShape(14.dp)).background(if (primary) C.Accent else C.Surface).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (primary) C.OnAccent else C.Text, modifier = Modifier.size(19.dp))
        Text(text, color = if (primary) C.OnAccent else C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun MusicScanBar(lib: MusicLib) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (lib.scanning) lib.phase.ifEmpty { "正在扫描" } else "扫描遇到问题", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Format badge: lossless / DSD / formats played by the compatibility engine. */
private fun badge(t: Track): String? = when (t.ext) {
    "flac", "ape", "wv", "wav", "aiff", "aif", "aifc", "alac" -> t.ext.uppercase().let { if (it == "AIF" || it == "AIFC") "AIFF" else it }
    "dsf", "dff" -> "DSD"
    "m4a", "m4b" -> if (t.codec == "alac") "ALAC" else null
    "wma" -> "WMA"
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(t: Track, sourceKey: String, showNo: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val playing = MusicState.mediaId == "$sourceKey|${t.id}"
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNo) Text(if (t.trackNo > 0) "${t.trackNo}" else "·", color = if (playing) C.Accent else C.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
            else MusicArt(t.artPath, Modifier.size(46.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.title.ifBlank { t.name }, color = if (playing) C.Accent else C.Text, fontSize = 15.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    badge(t)?.let { b ->
                        Text(b, color = C.Sub, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp).clip(RoundedCornerShape(3.dp)).background(C.Chip).padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                    Text(listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.durationMs > 0) Text(Music.fmt(t.durationMs), color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, null, tint = C.Faint, modifier = Modifier.size(18.dp)) }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.background(C.Surface)) {
            DropdownMenuItem(text = { Text("下一首播放", color = C.Text) }, onClick = { menu = false; MusicState.playNext(sourceKey, t); toast(ctx, "下一首播放") })
            DropdownMenuItem(text = { Text("加到播放列表", color = C.Text) }, onClick = { menu = false; MusicState.enqueue(sourceKey, t); toast(ctx, "已加到播放列表") })
            if (t.album.isNotBlank()) DropdownMenuItem(text = { Text("查看专辑", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("album", t.albumKey, t.displayAlbum)) })
            DropdownMenuItem(text = { Text("查看歌手", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("artist", t.artists.first(), t.artists.first())) })
            DropdownMenuItem(text = { Column { Text("文件信息", color = C.Text); Text("${t.ext.uppercase()} · ${t.codec} · ${"%.1f".format(t.size / 1048576.0)} MB", color = C.Sub, fontSize = 11.sp)
                Text(t.path.removePrefix("fm:"), color = C.Faint, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }, onClick = { menu = false })
        }
    }
}

@Composable
private fun AlbumCard(g: MusicGroup, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        MusicArt(g.art, Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
        Text(g.title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(listOf(g.sub, fmtCount(g.count)).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupRow(g: MusicGroup, round: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (g.kind == "folder") Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) { Icon(LI.folder(), null, tint = C.Text, modifier = Modifier.size(22.dp)) }
        else MusicArt(g.art, Modifier.size(48.dp), corner = if (round) 24.dp else 8.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(g.title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(fmtCount(g.count), g.sub).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

// ============================================================ album / artist / folder

@Composable
fun MusicListScreen(r: Route.MusicList) {
    val key = Movies.keyOf() ?: return Page(r.title) { StateMessage(Icons.Rounded.MusicNote, "先添加一个来源", "") }
    val lib = remember(key) { Music.lib(key) }
    val v = lib.version
    val tracks = remember(v, lib.tracks.size, r) {
        when (r.kind) {
            "album" -> lib.tracks.filter { it.albumKey == r.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.name }))
            "artist" -> lib.tracks.filter { r.key in it.artists }.sortedWith(Comparator<Track> { a, b -> collator.compare(a.album, b.album) }.thenBy { it.discNo }.thenBy { it.trackNo })
            else -> lib.tracks.filter { it.folder == r.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.name }))
        }
    }
    val art = tracks.firstNotNullOfOrNull { it.artPath }
    val first = tracks.firstOrNull()
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Nav.pop() }) { Icon(LI.back(true), null, tint = C.Text) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.kind == "folder") Box(Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2), contentAlignment = Alignment.Center) { Icon(LI.folder(), null, tint = C.Text, modifier = Modifier.size(40.dp)) }
                    else MusicArt(art, Modifier.size(110.dp), corner = if (r.kind == "artist") 55.dp else 14.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(r.title, color = C.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = when (r.kind) {
                            "album" -> listOfNotNull(first?.albumArtist?.ifBlank { first.displayArtist }, first?.year?.takeIf { it > 0 }?.toString()).joinToString(" · ")
                            "folder" -> r.key.removePrefix("fm:")
                            else -> "${tracks.map { it.albumKey }.distinct().size} 张专辑"
                        }
                        if (sub.isNotBlank()) Text(sub, color = C.Sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text("${tracks.size} 首 · ${Music.fmt(tracks.sumOf { it.durationMs })}", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(Icons.Rounded.PlayArrow, "全部播放", Modifier.weight(1f), primary = true) { MusicState.play(key, tracks, 0, shuffled = false) }
                    PillButton(Icons.Rounded.Shuffle, "随机播放", Modifier.weight(1f)) { MusicState.play(key, tracks, -1, shuffled = true) }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, key, showNo = r.kind == "album") { MusicState.play(key, tracks, i) } }
        }
    }
}

// ============================================================ mini bar

/** Above the bottom navigation (or at the bottom on wide screens) while something is queued. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    if (!MusicState.active) return
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(MusicState.isPlaying, MusicState.mediaId) { while (true) { pos = MusicState.position; delay(500) } }
    val dur = MusicState.duration
    Column(modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).clickable { Nav.push(Route.MusicNowPlaying) }) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArt(t?.artPath, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t?.title?.ifBlank { t.name } ?: "正在准备…", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.displayArtist ?: "", color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { MusicState.toggle() }) {
                if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Icon(if (MusicState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = C.Text, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { MusicState.next() }) { Icon(Icons.Rounded.SkipNext, null, tint = C.Text, modifier = Modifier.size(26.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.Surface2)) {
            if (dur > 0) Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(C.Accent))
        }
    }
}

// ============================================================ full screen player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicNowPlayingScreen() {
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(Store.getStr("music.showLyrics") == "1") }
    var queueOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { pos = MusicState.position; delay(200) } }
    LaunchedEffect(MusicState.active) { if (!MusicState.active) { delay(400); if (!MusicState.active) Nav.pop() } }
    val dur = MusicState.duration.takeIf { it > 0 } ?: (t?.durationMs ?: 0L)
    val lib = remember(MusicState.mediaId) { MusicState.mediaId?.substringBefore('|')?.let { Music.lib(it) } }
    val lyrics by produceState<List<Lrc.Line>?>(null, t?.id) {
        value = null
        val tr = t ?: return@produceState
        val s = lib?.lyrics(tr)
        value = s?.let { val p = Lrc.parse(it); if (p.isNotEmpty()) p else Lrc.plain(it) } ?: emptyList()
    }
    BackHandler { if (queueOpen) queueOpen = false else Nav.pop() }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // soft blurred-looking backdrop: the cover, very dim
        if (t?.artPath != null) Box(Modifier.fillMaxSize()) {
            NetImage("file://${t.artPath}", Modifier.fillMaxSize(), crossfade = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.86f), C.Bg.copy(alpha = 0.95f), C.Bg))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Nav.pop() }) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = C.Text, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在播放", color = C.Sub, fontSize = 12.sp)
                    if (t != null) Text(t.displayAlbum, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { showLyrics = !showLyrics; Store.putStr("music.showLyrics", if (showLyrics) "1" else "") }) {
                    Text("词", color = if (showLyrics) C.Accent else C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                showLyrics = !showLyrics; Store.putStr("music.showLyrics", if (showLyrics) "1" else "")
            }, contentAlignment = Alignment.Center) {
                if (showLyrics) LyricsView(lyrics, pos) { MusicState.seek(it) }
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val scale by animateFloatAsState(if (MusicState.isPlaying) 1f else 0.9f, label = "art")
                    MusicArt(t?.artPath, Modifier.padding(horizontal = 36.dp).fillMaxWidth().aspectRatio(1f).graphicsScale(scale), corner = 18.dp)
                    // one current lyric line under the cover
                    val cur = lyrics?.let { l -> currentLine(l, pos) }?.let { lyrics?.getOrNull(it)?.text }
                    Text(cur ?: "", color = C.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 22.dp, start = 24.dp, end = 24.dp).fillMaxWidth())
                }
            }
            Column(Modifier.padding(horizontal = 26.dp)) {
                Text(t?.title?.ifBlank { t.name } ?: "", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Text(t?.displayArtist ?: "", color = C.Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    t?.let { badge(it) }?.let { b -> Text(b, color = C.Sub, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).background(C.Chip).padding(horizontal = 4.dp, vertical = 1.dp)) }
                }
                Slider(value = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { f -> if (dur > 0) MusicState.seek((f * dur).toLong()) }; drag = null }, enabled = dur > 0,
                    colors = SliderDefaults.colors(thumbColor = C.Text, activeTrackColor = C.Text, inactiveTrackColor = C.Surface2), modifier = Modifier.padding(top = 10.dp))
                Row {
                    Text(Music.fmt(drag?.let { (it * dur).toLong() } ?: pos), color = C.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (dur > 0) Music.fmt(dur) else "--:--", color = C.Sub, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val (modeIcon, modeTint) = when {
                    MusicState.shuffle -> Icons.Rounded.Shuffle to C.Accent
                    MusicState.repeat == Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to C.Accent
                    MusicState.repeat == Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to C.Text
                    else -> Icons.Rounded.Repeat to C.Faint
                }
                IconButton(onClick = { cycleMode() }) { Icon(modeIcon, null, tint = modeTint, modifier = Modifier.size(24.dp)) }
                IconButton(onClick = { MusicState.prev() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, null, tint = C.Text, modifier = Modifier.size(38.dp)) }
                Box(Modifier.size(70.dp).clip(CircleShape).background(C.Text).clickable { MusicState.toggle() }, contentAlignment = Alignment.Center) {
                    if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    else Icon(if (MusicState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = C.Bg, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { MusicState.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, null, tint = C.Text, modifier = Modifier.size(38.dp)) }
                IconButton(onClick = { queueOpen = true }) { Icon(Icons.Rounded.QueueMusic, null, tint = C.Text, modifier = Modifier.size(24.dp)) }
            }
            Text(modeText(), color = C.Faint, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp))
        }
    }
    if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = C.Surface) { QueueSheet() }
}

private fun Modifier.graphicsScale(s: Float): Modifier = this.graphicsLayer { scaleX = s; scaleY = s }

/** 列表循环 → 单曲循环 → 随机播放 → (back to 列表循环). */
private fun cycleMode() {
    when {
        MusicState.shuffle -> { MusicState.shuffleTo(false); MusicState.repeatTo(Player.REPEAT_MODE_ALL) }
        MusicState.repeat == Player.REPEAT_MODE_ONE -> { MusicState.repeatTo(Player.REPEAT_MODE_ALL); MusicState.shuffleTo(true) }
        else -> MusicState.repeatTo(Player.REPEAT_MODE_ONE)
    }
}
private fun modeText(): String = when {
    MusicState.shuffle -> "随机播放"
    MusicState.repeat == Player.REPEAT_MODE_ONE -> "单曲循环"
    MusicState.repeat == Player.REPEAT_MODE_ALL -> "列表循环"
    else -> "顺序播放"
}

private fun currentLine(l: List<Lrc.Line>, pos: Long): Int? {
    if (l.isEmpty() || l.first().ms < 0) return null
    var i = l.indexOfLast { it.ms <= pos + 300 }
    if (i < 0) i = 0
    return i
}

@Composable
private fun LyricsView(lines: List<Lrc.Line>?, pos: Long, onSeek: (Long) -> Unit) {
    when {
        lines == null -> CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(26.dp))
        lines.isEmpty() -> Text(if (MusicPrefs.online) "没有找到歌词" else "没有歌词（联网补全已关闭）", color = C.Sub, fontSize = 15.sp)
        else -> {
            val st = rememberLazyListState()
            val cur = currentLine(lines, pos)
            LaunchedEffect(cur) { if (cur != null) st.animateScrollToItem((cur - 3).coerceAtLeast(0)) }
            LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 120.dp, horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(lines) { i, l ->
                    val on = i == cur
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = l.ms >= 0) { onSeek(l.ms) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l.text.ifEmpty { "♪" }, color = if (on) C.Text else C.Sub.copy(alpha = 0.75f), fontSize = if (on) 19.sp else 16.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                        if (l.sub.isNotEmpty()) Text(l.sub, color = if (on) C.Sub else C.Faint, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSheet() {
    val q = MusicState.queue
    val st = rememberLazyListState(initialFirstVisibleItemIndex = (MusicState.index - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("  ${q.size} 首 · ${modeText()}", color = C.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { MusicState.stop() }) { Text("清空", color = C.Danger) }
        }
        LazyColumn(state = st, contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(q, key = { i, m -> "$i|${m.mediaId}" }) { i, m ->
                val on = i == MusicState.index
                Row(Modifier.fillMaxWidth().clickable { MusicState.playAt(i) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (on) Icon(Icons.Rounded.GraphicEq, null, tint = C.Accent, modifier = Modifier.size(18.dp).padding(end = 2.dp))
                    Column(Modifier.weight(1f).padding(start = if (on) 8.dp else 0.dp)) {
                        Text(m.mediaMetadata.title?.toString() ?: "", color = if (on) C.Accent else C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.mediaMetadata.artist?.toString() ?: "", color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!on) Icon(LI.close(), null, tint = C.Faint, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { MusicState.remove(i) })
                }
            }
        }
    }
}

// ============================================================ settings

@Composable
fun MusicSettingsScreen() {
    val ctx = LocalContext.current
    val key = Movies.keyOf()
    val lib = key?.let { remember(it) { Music.lib(it) } }
    var picking by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(MusicPrefs.online) }
    var confirmClear by remember { mutableStateOf(false) }
    val src = currentSource()
    Page("音乐库", src?.let { "${if (it.kind == "dav") "WebDAV" else "飞牛"} · ${it.title}" } ?: "") {
        if (lib == null) { StateMessage(Icons.Rounded.MusicNote, "先添加一个来源", ""); return@Page }
        if (picking) { FolderPicker(lib.fs, lib.kind, onDismiss = { picking = false }, fmOnly = true) { path, name ->
            picking = false
            if (lib.dirs.none { it.path == path }) { lib.saveDirs(lib.dirs + MusicDir(path, name)); lib.scan(); ScanService.ensure(ctx) }
        }; return@Page }
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            Section("音乐文件夹")
            Card {
                if (lib.dirs.isEmpty()) Text("和照片、影视分开设置。音乐文件夹里的文件不会出现在照片和视频页，影视扫描也会跳过它们。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                lib.dirs.forEachIndexed { i, d ->
                    if (i > 0) Div()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LI.folder(), null, tint = C.Text, modifier = Modifier.size(20.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(d.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.path.removePrefix("fm:"), color = C.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("移除", color = C.Danger, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { lib.saveDirs(lib.dirs - d) }.padding(6.dp))
                    }
                }
                Div()
                NavRow(LI.plus(), "添加文件夹", if (lib.kind == "fn") "飞牛请选「文件管理」里的文件夹（相册文件夹读不到音乐）" else "浏览这个 WebDAV") { picking = true }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(if (lib.scanning) "扫描中…" else "立即扫描", Modifier.weight(1f), loading = lib.scanning) {
                    if (lib.dirs.isEmpty()) toast(ctx, "先添加音乐文件夹") else { lib.scan(); ScanService.ensure(ctx); toast(ctx, "开始扫描，离开页面也会继续") }
                }
                Text("重新刮削", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).clickable(enabled = !lib.scanning) {
                        if (lib.dirs.isEmpty()) toast(ctx, "先添加音乐文件夹") else { lib.scan(rescrape = true); ScanService.ensure(ctx); toast(ctx, "重新读取所有歌曲的标签") }
                    }.padding(horizontal = 16.dp).wrapContentHeight(Alignment.CenterVertically))
            }
            Section("刮削")
            Card {
                ToggleRow(Icons.Rounded.Cloud, "联网补全", "文件里缺专辑、歌手、封面或歌词时，从网易云音乐（国内直连）和 LRCLIB 补上；不会改写文件里已有的信息", online) { online = it; MusicPrefs.online = it }
                Text("优先读取文件内嵌标签（ID3、FLAC、APE、MP4、WMA 等），同名 .lrc 歌词和 cover.jpg / folder.jpg 封面优先于联网结果。没有标签时按「歌手 - 歌名」文件名和文件夹名推断。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 2.dp))
            }
            Section("格式")
            Card {
                Text("mp3、flac、wav、m4a（AAC / ALAC）、ogg、opus 用内置播放器；ape、wma、wv、aiff、dsf / dff（DSD）用兼容模式（VLC）播放。个别文件放不了时会提示并自动跳到下一首。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            Section("缓存")
            Card { NavRow(LI.trash(), "清空这个来源的音乐库", "${lib.tracks.size} 首 · 封面和歌词缓存一起删除", danger = true) { confirmClear = true } }
        }
    }
    if (confirmClear && lib != null) ConfirmDialog("清空音乐库？", "歌曲信息、封面和歌词会重新扫描，文件本身不受影响。", "清空", onDismiss = { confirmClear = false }) { confirmClear = false; MusicState.stop(); lib.clearCache() }
}
