package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
    // 审查 11：电影 / 剧集的选择也存下来，重启后保持
    var seg by mutableIntStateOf(Store.getStr("movies.seg", "0").toIntOrNull() ?: 0)        // 0 电影, 1 剧集
    /**
     * 1.0.3 #4 的「电影剧集 | 音乐」切换（Store "movies.area"）。1.4.0 拆成两个底栏 tab 后只用于迁移：
     * 上次停在影音 → 音乐的用户，升级后第一次启动直接回到「音乐」tab（见 MainActivity 恢复 nav.tab）。
     */
    val legacyMusicArea: Boolean get() = Store.getStr("movies.area", "0") == "1"
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
    val key = Movies.keyOf()
    if (key == null) { Column { TopSwitch(SearchScope.None, showDate = false); Box(Modifier.weight(1f)) { NoSourceState(LI.film(), "影视库跟着左上角的来源走") } }; return }
    val lib = remember(key) { Movies.lib(key) }
    val grid = rememberMemGrid("movies-$key-${MoviesState.seg}")
    LaunchedEffect(key) {
        val t0 = System.currentTimeMillis()
        lib.awaitReady()   // #2 / H4: the library loads on IO; decide about the quiet rescan once it is in
        // 用户 2026-11-01 反馈第一次进影音黑屏：记下读库用时和条数，下次凭诊断日志确认
        Diag.log("MEDIA", "movies tab ready in ${System.currentTimeMillis() - t0} ms, items=${lib.items.size} dirs=${lib.dirs.size}")
        // first visit after a restart: pick up new files quietly (only new ones are scraped)
        autoScanIfStale("movies.last.$key", lib.dirs.isNotEmpty(), lib.scanning) { lib.scan(quiet = true); ScanService.ensure(App.ctx) }
    }
    val v = lib.version
    val kind = if (MoviesState.seg == 0) "movie" else "tv"
    // 优化 14：同一部电影的多个版本（不同文件夹 / 清晰度）合并成一张海报，详情页里选版本
    val shown = remember(v, lib.items.size, kind, MoviesState.sort) { MovieMerge.group(sorted(lib.items.filter { it.kind == kind }, MoviesState.sort)) }
    val cont = remember(v, lib.items.size) { lib.continueWatching() }
    val wish = remember(v, lib.items.size) { lib.wishItems() }
    val counts = remember(v, lib.items.size) { lib.items.count { it.kind == "movie" } to lib.items.count { it.kind == "tv" } }

    // 第 4 批：平板/折叠屏——Adaptive 替代 Fixed(3)：手机上仍约 3 列（110dp 下限），平板自动变多列；通栏行用 maxLineSpan。
    // B7：下拉刷新 = 后台增量扫描（和右上角刷新一样只扫新文件），有缓存时只显示一行「正在更新」
    PullRefreshBox(enabled = lib.ready && lib.dirs.isNotEmpty() && !lib.scanning, onRefresh = { lib.scan(quiet = true); ScanService.ensure(App.ctx) }) {
    LazyVerticalGrid(GridCells.Adaptive(110.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.page, end = Space.page, bottom = 120.dp + miniPlayerPad),
        horizontalArrangement = Arrangement.spacedBy(PosterSize.gap), verticalArrangement = Arrangement.spacedBy(Space.l + Space.xs)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.bleed(Space.page)) { MediaHeader("影视", SearchScope.Media, lib.scanning, lib.dirs.isNotEmpty(), "重新扫描影视") { lib.scan(); ScanService.ensure(App.ctx) } }
        }
        if (!lib.ready) { items(9) { PosterSkeleton() }; return@LazyVerticalGrid }
        if (lib.dirs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(460.dp)) {
                    StateMessage(LI.film(), "还没有影视目录", "选好电影和剧集所在的文件夹，我来扫描并配上海报和简介。", "选择影视文件夹") { Nav.push(Route.MovieLibrarySettings) }
                }
            }
            return@LazyVerticalGrid
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("电影 ${counts.first.takeIf { it > 0 } ?: ""}".trim(), "剧集 ${counts.second.takeIf { it > 0 } ?: ""}".trim()), MoviesState.seg) { MoviesState.seg = it; Store.putStr("movies.seg", "$it") }
                Spacer(Modifier.weight(1f))
                SortMenu()
            }
        }
        // 优化 11 / 12：有缓存时，自动刷新和刷新失败只显示一行小字（列表先显示上次的内容）；没缓存或手动全量扫描才用进度卡片
        val hasCache = lib.items.isNotEmpty()
        if ((lib.scanning && lib.quiet && hasCache) || (!lib.scanning && lib.error != null && hasCache))
            item(span = { GridItemSpan(maxLineSpan) }) { QuietStatusLine(lib.scanning, lib.error) { lib.scan(); ScanService.ensure(App.ctx) } }
        else if (lib.scanning || lib.error != null) item(span = { GridItemSpan(maxLineSpan) }) { ScanBar(lib) }
        // 1.6.6 阶段 4：分区标题与合集 / 发现页同一款（ContentHeader）；横条通栏滑动，首尾和海报墙左右对齐
        if (cont.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(Space.page)) {
                    ContentHeader("继续观看", "${cont.size}")
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(PosterSize.gap)) {
                        itemsIndexed(cont, key = { _, c -> c.first.id }) { i, (m, ep) -> ContinueCard(lib, m, ep, i) }
                    }
                }
            }
        }
        if (wish.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(Space.page)) {
                    ContentHeader("我的收藏", "${wish.size}")
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(PosterSize.gap)) {
                        itemsIndexed(wish, key = { _, m -> m.id }) { i, m -> Box(Modifier.width(PosterSize.row)) { PosterCard(m, lib, index = i) { Nav.push(Route.MovieDetail(key, m.id)) } } }
                    }
                }
            }
        }
        if (cont.isNotEmpty() || wish.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.bleed(Space.page)) { ContentHeader(if (kind == "movie") "全部电影" else "全部剧集", if (shown.isEmpty()) "" else "${shown.size}") }
        }
        if (shown.isEmpty()) {
            if (lib.scanning) items(9) { PosterSkeleton() }
            else item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (kind == "movie") LI.film() else LI.tv(), null, tint = C.Faint, modifier = Modifier.size(44.dp))
                    Text(if (kind == "movie") "还没找到电影" else "还没找到剧集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Space.l))
                    Text(if (kind == "movie") "剧集文件夹可以在设置里标成「剧集」。" else "文件名带 S01E02、第1集 这类写法就能认出来。",
                        style = MaterialTheme.typography.bodyMedium, color = C.Sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s))
                    // 全面审查 B4：空状态给一个去设置文件夹类型的按钮
                    Box(Modifier.padding(top = Space.m)) { HeaderTextButton("设置文件夹类型", C.Accent) { Nav.push(Route.MovieLibrarySettings) } }
                }
            }
        } else itemsIndexed(shown, key = { _, g -> g.first().id }) { i, g -> val m = g.first(); PosterCard(m, lib, versions = g.sumOf { it.files.size }, index = i) { Nav.push(Route.MovieDetail(key, m.id)) } }
    }
    }
}

/**
 * 全面审查 C8：影视页 / 音乐页共用的头部——来源顶栏 + 大标题（1.4.0 起分别是「影视」「音乐」两个 tab，不再有切换）+ 刷新 / 设置。
 * 审查 2/3：影音不显示「按日期筛选照片」；[search] 电影剧集是搜影片，音乐页有页内搜索框所以不放。
 */
@Composable
fun MediaHeader(title: String, search: SearchScope, scanning: Boolean, hasDirs: Boolean, scanLabel: String, onScan: () -> Unit) {
    Column {
        TopSwitch(search = search, showDate = false)
        // 1.6.6：大标题与照片 / 合集页一致（displaySmall、读屏标题）；右侧 24dp 线性图标按钮，扫描中刷新图标转起来
        Row(Modifier.padding(top = Space.s, start = Space.page, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.weight(1f).semantics { heading() })
            if (hasDirs) IconButton(onClick = onScan, enabled = !scanning) {
                val spin = if (scanning && !Motion.reduced) rememberInfiniteTransition(label = "scan").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin").value else 0f
                Icon(KI.refresh(), if (scanning) "正在扫描" else scanLabel, tint = if (scanning) C.Sub else C.Text, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = spin })
            }
            IconButton(onClick = { Nav.push(Route.MediaSettings) }) { Icon(KI.settings(), "影音设置", tint = C.Text, modifier = Modifier.size(24.dp)) }
        }
        Spacer(Modifier.height(Space.s))
    }
}

@Composable
fun Segmented(items: List<String>, sel: Int, onPick: (Int) -> Unit) {
    // 1.6.0 分段控件：内凹的槽 + 浮起的选中块（质感）/ 中性槽 + 页面底色块（简洁）；切换时文字颜色渐变、轻震
    val view = androidx.compose.ui.platform.LocalView.current
    Row(Modifier.skeuoInset(RoundedCornerShape(Radius.s + 2.dp), if (Skeuo.on) C.Surface2 else C.Chip).padding(3.dp)) {
        items.forEachIndexed { i, t ->
            val on = i == sel
            val fg by androidx.compose.animation.animateColorAsState(if (on) C.Text else C.Sub, Motion.state(), label = "segFg")
            Text(t, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.then(if (on) Modifier.skeuoPressed(false, RoundedCornerShape(Radius.s - 1.dp), if (Skeuo.on) C.Surface else C.Bg, Elevation.pressed)
                    else Modifier.clip(RoundedCornerShape(Radius.s - 1.dp))).clickable(role = androidx.compose.ui.semantics.Role.Tab) { if (!on) Haptics.tick(view); onPick(i) }.semantics { selected = on }.padding(horizontal = 16.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun SortMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.s)).clickable(onClickLabel = "选择排序方式", role = androidx.compose.ui.semantics.Role.Button) { open = true }
            .semantics(mergeDescendants = true) { contentDescription = "排序：${MoviesState.sorts[MoviesState.sort]}" }.padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(KI.sort(), null, tint = C.Sub, modifier = Modifier.size(16.dp))
            Text(MoviesState.sorts[MoviesState.sort], color = C.Sub, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Space.xs))
        }
        AppDropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Surface)) {
            MoviesState.sorts.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s, color = if (i == MoviesState.sort) C.Accent else C.Text, fontWeight = if (i == MoviesState.sort) FontWeight.Medium else FontWeight.Normal) },
                    trailingIcon = if (i == MoviesState.sort) { { Icon(KI.check(), "当前", tint = C.Accent, modifier = Modifier.size(18.dp)) } } else null,
                    onClick = { open = false; MoviesState.sort = i; Store.putStr("movies.sort", "$i") })
            }
        }
    }
}

/**
 * Shared 影视 / 音乐 library pieces (D2 / D8): one scan progress bar, one settings-page scan row
 * (manual scan + the differently-named “redo” action), one folder row, one 6 h stale auto-scan.
 * The two libs keep their own scan() parameters — rescrapeUnmatched (影视) vs rescrape (音乐) — so the
 * row takes explicit [onScan] / [onRedo] lambdas instead of a shared scan call; nothing may drop a parameter.
 */
@Composable
private fun ScanBar(lib: MovieLib) {
    // 第 4 批：复用共用 ScanProgressCard（与音乐共用一份实现，样式与旧版一致）。
    ScanProgressCard(
        title = if (lib.scanning) lib.phase.ifEmpty { "正在扫描" } else "扫描遇到问题",
        countText = if (lib.scanning && lib.total > 0) "${lib.done}/${lib.total}" else null,
        scanning = lib.scanning,
        error = lib.error,
        progress = if (lib.scanning && lib.total > 0) lib.done / lib.total.toFloat() else null,
        onRetry = { lib.scan(); ScanService.ensure(App.ctx) },
    )
}


/** #2 / H4: shown while a 影视 / 音乐 library is still being read from disk on the IO thread. */
@Composable
fun LibLoading() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(28.dp))
        Text("正在读取媒体库…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

/** First visit after a restart: pick up new files quietly when the last scan is over 6 h old. */
fun autoScanIfStale(storeKey: String, hasDirs: Boolean, scanning: Boolean, onScan: () -> Unit) {
    if (hasDirs && !scanning && System.currentTimeMillis() - (Store.getStr(storeKey).toLongOrNull() ?: 0L) > 6 * 3600_000L) {
        Store.putStr(storeKey, System.currentTimeMillis().toString()); onScan()
    }
}

@Composable
fun GhostButton(t: String, enabled: Boolean = true, onClick: () -> Unit) {
    // 1.6.6：与次要按钮同一套按压（缩放 + 质感浮起），不可用时文字变弱
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Text(t, color = if (enabled) C.Text else C.Faint, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        modifier = Modifier.height(48.dp).skeuoPressable(interaction, RoundedCornerShape(Radius.m), if (Skeuo.on) C.Surface else C.Surface2)
            .clickable(interaction, null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = Space.l).wrapContentHeight(Alignment.CenterVertically))
}

/** Settings-page scan row: one manual “立即扫描” + the lib's own “redo” action ([onRedo]). */
@Composable
fun LibraryScanRow(scanning: Boolean, hasDirs: Boolean, emptyHint: String, onScan: () -> Unit, redoLabel: String, redoEnabled: Boolean = true, onRedo: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GradientButton(if (scanning) "扫描中…" else "立即扫描", Modifier.weight(1f), loading = scanning) {
            if (!hasDirs) toast(ctx, emptyHint) else { onScan(); toast(ctx, "开始扫描，离开页面也会继续") }
        }
        GhostButton(redoLabel, enabled = redoEnabled) { onRedo() }
    }
}

/** One folder row of a library settings page (name / path / 移除); kind pickers stay per-page. */
@Composable
fun LibraryDirRow(name: String, path: String, onRemove: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KI.folder(), null, tint = C.Sub, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = Space.m).weight(1f)) {
            Text(name, color = C.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HeaderTextButton("移除", C.Danger) { onRemove() }
    }
}

/** Poster file path or url → something Coil loads. */
fun posterModel(p: String?): String? = p?.let { if (it.startsWith("/")) "file://$it" else TmdbNet.imgUrl(it) }

/** 1.6.6 阶段 4：海报尺寸（圆角 [Radius.s]、横条里的固定宽度、海报之间的间距）。 */
object PosterSize {
    val row = 112.dp
    val gap = 12.dp
    val continueW = 240.dp
}

/** 海报上的小角标：深色半透明底 + 白字（不用强调色块，颜色留给海报本身）。 */
@Composable
internal fun PosterBadge(text: String, modifier: Modifier = Modifier, num: Boolean = false) {
    Text(text, color = Color.White, style = if (num) Type.badge else MaterialTheme.typography.labelSmall.copy(color = Color.White), maxLines = 1,
        modifier = modifier.padding(Space.xs + 2.dp).clip(RoundedCornerShape(Radius.xs)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp))
}

/**
 * 全面审查 A5：[lib] 不为空时海报上显示观看状态（电影「已看」/ 进度条，剧集「看到第几集」），长按可标已看 / 未看（A1）。
 * 1.6.6：圆角 10 + 质感轻影、按压 0.985、首屏前几张错开上浮进入（[index]）；角标统一深色底；读屏整张读成一个按钮。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PosterCard(m: MovieItem, lib: MovieLib? = null, versions: Int = 0, index: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val paths = remember(m) { if (m.kind == "movie") m.files.map { it.path } else m.episodes.map { it.path } }
    val st = lib?.let { l -> watchState(m, paths, l) }
    val sub = st?.label ?: listOfNotNull(m.year?.toString(), if (m.kind == "tv") "${m.episodes.size} 集" else null).joinToString("  ")
    Box {
    Column(Modifier.enterRise(index).pressScale(Motion.PRESS_SCALE_LARGE)
        .combinedClickable(onLongClick = if (lib != null && paths.isNotEmpty()) ({ menu = true }) else null, onLongClickLabel = "标为已看或未看", onClick = onClick)
        .semantics(mergeDescendants = true) {
            role = androidx.compose.ui.semantics.Role.Button
            contentDescription = listOfNotNull(m.title, sub.takeIf { it.isNotEmpty() }, m.rating?.let { "评分 %.1f".format(it) }, if (st?.allWatched == true) "已看" else null,
                if (versions > 1) "$versions 个版本" else null).joinToString("，")
        }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeuoPhoto(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize().padding(Space.s + 2.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (m.kind == "tv") KI.tv() else KI.film(), null, tint = C.Faint, modifier = Modifier.size(28.dp))
                Text(m.title, color = C.Sub, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
            }
            m.rating?.let { r -> PosterBadge("%.1f".format(r), Modifier.align(Alignment.TopEnd), num = true) }
            if (st?.allWatched == true) Box(Modifier.align(Alignment.TopStart).padding(Space.xs + 2.dp).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(KI.check(), null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
            if (!m.matched && m.source != "local") PosterBadge(if (m.pending) "待确认" else "未匹配", Modifier.align(Alignment.BottomStart))
            if (versions > 1) PosterBadge("$versions 个版本", Modifier.align(Alignment.BottomEnd))
            st?.ratio?.let { r -> ProgressLine(r, Modifier.align(Alignment.BottomStart)) }
        }
        Text(m.title, color = C.Text, style = MaterialTheme.typography.titleSmall, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        Text(sub, color = if (st?.label != null) C.Accent else C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (lib != null) WatchMenu(menu, onDismiss = { menu = false }, watched = st?.allWatched == true,
        onWatched = { lib.markWatched(paths) }, onUnwatched = { lib.markUnwatched(paths) })
    }
}

/** 海报 / 剧照底部的观看进度：3dp 强调色线压在一条白 24% 的底轨上，看得出「还剩多少」。 */
@Composable
internal fun ProgressLine(ratio: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.24f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(ratio.coerceIn(0f, 1f)).background(C.Accent))
    }
}

/** A5：海报上的观看状态。[ratio] 看了一半的电影显示进度条；[label] 剧集「看到 第1季 第3集」/「已看完」，没看过为 null。 */
private class WatchState(val allWatched: Boolean, val ratio: Float?, val label: String?)

private fun watchState(m: MovieItem, paths: List<String>, lib: MovieLib): WatchState? {
    if (paths.isEmpty()) return null
    val prs = paths.map { lib.progressOf(it) }
    if (prs.all { it == null }) return null
    val all = prs.all { it?.watched == true }
    return if (m.kind == "movie") {
        WatchState(all, if (all) null else prs.filterNotNull().maxByOrNull { it.at }?.ratio?.takeIf { it > 0.01f }, null)
    } else {
        val left = prs.count { it?.watched != true }
        val pick = lib.resumeShow(m)
        val ep = m.episodes.firstOrNull { it.path == pick?.path }
        val label = when {
            all -> "已看完"
            ep != null -> "看到 第${ep.season}季 第${ep.episode}集 · 剩 $left 集"
            else -> "剩 $left 集未看"
        }
        WatchState(all, null, label)
    }
}

/** 海报占位：形状与 [PosterCard] 一致（海报 + 两行字），只有海报扫光。 */
@Composable
private fun PosterSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Radius.s)).background(shimmerBrush()))
        Box(Modifier.padding(top = Space.s + 2.dp).fillMaxWidth(0.8f).height(11.dp).clip(RoundedCornerShape(Radius.xs)).background(C.Surface2))
        Box(Modifier.padding(top = Space.xs + 2.dp).fillMaxWidth(0.4f).height(9.dp).clip(RoundedCornerShape(Radius.xs)).background(C.Surface2))
    }
}

/** 继续观看卡：16:9 剧照 + 中央圆形播放钮 + 底部进度；按压 0.985。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(lib: MovieLib, m: MovieItem, ep: Episode?, index: Int) {
    val path = ep?.path ?: m.files.maxByOrNull { lib.progressOf(it.path)?.at ?: 0L }?.path ?: return
    val pr = lib.progressOf(path)
    var menu by remember { mutableStateOf(false) }
    val sub = if (ep != null) "第${ep.season}季 第${ep.episode}集" + (pr?.let { if (it.watched) "" else " · 剩 ${fmtLeft(it)}" } ?: " · 下一集") else pr?.let { "剩 ${fmtLeft(it)}" } ?: ""
    Box {
    // 全面审查 A1：长按 从继续观看移除 / 标为已看 / 从头播放
    Column(Modifier.width(PosterSize.continueW).enterRise(index).pressScale(Motion.PRESS_SCALE_LARGE)
        .combinedClickable(onLongClick = { menu = true }, onLongClickLabel = "更多操作") { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, path)) }
        .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button; contentDescription = "继续播放 ${m.title}" + (if (sub.isNotEmpty()) "，$sub" else "") }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).skeuoPhoto(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            val img = ep?.still ?: m.backdrop ?: m.poster
            if (img != null) NetImage(posterModel(img), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x8C000000))))
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Icon(KI.playSolid(), null, tint = Color.White, modifier = Modifier.size(20.dp).offset(x = 1.dp))
            }
            if (pr != null) ProgressLine(pr.ratio, Modifier.align(Alignment.BottomStart))
        }
        Text(m.title, color = C.Text, style = MaterialTheme.typography.titleSmall, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        Text(sub, color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), maxLines = 1)
    }
    WatchMenu(menu, onDismiss = { menu = false }, watched = false, resumable = pr != null && !pr.watched,
        onWatched = { lib.markWatched(listOf(path)) }, onUnwatched = { lib.markUnwatched(listOf(path)) },
        onFromStart = { Nav.push(Route.MoviePlay(lib.sourceKey, m.id, path, fromStart = true)) },
        onHideContinue = { lib.hideFromContinue(m.id) })
    }
}

private fun fmtLeft(p: Progress): String { val m = ((p.dur - p.pos) / 60000).coerceAtLeast(1); return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }
