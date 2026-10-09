package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================ 详情

@Composable
fun MovieDetailScreen(r: Route.MovieDetail) {
    val lib = remember(r.sourceKey) { Movies.lib(r.sourceKey) }
    val v = lib.version
    val m = remember(v, lib.items.size) { lib.item(r.itemId) }
    if (m == null) { Page("影视") { StateMessage(LI.film(), "找不到这部作品", "可能已从影视目录移走，重新扫描试试") }; return }
    var season by remember(m.id) { mutableIntStateOf(m.seasons.keys.minOrNull()?.takeIf { it > 0 } ?: m.seasons.keys.minOrNull() ?: 1) }
    var versions by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }
    var fullPlot by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // what 播放 / 继续播放 starts
    val resume: Pair<String, Progress?>? = remember(v, m) {
        if (m.kind == "movie") m.files.firstOrNull()?.let { f -> m.files.mapNotNull { x -> lib.progressOf(x.path)?.takeIf { !it.watched }?.let { x.path to it } }.maxByOrNull { it.second.at } ?: (f.path to null) }
        else {
            val eps = m.episodes
            val last = eps.mapNotNull { e -> lib.progressOf(e.path)?.let { e to it } }.maxByOrNull { it.second.at }
            when {
                last == null -> eps.firstOrNull()?.let { it.path to null }
                last.second.watched -> eps.getOrNull(eps.indexOf(last.first) + 1)?.let { it.path to null } ?: (last.first.path to last.second)
                else -> last.first.path to last.second
            }
        }
    }
    val resumeEp = m.episodes.firstOrNull { it.path == resume?.first }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    val bd = m.backdrop ?: m.poster
                    if (bd != null) NetImage(posterModel(bd), Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(C.Surface))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 1f to C.Bg)))
                }
            }
            item {
                Row(Modifier.padding(horizontal = 18.dp).offset(y = (-96).dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(108.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
                        if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
                        else Icon(if (m.kind == "tv") LI.tv() else LI.film(), null, tint = C.Faint, modifier = Modifier.align(Alignment.Center).size(30.dp))
                    }
                    Column(Modifier.padding(start = 14.dp, bottom = 4.dp).weight(1f)) {
                        Text(m.title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (m.originalTitle.isNotEmpty() && m.originalTitle != m.title) Text(m.originalTitle, color = C.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        Text(listOfNotNull(
                            m.year?.toString(),
                            m.rating?.let { "%.1f 分".format(it) },
                            if (m.kind == "tv") "${m.seasons.size} 季 ${m.episodes.size} 集" else m.runtime.takeIf { it > 0 }?.let { "${it} 分钟" },
                        ).joinToString("  ·  "), color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        if (m.genres.isNotEmpty()) Text(m.genres.take(3).joinToString(" / "), color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-80).dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            resume?.second != null -> "继续播放" + (resumeEp?.let { " 第${it.episode}集" } ?: "")
                            resumeEp != null && m.episodes.indexOf(resumeEp) > 0 -> "播放 第${resumeEp.season}季 第${resumeEp.episode}集"
                            else -> "播放"
                        }
                        Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Accent).clickable(enabled = resume != null) {
                            resume?.let { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, it.first)) }
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(LI.play(true), null, tint = C.OnAccent, modifier = Modifier.size(18.dp))
                            Text(label, color = C.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                        }
                        if (m.kind == "movie" && m.files.size > 1) GhostButton("版本 ${m.files.size}") { versions = true }
                        GhostButton("重新识别") { fixing = true }
                        GhostButton(if (CastState.device != null) "投屏中" else "投屏") { casting = true }
                    }
                    // 1.0.9: quality badges of what 播放 starts (real track info once played, else from the file name)
                    val bf = (m.files.firstOrNull { it.path == resume?.first } ?: resumeEp?.file ?: m.files.firstOrNull())
                    if (bf != null) {
                        val badges = remember(bf.path, v) { MediaBadges.of(bf.path, bf.name) }
                        BadgeRow(badges, Modifier.padding(top = 12.dp))
                        DeviceCaps.hint(ctx, badges)?.let { Text(it, color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                    resume?.second?.takeIf { !it.watched }?.let { p ->
                        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Surface2)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.ratio).background(C.Accent))
                        }
                    }
                    // 1.0.1 #6: low-confidence automatic match → ask instead of guessing
                    if (m.pending && m.guess != null) PendingCard(lib, m, m.guess) { fixing = true }
                    if (!m.overview.isNullOrBlank()) Text(m.overview, color = C.Text.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 22.sp,
                        maxLines = if (fullPlot) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 18.dp).clickable { fullPlot = !fullPlot })
                    else if (!m.matched && !m.pending) Text(if (Tmdb.configured) "还没匹配到资料。点「重新识别」手动搜索。" else "在 设置 → 影视 填上 TMDB 密钥，就能自动配上海报和简介。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 18.dp))
                }
            }
            if (m.cast.isNotEmpty()) {
                item { Text("演员", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, bottom = 10.dp).offset(y = (-66).dp)) }
                item {
                    LazyRow(Modifier.offset(y = (-66).dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(m.cast) { c ->
                            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(64.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
                                    if (c.img != null) NetImage(posterModel(c.img), Modifier.fillMaxSize()) else Text(c.name.take(1), color = C.Sub, fontSize = 20.sp)
                                }
                                Text(c.name, color = C.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                if (c.role.isNotEmpty()) Text(c.role, color = C.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (m.kind == "tv") {
                item {
                    Column(Modifier.offset(y = (-50).dp)) {
                        if (m.seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            items(m.seasons.keys.sorted()) { s ->
                                val on = s == season
                                Text(if (s == 0) "特别篇" else "第 $s 季", color = if (on) C.OnAccent else C.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) C.Accent else C.Surface).clickable { season = s }.padding(horizontal = 14.dp, vertical = 7.dp))
                            }
                        } else Text(if (season == 0) "特别篇" else "第 $season 季", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
                    }
                }
                items(m.seasons[season].orEmpty(), key = { it.path }) { e ->
                    Box(Modifier.offset(y = (-50).dp)) { EpisodeRow(lib, m, e) }
                }
            } else if (m.files.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 18.dp).offset(y = (-50).dp)) {
                        Text("文件", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                        m.files.forEach { f ->
                            Text(f.name, color = C.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
                            BadgeRow(MediaBadges.of(f.path, f.name), Modifier.padding(bottom = 3.dp))
                            Text(listOfNotNull(fmtBytes(f.size).takeIf { f.size > 0 }, if (f.subs.isNotEmpty()) "${f.subs.size} 个外挂字幕" else null).joinToString(" · "), color = C.Faint, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Box(Modifier.statusBarsPadding().padding(10.dp)) { RoundIcon(LI.back(true), bg = Color(0x55000000), tint = Color.White) { Nav.pop() } }
    }

    if (versions) AlertDialog(onDismissRequest = { versions = false }, containerColor = C.Surface, title = { Text("选择版本", color = C.Text) }, text = {
        Column {
            m.files.forEach { f ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { versions = false; Nav.push(Route.MoviePlay(lib.sourceKey, m.id, f.path)) }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                    Text(versionLabel(f.name), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    BadgeRow(MediaBadges.of(f.path, f.name), Modifier.padding(vertical = 3.dp))
                    Text(f.name + "  ·  " + fmtBytes(f.size), color = C.Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { versions = false }) { Text("取消", color = C.Sub) } })

    if (fixing) FixMatchDialog(lib, m) { fixing = false }
    if (casting) {
        val path = resume?.first
        val f = m.files.firstOrNull { it.path == path } ?: m.episodes.firstOrNull { it.path == path }?.file
        val media = f?.let { mf ->
            CastMedia(if (m.kind == "tv") m.title + (resumeEp?.let { " 第${it.episode}集" } ?: "") else m.title, mf.name, lib.progressOf(mf.path)?.takeIf { !it.watched }?.pos ?: 0L) {
                lib.fs.playUrl(mf)?.let { u -> u to lib.fs.headers(u) }
            }
        }
        CastDialog(media) { casting = false }
    }
}

private fun versionLabel(name: String): String {
    val n = name.lowercase()
    val res = listOf("2160p" to "4K", "4k" to "4K", "1080p" to "1080p", "720p" to "720p").firstOrNull { n.contains(it.first) }?.second
    val hdr = if (Regex("hdr|dovi|\\bdv\\b").containsMatchIn(n)) "HDR" else null
    val codec = when { n.contains("265") || n.contains("hevc") -> "HEVC"; n.contains("264") || n.contains("avc") -> "H.264"; else -> null }
    return listOfNotNull(res, hdr, codec, NameParser.ext(name).uppercase()).joinToString(" · ")
}

@Composable
private fun GhostButton(t: String, onClick: () -> Unit) {
    Text(t, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).clickable(onClick = onClick).padding(horizontal = 16.dp).wrapContentHeight(Alignment.CenterVertically))
}

@Composable
private fun EpisodeRow(lib: MovieLib, m: MovieItem, e: Episode) {
    val p = lib.progressOf(e.path)
    Row(Modifier.fillMaxWidth().clickable { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, e.path)) }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.Surface2)) {
            val img = e.still ?: m.backdrop
            if (img != null) NetImage(posterModel(img), Modifier.fillMaxSize())
            if (p != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.ratio).height(3.dp).background(C.Accent))
            if (p?.watched == true) Text("看完", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("第 ${e.episode} 集", color = C.Sub, fontSize = 12.sp)
            Text(e.title ?: NameParser.stem(e.file?.name ?: e.path.substringAfterLast('/')), color = C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ============================================================ 待确认 / 重新识别

@Composable
private fun PendingCard(lib: MovieLib, m: MovieItem, g: Tmdb.Hit, onOther: () -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(5.dp)).background(C.Surface2)) { posterModel(Tmdb.image(g.poster, "w92"))?.let { NetImage(it, Modifier.fillMaxSize()) } }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("待确认 · 自动识别把握不大", color = C.Sub, fontSize = 12.sp)
            Text("可能是「${g.title}」" + (g.year?.let { "（$it）" } ?: ""), color = C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = {
            busy = true
            Ops.launch { runCatching { lib.applyMatch(m, g) }.onSuccess { toast(ctx, "已确认「${g.title}」") }.onFailure { toast(ctx, it.message ?: "失败") }; busy = false }
        }, enabled = !busy) { Text(if (busy) "…" else "是它", color = C.Accent) }
        TextButton(onClick = onOther) { Text("不是", color = C.Sub) }
    }
}

// ============================================================ 修正匹配

@Composable
private fun FixMatchDialog(lib: MovieLib, m: MovieItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(m.query.ifEmpty { m.title }) }
    var year by remember { mutableStateOf(m.year?.toString() ?: "") }
    var kind by remember { mutableStateOf(m.kind) }
    var hits by remember { mutableStateOf<List<Tmdb.Hit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun search() = scope.launch {
        busy = true; err = null
        runCatching { withContext(Dispatchers.IO) { Tmdb.search(kind, q.trim(), year.toIntOrNull()) } }
            .onSuccess { hits = it; if (it.isEmpty()) err = "没有结果，换个名字或去掉年份" }.onFailure { err = it.message }
        busy = false
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("重新识别 / 手动选择", color = C.Text) }, text = {
        Column {
            if (!Tmdb.configured) { Text("先在 设置 → 影视 填 TMDB 密钥。", color = C.Sub, fontSize = 14.sp); return@Column }
            Segmented(listOf("电影", "剧集"), if (kind == "movie") 0 else 1) { kind = if (it == 0) "movie" else "tv"; hits = null }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("名称") }, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent))
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("年份") }, modifier = Modifier.padding(start = 8.dp).width(84.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { search() }, enabled = !busy && q.isNotBlank()) { Text(if (busy) "搜索中…" else "搜索", color = C.Accent) }
                TextButton(onClick = {
                    onDismiss()
                    Ops.launch {
                        runCatching { lib.rematch(m) }.onSuccess { n -> toast(ctx, when { n.matched -> "已识别为「${n.title}」"; n.pending -> "把握不大，已标为待确认"; else -> "没有识别出来，试试手动搜索" }) }
                            .onFailure { toast(ctx, it.message ?: "识别失败") }
                    }
                }, enabled = !busy) { Text("自动重新识别", color = C.Sub) }
            }
            Text("选中的条目会记住，重新扫描或清空影视库后也不变。", color = C.Faint, fontSize = 11.sp)
            err?.let { Text(it, color = C.Danger, fontSize = 12.sp) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(hits.orEmpty(), key = { it.kind + it.id }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        onDismiss()
                        Ops.launch { runCatching { lib.applyMatch(m, h) }.onSuccess { toast(ctx, "已匹配「${h.title}」") }.onFailure { toast(ctx, it.message ?: "匹配失败") } }
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(5.dp)).background(C.Surface2)) { posterModel(Tmdb.image(h.poster, "w92"))?.let { NetImage(it, Modifier.fillMaxSize()) } }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(h.title, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(h.year?.toString(), if (h.kind == "tv") "剧集" else "电影", h.original.takeIf { it != h.title }).joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.overview.isNotBlank()) Text(h.overview, color = C.Faint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", color = C.Sub) } })
    LaunchedEffect(Unit) { if (Tmdb.configured && q.isNotBlank()) search() }
}

// ============================================================ 媒体库设置（每个来源各一份）

@Composable
fun MovieLibrarySettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = Movies.keyOf()
    val lib = key?.let { remember(it) { Movies.lib(it) } }
    var picking by remember { mutableStateOf(false) }
    var tmdbKey by remember { mutableStateOf(Tmdb.key) }
    var api by remember { mutableStateOf(Tmdb.apiBase) }
    var img by remember { mutableStateOf(Tmdb.imgBase) }
    var confirmClear by remember { mutableStateOf(false) }
    var dmId by remember { mutableStateOf(DanmakuPrefs.appId) }
    var dmSecret by remember { mutableStateOf(DanmakuPrefs.appSecret) }
    var dmCell by remember { mutableStateOf(DanmakuPrefs.hashOnCell) }
    val src = currentSource()
    Page("影视媒体库", src?.let { "${if (it.kind == "dav") "WebDAV" else "飞牛"} · ${it.title}" } ?: "") {
        if (lib == null) { StateMessage(LI.film(), "先添加一个来源", ""); return@Page }
        if (picking) { FolderPicker(lib.fs, lib.kind, onDismiss = { picking = false }) { path, name ->
            picking = false
            if (lib.dirs.none { it.path == path }) lib.saveDirs(lib.dirs + MovieDir(path, name, "auto"))
        }; return@Page }
        Column(Modifier.fillMaxSize().verticalScrollCompat().navigationBarsPadding().padding(bottom = 40.dp)) {
            Section("影视文件夹")
            Card {
                if (lib.dirs.isEmpty()) Text("和相册的扫描目录分开设置。可以加多个，每个标成电影、剧集或自动识别。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                lib.dirs.forEachIndexed { i, d ->
                    if (i > 0) Div()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(LI.folder(), null, tint = C.Text, modifier = Modifier.size(20.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(d.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.path, color = C.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("移除", color = C.Danger, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { lib.saveDirs(lib.dirs - d) }.padding(6.dp))
                        }
                        Row(Modifier.padding(start = 32.dp, top = 8.dp)) {
                            Segmented(listOf("自动", "电影", "剧集"), listOf("auto", "movie", "tv").indexOf(d.kind).coerceAtLeast(0)) { k ->
                                lib.saveDirs(lib.dirs.map { if (it.path == d.path) it.copy(kind = listOf("auto", "movie", "tv")[k]) else it })
                            }
                        }
                    }
                }
                Div()
                NavRow(LI.plus(), "添加文件夹", if (lib.kind == "fn") "飞牛只能选相册里已添加的文件夹" else "浏览这个 WebDAV") { picking = true }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(if (lib.scanning) "扫描中…" else "立即扫描", Modifier.weight(1f), loading = lib.scanning) {
                    if (lib.dirs.isEmpty()) toast(ctx, "先添加影视文件夹") else { lib.scan(); ScanService.ensure(ctx); toast(ctx, "开始扫描，离开页面也会继续") }
                }
                GhostButton("重新匹配未匹配") { lib.scan(rescrapeUnmatched = true); ScanService.ensure(ctx) }
            }
            if (lib.kind == "fn") Text("飞牛的相册接口只返回视频本身，读不到同目录的 NFO、海报和字幕；这些会用 TMDB 补上。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))

            Section("TMDB 刮削（所有来源共用）")
            Card {
                Spacer(Modifier.height(8.dp))
                InputRow("API 密钥", tmdbKey, "v3 API Key 或 v4 读取令牌") { tmdbKey = it }
                InputRow("接口代理（可选）", api, "直连不通时优先用它，再用内置代理") { api = it }
                InputRow("图片代理（可选）", img, "直连不通时优先用它") { img = it }
                TestButton("保存并测试", Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), resetKey = tmdbKey + "|" + api + "|" + img, height = 44.dp) {
                    Tmdb.key = tmdbKey; Tmdb.apiBase = api; Tmdb.imgBase = img
                    val t0 = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { Tmdb.test() } + " · ${System.currentTimeMillis() - t0} ms"
                }
                // 1.0.1 #1: which way TMDB is reached right now (direct overseas, an automatically verified proxy in China)
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前网络", color = C.Sub, fontSize = 12.sp)
                        Text(if (TmdbNet.checking) "检测中…" else TmdbNet.status.ifEmpty { TmdbNet.describe() }, color = C.Text, fontSize = 14.sp)
                    }
                    TextButton(onClick = { TmdbNet.recheckAsync() }, enabled = !TmdbNet.checking) { Text("重新检测", color = C.Accent) }
                }
                Text("在 themoviedb.org 注册后，于 设置 → API 免费申请。填好后会自动检测：能直连就直连，连不上自动换国内可用的代理（每天和换网络时重新检测）。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }

            // 1.0.1 #11
            Section("弹幕（弹弹play，所有来源共用）")
            Card {
                Spacer(Modifier.height(8.dp))
                InputRow("AppId", dmId, "在弹弹play开放平台申请") { dmId = it }
                InputRow("AppSecret", dmSecret, "只保存在本机") { dmSecret = it }
                TestButton("保存并测试", Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), resetKey = dmId + "|" + dmSecret, height = 44.dp) {
                    DanmakuPrefs.appId = dmId; DanmakuPrefs.appSecret = dmSecret
                    if (!DanmakuPrefs.configured) error("先填 AppId 和 AppSecret")
                    withContext(Dispatchers.IO) { DanDan.test() }
                }
                ToggleRow(LI.videos(), "移动网络也精确匹配", "精确匹配要读取视频开头 16 MB 计算指纹；关闭时移动网络下只按文件名匹配", dmCell) { dmCell = it; DanmakuPrefs.hashOnCell = it }
                Text(if (DanmakuPrefs.configured) "播放时自动按文件匹配弹幕（弹弹play 国内可直连），播放器顶部「弹幕」可开关、手动匹配和调整样式。兼容模式（VLC）不显示弹幕。"
                    else DanmakuPrefs.HINT + "。不填也不影响播放。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp))
            }

            Section("缓存")
            Card {
                NavRow(LI.trash(), "清空这个来源的影视库", "${lib.items.size} 部作品 · 播放进度保留", danger = true) { confirmClear = true }
            }
        }
    }
    if (confirmClear && lib != null) ConfirmDialog("清空影视库？", "海报和资料会重新扫描、重新匹配，播放进度保留。", "清空", onDismiss = { confirmClear = false }) { confirmClear = false; lib.clearCache() }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

/** Browses the current source's folders (PROPFIND on WebDAV; on 飞牛 any NAS folder via 文件管理, plus 相册 folders as fallback). */
@Composable
internal fun FolderPicker(fs: MovieFs, kind: String, onDismiss: () -> Unit, fmOnly: Boolean = false, onPick: (String, String) -> Unit) {
    // 1.0.3: shared with the 音乐 settings ([fmOnly]: on 飞牛 only 文件管理 folders, 相册 folders list no audio)
    var stack by remember { mutableStateOf(listOf(if (kind == "dav") "/" to "根目录" else "" to "飞牛")) }
    val (path, name) = stack.last()
    var dirs by remember { mutableStateOf<List<FsEntry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        dirs = null; err = null
        runCatching { withContext(Dispatchers.IO) { fs.list(path).filter { it.isDir && !it.name.startsWith(".") && !(fmOnly && path.isEmpty() && !it.path.startsWith("fm:")) } } }
            .onSuccess { dirs = it.sortedBy { d -> d.name.lowercase() } }.onFailure { err = it.message; dirs = emptyList() }
    }
    androidx.activity.compose.BackHandler { if (stack.size > 1) stack = stack.dropLast(1) else onDismiss() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stack.joinToString(" / ") { it.second }, color = C.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) }
        }
        val d = dirs
        Box(Modifier.weight(1f)) {
            when {
                d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(26.dp)) }
                d.isEmpty() -> StateMessage(LI.folder(), if (err != null) "打不开这个目录" else "没有子文件夹", err ?: "可以直接选用当前文件夹")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(d, key = { it.path }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { stack = stack + (e.path to e.name) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(LI.folder(), null, tint = C.Text, modifier = Modifier.size(20.dp))
                            Text(e.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        val canPick = !(kind == "fn" && path.isEmpty())
        Box(Modifier.padding(16.dp)) {
            GradientButton(if (canPick) "选用「$name」" else "进入一个文件夹再选", Modifier.fillMaxWidth()) { if (canPick) onPick(path, name) }
        }
    }
}
