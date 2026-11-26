package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.draw.scale
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
    fun start(p: Photo) { active = true; items[key(p)] = p }
    val list: List<Photo> get() = items.values.toList()
}

/** Remembers on-screen bounds of thumbnails so the viewer can grow out of them. */
object Hero {
    val bounds = HashMap<String, Rect>()
    var from by mutableStateOf<Rect?>(null)
    var fromThumb by mutableStateOf<String?>(null)
    /** 大图已经自己缩回到缩略图位置（共享元素关闭）：这次出栈不再做页面级的淡出缩放。 */
    var closed = false
    fun open(p: Photo) { from = bounds[p.key]; fromThumb = p.thumbS; closed = false }
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
    val sel = Selection.active && Selection.has(p)
    val s by animateFloatAsState(if (sel) 0.92f else 1f, Motion.state(), label = "sel")
    // 滚出屏幕 / 页面离开时把位置删掉：大图缩回时只落到真正在屏幕上的那一格
    DisposableEffect(p.key) { onDispose { Hero.bounds.remove(p.key) } }
    Box(
        Modifier.aspectRatio(1f).onGloballyPositioned { Hero.bounds[p.key] = it.boundsInRoot() }
    ) {
        Box(Modifier.fillMaxSize().scale(s)) {
            Thumb(p, if (sel) corner + 6.dp else corner, small = small) {
                if (Selection.swallowTap) { Selection.swallowTap = false; return@Thumb }
                if (Selection.active) Selection.toggle(p) else { Hero.open(p); onOpen() }
            }
        }
        if (Selection.active) {
            val ringBg by androidx.compose.animation.animateColorAsState(if (sel) C.Accent else Color(0x33000000), Motion.state(), label = "ring")
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
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
) {
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
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(C.Card)).background(C.Surface2)
                    .pointerInput(c.year) { detectTapGestures { onPick(c.year) } }
            ) {
                if (c.cover != null) NetImage(c.cover.thumbM, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xB3000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
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
