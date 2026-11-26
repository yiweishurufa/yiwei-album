package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.filter
import androidx.paging.map
import com.hark.shiguang.*
import com.hark.shiguang.data.Photo
import com.hark.shiguang.index.PhotoIndexDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 1.1.0「大文件」：WebDAV 索引里按文件大小从大到小列出照片和视频，用来清理空间。
 * 数据直接从 Room 用 Paging 3 按页读（每页 60 条），6 万条的图库也只在内存里放看得见的几页。
 * 隐藏相册里的照片、影视/音乐文件夹里的文件不显示（只列时间线里看得到的）。
 */
@Composable
fun DavBigFilesScreen(r: Route.DavBigFiles) {
    val lib = remember(r.accountId) { Dav.lib(r.accountId) }
    LaunchedEffect(r) { lib.ensure() }
    // path → Photo of what the timeline shows (hidden / isolated files excluded); rebuilt when the timeline changes
    var visible by remember { mutableStateOf<Map<String, Photo>?>(null) }
    LaunchedEffect(lib.photos.size) {   // not metaVersion: it ticks every few seconds while analysing and would reset the pager
        val snap = lib.photos.toList()
        visible = withContext(Dispatchers.Default) { Hidden.visible(MediaIsolation.davVisible(r.accountId, snap)).associateBy { it.cloudPath } }
    }
    val vis = visible
    val scope = rememberCoroutineScope()
    val flow = remember(r.accountId, vis) {
        if (vis == null) null
        else Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)) { PhotoIndexDb.get().dao().pageBySize(r.accountId) }
            .flow.map { pd -> pd.filter { it.path in vis }.map { row -> vis.getValue(row.path) to row.size } }
            .cachedIn(scope)
    }
    // 1.6.6 阶段 3：副标题只放状态（DESIGN §1 少说明）；加载中是与真实行同形的骨架；行内大小用等宽数字
    Page("大文件", "从大到小") {
        if (flow == null) { BigFileSkeleton(); return@Page }
        val items = flow.collectAsLazyPagingItems()
        if (items.itemCount == 0 && items.loadState.refresh is androidx.paging.LoadState.Loading) { BigFileSkeleton(); return@Page }
        if (items.itemCount == 0) {
            StateMessage(KI.archive(), "还没有文件", "扫描完成后，这里会按大小列出照片和视频。")
            return@Page
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(items.itemCount, key = items.itemKey { it.first.cloudPath }) { i ->
                val (p, size) = items[i] ?: return@items
                BigFileRow(p, size, i) {
                    if (Selection.active) Selection.toggle(p)
                    else Nav.push(Route.Viewer(items.itemSnapshotList.items.map { it.first }, i.coerceAtMost(items.itemSnapshotList.items.size - 1)))
                }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BigFileRow(p: Photo, size: Long, index: Int, onClick: () -> Unit) {
    val on = Selection.active && Selection.has(p)
    val bg by androidx.compose.animation.animateColorAsState(if (on) C.AccentSoft else Color.Transparent, Motion.state(), label = "bigSel")
    val folder = com.hark.shiguang.cloud.CloudPath.parent(p.cloudPath)
    Row(Modifier.fillMaxWidth().enterRise(index).background(bg)
        .combinedClickable(onClick = onClick, onLongClick = { Selection.start(p) }, onLongClickLabel = "多选")
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(if (p.isVideo) "视频" else "照片", p.fileName, fmtBytes(size), folder).filter { it.isNotEmpty() }.joinToString("，")
            if (Selection.active) selected = on
        }
        .heightIn(min = Space.row + Space.m).padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            NetImage(p.thumbS, Modifier.fillMaxSize())
            if (p.isVideo) Box(Modifier.align(Alignment.BottomEnd).padding(Space.xs).size(18.dp).clip(CircleShape).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                Icon(KI.playSolid(), null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
            // 多选时与照片网格同款的勾选圆
            if (Selection.active) Box(Modifier.align(Alignment.TopStart).padding(Space.xs).size(18.dp).clip(CircleShape)
                .background(if (on) C.Accent else Color(0x55000000)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                if (on) Icon(KI.check(), null, tint = C.OnAccent, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(p.fileName, color = C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.s))
        Text(fmtBytes(size), color = C.Text, style = MaterialTheme.typography.titleSmall.merge(Type.num))
    }
}

/** 加载中：8 行与 [BigFileRow] 同形的骨架。 */
@Composable
private fun BigFileSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = Space.xs)) {
        repeat(8) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(Radius.s)).background(shimmerBrush()))
                Column(Modifier.weight(1f).padding(start = Space.m)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(Radius.xs)).background(C.Surface2))
                    Box(Modifier.padding(top = Space.s).fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(Radius.xs)).background(C.Surface2))
                }
                Box(Modifier.size(48.dp, 12.dp).clip(RoundedCornerShape(Radius.xs)).background(C.Surface2))
            }
        }
    }
}

