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
    Page("大文件", "按文件大小排列，先清理最占空间的") {
        if (flow == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }; return@Page }
        val items = flow.collectAsLazyPagingItems()
        if (items.itemCount == 0 && items.loadState.refresh !is androidx.paging.LoadState.Loading) {
            StateMessage(Icons.Rounded.Inventory2, "还没有文件", "扫描完成后，这里会按大小列出照片和视频。")
            return@Page
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            items(items.itemCount, key = items.itemKey { it.first.cloudPath }) { i ->
                val (p, size) = items[i] ?: return@items
                BigFileRow(p, size) {
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
private fun BigFileRow(p: Photo, size: Long, onClick: () -> Unit) {
    val on = Selection.active && Selection.has(p)
    Row(Modifier.fillMaxWidth().background(if (on) C.Accent.copy(alpha = 0.10f) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = { Selection.start(p) })
        .padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            NetImage(p.thumbS, Modifier.fillMaxSize())
            if (p.isVideo) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(16.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(p.fileName, color = C.Text, fontSize = TextSize.bodyL, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(com.hark.shiguang.cloud.CloudPath.parent(p.cloudPath), color = C.Sub, fontSize = TextSize.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.s))
        Text(fmtBytes(size), color = C.Text, fontSize = TextSize.body)
    }
}

