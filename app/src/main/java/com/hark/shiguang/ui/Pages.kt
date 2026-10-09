package com.hark.shiguang.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import com.hark.shiguang.cloud.*
import com.hark.shiguang.data.*
import kotlinx.coroutines.launch

// ============================================================ shared chrome

@Composable
fun PageTop(title: String, sub: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.ArrowBackIosNew, size = 40.dp) { Selection.clear(); Nav.pop() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
internal fun Page(title: String, sub: String = "", actions: @Composable RowScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        PageTop(title, sub, actions)
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(28.dp)) }

@Composable
private fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) C.Accent else C.Chip).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = if (on) C.OnAccent else C.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun ListRow(icon: ImageVector?, image: String?, title: String, sub: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
            if (image != null) NetImage(image, Modifier.fillMaxSize()) else if (icon != null) Icon(icon, null, tint = C.Sub, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

internal fun fmtBytes(b: Long): String = when {
    b <= 0 -> ""
    b < 1 shl 20 -> "%.0f KB".format(b / 1024f)
    b < 1 shl 30 -> "%.1f MB".format(b / 1048576f)
    else -> "%.2f GB".format(b / 1073741824f)
}

/** Simple photo grid with a local selection set (used by recycle bin). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PickGrid(photos: List<Photo>, selected: Set<Int>, onTap: (Photo, Int) -> Unit, onLong: (Photo) -> Unit, onEnd: () -> Unit = {}) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 120.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(photos.size, key = { photos[it].key }) { i ->
            val p = photos[i]
            if (i >= photos.size - 8) LaunchedEffect(photos.size) { onEnd() }
            val on = p.id in selected
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(C.Surface)
                .combinedClickable(onClick = { onTap(p, i) }, onLongClick = { onLong(p) })) {
                NetImage(p.thumbS, Modifier.fillMaxSize())
                if (selected.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape)
                    .background(if (on) C.Accent else Color(0x55000000)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Thumbnail that joins the global multi-select on long press. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SelCell(p: Photo, onOpen: () -> Unit) {
    val on = Selection.active && Selection.has(p)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(C.Surface)
        .combinedClickable(onClick = { if (Selection.active) Selection.toggle(p) else onOpen() }, onLongClick = { Selection.start(p) })) {
        NetImage(p.thumbS, Modifier.fillMaxSize())
        if (p.isVideo) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp))
        if (Selection.active) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape)
            .background(if (on) C.Accent else Color(0x55000000)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Icon(Icons.Rounded.Check, null, tint = C.OnAccent, modifier = Modifier.size(14.dp))
        }
    }
}

// ============================================================ recycle bin

@Composable
fun RecycleScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<Photo>() }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf(setOf<Int>()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    suspend fun load(reset: Boolean) {
        if (reset) { photos.clear(); more = true }
        if (!more) return
        loading = true; err = null
        runCatching { NasX.recycleList(photos.size, 120) }.onSuccess { photos.addAll(it); more = it.size >= 120 }.onFailure { err = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load(true) }
    Page("回收站", if (photos.isNotEmpty()) "${photos.size}${if (more) "+" else ""} 项 · 删除 30 天后自动清除" else "", actions = {
        if (photos.isNotEmpty() && sel.isEmpty()) TextButton(onClick = { confirm = "clear" }) { Text("清空", color = C.Danger) }
        if (sel.isNotEmpty()) TextButton(onClick = { sel = emptySet() }) { Text("取消", color = C.Sub) }
    }) {
        when {
            photos.isEmpty() && loading -> Loading()
            photos.isEmpty() && err != null -> StateMessage(Icons.Rounded.CloudOff, "加载失败", err!!, "重试") { scope.launch { load(true) } }
            photos.isEmpty() -> StateMessage(Icons.Rounded.DeleteOutline, "回收站是空的", "删除的照片会在这里保留 30 天。")
            else -> PickGrid(photos, sel, onTap = { p, i ->
                if (sel.isNotEmpty()) sel = if (p.id in sel) sel - p.id else sel + p.id
                else Nav.push(Route.Viewer(photos.toList(), i))
            }, onLong = { p -> sel = sel + p.id }, onEnd = { scope.launch { load(false) } })
        }
        if (sel.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(C.Surface).navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val ids = sel.toList()
                scope.launch {
                    runCatching { NasX.recycleRestore(ids) }.onSuccess { toast(ctx, "已恢复 ${ids.size} 项"); photos.removeAll { it.id in ids }; sel = emptySet(); HomeState.dirty = true }
                        .onFailure { toast(ctx, it.message ?: "恢复失败") }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnAccent), modifier = Modifier.weight(1f)) { Text("恢复 ${sel.size} 项") }
            OutlinedButton(onClick = { confirm = "forever" }, modifier = Modifier.weight(1f)) { Text("彻底删除", color = C.Danger) }
        }
    }
    when (confirm) {
        "clear" -> ConfirmDialog("清空回收站？", "所有照片会被永久删除，无法恢复。", "清空", onDismiss = { confirm = null }) {
            confirm = null
            scope.launch { runCatching { NasX.recycleClear() }.onSuccess { photos.clear(); more = false }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
        "forever" -> ConfirmDialog("彻底删除 ${sel.size} 项？", "删除后无法恢复。", "彻底删除", onDismiss = { confirm = null }) {
            confirm = null
            val ids = sel.toList()
            scope.launch { runCatching { NasX.recycleDeleteForever(ids) }.onSuccess { photos.removeAll { it.id in ids }; sel = emptySet() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
}

// ============================================================ smart categories

@Composable
fun SmartScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var scene by remember { mutableStateOf<List<SmartCategory>?>(null) }
    var info by remember { mutableStateOf<List<SmartCategory>?>(null) }
    var media by remember { mutableStateOf<List<MediaCategory>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) {
        err = null
        runCatching {
            when (tab) {
                0 -> if (scene == null) scene = NasX.smartCategories("scene")
                1 -> if (info == null) info = NasX.smartCategories("information")
                else -> if (media == null) media = NasX.mediaCategories()
            }
        }.onFailure { err = it.message }
    }
    Page("智能分类") {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("场景与事物", tab == 0) { tab = 0 }; Pill("文档与信息", tab == 1) { tab = 1 }; Pill("媒体类型", tab == 2) { tab = 2 }
            }
            data class Card(val title: String, val count: Int, val cover: String?, val open: () -> Unit)
            val cards: List<Card>? = when (tab) {
                0 -> scene?.map { c -> Card(c.name, c.count, c.cover) { openCategory(c.name, c.count) } }
                1 -> info?.map { c -> Card(c.name, c.count, c.cover) { openCategory(c.name, c.count) } }
                else -> media?.map { m -> Card(mediaLabel(m.key), m.count, m.cover) {
                    Nav.push(Route.Collection(mediaLabel(m.key), "${m.count} 项", PhotoSource(grouped = true) { o, _ -> if (o > 0) emptyList<Photo>() to false else NasX.mediaCategoryPhotos(m.key) to false }))
                } }
            }
            Box(Modifier.weight(1f)) {
                when {
                    err != null && cards == null -> StateMessage(Icons.Rounded.AutoAwesome, "加载失败", err!!)
                    cards == null -> Loading()
                    cards.isEmpty() -> StateMessage(Icons.Rounded.AutoAwesome, "还没有分类", "需要在飞牛相册里开启 AI 识别，识别完成后会出现在这里。")
                    else -> LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(cards) { c ->
                            Column(Modifier.clickable(onClick = c.open)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(C.Surface2)) { if (c.cover != null) NetImage(c.cover, Modifier.fillMaxSize()) }
                                Text(c.title, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text("${c.count}", color = C.Sub, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openCategory(name: String, count: Int) {
    Nav.push(Route.Collection(name, "$count 项", PhotoSource(pageSize = 100) { o, l -> val r = NasX.categoryPhotos(name, o, l); r to (r.size >= l) }))
}

private fun mediaLabel(k: String) = when (k) {
    "photo" -> "照片"; "video" -> "视频"; "live_photo" -> "实况照片"; "gif" -> "动图"; "raw" -> "RAW"
    "360" -> "360° 全景"; "panorama" -> "全景"; "screenshot" -> "截图"; else -> k
}

// ============================================================ duplicates

@Composable
fun DuplicatesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableStateListOf<RepeatGroup>() }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    suspend fun load(reset: Boolean) {
        if (reset) groups.clear()
        loading = true; err = null
        runCatching { NasX.repeatGroups(groups.size, 30) }.onSuccess { groups.addAll(it.groups); total = it.count }.onFailure { err = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load(true) }
    Page("重复照片", if (total > 0) "$total 组" else "", actions = {
        // 1.0.2: no scan button here; 飞牛's repeat check is started together with the AI tab's 整理 (see DiscoverTab)
        RoundIcon(Icons.Rounded.Refresh) { scope.launch { load(true) } }
        if (groups.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("全部清理", color = C.Accent) }
    }) {
        when {
            groups.isEmpty() && loading -> Loading()
            groups.isEmpty() && err != null -> StateMessage(Icons.Rounded.CloudOff, "加载失败", err!!, "重试") { scope.launch { load(true) } }
            groups.isEmpty() -> StateMessage(Icons.Rounded.FilterNone, "暂时没有重复照片", "重复和相似检测随 AI 页的整理在 NAS 上进行，有结果会显示在这里。", "去 AI 页") { HomeState.openAiTab() }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(groups.size, key = { groups[it].id }) { gi ->
                    val g = groups[gi]
                    if (gi >= groups.size - 3 && groups.size < total && !loading) LaunchedEffect(groups.size) { load(false) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(12.dp)) {
                        Text("${g.photos.size} 张 · ${g.photos.firstOrNull()?.day?.replace(':', '.') ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            g.photos.forEachIndexed { i, p ->
                                Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).clickable { Nav.push(Route.Viewer(g.photos, i)) }) {
                                    NetImage(p.thumbS, Modifier.fillMaxSize())
                                    if (i == g.recommendedIndex) Box(Modifier.align(Alignment.BottomStart).background(C.Accent).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text("保留", color = C.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch { runCatching { NasX.repeatMerge(g.id) }.onSuccess { groups.remove(g); total--; HomeState.dirty = true; toast(ctx, "其余照片已移到回收站") }.onFailure { toast(ctx, it.message ?: "失败") } }
                            }) { Text("保留推荐，清理其余", color = C.Accent) }
                            TextButton(onClick = {
                                scope.launch { runCatching { NasX.repeatExcludeGroup(g.id) }.onSuccess { groups.remove(g); total-- }.onFailure { toast(ctx, it.message ?: "失败") } }
                            }) { Text("都保留", color = C.Sub) }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) ConfirmDialog("清理全部 $total 组？", "每组保留推荐的一张，其余移到回收站。", "清理", onDismiss = { confirmAll = false }) {
        confirmAll = false
        scope.launch { runCatching { NasX.repeatMerge(null) }.onSuccess { groups.clear(); total = 0; HomeState.dirty = true }.onFailure { toast(ctx, it.message ?: "失败") } }
    }
}

// ============================================================ folders

@Composable
fun FoldersScreen(r: Route.Folders) {
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<Pair<String, String>>?>(null) } // path to name
    val photos = remember { mutableStateListOf<Photo>() }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    suspend fun more() {
        if (r.path.isEmpty() || loading || (total > 0 && photos.size >= total)) return
        loading = true
        runCatching { NasX.folderPhotos(r.path, photos.size, 100) }.onSuccess { (l, t) -> photos.addAll(l); total = t }.onFailure { err = it.message }
        loading = false
    }
    LaunchedEffect(r.path) {
        runCatching {
            folders = if (r.path.isEmpty()) NasX.libraryFolders().map { it.path to it.path.trimEnd('/').substringAfterLast('/').ifEmpty { it.path } }
            else NasX.subFolders(r.path).map { it.path to it.name }
        }.onFailure { err = it.message; folders = emptyList() }
        more()
    }
    Page(r.title, if (r.path.isNotEmpty()) r.path else "相册里的照片文件夹") {
        val f = folders
        when {
            f == null -> Loading()
            f.isEmpty() && photos.isEmpty() && err != null -> StateMessage(Icons.Rounded.FolderOff, "加载失败", err!!)
            f.isEmpty() && photos.isEmpty() && !loading -> StateMessage(Icons.Rounded.Folder, "空文件夹", "")
            else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp, 0.dp, 2.dp, 60.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(f, span = { GridItemSpan(4) }, key = { "d" + it.first }) { (path, name) ->
                    ListRow(Icons.Rounded.Folder, null, name, "", trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = C.Faint) }) {
                        Nav.push(Route.Folders(path, name))
                    }
                }
                items(photos.size, key = { "p" + photos[it].key }) { i ->
                    if (i >= photos.size - 12) LaunchedEffect(photos.size) { more() }
                    SelCell(photos[i]) { Nav.push(Route.Viewer(photos.toList(), i)) }
                }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter))
    }
}

// ============================================================ map (no map SDK: places by cluster)

@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    var points by remember { mutableStateOf<List<MapPoint>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { NasX.mapPoints(MapBounds(90.0, 180.0, -90.0, -180.0)) }
            .onSuccess { points = it.sortedByDescending { p -> p.count } }.onFailure { err = it.message; points = emptyList() }
    }
    val places = HomeState.places
    Page("地图", points?.let { "${it.sumOf { p -> p.count }} 张带位置的照片" } ?: "") {
        val pts = points
        when {
            pts == null -> Loading()
            pts.isEmpty() && places.isNullOrEmpty() -> StateMessage(Icons.Rounded.Map, if (err != null) "加载失败" else "还没有带位置的照片", err ?: "拍照时开启定位，NAS 识别后会出现在这里。")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(pts.size) { i ->
                    val p = pts[i]
                    ListRow(null, p.cover, "%.4f, %.4f".format(p.lat, p.lng), "${p.count} 张" + if (p.dateTime.length >= 10) " · ${p.dateTime.take(10).replace(':', '.')}" else "",
                        trailing = {
                            IconButton(onClick = {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    .onFailure { toast(ctx, "没有可用的地图应用") }
                            }) { Icon(Icons.Rounded.Place, null, tint = C.Accent) }
                        }) {
                        val d = 0.02
                        val b = MapBounds(p.lat + d, p.lng + d, p.lat - d, p.lng - d)
                        Nav.push(Route.Collection("%.3f, %.3f".format(p.lat, p.lng), "附近的照片", PhotoSource(pageSize = 100) { o, l -> NasX.mapPhotos(b, o, l) }))
                    }
                }
            }
        }
    }
}

// ============================================================ transfers

@Composable
fun TransfersScreen() {
    Page("传输队列", if (Transfers.active > 0) "${Transfers.active} 个进行中" else "", actions = {
        if (Transfers.tasks.any { it.state == TState.DONE }) TextButton(onClick = { Transfers.clearFinished() }) { Text("清除已完成", color = C.Sub) }
    }) {
        if (Transfers.tasks.isEmpty()) StateMessage(Icons.Rounded.SwapVert, "没有传输任务", "下载的照片保存在手机的 Pictures/一维相册。")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(Transfers.tasks, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.upload) Icons.Rounded.Upload else Icons.Rounded.Download, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val st = when (t.state) {
                            TState.QUEUED -> "等待中"; TState.RUNNING -> "${(t.progress * 100).toInt()}%"
                            TState.DONE -> "完成 · ${t.target}"; TState.FAILED -> t.error ?: "失败"
                        }
                        Text(st, color = if (t.state == TState.FAILED) C.Danger else C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.state == TState.RUNNING) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = C.Accent, trackColor = C.Surface2)
                    }
                    if (t.state == TState.FAILED) IconButton(onClick = { Transfers.retry(t) }) { Icon(Icons.Rounded.Refresh, null, tint = C.Accent) }
                    if (t.state == TState.DONE) Icon(Icons.Rounded.CheckCircle, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ============================================================ upload (share-in / picked)

@Composable
fun UploadScreen(r: Route.Upload) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var target by remember { mutableStateOf("nas") } // "nas" or cloud account id
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var album by remember { mutableStateOf<Album?>(null) }
    LaunchedEffect(r) {
        items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.uris.mapNotNull { runCatching { LocalMediaReader.describe(ctx, it) }.getOrNull() } }
        runCatching { Repo.albums() }.onSuccess { albums = it }
    }
    val cloudAccounts = remember { CloudAccounts.all }
    Page("上传", items?.let { "${it.size} 个文件 · ${fmtBytes(it.sumOf { m -> m.size.coerceAtLeast(0) })}" } ?: "") {
        val l = items
        if (l == null) Loading() else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                l.take(30).forEach { m -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2)) { NetImage(m.uri.toString(), Modifier.fillMaxSize()) } }
            }
            Section("上传到")
            ListRow(Icons.Rounded.Dns, null, "飞牛 NAS 相册", "上传到相册的备份目录", trailing = { RadioButton(target == "nas", { target = "nas" }) }) { target = "nas" }
            cloudAccounts.forEach { a ->
                ListRow(Icons.Rounded.Cloud, null, a.title, kindName(a.kind) + " · 根目录 /一维相册", trailing = { RadioButton(target == a.id, { target = a.id }) }) { target = a.id }
            }
            if (target == "nas" && albums.isNotEmpty()) {
                Section("同时加入相册（可选）")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("不加入", album == null) { album = null }
                    albums.forEach { a -> Pill(a.name, album?.id == a.id) { album = a } }
                }
            }
            Spacer(Modifier.height(24.dp))
            GradientButton("开始上传", Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                val c = ctx.applicationContext
                val tgt = target; val alb = album
                l.forEach { m ->
                    if (tgt == "nas") Transfers.enqueue(m.name, true, alb?.name ?: "NAS 相册") { progress ->
                        NasX.upload(m.name, m.size, m.mime, { c.contentResolver.openInputStream(m.uri) ?: throw Exception("无法读取文件") },
                            UploadTarget.Library(alb?.id), onProgress = { s, t -> if (t > 0) progress(s.toFloat() / t) })
                        HomeState.dirty = true
                    } else {
                        val acc = CloudAccounts.get(tgt)
                        Transfers.enqueue(m.name, true, acc?.title ?: "网盘") { _ ->
                            val src = CloudPhotos.source(tgt) ?: throw Exception("网盘账户已移除")
                            runCatching { src.mkdir("/", "一维相册") }
                            src.upload("/一维相册", m.name, m.size) { c.contentResolver.openInputStream(m.uri) ?: throw Exception("无法读取文件") }
                        }
                    }
                }
                Nav.pop(); Nav.push(Route.Transfers)
            }
        }
    }
}

// ============================================================ backup

@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Store.backupOn) }
    var running by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(-1) }
    fun refreshPending() { scope.launch { pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocalMediaReader.newSince(ctx, Store.backupSince).size } } }
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            on = true; Store.backupOn = true
            if (Store.backupSince == 0L) Store.backupSince = System.currentTimeMillis() / 1000
            BackupScheduler.schedule(ctx); refreshPending()
        } else toast(ctx, "需要相册权限才能自动备份")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickLimit())) { uris ->
        if (uris.isNotEmpty()) Nav.push(Route.Upload(uris))
    }
    LaunchedEffect(Unit) { if (on) refreshPending() }
    Page("手机相册备份") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Section("手动")
            NavRow(Icons.Rounded.AddPhotoAlternate, "选择照片上传", "从手机相册挑选照片或视频") {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            Section("自动备份")
            ToggleRow(Icons.Rounded.Backup, "自动备份新照片", if (on) "每小时检查一次，从开启那一刻起的新照片会上传到 NAS" else "开启后只备份之后新拍的照片", on) { v ->
                if (v) permLauncher.launch(perms) else { on = false; Store.backupOn = false; BackupScheduler.cancel(ctx) }
            }
            Text(if (ScanPolicy.allowCellular) "Wi-Fi 和移动网络都会备份（设置 → 网络与电量）" else "只在 Wi-Fi 下备份（设置 → 网络与电量 可允许移动网络）",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            if (on) {
                NavRow(Icons.Rounded.CloudUpload, if (running) "正在加入队列…" else "立即备份",
                    when { pending > 0 -> "有 $pending 项待备份"; pending == 0 -> "已全部备份" + if (BackupRunner.lastResult.isNotEmpty()) " · 上次：${BackupRunner.lastResult}" else ""; else -> "" }) {
                    if (running) return@NavRow
                    running = true
                    scope.launch {
                        runCatching { BackupRunner.run(ctx.applicationContext, true) }.onSuccess { n -> toast(ctx, if (n == 0) "没有新照片" else "已加入 $n 项到传输队列") }
                            .onFailure { toast(ctx, it.message ?: "失败") }
                        running = false; refreshPending()
                    }
                }
                NavRow(Icons.Rounded.History, "把已有照片也备份", "从最早的照片开始补传（可能很多）") {
                    Store.backupSince = 0L; refreshPending(); toast(ctx, "下次备份会从最早的照片开始")
                }
            }
            Text("照片上传到飞牛相册设置的上传目录。备份只上传，不会删除手机上的照片。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
        }
    }
}

// ============================================================ WebDAV accounts

fun kindName(k: CloudKind) = "WebDAV"

@Composable
fun CloudHomeScreen() = DavAccountsScreen()

@Composable
fun DavAccountsScreen() {
    val ctx = LocalContext.current
    var accounts by remember { mutableStateOf(Dav.accounts) }
    var editing by remember { mutableStateOf<CloudAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CloudAccount?>(null) }
    Page("WebDAV", "可添加多个，照片不会被移动", actions = { RoundIcon(LI.plus()) { adding = true } }) {
        if (accounts.isEmpty()) StateMessage(LI.drive(), "还没有 WebDAV", "支持 123 云盘、坚果云、群晖、Nextcloud、Alist 等任何 WebDAV 服务。", "添加") { adding = true }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(accounts, key = { it.id }) { a ->
                val cur = a.id == Dav.current?.id
                ListRow(LI.drive(), null, a.title + if (cur) "  · 当前" else "", a.url + if (a.user.isNotEmpty()) " · ${a.user}" else "",
                    trailing = { Row {
                        IconButton(onClick = { editing = a }) { Icon(LI.settings(), null, tint = C.Sub, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { removing = a }) { Icon(LI.trash(), null, tint = C.Sub, modifier = Modifier.size(20.dp)) }
                    } }) {
                    Dav.select(a.id); HomeState.useSource("dav"); Nav.reset(Route.Home); toast(ctx, "已切换到 ${a.title}")
                }
            }
        }
    }
    if (adding || editing != null) DavDialog(editing, onDismiss = { adding = false; editing = null }) { saved ->
        adding = false; editing = null; accounts = Dav.accounts
        if (Dav.accounts.size == 1 || Dav.current == null) Dav.select(saved.id)
    }
    removing?.let { a ->
        ConfirmDialog("移除「${a.title}」？", "只从本机移除，不影响 WebDAV 里的文件。", "移除", onDismiss = { removing = null }) {
            CloudAccounts.remove(a.id); Dav.forget(a.id); removing = null; accounts = Dav.accounts
        }
    }
}

@Composable
fun DavDialog(old: CloudAccount?, onDismiss: () -> Unit, onSaved: (CloudAccount) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(old?.title ?: "我的 WebDAV") }
    var url by remember { mutableStateOf(old?.url ?: "https://") }
    var user by remember { mutableStateOf(old?.user ?: "") }
    var pass by remember { mutableStateOf(old?.pass ?: "") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf<DavPreset?>(old?.url?.let { u -> DavPresets.all.firstOrNull { p -> u.trimEnd('/').equals(p.url.trimEnd('/'), true) } }) }
    fun draft(): CloudAccount {
        val u = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        return CloudAccount(old?.id ?: CloudAccounts.newId(), CloudKind.WEBDAV, title.ifBlank { "WebDAV" }, u, user.trim(), pass, old?.extra ?: emptyMap())
    }
    fun save() {
        busy = true; err = null
        scope.launch {
            val base = draft()
            runCatching { CloudSources.create(base).connect() }
                .onSuccess { CloudAccounts.save(it); if (old != null) Dav.forget(it.id); onSaved(it) }
                .onFailure { err = it.message ?: "连接失败"; busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = C.Surface, title = { Text(if (old == null) "添加 WebDAV" else "编辑 WebDAV", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 1.0.1: presets (123 云盘 / 坚果云 / Alist·OpenList / 群晖 / 威联通 / Nextcloud) fill the address and show how to get the password
            Text("常用服务", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            DavPresetRow(preset?.key) { p ->
                val oldTitle = preset?.title
                preset = p; url = p.url
                if (title.isBlank() || title == "我的 WebDAV" || title == oldTitle) title = p.title
            }
            preset?.let { p -> Text(p.help, color = C.Sub, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 10.dp)) }
            Field2("名称", title) { title = it }
            Field2("服务器地址（含路径）", url) { url = it }
            Field2(preset?.userHint?.let { "用户名（$it）" } ?: "用户名", user) { user = it }
            Field2(if (preset?.key == "123" || preset?.key == "jgy") "应用密码" else "密码", pass, secret = true) { pass = it }
            if (preset == null) Text("例：https://webdav.123pan.cn/webdav、https://dav.jianguoyun.com/dav/、http://<IP>:5244/dav", color = C.Sub, fontSize = 12.sp)
            TestButton("测试连接", Modifier.fillMaxWidth().padding(top = 10.dp), resetKey = url + "|" + user + "|" + pass, height = 44.dp) {
                val d = draft()
                // 1.0.2: with a VPN on, measure VPN vs direct first so the test itself already uses the faster route
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NetEnv.measureNow(d.url) }
                val t0 = System.currentTimeMillis()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CloudSources.create(d).connect() }
                "连接成功 · ${System.currentTimeMillis() - t0} ms" + NetEnv.hint(d.url).let { if (it.isEmpty()) "" else "\n$it" }
            }
            err?.let { Text(it, color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = { save() }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp) else Text("连接", color = C.Accent) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
}

@Composable
fun Field2(label: String, v: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(v, onChange, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent, focusedLabelColor = C.Accent))
}

@Composable
fun CloudBrowseScreen(r: Route.CloudBrowse) {
    val scope = rememberCoroutineScope()
    var dirs by remember { mutableStateOf<List<CloudEntry>?>(null) }
    var others by remember { mutableStateOf<List<CloudEntry>>(emptyList()) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        err = null
        runCatching {
            val src = CloudPhotos.source(r.accountId) ?: error("账户不存在")
            val l = src.list(r.path)
            dirs = l.filter { it.isDir }.sortedBy { it.name.lowercase() }
            others = l.filter { !it.isDir && !it.isImage && !it.isVideo }
            photos = CloudPhotos.toPhotos(r.accountId, l.filter { !it.isDir && (it.isImage || it.isVideo) }).sortedByDescending { it.time }
        }.onFailure { err = it.message ?: "加载失败"; if (dirs == null) dirs = emptyList() }
    }
    LaunchedEffect(r) { load() }
    Page(r.title, r.path, actions = {
        RoundIcon(Icons.Rounded.ViewDay) { Nav.push(Route.CloudTimeline(r.accountId)) }
    }) {
        val d = dirs
        when {
            d == null -> Loading()
            err != null && d.isEmpty() && photos.isEmpty() -> StateMessage(Icons.Rounded.CloudOff, "打不开这个目录", err!!, "重试") { scope.launch { load() } }
            d.isEmpty() && photos.isEmpty() && others.isEmpty() -> StateMessage(Icons.Rounded.FolderOpen, "空文件夹", "")
            else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp, 0.dp, 2.dp, 120.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(d, span = { GridItemSpan(4) }, key = { "d" + it.path }) { e ->
                    ListRow(Icons.Rounded.Folder, null, e.name, "", trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = C.Faint) }) {
                        Nav.push(Route.CloudBrowse(r.accountId, e.path, e.name))
                    }
                }
                items(photos.size, key = { "p" + photos[it].key }) { i ->
                    SelCell(photos[i]) { Nav.push(Route.Viewer(photos, i)) }
                }
                items(others, span = { GridItemSpan(4) }, key = { "o" + it.path }) { e ->
                    ListRow(Icons.Rounded.InsertDriveFile, null, e.name, fmtBytes(e.size)) {}
                }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { load() } })
    }
}

@Composable
fun CloudTimelineScreen(r: Route.CloudTimeline) {
    val acc = remember { CloudAccounts.get(r.accountId) }
    val photos = remember { mutableStateListOf<Photo>() }
    var scanning by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        val ch = kotlinx.coroutines.channels.Channel<List<CloudEntry>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val conv = launch { for (b in ch) photos.addAll(CloudPhotos.toPhotos(r.accountId, b)) }
        runCatching {
            val src = CloudPhotos.source(r.accountId) ?: error("账户不存在")
            src.scanMedia("/", 5000) { batch -> ch.trySend(batch) }
        }.onFailure { err = it.message ?: "扫描失败" }
        ch.close(); conv.join()
        scanning = false
    }
    val entries by remember { derivedStateOf { buildEntries(photos.sortedByDescending { it.time }, true) } }
    Page(acc?.title ?: "网盘", if (scanning) "正在扫描… 已找到 ${photos.size} 项" else "${photos.size} 项") {
        when {
            photos.isEmpty() && scanning -> Loading()
            photos.isEmpty() && err != null -> StateMessage(Icons.Rounded.CloudOff, "扫描失败", err!!)
            photos.isEmpty() -> StateMessage(Icons.Rounded.PhotoLibrary, "没有找到照片或视频", "")
            else -> {
                val sorted = remember(photos.size) { photos.sortedByDescending { it.time } }
                PhotoGrid(entries, 4, scanning, onEnd = {}, onOpen = { Nav.push(Route.Viewer(sorted, it)) }, top = 0.dp, bottom = 120.dp)
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter))
    }
}

/** The system photo picker rejects (crashes on) a max above its own limit. */
fun pickLimit(): Int = runCatching {
    if (Build.VERSION.SDK_INT >= 33 || (Build.VERSION.SDK_INT >= 30 && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2))
        android.provider.MediaStore.getPickImagesMaxLimit() else 100
}.getOrDefault(100).coerceIn(2, 100)
