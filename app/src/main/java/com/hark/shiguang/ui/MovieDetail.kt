package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
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
    if (m == null && !lib.ready) { Page("影视") { LibLoading() }; return }
    if (m == null) { Page("影视") { StateMessage(LI.film(), "找不到这部作品", "可能已从影视目录移走，重新扫描试试") }; return }
    var season by remember(m.id) { mutableIntStateOf(m.seasons.keys.minOrNull()?.takeIf { it > 0 } ?: m.seasons.keys.minOrNull() ?: 1) }
    var versions by remember { mutableStateOf(false) }
    // 优化 14：合并到这张海报的其他片库条目（同 TMDB id，或同名同年）的文件也算版本；(条目 id, 文件)
    val allVersions = remember(v, m) { (listOf(m) + MovieMerge.siblings(lib.items, m)).flatMap { o -> o.files.map { o to it } } }
    var fixing by remember { mutableStateOf(false) }
    var fullPlot by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // what 播放 / 继续播放 starts
    val resume: Pair<String, Progress?>? = remember(v, m) {
        if (m.kind == "movie") m.files.firstOrNull()?.let { f -> m.files.mapNotNull { x -> lib.progressOf(x.path)?.takeIf { !it.watched }?.let { x.path to it } }.maxByOrNull { it.second.at } ?: (f.path to null) }
        // 1.1.0: the episode last played and where (shows.json), falling back to the old most-recent-progress rule
        else lib.resumeShow(m)?.let { it.path to it.progress }
    }
    val resumeEp = m.episodes.firstOrNull { it.path == resume?.first }

    // 第 4 批：平板/折叠屏——详情内容最大宽度 720dp 居中，宽屏不再通栏拉伸（头图随内容一起居中）。
    // 1.6.6 阶段 4：头图视差 + 渐隐到页面底；海报与标题压在头图下沿（不再靠负 offset 叠，下面不留空白）；
    // 主按钮「播放」独占一行，其余操作是一排图标 + 文字的次要按钮；滚过头图后顶部出现带片名的吸顶栏。
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headerPx = with(density) { DetailSize.backdrop.toPx() }
    val pinned by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > headerPx * 0.72f } }
    // 1.7.0：环境光晕取海报颜色
    AmbientFrom(posterModel(m.poster ?: m.backdrop))
    Box(Modifier.fillMaxSize().appBackground(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxHeight(), state = list, contentPadding = PaddingValues(bottom = 60.dp)) {
            item(key = "head") {
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(DetailSize.backdrop).graphicsLayer {
                        // 视差：头图以一半的速度上移；「移除动画」时不做
                        if (!Motion.reduced && list.firstVisibleItemIndex == 0) translationY = list.firstVisibleItemScrollOffset * 0.5f
                        clip = false
                    }) {
                        val bd = m.backdrop ?: m.poster
                        if (bd != null) NetImage(posterModel(bd), Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(C.Surface))
                        // 上沿压暗给状态栏与返回钮，下沿渐隐到页面底色（三段：顶部 40% 黑 → 透明 → 页面底）
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.3f to Color.Transparent, 0.62f to C.Bg.copy(alpha = 0.35f), 1f to C.Bg)))
                    }
                    Row(Modifier.padding(start = Space.page, end = Space.page, top = DetailSize.backdrop - DetailSize.posterOverlap), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.width(DetailSize.poster).aspectRatio(2f / 3f).skeuoPhoto(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
                            if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
                            else Icon(if (m.kind == "tv") KI.tv() else KI.film(), null, tint = C.Faint, modifier = Modifier.align(Alignment.Center).size(30.dp))
                        }
                        Column(Modifier.padding(start = Space.l, bottom = Space.xs).weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(m.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() })
                            if (m.originalTitle.isNotEmpty() && m.originalTitle != m.title) Text(m.originalTitle, color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
                            Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                                m.rating?.let { r ->
                                    Icon(KI.star(true), null, tint = C.Gold, modifier = Modifier.size(15.dp))
                                    Text("%.1f".format(r), color = C.Text, style = MaterialTheme.typography.labelLarge.merge(Type.num), modifier = Modifier.padding(start = Space.xxs, end = Space.s))
                                }
                                Text(listOfNotNull(
                                    m.year?.toString(),
                                    if (m.kind == "tv") "${m.seasons.size} 季 ${m.episodes.size} 集" else m.runtime.takeIf { it > 0 }?.let { "${it} 分钟" },
                                ).joinToString("  ·  "), color = C.Sub, style = MaterialTheme.typography.bodyMedium.merge(Type.num), maxLines = 1)
                            }
                            if (m.genres.isNotEmpty()) Text(m.genres.take(3).joinToString(" / "), color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = Space.page).padding(top = Space.l + Space.xs)) {
                    val label = when {
                        resume?.second != null -> "继续播放" + (resumeEp?.let { " 第${it.episode}集" } ?: "")
                        resumeEp != null && m.episodes.indexOf(resumeEp) > 0 -> "播放 第${resumeEp.season}季 第${resumeEp.episode}集"
                        else -> "播放"
                    }
                    val playIx = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().height(50.dp).skeuoPressable(playIx, RoundedCornerShape(Radius.m), if (resume != null) C.Accent else C.Surface2)
                        .clickable(playIx, null, enabled = resume != null, role = androidx.compose.ui.semantics.Role.Button) {
                            resume?.let { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, it.first)) }
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(KI.playSolid(), null, tint = if (resume != null) C.OnAccent else C.Faint, modifier = Modifier.size(18.dp))
                        Text(label, color = if (resume != null) C.OnAccent else C.Faint, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = Space.s), maxLines = 1)
                    }
                    resume?.second?.takeIf { !it.watched }?.let { p ->
                        Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Surface2)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(p.ratio).background(C.Accent))
                            }
                            Text("剩 ${fmtLeftMin(p)}", color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), modifier = Modifier.padding(start = Space.s))
                        }
                    }
                    // 次要操作：一排等宽的图标 + 文字按钮（以前四个文字按钮挤在播放键旁边，窄屏放不下）
                    Row(Modifier.fillMaxWidth().padding(top = Space.m)) {
                        val wished = lib.isWish(m.id)
                        DetailAction(KI.heart(wished), if (wished) "已收藏" else "收藏", tint = if (wished) C.Accent else C.Text, popKey = wished) {
                            lib.toggleWish(m.id); toastUndo(ctx, if (wished) "已取消收藏" else "已收藏，影视页顶部可以找到") { lib.toggleWish(m.id) }
                        }
                        if (m.kind == "movie" && allVersions.size > 1) DetailAction(KI.layers(), "版本 ${allVersions.size}") { versions = true }
                        DetailAction(KI.refresh(), "重新识别") { fixing = true }
                        DetailAction(KI.cast(CastState.device != null), if (CastState.device != null) "投屏中" else "投屏", tint = if (CastState.device != null) C.Accent else C.Text) { casting = true }
                    }
                    // 1.0.9: quality badges of what 播放 starts (real track info once played, else from the file name)
                    val bf = (m.files.firstOrNull { it.path == resume?.first } ?: resumeEp?.file ?: m.files.firstOrNull())
                    if (bf != null) {
                        val badges = remember(bf.path, v) { MediaBadges.of(bf.path, bf.name) }
                        BadgeRow(badges, Modifier.padding(top = Space.m))
                        DeviceCaps.hint(ctx, badges)?.let { Text(it, color = C.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs + 2.dp)) }
                    }
                    // 全面审查 A1：有进度时可以从头播放；整部标为已看 / 未看
                    val allPaths = if (m.kind == "movie") m.files.map { it.path } else m.episodes.map { it.path }
                    val allWatched = allPaths.isNotEmpty() && allPaths.all { lib.progressOf(it)?.watched == true }
                    Row(Modifier.padding(top = Space.xs).offset(x = (-Space.m)), verticalAlignment = Alignment.CenterVertically) {
                        if (resume?.second?.watched == false) HeaderTextButton("从头播放", C.Accent) { resume.let { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, it.first, fromStart = true)) } }
                        if (allPaths.isNotEmpty()) HeaderTextButton(if (allWatched) "标为未看" else if (m.kind == "tv") "全部标为已看" else "标为已看", C.Sub) {
                            if (allWatched) { lib.markUnwatched(allPaths); toast(ctx, "已标为未看") } else { lib.markWatched(allPaths); toast(ctx, "已标为已看") }
                        }
                    }
                    // 1.0.1 #6: low-confidence automatic match → ask instead of guessing
                    if (m.pending && m.guess != null) PendingCard(lib, m, m.guess) { fixing = true }
                    if (!m.overview.isNullOrBlank()) {
                        var over by remember(m.id) { mutableStateOf(false) }
                        Text(m.overview, color = C.Text, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp,
                            maxLines = if (fullPlot) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!fullPlot) over = it.hasVisualOverflow },
                            modifier = Modifier.padding(top = Space.l).animateContentSize(Motion.enter()).clickable(enabled = over || fullPlot, onClickLabel = if (fullPlot) "收起简介" else "展开简介") { fullPlot = !fullPlot })
                        if (over && !fullPlot) Text("展开", color = C.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Space.xs).clickable(role = androidx.compose.ui.semantics.Role.Button) { fullPlot = true })
                    }
                    else if (!m.matched && !m.pending) Text(if (Tmdb.configured) "还没匹配到资料。点「重新识别」手动搜索。" else "在 设置 → 影视 填上 TMDB 密钥，就能自动配上海报和简介。",
                        style = MaterialTheme.typography.bodyMedium, color = C.Sub, modifier = Modifier.padding(top = Space.l))
                }
            }
            if (m.cast.isNotEmpty()) {
                item(key = "cast-h") { ContentHeader("演员", "${m.cast.size}") }
                item(key = "cast") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        items(m.cast) { c ->
                            // 全面审查 A9：点演员，看库里还有他 / 她的哪些作品
                            Column(Modifier.width(76.dp).pressScale().clip(RoundedCornerShape(Radius.m))
                                .clickable(onClickLabel = "看库里${c.name}的其他作品") { Nav.push(Route.MovieActor(lib.sourceKey, c.name)) }
                                .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button; contentDescription = c.name + (if (c.role.isNotEmpty()) "，饰 ${c.role}" else "") }
                                .padding(vertical = Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(64.dp).skeuoPhoto(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
                                    if (c.img != null) NetImage(posterModel(c.img), Modifier.fillMaxSize()) else Text(c.name.take(1), color = C.Sub, style = MaterialTheme.typography.titleLarge)
                                }
                                Text(c.name, color = C.Text, style = MaterialTheme.typography.bodySmall.copy(color = C.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
                                if (c.role.isNotEmpty()) Text(c.role, color = C.Sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (m.kind == "tv") {
                item(key = "seasons") {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ContentHeader(if (m.seasons.size > 1) "剧集" else if (season == 0) "特别篇" else "第 $season 季", "${m.seasons[season].orEmpty().size} 集") }
                            // A1：这一季全部标为已看 / 未看；长按某一集的说明收进 ⓘ（DESIGN §1 少说明）
                            val sp = m.seasons[season].orEmpty().map { it.path }
                            val sw = sp.isNotEmpty() && sp.all { lib.progressOf(it)?.watched == true }
                            if (sp.size > 1) Box(Modifier.padding(top = Space.l)) { HeaderTextButton(if (sw) "本季标为未看" else "本季标为已看", C.Sub) { if (sw) lib.markUnwatched(sp) else lib.markWatched(sp) } }
                            Box(Modifier.padding(top = Space.l)) { InfoDot("长按某一集可以标为已看 / 未看、从头播放。", "剧集操作") }
                        }
                        if (m.seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(m.seasons.keys.sorted()) { s -> ChoiceChip(if (s == 0) "特别篇" else "第 $s 季", s == season) { season = s } }
                        }
                    }
                }
                items(m.seasons[season].orEmpty(), key = { it.path }) { e -> EpisodeRow(lib, m, e) }
            } else if (m.files.isNotEmpty()) {
                item(key = "files-h") { ContentHeader("文件", if (m.files.size > 1) "${m.files.size}" else "") }
                item(key = "files") {
                    Column(Modifier.padding(horizontal = Space.page)) {
                        m.files.forEach { f ->
                            Text(f.name, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
                            BadgeRow(MediaBadges.of(f.path, f.name), Modifier.padding(vertical = Space.xs))
                            Text(listOfNotNull(fmtBytes(f.size).takeIf { f.size > 0 }, if (f.subs.isNotEmpty()) "${f.subs.size} 个外挂字幕" else null).joinToString(" · "), color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num))
                        }
                    }
                }
            }
        }
        // 吸顶栏：滚过头图后淡入（98% 页面底 + 发丝线 + 片名），返回钮在两种状态下位置不变
        androidx.compose.animation.AnimatedVisibility(pinned, Modifier.align(Alignment.TopCenter), enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Box(Modifier.fillMaxWidth().background(C.Bg.copy(alpha = 0.98f)).drawBehind { drawLine(C.Line, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f) }
                .statusBarsPadding().height(64.dp), contentAlignment = Alignment.CenterStart) {
                Text(m.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 66.dp, end = Space.page))
            }
        }
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Space.s + 2.dp)) {
            val barBg by androidx.compose.animation.animateColorAsState(if (pinned) C.Chip else Color(0x55000000), Motion.state(), label = "backBg")
            val barFg by androidx.compose.animation.animateColorAsState(if (pinned) C.Text else Color.White, Motion.state(), label = "backFg")
            RoundIcon(KI.back(), bg = barBg, tint = barFg, size = 44.dp, desc = "返回") { Nav.pop() }
        }
    }

    if (versions) AppAlertDialog(onDismissRequest = { versions = false }, containerColor = C.Surface, title = { Text("选择版本", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            allVersions.forEach { (owner, f) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { versions = false; Nav.push(Route.MoviePlay(lib.sourceKey, owner.id, f.path)) }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                    Text(versionLabel(f.name), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    BadgeRow(MediaBadges.of(f.path, f.name), Modifier.padding(vertical = 3.dp))
                    // 来源：所在文件夹（合并的版本可能在不同目录）
                    val folder = f.path.removePrefix("fm:").trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
                    Text(listOf(f.name, fmtBytes(f.size), folder.takeIf { it.isNotEmpty() }?.let { "来自 $it" }).filterNotNull().joinToString("  ·  "), color = C.Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** 1.6.6 详情页尺寸。 */
private object DetailSize {
    val backdrop = 300.dp
    val poster = 112.dp
    /** 海报压进头图的高度。 */
    val posterOverlap = 96.dp
}

/** 详情页次要操作：图标在上、文字在下，平分一行；按压缩到 0.94。 */
@Composable
private fun RowScope.DetailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = C.Text, popKey: Any? = null, onClick: () -> Unit) {
    // 1.7.28：[popKey] 变化时图标弹一下（收藏 / 取消收藏，Motion.pop，与大图收藏同一节奏）；颜色过渡 Motion.state
    val c by androidx.compose.animation.animateColorAsState(tint, Motion.state(), label = "detailTint")
    Column(Modifier.weight(1f).heightIn(min = 56.dp).pressScale(0.94f).clip(RoundedCornerShape(Radius.m)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {}.padding(vertical = Space.s), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = c, modifier = Modifier.size(22.dp).popOnChange(popKey))
        Text(label, color = if (tint == C.Text) C.Sub else tint, style = MaterialTheme.typography.labelMedium.copy(color = if (tint == C.Text) C.Sub else tint), maxLines = 1, modifier = Modifier.padding(top = Space.xs + 2.dp))
    }
}

private fun fmtLeftMin(p: Progress): String { val m = ((p.dur - p.pos) / 60000).coerceAtLeast(1); return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(lib: MovieLib, m: MovieItem, e: Episode) {
    val p = lib.progressOf(e.path)
    var menu by remember { mutableStateOf(false) }
    Box {
    // 全面审查 A1：长按菜单 标为已看 / 标为未看 / 从头播放。1.6.6：剧照圆角 10、集号等宽数字、看完 = 勾、整行按压
    val title = e.title ?: NameParser.stem(e.file?.name ?: e.path.substringAfterLast('/'))
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }, onLongClickLabel = "更多操作") { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, e.path)) }
        .semantics(mergeDescendants = true) { contentDescription = "第 ${e.episode} 集，$title" + when { p?.watched == true -> "，已看完"; p != null -> "，看了 ${(p.ratio * 100).toInt()}%"; else -> "" } }
        .padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(136.dp).aspectRatio(16f / 9f).pressScale(Motion.PRESS_SCALE_LARGE).skeuoPhoto(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            val img = e.still ?: m.backdrop
            if (img != null) NetImage(posterModel(img), Modifier.fillMaxSize())
            if (p != null && !p.watched) ProgressLine(p.ratio, Modifier.align(Alignment.BottomStart))
            if (p?.watched == true) Box(Modifier.align(Alignment.TopEnd).padding(Space.xs + 1.dp).size(20.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(KI.check(), null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        Column(Modifier.padding(start = Space.m).weight(1f)) {
            Text("第 ${e.episode} 集", color = if (p?.watched == true) C.Sub else C.Accent, style = MaterialTheme.typography.labelMedium.merge(Type.num).copy(color = if (p?.watched == true) C.Sub else C.Accent))
            Text(title, color = if (p?.watched == true) C.Sub else C.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
        }
    }
    WatchMenu(menu, onDismiss = { menu = false }, watched = p?.watched == true, resumable = p != null && !p.watched,
        onWatched = { lib.markWatched(listOf(e.path)) }, onUnwatched = { lib.markUnwatched(listOf(e.path)) },
        onFromStart = { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, e.path, fromStart = true)) })
    }
}

/** 全面审查 A1：影视长按菜单（剧集、继续观看、海报墙共用）。 */
@Composable
fun WatchMenu(open: Boolean, onDismiss: () -> Unit, watched: Boolean, resumable: Boolean = false,
              onWatched: () -> Unit, onUnwatched: () -> Unit, onFromStart: (() -> Unit)? = null, onHideContinue: (() -> Unit)? = null) {
    AppDropdownMenu(open, onDismissRequest = onDismiss) {
        if (resumable && onFromStart != null) DropdownMenuItem(text = { Text("从头播放", color = C.Text) }, onClick = { onDismiss(); onFromStart() })
        if (watched) DropdownMenuItem(text = { Text("标为未看", color = C.Text) }, onClick = { onDismiss(); onUnwatched() })
        else DropdownMenuItem(text = { Text("标为已看", color = C.Text) }, onClick = { onDismiss(); onWatched() })
        if (onHideContinue != null) DropdownMenuItem(text = { Text("从继续观看移除", color = C.Text) }, onClick = { onDismiss(); onHideContinue() })
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
            Ops.launch { runCatching { lib.applyMatch(m, g) }.onSuccess { toast(ctx, "已确认「${g.title}」") }.onFailure { failToast(ctx, it, "失败") }; busy = false }
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
    AppAlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("重新识别 / 手动选择", color = C.Text) }, text = {
        Column {
            if (!Tmdb.configured) { Text("先在 设置 → 影视 填 TMDB 密钥。", color = C.Sub, fontSize = 14.sp); return@Column }
            Segmented(listOf("电影", "剧集"), if (kind == "movie") 0 else 1) { kind = if (it == 0) "movie" else "tv"; hits = null }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // 1.7.9：v2 玻璃输入框（浮层内样式）
                Field("名称", q, { q = it }, null, "", ime = androidx.compose.ui.text.input.ImeAction.Search, onGlass = true, modifier = Modifier.weight(1f))
                Field("年份", year, { year = it.filter(Char::isDigit).take(4) }, null, "", type = androidx.compose.ui.text.input.KeyboardType.Number,
                    onGlass = true, modifier = Modifier.padding(start = 8.dp).width(84.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { search() }, enabled = !busy && q.isNotBlank()) { Text(if (busy) "搜索中…" else "搜索", color = C.Accent) }
                TextButton(onClick = {
                    onDismiss()
                    Ops.launch {
                        runCatching { lib.rematch(m) }.onSuccess { n -> toast(ctx, when { n.matched -> "已识别为「${n.title}」"; n.pending -> "把握不大，已标为待确认"; else -> "没有识别出来，试试手动搜索" }) }
                            .onFailure { failToast(ctx, it, "识别失败") }
                    }
                }, enabled = !busy) { Text("自动重新识别", color = C.Sub) }
            }
            Text("选中的条目会记住，重新扫描或清空影视库后也不变。", color = C.Faint, fontSize = 11.sp)
            err?.let { Text(it, color = C.Danger, fontSize = 12.sp) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(hits.orEmpty(), key = { it.kind + it.id }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        onDismiss()
                        Ops.launch { runCatching { lib.applyMatch(m, h) }.onSuccess { toast(ctx, "已匹配「${h.title}」") }.onFailure { failToast(ctx, it, "匹配失败") } }
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
    var confirmClear by remember { mutableStateOf(false) }
    val src = currentSource()
    Page("影视文件夹", src?.let { "${if (it.kind == "dav") "WebDAV" else "飞牛"} · ${it.title}" } ?: "") {
        if (lib == null) { StateMessage(LI.film(), "先添加一个来源", ""); return@Page }
        if (!lib.ready) { LibLoading(); return@Page }   // never save folders over a library that is still loading
        if (picking) { FolderPicker(lib.fs, lib.kind, onDismiss = { picking = false }) { path, name ->
            picking = false
            // 全面审查 B4：和音乐一样合并父子目录，选完直接开始扫描
            val outer = lib.dirs.firstOrNull { MediaIsolation.isUnder(path, it.path) }
            if (outer != null) toast(ctx, "已包含在「${outer.name}」里")
            else {
                val inner = lib.dirs.filter { MediaIsolation.isUnder(it.path, path) }
                if (inner.isNotEmpty()) toast(ctx, "已合并 ${inner.size} 个子文件夹")
                lib.saveDirs(lib.dirs - inner.toSet() + MovieDir(path, name, "auto")); lib.scan(); ScanService.ensure(ctx)
            }
        }; return@Page }
        Column(Modifier.fillMaxSize().verticalScrollCompat().navigationBarsPadding().padding(bottom = 40.dp)) {
            Section("影视文件夹", info = "和相册的扫描目录分开设置。可以加多个，每个标成电影、剧集或自动识别。" +
                if (lib.kind == "fn") "\n\n飞牛的相册接口只返回视频本身，读不到同目录的 NFO、海报和字幕；这些会用 TMDB 补上。" else "")
            Card {
                if (lib.dirs.isEmpty()) Text("还没有影视文件夹", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                lib.dirs.forEachIndexed { i, d ->
                    if (i > 0) Div()
                    LibraryDirRow(d.name, d.path) { lib.saveDirs(lib.dirs - d); if (lib.dirs.isNotEmpty()) { lib.scan(); ScanService.ensure(ctx) } }
                    Row(Modifier.padding(start = 32.dp, top = 8.dp, bottom = 12.dp)) {
                        Segmented(listOf("自动", "电影", "剧集"), listOf("auto", "movie", "tv").indexOf(d.kind).coerceAtLeast(0)) { k ->
                            lib.saveDirs(lib.dirs.map { if (it.path == d.path) it.copy(kind = listOf("auto", "movie", "tv")[k]) else it })
                        }
                    }
                }
                Div()
                NavRow(LI.plus(), "添加文件夹") { picking = true }
            }
            LibraryScanRow(lib.scanning, lib.dirs.isNotEmpty(), "先添加影视文件夹",
                onScan = { lib.scan(); ScanService.ensure(ctx) }, redoLabel = "重新匹配未匹配") {
                lib.scan(rescrapeUnmatched = true); ScanService.ensure(ctx)
            }
            // 用户 2026-10-23：这页本来就是从「影音设置」进来的，回到影音设置的回链删掉（绕圈）
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


/**
 * 1.1.0: TMDB 刮削 card (was inside 影视媒体库), shown in 影音设置 →「资料补全」.
 * 用户 2026-10-23：代理已是自动选择（直连 → 内置代理），两个代理输入框收进就地折叠的「高级」，默认收起；
 * 以前填过代理的用户默认展开并标「已自定义」，免得一个看不见的值在起作用。存储键 tmdb.api / tmdb.img 不变。
 */
@Composable
internal fun TmdbSettingsCard(extra: @Composable ColumnScope.() -> Unit = {}) {
    var tmdbKey by remember { mutableStateOf(Tmdb.key) }
    var api by remember { mutableStateOf(Tmdb.apiBase) }
    var img by remember { mutableStateOf(Tmdb.imgBase) }
    val custom = Tmdb.apiBase.isNotBlank() || Tmdb.imgBase.isNotBlank()
    var adv by remember { mutableStateOf(custom) }
    Card {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { InputRow("TMDB API 密钥", tmdbKey, "v3 API Key 或 v4 读取令牌") { tmdbKey = it } }
            InfoDot("在 themoviedb.org 注册后，于「设置 → API」免费申请。填好后会自动检测：能直连就直连，连不上自动换国内可用的代理（每天和换网络时重新检测）。", "TMDB API 密钥")
        }
        // 1.0.1 #1: which way TMDB is reached right now (direct overseas, an automatically verified proxy in China)
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前网络：" + if (TmdbNet.checking) "检测中…" else TmdbNet.status.ifEmpty { TmdbNet.describe() }, color = C.Sub, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { TmdbNet.recheckAsync() }, enabled = !TmdbNet.checking) { Text("重新检测", color = C.Accent, fontSize = 13.sp) }
        }
        if (adv) {
            InputRow("接口代理", api, "留空 = 自动") { api = it }
            InputRow("图片代理", img, "留空 = 自动") { img = it }
        }
        TestButton("保存并测试", Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), resetKey = tmdbKey + "|" + api + "|" + img, height = 44.dp) {
            Tmdb.key = tmdbKey; Tmdb.apiBase = api; Tmdb.imgBase = img
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) { Tmdb.test() } + " · ${System.currentTimeMillis() - t0} ms"
        }
        Div()
        extra()
        Div()
        Row(Modifier.fillMaxWidth().clickable { adv = !adv }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("高级", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (custom) Text("已自定义", color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            Icon(if (adv) KI.up() else KI.down(), null, tint = C.Faint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 1.1.0 影音设置：影音相关的设置只有这一个入口（设置页、影视页、音乐页的齿轮都到这里）。
 * 文件夹按来源各一份，点进各自页面；TMDB、音乐联网补全所有来源共用，直接在这里改。
 */
@Composable
fun MediaSettingsScreen() {
    val src = currentSource()
    var online by remember { mutableStateOf(MusicPrefs.online) }
    var cleanNames by remember { mutableStateOf(MusicPrefs.cleanNames) }
    var pip by remember { mutableStateOf(VideoPip.enabled) }
    var autoNext by remember { mutableStateOf(PlayPrefs.autoNext) }
    var bgAudio by remember { mutableStateOf(PlayPrefs.bgAudio) }
    var gapless by remember { mutableStateOf(MusicPrefs.gapless) }
    var fnQuality by remember { mutableStateOf(PlayPrefs.fnQualityRemember) }
    Page("影音设置", src?.let { "${if (it.kind == "dav") "WebDAV" else "飞牛"} · ${it.title}" } ?: "") {
        Column(Modifier.fillMaxSize().verticalScrollCompat().navigationBarsPadding().padding(bottom = 40.dp)) {
            Section("媒体库")
            Card {
                val ml = Movies.lib()
                NavRow(LI.film(), "影视文件夹", value = ml?.dirs?.size?.let { if (it == 0) "未选择" else "$it 个" } ?: "先添加来源") { Nav.push(Route.MovieLibrarySettings) }
                Div()
                val mu = Music.lib()
                NavRow(LI.music(), "音乐文件夹", value = mu?.dirs?.size?.let { if (it == 0) "未选择" else "$it 个 · ${mu.tracks.size} 首" } ?: "先添加来源") { Nav.push(Route.MusicSettings) }
            }
            Section("播放")
            Card {
                val ctx = LocalContext.current
                val pipOk = VideoPip.supported(ctx)
                ToggleRow(LI.videos(), "离开时画中画", if (pipOk) null else "本机不支持", pip && pipOk, enabled = pipOk) { pip = it; VideoPip.enabled = it }
                Div()
                ToggleRow(LI.play(), "自动播放下一集", on = autoNext) { autoNext = it; PlayPrefs.autoNext = it }
                Div()
                // 优化 6：息屏 / 切到后台时影视继续出声（通知栏和锁屏可暂停）；回到前台画面接上
                ToggleRow(LI.music(), "息屏继续播放声音", on = bgAudio) {
                    bgAudio = it; PlayPrefs.bgAudio = it
                    if (it) (ctx as? android.app.Activity)?.let { a -> BgRun.maybeAskNotifications(a) }
                }
                // 用户 2026-10-23 反馈（清晰度）：只有飞牛来源有转码档位，WebDAV 时不显示
                if (src != null && src.kind != "dav") {
                    Div()
                    ToggleRow(LI.film(), "记住飞牛清晰度", on = fnQuality) { fnQuality = it; PlayPrefs.fnQualityRemember = it }
                }
                Div()
                // 优化 9 / 17：音乐预加载下一首 + 无缝播放
                ToggleRow(LI.music(), "音乐无缝播放", on = gapless, info = "当前这首缓冲好后提前加载下一首，切歌不停顿，整张专辑连续播放没有空隙。会提前用掉一首歌的流量。") {
                    gapless = it; MusicPrefs.gapless = it
                }
            }
            Section("资料补全（所有来源共用）")
            TmdbSettingsCard {
                ToggleRow(KI.cloud(), "音乐联网补全", on = online,
                    info = "文件里缺专辑、歌手、封面或歌词时，按「歌名 + 歌手 + 时长」从网易云音乐、QQ 音乐（国内直连）和 LRCLIB 补上；对不上的不用。" +
                        "不会改写文件里已有的信息，封面和歌词只存在本机。翻唱歌曲不会用原唱的专辑封面。") { online = it; MusicPrefs.online = it }
                Div()
                // 1.7.32：歌名规范显示（只改显示，不改文件）
                ToggleRow(LI.music(), "自动规范歌名和歌手", on = cleanNames,
                    info = "显示歌名时去掉音轨号（如「07-」）、文件名里的「歌手 - 」和 [FLAC]、320k、无损、官方版之类的杂项；Live、伴奏、Remix 等版本说明会保留。歌手和专辑名去掉编号、年份和首尾符号（「01张杰」→「张杰」），「未知艺术家」统一显示为未知歌手并排在最后。只改显示，不改文件。") {
                    cleanNames = it; MusicPrefs.cleanNames = it
                }
            }
        }
    }
}
