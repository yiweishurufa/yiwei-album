package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Dav
import com.hark.shiguang.Hidden
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.Repo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 高级搜索 (1.0.9): date range, camera, file type, video length. Applied on top of the text search,
 * or on its own when the text is empty.
 */
object AdvFilter {
    val TYPES = listOf("全部", "照片", "视频", "实况", "截图")
    val DURS = listOf("不限", "30 秒内", "30 秒–3 分钟", "3 分钟以上")
    var from by mutableStateOf<LocalDate?>(null)
    var to by mutableStateOf<LocalDate?>(null)
    var camera by mutableStateOf("")
    var type by mutableIntStateOf(0)
    var dur by mutableIntStateOf(0)
    val active: Boolean get() = from != null || to != null || camera.isNotEmpty() || type != 0 || dur != 0
    fun reset() { from = null; to = null; camera = ""; type = 0; dur = 0 }

    fun cameraOf(p: Photo): String =
        if (p.isCloud) Dav.lib(p.source).meta[p.cloudPath]?.cam.orEmpty()
        else (if (p.model.startsWith(p.make, true)) p.model else "${p.make} ${p.model}").trim()

    fun isScreenshot(p: Photo): Boolean {
        val n = p.fileName.lowercase(); val path = p.path.lowercase()
        return n.startsWith("screenshot") || n.contains("截屏") || n.contains("截图") || path.contains("screenshot") ||
            (p.isCloud && Dav.lib(p.source).meta[p.cloudPath]?.aiCat == "截图")
    }

    private fun dateOf(p: Photo): LocalDate? = runCatching { LocalDate.of(p.time.take(4).toInt(), p.time.substring(5, 7).toInt(), p.time.substring(8, 10).toInt()) }.getOrNull()

    fun matches(p: Photo): Boolean {
        if (from != null || to != null) {
            val d = dateOf(p) ?: return false
            if (from != null && d < from) return false
            if (to != null && d > to) return false
        }
        when (type) {
            1 -> if (p.isVideo) return false
            2 -> if (!p.isVideo) return false
            3 -> if (!p.isLive) return false
            4 -> if (p.isVideo || !isScreenshot(p)) return false
        }
        if (dur != 0) {
            if (!p.isVideo || p.duration <= 0) return false
            val ok = when (dur) { 1 -> p.duration < 30; 2 -> p.duration in 30..180; else -> p.duration > 180 }
            if (!ok) return false
        }
        if (camera.isNotEmpty() && cameraOf(p) != camera) return false
        return true
    }

    fun apply(l: List<Photo>): List<Photo> = Hidden.visible(if (active) l.filter(::matches) else l)

    fun chips(): List<String> = listOfNotNull(
        if (from != null || to != null) "${from?.let(::fmt) ?: "最早"} – ${to?.let(::fmt) ?: "现在"}" else null,
        camera.ifEmpty { null },
        if (type != 0) TYPES[type] else null,
        if (dur != 0) DURS[dur] else null,
    )
    private fun fmt(d: LocalDate) = "${d.year}.${d.monthValue}.${d.dayOfMonth}"

    /**
     * Pool searched when the text is empty. WebDAV: the whole index. 飞牛: by date range straight from the
     * gallery list API (up to 3000), otherwise the photos the timeline already loaded.
     */
    suspend fun pool(dav: Boolean): Pair<List<Photo>, String> {
        if (dav) {
            val lib = Dav.lib() ?: return emptyList<Photo>() to ""
            lib.ensure()
            val note = if (camera.isNotEmpty() || type == 4) "" else if (dur != 0) "WebDAV 视频没有时长信息，时长筛选不可用" else ""
            return lib.photos.toList() to note
        }
        if (from != null || to != null) {
            val s = (from ?: LocalDate.of(1970, 1, 1)); val e = (to ?: LocalDate.now())
            val start = "%04d:%02d:%02d 00:00:00".format(s.year, s.monthValue, s.dayOfMonth)
            val end = "%04d:%02d:%02d 23:59:59".format(e.year, e.monthValue, e.dayOfMonth)
            val out = ArrayList<Photo>(); var off = 0
            while (out.size < 3000) {
                val (l, more) = Repo.photos(start, end, off, 300)
                out.addAll(l); off += l.size
                if (!more || l.isEmpty()) break
            }
            return out to (if (out.size >= 3000) "只显示前 3000 项" else "")
        }
        return HomeState.timeline.photos.toList() to "只在已加载的时间线里筛选，日期范围更准确"
    }

    fun cameras(dav: Boolean): List<String> {
        val src = if (dav) Dav.lib()?.photos?.toList().orEmpty() else HomeState.timeline.photos.toList()
        return src.asSequence().map { cameraOf(it) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(30).map { it.key }
    }
}

/** Row under the search box: 「筛选」 + the active filters. */
@Composable
fun FilterChipsRow(onOpen: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (AdvFilter.active) C.Accent.copy(alpha = 0.16f) else C.Surface).clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = if (AdvFilter.active) C.Accent else C.Sub, modifier = Modifier.size(16.dp))
            Text("筛选", color = if (AdvFilter.active) C.Accent else C.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
        AdvFilter.chips().forEach { c ->
            Text(c, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (AdvFilter.active) Text("清除", color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(dav: Boolean, onDismiss: () -> Unit, onApply: () -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    val cams = remember(dav) { AdvFilter.cameras(dav) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("筛选", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Label("日期")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = LocalDate.now()
                Choice("不限", AdvFilter.from == null && AdvFilter.to == null) { AdvFilter.from = null; AdvFilter.to = null }
                Choice("近 30 天", AdvFilter.from == now.minusDays(30) && AdvFilter.to == null) { AdvFilter.from = now.minusDays(30); AdvFilter.to = null }
                Choice("今年", AdvFilter.from == LocalDate.of(now.year, 1, 1) && AdvFilter.to == null) { AdvFilter.from = LocalDate.of(now.year, 1, 1); AdvFilter.to = null }
                Choice("去年", AdvFilter.from == LocalDate.of(now.year - 1, 1, 1)) { AdvFilter.from = LocalDate.of(now.year - 1, 1, 1); AdvFilter.to = LocalDate.of(now.year - 1, 12, 31) }
                Choice(AdvFilter.chips().firstOrNull()?.takeIf { AdvFilter.from != null || AdvFilter.to != null }?.let { "自定义：$it" } ?: "自定义范围…", false) { pickDate = true }
            }
            Label("类型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdvFilter.TYPES.forEachIndexed { i, t -> Choice(t, AdvFilter.type == i) { AdvFilter.type = i } }
            }
            Label("视频时长")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdvFilter.DURS.forEachIndexed { i, t -> Choice(t, AdvFilter.dur == i) { AdvFilter.dur = i; if (i != 0 && AdvFilter.type == 1) AdvFilter.type = 0 } }
            }
            if (dav) Text("WebDAV 视频读不到时长，选时长时它们不会出现。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Label("相机")
            if (cams.isEmpty()) Text(if (dav) "还没有相机信息（后台整理时会补读相机型号，稍后再看）。" else "已加载的照片里没有相机信息。", style = MaterialTheme.typography.bodySmall)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("不限", AdvFilter.camera.isEmpty()) { AdvFilter.camera = "" }
                cams.forEach { c -> Choice(c, AdvFilter.camera == c) { AdvFilter.camera = c } }
            }
            Row(Modifier.padding(top = 22.dp)) {
                TextButton(onClick = { AdvFilter.reset() }) { Text("重置", color = C.Sub) }
                Spacer(Modifier.weight(1f))
                GradientButton("查看结果", Modifier.width(160.dp)) { onApply() }
            }
        }
    }
    if (pickDate) {
        val st = rememberDateRangePickerState(
            initialSelectedStartDateMillis = AdvFilter.from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = AdvFilter.to?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton(onClick = {
                fun d(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                AdvFilter.from = d(st.selectedStartDateMillis); AdvFilter.to = d(st.selectedEndDateMillis) ?: AdvFilter.from
                pickDate = false
            }) { Text("确定", color = C.Accent) }
        }, dismissButton = { TextButton(onClick = { pickDate = false }) { Text("取消", color = C.Sub) } }) {
            DateRangePicker(st, Modifier.weight(1f), title = { Text("选择日期范围", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
private fun Choice(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, color = if (on) C.OnAccent else C.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Accent else C.Surface2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}
