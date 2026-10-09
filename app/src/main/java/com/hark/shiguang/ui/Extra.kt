package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.*
import kotlinx.coroutines.launch

// ============================================================ 视频 tab

/** NAS: pages the whole timeline and keeps videos only (the list API has no type filter we can rely on). */
object NasVideos {
    var src: PhotoSource? = null
    var forAccount = ""
    fun get(): PhotoSource {
        val id = NasAccounts.currentId
        if (src == null || forAccount != id) {
            forAccount = id
            var cursor = 0
            src = PhotoSource(pageSize = 60) { _, limit ->
                val out = ArrayList<Photo>(); var more = true; var guard = 0
                while (out.size < limit && more && guard++ < 40) {
                    val (l, m) = Repo.photos(Repo.FAR_START, Repo.FAR_END, cursor, 500)
                    cursor += l.size; more = m && l.isNotEmpty()
                    out.addAll(l.filter { it.isVideo })
                }
                out to more
            }
        }
        return src!!
    }
}

@Composable
fun VideosTab(dav: Boolean) {
    val scope = rememberCoroutineScope()
    val grid = rememberMemGrid(if (dav) "dav-videos-${Dav.lib()?.accountId}" else "fn-videos-${NasAccounts.currentId}")
    val cols = HomeState.columns.coerceAtMost(4)
    Box(Modifier.fillMaxSize()) {
        if (dav) {
            val lib = Dav.lib()
            if (lib == null) { Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "还没有 WebDAV", "", "添加") { Nav.push(Route.DavAccounts) } } }; return@Box }
            LaunchedEffect(lib) { lib.ensure() }
            val vids by remember(lib) { derivedStateOf { lib.metaVersion; MediaIsolation.version; Hidden.visible(MediaIsolation.davVisible(lib.accountId, lib.photos.filter { it.isVideo })) } }
            val entries = remember(vids) { buildEntries(vids, true) }
            if (vids.isEmpty()) Column { TopSwitch(); Title("视频", if (lib.scanning) "正在扫描…" else "0 个"); Box(Modifier.weight(1f)) { if (lib.scanning) SkeletonGrid(cols, 0.dp) else StateMessage(LI.videos(), "没有视频", "") } }
            else PhotoGrid(entries, cols, lib.scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(vids, it)) }, state = grid,
                header = { Column { TopSwitch(); Title("视频", "${vids.size} 个" + if (lib.scanning) " · 正在扫描…" else "") } })
        } else {
            val src = remember(NasAccounts.currentId) { NasVideos.get() }
            LaunchedEffect(src) { if (!src.started) { src.started = true; src.next() } }
            val shown by remember(src) { derivedStateOf { MediaIsolation.version; Hidden.visible(MediaIsolation.fnVisible(src.photos.toList())) } }
            val entries by remember(src) { derivedStateOf { buildEntries(shown, true) } }
            when {
                src.photos.isEmpty() && src.loading -> Column { TopSwitch(); Title("视频", "正在查找…"); SkeletonGrid(cols, 0.dp) }
                src.photos.isEmpty() && src.error != null -> Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.videos(), "加载失败", src.error!!, "重试") { scope.launch { src.refresh() } } } }
                src.photos.isEmpty() -> Column { TopSwitch(); Title("视频", "0 个"); Box(Modifier.weight(1f)) { StateMessage(LI.videos(), "没有视频", "") } }
                else -> PhotoGrid(entries, cols, src.loading, onEnd = { scope.launch { src.next() } }, onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid,
                    header = { Column { TopSwitch(); Title("视频", "${shown.size}${if (src.more) "+" else ""} 个") } })
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = {})
    }
}

@Composable
private fun Title(t: String, sub: String) {
    Column(Modifier.padding(start = 18.dp, top = 10.dp, bottom = 12.dp)) {
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text)
        if (sub.isNotEmpty()) Text(sub, color = C.Sub, fontSize = 14.sp)
    }
}

// ============================================================ 按日期筛选

@Composable
fun DateFilterDialog(onDismiss: () -> Unit) {
    val now = java.util.Calendar.getInstance()
    var year by remember { mutableIntStateOf(now.get(java.util.Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(0) } // 0 = 全年
    var day by remember { mutableIntStateOf(0) }   // 0 = 整月
    val years = (now.get(java.util.Calendar.YEAR) downTo 1990).toList()
    val days = if (month == 0) 0 else java.util.GregorianCalendar(year, month - 1, 1).getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    if (day > days) day = 0
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("按日期筛选", color = C.Text) }, text = {
        Column {
            Text("年", color = C.Sub, fontSize = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) { items(years) { y -> Chip("$y", year == y) { year = y } } }
            Text("月", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) { items((0..12).toList()) { m -> Chip(if (m == 0) "全年" else "${m}月", month == m) { month = m; day = 0 } } }
            if (month > 0) {
                Text("日", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) { items((0..days).toList()) { d -> Chip(if (d == 0) "整月" else "${d}日", day == d) { day = d } } }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { onDismiss(); openDate(year, month, day) }) { Text("查看", color = C.Accent) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
}

@Composable
private fun Chip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 13.sp, color = if (on) C.OnAccent else C.Text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Accent else C.Chip)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

private fun openDate(y: Int, m: Int, d: Int) {
    val title = "${y}年" + (if (m > 0) "${m}月" else "") + (if (d > 0) "${d}日" else "")
    val prefix = "%04d".format(y) + (if (m > 0) ":%02d".format(m) else "") + (if (d > 0) ":%02d".format(d) else "")
    if (HomeState.source == "dav") {
        val lib = Dav.lib() ?: return
        Nav.push(Route.Collection(title, "WebDAV", PhotoSource(pageSize = 100000) { o, _ ->
            if (o > 0) emptyList<Photo>() to false else lib.photos.filter { it.time.startsWith(prefix) } to false
        }))
    } else {
        val start = when { d > 0 -> "$prefix 00:00:00"; m > 0 -> "$prefix:01 00:00:00"; else -> "$prefix:01:01 00:00:00" }
        val end = when {
            d > 0 -> "$prefix 23:59:59"
            m > 0 -> "$prefix:%02d 23:59:59".format(java.util.GregorianCalendar(y, m - 1, 1).getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
            else -> "$prefix:12:31 23:59:59"
        }
        Nav.push(Route.Collection(title, "飞牛", PhotoSource { o, l -> Repo.photos(start, end, o, l) }))
    }
}
