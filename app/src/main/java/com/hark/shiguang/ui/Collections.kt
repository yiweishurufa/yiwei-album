package com.hark.shiguang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.*

/**
 * 用户口述 3（2026-10-30，参照飞牛官方相册「合集」页）：照片 tab 的「图库 | 合集」里的合集页。
 * 一页纵向分区：回忆（那年今日 / 旅行）、相册、人物、地点、事物场景、资料记录、标签、媒体类型。
 * 每区标题右侧可折叠；「调整排序」可上下移动分区，顺序和折叠都存在本机（CollectionLayout）。
 * 第一阶段：WebDAV 来源只有 回忆 / 相册 / 标签 / 媒体类型（人物、地点、分类仍在「发现」页）。
 */
@Composable
internal fun CollectionsTab(dav: Boolean) {
    var allAlbums by rememberSaveable { mutableStateOf(false) }
    if (allAlbums) {
        BackHandler { allAlbums = false }
        AlbumsTab(dav, onBack = { allAlbums = false })
        return
    }
    var order by remember { mutableStateOf(CollectionLayout.loadOrder()) }
    var folded by remember { mutableStateOf(CollectionLayout.loadFolded()) }
    var sorting by remember { mutableStateOf(false) }
    fun toggle(id: String) { folded = if (id in folded) folded - id else folded + id; CollectionLayout.saveFolded(folded) }

    val lib = if (dav) Dav.lib() else null
    // ---- data (飞牛 reuses the home caches the 发现 / 相册 pages fill)
    var scene by remember { mutableStateOf<List<SmartCategory>?>(null) }
    var info by remember { mutableStateOf<List<SmartCategory>?>(null) }
    var media by remember { mutableStateOf<List<MediaCategory>?>(null) }
    var fnMemories by remember { mutableStateOf<List<MemoryGroup>>(emptyList()) }
    if (!dav) {
        LaunchedEffect(Unit) { if (HomeState.albums == null) runCatching { Repo.albums() }.onSuccess { HomeState.albums = it } }
        LaunchedEffect(Unit) { if (HomeState.persons == null) runCatching { Repo.persons() }.onSuccess { HomeState.persons = it }.onFailure { HomeState.persons = emptyList() } }
        LaunchedEffect(Unit) { if (HomeState.places == null) runCatching { Repo.places() }.onSuccess { HomeState.places = it }.onFailure { HomeState.places = emptyList() } }
        LaunchedEffect(Unit) { scene = runCatching { NasX.smartCategories("scene") }.getOrDefault(emptyList()) }
        LaunchedEffect(Unit) { info = runCatching { NasX.smartCategories("information") }.getOrDefault(emptyList()) }
        LaunchedEffect(Unit) { media = runCatching { NasX.mediaCategories() }.getOrDefault(emptyList()) }
        LaunchedEffect(HomeState.timeline.days) { fnMemories = runCatching { OnThisDay.loadFn(HomeState.timeline, NasAccounts.currentId) }.getOrDefault(emptyList()) }
    } else LaunchedEffect(lib) { lib?.ensure() }
    val davMemories by remember(lib) { derivedStateOf { lib?.metaVersion; lib?.let { l -> OnThisDay.from(l.photos.filter { !it.isVideo }) }.orEmpty() } }
    val memories = if (dav) davMemories else fnMemories

    val list = rememberMemList(if (dav) "dav-collections-${lib?.accountId}" else "fn-collections-${NasAccounts.currentId}")
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 120.dp + miniPlayerPad)) {
        item { TopSwitch() }
        item { PhotoModeSwitch() }
        item {
            Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("合集", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { sorting = true }) { Text("调整排序", color = C.Sub, fontSize = 14.sp) }
            }
        }
        order.forEach { id ->
            val visible = when (id) {
                "people", "places", "scene", "info" -> !dav
                "memories" -> memories.isNotEmpty()
                else -> true
            }
            if (!visible) return@forEach
            val open = id !in folded
            item(key = "h-$id") {
                val trailing = when (id) {
                    "albums" -> "全部"
                    "places" -> "地图"
                    "scene", "info" -> "全部"
                    else -> null
                }
                SectionHeader(CollectionLayout.TITLES[id].orEmpty(), open, trailing, onFold = { toggle(id) }) {
                    when (id) {
                        "albums" -> allAlbums = true
                        "places" -> Nav.push(Route.X(ExtScreen.Map(false)))
                        else -> Nav.push(Route.Smart)
                    }
                }
            }
            if (open) item(key = "b-$id") {
                when (id) {
                    "memories" -> MemoriesRow(memories)
                    "albums" -> if (dav) DavAlbumsRow(lib) else FnAlbumsRow()
                    "people" -> PeopleRow()
                    "places" -> PlacesRow()
                    "scene" -> CategoryRow(scene, "飞牛相册开启 AI 识别后，照片会按猫、海边、美食这类事物和场景自动归类。")
                    "info" -> CategoryRow(info, "证件、证书、票据、截图里的文字信息识别后会归到这里。")
                    "tags" -> EmptyCard("为照片添加标签后，相关内容会按标签整理并显示在这里。")
                    "media" -> MediaTypeGrid(dav, media)
                }
            }
        }
    }
    if (sorting) SortDialog(order, onMove = { id, d -> order = CollectionLayout.move(order, id, d); CollectionLayout.saveOrder(order) },
        onReset = { order = CollectionLayout.DEFAULT; CollectionLayout.saveOrder(order) }) { sorting = false }
}

@Composable
private fun SectionHeader(title: String, open: Boolean, trailing: String?, onFold: () -> Unit, onTrailing: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).then(if (trailing != null) Modifier.clickable(onClick = onTrailing) else Modifier),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (trailing != null) {
                Text("  $trailing", color = C.Sub, fontSize = 13.sp)
                Icon(LI.chevron(), null, tint = C.Sub, modifier = Modifier.size(16.dp))
            }
        }
        IconButton(onClick = onFold) {
            Icon(Icons.Rounded.ExpandMore, if (open) "收起$title" else "展开$title", tint = C.Sub, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        .clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(horizontal = 16.dp, vertical = 18.dp))
}

/** 横滑卡片（相册 / 分类共用）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(title: String, sub: String, cover: String?, icon: ImageVector? = null, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(128.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface2), contentAlignment = Alignment.Center) {
            if (cover != null) NetImage(cover, Modifier.fillMaxSize())
            else if (icon != null) Icon(icon, null, tint = C.Accent, modifier = Modifier.size(32.dp))
        }
        Text(title, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (sub.isNotEmpty()) Text(sub, color = C.Sub, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun FnAlbumsRow() {
    val albums = HomeState.albums
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "fav") { CoverTile("收藏", "", null, LI.heart()) { Nav.push(Route.Collection("收藏", "", PhotoSource { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, collect = true) })) } }
        item(key = "recent") { CoverTile("最近添加", "", null, LI.clock()) {
            Nav.push(Route.Collection("最近添加", "", PhotoSource(grouped = false) { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, mode = "createdAt") }))
        } }
        item(key = "hidden") { CoverTile("隐藏相册", "", null, LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) } }
        if (albums == null) items(3) { Box(Modifier.size(128.dp).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
        else items(albums, key = { "a" + it.id }) { a -> CoverTile(a.name, if (a.photos + a.videos > 0) "${a.photos + a.videos} 项" else "空相册", a.poster) { openAlbum(a) } }
    }
}

@Composable
private fun DavAlbumsRow(lib: DavLib?) {
    if (lib == null) { EmptyCard("还没有 WebDAV 账户。"); return }
    val als = lib.albums
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "hidden") { CoverTile("隐藏相册", "", null, LI.lock()) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) } }
        if (als == null) items(2) { Box(Modifier.size(128.dp).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
        else items(als, key = { "v" + it.id }) { a ->
            val cover = (lib.photoAt(a.cover) ?: lib.photosOf(a).firstOrNull())?.thumbM
            CoverTile(a.name, "${a.items.size} 项", cover) { openDavAlbum(lib, a) }
        }
    }
}

@Composable
private fun PeopleRow() {
    val hidden = FnPersonHide.version.let { FnPersonHide.ids() }
    val ps = HomeState.persons?.filter { it.id !in hidden }
    if (ps != null && ps.isEmpty()) { EmptyCard("飞牛相册识别出人脸后，人物会出现在这里。"); return }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ps == null) items(5) { Box(Modifier.size(68.dp).clip(CircleShape).background(shimmerBrush())) }
        else items(ps, key = { it.id }) { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { openPerson(p) }) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(C.Surface2)) { NetImage(FnClient.abs("/p/api/v1/stream/face/${p.faceId}"), Modifier.fillMaxSize()) }
                Text(p.name.ifEmpty { "未命名" }, fontSize = 12.sp, color = C.Sub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun PlacesRow() {
    val places = HomeState.places
    if (places != null && places.isEmpty()) { EmptyCard("带拍摄地点的照片会按城市归到这里。"); return }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (places == null) items(3) { Box(Modifier.size(128.dp).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
        else items(places) { pl ->
            CoverTile(pl.city.ifEmpty { pl.country }, "${pl.count} 张", pl.poster) {
                Nav.push(Route.Collection(pl.city.ifEmpty { pl.country }, pl.country, PhotoSource(pageSize = 200, grouped = true) { o, l -> Repo.placePhotos(pl, o, l) }))
            }
        }
    }
}

@Composable
private fun CategoryRow(cats: List<SmartCategory>?, empty: String) {
    if (cats != null && cats.isEmpty()) { EmptyCard(empty); return }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (cats == null) items(3) { Box(Modifier.size(128.dp).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())) }
        else items(cats, key = { it.name }) { c ->
            CoverTile(c.name, "${c.count} 张", c.cover) {
                Nav.push(Route.Collection(c.name, "${c.count} 项", PhotoSource(pageSize = 100) { o, l -> val r = NasX.categoryPhotos(c.name, o, l); r to (r.size >= l) }))
            }
        }
    }
}

/** 媒体类型：两列圆角按钮（参照官方合集页）。飞牛按服务器的媒体分类；WebDAV 跳回图库并套上对应筛选。 */
@Composable
private fun MediaTypeGrid(dav: Boolean, media: List<MediaCategory>?) {
    data class Btn(val label: String, val icon: ImageVector, val open: () -> Unit)
    val btns: List<Btn> = if (dav) listOf(
        Btn("视频", Icons.Rounded.PlayCircleOutline) { HomeState.setFilter(2); HomeState.tab = 0 },
        Btn("实况照片", Icons.Rounded.MotionPhotosOn) { HomeState.setFilter(3); HomeState.tab = 0 },
    ) else media.orEmpty().filter { it.count > 0 && it.key != "photo" }.map { m ->
        Btn(mediaTypeLabel(m.key) + "  ${m.count}", mediaTypeIcon(m.key)) {
            Nav.push(Route.Collection(mediaTypeLabel(m.key), "${m.count} 项", PhotoSource(grouped = true) { o, _ -> if (o > 0) emptyList<Photo>() to false else NasX.mediaCategoryPhotos(m.key) to false }))
        }
    }
    if (!dav && media == null) { Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(C.Card)).background(shimmerBrush())); return }
    if (btns.isEmpty()) { EmptyCard("还没有视频、实况、截图这类特殊媒体。"); return }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        btns.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { b ->
                    Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface).clickable(onClick = b.open).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(b.icon, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                        Text(b.label, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun mediaTypeLabel(k: String) = when (k) {
    "photo" -> "照片"; "video" -> "视频"; "live_photo" -> "实况 / 动态照片"; "gif" -> "动图"; "raw" -> "RAW"
    "360" -> "360° 照片视频"; "panorama" -> "全景照片"; "screenshot" -> "屏幕截图"; else -> k
}

private fun mediaTypeIcon(k: String): ImageVector = when (k) {
    "video" -> Icons.Rounded.PlayCircleOutline; "live_photo" -> Icons.Rounded.MotionPhotosOn; "gif" -> Icons.Rounded.Gif
    "raw" -> Icons.Rounded.RawOn; "360" -> Icons.Rounded.Panorama; "panorama" -> Icons.Rounded.Panorama; "screenshot" -> Icons.Rounded.Screenshot
    else -> Icons.Rounded.Image
}

@Composable
private fun SortDialog(order: List<String>, onMove: (String, Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("调整分区顺序", color = C.Text) }, text = {
        Column {
            order.forEachIndexed { i, id ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(CollectionLayout.TITLES[id].orEmpty(), color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onMove(id, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "上移", tint = if (i > 0) C.Text else C.Faint) }
                    IconButton(onClick = { onMove(id, 1) }, enabled = i < order.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "下移", tint = if (i < order.size - 1) C.Text else C.Faint) }
                }
            }
            Text("顺序和折叠状态只保存在这台手机上。", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成", color = C.Accent) } },
        dismissButton = { TextButton(onClick = onReset) { Text("恢复默认", color = C.Sub) } })
}
