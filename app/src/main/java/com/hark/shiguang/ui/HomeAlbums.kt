package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.hark.shiguang.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

private fun n(v: Int) = NumberFormat.getIntegerInstance().format(v)

// 1.1.0 拆分自 Home.kt（只移动代码，行为不变）：相册页。
// ============================================================ albums

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumsTab(dav: Boolean, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    var renaming by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<AlbumRef, String>?>(null) }
    val lib = if (dav) Dav.lib() else null
    fun load() = scope.launch {
        err = null
        if (dav) { lib?.refreshAlbums(); lib?.refreshFolders() }
        else runCatching { Repo.albums() }.onSuccess { HomeState.albums = it }.onFailure { err = it.message }
    }
    LaunchedEffect(dav, lib) { if (dav) { lib?.ensure() } else if (HomeState.albums == null) load() }
    val grid = rememberMemGrid(if (dav) "dav-albums-${lib?.accountId}" else "fn-albums-${NasAccounts.currentId}")
    Column(Modifier.fillMaxSize()) {
        // 用户口述 3：从「合集 → 相册 全部」进来时顶栏是返回 + 新建，不再显示来源切换和图库 | 合集
        if (onBack != null) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(LI.back(), "返回合集", tint = C.Text) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(LI.plus(), "新建相册", tint = C.Text) }
        } else {
            TopSwitch { IconButton(onClick = { creating = true }) { Icon(LI.plus(), "新建相册", tint = C.Text) } }
            PhotoModeSwitch()
        }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + miniPlayerPad),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "top") {
                Text("相册", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            }
            if (dav) {
                if (lib == null) item(span = { GridItemSpan(maxLineSpan) }) { Text("还没有 WebDAV 账户", color = C.Sub) }
                else {
                    val folders = lib.folders
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fh") { SectionTitle("文件夹", folders?.let { "${it.size} 个" } ?: "") }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fl") {
                        if (folders == null) Box(Modifier.height(90.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(shimmerBrush()))
                        else if (folders.isEmpty()) Text("根目录下没有文件夹", style = MaterialTheme.typography.bodySmall)
                        else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(folders, key = { it.path }) { f ->
                                Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).clickable { Nav.push(Route.CloudBrowse(lib.accountId, f.path, f.name)) }.padding(12.dp)) {
                                    Icon(LI.folder(), null, tint = C.Gold, modifier = Modifier.size(26.dp))
                                    Text(f.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                                }
                            }
                        }
                    }
                    item(key = "dhidden") {
                        AlbumTile("隐藏相册", "需验证指纹查看", null, icon = LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) }
                    }
                    val als = lib.albums
                    item(span = { GridItemSpan(maxLineSpan) }, key = "ah") { SectionTitle("我的相册", als?.let { "${it.size} 个 · 存在 WebDAV 根目录清单里" } ?: "") }
                    if (als == null) items(2) { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
                    else if (als.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("还没有相册。点右上角 ＋ 新建，或在照片多选后「加入相册」。照片不会被移动。", style = MaterialTheme.typography.bodySmall)
                    }
                    else items(als.size, key = { "v" + als[it].id }) { i ->
                        val a = als[i]
                        val cover = (lib.photoAt(a.cover) ?: lib.photosOf(a).firstOrNull())?.thumbM
                        AlbumTile(a.name, "${a.items.size} 项", cover, onLong = { menuFor = AlbumRef(a.id, lib.accountId) to a.name }) { openDavAlbum(lib, a) }
                    }
                }
            } else {
                item(key = "fav") {
                    AlbumTile("收藏", "你标记喜欢的瞬间", null, icon = LI.heart()) {
                        Nav.push(Route.Collection("收藏", "", PhotoSource { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, collect = true) }))
                    }
                }
                item(key = "hidden") {
                    AlbumTile("隐藏相册", "需验证指纹查看", null, icon = LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) }
                }
                item(key = "recent") {
                    AlbumTile("最近添加", "最新进入相册的照片", null, icon = LI.clock()) {
                        Nav.push(Route.Collection("最近添加", "", PhotoSource(grouped = false) { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, mode = "createdAt") }))
                    }
                }
                val albums = HomeState.albums
                if (albums == null && err != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(420.dp)) { ErrorState(err, onRetry = { load() }) } }
                else if (albums == null) items(4) { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
                else {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("我的相册", "${albums.size} 个 · 长按管理") }
                    items(albums.size, key = { "a" + albums[it].id }) { i ->
                        val a = albums[i]
                        AlbumTile(a.name, listOf(if (a.photos > 0) "${n(a.photos)} 张" else "", if (a.videos > 0) "${n(a.videos)} 个视频" else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "空相册" }, a.poster,
                            onLong = { menuFor = AlbumRef(a.id.toString(), null) to a.name }) { openAlbum(a) }
                    }
                }
            }
        }
    }
    if (creating) InputDialog("新建相册", "", "相册名称", "创建", onDismiss = { creating = false }) { name ->
        creating = false
        Ops.launch {
            runCatching { if (dav) { lib?.createAlbum(name) ?: error("没有 WebDAV 账户") } else { NasX.createAlbum(name); HomeState.albums = Repo.albums() } }
                .onSuccess { toast(ctx, "已创建「$name」") }.onFailure { toast(ctx, it.message ?: "创建失败") }
        }
    }
    menuFor?.let { m ->
        AlertDialog(onDismissRequest = { menuFor = null }, containerColor = C.Surface, title = { Text(m.second, color = C.Text) }, text = {
            Column {
                Text("重命名", color = C.Text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { menuFor = null; renaming = m }.padding(vertical = 12.dp))
                Text("删除相册", color = C.Danger, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { menuFor = null; deleting = m }.padding(vertical = 12.dp))
                Text("打开相册后多选照片可「设为封面」「移出相册」。", color = C.Sub, fontSize = 12.sp)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { menuFor = null }) { Text("取消", color = C.Sub) } })
    }
    renaming?.let { m ->
        InputDialog("重命名相册", m.second, "相册名称", onDismiss = { renaming = null }) { name ->
            renaming = null
            Ops.launch { runCatching { AlbumOps.rename(m.first, name); if (!dav) HomeState.albums = Repo.albums() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
    deleting?.let { m ->
        ConfirmDialog("删除相册「${m.second}」？", "只删除相册，照片本身不会被删除。", "删除", onDismiss = { deleting = null }) {
            deleting = null
            Ops.launch { runCatching { AlbumOps.delete(m.first); if (!dav) HomeState.albums = Repo.albums() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(title: String, sub: String, poster: String?, icon: ImageVector? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.pressScale().combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLong, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(C.Card)).background(if (icon != null) C.Accent.copy(alpha = 0.12f) else C.Surface)) {
            if (poster != null) NetImage(poster, Modifier.fillMaxSize())
            else Icon(icon ?: LI.albums(), null, tint = if (icon != null) C.Accent else C.Faint, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, start = 2.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 2.dp, start = 2.dp))
    }
}
