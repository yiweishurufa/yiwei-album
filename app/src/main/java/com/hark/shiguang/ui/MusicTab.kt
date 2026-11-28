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
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.launch
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
        autoScanIfStale("music.last.$key", lib.dirs.isNotEmpty(), lib.scanning) { lib.scan(quiet = true, reason = "进入音乐页（距上次超过 6 小时）"); ScanService.ensure(App.ctx) }
        // 用户 2026-11-02 反馈（WAV 中文 ??）：旧版存下的乱码歌名不等过期，进来就重读这些歌（其余不动）
        if (lib.needsRetag && !lib.scanning && lib.dirs.isNotEmpty()) { lib.needsRetag = false; Diag.log("MUSIC", "garbled tags cached, re-reading them"); lib.scan(quiet = true, reason = "旧版标签重读（一次性）"); ScanService.ensure(App.ctx) }
    }
    val v = lib.version
    val q = MusicUi.query.trim()
    val all = remember(v, lib.tracks.size) { lib.tracks.toList() }
    // H10: filtering, sorting and grouping run on Dispatchers.Default (only the open sub-page's groups); a newer
    // version cancels the older computation, so a scan's ~1/s updates never queue up on the main thread.
    val view by produceState<MusicView?>(null, all, q, MusicUi.sub, MusicUi.sort) {
        val sub = MusicUi.sub; val sort = MusicUi.sort
        value = withContext(Dispatchers.Default) { buildMusicView(all, q, sub, sort, lib::artistPhoto) }
    }
    val shown = view?.shown ?: emptyList()
    val albums = view?.albums ?: emptyList()
    val artists = view?.artists ?: emptyList()
    val folders = view?.folders ?: emptyList()
    val list = rememberMemList("music-$key-${MusicUi.sub}")

    val albumCols = adaptiveCols(2)
    // B7：下拉刷新 = 后台增量扫描（没变的文件夹不重列），有缓存时只显示一行「正在更新」
    PullRefreshBox(enabled = lib.ready && lib.dirs.isNotEmpty() && !lib.scanning, onRefresh = { lib.scan(quiet = true, manual = true, reason = "下拉刷新"); ScanService.ensure(App.ctx) }) {
    // 1.7.33：底部留白和照片页同一套（底栏 + 系统导航条 + 迷你播放条），以前固定 170dp，三键导航 + 迷你播放条时最后一行露不全
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = homeListBottom())) {
        item { MediaHeader("音乐", SearchScope.None, lib.scanning, lib.dirs.isNotEmpty(), "重新扫描音乐") { lib.scan(); ScanService.ensure(App.ctx) } }
        // 1.7.10：读库时显示和歌曲行同形的骨架（以前是居中转圈）
        if (!lib.ready) { item { ListRowSkeleton(9, 48.dp) }; return@LazyColumn }
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
                // 1.7.33：分段里不再带数量（「歌曲 123」在大字号下放不下，空格后面的数字被折到第二行看不见，「歌曲」两个字也偏到左边），
                // 数量挪到下面一行
                Segmented(listOf("歌曲", "专辑", "歌手", "文件夹"), MusicUi.sub) { MusicUi.sub = it; Store.putStr("music.sub", "$it") }
            }
        }
        if (shown.isNotEmpty()) item {
            Row(Modifier.padding(start = Space.page + Space.xs, end = Space.s).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                val n = when (MusicUi.sub) { 1 -> "${albums.size} 张专辑"; 2 -> "${artists.size} 位歌手"; 3 -> "${folders.size} 个文件夹"; else -> "${shown.size} 首" }
                Text(n, color = C.Sub, style = MaterialTheme.typography.labelMedium.merge(Type.num), modifier = Modifier.weight(1f))
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
            2 -> items(artists, key = { "a" + it.key }) { g -> GroupRow(g, round = true) { Nav.push(Route.MusicList("artist", g.title, g.title)) } }
            else -> items(folders, key = { "f" + it.key }) { g -> GroupRow(g) { Nav.push(Route.MusicList("folder", g.key, g.title)) } }
        }
    }
    }
}

data class MusicGroup(val kind: String, val key: String, val title: String, val sub: String, val art: String?, val count: Int)

/** H10: what the 音乐 page shows, computed off the main thread. Groups are built only for the open sub-page. */
class MusicView(val shown: List<Track>, val albums: List<MusicGroup>, val artists: List<MusicGroup>, val folders: List<MusicGroup>)

private fun buildMusicView(all: List<Track>, q: String, sub: Int, sort: Int = 0, artistPhoto: (String) -> String? = { null }): MusicView {
    val col = java.text.Collator.getInstance(java.util.Locale.CHINA)   // own instance: Collator is not thread-safe
    // 1.7.33：先按整个曲库算规范歌手名（「01张杰」…→「张杰」、「08素颜」对调），下面的显示 / 分组都用它
    runCatching { MusicNames.update(all) }
    fun <T> sortByKey(l: List<T>, s: (T) -> String): List<T> = l.map { col.getCollationKey(s(it)) to it }.sortedBy { it.first }.map { it.second }
    /** 1.7.33：名字排序：汉字按拼音、字母按字母；开头的符号不算（「.K」排在 K）；只有数字 / 符号的排后面；未知歌手 / 未知专辑最后。 */
    fun <T> sortByName(l: List<T>, s: (T) -> String): List<T> =
        l.map { Triple(MusicArtist.sortRank(s(it)), col.getCollationKey(MusicArtist.sortText(s(it))), it) }
            .sortedWith(compareBy<Triple<Int, java.text.CollationKey, T>> { it.first }.thenBy { it.second }).map { it.third }
    val base = if (q.isEmpty()) all else all.filter { t -> listOf(t.displayTitle, t.title, t.artist, t.displayArtist, t.album, t.albumArtist, t.name).any { it.contains(q, true) } }
    val byTitle = sortByKey(base) { it.displayTitle }
    val shown = when (sort) {
        1 -> byTitle.sortedByDescending { it.mtime }
        2 -> sortByName(byTitle) { it.displayArtist }
        3 -> byTitle.sortedByDescending { it.durationMs }
        else -> byTitle
    }
    val albums = if (sub != 1) emptyList() else sortByName(shown.groupBy { it.albumKey }.map { (k, l) ->
        // 同一张专辑里各曲的写法可能不同（「(2002)时光·漫步」「时光·漫步」）：显示出现最多的那个
        val name = l.groupingBy { it.displayAlbum }.eachCount().maxByOrNull { it.value }!!.key
        MusicGroup("album", k, name, l.first().displayAlbumArtist, l.firstNotNullOfOrNull { it.artPath }, l.size)
    }) { it.title }
    val artists = if (sub != 2) emptyList() else {
        // 1.7.33：按规范键合并（大小写 / 空格 / 装饰符不同算同一位），头像优先用歌手照片，没有再用他某首歌的封面
        val m = LinkedHashMap<String, Pair<String, MutableList<Track>>>()
        shown.forEach { t -> t.artists.forEach { a -> m.getOrPut(MusicArtist.key(a)) { a to ArrayList() }.second.add(t) } }
        sortByName(m.map { (k, p) -> MusicGroup("artist", k, p.first, "", artistPhoto(p.first) ?: p.second.firstNotNullOfOrNull { it.artPath }, p.second.size) }) { it.title }
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
    // 1.7.33：以前没有时显示「还没有」（半句话），改成和另一张卡一样的「0 首」
    val countText = fmtCount(count)
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
    Row(modifier.height(46.dp).skeuoPressable(ix, RoundedCornerShape(Radius.m), if (primary) C.Accent else C.Surface)
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
internal fun badge(t: Track): String? = when (t.ext) {
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
    var manual by remember { mutableStateOf(false) }
    if (manual) MusicManualSearch(t, sourceKey) { manual = false }
    Box {
        // 1.6.6：正在播放的这首 = 封面上压一层三根跳动小柱（只在真在播时动）+ 标题强调色；时长等宽数字次要色
        val sub = listOf(t.displayArtist, t.cleanAlbum).filter { it.isNotBlank() }.joinToString(" · ")
        Row(Modifier.fillMaxWidth().enterRise(index).combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "更多操作")
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(t.displayTitle, sub.takeIf { it.isNotEmpty() }, if (fav) "已喜欢" else null, if (playing) "正在播放" else null,
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
                Text(t.displayTitle, color = if (playing) C.Accent else C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, fontWeight = if (playing) FontWeight.Medium else FontWeight.Normal,
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
            // 1.7.32：封面 / 歌词 / 歌名不对时，重新匹配（按现在的规则重读重查）或手动搜索挑一个
            DropdownMenuItem(text = { Text("重新匹配封面和歌词", color = C.Text) }, onClick = {
                menu = false
                toast(ctx, "正在重新匹配…")
                Ops.launch {
                    runCatching { lib.rematch(t) }
                        .onSuccess { n -> toast(ctx, if (n.artPath != null) "已重新匹配" else "已重新匹配，没有找到可信的封面") }
                        .onFailure { failToast(ctx, it, "重新匹配失败") }
                }
            })
            DropdownMenuItem(text = { Text("手动搜索封面和歌词", color = C.Text) }, onClick = { menu = false; manual = true })
            if (t.cleanAlbum.isNotBlank()) DropdownMenuItem(text = { Text("查看专辑", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("album", t.albumKey, t.displayAlbum)) })
            DropdownMenuItem(text = { Text("查看歌手", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("artist", t.artists.first(), t.artists.first())) })
            DropdownMenuItem(text = { Column { Text("文件信息", color = C.Text); if (t.displayTitle != t.title && t.title.isNotBlank()) Text("原标题：${t.title}", color = C.Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${t.ext.uppercase()} · ${t.codec} · ${"%.1f".format(t.size / 1048576.0)} MB", color = C.Sub, fontSize = 11.sp)
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
        else if (g.kind == "artist" && g.art == null) ArtistAvatar(g.title, 48.dp)
        else MusicArt(g.art, Modifier.size(48.dp), corner = if (round) 24.dp else Radius.s)
        Column(Modifier.weight(1f).padding(start = Space.m)) {
            Text(g.title, color = C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(KI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

/**
 * 1.7.33：没有照片的歌手统一占位：圆形中性底 + 名字的第一个字（开头的符号跳过，「.K」显示 K）；未知歌手用人像图标。
 * 以前是和歌曲一样的音符方块，歌手页里有照片的、没照片的混在一起很乱。只是装饰，读屏读整行（行上已合并语义）。
 */
@Composable
private fun ArtistAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
        val ch = MusicArtist.sortText(name).takeIf { name != MusicArtist.UNKNOWN }?.let { t -> t.codePointAt(0).let { String(Character.toChars(it)) } }?.uppercase()
        if (ch.isNullOrBlank()) Icon(KI.person(), null, tint = C.Faint, modifier = Modifier.size(size * 0.46f))
        else Text(ch, color = C.Sub, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            // 1.7.33：按规范键比（「01张杰」「张杰」「张 杰」都算张杰）
            "artist" -> MusicArtist.key(r.key).let { k -> lib.tracks.filter { t -> t.artists.any { MusicArtist.key(it) == k } } }.sortedWith(Comparator<Track> { a, b -> collator.compare(a.album, b.album) }.thenBy { it.discNo }.thenBy { it.trackNo })
            else -> lib.tracks.filter { it.folder == r.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.name }))
        }
    }
    // 1.7.33：歌手页头像优先用歌手照片
    val art = (if (r.kind == "artist") lib.artistPhoto(r.title) else null) ?: tracks.firstNotNullOfOrNull { it.artPath }
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
                    else if (r.kind == "artist" && art == null) ArtistAvatar(r.title, 116.dp)
                    else MusicArt(art, Modifier.size(116.dp), corner = if (r.kind == "artist") 58.dp else Radius.l, lifted = true)
                    Column(Modifier.padding(start = Space.l).weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        val sub = when (r.kind) {
                            "album" -> listOfNotNull(first?.displayAlbumArtist, first?.year?.takeIf { it > 0 }?.toString()).joinToString(" · ")
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
            // 1.7.25：没有歌时不显示「全部播放 / 随机播放」（以前点了只弹「还没有歌曲」）
            if (tracks.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PillButton(KI.playSolid(), "全部播放", Modifier.weight(1f), primary = true) { MusicState.play(key, tracks, 0, shuffled = false) }
                    PillButton(KI.shuffle(), "随机播放", Modifier.weight(1f)) { MusicState.play(key, tracks, -1, shuffled = true) }
                }
            }
            // 1.7.25 空状态：标题 + 一句「怎么会出现在这里」（以前只有一行灰色大字），和其它页的空状态同一套
            if (tracks.isEmpty() && (r.kind == "fav" || r.kind == "recent")) item {
                Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                    if (r.kind == "fav") StateMessage(KI.heart(), "还没有喜欢的歌", "在正在播放页点 ♡，或在歌曲右边的「⋯」里选「加入我喜欢」，歌就会收进这里。")
                    else StateMessage(KI.history(), "还没有播放记录", "播放过的歌会按时间先后出现在这里，最近听的在最上面。")
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, key, showNo = r.kind == "album", index = i) { MusicState.play(key, tracks, i) } }
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

/**
 * 1.7.32 歌曲菜单「手动搜索封面和歌词」：在网易云音乐、QQ 音乐里搜（关键词默认是规范歌名 + 歌手，可改），
 * 列出歌名 / 歌手 / 专辑 / 时长，选一个就用它的封面和歌词（内嵌和同目录的歌词仍然优先）。
 */
@Composable
internal fun MusicManualSearch(t: Track, sourceKey: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val lib = remember(sourceKey) { Music.lib(sourceKey) }
    var q by remember { mutableStateOf(listOf(t.cleanTitle, t.displayArtist.takeIf { it != "未知歌手" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember { mutableStateOf<List<MusicMatch.Cand>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (q.isBlank() || busy) return
        busy = true; err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MusicOnline.manualSearch(q.trim(), t) } }
            busy = false
            r.onSuccess { results = it }.onFailure { err = "网络不通，稍后再试"; results = null }
        }
    }
    LaunchedEffect(Unit) { search() }
    AppAlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text("手动搜索", color = C.Text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(46.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "搜索关键词" })
                    IconButton(onClick = { search() }) { Icon(KI.search(), "搜索", tint = C.Sub, modifier = Modifier.size(20.dp)) }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).padding(top = Space.s), contentAlignment = Alignment.Center) {
                    val r = results
                    when {
                        busy || applying -> CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        err != null -> Text(err!!, color = C.Sub, style = MaterialTheme.typography.bodyMedium)
                        r != null && r.isEmpty() -> Text("没有找到，换个关键词试试", color = C.Sub, style = MaterialTheme.typography.bodyMedium)
                        r != null -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(r, key = { "${it.source}|${it.id}" }) { c ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s)).clickable(onClickLabel = "用这个结果") {
                                    applying = true
                                    scope.launch {
                                        runCatching { lib.applyPick(t, c) }
                                            .onSuccess { toast(ctx, "已换成所选的封面和歌词"); onDismiss() }
                                            .onFailure { applying = false; failToast(ctx, it, "下载失败") }
                                    }
                                }.padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.s)).background(C.Surface2), contentAlignment = Alignment.Center) {
                                        if (c.cover != null) NetImage(c.cover, Modifier.fillMaxSize()) else Icon(KI.music(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
                                    }
                                    Column(Modifier.weight(1f).padding(start = Space.m)) {
                                        Text(c.title, color = C.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(c.artists.joinToString("/"), c.album).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Space.s)) {
                                        if (c.durationMs > 0) Text(Music.fmt(c.durationMs), color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num))
                                        Text(if (c.source == "qq") "QQ 音乐" else "网易云", color = C.Faint, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if (t.durationMs > 0) Text("这首时长 ${Music.fmt(t.durationMs)}，时长接近的更可能是同一个版本", color = C.Faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = Space.xs))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = C.Sub) } })
}
