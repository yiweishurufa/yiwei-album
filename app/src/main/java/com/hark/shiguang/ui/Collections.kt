package com.hark.shiguang.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.heading
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
            // 1.6.2：页面大标题与照片页一致（displaySmall），「调整排序」是次要色文字按钮
            Row(Modifier.padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text("合集", style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.weight(1f).semantics { heading() })
                HeaderTextButton("调整排序", C.Sub) { sorting = true }
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
    // 1.6.2：标题 titleMedium（读屏「标题」）；右侧「全部 / 地图」文字按钮 + 折叠箭头（旋转跟随 Motion.move）
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, Motion.move(), label = "fold")
    Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.l, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = C.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) HeaderTextButton(trailing, C.Sub, onTrailing)
        IconButton(onClick = onFold) {
            Icon(KI.down(), if (open) "收起$title" else "展开$title", tint = C.Sub, modifier = Modifier.size(20.dp).rotate(turn))
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    // 空分区：一句话，次要色，放在与封面同圆角的浅卡里
    Text(text, style = MaterialTheme.typography.bodyMedium, color = C.Sub, modifier = Modifier.padding(horizontal = Space.l).fillMaxWidth()
        .clip(RoundedCornerShape(Radius.l)).background(C.Surface).padding(horizontal = Space.l, vertical = Space.l))
}

@Composable
private fun FnAlbumsRow() {
    val albums = HomeState.albums
    LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(CoverSize.gap)) {
        item(key = "fav") { CoverTile("收藏", "", null, KI.heart(), index = 0) { Nav.push(Route.Collection("收藏", "", PhotoSource { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, collect = true) })) } }
        item(key = "recent") { CoverTile("最近添加", "", null, KI.clock(), index = 1) {
            Nav.push(Route.Collection("最近添加", "", PhotoSource(grouped = false) { o, l -> Repo.photos(Repo.FAR_START, Repo.FAR_END, o, l, mode = "createdAt") }))
        } }
        item(key = "hidden") { CoverTile("隐藏相册", "", null, KI.lock(), index = 2) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) } }
        if (albums == null) items(3) { CoverTileSkeleton() }
        else itemsIndexed(albums, key = { _, a -> "a" + a.id }) { i, a -> CoverTile(a.name, if (a.photos + a.videos > 0) "${a.photos + a.videos} 项" else "空相册", a.poster, index = i + 3) { openAlbum(a) } }
    }
}

@Composable
private fun DavAlbumsRow(lib: DavLib?) {
    if (lib == null) { EmptyCard("还没有 WebDAV 账户。"); return }
    val als = lib.albums
    LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(CoverSize.gap)) {
        item(key = "hidden") { CoverTile("隐藏相册", "", null, KI.lock(), index = 0) { Nav.push(Route.X(ExtScreen.HiddenAlbum)) } }
        if (als == null) items(2) { CoverTileSkeleton() }
        else itemsIndexed(als, key = { _, a -> "v" + a.id }) { i, a ->
            val cover = (lib.photoAt(a.cover) ?: lib.photosOf(a).firstOrNull())?.thumbM
            CoverTile(a.name, "${a.items.size} 项", cover, index = i + 1) { openDavAlbum(lib, a) }
        }
    }
}

@Composable
private fun PeopleRow() {
    val hidden = FnPersonHide.version.let { FnPersonHide.ids() }
    val ps = HomeState.persons?.filter { it.id !in hidden }
    if (ps != null && ps.isEmpty()) { EmptyCard("飞牛相册识别出人脸后，人物会出现在这里。"); return }
    LazyRow(contentPadding = PaddingValues(horizontal = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (ps == null) items(5) { PersonChipSkeleton() }
        else itemsIndexed(ps, key = { _, p -> p.id }) { i, p ->
            PersonChip(p.name, FnClient.abs("/p/api/v1/stream/face/${p.faceId}"), index = i) { openPerson(p) }
        }
    }
}

@Composable
private fun PlacesRow() {
    val places = HomeState.places
    if (places != null && places.isEmpty()) { EmptyCard("带拍摄地点的照片会按城市归到这里。"); return }
    // C3：地点在合集页和发现页是同一款竖向封面卡（标题压在照片上）
    LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(CoverSize.gap)) {
        if (places == null) items(3) { CoverCardSkeleton() }
        else itemsIndexed(places) { i, pl ->
            CoverCard(pl.city.ifEmpty { pl.country }, "${pl.count} 张", pl.poster, index = i) {
                Nav.push(Route.Collection(pl.city.ifEmpty { pl.country }, pl.country, PhotoSource(pageSize = 200, grouped = true) { o, l -> Repo.placePhotos(pl, o, l) }))
            }
        }
    }
}

@Composable
private fun CategoryRow(cats: List<SmartCategory>?, empty: String) {
    if (cats != null && cats.isEmpty()) { EmptyCard(empty); return }
    LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(CoverSize.gap)) {
        if (cats == null) items(3) { CoverTileSkeleton() }
        else itemsIndexed(cats, key = { _, c -> c.name }) { i, c ->
            CoverTile(c.name, "${c.count} 张", c.cover, index = i) {
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
        Btn("视频", KI.videos()) { HomeState.setFilter(2); HomeState.tab = 0 },
        Btn("实况照片", KI.live()) { HomeState.setFilter(3); HomeState.tab = 0 },
    ) else media.orEmpty().filter { it.count > 0 && it.key != "photo" }.map { m ->
        Btn(mediaTypeLabel(m.key) + "  ${m.count}", mediaTypeIcon(m.key)) {
            Nav.push(Route.Collection(mediaTypeLabel(m.key), "${m.count} 项", PhotoSource(grouped = true) { o, _ -> if (o > 0) emptyList<Photo>() to false else NasX.mediaCategoryPhotos(m.key) to false }))
        }
    }
    if (!dav && media == null) { Box(Modifier.padding(horizontal = Space.l).fillMaxWidth().height(Space.row).clip(RoundedCornerShape(Radius.l)).background(shimmerBrush())); return }
    if (btns.isEmpty()) { EmptyCard("还没有视频、实况、截图这类特殊媒体。"); return }
    Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        btns.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                row.forEach { b ->
                    // 1.6.2：入口块 = 质感卡片 + 次要色线性图标（不再用强调色），按下 0.97
                    Row(Modifier.weight(1f).pressScale().heightIn(min = Space.row).skeuoCard(RoundedCornerShape(Radius.l)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = b.open).padding(horizontal = Space.l),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(b.icon, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                        Text(b.label, color = C.Text, style = MaterialTheme.typography.bodyMedium.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Space.m))
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
    "video" -> KI.videos(); "live_photo" -> KI.live(); "gif" -> Icons.Rounded.Gif
    "raw" -> Icons.Rounded.RawOn; "360" -> Icons.Rounded.Panorama; "panorama" -> Icons.Rounded.Panorama; "screenshot" -> Icons.Rounded.Screenshot
    else -> Icons.Rounded.Image
}

@Composable
private fun SortDialog(order: List<String>, onMove: (String, Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("调整分区顺序", color = C.Text) }, text = {
        Column {
            order.forEachIndexed { i, id ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(CollectionLayout.TITLES[id].orEmpty(), color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onMove(id, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "上移", tint = if (i > 0) C.Text else C.Faint) }
                    IconButton(onClick = { onMove(id, 1) }, enabled = i < order.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "下移", tint = if (i < order.size - 1) C.Text else C.Faint) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成", color = C.Accent) } },
        dismissButton = { TextButton(onClick = onReset) { Text("恢复默认", color = C.Sub) } })
}
