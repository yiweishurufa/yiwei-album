package com.hark.shiguang.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.0.1 #11: draws 弹弹play comments over the media3 PlayerView (added to its overlay frame, so taps still reach the
 * controller). Driven by the player clock: [position] is polled every frame, so pause / seek / speed follow automatically.
 * Scroll comments move at one constant speed (no lane overtaking); top / bottom comments stay 4 s.
 */
class DanmakuView(ctx: Context) : View(ctx) {
    var position: () -> Long = { 0L }
    var playing: () -> Boolean = { false }

    private class Live(val d: Dm, val lane: Int, val start: Long, val w: Float)

    private var items: List<Dm> = emptyList()
    private var idx = 0
    private var lastPos = -1L
    private val scroll = ArrayList<Live>()
    private val tops = ArrayList<Live>()
    private val bottoms = ArrayList<Live>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; color = 0xFF000000.toInt() }
    private var alpha255 = 217
    private var textPx = 0f
    private var areaFrac = 0.5f
    private var durMs = 8000f
    private var showScroll = true; private var showTop = true; private var showBottom = true; private var colors = true

    init { isClickable = false; isFocusable = false; applyPrefs() }

    fun setData(list: List<Dm>) { items = list; reset(position()); invalidate() }
    fun clear() { items = emptyList(); reset(0); invalidate() }
    val count: Int get() = items.size

    fun applyPrefs() {
        alpha255 = (DanmakuPrefs.opacity.coerceIn(0.1f, 1f) * 255).toInt()
        textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f * DanmakuPrefs.size.coerceIn(0.5f, 2f), resources.displayMetrics)
        fill.textSize = textPx; stroke.textSize = textPx; stroke.strokeWidth = (textPx / 9f).coerceAtLeast(2f)
        areaFrac = DanmakuPrefs.area.coerceIn(0.1f, 1f)
        durMs = 8000f / DanmakuPrefs.speed.coerceIn(0.4f, 2.5f)
        showScroll = DanmakuPrefs.scroll; showTop = DanmakuPrefs.top; showBottom = DanmakuPrefs.bottom; colors = DanmakuPrefs.colors
        scroll.clear(); tops.clear(); bottoms.clear()
        invalidate()
    }

    private fun reset(pos: Long) {
        scroll.clear(); tops.clear(); bottoms.clear()
        var lo = 0; var hi = items.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (items[m].t < pos) lo = m + 1 else hi = m }
        idx = lo; lastPos = pos
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || width == 0) return
        val pos = position()
        if (lastPos < 0 || pos < lastPos - 400 || pos - lastPos > 2500) reset(pos)
        val lineH = textPx * 1.3f
        val lanes = ((height * areaFrac) / lineH).toInt().coerceAtLeast(1)
        val speed = width / durMs                     // px per ms of video time
        // spawn what is due (anything older than 1.5 s is skipped: no catch-up bursts after a stall)
        while (idx < items.size && items[idx].t <= pos) {
            val d = items[idx++]
            if (pos - d.t > 1500) continue
            when (d.mode) {
                5 -> if (showTop) freeFixed(tops, lanes, pos)?.let { tops.add(Live(d, it, d.t, fill.measureText(d.text))) }
                4 -> if (showBottom) freeFixed(bottoms, lanes, pos)?.let { bottoms.add(Live(d, it, d.t, fill.measureText(d.text))) }
                else -> if (showScroll) {
                    val w = fill.measureText(d.text)
                    freeScroll(lanes, pos, speed)?.let { scroll.add(Live(d, it, d.t, w)) }
                }
            }
        }
        val base = -fill.fontMetrics.ascent
        val it1 = scroll.iterator()
        while (it1.hasNext()) {
            val l = it1.next()
            val x = width - (pos - l.start) * speed
            if (x + l.w < 0) { it1.remove(); continue }
            draw(canvas, l, x, l.lane * lineH + base)
        }
        tops.removeAll { pos - it.start > 4000 }
        bottoms.removeAll { pos - it.start > 4000 }
        tops.forEach { draw(canvas, it, (width - it.w) / 2, it.lane * lineH + base) }
        bottoms.forEach { draw(canvas, it, (width - it.w) / 2, height - (it.lane + 1) * lineH + base - textPx * 0.3f) }
        lastPos = pos
        if (playing()) postInvalidateOnAnimation() else postInvalidateDelayed(250)
    }

    private fun draw(c: Canvas, l: Live, x: Float, y: Float) {
        val rgb = if (colors) l.d.color and 0xFFFFFF else 0xFFFFFF
        stroke.alpha = (alpha255 * 0.7f).toInt()
        fill.color = (alpha255 shl 24) or rgb
        c.drawText(l.d.text, x, y, stroke)
        c.drawText(l.d.text, x, y, fill)
    }

    private fun freeScroll(lanes: Int, pos: Long, speed: Float): Int? {
        val gap = textPx
        for (lane in 0 until lanes) {
            val last = scroll.lastOrNull { it.lane == lane }
            if (last == null || width - (pos - last.start) * speed + last.w + gap <= width) return lane
        }
        return null
    }

    private fun freeFixed(list: List<Live>, lanes: Int, pos: Long): Int? =
        (0 until lanes).firstOrNull { lane -> list.none { it.lane == lane && pos - it.start <= 4000 } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); invalidate() }
}

// ============================================================ dialogs

@Composable
fun DanmakuSettingsDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    var alpha by remember { mutableFloatStateOf(DanmakuPrefs.opacity) }
    var size by remember { mutableFloatStateOf(DanmakuPrefs.size) }
    var area by remember { mutableFloatStateOf(DanmakuPrefs.area) }
    var speed by remember { mutableFloatStateOf(DanmakuPrefs.speed) }
    var sc by remember { mutableStateOf(DanmakuPrefs.scroll) }
    var tp by remember { mutableStateOf(DanmakuPrefs.top) }
    var bt by remember { mutableStateOf(DanmakuPrefs.bottom) }
    var col by remember { mutableStateOf(DanmakuPrefs.colors) }
    fun changed() { onChanged() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("弹幕设置", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            DmStep("不透明度", "${(alpha * 100).toInt()}%", { alpha = (alpha - 0.1f).coerceAtLeast(0.2f); DanmakuPrefs.opacity = alpha; changed() }, { alpha = (alpha + 0.1f).coerceAtMost(1f); DanmakuPrefs.opacity = alpha; changed() })
            DmStep("字号", "${(size * 100).toInt()}%", { size = (size - 0.1f).coerceAtLeast(0.6f); DanmakuPrefs.size = size; changed() }, { size = (size + 0.1f).coerceAtMost(1.6f); DanmakuPrefs.size = size; changed() })
            DmStep("显示区域（密度）", when { area <= 0.25f -> "1/4 屏"; area <= 0.5f -> "半屏"; area <= 0.75f -> "3/4 屏"; else -> "全屏" },
                { area = (area - 0.25f).coerceAtLeast(0.25f); DanmakuPrefs.area = area; changed() }, { area = (area + 0.25f).coerceAtMost(1f); DanmakuPrefs.area = area; changed() })
            DmStep("速度", "%.1f×".format(speed), { speed = (speed - 0.25f).coerceAtLeast(0.5f); DanmakuPrefs.speed = speed; changed() }, { speed = (speed + 0.25f).coerceAtMost(2f); DanmakuPrefs.speed = speed; changed() })
            DmSwitch("滚动弹幕", sc) { sc = it; DanmakuPrefs.scroll = it; changed() }
            DmSwitch("顶部弹幕", tp) { tp = it; DanmakuPrefs.top = it; changed() }
            DmSwitch("底部弹幕", bt) { bt = it; DanmakuPrefs.bottom = it; changed() }
            DmSwitch("彩色弹幕", col) { col = it; DanmakuPrefs.colors = it; changed() }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成", color = C.Accent) } })
}

@Composable
private fun DmStep(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onMinus) { Text("−", color = C.Text, fontSize = 18.sp) }
        Text(value, color = C.Text, fontSize = 13.sp, modifier = Modifier.widthIn(min = 56.dp), textAlign = TextAlign.Center)
        TextButton(onPlus) { Text("+", color = C.Text, fontSize = 18.sp) }
    }
}

@Composable
private fun DmSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent))
    }
}

/** 手动匹配: search dandanplay by title (+ episode), pick one; the choice is remembered for this file. */
@Composable
fun DanmakuSearchDialog(initial: String, episode: Int?, onPick: (DmMatch) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(initial) }
    var ep by remember { mutableStateOf(episode?.toString() ?: "") }
    var res by remember { mutableStateOf<List<DmMatch>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun go() = scope.launch {
        busy = true; err = null
        runCatching { withContext(Dispatchers.IO) { DanDan.search(q.trim(), null, false, ep.toIntOrNull()) } }
            .onSuccess { l -> res = l.flatMap { it.second }.take(80); if (res!!.isEmpty()) err = "没有结果，换个名字（中文 / 日文 / 英文都行）或去掉集数" }
            .onFailure { err = it.message }
        busy = false
    }
    LaunchedEffect(Unit) { if (DanmakuPrefs.configured && q.length >= 2) go() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("手动匹配弹幕", color = C.Text) }, text = {
        Column {
            if (!DanmakuPrefs.configured) { Text(DanmakuPrefs.HINT, color = C.Sub, fontSize = 13.sp); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("作品名") }, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent))
                OutlinedTextField(ep, { ep = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("集") }, modifier = Modifier.padding(start = 8.dp).width(70.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent))
            }
            TextButton(onClick = { go() }, enabled = !busy && q.trim().length >= 2) { Text(if (busy) "搜索中…" else "搜索", color = C.Accent) }
            err?.let { Text(it, color = C.Danger, fontSize = 12.sp) }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(res.orEmpty(), key = { it.episodeId }) { m ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(m); onDismiss() }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text(m.anime, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.episode, color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("关闭", color = C.Sub) } })
}
