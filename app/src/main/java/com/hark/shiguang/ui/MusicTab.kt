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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 1.0.3 #4 影音 · 音乐: 歌曲 / 专辑 / 歌手 / 文件夹, search, 全部播放 / 随机播放, the mini bar above the bottom navigation,
 * the full screen player with scrolling lyrics, and the 音乐 settings page.
 */
object MusicUi {
    var sub by mutableIntStateOf(Store.getStr("music.sub", "0").toIntOrNull() ?: 0)   // 0 歌曲 1 专辑 2 歌手 3 文件夹
    var query by mutableStateOf("")
    // 全面审查 A8：歌曲排序 0 歌名 / 1 最近添加 / 2 歌手 / 3 时长，记在 Store
    var sort by mutableIntStateOf(Store.getStr("music.sort", "0").toIntOrNull()?.coerceIn(0, 3) ?: 0)
    val sorts = listOf("歌名", "最近添加", "歌手", "时长")
}

private val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)


/** Little square cover (local file) with a note icon when there is none. */
@Composable
fun MusicArt(path: String?, modifier: Modifier, corner: androidx.compose.ui.unit.Dp = Radius.s, lifted: Boolean = false) {
    // 1.6.6：封面圆角 10（列表）/ 20（大封面）；[lifted] = 质感风格下加一层照片轻影
    Box((if (lifted) modifier.skeuoPhoto(RoundedCornerShape(corner)) else modifier.clip(RoundedCornerShape(corner))).background(C.Surface2), contentAlignment = Alignment.Center) {
        if (path != null) NetImage("file://$path", Modifier.fillMaxSize())
        else Icon(KI.music(), null, tint = C.Faint, modifier = Modifier.fillMaxSize(0.42f))
    }
}

private fun fmtCount(n: Int) = "$n 首"

@Composable
fun MusicTab() {
    val key = Movies.keyOf()
    val ctx = LocalContext.current
    if (key == null) { Column { TopSwitch(SearchScope.None, showDate = false); Box(Modifier.weight(1f)) { NoSourceState(KI.music(), "音乐库跟着左上角的来源走") } }; return }
    val lib = remember(key) { Music.lib(key) }
    LaunchedEffect(key) {
        lib.awaitReady()   // #2 / H4: the library loads on IO; decide about the quiet rescan once it is in
        // first visit after a restart: pick up new files quietly (only new / changed files are read)
        autoScanIfStale("music.last.$key", lib.dirs.isNotEmpty(), lib.scanning) { lib.scan(quiet = true); ScanService.ensure(App.ctx) }
        // 用户 2026-11-02 反馈（WAV 中文 ??）：旧版存下的乱码歌名不等过期，进来就重读这些歌（其余不动）
        if (lib.needsRetag && !lib.scanning && lib.dirs.isNotEmpty()) { lib.needsRetag = false; Diag.log("MUSIC", "garbled tags cached, re-reading them"); lib.scan(quiet = true); ScanService.ensure(App.ctx) }
    }
    val v = lib.version
    val q = MusicUi.query.trim()
    val all = remember(v, lib.tracks.size) { lib.tracks.toList() }
    // H10: filtering, sorting and grouping run on Dispatchers.Default (only the open sub-page's groups); a newer
    // version cancels the older computation, so a scan's ~1/s updates never queue up on the main thread.
    val view by produceState<MusicView?>(null, all, q, MusicUi.sub, MusicUi.sort) {
        val sub = MusicUi.sub; val sort = MusicUi.sort
        value = withContext(Dispatchers.Default) { buildMusicView(all, q, sub, sort) }
    }
    val shown = view?.shown ?: emptyList()
    val albums = view?.albums ?: emptyList()
    val artists = view?.artists ?: emptyList()
    val folders = view?.folders ?: emptyList()
    val list = rememberMemList("music-$key-${MusicUi.sub}")

    val albumCols = adaptiveCols(2)
    // B7：下拉刷新 = 后台增量扫描（没变的文件夹不重列），有缓存时只显示一行「正在更新」
    PullRefreshBox(enabled = lib.ready && lib.dirs.isNotEmpty() && !lib.scanning, onRefresh = { lib.scan(quiet = true); ScanService.ensure(App.ctx) }) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 170.dp)) {
        item { MediaHeader("音乐", SearchScope.None, lib.scanning, lib.dirs.isNotEmpty(), "重新扫描音乐") { lib.scan(); ScanService.ensure(App.ctx) } }
        if (!lib.ready) { item { Box(Modifier.fillMaxWidth().height(440.dp)) { LibLoading() } }; return@LazyColumn }
        if (lib.dirs.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(440.dp)) {
                    StateMessage(KI.music(), "还没有音乐文件夹", if (lib.kind == "fn") "选好飞牛「文件管理」里放音乐的文件夹，我来读取歌名、歌手、专辑和封面。" else "选好网盘里放音乐的文件夹，我来读取歌名、歌手、专辑和封面。",
                        "选择音乐文件夹") { Nav.push(Route.MusicSettings) }
                }
            }
            return@LazyColumn
        }
        // 优化 11 / 12：有缓存时，自动刷新和刷新失败只显示一行小字（先显示上次的列表）；没缓存或手动全量扫描才用进度卡片
        val hasCache = lib.tracks.isNotEmpty()
        if ((lib.scanning && lib.quiet && hasCache) || (!lib.scanning && lib.error != null && hasCache))
            item { QuietStatusLine(lib.scanning, lib.error, Modifier.padding(horizontal = Space.page)) { lib.scan(); ScanService.ensure(App.ctx) } }
        else if (lib.scanning || lib.error != null) item { Box(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) { MusicScanBar(lib) } }
        item { SearchBox() }
        item {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                PillButton(KI.playSolid(), "全部播放", Modifier.weight(1f), primary = true) {
                    if (shown.isEmpty()) toast(ctx, "还没有歌曲") else MusicState.play(key, shown, 0, shuffled = false)
                }
                PillButton(KI.shuffle(), "随机播放", Modifier.weight(1f)) {
                    if (shown.isEmpty()) toast(ctx, "还没有歌曲") else MusicState.play(key, shown, -1, shuffled = true)
                }
            }
        }
        // A2：「我喜欢」「最近播放」入口（搜索时不显示）
        if (q.isEmpty()) item { MarkCards(lib) }
        item {
            Row(Modifier.padding(start = Space.page, end = Space.s, top = Space.s, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("歌曲 ${shown.size.takeIf { it > 0 } ?: ""}".trim(), "专辑", "歌手", "文件夹"), MusicUi.sub) { MusicUi.sub = it; Store.putStr("music.sub", "$it") }
                Spacer(Modifier.weight(1f))
                if (MusicUi.sub == 0) MusicSortMenu()
            }
        }
        if (shown.isEmpty()) {
            item {
                // 1.6.6 空状态：一个弱色线性图标 + 一句标题（+ 一句说明），不用小字堆说明
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    val busy = (view == null && all.isNotEmpty()) || lib.scanning
                    if (busy) CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    else Icon(if (q.isNotEmpty()) KI.search() else KI.music(), null, tint = C.Faint, modifier = Modifier.size(44.dp))
                    Text(if (view == null && all.isNotEmpty()) "正在整理…" else if (lib.scanning) "正在扫描…" else if (q.isNotEmpty()) "没有找到「$q」" else "还没找到音乐",
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.l))
                    if (!busy && q.isEmpty()) Text("支持 mp3、flac、ape、wav、m4a、ogg、opus、wma、dsf、aiff、wv。", style = MaterialTheme.typography.bodyMedium, color = C.Sub,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s))
                }
            }
            return@LazyColumn
        }
        when (MusicUi.sub) {
            0 -> itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> TrackRow(t, key, index = i) { MusicState.play(key, shown, i) } }
            // 全面审查 B11：专辑列数随屏宽变化（手机 2 列，平板 / 横屏更多），不再固定两列
            1 -> items(albums.chunked(albumCols), key = { it.first().key }) { row ->
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    row.forEach { g -> AlbumCard(g, Modifier.weight(1f), albums.indexOf(g)) { Nav.push(Route.MusicList("album", g.key, g.title)) } }
                    repeat(albumCols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            2 -> items(artists, key = { "a" + it.key }) { g -> GroupRow(g, round = true) { Nav.push(Route.MusicList("artist", g.key, g.title)) } }
            else -> items(folders, key = { "f" + it.key }) { g -> GroupRow(g) { Nav.push(Route.MusicList("folder", g.key, g.title)) } }
        }
    }
    }
}

data class MusicGroup(val kind: String, val key: String, val title: String, val sub: String, val art: String?, val count: Int)

/** H10: what the 音乐 page shows, computed off the main thread. Groups are built only for the open sub-page. */
class MusicView(val shown: List<Track>, val albums: List<MusicGroup>, val artists: List<MusicGroup>, val folders: List<MusicGroup>)

private fun buildMusicView(all: List<Track>, q: String, sub: Int, sort: Int = 0): MusicView {
    val col = java.text.Collator.getInstance(java.util.Locale.CHINA)   // own instance: Collator is not thread-safe
    fun <T> sortByKey(l: List<T>, s: (T) -> String): List<T> = l.map { col.getCollationKey(s(it)) to it }.sortedBy { it.first }.map { it.second }
    val base = if (q.isEmpty()) all else all.filter { t -> listOf(t.title, t.artist, t.album, t.albumArtist, t.name).any { it.contains(q, true) } }
    val byTitle = sortByKey(base) { it.title.ifBlank { it.name } }
    val shown = when (sort) {
        1 -> byTitle.sortedByDescending { it.mtime }
        2 -> sortByKey(byTitle) { it.displayArtist }
        3 -> byTitle.sortedByDescending { it.durationMs }
        else -> byTitle
    }
    val albums = if (sub != 1) emptyList() else sortByKey(shown.groupBy { it.albumKey }.map { (k, l) -> MusicGroup("album", k, l.first().displayAlbum, l.first().albumArtist.ifBlank { l.first().displayArtist }, l.firstNotNullOfOrNull { it.artPath }, l.size) }) { it.title }
    val artists = if (sub != 2) emptyList() else {
        val m = LinkedHashMap<String, MutableList<Track>>()
        shown.forEach { t -> t.artists.forEach { a -> m.getOrPut(a) { ArrayList() }.add(t) } }
        sortByKey(m.map { (a, l) -> MusicGroup("artist", a, a, "", l.firstNotNullOfOrNull { it.artPath }, l.size) }) { it.title }
    }
    val folders = if (sub != 3) emptyList() else sortByKey(shown.groupBy { it.folder }.map { (f, l) -> MusicGroup("folder", f, f.trimEnd('/').substringAfterLast('/').removePrefix("fm:").ifEmpty { f }, f.removePrefix("fm:"), l.firstNotNullOfOrNull { it.artPath }, l.size) }) { it.title }
    return MusicView(shown, albums, artists, folders)
}

/** A2：歌曲页上方两张小卡片，点开是「我喜欢」/「最近播放」列表。1.6.6：去掉强调色方块底，线性图标 + 质感卡片 + 按压。 */
@Composable
private fun MarkCards(lib: MusicLib) {
    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        MarkCard(KI.heart(), "我喜欢", lib.favs.size, Modifier.weight(1f)) { Nav.push(Route.MusicList("fav", "", "我喜欢")) }
        MarkCard(KI.history(), "最近播放", lib.recent.size, Modifier.weight(1f)) { Nav.push(Route.MusicList("recent", "", "最近播放")) }
    }
}

@Composable
private fun MarkCard(icon: ImageVector, title: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val countText = if (count > 0) fmtCount(count) else "还没有"
    Row(modifier.heightIn(min = 60.dp).pressScale(Motion.PRESS_SCALE_LARGE).skeuoCard(RoundedCornerShape(Radius.l)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "$title，$countText" }.padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = Space.m)) {
            Text(title, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(countText, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1)
        }
    }
}

@Composable
private fun SearchBox() {
    // 1.6.6：内凹的搜索槽（与影视搜索同款），占位提示用次要色
    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs).fillMaxWidth().height(48.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(KI.search(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(start = Space.s), contentAlignment = Alignment.CenterStart) {
            if (MusicUi.query.isEmpty()) Text("搜索歌名、歌手、专辑", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(MusicUi.query, { MusicUi.query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text),
                cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索音乐" })
        }
        // H17: 48dp touch target (was an 18dp icon) and a spoken label
        if (MusicUi.query.isNotEmpty()) IconButton(onClick = { MusicUi.query = "" }) { Icon(KI.close(), "清除搜索", tint = C.Sub, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun PillButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    // 1.6.6：与主 / 次要按钮同一套按压（缩放 + 质感浮起），高 46
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(modifier.height(46.dp).skeuoPressable(ix, RoundedCornerShape(Radius.m), if (primary) C.Accent else if (Skeuo.on) C.Surface else C.Surface2)
        .clickable(ix, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (primary) C.OnAccent else C.Text, modifier = Modifier.size(18.dp))
        Text(text, color = if (primary) C.OnAccent else C.Text, style = MaterialTheme.typography.labelLarge, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(start = Space.s))
    }
}

@Composable
private fun MusicScanBar(lib: MusicLib) {
    // 第 4 批：复用共用 ScanProgressCard（与影视共用一份实现，样式与旧版一致）。
    ScanProgressCard(
        title = if (lib.scanning) lib.phase.ifEmpty { "正在扫描" } else "扫描遇到问题",
        countText = if (lib.scanning && lib.total > 0) "${lib.done}/${lib.total}" else null,
        scanning = lib.scanning,
        error = lib.error,
        progress = if (lib.scanning && lib.total > 0) lib.done / lib.total.toFloat() else null,
        onRetry = { lib.scan(); ScanService.ensure(App.ctx) },
    )
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
private fun TrackRow(t: Track, sourceKey: String, showNo: Boolean = false, index: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val playing = MusicState.mediaId == "$sourceKey|${t.id}"
    val lib = remember(sourceKey) { Music.lib(sourceKey) }
    val fav = t.id in lib.favs
    var menu by remember { mutableStateOf(false) }
    Box {
        // 1.6.6：正在播放的这首 = 封面上压一层三根跳动小柱（只在真在播时动）+ 标题强调色；时长等宽数字次要色
        val sub = listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · ")
        Row(Modifier.fillMaxWidth().enterRise(index).combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "更多操作")
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(t.title.ifBlank { t.name }, sub.takeIf { it.isNotEmpty() }, if (fav) "已喜欢" else null, if (playing) "正在播放" else null,
                    t.durationMs.takeIf { it > 0 }?.let { Music.fmt(it) }).joinToString("，")
            }
            .heightIn(min = Space.row + Space.xs).padding(horizontal = Space.page, vertical = Space.xs + 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNo) Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (playing) NowPlayingBars(MusicState.isPlaying, C.Accent)
                else Text(if (t.trackNo > 0) "${t.trackNo}" else "·", color = C.Sub, style = MaterialTheme.typography.bodyMedium.merge(Type.num), textAlign = TextAlign.Center)
            }
            else Box(Modifier.size(48.dp)) {
                MusicArt(t.artPath, Modifier.fillMaxSize())
                if (playing) Box(Modifier.matchParentSize().clip(RoundedCornerShape(Radius.s)).background(Color(0x73000000)), contentAlignment = Alignment.Center) { NowPlayingBars(MusicState.isPlaying, Color.White) }
            }
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(t.title.ifBlank { t.name }, color = if (playing) C.Accent else C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, fontWeight = if (playing) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fav) Icon(KI.heartSolid(), null, tint = C.Accent, modifier = Modifier.padding(end = Space.xs).size(12.dp))
                    badge(t)?.let { b ->
                        Text(b, color = C.Sub, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, modifier = Modifier.padding(end = Space.xs + 1.dp).clip(RoundedCornerShape(3.dp)).background(C.Chip).padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                    Text(sub, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.durationMs > 0) Text(Music.fmt(t.durationMs), color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), modifier = Modifier.padding(start = Space.s))
            IconButton(onClick = { menu = true }) { Icon(KI.more(), "更多", tint = C.Sub, modifier = Modifier.size(18.dp)) }
        }
        AppDropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (fav) "取消喜欢" else "加入我喜欢", color = C.Text) }, onClick = {
                menu = false; toastUndo(ctx, if (lib.toggleFav(t.id)) "已加入我喜欢" else "已取消喜欢") { lib.toggleFav(t.id) }
            })
            DropdownMenuItem(text = { Text("下一首播放", color = C.Text) }, onClick = { menu = false; MusicState.playNext(sourceKey, t); toast(ctx, "下一首播放") })
            DropdownMenuItem(text = { Text("加到播放列表", color = C.Text) }, onClick = { menu = false; MusicState.enqueue(sourceKey, t); toast(ctx, "已加到播放列表") })
            // 1.1.0: libVLC removed — ape / wma / DSD etc. go to another app (auth through the loopback proxy)
            DropdownMenuItem(text = { Text(if (t.ext in Music.UNSUPPORTED) "用其他播放器打开（内置不支持）" else "用其他播放器打开", color = C.Text) }, onClick = {
                menu = false
                Ops.launch {
                    val r = withContext(Dispatchers.IO) { runCatching {
                        val fs = Music.lib(sourceKey).fs
                        val raw = fs.playUrl(MediaFile(t.path, t.name, t.size)) ?: error("读不到播放地址")
                        raw to fs.headers(raw)
                    } }
                    r.onSuccess { (u, h) -> ExternalPlayer.open(ctx, u, h, t.name, audio = true)?.let { toast(ctx, it) } }.onFailure { failToast(ctx, it, "打开失败") }
                }
            })
            if (t.album.isNotBlank()) DropdownMenuItem(text = { Text("查看专辑", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("album", t.albumKey, t.displayAlbum)) })
            DropdownMenuItem(text = { Text("查看歌手", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("artist", t.artists.first(), t.artists.first())) })
            DropdownMenuItem(text = { Column { Text("文件信息", color = C.Text); Text("${t.ext.uppercase()} · ${t.codec} · ${"%.1f".format(t.size / 1048576.0)} MB", color = C.Sub, fontSize = 11.sp)
                Text(t.path.removePrefix("fm:"), color = C.Sub, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }, onClick = { menu = false })
        }
    }
}

/** 专辑卡：方形封面圆角 10 + 质感轻影，按压 0.985，首屏错开进入；读屏整张读成一个按钮。 */
@Composable
private fun AlbumCard(g: MusicGroup, modifier: Modifier, index: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    val sub = listOf(g.sub, fmtCount(g.count)).filter { it.isNotBlank() }.joinToString(" · ")
    Column(modifier.enterRise(index).pressScale(Motion.PRESS_SCALE_LARGE).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "${g.title}，$sub" }) {
        MusicArt(g.art, Modifier.fillMaxWidth().aspectRatio(1f), corner = Radius.s, lifted = true)
        Text(g.title, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        Text(sub, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupRow(g: MusicGroup, round: Boolean = false, onClick: () -> Unit) {
    val sub = listOf(fmtCount(g.count), g.sub).filter { it.isNotBlank() }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "${g.title}，$sub" }
        .heightIn(min = Space.row + Space.xs).padding(horizontal = Space.page, vertical = Space.xs + 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (g.kind == "folder") Box(Modifier.size(48.dp).clip(RoundedCornerShape(Radius.s)).background(C.Surface2), contentAlignment = Alignment.Center) { Icon(KI.folder(), null, tint = C.Sub, modifier = Modifier.size(22.dp)) }
        else MusicArt(g.art, Modifier.size(48.dp), corner = if (round) 24.dp else Radius.s)
        Column(Modifier.weight(1f).padding(start = Space.m)) {
            Text(g.title, color = C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(KI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

// ============================================================ album / artist / folder

@Composable
fun MusicListScreen(r: Route.MusicList) {
    val key = Movies.keyOf() ?: return Page(r.title) { StateMessage(KI.music(), "先添加一个来源", "") }
    val lib = remember(key) { Music.lib(key) }
    val v = lib.version
    // A2：「我喜欢」「最近播放」按记录的顺序（新的在前），库里已不存在的歌跳过
    val marks = when (r.kind) { "fav" -> lib.favs.toList(); "recent" -> lib.recent.toList(); else -> null }
    val tracks = remember(v, lib.tracks.size, r, marks) {
        when (r.kind) {
            "fav", "recent" -> { val byId = lib.tracks.associateBy { it.id }; marks.orEmpty().mapNotNull { byId[it] } }
            "album" -> lib.tracks.filter { it.albumKey == r.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.name }))
            "artist" -> lib.tracks.filter { r.key in it.artists }.sortedWith(Comparator<Track> { a, b -> collator.compare(a.album, b.album) }.thenBy { it.discNo }.thenBy { it.trackNo })
            else -> lib.tracks.filter { it.folder == r.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.name }))
        }
    }
    val art = tracks.firstNotNullOfOrNull { it.artPath }
    val first = tracks.firstOrNull()
    Column(Modifier.fillMaxSize().appBackground()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                // 1.6.6：二级页同款 44dp 返回圆钮
                Row(Modifier.statusBarsPadding().padding(start = Space.s + 2.dp, end = Space.xs, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(KI.back(), size = 44.dp, desc = "返回") { Nav.pop() }
                    Spacer(Modifier.weight(1f))
                    if (r.kind == "recent" && tracks.isNotEmpty()) HeaderTextButton("清空", C.Sub) { lib.clearRecent() }
                }
            }
            item {
                // 1.6.6：封面 116dp（专辑 / 歌手有照片轻影），我喜欢 / 最近 / 文件夹用中性底 + 线性图标（不再铺强调色方块）
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (r.kind == "fav" || r.kind == "recent" || r.kind == "folder") Box(Modifier.size(116.dp).skeuoCard(RoundedCornerShape(Radius.l), color = C.Surface2), contentAlignment = Alignment.Center) {
                        Icon(when (r.kind) { "fav" -> KI.heart(); "recent" -> KI.history(); else -> KI.folder() }, null, tint = C.Sub, modifier = Modifier.size(40.dp))
                    }
                    else MusicArt(art, Modifier.size(116.dp), corner = if (r.kind == "artist") 58.dp else Radius.l, lifted = true)
                    Column(Modifier.padding(start = Space.l).weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        val sub = when (r.kind) {
                            "album" -> listOfNotNull(first?.albumArtist?.ifBlank { first.displayArtist }, first?.year?.takeIf { it > 0 }?.toString()).joinToString(" · ")
                            "folder" -> r.key.removePrefix("fm:")
                            "fav" -> "在正在播放页点 ♡，或长按歌曲加入"
                            "recent" -> "最近听过的 ${MusicMarks.RECENT_MAX} 首以内"
                            else -> "${tracks.map { it.albumKey }.distinct().size} 张专辑"
                        }
                        if (sub.isNotBlank()) Text(sub, color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
                        Text("${tracks.size} 首 · ${Music.fmt(tracks.sumOf { it.durationMs })}", color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), modifier = Modifier.padding(top = Space.xs))
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PillButton(KI.playSolid(), "全部播放", Modifier.weight(1f), primary = true) { MusicState.play(key, tracks, 0, shuffled = false) }
                    PillButton(KI.shuffle(), "随机播放", Modifier.weight(1f)) { MusicState.play(key, tracks, -1, shuffled = true) }
                }
            }
            if (tracks.isEmpty() && (r.kind == "fav" || r.kind == "recent")) item {
                Text(if (r.kind == "fav") "还没有喜欢的歌" else "还没有播放记录", style = MaterialTheme.typography.titleLarge, color = C.Sub, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xl))
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, key, showNo = r.kind == "album", index = i) { MusicState.play(key, tracks, i) } }
        }
    }
}

// ============================================================ mini bar

/** Extra bottom space for Home tab lists while the mini bar is showing, so it no longer covers the last row. */
val miniPlayerPad: Dp get() = if (MusicState.active && !Selection.active) 64.dp else 0.dp

/**
 * Above the bottom navigation (or at the bottom on wide screens) while something is queued.
 * 1.6.6 阶段 5：浮起的胶囊（圆角 20、质感卡片影）、底部 2dp 进度细线；整条按压 0.985，点开正在播放页（从下往上展开）；
 * 播放 / 暂停换图标有缩放过渡；出现 / 消失由 Home 里的 AnimatedVisibility 从底部滑入滑出。
 */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null) {
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    // 全面审查 B10：暂停时只读一次位置，不再每 0.5 秒刷新
    LaunchedEffect(MusicState.isPlaying, MusicState.mediaId) { pos = MusicState.position; while (MusicState.isPlaying) { delay(500); pos = MusicState.position } }
    val dur = MusicState.duration
    val title = t?.title?.ifBlank { t.name } ?: "正在准备…"
    // 1.7.0 v2：玻璃胶囊（和底栏同宽、首页上有真模糊），按下液体形变；环境光晕取当前封面
    AmbientFrom(t?.artPath?.let { "file://$it" })
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.l)
    Column(modifier.padding(horizontal = 14.dp).fillMaxWidth().glassPress(press, 0.5f).glass(shape, backdrop, shadow = 14.dp).clip(shape)
        .clickable(press, null, onClickLabel = "打开正在播放") { Nav.push(Route.MusicNowPlaying) }
        .semantics(mergeDescendants = false) { contentDescription = "正在播放：$title" + (t?.displayArtist?.takeIf { it.isNotBlank() }?.let { "，$it" } ?: "") }) {
        Row(Modifier.padding(start = Space.s, end = Space.xs, top = Space.s, bottom = Space.s - 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArt(t?.artPath, Modifier.size(42.dp), corner = Radius.l - Space.s)
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(title, color = C.OnGlass, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.displayArtist ?: "", color = C.OnGlassSub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { MusicState.toggle() }, modifier = Modifier.semantics { contentDescription = if (MusicState.isPlaying) "暂停" else "播放" }) {
                if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else PlayPauseIcon(MusicState.isPlaying, C.OnGlass, 24.dp)
            }
            IconButton(onClick = { MusicState.next() }) { Icon(KI.skipFwdSolid(), "下一首", tint = C.OnGlass, modifier = Modifier.size(22.dp)) }
        }
        Box(Modifier.padding(horizontal = Space.l).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.Line)) {
            if (dur > 0) Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(C.Accent))
        }
        Spacer(Modifier.height(Space.xs + 2.dp))
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

    // 1.6.6 阶段 5：Kubai 图标、实心播放钮 72dp（按压 0.92、换图标缩放过渡）、可加粗的进度条、心形收藏回弹、
    // 封面暂停时轻轻缩小（弹簧，「移除动画」时直接切换）；时间与时长等宽数字
    Box(Modifier.fillMaxSize().appBackground()) {
        // 1.7.0 v2：封面放大并重度模糊铺满全屏（API 31+ 真模糊；以下仍是压暗的封面），环境光晕取封面颜色
        AmbientFrom(t?.artPath?.let { "file://$it" })
        if (t?.artPath != null) Box(Modifier.fillMaxSize()) {
            val real = Glass.on && Glass.blurSupported
            NetImage("file://${t.artPath}", Modifier.fillMaxSize().graphicsLayer { if (real) { scaleX = 1.3f; scaleY = 1.3f } }.then(if (real) Modifier.blur(80.dp) else Modifier), crossfade = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(if (real) listOf(C.Bg.copy(alpha = 0.45f), C.Bg.copy(alpha = 0.7f), C.Bg.copy(alpha = 0.92f))
                else listOf(C.Bg.copy(alpha = 0.86f), C.Bg.copy(alpha = 0.95f), C.Bg))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = Space.xs, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Nav.pop() }) { Icon(KI.down(), "收起", tint = C.Text, modifier = Modifier.size(28.dp)) }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在播放", color = C.Sub, style = MaterialTheme.typography.labelMedium)
                    if (t != null) Text(t.displayAlbum, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // A2：喜欢当前这首
                if (t != null && lib != null) {
                    val fav = t.id in lib.favs
                    IconButton(onClick = { lib.toggleFav(t.id) }) {
                        Icon(if (fav) KI.heartSolid() else KI.heart(), if (fav) "取消喜欢" else "喜欢", tint = if (fav) C.Accent else C.Text, modifier = Modifier.size(24.dp).popOnChange(fav))
                    }
                }
                SleepButton()
                val lyrIx = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val lyrBg by androidx.compose.animation.animateColorAsState(if (showLyrics) C.AccentSoft else Color.Transparent, Motion.state(), label = "lyrBg")
                Box(Modifier.size(48.dp).clickable(lyrIx, null, role = androidx.compose.ui.semantics.Role.Switch) { showLyrics = !showLyrics; Store.putStr("music.showLyrics", if (showLyrics) "1" else "") }
                    .semantics { contentDescription = "歌词"; stateDescription = if (showLyrics) "已显示" else "已隐藏" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp).pressScale(0.92f).clip(CircleShape).background(lyrBg), contentAlignment = Alignment.Center) {
                        Text("词", color = if (showLyrics) C.Accent else C.Text, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                showLyrics = !showLyrics; Store.putStr("music.showLyrics", if (showLyrics) "1" else "")
            }, contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(showLyrics, animationSpec = Motion.enter(), label = "lyrics") { ly ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (ly) LyricsView(lyrics, pos) { MusicState.seek(it) }
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val scale by animateFloatAsState(if (MusicState.isPlaying) 1f else 0.9f, if (Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 260f), label = "art")
                            MusicArt(t?.artPath, Modifier.padding(horizontal = 36.dp).fillMaxWidth().aspectRatio(1f).graphicsScale(scale), corner = Radius.l, lifted = true)
                            // one current lyric line under the cover
                            val cur = lyrics?.let { l -> currentLine(l, pos) }?.let { lyrics?.getOrNull(it)?.text }
                            androidx.compose.animation.AnimatedContent(cur ?: "", transitionSpec = {
                                (fadeIn(Motion.enter()) + androidx.compose.animation.slideInVertically(Motion.move()) { it / 2 }) togetherWith fadeOut(Motion.exit())
                            }, label = "curLine") { line ->
                                Text(line, color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 22.dp, start = Space.xl, end = Space.xl).fillMaxWidth())
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 26.dp)) {
                Text(t?.title?.ifBlank { t.name } ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xxs)) {
                    Text(t?.displayArtist ?: "", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    t?.let { badge(it) }?.let { b -> Text(b, color = C.Sub, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, modifier = Modifier.padding(start = Space.s).clip(RoundedCornerShape(4.dp)).background(C.Chip).padding(horizontal = 4.dp, vertical = 1.dp)) }
                }
                val shownPos = drag?.let { (it * dur).toLong() } ?: pos
                ScrubBar(drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, enabled = dur > 0,
                    onChange = { drag = it }, onCommit = { f -> if (dur > 0) MusicState.seek((f * dur).toLong()); drag = null },
                    modifier = Modifier.padding(top = Space.m), valueText = "${Music.fmt(shownPos)} / ${if (dur > 0) Music.fmt(dur) else "--:--"}")
                Row {
                    Text(Music.fmt(shownPos), color = if (drag != null) C.Text else C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), modifier = Modifier.weight(1f))
                    Text(if (dur > 0) Music.fmt(dur) else "--:--", color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val modeTint = when {
                    MusicState.shuffle || MusicState.repeat == Player.REPEAT_MODE_ONE -> C.Accent
                    MusicState.repeat == Player.REPEAT_MODE_ALL -> C.Text
                    else -> C.Faint
                }
                IconButton(onClick = { cycleMode() }, modifier = Modifier.semantics { stateDescription = modeText() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (MusicState.shuffle) KI.shuffle() else KI.repeat(), "播放模式", tint = modeTint, modifier = Modifier.size(24.dp))
                        // 单曲循环：循环图标中间加一个小「1」（Kubai 没有 repeat-one）
                        if (!MusicState.shuffle && MusicState.repeat == Player.REPEAT_MODE_ONE) Text("1", color = modeTint, style = Type.badge, fontSize = 9.sp)
                    }
                }
                IconButton(onClick = { MusicState.prev() }, modifier = Modifier.size(56.dp)) { Icon(KI.skipBackSolid(), "上一首", tint = C.Text, modifier = Modifier.size(30.dp)) }
                val playIx = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(Modifier.size(72.dp).skeuoPressable(playIx, CircleShape, C.Text, scale = 0.92f, flat = true).clickable(playIx, null, role = androidx.compose.ui.semantics.Role.Button) { MusicState.toggle() }
                    .semantics { contentDescription = if (MusicState.isPlaying) "暂停" else "播放" }, contentAlignment = Alignment.Center) {
                    if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    else PlayPauseIcon(MusicState.isPlaying, C.Bg, 32.dp)
                }
                IconButton(onClick = { MusicState.next() }, modifier = Modifier.size(56.dp)) { Icon(KI.skipFwdSolid(), "下一首", tint = C.Text, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = { queueOpen = true }) { Icon(KI.playlist(), "播放列表", tint = C.Text, modifier = Modifier.size(24.dp)) }
            }
            Text(modeText(), color = C.Sub, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clearAndSetSemantics {})
        }
    }
    if (queueOpen) AppBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = C.Surface) { QueueSheet() }
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
        lines == null -> CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        lines.isEmpty() -> Text(if (MusicPrefs.online) "没有找到歌词" else "没有歌词（联网补全已关闭）", color = C.Sub, style = MaterialTheme.typography.bodyLarge)
        else -> {
            val st = rememberLazyListState()
            val cur = currentLine(lines, pos)
            // 1.6.6：当前行滚到视口约 40% 处（弹簧滚动，用户手动滑动时不抢）；字号固定，当前行靠颜色 + 缩放 1.0 / 0.94 区分，
            // 换行时不重新排版（以前字号 16↔19 来回变会让整列跳一下）
            val density = androidx.compose.ui.platform.LocalDensity.current
            LaunchedEffect(cur) {
                if (cur != null && !st.isScrollInProgress) {
                    val off = -(st.layoutInfo.viewportSize.height * 0.4f).toInt() + with(density) { 120.dp.roundToPx() }
                    if (Motion.reduced) st.scrollToItem(cur, off) else st.animateScrollToItem(cur, off)
                }
            }
            LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 120.dp, horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(lines) { i, l ->
                    val on = i == cur
                    val fg by androidx.compose.animation.animateColorAsState(if (on) C.Text else C.Sub.copy(alpha = 0.7f), Motion.state(), label = "lrcFg")
                    val sc by animateFloatAsState(if (on) 1f else 0.94f, Motion.move(), label = "lrcScale")
                    Column(Modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(Radius.s)).clickable(enabled = l.ms >= 0, onClickLabel = "从这句开始播放") { onSeek(l.ms) }.padding(vertical = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l.text.ifEmpty { "♪" }, color = fg, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
                        if (l.sub.isNotEmpty()) Text(l.sub, color = C.Sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
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
    var confirmClear by remember { mutableStateOf(false) }
    // 全面审查 B9：右侧拖动手柄排序；拖动时这一行跟着手指走，松手后按移动的行数换位置
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
        Row(Modifier.padding(start = Space.xl, end = Space.s, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
                Text("播放列表", style = MaterialTheme.typography.titleLarge)
                Text("${q.size} 首 · ${modeText()}", color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(start = Space.s, bottom = 2.dp))
            }
            HeaderTextButton("清空", C.Danger) { confirmClear = true }
        }
        LazyColumn(state = st, contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(q, key = { i, m -> "$i|${m.mediaId}" }) { i, m ->
                val on = i == MusicState.index
                val dragging = dragFrom == i
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { if (dragging) { translationY = dragDy; shadowElevation = 8f } }
                    .background(if (dragging) C.Surface else Color.Transparent)
                    .clickable(onClickLabel = "播放这首") { MusicState.playAt(i) }.padding(start = Space.xl, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (on) NowPlayingBars(MusicState.isPlaying, C.Accent, Modifier.padding(end = Space.m))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { if (on) stateDescription = "正在播放" }) {
                        Text(m.mediaMetadata.title?.toString() ?: "", color = if (on) C.Accent else C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.mediaMetadata.artist?.toString() ?: "", color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // B9：删除按钮 48dp 触控区（原来 18dp 图标直接点，容易点歪）
                    if (!on) IconButton(onClick = { MusicState.remove(i) }) { Icon(KI.close(), "从播放列表移除", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                    Box(Modifier.size(48.dp).pointerInput(i, q.size) {
                        detectDragGestures(
                            onDragStart = { dragFrom = i; dragDy = 0f },
                            onDragEnd = {
                                val to = (i + kotlin.math.round(dragDy / rowPx).toInt()).coerceIn(0, q.size - 1)
                                dragFrom = -1; dragDy = 0f; MusicState.move(i, to)
                            },
                            onDragCancel = { dragFrom = -1; dragDy = 0f },
                        ) { ch, d -> ch.consume(); dragDy += d.y }
                    }, contentAlignment = Alignment.Center) {
                        Icon(KI.menu(), "拖动排序", tint = C.Faint, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    if (confirmClear) AppAlertDialog(onDismissRequest = { confirmClear = false }, containerColor = C.Surface,
        title = { Text("清空播放列表？", color = C.Text) }, text = { Text("会停止播放并移除列表里的 ${q.size} 首歌。", color = C.Sub) },
        confirmButton = { TextButton(onClick = { confirmClear = false; MusicState.stop() }) { Text("清空", color = C.Danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = C.Sub) } })
}

// ============================================================ settings

@Composable
fun MusicSettingsScreen() {
    val ctx = LocalContext.current
    val key = Movies.keyOf()
    val lib = key?.let { remember(it) { Music.lib(it) } }
    var picking by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val src = currentSource()
    Page("音乐文件夹", src?.let { "${if (it.kind == "dav") "WebDAV" else "飞牛"} · ${it.title}" } ?: "") {
        if (lib == null) { StateMessage(KI.music(), "先添加一个来源", ""); return@Page }
        if (!lib.ready) { LibLoading(); return@Page }   // never save folders over a library that is still loading
        if (picking) { FolderPicker(lib.fs, lib.kind, onDismiss = { picking = false }, fmOnly = true) { path, name ->
            picking = false
            // H1: a folder inside an already chosen one is already covered; a parent replaces its chosen subfolders.
            val outer = lib.dirs.firstOrNull { MediaIsolation.isUnder(path, it.path) }
            if (outer != null) toast(ctx, "已包含在「${outer.name}」里")
            else {
                val inner = lib.dirs.filter { MediaIsolation.isUnder(it.path, path) }
                if (inner.isNotEmpty()) toast(ctx, "已合并 ${inner.size} 个子文件夹")
                lib.saveDirs(lib.dirs - inner.toSet() + MusicDir(path, name)); lib.scan(); ScanService.ensure(ctx)
            }
        }; return@Page }
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            Section("音乐文件夹", info = "和照片、影视分开设置。音乐文件夹里的文件不会出现在照片和视频页，影视扫描也会跳过它们。\n\n" +
                "格式：mp3、flac、wav、m4a（AAC / ALAC）、ogg、opus 用内置播放器；ape、wma、wv、aiff、dsf / dff（DSD）内置播放器不支持，播放列表里会自动跳过，可长按歌曲选「用其他播放器打开」。")
            Card {
                if (lib.dirs.isEmpty()) Text("还没有音乐文件夹", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                lib.dirs.forEachIndexed { i, d ->
                    if (i > 0) Div()
                    LibraryDirRow(d.name, d.path.removePrefix("fm:")) { lib.saveDirs(lib.dirs - d) }
                }
                Div()
                NavRow(LI.plus(), "添加文件夹", info = if (lib.kind == "fn") "飞牛请选「文件管理」里的文件夹，相册文件夹读不到音乐。" else null) { picking = true }
            }
            LibraryScanRow(lib.scanning, lib.dirs.isNotEmpty(), "先添加音乐文件夹",
                onScan = { lib.scan(); ScanService.ensure(ctx) }, redoLabel = "重新刮削", redoEnabled = !lib.scanning) {
                if (lib.dirs.isEmpty()) toast(ctx, "先添加音乐文件夹") else { lib.scan(rescrape = true); ScanService.ensure(ctx); toast(ctx, "重新读取所有歌曲的标签") }
            }
            // 用户 2026-10-23：回到影音设置的回链删掉（这页本来就是从那里进来的）；格式说明并进分组标题的 ⓘ
            Section("缓存")
            Card { NavRow(LI.trash(), "清空这个来源的音乐库", "${lib.tracks.size} 首 · 封面和歌词缓存一起删除", danger = true) { confirmClear = true } }
        }
    }
    if (confirmClear && lib != null) ConfirmDialog("清空音乐库？", "歌曲信息、封面和歌词会重新扫描，文件本身不受影响。", "清空", onDismiss = { confirmClear = false }) { confirmClear = false; MusicState.stop(); lib.clearCache() }
}

/** 全面审查 A3：正在播放页顶栏的定时关闭（和影视播放器同样的选项），开着时图标变色并显示剩余分钟。 */
@Composable
private fun SleepButton() {
    var open by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(MusicState.sleepAt) { while (MusicState.sleepAt > 0) { left = (((MusicState.sleepAt - System.currentTimeMillis()) + 59_999) / 60_000).toInt().coerceAtLeast(0); delay(15_000) } }
    val on = MusicState.sleepAt > 0 || MusicState.sleepAfterTrack != null
    Box {
        Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }
            .semantics(mergeDescendants = true) { contentDescription = "定时关闭" + when { MusicState.sleepAt > 0 -> "，剩 $left 分钟"; MusicState.sleepAfterTrack != null -> "，本首播完停止"; else -> "" } }
            .padding(horizontal = Space.s + 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(KI.moon(on), null, tint = if (on) C.Accent else C.Text, modifier = Modifier.size(22.dp))
            if (MusicState.sleepAt > 0) Text(" $left 分", color = C.Accent, style = MaterialTheme.typography.labelMedium.merge(Type.num).copy(color = C.Accent))
            else if (MusicState.sleepAfterTrack != null) Text(" 本首", color = C.Accent, style = MaterialTheme.typography.labelMedium.copy(color = C.Accent))
        }
        AppDropdownMenu(open, onDismissRequest = { open = false }) {
            PlayerHints.sleepChoices.forEach { m -> DropdownMenuItem(text = { Text("$m 分钟后暂停", color = C.Text) }, onClick = { open = false; MusicState.setSleep(m) }) }
            DropdownMenuItem(text = { Text("本首播完后停止", color = C.Text) }, onClick = { open = false; MusicState.sleepAfterThisTrack() })
            if (on) DropdownMenuItem(text = { Text("关闭定时", color = C.Danger) }, onClick = { open = false; MusicState.clearSleep() })
        }
    }
}

/** 全面审查 A8：歌曲排序菜单（和影视的排序菜单同一种样子）。 */
@Composable
private fun MusicSortMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.s)).clickable(onClickLabel = "选择排序方式", role = androidx.compose.ui.semantics.Role.Button) { open = true }
            .semantics(mergeDescendants = true) { contentDescription = "排序：${MusicUi.sorts[MusicUi.sort]}" }.padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(KI.sort(), null, tint = C.Sub, modifier = Modifier.size(16.dp))
            Text(MusicUi.sorts[MusicUi.sort], color = C.Sub, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Space.xs))
        }
        AppDropdownMenu(open, onDismissRequest = { open = false }) {
            MusicUi.sorts.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s, color = if (i == MusicUi.sort) C.Accent else C.Text, fontWeight = if (i == MusicUi.sort) FontWeight.Medium else FontWeight.Normal) },
                    trailingIcon = if (i == MusicUi.sort) { { Icon(KI.check(), "当前", tint = C.Accent, modifier = Modifier.size(18.dp)) } } else null,
                    onClick = { open = false; MusicUi.sort = i; Store.putStr("music.sort", "$i") })
            }
        }
    }
}
