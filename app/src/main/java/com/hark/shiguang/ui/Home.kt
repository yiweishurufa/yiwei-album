package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

// ============================================================ scroll memory

/** Remembers scroll positions per page so going back never jumps to the top. */
object ScrollMem {
    val map = HashMap<String, Pair<Int, Int>>()
    fun reset() = map.clear()
}

@Composable
fun rememberMemGrid(key: String): LazyGridState {
    val saved = ScrollMem.map[key]
    val s = remember(key) { LazyGridState(saved?.first ?: 0, saved?.second ?: 0) }
    DisposableEffect(key) { onDispose { ScrollMem.map[key] = s.firstVisibleItemIndex to s.firstVisibleItemScrollOffset } }
    ScrollToTopOnTab(grid = s)
    return s
}

@Composable
fun rememberMemList(key: String): LazyListState {
    val saved = ScrollMem.map[key]
    val s = remember(key) { LazyListState(saved?.first ?: 0, saved?.second ?: 0) }
    DisposableEffect(key) { onDispose { ScrollMem.map[key] = s.firstVisibleItemIndex to s.firstVisibleItemScrollOffset } }
    ScrollToTopOnTab(list = s)
    return s
}

/** Double tap on the current bottom tab bumps [tick]; the visible grid scrolls to the top. */
object ScrollTop {
    var tick by mutableIntStateOf(0)
    var lastTab = -1
    var lastAt = 0L
}

/** Scrolls a list/grid to the top whenever the current tab is double-tapped. */
@Composable
fun ScrollToTopOnTab(grid: LazyGridState? = null, list: LazyListState? = null) {
    val t = ScrollTop.tick
    LaunchedEffect(t) { if (t > 0) { grid?.animateScrollToItem(0); list?.animateScrollToItem(0) } }
}

/** The viewer reports the last photo looked at; grids scroll to it on return. */
object ViewerReturn {
    var lastKey: String? = null
    var tick by mutableIntStateOf(0)
}

private fun n(v: Int) = NumberFormat.getIntegerInstance().format(v)

// ============================================================ home shell

/** [index] is HomeState.tab; 相册 (2) has no button of its own, it lives inside 照片 behind the 时间线 | 相册 switch. */
private data class Tab(val title: String, val index: Int, val icon: (Boolean) -> ImageVector)
private val tabs = listOf(
    Tab("照片", 0) { LI.photos(it) }, Tab("视频", 1) { LI.videos(it) }, Tab("影音", 5) { LI.film(it) }, Tab("AI", 3) { LI.discover(it) }, Tab("设置", 4) { LI.settings(it) },
)

/** 时间线 | 相册 inside the 照片 tab. */
@Composable
private fun PhotoModeSwitch() {
    Row(Modifier.padding(start = 16.dp, top = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(3.dp)) {
        listOf("时间线" to 0, "相册" to 2).forEach { (t, i) ->
            val on = HomeState.tab == i
            Text(t, color = if (on) C.Text else C.Sub, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) C.Surface2 else Color.Transparent).clickable { HomeState.tab = i }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
fun HomeScreen() {
    val dav = HomeState.source == "dav"
    // NAS connection: pick 内网/外网 and keep measuring latency while a 飞牛 source is shown
    LaunchedEffect(HomeState.source, NasAccounts.currentId) {
        if (HomeState.source != "fn" || NasAccounts.current == null) return@LaunchedEffect
        runCatching { Endpoint.autoSelect() }
        while (true) { delay(60_000); runCatching { Endpoint.ping() } }
    }
    LaunchedEffect(HomeState.switching) { if (HomeState.switching) { delay(550); HomeState.switching = false } }
    val wide = isWide()
    Row(Modifier.fillMaxSize().background(C.Bg)) {
    if (wide) SideRail()
    Box(Modifier.weight(1f).fillMaxHeight()) {
        if (HomeState.switching && HomeState.tab != 4) Column { TopSwitch(); SkeletonGrid(4, 20.dp) }
        else when (HomeState.tab) {
            0 -> if (dav) DavPhotosTab() else PhotosTab()
            1 -> VideosTab(dav)
            2 -> AlbumsTab(dav)
            3 -> if (dav) DavDiscoverTab() else DiscoverTab()
            5 -> MoviesTab()
            else -> SettingsScreen()
        }
        // 1.0.3 #4: music mini bar sits above the bottom navigation (at the bottom on wide screens)
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (!Selection.active) MiniPlayer(Modifier.padding(bottom = if (wide) 0.dp else 4.dp).then(if (wide) Modifier.navigationBarsPadding().padding(bottom = 10.dp) else Modifier))
            if (!wide) BottomBar(Modifier)
        }
    }
    }
    if (HomeState.showConn) ConnectionSheet { HomeState.showConn = false }
    if (HomeState.showDav) DavSheet { HomeState.showDav = false }
    if (HomeState.showDate) DateFilterDialog { HomeState.showDate = false }
    if (HomeState.showFaceCfg) FaceSettingsDialog { HomeState.showFaceCfg = false }
    HomeState.renameFace?.let { p ->
        InputDialog("给人物命名", p.name, "名字", "保存", onDismiss = { HomeState.renameFace = null }) { v ->
            HomeState.renameFace = null; Dav.current?.let { Faces.lib(it.id).rename(p, v) }
        }
    }
    HomeState.renamePerson?.let { p ->
        val ctx = LocalContext.current
        InputDialog("给人物命名", p.name, "名字", "保存", onDismiss = { HomeState.renamePerson = null }) { v ->
            HomeState.renamePerson = null
            Ops.launch { runCatching { Repo.renamePerson(p.id, v.trim()); HomeState.persons = Repo.persons() }.onSuccess { toast(ctx, "已命名") }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
}

private fun onTabTap(i: Int, sel: Boolean) {
    val now = System.currentTimeMillis()
    // double tap on the tab you are on: back to the top
    if (sel && ScrollTop.lastTab == i && now - ScrollTop.lastAt < 450) ScrollTop.tick++
    ScrollTop.lastTab = i; ScrollTop.lastAt = now
    HomeState.tab = if (i == 0 && HomeState.tab == 2) 2 else i
}

/** Window ≥ 600 dp: the five tabs move into a NavigationRail on the left. */
@Composable
private fun SideRail() {
    NavigationRail(containerColor = C.Bg, modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        Spacer(Modifier.height(12.dp))
        tabs.forEach { t ->
            val sel = HomeState.tab == t.index || (t.index == 0 && HomeState.tab == 2)
            NavigationRailItem(selected = sel, onClick = { onTabTap(t.index, sel) },
                icon = { Icon(t.icon(sel), null, modifier = Modifier.size(23.dp)) },
                label = { Text(t.title, fontSize = 11.sp) },
                colors = NavigationRailItemDefaults.colors(selectedIconColor = C.Accent, selectedTextColor = C.Accent, unselectedIconColor = C.Sub,
                    unselectedTextColor = C.Sub, indicatorColor = C.Accent.copy(alpha = 0.16f)))
        }
    }
}

@Composable
private fun BottomBar(modifier: Modifier) {
    if (Selection.active) return
    Row(
        modifier.fillMaxWidth().background(C.Bg.copy(alpha = 0.97f)).navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { t ->
            val i = t.index
            val sel = HomeState.tab == i || (i == 0 && HomeState.tab == 2)
            Column(Modifier.clickable(remember { MutableInteractionSource() }, null) { onTabTap(i, sel) }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(52.dp).height(32.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) C.Accent.copy(alpha = 0.16f) else Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(t.icon(sel), null, tint = if (sel) C.Accent else C.Sub, modifier = Modifier.size(23.dp))
                }
                Text(t.title, fontSize = 11.sp, color = if (sel) C.Accent else C.Sub, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** One source = one 飞牛 account or one WebDAV account. */
data class Src(val kind: String, val id: String, val title: String, val sub: String)

fun allSources(): List<Src> =
    NasAccounts.list.map { Src("fn", it.id, it.title, "飞牛 · ${it.user}") } +
        Dav.accounts.map { Src("dav", it.id, it.title, "WebDAV · " + it.url.removePrefix("https://").removePrefix("http://").take(28)) }

fun currentSource(): Src? =
    if (HomeState.source == "dav") Dav.current?.let { Src("dav", it.id, it.title, "") }
    else NasAccounts.current?.let { Src("fn", it.id, it.title, "") }

/** Switches the whole app to [s]: photos, videos, albums, AI and settings all follow it. */
fun switchSource(ctx: android.content.Context, s: Src) {
    val cur = currentSource()
    if (cur?.kind == s.kind && cur.id == s.id) return
    Selection.clear(); SearchState.query = ""; SearchState.reset()
    if (s.kind == "fn") {
        if (NasAccounts.currentId != s.id) { HomeState.stash(NasAccounts.currentId); NasAccounts.switchTo(s.id); HomeState.restore(s.id) }
        HomeState.useSource("fn")
    } else {
        if (HomeState.source == "fn") HomeState.stash(NasAccounts.currentId)
        Dav.select(s.id); HomeState.useSource("dav")
    }
    HomeState.switching = true
    toast(ctx, "已切换到 ${s.title}")
}

/** Source capsule (tap: dropdown of every 飞牛 / WebDAV account) + date filter + search, at the top of every home tab. */
@Composable
fun TopSwitch(extra: @Composable RowScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val cur = currentSource()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(C.Surface).clickable { open = true }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (cur?.kind == "fn") {
                    val ok = Endpoint.online && Endpoint.latency >= 0
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) C.Green else C.Danger))
                } else Icon(LI.drive(), null, tint = C.Gold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(cur?.title ?: "添加来源", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                Icon(LI.down(), null, tint = C.Sub, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Surface).widthIn(min = 230.dp)) {
                val all = allSources()
                listOf("fn" to "飞牛", "dav" to "WebDAV").forEach { (k, label) ->
                    val group = all.filter { it.kind == k }
                    if (group.isEmpty()) return@forEach
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                    group.forEach { s ->
                        val on = cur?.kind == s.kind && cur.id == s.id
                        DropdownMenuItem(
                            leadingIcon = { Box(Modifier.width(20.dp)) { if (on) Icon(Icons.Rounded.Check, null, tint = C.Accent, modifier = Modifier.size(18.dp)) } },
                            text = { Column {
                                Text(s.title, color = C.Text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                Text(s.sub, color = C.Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } },
                            onClick = { open = false; switchSource(ctx, s) })
                    }
                }
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 4.dp))
                if (cur?.kind == "fn") DropdownMenuItem(leadingIcon = { Icon(LI.nas(), null, tint = C.Sub, modifier = Modifier.size(18.dp)) },
                    text = { Text("连接与账号设置", color = C.Text, fontSize = 14.sp) }, onClick = { open = false; HomeState.showConn = true })
                DropdownMenuItem(leadingIcon = { Icon(LI.plus(), null, tint = C.Sub, modifier = Modifier.size(18.dp)) },
                    text = { Text("添加飞牛账号", color = C.Text, fontSize = 14.sp) }, onClick = { open = false; Nav.push(Route.Login) })
                DropdownMenuItem(leadingIcon = { Icon(LI.drive(), null, tint = C.Sub, modifier = Modifier.size(18.dp)) },
                    text = { Text("添加 / 管理 WebDAV", color = C.Text, fontSize = 14.sp) }, onClick = { open = false; Nav.push(Route.DavAccounts) })
            }
        }
        Spacer(Modifier.weight(1f))
        extra()
        IconButton(onClick = { HomeState.showDate = true }) { Icon(LI.calendar(), null, tint = C.Text, modifier = Modifier.size(22.dp)) }
        IconButton(onClick = { Nav.push(Route.Search) }) { Icon(LI.search(), null, tint = C.Text, modifier = Modifier.size(23.dp)) }
    }
}

/** Old second-row capsules: the source capsule above now does both jobs. Kept as no-ops so the tabs need no other change. */
@Composable
fun ConnCapsule() {}

@Composable
fun DavCapsule() {}

@Composable
private fun BigTitle(title: String, sub: String) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.5).sp)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun monthTitle(day: String?): Pair<String, String> {
    if (day == null || day.length < 7) return "照片" to ""
    val y = day.take(4); val m = day.substring(5, 7).trimStart('0')
    return "${m}月" to "${y}年"
}

/** Month of the first visible photo. */
@Composable
private fun visibleDay(entries: List<Entry>, grid: LazyGridState, offset: Int): String? {
    val idx by remember(entries) { derivedStateOf { (grid.firstVisibleItemIndex - offset).coerceAtLeast(0) } }
    return remember(idx, entries) {
        (idx until minOf(entries.size, idx + 20)).firstNotNullOfOrNull { (entries[it] as? Entry.Cell)?.photo?.day }
            ?: entries.firstNotNullOfOrNull { (it as? Entry.Cell)?.photo?.day }
    }
}

@Composable
private fun CompactBar(title: String) {
    Row(Modifier.fillMaxWidth().background(C.Bg.copy(alpha = 0.96f)).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.weight(1f))
        Icon(LI.search(), null, tint = C.Text, modifier = Modifier.size(22.dp).clickable { Nav.push(Route.Search) })
    }
}

// ============================================================ photos (NAS)

@Composable
private fun PhotosTab() {
    val tl = HomeState.timeline
    val scope = rememberCoroutineScope()
    val grid = rememberMemGrid("fn-photos-" + NasAccounts.currentId)
    LaunchedEffect(tl) { if (tl.photos.isEmpty() && !tl.loading) tl.refresh() }
    var level by remember { mutableStateOf(Level.values()[Store.timelineLevel.coerceIn(0, 4)].let { if (it == Level.YEAR) Level.DAY4 else it }) }
    fun setLevel(l: Level) { level = l; Store.timelineLevel = l.ordinal }
    fun pinch(zoomIn: Boolean) { val i = level.ordinal + if (zoomIn) 1 else -1; if (i in 0..4) setLevel(Level.values()[i]) }
    val baseCols = if (level == Level.YEAR) 1 else if (level.ordinal >= 2) HomeState.columns.coerceIn(3, 5).let { if (level == Level.DAY5) 5 else if (level == Level.DAY3) 3 else it } else level.cols
    val cols = if (level == Level.YEAR) 1 else adaptiveCols(baseCols)
    val stills by remember { derivedStateOf { MediaIsolation.version; Hidden.visible(MediaIsolation.fnVisible(tl.photos.filter { !it.isVideo })) } }
    var memories by remember { mutableStateOf<List<MemoryGroup>>(emptyList()) }
    LaunchedEffect(tl.days) { memories = OnThisDay.loadFn(tl, NasAccounts.currentId) }
    LaunchedEffect(memories, stills.isNotEmpty()) { if (stills.isNotEmpty()) OnThisDay.feedWidget("fn:" + NasAccounts.currentId, memories) { stills } }
    val entries by remember(level) { derivedStateOf { buildEntriesBy(stills, level, true) } }
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val (pc, vc) = tl.stats
    val sub = listOf(yt, if (pc > 0) "${n(pc)} 张照片" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    Box(Modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        when {
            tl.photos.isEmpty() && tl.loading -> Column { TopSwitch(); ConnCapsule(); SkeletonGrid(4, 20.dp) }
            tl.photos.isEmpty() && tl.error != null -> Column { TopSwitch(); ConnCapsule(); Box(Modifier.weight(1f)) { StateMessage(LI.nas(), "连不上飞牛", tl.error!!, "重试") { scope.launch { runCatching { Endpoint.autoSelect() }; tl.refresh() } } } }
            tl.photos.isEmpty() && tl.done -> Column { TopSwitch(); ConnCapsule(); Box(Modifier.weight(1f)) { StateMessage(LI.photos(), "还没有照片", "在飞牛相册里添加照片文件夹后，这里会自动出现。") } }
            level == Level.YEAR -> {
                val cards = remember(tl.months, tl.photos.size) {
                    tl.months.groupBy { it.first.take(4).toInt() }.map { (y, ms) -> YearCard(y, ms.sumOf { it.second }, tl.photos.firstOrNull { it.day.startsWith("$y") }) }.sortedByDescending { it.year }
                }
                YearGrid(cards, statusTop + 60.dp, onPick = { y ->
                    setLevel(Level.MONTH)
                    scope.launch {
                        tl.ensureLoaded("%04d".format(y))
                        val idx = buildEntriesBy(tl.photos.filter { !it.isVideo }, Level.MONTH, true).indexOfFirst { it is Entry.Cell && it.photo.day.startsWith("$y") }
                        if (idx >= 0) grid.scrollToItem(idx + 1)
                    }
                }, onPinch = ::pinch)
                CompactBar("按年")
            }
            else -> {
                PhotoGrid(
                    entries, cols, tl.loading, onEnd = { scope.launch { tl.next() } },
                    onOpen = { Nav.push(Route.Viewer(stills, it)) }, state = grid, top = 0.dp,
                    header = { Column { TopSwitch(); PhotoModeSwitch(); BigTitle(mt, sub); MemoriesRow(memories) } },
                    onPinch = ::pinch,
                    onHeaderClick = { h -> if (level == Level.MONTH) {
                        val first = entries.indexOf(h)
                        val d = (entries.getOrNull(first + 1) as? Entry.Cell)?.photo?.day
                        setLevel(Level.DAY4)
                        if (d != null) scope.launch {
                            val target = buildEntriesBy(tl.photos.filter { !it.isVideo }, Level.DAY4, true).indexOfFirst { it is Entry.Cell && it.photo.day.take(7) == d.take(7) }
                            if (target >= 0) grid.scrollToItem(target + 1)
                        }
                    } },
                )
                AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) { CompactBar("$mt · $yt".trim(' ', '·')) }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { tl.refresh() } })
    }
}

// ============================================================ photos (WebDAV)

@Composable
private fun DavPhotosTab() {
    val lib = Dav.lib()
    val ctx = LocalContext.current
    if (lib == null) {
        Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "还没有 WebDAV", "添加一个 WebDAV 账户，照片按拍摄时间排好。", "添加") { Nav.push(Route.DavAccounts) } } }
        return
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(lib) { lib.ensure(); if (!ScanPolicy.paused) { Analyzer.start(ctx, lib, false); if (AiRunner.on) AiRunner.startDav(ctx, lib) } }
    val grid = rememberMemGrid("dav-photos-" + lib.accountId)
    var level by remember { mutableStateOf(Level.DAY4) }
    fun pinch(zoomIn: Boolean) { val i = level.ordinal + if (zoomIn) 1 else -1; if (i in 1..4) level = Level.values()[i] }
    val stills by remember(lib) { derivedStateOf { MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { !it.isVideo })) } }
    val memories by remember(lib) { derivedStateOf { lib.metaVersion; OnThisDay.from(stills) } }
    LaunchedEffect(memories, stills.isNotEmpty()) { if (stills.isNotEmpty()) OnThisDay.feedWidget("dav:" + lib.accountId, memories) { stills } }
    val entries by remember(lib, level) { derivedStateOf { lib.metaVersion; buildEntriesBy(stills, level, true) } }
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val sub = listOf(yt, "${n(stills.size)} 张照片", ScanStatus.listing(lib)?.let { "正在扫描 ${it.done}/${it.total} 个文件夹" } ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
    Box(Modifier.fillMaxSize()) {
        when {
            lib.photos.isEmpty() && lib.scanning -> Column { TopSwitch(); DavCapsule(); SkeletonGrid(4, 20.dp) }
            lib.photos.isEmpty() && lib.error != null -> Column { TopSwitch(); DavCapsule(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "连不上 WebDAV", lib.error!!, "重试") { scope.launch { lib.scan() } } } }
            stills.isEmpty() -> Column { TopSwitch(); PhotoModeSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.photos(), "没有找到照片", "把照片放进这个 WebDAV 后下拉刷新。", "重新扫描") { scope.launch { lib.scan(full = true) } } } }
            else -> {
                PhotoGrid(entries, adaptiveCols(level.cols.coerceAtMost(7)), lib.scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(stills, it)) }, state = grid, top = 0.dp,
                    header = { Column { TopSwitch(); PhotoModeSwitch(); BigTitle(mt, sub); MemoriesRow(memories) } }, onPinch = ::pinch)
                AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) { CompactBar("$mt · $yt".trim(' ', '·')) }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { lib.scan() } })
    }
}

// ============================================================ albums

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsTab(dav: Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    var renaming by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    val lib = if (dav) Dav.lib() else null
    fun load() = scope.launch {
        err = null
        if (dav) { lib?.refreshAlbums(); lib?.refreshFolders() }
        else runCatching { Repo.albums() }.onSuccess { HomeState.albums = it }.onFailure { err = it.message }
    }
    LaunchedEffect(dav, lib) { if (dav) { lib?.ensure() } else if (HomeState.albums == null) load() }
    val grid = rememberMemGrid(if (dav) "dav-albums-${lib?.accountId}" else "fn-albums-${NasAccounts.currentId}")
    Column(Modifier.fillMaxSize()) {
        TopSwitch { IconButton(onClick = { creating = true }) { Icon(LI.plus(), null, tint = C.Text) } }
        PhotoModeSwitch()
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "top") {
                Text("相册", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            }
            if (dav) {
                if (lib == null) item(span = { GridItemSpan(maxLineSpan) }) { Text("还没有 WebDAV 账户", color = C.Sub) }
                else {
                    val folders = lib.folders
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fh") { SectionTitle("文件夹", folders?.let { "${it.size} 个" } ?: "") }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fl") {
                        if (folders == null) Box(Modifier.height(90.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(shimmerBrush()))
                        else if (folders.isEmpty()) Text("根目录下没有文件夹", style = MaterialTheme.typography.bodySmall)
                        else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(folders, key = { it.path }) { f ->
                                Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).clickable { Nav.push(Route.CloudBrowse(lib.accountId, f.path, f.name)) }.padding(12.dp)) {
                                    Icon(LI.folder(), null, tint = C.Gold, modifier = Modifier.size(26.dp))
                                    Text(f.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                                }
                            }
                        }
                    }
                    item(key = "dhidden") {
                        AlbumTile("隐藏相册", "需验证指纹查看", null, icon = LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) }
                    }
                    val als = lib.albums
                    item(span = { GridItemSpan(maxLineSpan) }, key = "ah") { SectionTitle("我的相册", als?.let { "${it.size} 个 · 存在 WebDAV 根目录清单里" } ?: "") }
                    if (als == null) items(2) { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
                    else if (als.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("还没有相册。点右上角 ＋ 新建，或在照片多选后「加入相册」。照片不会被移动。", style = MaterialTheme.typography.bodySmall)
                    }
                    else items(als.size, key = { "v" + als[it].id }) { i ->
                        val a = als[i]
                        val cover = (lib.photoAt(a.cover) ?: lib.photosOf(a).firstOrNull())?.thumbM
                        AlbumTile(a.name, "${a.items.size} 项", cover, onLong = { menuFor = AlbumRef(a.id, lib.accountId) to a.name }) { openDavAlbum(lib, a) }
                    }
                }
            } else {
                item(key = "fav") {
                    AlbumTile("收藏", "你标记喜欢的瞬间", null, icon = LI.heart()) {
                        Nav.push(Route.Collection("收藏", "", PhotoSource { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, collect = true) }))
                    }
                }
                item(key = "hidden") {
                    AlbumTile("隐藏相册", "需验证指纹查看", null, icon = LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) }
                }
                item(key = "recent") {
                    AlbumTile("最近添加", "最新进入相册的照片", null, icon = LI.clock()) {
                        Nav.push(Route.Collection("最近添加", "", PhotoSource(grouped = false) { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, mode = "createdAt") }))
                    }
                }
                val albums = HomeState.albums
                if (albums == null && err != null) item(span = { GridItemSpan(maxLineSpan) }) { StateMessage(LI.albums(), "相册加载失败", err!!, "重试") { load() } }
                else if (albums == null) items(4) { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
                else {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("我的相册", "${albums.size} 个 · 长按管理") }
                    items(albums.size, key = { "a" + albums[it].id }) { i ->
                        val a = albums[i]
                        AlbumTile(a.name, listOf(if (a.photos > 0) "${n(a.photos)} 张" else "", if (a.videos > 0) "${n(a.videos)} 个视频" else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "空相册" }, a.poster,
                            onLong = { menuFor = AlbumRef(a.id.toString(), null) to a.name }) { openAlbum(a) }
                    }
                }
            }
        }
    }
    if (creating) InputDialog("新建相册", "", "相册名称", "创建", onDismiss = { creating = false }) { name ->
        creating = false
        Ops.launch {
            runCatching { if (dav) { lib?.createAlbum(name) ?: error("没有 WebDAV 账户") } else { NasX.createAlbum(name); HomeState.albums = Repo.albums() } }
                .onSuccess { toast(ctx, "已创建「$name」") }.onFailure { toast(ctx, it.message ?: "创建失败") }
        }
    }
    menuFor?.let { m ->
        AlertDialog(onDismissRequest = { menuFor = null }, containerColor = C.Surface, title = { Text(m.second, color = C.Text) }, text = {
            Column {
                Text("重命名", color = C.Text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { menuFor = null; renaming = m }.padding(vertical = 12.dp))
                Text("删除相册", color = C.Danger, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { menuFor = null; deleting = m }.padding(vertical = 12.dp))
                Text("打开相册后多选照片可「设为封面」「移出相册」。", color = C.Sub, fontSize = 12.sp)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { menuFor = null }) { Text("取消", color = C.Sub) } })
    }
    renaming?.let { m ->
        InputDialog("重命名相册", m.second, "相册名称", onDismiss = { renaming = null }) { name ->
            renaming = null
            Ops.launch { runCatching { AlbumOps.rename(m.first, name); if (!dav) HomeState.albums = Repo.albums() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
    deleting?.let { m ->
        ConfirmDialog("删除相册「${m.second}」？", "只删除相册，照片本身不会被删除。", "删除", onDismiss = { deleting = null }) {
            deleting = null
            Ops.launch { runCatching { AlbumOps.delete(m.first); if (!dav) HomeState.albums = Repo.albums() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(title: String, sub: String, poster: String?, icon: ImageVector? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.pressScale().combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLong, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(if (icon != null) C.Accent.copy(alpha = 0.12f) else C.Surface)) {
            if (poster != null) NetImage(poster, Modifier.fillMaxSize())
            else Icon(icon ?: LI.albums(), null, tint = if (icon != null) C.Accent else C.Faint, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, start = 2.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 2.dp, start = 2.dp))
    }
}

// ============================================================ discover (NAS)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverTab() {
    val ctx = LocalContext.current
    var menuPerson by remember { mutableStateOf<Person?>(null) }
    var menuPlace by remember { mutableStateOf<Place?>(null) }
    menuPerson?.let { p ->
        GroupActions(p.name.ifEmpty { "未命名人物" }, "${p.count} 张照片", onDismiss = { menuPerson = null }, actions = listOf(
            "命名" to { menuPerson = null; HomeState.renamePerson = p },
            "隐藏（路人）" to { menuPerson = null; FnPersonHide.set(p.id, true); toast(ctx, "已在本机隐藏") },
            "创建相册" to { menuPerson = null; makeNasAlbum(ctx, p.name.ifEmpty { "人物" }) { o, l -> Repo.personPhotos(p.id, o, l) } },
        ))
    }
    menuPlace?.let { pl ->
        GroupActions(pl.city.ifEmpty { pl.country }, "${pl.count} 张照片", onDismiss = { menuPlace = null }, actions = listOf(
            "创建相册" to { menuPlace = null; makeNasAlbum(ctx, pl.city.ifEmpty { pl.country }) { o, l -> Repo.placePhotos(pl, o, l) } },
        ))
    }
    LaunchedEffect(Unit) { if (HomeState.places == null) runCatching { Repo.places() }.onSuccess { HomeState.places = it }.onFailure { HomeState.places = emptyList() } }
    LaunchedEffect(Unit) { if (HomeState.persons == null) runCatching { Repo.persons() }.onSuccess { HomeState.persons = it }.onFailure { HomeState.persons = emptyList() } }
    // 1.0.9: optional AI labelling of NAS photos runs alongside, same ScanPolicy rules (waits instead of quitting)
    // 1.0.2: 重复/相似 detection belongs to 整理 — ask 飞牛 to re-check at most once every 12 h (server-side, incremental)
    LaunchedEffect(Unit) {
        val k = "fn.repeatCheck." + NasAccounts.currentId
        if (!ScanPolicy.paused && System.currentTimeMillis() - (Store.getStr(k).toLongOrNull() ?: 0L) > 12 * 3600_000L)
            runCatching { NasX.repeatCheck(true) }.onSuccess { Store.putStr(k, System.currentTimeMillis().toString()) }
    }
    LaunchedEffect(HomeState.timeline.photos.size, AiConfig.nasToo) { if (AiConfig.nasToo && AiRunner.on && !ScanPolicy.paused) AiRunner.startNas(ctx, HomeState.timeline.photos.toList()) }
    val list = rememberMemList("fn-discover")
    val places = HomeState.places
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 120.dp)) {
        item { TopSwitch() }
        item { BigTitle("AI", "") }
        item { SearchBar() }
        val hiddenIds = FnPersonHide.version.let { FnPersonHide.ids() }
        val ps = HomeState.persons.orEmpty().filter { it.id !in hiddenIds }
        if (ps.isNotEmpty() || hiddenIds.isNotEmpty()) {
            item { RowHeader("人物", "${ps.size} 位", extra = if (hiddenIds.isNotEmpty()) ("已隐藏 ${hiddenIds.size}" to { Nav.push(Route.X(ExtScreen.FnHiddenPersons)) }) else null) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(ps, key = { it.id }) { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).combinedClickable(onLongClick = { menuPerson = p }) { openPerson(p) }) {
                            Box(Modifier.size(68.dp).clip(CircleShape).background(C.Surface2)) { NetImage(FnClient.abs("/p/api/v1/stream/face/${p.faceId}"), Modifier.fillMaxSize()) }
                            Text(p.name.ifEmpty { "未命名" }, fontSize = 12.sp, color = C.Sub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        item { RowHeader("地点", places?.let { "${it.size} 处" } ?: "", "地图") { Nav.push(Route.X(ExtScreen.Map(false))) } }
        item {
            when {
                places == null -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(3) { Box(Modifier.size(150.dp, 180.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush())) }
                }
                places.isEmpty() -> Text("照片里还没有地点信息。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(places) { pl ->
                        Box(Modifier.size(150.dp, 180.dp).pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).combinedClickable(onLongClick = { menuPlace = pl }) {
                            Nav.push(Route.Collection(pl.city.ifEmpty { pl.country }, pl.country, PhotoSource(pageSize = 200, grouped = true) { o, l -> Repo.placePhotos(pl, o, l) }))
                        }) {
                            NetImage(pl.poster, Modifier.fillMaxSize())
                            Box(Modifier.matchParentSize().background(C.Scrim))
                            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                Text(pl.city.ifEmpty { pl.country }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${pl.country} · ${n(pl.count)}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).padding(top = 10.dp)) { SectionTitle("整理") } }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.sparkle(), "智能分类", "风景、美食、文档…", Modifier.weight(1f)) { Nav.push(Route.Smart) }
                    ToolCard(LI.copy(), "重复照片", "找出并清理", Modifier.weight(1f)) { Nav.push(Route.Duplicates) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.map(), "地图", "按拍摄地点", Modifier.weight(1f)) { Nav.push(Route.X(ExtScreen.Map(false))) }
                    ToolCard(LI.folder(), "文件夹", "按 NAS 目录", Modifier.weight(1f)) { Nav.push(Route.Folders("", "文件夹")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.trash(), "回收站", "可以恢复", Modifier.weight(1f)) { Nav.push(Route.Recycle) }
                    ToolCard(LI.upload(), "上传", "传输队列", Modifier.weight(1f)) { Nav.push(Route.Transfers) }
                }
            }
        }
    }
}

@Composable
private fun SearchBar() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface)
            .clickable { Nav.push(Route.Search) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(LI.search(), null, tint = C.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("试试「海边日落」「去年的猫」", color = C.Faint, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!AiConfig.configured && HomeState.source == "dav") Text("需配置 AI", color = C.Gold, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Gold.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, sub: String, modifier: Modifier, covers: List<String?> = emptyList(), onClick: () -> Unit) {
    Column(modifier.pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        if (covers.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            covers.take(3).forEach { c -> Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(C.Surface2)) { if (c != null) NetImage(c, Modifier.fillMaxSize()) } }
            repeat(3 - covers.take(3).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// ============================================================ discover (WebDAV)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DavDiscoverTab() {
    val ctx = LocalContext.current
    val lib = Dav.lib()
    if (lib == null) { Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "还没有 WebDAV", "", "添加") { Nav.push(Route.DavAccounts) } } }; return }
    val fl = remember(lib) { Faces.lib(lib.accountId) }
    // Only new photos are processed: both runners return at once when everything is already done.
    LaunchedEffect(lib) {
        lib.ensure(); fl.cluster(lib)
        // 1.0.9: AI labelling starts together with analysis and faces; it waits for the ScanPolicy conditions by itself
        if (!ScanPolicy.paused) { Analyzer.start(ctx, lib, false); Faces.start(ctx, lib, false); if (AiRunner.on) AiRunner.startDav(ctx, lib) }
    }
    val v = lib.metaVersion
    val places = remember(v, lib.photos.size) { lib.places() }
    val cats = remember(v, lib.photos.size) { lib.aiCategories() }
    val analyzedN = remember(v, lib.photos.size) { lib.meta.values.count { it.done } }
    val aiN = remember(v, lib.photos.size) { lib.aiDone() }
    val imgs = lib.imagesCount()
    val faceTodo = remember(fl.done, fl.running, lib.photos.size) { lib.photos.count { !it.isVideo && it.cloudPath !in fl.scanned } }
    var menuPerson by remember { mutableStateOf<com.hark.shiguang.FacePerson?>(null) }
    var mergeFrom by remember { mutableStateOf<com.hark.shiguang.FacePerson?>(null) }
    var menuPlace by remember { mutableStateOf<Pair<String, List<Photo>>?>(null) }
    val list = rememberMemList("dav-discover-" + lib.accountId)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 120.dp)) {
        item { TopSwitch() }
        item { BigTitle("AI", "${n(imgs)} 张照片 · ${fl.persons.size} 位人物 · ${places.size} 个地点") }
        item { SearchBar() }

        // ---------------- 整理进度：一张卡说清楚三件事
        item {
            ScanStatusCard(
                rows = (if (lib.scanning) listOf(Triple("读取文件", lib.scanListed to maxOf(lib.scanKnown, lib.scanListed), true)) else emptyList()) + listOf(
                    Triple("本地分析", (if (lib.analyzing) lib.analyzed else analyzedN) to imgs, lib.analyzing),
                    Triple("人脸识别", (imgs - faceTodo).coerceAtLeast(0) to imgs, fl.running),
                ) + if (AiRunner.on) listOf(Triple(AiRunner.rowLabel, aiN to imgs, AiRunner.running)) else emptyList(),
                running = lib.scanning || lib.analyzing || fl.running || AiRunner.running,
                note = run {
                    val base = fl.status.ifEmpty { if (lib.analyzing || fl.running || AiRunner.running) "离开页面也会继续" else if (ScanPolicy.paused) "已暂停，点「立即继续」恢复" else ScanPolicy.waitingText() }
                    val ai = if (AiRunner.on && AiRunner.cardNote.isNotEmpty() && (aiN < imgs || AiPool.switched.isNotEmpty())) "AI 分类：" + AiRunner.cardNote else ""
                    if (ai.isEmpty()) base else if (lib.analyzing || fl.running) "$base\n$ai" else ai
                },
                onToggle = {
                    if (lib.analyzing || fl.running || AiRunner.running) { Analyzer.stop(); Faces.stop(); AiRunner.pause() }
                    else { ScanPolicy.paused = false; Analyzer.start(ctx, lib, true); Faces.start(ctx, lib, true); if (AiRunner.on) AiRunner.startDav(ctx, lib, manual = true) }
                },
                onSettings = { Nav.push(Route.Settings) },
            )
        }

        // ---------------- 人物
        item {
            RowHeader("人物", if (fl.persons.isNotEmpty()) "${fl.persons.size} 位" else "", "识别设置", onAction = { HomeState.showFaceCfg = true },
                extra = if (fl.hiddenPersons.isNotEmpty()) ("已隐藏 ${fl.hiddenPersons.size}" to { Nav.push(Route.X(ExtScreen.HiddenPersons(lib.accountId))) }) else null)
        }
        item {
            if (fl.persons.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(fl.persons, key = { it.key }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).combinedClickable(onLongClick = { menuPerson = p }) {
                        openDavPerson(lib.accountId, p)
                    }) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(C.Surface2)) { NetImage(fl.cropFile(p.cover).absolutePath, Modifier.fillMaxSize()) }
                        Text(p.name.ifEmpty { "未命名" }, fontSize = 13.sp, color = if (p.name.isEmpty()) C.Sub else C.Text, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                        Text("${p.photos.size} 张", fontSize = 11.sp, color = C.Faint)
                    }
                }
            } else Text(if (fl.scanned.isEmpty()) "识别后按人归类，长按人物可以命名或建相册。" else "已识别 ${n(fl.scanned.size)} 张，还没有人达到 ${FaceConfig.minPhotos} 张，可以在识别设置里调低。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp))
            Text("人脸只在本机识别，不会上传。长按人物可命名或建相册。", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
        }

        // ---------------- 地点
        if (places.isNotEmpty()) {
            item { RowHeader("地点", "${places.size} 处", "地图") { Nav.push(Route.X(ExtScreen.Map(true))) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(places, key = { it.first }) { pl ->
                        CoverCard(pl.first, "${n(pl.second.size)} 张", pl.second.firstOrNull()?.thumbM, onLong = { menuPlace = pl }) { openGroup(pl.first, "地点", pl.second) }
                    }
                }
            }
        }

        // ---------------- AI 分类
        item { RowHeader("AI 分类", if (cats.isNotEmpty()) "${cats.size} 类" else "", if (AiConfig.configured) "AI 设置" else null) { Nav.push(Route.AiSettings) } }
        item {
            if (cats.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cats, key = { it.first }) { (c, ps) -> CoverCard(c, "${n(ps.size)} 张", ps.firstOrNull()?.thumbM, small = true) { openGroup(c, "AI 分类", ps) } }
            }
            else InfoRow(LI.sparkle(), if (AiConfig.configured) "等待 AI 整理" else "接入大模型后自动分类", if (AiConfig.configured) (AiRunner.note.ifEmpty { AiRunner.status }.ifEmpty { "按设置的网络和充电条件开始" }) else "风景、美食、人像、文档…",
                null, if (AiRunner.on) "立即整理" else "去配置") { if (!AiRunner.on) Nav.push(Route.AiSettings) else AiRunner.startDav(ctx, lib, manual = true) }
        }

        // ---------------- 清理
        item { RowHeader("清理空间") }
        item {
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface)) {
                var dupN by remember { mutableIntStateOf(0) }
                var simN by remember { mutableIntStateOf(0) }
                LaunchedEffect(v, lib.photos.size) {
                    kotlinx.coroutines.delay(1500) // debounce: metaVersion ticks every few seconds while analysing
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { lib.duplicates().size to lib.similar().size }.getOrNull() }
                    if (r != null) { dupN = r.first; simN = r.second }
                }
                val pend = if (analyzedN < imgs) " · 已分析 ${n(analyzedN)}/${n(imgs)}" else ""
                ToolRow(LI.copy(), "重复照片", (if (dupN > 0) "$dupN 组" else "完全相同的文件，保留一张") + pend) { Nav.push(Route.DavGroups("重复照片", lib.accountId, "dup")) }
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(start = 58.dp))
                ToolRow(LI.layers(), "相似照片", (if (simN > 0) "$simN 组" else "连拍、相近的几张，挑最好的留下") + pend) { Nav.push(Route.DavGroups("相似照片", lib.accountId, "sim")) }
            }
        }
    }
    menuPerson?.let { p ->
        GroupActions(p.name.ifEmpty { "未命名人物" }, "${p.photos.size} 张照片", onDismiss = { menuPerson = null }, actions = listOf(
            "命名" to { menuPerson = null; HomeState.renameFace = p },
            "合并到其他人物…" to { menuPerson = null; mergeFrom = p },
            "隐藏（路人）" to { menuPerson = null; fl.edits.hide(p, true); fl.cluster(lib); toast(ctx, "已隐藏，可在人物栏「已隐藏」里恢复") },
            "创建相册" to { menuPerson = null; makeDavAlbum(ctx, lib, p.name.ifEmpty { "人物" }, p.photos) },
        ))
    }
    mergeFrom?.let { p ->
        PersonPicker(fl, exclude = p.key, title = "把「${p.name.ifEmpty { "该人物" }}」合并到…", onDismiss = { mergeFrom = null }) { t ->
            mergeFrom = null
            if (t.name.isEmpty() && p.name.isNotEmpty()) fl.rename(t, p.name)
            fl.edits.merge(fl.persons.firstOrNull { it.key == t.key } ?: t, p); fl.cluster(lib); toast(ctx, "已合并")
        }
    }
    menuPlace?.let { pl ->
        GroupActions(pl.first, "${pl.second.size} 张照片", onDismiss = { menuPlace = null }, actions = listOf(
            "创建相册" to { menuPlace = null; makeDavAlbum(ctx, lib, pl.first, pl.second) },
        ))
    }
}

private fun makeDavAlbum(ctx: android.content.Context, lib: DavLib, name: String, photos: List<Photo>) {
    val existing = lib.albums.orEmpty().firstOrNull { it.name == name }
    Ops.launch {
        runCatching {
            if (existing != null) lib.addTo(existing.id, photos.map { it.cloudPath }) else lib.createAlbum(name, photos.map { it.cloudPath })
        }.onSuccess { toast(ctx, if (existing != null) "已把 ${photos.size} 张加入「$name」" else "已创建相册「$name」，${photos.size} 张") }
            .onFailure { toast(ctx, it.message ?: "创建失败") }
    }
}

/** 飞牛: page through a person / place and put every photo into a new NAS album. */
private fun makeNasAlbum(ctx: android.content.Context, name: String, load: suspend (Int, Int) -> Pair<List<Photo>, Boolean>) {
    toast(ctx, "正在创建相册「$name」…")
    Ops.launch {
        runCatching {
            val ids = ArrayList<Int>(); var off = 0; var guard = 0
            while (guard++ < 100) { val (l, more) = load(off, 200); ids.addAll(l.map { it.id }); off += l.size; if (!more || l.isEmpty()) break }
            val id = NasX.createAlbum(name)
            ids.chunked(200).forEach { NasX.addToAlbum(id, it, name) }
            HomeState.albums = null
            ids.size
        }.onSuccess { toast(ctx, "已创建相册「$name」，$it 张") }.onFailure { toast(ctx, it.message ?: "创建失败") }
    }
}

@Composable
private fun GroupActions(title: String, sub: String, onDismiss: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = {
        Column { Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(sub, color = C.Sub, fontSize = 13.sp) }
    }, text = {
        Column {
            actions.forEach { (label, act) ->
                Text(label, color = C.Text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { act() }.padding(vertical = 14.dp, horizontal = 4.dp))
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
}

@Composable
private fun ScanStatusCard(rows: List<Triple<String, Pair<Int, Int>, Boolean>>, running: Boolean, note: String, onToggle: () -> Unit, onSettings: () -> Unit) {
    val allDone = rows.all { it.second.second > 0 && it.second.first >= it.second.second }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (allDone) "全部整理完毕" else if (running) "正在整理" else "整理暂停", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (allDone) "有新照片加入时自动处理" else note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (!allDone) Text(if (running) "暂停" else "立即继续", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 7.dp))
            else Text("扫描设置", color = C.Sub, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSettings).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        if (!allDone) rows.forEach { (label, pr, on) ->
            val (d, t) = pr
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (on) C.Text else C.Sub, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f).background(if (on) C.Accent else C.Faint))
                }
                Text("${n(d)}/${n(t)}", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

private fun openGroup(name: String, sub: String, ps: List<Photo>) =
    Nav.push(Route.Collection(name, sub, PhotoSource(pageSize = 100000) { o, _ -> if (o > 0) emptyList<Photo>() to false else ps to false }))

@Composable
private fun RowHeader(title: String, trailing: String = "", action: String? = null, extra: Pair<String, () -> Unit>? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (trailing.isNotEmpty()) Text(trailing, color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        if (extra != null) Text(extra.first, color = C.Sub, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = extra.second).padding(horizontal = 8.dp, vertical = 4.dp))
        if (action != null) Text(action, color = C.Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CoverCard(title: String, sub: String, cover: String?, small: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val w = if (small) 120.dp else 150.dp; val h = if (small) 120.dp else 180.dp
    Box(Modifier.size(w, h).pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).combinedClickable(onLongClick = onLong, onClick = onClick)) {
        NetImage(cover, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(C.Scrim))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String, progress: Float?, action: String?, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (action != null) Text(action, color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = C.Accent, trackColor = C.Surface2,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Text("扫描", color = C.Accent, fontSize = 13.sp)
        Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

/** 飞牛官方的三个人脸参数。改完只重新归类，不用重新识别。 */
@Composable
fun FaceSettingsDialog(onDismiss: () -> Unit) {
    var conf by remember { mutableFloatStateOf(FaceConfig.conf) }
    var minP by remember { mutableFloatStateOf(FaceConfig.minPhotos.toFloat()) }
    var diff by remember { mutableFloatStateOf(FaceConfig.diff) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("人脸识别设置", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("人脸置信度阈值  %.2f".format(conf), color = C.Text, fontSize = 14.sp)
            Text("越高越只认清晰的正脸；侧脸、模糊、太小的会被忽略", style = MaterialTheme.typography.bodySmall)
            Slider(conf, { conf = (it * 100).toInt() / 100f }, valueRange = 0.3f..0.95f)
            Text("生成人物的最少照片数  ${minP.toInt()} 张", color = C.Text, fontSize = 14.sp)
            Text("一个人出现在这么多张照片里才生成人物", style = MaterialTheme.typography.bodySmall)
            Slider(minP, { minP = it }, valueRange = 1f..20f, steps = 18)
            Text("人物相似度差异阈值  %.2f".format(diff), color = C.Text, fontSize = 14.sp)
            Text("越小越严格：同一人容易被拆成几个；越大越宽松：不同人容易被并在一起", style = MaterialTheme.typography.bodySmall)
            Slider(diff, { diff = (it * 100).toInt() / 100f }, valueRange = 0.15f..0.6f)
            Text("恢复默认（0.65 · 5 张 · 0.36）", color = C.Accent, fontSize = 13.sp, modifier = Modifier.clickable { conf = FaceConfig.DEF_CONF; minP = FaceConfig.DEF_MIN.toFloat(); diff = FaceConfig.DEF_DIFF }.padding(vertical = 8.dp))
            Text("清除识别结果并重新识别", color = C.Danger, fontSize = 13.sp, modifier = Modifier.clickable { confirmClear = true }.padding(vertical = 8.dp))
        }
    }, confirmButton = {
        TextButton(onClick = {
            FaceConfig.conf = conf; FaceConfig.minPhotos = minP.toInt(); FaceConfig.diff = diff; FaceConfig.save()
            Dav.lib()?.let { Faces.lib(it.accountId).cluster(it) }
            onDismiss()
        }) { Text("保存并重新归类", color = C.Accent) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
    if (confirmClear) ConfirmDialog("清除人脸识别结果？", "人物和名字都会清空，之后重新识别。", "清除", onDismiss = { confirmClear = false }) {
        confirmClear = false; Faces.stop(); Dav.lib()?.let { Faces.lib(it.accountId).clear() }; onDismiss()
    }
}

@Composable
private fun GroupPicker(title: String, groups: List<Pair<String, List<Photo>>>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text(title, color = C.Text) }, text = {
        if (groups.isEmpty()) Text("还没有结果。", color = C.Sub)
        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(groups, key = { it.first }) { (name, ps) ->
                Row(Modifier.fillMaxWidth().clickable {
                    onDismiss(); Nav.push(Route.Collection(name, title, PhotoSource(pageSize = 100000) { o, _ -> if (o > 0) emptyList<Photo>() to false else ps to false }))
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) { NetImage(ps.firstOrNull()?.thumbM, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Text(name, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${ps.size}", color = C.Sub, fontSize = 13.sp)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", color = C.Sub) } })
}

// ============================================================ connection / accounts sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editLan by remember { mutableStateOf(false) }
    var editWan by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var speedLan by remember { mutableStateOf<Endpoint.Speed?>(null) }
    var speedWan by remember { mutableStateOf<Endpoint.Speed?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Column(Modifier.padding(bottom = 30.dp)) {
            Text("账号", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(NasAccounts.list, key = { it.id }) { a ->
                    val cur = a.id == NasAccounts.currentId
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                        if (!cur || HomeState.source != "fn") { switchSource(ctx, Src("fn", a.id, a.title, "")); onDismiss() }
                    }) {
                        Avatar(a.user, 52.dp, ring = cur)
                        Text(a.title, fontSize = 12.sp, color = if (cur) C.Text else C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable { onDismiss(); Nav.push(Route.Login) }) {
                        Box(Modifier.size(52.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape), contentAlignment = Alignment.Center) { Icon(LI.plus(), null, tint = C.Sub) }
                        Text("添加", fontSize = 12.sp, color = C.Sub, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            val a = NasAccounts.current
            if (a != null) {
                Section("连接 · ${a.title}")
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Bg.copy(alpha = 0.6f)).padding(4.dp)) {
                    listOf("auto" to "自动", "lan" to "仅内网", "wan" to "仅外网").forEach { (k, t) ->
                        val on = a.mode == k
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) C.Surface2 else Color.Transparent).clickable {
                            NasAccounts.update(a.copy(mode = k)); scope.launch { runCatching { Endpoint.autoSelect() }; HomeState.clear() }
                        }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(t, color = if (on) C.Text else C.Sub, fontSize = 14.sp) }
                    }
                }
                NavRow(LI.edit(), "重命名", a.title) { renaming = true }
                NavRow(LI.nas(), "内网地址", a.lanUrl.ifEmpty { "未设置 · 点按填写" } + if (Endpoint.active == "内网") "  · 使用中" else "") { editLan = true }
                NavRow(LI.swap(), "外网地址", a.wanUrl.ifEmpty { "未设置 · 点按填写" } + if (Endpoint.active == "外网") "  · 使用中" else "") { editWan = true }
                // 1.0.0: 内网 / 外网 speed test, result inline on each button
                val samples = remember(HomeState.source) { if (HomeState.source == "fn") HomeState.timeline.photos.asSequence().filter { !it.isVideo }.map { it.thumbM }.filter { it.isNotEmpty() }.take(24).toList() else emptyList() }
                listOf(true, false).forEach { isLan ->
                    val addr = if (isLan) a.lanUrl else a.wanUrl
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(if (isLan) "内网测速" else "外网测速", color = C.Text, fontSize = 15.sp)
                            Text(addr.ifEmpty { "未设置地址" }.substringAfter("://"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        TestButton("测速", Modifier.width(170.dp), resetKey = addr, height = 42.dp, holdMs = 6000, enabled = addr.isNotEmpty(),
                            okLabel = { it.substringAfter("下载 ", "").substringBefore("（").ifEmpty { it.substringBefore(" ·") } }) { prog ->
                            val r = Endpoint.speedTest(addr, samples, prog)
                            if (isLan) speedLan = r else speedWan = r
                            Endpoint.rememberFaster(speedLan, speedWan)
                            r.line()
                        }
                    }
                }
                NavRow(LI.clock(), "延迟", if (testing) "测速中…" else if (Endpoint.latency >= 0) "${Endpoint.latency} ms · ${Endpoint.active.ifEmpty { "当前地址" }} · 点按重测" else "连不上 · 点按重测") {
                    testing = true; scope.launch { runCatching { Endpoint.autoSelect() }; testing = false }
                }
                NavRow(LI.close(), "退出这个账号", a.user, danger = true) {
                    HomeState.forget(a.id); NasAccounts.remove(a.id); HomeState.clear(); onDismiss()
                    if (NasAccounts.current == null) { Store.clear(); FnClient.token = ""; Nav.reset(Route.Login) }
                }
            }
        }
    }
    val a = NasAccounts.current
    if (a != null && renaming) InputDialog("重命名账号", a.title, "比如：家里的飞牛", "保存", onDismiss = { renaming = false }) { v ->
        renaming = false; NasAccounts.update(a.copy(name = v.trim()))
    }
    if (a != null && (editLan || editWan)) {
        val lan = editLan
        InputDialog(if (lan) "内网地址" else "外网地址", if (lan) a.lanUrl else a.wanUrl, if (lan) "192.168.1.10:5666" else "nas.example.com 或 IP:端口", "保存", onDismiss = { editLan = false; editWan = false }) { v ->
            editLan = false; editWan = false
            scope.launch {
                val u = if (v.isBlank()) "" else runCatching { Endpoint.resolve(v) }.getOrDefault(v)
                NasAccounts.update(if (lan) a.copy(lanUrl = u) else a.copy(wanUrl = u))
                runCatching { Endpoint.autoSelect() }
                toast(ctx, "已保存")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DavSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Column(Modifier.padding(bottom = 30.dp)) {
            Text("WebDAV 账户", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
            Dav.accounts.forEach { a ->
                val cur = a.id == Dav.current?.id
                Row(Modifier.fillMaxWidth().clickable { switchSource(ctx, Src("dav", a.id, a.title, "")); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LI.drive(cur), null, tint = if (cur) C.Accent else C.Sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = C.Text, fontSize = 15.sp, fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal)
                        Text(a.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (cur) Text("当前", color = C.Accent, fontSize = 12.sp)
                }
            }
            NavRow(LI.settings(), "管理 WebDAV", "添加、编辑、移除") { onDismiss(); Nav.push(Route.DavAccounts) }
        }
    }
}

@Composable
fun LockOverlay(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.Bg).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(LI.lock(), null, tint = C.Accent, modifier = Modifier.size(48.dp))
            Text("一维相册已锁定", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            GradientButton("解锁", Modifier.padding(top = 24.dp).width(180.dp), onClick = onUnlock)
        }
    }
}
