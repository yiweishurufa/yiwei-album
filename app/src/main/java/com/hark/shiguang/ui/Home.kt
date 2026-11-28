package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
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

/**
 * 点当前底栏 Tab：[tick] +1，当前页的列表 / 网格回到顶部（1.7.26 起单击即可，以前要双击）。
 * F8（1.7.26，iOS / Google 相册同款）：如果这一页本来就在顶部，就改为刷新——列表里谁需要滚动就把 [moved] 记成这次的 tick，
 * 下一帧 [PullRefreshBox] 看到这次没人滚动，就像下拉一样触发刷新（只有带下拉刷新的页面会刷新）。
 */
object ScrollTop {
    var tick by mutableIntStateOf(0)
    /** 最近一次「需要滚动」的 tick；等于当前 tick 说明这次是回顶部，不是刷新。 */
    @Volatile var moved = 0
}

/** Scrolls a list/grid to the top whenever the current tab is tapped again (or refreshes when already there, see [ScrollTop]). */
@Composable
fun ScrollToTopOnTab(grid: LazyGridState? = null, list: LazyListState? = null) {
    val t = ScrollTop.tick
    // 1.7.30：只响应「进入组合之后」的点按。以前用 t > 0 判断：点过一次当前 Tab 后，每次切回这个 Tab（Crossfade 重新组合）
    // 都会立刻回到顶部，记住的滚动位置丢了。
    val start = remember { t }
    LaunchedEffect(t) {
        if (t == 0 || t == start) return@LaunchedEffect
        val g = grid?.let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 } == true
        val l = list?.let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 } == true
        if (g || l) ScrollTop.moved = t
        if (g) grid?.animateScrollToItem(0)
        if (l) list?.animateScrollToItem(0)
    }
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
private data class Tab(val title: String, val index: Int, val icon: (Boolean) -> ImageVector, val filled: () -> ImageVector)
private val tabs = listOf(
    // 用户 2026-11-02：「影音」拆成「影视」「音乐」两个 tab（音乐 = 索引 6）
    // 1.6.0：Kubai 线性图标；选中 = 线性双色（外形内一层 22% 同色填充）
    // 1.7.0：选中态 = 实心感（FILL_ALPHA）+ 发光
    Tab("照片", 0, { KI.photos(it) }, { KI.photosFilled() }), Tab("影视", 5, { KI.film(it) }, { KI.filmFilled() }), Tab("音乐", 6, { KI.music(it) }, { KI.musicFilled() }),
    Tab("发现", 3, { KI.discover(it) }, { KI.discoverFilled() }), Tab("设置", 4, { KI.settings(it) }, { KI.settingsFilled() }),
)

/** 1.1.0: 全部 / 照片 / 视频 / 实况 chips under the 时间线 | 相册 switch (timeline mode only). */
@Composable
internal fun PhotoFilterChips() {
    if (HomeState.tab != 0) return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("全部", "照片", "视频", "实况").forEachIndexed { i, t ->
            ChoiceChip(t, HomeState.photoFilter == i) { HomeState.setFilter(i) }
        }
        Spacer(Modifier.weight(1f))
        // 第 6 批 沉浸视图第一步：网格 ↔ 单列大图流
        IconButton(onClick = { HomeState.useImmersive(!HomeState.immersive) }) {
            Icon(if (HomeState.immersive) KI.grid() else KI.feed(), if (HomeState.immersive) "切换到网格" else "切换到大图流", tint = C.Sub, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * 图库 | 合集 inside the 照片 tab（用户口述 3，参照飞牛官方相册；原「时间线 | 相册」，相册收进合集页）。
 * 全面审查 C4：用共用的 [Segmented]，拟物风格下和影音页的分段一致。
 */
@Composable
internal fun PhotoModeSwitch() {
    Box(Modifier.padding(start = 16.dp, top = 6.dp)) {
        Segmented(listOf("图库", "合集"), if (HomeState.tab == 2) 1 else 0) { HomeState.tab = if (it == 1) 2 else 0 }
    }
}

@Composable
fun HomeScreen() {
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
    // 1.7.0：首页内容录进 backdrop，浮在上面的玻璃（底栏、顶栏胶囊）模糊它（Glass.kt）
    val backdrop = remember { GlassBackdrop() }
    Row(Modifier.fillMaxSize().appBackground()) {
    if (wide) SideRail()
    Box(Modifier.weight(1f).fillMaxHeight()) {
        // 1.7.33：底栏（和迷你播放条）区域里的内容淡出，文字不再从玻璃后面透出来和图标叠在一起（宽屏没有底栏，不淡出）。
        // 淡出画在 glassSource 外面：真模糊的玻璃仍然模糊完整的内容（颜色照旧透上来），降级玻璃后面只剩背景。
        Box(Modifier.fillMaxSize().then(if (wide) Modifier else Modifier.bottomFade()).glassSource(backdrop)) {
        if (HomeState.switching && HomeState.tab != 4) Column { TopSwitch(search = if (HomeState.tab == 5 || HomeState.tab == 6) SearchScope.None else SearchScope.Photos, showDate = HomeState.tab != 5 && HomeState.tab != 6); SkeletonGrid(4, 20.dp) }
        // 用户 2026-10-24 要求继续优化：切底部 tab 时各 tab 的 rememberSaveable 状态（设置页滚动、发现页展开的卡片）
        // 也按 tab 保存，切回来还在。照片网格 / 发现页列表的滚动另有 ScrollMem 按账号保存。
        // 1.7.12：切 Tab 时新旧内容交叉淡入。1.7.32（用户 2026-11-28「底部从照片切换到视频动画有问题」）：
        // 以前两页各 220ms 同时淡入淡出，中间一段两页都是半透明叠在一起（照片网格透出影视海报，重影 + 发灰）。
        // 现在旧页 90ms 快速淡出、新页稍后 170ms 淡入（Motion.tabEnter / tabExit），几乎不再同时可见；移除动画时直接切换。
        // 各 Tab 的状态仍按 tab 保存（tabStates），切回来滚动位置、展开的卡片都在。
        else androidx.compose.animation.AnimatedContent(HomeState.tab to HomeState.source, transitionSpec = { Motion.tabEnter() togetherWith Motion.tabExit() }, label = "homeTab") { (tab, src) ->
          val dav = src == "dav"
          tabStates.SaveableStateProvider(homeTabKey(tab, false, src)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalHomeTab provides tab) {
            when (tab) {
                // 全面审查 C2：「视频」筛选并回时间线（月份标题、捏合缩放、列数都和全部 / 照片 / 实况一致）
                0 -> if (dav) DavPhotosTab() else PhotosTab()
                1 -> VideosTab(dav)   // only reachable for one frame before openTab() maps it; kept as a safe fallback
                2 -> CollectionsTab(dav)   // 用户口述 3：「合集」页（相册、人物、地点、分类、媒体类型…）
                3 -> if (dav) DavDiscoverTab() else DiscoverTab()
                5 -> MoviesTab()
                6 -> MusicTab()
                else -> SettingsScreen()
            }
            }
          }
        }
        }
        HomeTopCapsule(backdrop)
        // 1.0.3 #4: music mini bar sits above the bottom navigation (at the bottom on wide screens)
        Column(Modifier.align(Alignment.BottomCenter)) {
            // 1.6.6：迷你播放条从底栏上方滑入 / 滑出（多选时让位给多选栏）
            androidx.compose.animation.AnimatedVisibility(MusicState.active && !Selection.active,
                enter = androidx.compose.animation.slideInVertically(Motion.sheet()) { it } + androidx.compose.animation.fadeIn(Motion.enter()),
                exit = androidx.compose.animation.slideOutVertically(Motion.exit()) { it } + androidx.compose.animation.fadeOut(Motion.exit())) {
                MiniPlayer(Modifier.padding(bottom = if (wide) 0.dp else Space.xs + 2.dp).then(if (wide) Modifier.navigationBarsPadding().padding(bottom = 10.dp) else Modifier), backdrop)
            }
            if (!wide) BottomBar(Modifier, backdrop)
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
            Ops.launch { runCatching { Repo.renamePerson(p.id, v.trim()); HomeState.persons = Repo.persons() }.onSuccess { toast(ctx, "已命名") }.onFailure { failToast(ctx, it, "失败") } }
        }
    }
}

/**
 * 1.7.33：首页内容在悬浮底栏区域淡出（用户 2026-11-28 截图：列表最后一行文字从底栏后面透出来，和底栏图标重叠）。
 * 从底栏（+ 迷你播放条）上沿以上 20dp 开始，到底栏图标上沿（离底栏上沿 16dp）完全透明；再往下全透明。
 * 只是一层 DstIn 渐变蒙版（离屏合成一次），不做模糊。滚到底时最后一行在 [homeListBottom] 的留白里，不受影响。
 */
@Composable
private fun Modifier.bottomFade(): Modifier {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barTop = nav + 76.dp + miniPlayerPad   // 底栏 66 + 离底 10 + 导航条 + 迷你播放条
    val d = androidx.compose.ui.platform.LocalDensity.current
    val topPx = with(d) { (barTop + 20.dp).toPx() }
    val clearPx = with(d) { (barTop - 16.dp).toPx() }
    return this.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            drawRect(Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = h - topPx, endY = h - clearPx),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
}

/** 首页各 tab 的状态 key：tab + 照片页「视频」筛选 + 来源（飞牛 / WebDAV 的页面结构不同，不互相恢复）。 */
internal fun homeTabKey(tab: Int, videosInPhotos: Boolean, source: String): String =
    "tab$tab" + (if (tab == 0 && videosInPhotos) "v" else "") + "-" + source

private fun onTabTap(i: Int, sel: Boolean) {
    // 1.7.26：点你正在看的 Tab → 回到顶部；已经在顶部 → 刷新（见 ScrollTop）
    if (sel) ScrollTop.tick++
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
                icon = { Icon(t.icon(sel), null, modifier = Modifier.size(24.dp)) },
                label = { Text(t.title, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationRailItemDefaults.colors(selectedIconColor = C.Accent, selectedTextColor = C.Text, unselectedIconColor = C.Sub,
                    unselectedTextColor = C.Sub, indicatorColor = C.AccentSoft))
        }
    }
}

/**
 * 1.7.0 底栏 v2（docs/DESIGN.md §9）：悬浮的玻璃胶囊（离底 10dp + 导航条、左右 14dp），内容从下面透出来（API 31+ 真模糊）。
 * 选中项背后是一颗彩色玻璃气泡，切换时弹簧滑过去、移动中横向拉长（像液体）；选中图标换成实心感 + 背后一团光，
 * 图标上移、标签淡入。整格可点（≥ 48dp），读屏是「标签页」。
 */
@Composable
private fun BottomBar(modifier: Modifier, backdrop: GlassBackdrop) {
    if (Selection.active) return
    val view = androidx.compose.ui.platform.LocalView.current
    val selIdx = tabs.indexOfFirst { HomeState.tab == it.index || (it.index == 0 && HomeState.tab == 2) }.coerceAtLeast(0)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        GlassSurface(Modifier.fillMaxWidth().height(66.dp), shape = CircleShape, backdrop = backdrop, shadow = 18.dp, solid = true) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
                val w = maxWidth / tabs.size
                val x by animateDpAsState(w * selIdx, Motion.liquid(), label = "bubbleX")
                val stretch = 1f + (kotlin.math.abs((w * selIdx - x).value) / w.value).coerceAtMost(1f) * 0.34f
                val bubble = C.Accent.copy(alpha = if (C.P.dark) 0.24f else 0.16f)
                val rim = Glass.rimBrush(0.9f)
                Box(Modifier.offset(x = x).width(w).fillMaxHeight()
                    .graphicsLayer { scaleX = stretch; scaleY = 1f - (stretch - 1f) * 0.3f }
                    .clip(CircleShape).background(bubble)
                    .border(1.dp, rim, CircleShape))
                Row(Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { k, t ->
                        val sel = k == selIdx
                        // 1.7.32：图标上移 / 标签浮现改用几乎不过冲的弹簧（以前用玻璃气泡的弹性弹簧，标签和图标会上下多弹一下）
                        val lift by animateFloatAsState(if (sel) 1f else 0f, Motion.settle(), label = "tabLift")
                        val icon by androidx.compose.animation.animateColorAsState(if (sel) C.Accent else C.OnGlassSub, Motion.state(), label = "tabIcon")
                        // 1.7.26：按下图标微缩（跟手）。1.7.32：去掉「选中瞬间先跳到 0.8 再弹回」——snapTo 是一帧内的硬跳，
                        // 又和按压缩放（0.86）相乘，松手那一刻图标会突然缩到约 0.7 再弹大，看起来像闪了一下
                        val ix = remember { MutableInteractionSource() }
                        val pressed by ix.collectIsPressedAsState()
                        val press by animateFloatAsState(if (pressed) Motion.PRESS_SCALE_ICON else 1f, if (pressed) Motion.pressIn() else Motion.pressOut(), label = "tabPress")
                        Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .clickable(ix, null, role = androidx.compose.ui.semantics.Role.Tab) { if (!sel) Haptics.tick(view); onTabTap(t.index, sel) }
                            .semantics { selected = sel }, contentAlignment = Alignment.Center) {
                            val up = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
                            Box(Modifier.size(44.dp).graphicsLayer { translationY = -up * lift; alpha = lift.coerceIn(0f, 1f) }
                                .background(Brush.radialGradient(listOf(C.Accent.copy(alpha = if (C.P.dark) 0.55f else 0.35f), C.Accent.copy(alpha = 0f)))))
                            Icon(if (sel) t.filled() else t.icon(false), null, tint = icon,
                                modifier = Modifier.size(25.dp).graphicsLayer { translationY = -up * lift; val sc = (1f + 0.08f * lift) * press; scaleX = sc; scaleY = sc })
                            Text(t.title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = C.OnGlass, maxLines = 1,
                                modifier = Modifier.graphicsLayer { alpha = lift.coerceIn(0f, 1f); translationY = up * 2.3f + (1f - lift) * up })
                        }
                    }
                }
            }
        }
    }
}

/** 1.7.0：滚过首页大图后，标题收进顶部的玻璃胶囊（照片页的「11月 · 2026年」、按年视图）。各 tab 写 [title]，离开时清掉。 */
object HomeChrome {
    var title by mutableStateOf<String?>(null)
    /** 1.7.32：写标题的是哪个 Tab。切 Tab 时旧页还要淡出一会儿，胶囊只显示当前 Tab 写的标题，旧页离开时也不会把新页的标题清掉。 */
    var owner by mutableIntStateOf(-1)
}

/** 1.7.32：首页 Tab 内容里可读到自己是哪个 Tab（[HomeChrome.owner]）。 */
val LocalHomeTab = androidx.compose.runtime.staticCompositionLocalOf { -1 }

@Composable
private fun HomeTopCapsule(backdrop: GlassBackdrop) {
    // 1.7.32：不是当前 Tab 写的标题不显示（以前从照片切到影视时，照片页的「11月 · 2026年」胶囊要等照片页淡出完才弹性缩走）
    val mine = HomeChrome.owner == HomeState.tab
    val t = HomeChrome.title?.takeIf { mine }
    var last by remember { mutableStateOf("") }
    if (t != null) last = t
    val p by animateFloatAsState(if (t != null) 1f else 0f, Motion.materialize(), label = "capsule")
    if (t == null && (p < 0.01f || !mine)) return
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        val drop = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
        GlassSurface(Modifier.graphicsLayer {
            alpha = p.coerceIn(0f, 1f); val sc = 0.78f + 0.22f * p; scaleX = sc; scaleY = sc; translationY = (1f - p) * drop
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        }.heightIn(min = 50.dp), shape = CircleShape, backdrop = backdrop) {
            Row(Modifier.padding(start = 22.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(last, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Type.numFamily, fontFeatureSettings = Type.TNUM), color = C.OnGlass,
                    maxLines = 1, modifier = Modifier.semantics { heading() })
                IconButton(onClick = { Nav.push(Route.Search) }) { Icon(KI.search(), "搜索", tint = C.OnGlass, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** 写入顶栏胶囊标题；页面离开时清掉。 */
@Composable
internal fun ChromeTitle(title: String?) {
    val tab = LocalHomeTab.current
    // 正在淡出的旧 Tab 不再写（否则它的最后一次重组会把新 Tab 的标题盖掉）
    SideEffect { if (tab == HomeState.tab || tab == -1) { HomeChrome.title = title; HomeChrome.owner = if (tab == -1) HomeState.tab else tab } }
    DisposableEffect(Unit) { onDispose { if (HomeChrome.owner == tab || tab == -1) HomeChrome.title = null } }
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
fun TopSwitch(search: SearchScope = SearchScope.Photos, showDate: Boolean = true, overPhoto: Boolean = false, extra: @Composable RowScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val cur = currentSource()
    val fg = if (overPhoto) Color.White else C.OnGlass
    val fgSub = if (overPhoto) Color.White.copy(alpha = 0.8f) else C.OnGlassSub
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            // 1.7.0：来源胶囊 = 玻璃胶囊（压在照片上时深色着色 + 白字），按下液体形变；状态点 7dp（绿 = 在线，红 = 连不上）
            // 1.7.16：压在照片上必须 onDark = true（和右边的 GlassIconButton 一致），否则浅色主题下高光按浅色画（35% 白），白字约 3.4:1。
            val srcPress = remember { MutableInteractionSource() }
            Row(Modifier.heightIn(min = 44.dp).glassPress(srcPress)
                .glass(CircleShape, null, tint = if (overPhoto) Color.Black.copy(alpha = 0.6f) else Color.Unspecified, shadow = if (overPhoto) 0.dp else 8.dp, highlight = if (overPhoto) 0.7f else 1f, onDark = overPhoto)
                .clip(CircleShape)
                .clickable(srcPress, null, role = Role.Button, onClickLabel = "切换来源") { open = true }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (cur?.kind == "fn") {
                    val ok = Endpoint.online && Endpoint.latency >= 0
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) C.Green else C.Danger))
                } else Icon(KI.drive(), null, tint = fgSub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(cur?.title ?: "添加来源", color = fg, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                Icon(KI.down(), null, tint = fgSub, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
            AppDropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 230.dp)) {
                val all = allSources()
                listOf("fn" to "飞牛", "dav" to "WebDAV").forEach { (k, label) ->
                    val group = all.filter { it.kind == k }
                    if (group.isEmpty()) return@forEach
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                    group.forEach { s ->
                        val on = cur?.kind == s.kind && cur.id == s.id
                        DropdownMenuItem(
                            leadingIcon = { Box(Modifier.width(20.dp)) { if (on) Icon(KI.check(), null, tint = C.Accent, modifier = Modifier.size(18.dp)) } },
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
        if (showDate) GlassIconButton(KI.calendar(), "按日期查看照片", overPhoto = overPhoto) { HomeState.showDate = true }
        when (search) {
            SearchScope.Photos -> GlassIconButton(KI.search(), "搜索照片", overPhoto = overPhoto) { Nav.push(Route.Search) }
            SearchScope.Media -> GlassIconButton(KI.search(), "搜索影片", overPhoto = overPhoto) { Nav.push(Route.MovieSearch) }
            SearchScope.None -> Unit
        }
    }
}

@Composable
internal fun BigTitle(title: String, sub: String) {
    // 1.7.0：页面大标题 displaySmall（40sp 粗体、负字距；数字 Allo 等宽），副标题一行次要色；标题是读屏的「标题」
    Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 20.dp, bottom = 10.dp).semantics(mergeDescendants = true) { heading() }) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium.merge(Type.num), color = C.Sub, modifier = Modifier.padding(top = 2.dp))
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

/**
 * 1.7.0 照片页顶部的全出血大图（DESIGN §9）：那年今天的封面（没有时用最新一张），铺满宽度、约 56% 屏高，
 * 滚动时以 0.45 倍速视差下移；上沿压暗给顶栏，下沿压暗托住超大的月份标题（Allo 60sp 粗体、负字距）。
 * 标题随滚动缩小淡出，滚过之后由顶栏玻璃胶囊接手（[HomeChrome]）。点大图打开那组照片。
 */
@Composable
internal fun PhotoHero(cover: Photo?, kicker: String?, title: String, sub: String, scroll: () -> Int, onOpen: (() -> Unit)?) {
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val h = (conf.screenHeightDp * 0.56f).dp.coerceIn(340.dp, 560.dp)
    val hPx = with(androidx.compose.ui.platform.LocalDensity.current) { h.toPx() }
    AmbientFrom(cover?.thumbS)
    Box(Modifier.fillMaxWidth().height(h).clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val y = scroll().toFloat().coerceIn(0f, hPx)
            if (!Motion.reduced) { translationY = y * 0.45f; val sc = 1.04f + 0.04f * (y / hPx); scaleX = sc; scaleY = sc }
        }.then(if (onOpen != null) Modifier.clickable(onClickLabel = "查看", role = Role.Button) { onOpen() } else Modifier)) {
            if (cover != null) NetImage(cover.thumbM, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Accent.copy(alpha = 0.55f), C.Bg))))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color(0x80000000), 0.28f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color(0xB8000000))))
        TopSwitch(overPhoto = true)
        val shadow = androidx.compose.ui.graphics.Shadow(Color(0x73000000), blurRadius = 28f)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
            .graphicsLayer {
                val f = (scroll().toFloat() / (hPx * 0.6f)).coerceIn(0f, 1f)
                alpha = 1f - f; if (!Motion.reduced) { val sc = 1f - 0.18f * f; scaleX = sc; scaleY = sc; translationY = -f * 24f }
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
            }.semantics(mergeDescendants = true) { heading() }) {
            if (kicker != null) Text(kicker, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge.copy(shadow = shadow), modifier = Modifier.padding(bottom = 2.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.displayLarge.copy(shadow = shadow))
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge.merge(Type.num).copy(shadow = shadow), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * 1.7.16：照片页网格 / 沉浸流的底部留白 = 悬浮底栏（66dp + 离底 10dp）+ 系统导航条 + 14dp 间隙 + 迷你播放条。
 * 以前固定 100dp，三键导航（约 48dp）时 66 + 10 + 48 = 124dp > 100dp，滚到底最后一行被底栏盖住。
 * 滚动途中内容从悬浮玻璃底栏后面透过去是设计如此（DESIGN §9），只保证到底时能完整露出来。宽屏（侧边栏、没有底栏）保持原来的 100dp。
 */
@Composable
internal fun homeListBottom(): androidx.compose.ui.unit.Dp {
    if (isWide()) return 100.dp + miniPlayerPad   // 宽屏不变
    return 90.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + miniPlayerPad
}

/** 第一项（大图所在的页头）滚动了多少 px；页头已经滚出去时返回一个很大的值。 */
private fun heroScroll(index: Int, offset: Int): Int = if (index == 0) offset else Int.MAX_VALUE / 4

/** 大图高度（与 [PhotoHero] 一致），px。 */
@Composable
private fun heroPx(): Float {
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    return with(androidx.compose.ui.platform.LocalDensity.current) { (conf.screenHeightDp * 0.56f).dp.coerceIn(340.dp, 560.dp).toPx() }
}

/** 照片页页头：全出血大图 + 图库 / 合集 + 筛选 + 那年今天横条。 */
@Composable
private fun PhotoHeader(memories: List<MemoryGroup>, shown: List<Photo>, mt: String, sub: String, scroll: () -> Int, extra: @Composable () -> Unit = {}) {
    val mem = remember(memories, Hidden.version) { memories.firstNotNullOfOrNull { g -> Hidden.visible(g.photos).takeIf { it.isNotEmpty() }?.let { g to it } } }
    val cover = mem?.second?.first() ?: shown.firstOrNull()
    val labels = HeroText.labels(mem?.first, mem?.second?.size ?: 0, mem?.second?.first()?.time, java.time.LocalDate.now(), mt, sub)
    val open: (() -> Unit)? = when {
        mem != null -> { { Nav.push(Route.Viewer(mem.second, 0)) } }
        shown.isNotEmpty() -> { { Nav.push(Route.Viewer(shown, 0)) } }
        else -> null
    }
    Column {
        PhotoHero(cover, labels.kicker, labels.title, labels.sub, scroll, open)
        Spacer(Modifier.height(4.dp))
        PhotoModeSwitch(); PhotoFilterChips(); MemoriesRow(memories); extra()
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
    val hp = heroPx()
    val feed = rememberMemList("fn-feed-" + NasAccounts.currentId)
    val scrolled by remember { derivedStateOf {
        if (HomeState.immersive) heroScroll(feed.firstVisibleItemIndex, feed.firstVisibleItemScrollOffset) > hp * 0.55f
        else heroScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) > hp * 0.55f } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val (pc, vc) = tl.stats
    val sub = listOf(yt, if (filter == 2) (if (vc > 0) "${n(vc)} 个视频" else "") else if (pc > 0) "${n(pc)} 张照片" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    // 1.7.24：断网时先显示上次的内容；变成离线时提示一次
    fun retry() { scope.launch { runCatching { Endpoint.autoSelect() }; tl.refresh() } }
    val ctx = LocalContext.current
    LaunchedEffect(tl, tl.offline) { if (tl.offline) toast(ctx, "连不上飞牛，先显示上次的内容") }
    // B7：飞牛照片页下拉刷新（重新拉时间线）
    PullRefreshBox(enabled = !tl.loading, onRefresh = { retry() }) {
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
                        // 1.7.23：按网格实际显示的照片（已去掉隐藏 / 隔离的）算位置，落在该年第一个月的标题上（+1 是顶部大图）
                        val idx = firstIndexOfYear(buildEntriesBy(shown, Level.MONTH, true), y)
                        if (idx >= 0) grid.scrollToItem(idx + 1)
                    }
                }, onPinch = ::pinch)
                ChromeTitle("按年")
            }
            HomeState.immersive -> {
                ImmersiveFeed(entries, tl.loading, onEnd = { scope.launch { tl.next() } }, onOpen = { Nav.push(Route.Viewer(shown, it)) },
                    state = feed, bottom = homeListBottom(),
                    header = { PhotoHeader(memories, shown, mt, sub, { heroScroll(feed.firstVisibleItemIndex, feed.firstVisibleItemScrollOffset) }) { OfflineNote(tl) { retry() } } })
                ChromeTitle(if (scrolled) "$mt · $yt".trim(' ', '·') else null)
            }
            else -> {
                PhotoGrid(
                    entries, cols, tl.loading, onEnd = { scope.launch { tl.next() } },
                    onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = 0.dp, bottom = homeListBottom(),
                    header = { PhotoHeader(memories, shown, mt, sub, { heroScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }) {
                        OfflineNote(tl) { retry() }
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
                ChromeTitle(if (scrolled) "$mt · $yt".trim(' ', '·') else null)
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
            runCatching { lib.scan(reason = "下拉刷新", user = true) }
            ptr.endRefresh()
        }
    }
    LaunchedEffect(lib) { lib.ensure(); if (!ScanPolicy.paused) { Analyzer.start(ctx, lib, false); if (AiRunner.on) AiRunner.startDav(ctx, lib) } }
    val grid = rememberMemGrid("dav-photos-" + lib.accountId)
    // 1.7.23：WebDAV 也能从「月」再捏合到「年」，点年份卡片直达那一年（与飞牛一致）；打开 App 时不停在「年」
    var level by remember { mutableStateOf(Level.values()[Store.timelineLevel.coerceIn(1, 4)]) }
    fun setLevel(l: Level) { level = l; Store.timelineLevel = l.ordinal }
    fun pinch(zoomIn: Boolean) { val i = level.ordinal + if (zoomIn) 1 else -1; if (i in 0..4) setLevel(Level.values()[i]) }
    val stills by remember(lib) { derivedStateOf { MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { !it.isVideo })) } }
    val filter = HomeState.photoFilter
    val shown by remember(lib, filter) { derivedStateOf { if (filter == 1) stills else { MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { HomeState.accepts(it, filter) })) } } }
    val memories by remember(lib) { derivedStateOf { lib.metaVersion; OnThisDay.from(stills) } }
    LaunchedEffect(memories, stills.isNotEmpty()) { if (stills.isNotEmpty()) OnThisDay.feedWidget("dav:" + lib.accountId, memories) { stills } }
    val entries by remember(lib, level, filter) { derivedStateOf { lib.metaVersion; buildEntriesBy(shown, level, true) } }
    val hp = heroPx()
    val feed = rememberMemList("dav-feed-" + lib.accountId)
    val scrolled by remember { derivedStateOf {
        if (HomeState.immersive) heroScroll(feed.firstVisibleItemIndex, feed.firstVisibleItemScrollOffset) > hp * 0.55f
        else heroScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) > hp * 0.55f } }
    val day = visibleDay(entries, grid, 1)
    val (mt, yt) = monthTitle(day)
    val sub = listOf(yt, (if (filter == 1) "${n(stills.size)} 张照片" else "${n(shown.size)} 项"), ScanStatus.listing(lib)?.let { "正在扫描 ${it.done}/${it.total} 个文件夹" } ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
    Box(Modifier.fillMaxSize().then(if (!lib.scanning) Modifier.nestedScroll(ptr.nestedScrollConnection) else Modifier)) {
        when {
            lib.photos.isEmpty() && lib.scanning -> Column { TopSwitch(); SkeletonGrid(4, 20.dp) }
            lib.photos.isEmpty() && lib.error != null -> Column { TopSwitch(); Box(Modifier.weight(1f)) { ErrorState(lib.error, onRetry = { scope.launch { lib.scan(reason = "出错后重试", user = true) } }) } }
            shown.isEmpty() -> Column { TopSwitch(); PhotoModeSwitch(); PhotoFilterChips(); Box(Modifier.weight(1f)) {
                if (filter == 2) StateMessage(LI.videos(), "没有视频", "")
                else if (filter == 3 && stills.isNotEmpty()) StateMessage(KI.live(), "没有实况照片", "实况和动态照片分析完后会出现在这里。")
                else StateMessage(LI.photos(), "没有找到照片", "把照片放进这个 WebDAV 后下拉刷新。", "重新扫描") { scope.launch { lib.scan(full = true, reason = "重新扫描（全量）", user = true) } } } }
            level == Level.YEAR -> {
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val cards = remember(shown) { yearCardsOf(shown) }
                YearGrid(cards, statusTop + 60.dp, onPick = { y ->
                    setLevel(Level.MONTH)
                    scope.launch {
                        val idx = firstIndexOfYear(buildEntriesBy(shown, Level.MONTH, true), y)
                        if (idx >= 0) grid.scrollToItem(idx + 1)
                    }
                }, onPinch = ::pinch)
                ChromeTitle("按年")
            }
            HomeState.immersive -> {
                ImmersiveFeed(entries, lib.scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(shown, it)) },
                    state = feed, bottom = homeListBottom(),
                    header = { PhotoHeader(memories, shown, mt, sub, { heroScroll(feed.firstVisibleItemIndex, feed.firstVisibleItemScrollOffset) }) })
                ChromeTitle(if (scrolled) "$mt · $yt".trim(' ', '·') else null)
            }
            else -> {
                PhotoGrid(entries, adaptiveCols(level.cols.coerceAtMost(7)), lib.scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = 0.dp, bottom = homeListBottom(),
                    header = { PhotoHeader(memories, shown, mt, sub, { heroScroll(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }) }, onPinch = ::pinch)
                ChromeTitle(if (scrolled) "$mt · $yt".trim(' ', '·') else null)
            }
        }
        GlassRefreshIndicator(ptr, Modifier.align(Alignment.TopCenter))
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { lib.scan(reason = "整理照片后刷新") } })
    }
}

/**
 * 1.7.24：首页显示的是上次保存的内容时的一行提示。联网失败（离线）时带「重试」；还在后台拉新的时只说「正在更新」。
 * 读屏：一整行读出来，「重试」是按钮。
 */
@Composable
private fun OfflineNote(tl: TimelineSource, onRetry: () -> Unit) {
    val show = tl.offline || (tl.cached && tl.loading)
    androidx.compose.animation.AnimatedVisibility(show, enter = androidx.compose.animation.fadeIn(Motion.enter()) + androidx.compose.animation.expandVertically(Motion.enter()),
        exit = androidx.compose.animation.fadeOut(Motion.exit()) + androidx.compose.animation.shrinkVertically(Motion.exit())) {
        Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs).fillMaxWidth().clip(RoundedCornerShape(Radius.m))
            .background(C.Text.copy(alpha = 0.06f)).padding(start = Space.m, end = Space.xs).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KI.info(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
            Text(if (tl.offline) "离线 · 显示的是上次的内容" else "正在更新…", color = C.Sub, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = Space.s).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            if (tl.offline) HeaderTextButton("重试", C.Accent, onRetry)
        }
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

