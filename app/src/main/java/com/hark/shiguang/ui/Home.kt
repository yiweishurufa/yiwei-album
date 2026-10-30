package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/**
 * [index] is HomeState.tab; 相册 (2) has no button of its own, it lives inside 照片 behind the 时间线 | 相册 switch.
 * 1.1.0: 5 → 4 个 tab。「视频」并入照片页（全部 / 照片 / 视频 / 实况 筛选），「AI」改名「发现」；旧索引 1 由 HomeState.openTab 兼容。
 */
private data class Tab(val title: String, val index: Int, val icon: (Boolean) -> ImageVector)
private val tabs = listOf(
    Tab("照片", 0) { LI.photos(it) }, Tab("影音", 5) { LI.film(it) }, Tab("发现", 3) { LI.discover(it) }, Tab("设置", 4) { LI.settings(it) },
)

/** 1.1.0: 全部 / 照片 / 视频 / 实况 chips under the 时间线 | 相册 switch (timeline mode only). */
@Composable
internal fun PhotoFilterChips() {
    if (HomeState.tab != 0) return
    Row(Modifier.padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("全部", "照片", "视频", "实况").forEachIndexed { i, t ->
            val on = HomeState.photoFilter == i
            Text(t, color = if (on) C.Accent else C.Sub, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) C.Accent.copy(alpha = 0.14f) else C.Surface)
                    .clickable { HomeState.setFilter(i) }.heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

/** 时间线 | 相册 inside the 照片 tab. 全面审查 C4：改用共用的 [Segmented]，拟物风格下和影音页的分段一致。 */
@Composable
internal fun PhotoModeSwitch() {
    Box(Modifier.padding(start = 16.dp, top = 6.dp)) {
        Segmented(listOf("时间线", "相册"), if (HomeState.tab == 2) 1 else 0) { HomeState.tab = if (it == 1) 2 else 0 }
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
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // 审查 8：在影音 / 发现 / 设置 tab 按返回，先回到照片，再按一次才退出（首页上面盖着别的页面时不拦截）
    androidx.activity.compose.BackHandler(enabled = Nav.stack.size == 1 && HomeState.tab != 0 && HomeState.tab != 2 && !Selection.active) { HomeState.tab = 0 }
    Row(Modifier.fillMaxSize().appBackground()) {
    if (wide) SideRail()
    Box(Modifier.weight(1f).fillMaxHeight()) {
        if (HomeState.switching && HomeState.tab != 4) Column { TopSwitch(search = if (HomeState.tab == 5) SearchScope.None else SearchScope.Photos, showDate = HomeState.tab != 5); SkeletonGrid(4, 20.dp) }
        // 用户 2026-10-24 要求继续优化：切底部 tab 时各 tab 的 rememberSaveable 状态（设置页滚动、发现页展开的卡片）
        // 也按 tab 保存，切回来还在。照片网格 / 发现页列表的滚动另有 ScrollMem 按账号保存。
        else tabStates.SaveableStateProvider(homeTabKey(HomeState.tab, false, HomeState.source)) {
            when (HomeState.tab) {
                // 全面审查 C2：「视频」筛选并回时间线（月份标题、捏合缩放、列数都和全部 / 照片 / 实况一致）
                0 -> if (dav) DavPhotosTab() else PhotosTab()
                1 -> VideosTab(dav)   // only reachable for one frame before openTab() maps it; kept as a safe fallback
                2 -> AlbumsTab(dav)
                3 -> if (dav) DavDiscoverTab() else DiscoverTab()
                5 -> MoviesTab()
                else -> SettingsScreen()
            }
        }
        // 1.0.3 #4: music mini bar sits above the bottom navigation (at the bottom on wide screens)
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (!Selection.active) MiniPlayer(Modifier.padding(bottom = if (wide) 0.dp else 4.dp).then(if (wide) Modifier.navigationBarsPadding().padding(bottom = 10.dp) else Modifier))
            if (!wide) BottomBar(Modifier)
        }
    }
    }
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

/** 首页各 tab 的状态 key：tab + 照片页「视频」筛选 + 来源（飞牛 / WebDAV 的页面结构不同，不互相恢复）。 */
internal fun homeTabKey(tab: Int, videosInPhotos: Boolean, source: String): String =
    "tab$tab" + (if (tab == 0 && videosInPhotos) "v" else "") + "-" + source

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
        modifier.fillMaxWidth().skeuoBar().navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { t ->
            val i = t.index
            val sel = HomeState.tab == i || (i == 0 && HomeState.tab == 2)
            Column(Modifier.clickable(remember { MutableInteractionSource() }, null) { onTabTap(i, sel) }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 拟物：选中的标签像被按下去一样（内凹的小槽），其余平放
                Box(Modifier.width(52.dp).height(32.dp).then(if (sel && Skeuo.on) Modifier.skeuoInset(RoundedCornerShape(12.dp), C.Accent.copy(alpha = 0.14f))
                    else Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) C.Accent.copy(alpha = 0.16f) else Color.Transparent)), contentAlignment = Alignment.Center) {
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
    // 审查 7 / 10：高级搜索条件（相机型号、日期）和音乐搜索词都是按来源的，切换来源时清掉
    AdvFilter.reset(); MusicUi.query = ""
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

/** 审查 1/2/3/6：顶栏的放大镜搜什么。Photos = 照片搜索，Media = 影片搜索，None = 不显示（页内已有搜索框或与搜索无关）。 */
enum class SearchScope { Photos, Media, None }

/**
 * Source capsule (tap: dropdown of every 飞牛 / WebDAV account) + date filter + search, at the top of every home tab.
 * [showDate]「按日期筛选照片」只在照片 / 相册 / 发现保留；影音、音乐、设置不显示。
 */
@Composable
fun TopSwitch(search: SearchScope = SearchScope.Photos, showDate: Boolean = true, extra: @Composable RowScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val cur = currentSource()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.skeuoCard(RoundedCornerShape(18.dp), Elevation.pressed + 1.dp).clickable { open = true }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
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
                // Only source switching lives here: account management is the Settings header card
                // (飞牛 → 连接设置页, WebDAV → 账户管理页), so this menu can never offer a second path to it.
            }
        }
        Spacer(Modifier.weight(1f))
        extra()
        if (showDate) IconButton(onClick = { HomeState.showDate = true }) { Icon(LI.calendar(), "按日期查看照片", tint = C.Text, modifier = Modifier.size(22.dp)) }
        when (search) {
            SearchScope.Photos -> IconButton(onClick = { Nav.push(Route.Search) }) { Icon(LI.search(), "搜索照片", tint = C.Text, modifier = Modifier.size(23.dp)) }
            SearchScope.Media -> IconButton(onClick = { Nav.push(Route.MovieSearch) }) { Icon(LI.search(), "搜索影片", tint = C.Text, modifier = Modifier.size(23.dp)) }
            SearchScope.None -> Unit
        }
    }
}

@Composable
internal fun BigTitle(title: String, sub: String) {
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
    // 1.1.0: what the grid shows (全部 / 照片 / 实况); memories keep using stills
    val filter = HomeState.photoFilter
    val shown by remember(filter) { derivedStateOf { if (filter == 1) stills else { MediaIsolation.version; Hidden.visible(MediaIsolation.fnVisible(tl.photos.filter { HomeState.accepts(it, filter) })) } } }
    // C2：飞牛时间线按月分页加载；筛「视频 / 实况」时它们可能很稀疏，自动往后翻，直到够一屏或翻完
    LaunchedEffect(filter, tl) {
        if (filter == 0) return@LaunchedEffect
        snapshotFlow { Triple(shown.size, tl.loading, tl.done) }.collect { (n, loading, done) ->
            if (n < 60 && !loading && !done && tl.photos.isNotEmpty()) tl.next()
        }
    }
    var memories by remember { mutableStateOf<List<MemoryGroup>>(emptyList()) }
    LaunchedEffect(tl.days) { memories = OnThisDay.loadFn(tl, NasAccounts.currentId) }
    LaunchedEffect(memories, stills.isNotEmpty()) { if (stills.isNotEmpty()) OnThisDay.feedWidget("fn:" + NasAccounts.currentId, memories) { stills } }
    val entries by remember(level, filter) { derivedStateOf { buildEntriesBy(shown, level, true) } }
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val (pc, vc) = tl.stats
    val sub = listOf(yt, if (filter == 2) (if (vc > 0) "${n(vc)} 个视频" else "") else if (pc > 0) "${n(pc)} 张照片" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    Box(Modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        when {
            tl.photos.isEmpty() && tl.loading -> Column { TopSwitch(); SkeletonGrid(4, 20.dp) }
            tl.photos.isEmpty() && tl.error != null -> Column { TopSwitch(); Box(Modifier.weight(1f)) { ErrorState(tl.error, onRetry = { scope.launch { runCatching { Endpoint.autoSelect() }; tl.refresh() } }) } }
            tl.photos.isEmpty() && tl.done -> Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.photos(), "还没有照片", "在飞牛相册里添加照片文件夹后，这里会自动出现。") } }
            level == Level.YEAR -> {
                val cards = remember(tl.months, tl.photos.size) {
                    tl.months.groupBy { it.first.take(4).toInt() }.map { (y, ms) -> YearCard(y, ms.sumOf { it.second }, tl.photos.firstOrNull { it.day.startsWith("$y") }) }.sortedByDescending { it.year }
                }
                YearGrid(cards, statusTop + 60.dp, onPick = { y ->
                    setLevel(Level.MONTH)
                    scope.launch {
                        tl.ensureLoaded("%04d".format(y))
                        val idx = buildEntriesBy(tl.photos.filter { HomeState.accepts(it, filter) }, Level.MONTH, true).indexOfFirst { it is Entry.Cell && it.photo.day.startsWith("$y") }
                        if (idx >= 0) grid.scrollToItem(idx + 1)
                    }
                }, onPinch = ::pinch)
                CompactBar("按年")
            }
            else -> {
                PhotoGrid(
                    entries, cols, tl.loading, onEnd = { scope.launch { tl.next() } },
                    onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = 0.dp, bottom = 100.dp + miniPlayerPad,
                    header = { Column { TopSwitch(); PhotoModeSwitch(); PhotoFilterChips(); BigTitle(mt, sub); MemoriesRow(memories)
                        if (shown.isEmpty()) Text(if (tl.loading || !tl.done) "正在查找…" else if (filter == 2) "没有视频" else "没有实况照片",
                            style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp))
                    } },
                    onPinch = ::pinch,
                    onHeaderClick = { h -> if (level == Level.MONTH) {
                        val first = entries.indexOf(h)
                        val d = (entries.getOrNull(first + 1) as? Entry.Cell)?.photo?.day
                        setLevel(Level.DAY4)
                        if (d != null) scope.launch {
                            val target = buildEntriesBy(tl.photos.filter { HomeState.accepts(it, filter) }, Level.DAY4, true).indexOfFirst { it is Entry.Cell && it.photo.day.take(7) == d.take(7) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DavPhotosTab() {
    val lib = Dav.lib()
    val ctx = LocalContext.current
    if (lib == null) {
        Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "还没有 WebDAV", "添加一个 WebDAV 账户，照片按拍摄时间排好。", "添加") { Nav.push(Route.DavAccounts) } } }
        return
    }
    val scope = rememberCoroutineScope()
    // 第 4 批：照片页下拉刷新。以前 Dav.kt 注释和空态文案都写着「下拉刷新」，实际没有实现，
    // 只有「重新扫描」按钮（full 全量）。现在下拉触发 lib.scan() 增量扫描（默认增量，非 full）。
    val ptr = rememberPullToRefreshState()
    if (ptr.isRefreshing) {
        LaunchedEffect(ptr.isRefreshing) {
            runCatching { lib.scan() }
            ptr.endRefresh()
        }
    }
    LaunchedEffect(lib) { lib.ensure(); if (!ScanPolicy.paused) { Analyzer.start(ctx, lib, false); if (AiRunner.on) AiRunner.startDav(ctx, lib) } }
    val grid = rememberMemGrid("dav-photos-" + lib.accountId)
    var level by remember { mutableStateOf(Level.DAY4) }
    fun pinch(zoomIn: Boolean) { val i = level.ordinal + if (zoomIn) 1 else -1; if (i in 1..4) level = Level.values()[i] }
    val stills by remember(lib) { derivedStateOf { MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { !it.isVideo })) } }
    val filter = HomeState.photoFilter
    val shown by remember(lib, filter) { derivedStateOf { if (filter == 1) stills else { MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { HomeState.accepts(it, filter) })) } } }
    val memories by remember(lib) { derivedStateOf { lib.metaVersion; OnThisDay.from(stills) } }
    LaunchedEffect(memories, stills.isNotEmpty()) { if (stills.isNotEmpty()) OnThisDay.feedWidget("dav:" + lib.accountId, memories) { stills } }
    val entries by remember(lib, level, filter) { derivedStateOf { lib.metaVersion; buildEntriesBy(shown, level, true) } }
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val sub = listOf(yt, (if (filter == 1) "${n(stills.size)} 张照片" else "${n(shown.size)} 项"), ScanStatus.listing(lib)?.let { "正在扫描 ${it.done}/${it.total} 个文件夹" } ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
    Box(Modifier.fillMaxSize().then(if (!lib.scanning) Modifier.nestedScroll(ptr.nestedScrollConnection) else Modifier)) {
        when {
            lib.photos.isEmpty() && lib.scanning -> Column { TopSwitch(); SkeletonGrid(4, 20.dp) }
            lib.photos.isEmpty() && lib.error != null -> Column { TopSwitch(); Box(Modifier.weight(1f)) { ErrorState(lib.error, onRetry = { scope.launch { lib.scan() } }) } }
            shown.isEmpty() -> Column { TopSwitch(); PhotoModeSwitch(); PhotoFilterChips(); Box(Modifier.weight(1f)) {
                if (filter == 2) StateMessage(LI.videos(), "没有视频", "")
                else if (filter == 3 && stills.isNotEmpty()) StateMessage(LI.photos(), "没有实况照片", "iPhone 实况（照片 + 同名 MOV）和安卓动态照片分析完后会出现在这里。")
                else StateMessage(LI.photos(), "没有找到照片", "把照片放进这个 WebDAV 后下拉刷新。", "重新扫描") { scope.launch { lib.scan(full = true) } } } }
            else -> {
                PhotoGrid(entries, adaptiveCols(level.cols.coerceAtMost(7)), lib.scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = 0.dp, bottom = 100.dp + miniPlayerPad,
                    header = { Column { TopSwitch(); PhotoModeSwitch(); PhotoFilterChips(); BigTitle(mt, sub); MemoriesRow(memories) } }, onPinch = ::pinch)
                AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) { CompactBar("$mt · $yt".trim(' ', '·')) }
            }
        }
        PullToRefreshContainer(state = ptr, modifier = Modifier.align(Alignment.TopCenter))
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { lib.scan() } })
    }
}

@Composable
fun LockOverlay(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().appBackground().clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(LI.lock(), null, tint = C.Accent, modifier = Modifier.size(48.dp))
            Text("一维相册已锁定", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            GradientButton("解锁", Modifier.padding(top = 24.dp).width(180.dp), onClick = onUnlock)
        }
    }
}

