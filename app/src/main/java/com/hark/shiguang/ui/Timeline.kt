package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
    fun open(p: Photo) { from = bounds[p.key]; fromThumb = p.thumbS }
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
    val s by animateFloatAsState(if (sel) 0.86f else 1f, label = "sel")
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
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (sel) C.Accent else Color(0x33000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (sel) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(15.dp)) }
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
            contentPadding = PaddingValues(top = top, bottom = bottom, start = if (columns >= 6) 0.dp else 2.dp, end = if (columns >= 6) 0.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize()
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
                    is Entry.Cell -> SelectableThumb(e.photo, corner, { onOpen(e.index) }, small = columns >= 6)
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
        var n = 0; var k = i + 1
        while (k < entries.size && entries[k] is Entry.Cell) { n++; k++ }
        entries.subList(i + 1, k).map { (it as Entry.Cell).photo }
    }
    val allSel = Selection.active && inGroup.isNotEmpty() && inGroup.all { Selection.has(it) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp)
            .pointerInput(onClick) { detectTapGestures { if (onClick != null) onClick(e) } },
        verticalAlignment = Alignment.Bottom
    ) {
        Text(e.title, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, fontFamily = C.P.display, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(e.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 1.dp))
        if (Selection.active) {
            Text(if (allSel) "取消" else "全选", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.pointerInput(inGroup, allSel) { detectTapGestures { inGroup.forEach { p -> Selection.set(p, !allSel) } } })
        }
    }
}

@Composable
private fun Scrubber(entries: List<Entry>, state: LazyGridState, headerOffset: Int, top: Dp, bottom: Dp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var trackH by remember { mutableFloatStateOf(1f) }
    var frac by remember { mutableFloatStateOf(0f) }
    val visible by remember { derivedStateOf { state.isScrollInProgress } }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible, dragging) {
        if (visible || dragging) show = true else { kotlinx.coroutines.delay(1400); show = false }
    }
    val curFrac by remember(entries) {
        derivedStateOf { if (entries.isEmpty()) 0f else (state.firstVisibleItemIndex.toFloat() / entries.size).coerceIn(0f, 1f) }
    }
    val f = if (dragging) frac else curFrac
    val label = remember(entries, f) {
        val idx = (f * (entries.size - 1)).roundToInt().coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        val p = (entries.getOrNull(idx) as? Entry.Cell)?.photo ?: (entries.drop(idx).firstOrNull { it is Entry.Cell } as? Entry.Cell)?.photo
        p?.let { val s = it.day.split(':', '-'); if (s.size >= 2) "${s[0]}年${s[1].toIntOrNull() ?: s[1]}月" else "" } ?: ""
    }
    AnimatedVisibility(show, modifier = modifier.fillMaxHeight().padding(top = top + 8.dp, bottom = bottom + 8.dp), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxHeight().width(80.dp).onGloballyPositioned { trackH = it.size.height.toFloat().coerceAtLeast(1f) }
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
            val y = with(density) { (f * (trackH - 44.dp.toPx())).toDp() }
            Row(Modifier.align(Alignment.TopEnd).offset(y = y), verticalAlignment = Alignment.CenterVertically) {
                if (dragging && label.isNotEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface2).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(label, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    Modifier.padding(end = 4.dp).size(width = 22.dp, height = 44.dp).clip(RoundedCornerShape(11.dp)).background(C.Surface2)
                        .border(0.5.dp, C.Line, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.UnfoldMore, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
            }
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
                    Text("${c.year}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = C.P.display)
                    Text("${c.count} 项", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }
    }
}
