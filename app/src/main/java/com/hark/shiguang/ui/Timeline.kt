package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ============================================================ selection & hero

object Selection {
    val items = mutableStateMapOf<String, Photo>()
    var active by mutableStateOf(false)
    /** The release that ends a long press also reaches the thumbnail as a tap; it must not undo the selection it just made. */
    @Volatile var swallowTap = false
    fun key(p: Photo) = p.key
    fun toggle(p: Photo) { val k = key(p); if (items.containsKey(k)) items.remove(k) else items[k] = p; if (items.isEmpty()) active = false }
    fun set(p: Photo, on: Boolean) { val k = key(p); if (on) items[k] = p else items.remove(k) }
    fun has(p: Photo) = items.containsKey(key(p))
    fun clear() { items.clear(); active = false }
    fun start(p: Photo) { enter(); items[key(p)] = p }
    /** 进入多选（还没选中任何一项，例如搜索页的「选择」按钮）。 */
    fun enter() { if (!active) startedAt = android.os.SystemClock.uptimeMillis(); active = true }
    val list: List<Photo> get() = items.values.toList()
    /** 1.7.26：进入多选的时刻。刚进入的 [Motion.BURST] 毫秒内出现的勾选圈弹入；之后滚进屏幕的格子直接显示（不在滚动时一格格弹）。 */
    var startedAt = 0L
        private set
    /**
     * U5（1.7.27）「全选本页」：当前页面网格里已经加载的全部照片。由 [PhotoGrid] 在组合时登记、离开时撤销，
     * 多选栏据此显示「全选 / 全不选」。null = 这一页没有登记（例如搜索页自己有全选按钮）。
     */
    var page by mutableStateOf<(() -> List<Photo>)?>(null)
    fun selectAll(on: Boolean) {
        val all = page?.invoke().orEmpty()
        if (on) all.forEach { items[key(it)] = it } else { all.forEach { items.remove(key(it)) }; if (items.isEmpty()) active = false }
    }
}

/** Remembers on-screen bounds of thumbnails so the viewer can grow out of them. */
object Hero {
    /**
     * 1.7.18：只存缩略图的 LayoutCoordinates 引用（滚动时每帧每格都会回调，存引用几乎不花钱），
     * 真正要用的时候（点开 / 缩回）才算屏幕位置。以前每帧每格都 boundsInRoot() + 新建 Rect。
     */
    val coords = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()
    fun bounds(key: String): Rect? = coords[key]?.takeIf { it.isAttached }?.boundsInRoot()
    var from by mutableStateOf<Rect?>(null)
    var fromThumb by mutableStateOf<String?>(null)
    /** 大图已经自己缩回到缩略图位置（共享元素关闭）：这次出栈不再做页面级的淡出缩放。 */
    var closed = false
    fun open(p: Photo) { from = bounds(p.key); fromThumb = p.thumbS; closed = false }
}

// ============================================================ entries

enum class Level(val label: String, val cols: Int) { YEAR("年", 1), MONTH("月", 7), DAY5("日", 5), DAY4("日", 4), DAY3("日", 3) }

fun monthTitle(day: String): String {
    val p = day.split(':', '-'); if (p.size < 2) return day
    val y = p[0].toIntOrNull() ?: return day; val m = p[1].toIntOrNull() ?: return day
    return if (y == java.time.LocalDate.now().year) "${m}月" else "${y}年${m}月"
}

fun buildEntriesBy(photos: List<Photo>, level: Level, grouped: Boolean): List<Entry> {
    if (!grouped) return photos.mapIndexed { i, p -> Entry.Cell(p, i) }
    if (level == Level.MONTH) {
        val out = ArrayList<Entry>(photos.size + 32); var last = ""
        photos.forEachIndexed { i, p ->
            val m = p.day.take(7)
            if (m != last) { out += Entry.Header("m$m$i", monthTitle(p.day), ""); last = m }
            out += Entry.Cell(p, i)
        }
        return out
    }
    return buildEntries(photos, true)
}

// ============================================================ grid

@Composable
fun SelectableThumb(p: Photo, corner: Dp, onOpen: () -> Unit, small: Boolean = false) {
    // 1.7.18：选择集合任何一项变化都会通知所有读过它的格子；derivedStateOf 让只有「自己的选中状态真的变了」的格子重组（拖选时不再整屏重组）
    val sel by remember(p.key) { derivedStateOf { Selection.active && Selection.has(p) } }
    val s = animateFloatAsState(if (sel) 0.92f else 1f, Motion.state(), label = "sel")
    // 滚出屏幕 / 页面离开时把位置删掉：大图缩回时只落到真正在屏幕上的那一格
    DisposableEffect(p.key) { onDispose { Hero.coords.remove(p.key) } }
    Box(
        Modifier.aspectRatio(1f).onGloballyPositioned { Hero.coords[p.key] = it }
    ) {
        // 选中缩放在绘制阶段读（graphicsLayer），动画每一帧不再触发重组
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = s.value; scaleY = s.value }) {
            Thumb(p, if (sel) corner + 6.dp else corner, small = small) {
                if (Selection.swallowTap) { Selection.swallowTap = false; return@Thumb }
                if (Selection.active) Selection.toggle(p) else { Hero.open(p); onOpen() }
            }
        }
        if (Selection.active) {
            val ringBg by androidx.compose.animation.animateColorAsState(if (sel) C.Accent else Color(0x33000000), Motion.state(), label = "ring")
            // 1.7.26：刚进入多选时，屏幕上的勾选圈从 0.4 弹到原大（Motion.pop），之后新滚进来的格子不再弹
            val ring = remember {
                val fresh = !Motion.reduced && android.os.SystemClock.uptimeMillis() - Selection.startedAt < Motion.BURST
                androidx.compose.animation.core.Animatable(if (fresh) 0.4f else 1f)
            }
            LaunchedEffect(Unit) { if (ring.value < 1f) ring.animateTo(1f, Motion.pop()) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).graphicsLayer { scaleX = ring.value; scaleY = ring.value; alpha = ring.value.coerceIn(0f, 1f) }.clip(CircleShape)
                    .background(ringBg)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 1.6.4：勾用 Kubai 线性勾，选中时带一点回弹地弹出（Motion.pressOut），取消时淡出
                AnimatedVisibility(sel, enter = scaleIn(Motion.pressOut(), initialScale = 0.5f) + fadeIn(Motion.state()), exit = fadeOut(Motion.exit())) {
                    Icon(KI.check(), null, tint = C.OnAccent, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/**
 * Photo grid with: day/month headers, pinch to change [Level], long-press + drag multi-select,
 * a right-side fast scrubber, infinite loading and a hero hand-off to the viewer.
 */
@Composable
fun PhotoGrid(
    entries: List<Entry>,
    columns: Int,
    loadingMore: Boolean,
    onEnd: () -> Unit,
    onOpen: (Int) -> Unit,
    state: LazyGridState = rememberLazyGridState(),
    top: Dp = 0.dp,
    bottom: Dp = 100.dp,
    header: (@Composable () -> Unit)? = null,
    onPinch: ((zoomIn: Boolean) -> Unit)? = null,
    scrubber: Boolean = true,
    onHeaderClick: ((Entry.Header) -> Unit)? = null,
    pageSelectAll: Boolean = true,
) {
    // U5：把这一页已加载的照片登记给多选栏的「全选」（同一时刻只认最后登记的那个网格；离开时只撤销自己的）
    if (pageSelectAll) {
        val cur by androidx.compose.runtime.rememberUpdatedState(entries)
        val provider = remember { { cur.mapNotNull { (it as? Entry.Cell)?.photo } } }
        DisposableEffect(provider) {
            Selection.page = provider
            onDispose { if (Selection.page === provider) Selection.page = null }
        }
    }
    val gap = if (columns >= 6) 1.dp else C.Gap
    val corner = if (columns >= 6) 0.dp else if (columns >= 5) minOf(C.Corner, 3.dp) else C.Corner
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val headerOffset = if (header != null) 1 else 0
    // 1.6.4 捏合切换层级：新网格从略小（放大一级）或略大（缩小一级）缩放淡入到位，说明「层级变了、往哪变」；移除动画时直接切换
    val levelAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    var lastCols by remember { mutableIntStateOf(columns) }
    LaunchedEffect(columns) {
        if (columns != lastCols && !Motion.reduced) {
            levelAnim.snapTo(if (columns < lastCols) 0.94f else 1.05f)
            lastCols = columns
            levelAnim.animateTo(1f, Motion.zoom())
        } else lastCols = columns
    }
    // 1.7.12 首屏错落淡入（DESIGN §7）：网格第一次有内容、且停在顶部时，前 GRID_INTRO_MAX 格按「行 + 列」斜向依次淡入并略微放大到位。
    // 一个 Animatable 驱动所有格子（只在绘制阶段读，不重组）；播过一次就记住（切 Tab 回来、从大图返回都不再播）。移除动画时不播。
    var introPlayed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val intro = remember { androidx.compose.animation.core.Animatable(if (introPlayed || Motion.reduced) Float.MAX_VALUE else 0f) }
    val introDiag = remember(entries, columns) { gridIntroDiagonals(entries, columns, Motion.GRID_INTRO_MAX) }
    val hasCells = entries.isNotEmpty()
    LaunchedEffect(hasCells) {
        if (!hasCells || introPlayed) return@LaunchedEffect
        introPlayed = true
        if (Motion.reduced || state.firstVisibleItemIndex > 1) { intro.snapTo(Float.MAX_VALUE); return@LaunchedEffect }
        val total = ((introDiag.maxOrNull() ?: 0) * Motion.GRID_INTRO_STEP + Motion.GRID_INTRO_CELL).toFloat()
        intro.animateTo(total, androidx.compose.animation.core.tween(total.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        intro.snapTo(Float.MAX_VALUE)
    }
    // back from the viewer: make sure the last photo looked at is on screen
    LaunchedEffect(ViewerReturn.tick) {
        val k = ViewerReturn.lastKey ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it is Entry.Cell && it.photo.key == k }
        if (idx >= 0) {
            val gi = idx + headerOffset
            val vis = state.layoutInfo.visibleItemsInfo
            if (vis.isEmpty() || vis.none { it.index == gi }) state.scrollToItem(gi, -300)
        }
    }
    var dragMode by remember { mutableStateOf<Boolean?>(null) } // true=select, false=deselect
    var lastDragIndex by remember { mutableIntStateOf(-1) }
    var anchorIndex by remember { mutableIntStateOf(-1) }

    fun cellAt(pos: Offset): Int? {
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val o = item.offset; val sz = item.size
            pos.x >= o.x && pos.x <= o.x + sz.width && pos.y >= o.y && pos.y <= o.y + sz.height
        } ?: return null
        val idx = info.index - headerOffset
        return if (idx in entries.indices && entries[idx] is Entry.Cell) idx else null
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), state = state,
            // 1.7.0 照片铺满：去掉左右页边距，缩略图直角、间隙 1.5dp（≥ 6 列 1dp）
            contentPadding = PaddingValues(top = top, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize()
                .graphicsLayer {
                    val v = levelAnim.value
                    scaleX = v; scaleY = v
                    alpha = (1f - kotlin.math.abs(1f - v) * 8f).coerceIn(0.5f, 1f)
                }
                .pointerInput(onPinch) {
                    if (onPinch == null) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var total = 1f; var fired = false
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1) {
                                total *= ev.calculateZoom()
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                if (!fired && (total > 1.25f || total < 0.8f)) { fired = true; onPinch(total > 1f) }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(entries) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val i = cellAt(pos) ?: return@detectDragGesturesAfterLongPress
                            val p = (entries[i] as Entry.Cell).photo
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Selection.swallowTap = true
                            if (!Selection.active) Selection.start(p) else Selection.toggle(p)
                            dragMode = Selection.has(p); anchorIndex = i; lastDragIndex = i
                        },
                        onDrag = { change, _ ->
                            val i = cellAt(change.position) ?: return@detectDragGesturesAfterLongPress
                            if (i != lastDragIndex && anchorIndex >= 0) {
                                val lo = minOf(anchorIndex, i); val hi = maxOf(anchorIndex, i)
                                val plo = minOf(anchorIndex, lastDragIndex); val phi = maxOf(anchorIndex, lastDragIndex)
                                for (k in minOf(lo, plo)..maxOf(hi, phi)) {
                                    val e = entries.getOrNull(k) as? Entry.Cell ?: continue
                                    val inRange = k in lo..hi
                                    Selection.set(e.photo, if (inRange) dragMode == true else !(dragMode == true))
                                }
                                lastDragIndex = i
                            }
                            // auto-scroll near edges
                            val h = state.layoutInfo.viewportSize.height
                            if (change.position.y > h - 120) scope.launch { state.scrollBy(40f) }
                            else if (change.position.y < 160) scope.launch { state.scrollBy(-40f) }
                        },
                        onDragEnd = { dragMode = null; anchorIndex = -1; scope.launch { kotlinx.coroutines.delay(120); Selection.swallowTap = false } },
                        onDragCancel = { dragMode = null; anchorIndex = -1; scope.launch { kotlinx.coroutines.delay(120); Selection.swallowTap = false } },
                    )
                },
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }, key = "__header") { header() }
            items(entries.size, key = { i -> when (val e = entries[i]) { is Entry.Header -> e.key; is Entry.Cell -> "c${e.photo.key}_${e.index}" } },
                span = { i -> if (entries[i] is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { i -> if (entries[i] is Entry.Header) 0 else 1 }) { i ->
                when (val e = entries[i]) {
                    is Entry.Header -> DateHeader(e, entries, i, onHeaderClick)
                    is Entry.Cell -> {
                        val d = introDiag.getOrElse(i) { -1 }
                        Box(if (d < 0) Modifier else Modifier.graphicsLayer {
                            val t = intro.value
                            if (t < Float.MAX_VALUE) {
                                val p = ((t - d * Motion.GRID_INTRO_STEP) / Motion.GRID_INTRO_CELL).coerceIn(0f, 1f)
                                val k = Motion.EaseOut.transform(p)
                                alpha = k; scaleX = 0.94f + 0.06f * k; scaleY = scaleX
                            }
                        }) { SelectableThumb(e.photo, corner, { onOpen(e.index) }, small = columns >= 6) }
                    }
                }
                if (i >= entries.size - columns * 6) LaunchedEffect(entries.size) { onEnd() }
            }
            if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }, key = "__more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = C.Accent)
                }
            }
        }
        if (scrubber && entries.size > 200) Scrubber(entries, state, headerOffset, top, bottom, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun DateHeader(e: Entry.Header, entries: List<Entry>, i: Int, onClick: ((Entry.Header) -> Unit)?) {
    val inGroup = remember(entries, i) {
        var k = i + 1
        while (k < entries.size && entries[k] is Entry.Cell) k++
        entries.subList(i + 1, k).map { (it as Entry.Cell).photo }
    }
    val allSel = Selection.active && inGroup.isNotEmpty() && inGroup.all { Selection.has(it) }
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = Space.xs, top = 30.dp, bottom = 10.dp).heightIn(min = 40.dp)
            .pointerInput(onClick) { detectTapGestures { if (onClick != null) onClick(e) } }
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1.7.0：日期 / 月份标题大号粗体左对齐（headlineSmall 22sp、负字距；数字 Allo 等宽），副标题同一基线
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(e.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = C.P.display, fontFeatureSettings = Type.TNUM), color = C.Text)
            if (e.sub.isNotEmpty()) {
                Spacer(Modifier.width(Space.s))
                Text(e.sub, style = MaterialTheme.typography.bodyMedium, color = C.Sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        // 多选时出现「全选 / 取消」：淡入，不挤动标题；整块 ≥ 40dp 高便于点按，读屏是按钮
        AnimatedVisibility(Selection.active, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            val label = if (allSel) "取消" else "全选"
            Text(label, color = C.Accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(Radius.s))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (allSel) "取消选择这一天" else "选择这一天的全部照片") { inGroup.forEach { p -> Selection.set(p, !allSel) } }
                    .padding(horizontal = Space.m, vertical = 10.dp))
        }
    }
}

/**
 * 1.6.2 快速滚动条（DESIGN §7）：滚动时右侧出现一枚细长把手；按住拖动时把手变宽、左侧弹出年月气泡（Allo 等宽数字），
 * 每跨过一个月轻震一下。松手 / 停止滚动 1.4 s 后淡出。把手的触摸区 48dp 宽，画出来的只有 4–6dp。
 */
@Composable
private fun Scrubber(entries: List<Entry>, state: LazyGridState, headerOffset: Int, top: Dp, bottom: Dp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var trackH by remember { mutableFloatStateOf(1f) }
    var frac by remember { mutableFloatStateOf(0f) }
    val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) show = true else { kotlinx.coroutines.delay(1400); show = false }
    }
    // 把手位置 = 第一个可见项 / (总数 - 一屏的项数)，这样滚到底时把手也到底
    val curFrac by remember(entries) {
        derivedStateOf {
            val total = state.layoutInfo.totalItemsCount
            val perScreen = state.layoutInfo.visibleItemsInfo.size
            val range = (total - perScreen).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
        }
    }
    val f = if (dragging) frac else curFrac
    fun labelAt(x: Float): String {
        if (entries.isEmpty()) return ""
        val idx = (x * (entries.size - 1)).roundToInt().coerceIn(0, entries.size - 1)
        var k = idx
        while (k < entries.size && entries[k] !is Entry.Cell) k++
        val p = (entries.getOrNull(k) as? Entry.Cell)?.photo ?: return ""
        val s = p.day.split(':', '-'); return if (s.size >= 2) "${s[0]}年${s[1].toIntOrNull() ?: s[1]}月" else ""
    }
    val label = remember(entries, f) { labelAt(f) }
    // 拖动跨过一个月：轻震（只在拖动时）
    var lastLabel by remember { mutableStateOf("") }
    LaunchedEffect(label, dragging) {
        if (dragging && label.isNotEmpty() && lastLabel.isNotEmpty() && label != lastLabel) Haptics.tick(view)
        lastLabel = label
    }
    val handleW by animateDpAsState(if (dragging) 6.dp else 4.dp, Motion.move(), label = "handleW")
    val handleH = 40.dp
    AnimatedVisibility(show, modifier = modifier.fillMaxHeight().padding(top = top + Space.s, bottom = bottom + Space.s),
        enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        Box(
            Modifier.fillMaxHeight().width(48.dp).onGloballyPositioned { trackH = it.size.height.toFloat().coerceAtLeast(1f) }
                .semantics { contentDescription = "快速滚动" + if (label.isNotEmpty()) "，$label" else "" }
                .pointerInput(entries) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> dragging = true; frac = (o.y / trackH).coerceIn(0f, 1f) },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        frac = (change.position.y / trackH).coerceIn(0f, 1f)
                        val target = (frac * (entries.size - 1)).roundToInt() + headerOffset
                        scope.launch { state.scrollToItem(target) }
                    }
                }
        ) {
            val y = with(density) { (f * (trackH - handleH.toPx())).toDp() }
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = y).padding(end = 3.dp).size(width = handleW, height = handleH)
                    .clip(CircleShape).background(if (dragging) C.Accent else C.Sub.copy(alpha = 0.55f))
            )
        }
    }
    // 年月气泡：画在轨道左边（轨道只有 48dp 宽，气泡不能放在它里面）
    AnimatedVisibility(dragging && label.isNotEmpty(), modifier = modifier.padding(top = top + Space.s, end = 48.dp),
        enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)),
        exit = fadeOut(Motion.exit())) {
        val y = with(density) { (f * (trackH - 40.dp.toPx())).toDp() }
        Box(
            Modifier.offset(y = y).heightIn(min = 40.dp).skeuoCard(RoundedCornerShape(Radius.m), color = C.Surface2).padding(horizontal = Space.l),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = C.Text, style = MaterialTheme.typography.titleMedium.copy(fontFamily = C.P.display, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Type.TNUM))
        }
    }
}

// ============================================================ year view

data class YearCard(val year: Int, val count: Int, val cover: Photo?)

/** 1.7.23：从已加载的照片按年分组（WebDAV「年」视图用；飞牛按服务器的月统计算）。照片按时间倒序，取每年第一张当封面。 */
internal fun yearCardsOf(photos: List<Photo>): List<YearCard> {
    val out = LinkedHashMap<Int, YearCard>()
    photos.forEach { p ->
        val y = p.day.take(4).toIntOrNull() ?: return@forEach
        val c = out[y]
        out[y] = if (c == null) YearCard(y, 1, p) else c.copy(count = c.count + 1, cover = c.cover ?: p)
    }
    return out.values.sortedByDescending { it.year }
}

/**
 * 1.7.23：在「月」层级的条目里，[year] 年第一个月的位置（指向月份标题，让标题露出来；找不到返回 -1）。
 * 以前飞牛用「全部照片」重算下标，没扣掉隐藏 / 隔离的照片，有隐藏照片时会落到别的月份。
 */
internal fun firstIndexOfYear(entries: List<Entry>, year: Int): Int {
    val i = entries.indexOfFirst { it is Entry.Cell && it.photo.day.take(4) == "$year" }
    if (i < 0) return -1
    return if (i > 0 && entries[i - 1] is Entry.Header) i - 1 else i
}

@Composable
fun YearGrid(cards: List<YearCard>, top: Dp, onPick: (Int) -> Unit, onPinch: (Boolean) -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(top = top, bottom = 120.dp, start = 14.dp, end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var total = 1f; var fired = false
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1) {
                        total *= ev.calculateZoom(); ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        if (!fired && (total > 1.25f || total < 0.8f)) { fired = true; onPinch(total > 1f) }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
    ) {
        items(cards.size, key = { cards[it].year }) { i ->
            val c = cards[i]
            // 1.7.23：整卡是按钮（以前用 detectTapGestures，读屏点不到、没有按压反馈）；读屏读「2024 年，1234 项，按钮」
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(C.Card)).background(C.Surface2)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看这一年") { onPick(c.year) }
                    .semantics(mergeDescendants = true) { contentDescription = "${c.year} 年，${c.count} 项" }
            ) {
                if (c.cover != null) NetImage(c.cover.thumbM, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xB3000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp).clearAndSetSemantics { }) {
                    Text("${c.year}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = C.P.display)
                    Text("${c.count} 项", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 1.7.12 首屏错落淡入用：前 [max] 个条目各自的「行 + 列」（日期标题占一整行，取 -1 表示不参与动画）。
 * 只是近似的网格位置（不量实际布局），足够让格子沿对角线依次出现。
 */
internal fun gridIntroDiagonals(entries: List<Entry>, columns: Int, max: Int): IntArray {
    val n = minOf(entries.size, max)
    val out = IntArray(n) { -1 }
    var row = 0; var col = 0; var started = false
    for (i in 0 until n) {
        when (entries[i]) {
            is Entry.Header -> { if (started) row += if (col > 0) 2 else 1; col = 0 }
            is Entry.Cell -> {
                if (col == columns) { col = 0; row++ }
                out[i] = row + col
                col++; started = true
            }
        }
    }
    return out
}
