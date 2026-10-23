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

// 1.1.0 拆分自 Home.kt（只移动代码，行为不变）：发现页（飞牛 / WebDAV）及其卡片组件。
// ============================================================ discover (NAS)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoverTab() {
    val ctx = LocalContext.current
    var menuPerson by remember { mutableStateOf<Person?>(null) }
    var menuPlace by remember { mutableStateOf<Place?>(null) }
    menuPerson?.let { p ->
        ActionListDialog(p.name.ifEmpty { "未命名人物" }, "${p.count} 张照片", onDismiss = { menuPerson = null }, actions = listOf(
            "命名" to { menuPerson = null; HomeState.renamePerson = p },
            "隐藏（路人）" to { menuPerson = null; FnPersonHide.set(p.id, true); toast(ctx, "已在本机隐藏") },
            // 第 4 批：飞牛暂未提供人物合并/移出接口（FnPersonHide），不硬编假调用；点开只说明原因，维持现状。
            "合并 / 移出" to { menuPerson = null; toast(ctx, "飞牛暂未提供人物合并/移出接口，仅支持本机隐藏") },
            "创建相册" to { menuPerson = null; makeNasAlbum(ctx, p.name.ifEmpty { "人物" }) { o, l -> Repo.personPhotos(p.id, o, l) } },
        ))
    }
    menuPlace?.let { pl ->
        ActionListDialog(pl.city.ifEmpty { pl.country }, "${pl.count} 张照片", onDismiss = { menuPlace = null }, actions = listOf(
            "创建相册" to { menuPlace = null; makeNasAlbum(ctx, pl.city.ifEmpty { pl.country }) { o, l -> Repo.placePhotos(pl, o, l) } },
        ))
    }
    LaunchedEffect(Unit) { if (HomeState.places == null) runCatching { Repo.places() }.onSuccess { HomeState.places = it }.onFailure { HomeState.places = emptyList() } }
    LaunchedEffect(Unit) { if (HomeState.persons == null) runCatching { Repo.persons() }.onSuccess { HomeState.persons = it }.onFailure { HomeState.persons = emptyList() } }
    // 1.0.9: optional AI labelling of NAS photos runs alongside, same ScanPolicy rules (waits instead of quitting)
    // 1.0.2: 重复/相似 detection belongs to 整理 — ask 飞牛 to re-check at most once every 12 h (server-side, incremental)
    LaunchedEffect(Unit) {
        val k = "fn.repeatCheck." + NasAccounts.currentId
        if (!ScanPolicy.paused && System.currentTimeMillis() - (Store.getStr(k).toLongOrNull() ?: 0L) > 12 * 3600_000L)
            runCatching { NasX.repeatCheck(true) }.onSuccess { Store.putStr(k, System.currentTimeMillis().toString()) }
    }
    LaunchedEffect(HomeState.timeline.photos.size, AiConfig.nasToo) { if (AiConfig.nasToo && AiRunner.on && !ScanPolicy.paused) AiRunner.startNas(ctx, HomeState.timeline.photos.toList()) }
    val list = rememberMemList("fn-discover")
    val places = HomeState.places
    var fnMemories by remember { mutableStateOf<List<MemoryGroup>>(emptyList()) }
    LaunchedEffect(HomeState.timeline.days) { fnMemories = runCatching { OnThisDay.loadFn(HomeState.timeline, NasAccounts.currentId) }.getOrDefault(emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 120.dp + miniPlayerPad)) {
        item { TopSwitch() }
        item { BigTitle("发现", "") }
        item { SearchBar() }
        val hiddenIds = FnPersonHide.version.let { FnPersonHide.ids() }
        val ps = HomeState.persons.orEmpty().filter { it.id !in hiddenIds }
        if (ps.isNotEmpty() || hiddenIds.isNotEmpty()) {
            item { RowHeader("人物", "${ps.size} 位", extra = if (hiddenIds.isNotEmpty()) ("已隐藏 ${hiddenIds.size}" to { Nav.push(Route.X(ExtScreen.FnHiddenPersons)) }) else null) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(ps, key = { it.id }) { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).combinedClickable(onLongClick = { menuPerson = p }) { openPerson(p) }) {
                            Box(Modifier.size(68.dp).clip(CircleShape).background(C.Surface2)) { NetImage(FnClient.abs("/p/api/v1/stream/face/${p.faceId}"), Modifier.fillMaxSize()) }
                            Text(p.name.ifEmpty { "未命名" }, fontSize = 12.sp, color = C.Sub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        item { RowHeader("地点", places?.let { "${it.size} 处" } ?: "", "地图") { Nav.push(Route.X(ExtScreen.Map(false))) } }
        item {
            when {
                places == null -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(3) { Box(Modifier.size(150.dp, 180.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush())) }
                }
                places.isEmpty() -> Text("照片里还没有地点信息。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(places) { pl ->
                        Box(Modifier.size(150.dp, 180.dp).pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).combinedClickable(onLongClick = { menuPlace = pl }) {
                            Nav.push(Route.Collection(pl.city.ifEmpty { pl.country }, pl.country, PhotoSource(pageSize = 200, grouped = true) { o, l -> Repo.placePhotos(pl, o, l) }))
                        }) {
                            NetImage(pl.poster, Modifier.fillMaxSize())
                            Box(Modifier.matchParentSize().background(C.Scrim))
                            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                Text(pl.city.ifEmpty { pl.country }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${pl.country} · ${n(pl.count)}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        // 1.1.0 发现 → 回忆
        item { RowHeader("回忆") }
        item { MemoriesSection(fnMemories, recent = { HomeState.timeline.photos.filter { !it.isVideo } }) }
        item { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).padding(top = 10.dp)) { SectionTitle("整理 · 清理空间") } }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.sparkle(), "智能分类", "风景、美食、文档…", Modifier.weight(1f)) { Nav.push(Route.Smart) }
                    ToolCard(LI.copy(), "重复照片", "找出并清理", Modifier.weight(1f)) { Nav.push(Route.Duplicates) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.map(), "地图", "按拍摄地点", Modifier.weight(1f)) { Nav.push(Route.X(ExtScreen.Map(false))) }
                    ToolCard(LI.folder(), "文件夹", "按 NAS 目录", Modifier.weight(1f)) { Nav.push(Route.Folders("", "文件夹")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard(LI.trash(), "回收站", "可以恢复", Modifier.weight(1f)) { Nav.push(Route.Recycle) }
                    ToolCard(LI.upload(), "上传", "传输队列", Modifier.weight(1f)) { Nav.push(Route.Transfers) }
                }
            }
        }
    }
}

@Composable
private fun SearchBar() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface)
            .clickable { Nav.push(Route.Search) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(LI.search(), null, tint = C.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("试试「海边日落」「去年的猫」", color = C.Faint, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!AiConfig.configured && HomeState.source == "dav") Text("需配置 AI", color = C.Gold, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Gold.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, sub: String, modifier: Modifier, covers: List<String?> = emptyList(), onClick: () -> Unit) {
    Column(modifier.pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        if (covers.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            covers.take(3).forEach { c -> Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(C.Surface2)) { if (c != null) NetImage(c, Modifier.fillMaxSize()) } }
            repeat(3 - covers.take(3).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// ============================================================ discover (WebDAV)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun DavDiscoverTab() {
    val ctx = LocalContext.current
    val lib = Dav.lib()
    if (lib == null) { Column { TopSwitch(); Box(Modifier.weight(1f)) { StateMessage(LI.drive(), "还没有 WebDAV", "", "添加") { Nav.push(Route.DavAccounts) } } }; return }
    val fl = remember(lib) { Faces.lib(lib.accountId) }
    // Only new photos are processed: both runners return at once when everything is already done.
    LaunchedEffect(lib) {
        lib.ensure(); fl.cluster(lib)
        // 1.0.9: AI labelling starts together with analysis and faces; it waits for the ScanPolicy conditions by itself
        if (!ScanPolicy.paused) ScanStarter.startAll(ctx, lib, manual = false)
    }
    val v = lib.metaVersion
    val places = remember(v, lib.photos.size) { lib.places() }
    val cats = remember(v, lib.photos.size) { lib.aiCategories() }
    val analyzedN = remember(v, lib.photos.size) { lib.meta.values.count { it.done } }
    val aiN = remember(v, lib.photos.size) { lib.aiDone() }
    val imgs = lib.imagesCount()
    val faceTodo = remember(fl.done, fl.running, lib.photos.size) { Faces.pending(lib, fl) }   // 不含 AI 已判定无人脸的
    var menuPerson by remember { mutableStateOf<com.hark.shiguang.FacePerson?>(null) }
    var mergeFrom by remember { mutableStateOf<com.hark.shiguang.FacePerson?>(null) }
    var menuPlace by remember { mutableStateOf<Pair<String, List<Photo>>?>(null) }
    val list = rememberMemList("dav-discover-" + lib.accountId)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 120.dp + miniPlayerPad)) {
        item { TopSwitch() }
        item { BigTitle("发现", "${n(imgs)} 张照片 · ${fl.persons.size} 位人物 · ${places.size} 个地点") }
        item { SearchBar() }

        // ---------------- 整理进度：一张卡说清楚三件事
        item {
            ScanStatusCard(
                rows = (if (lib.scanning) listOf(Triple("读取文件", lib.scanListed to maxOf(lib.scanKnown, lib.scanListed), true)) else emptyList()) + listOf(
                    Triple("本地分析", (if (lib.analyzing) lib.analyzed else analyzedN) to imgs, lib.analyzing),
                    Triple("人脸识别", (imgs - faceTodo).coerceAtLeast(0) to imgs, fl.running),
                ) + if (AiRunner.on) listOf(Triple(AiRunner.rowLabel, aiN to imgs, AiRunner.running)) else emptyList(),
                running = lib.scanning || lib.analyzing || fl.running || AiRunner.running,
                note = run {
                    val base = fl.status.ifEmpty { if (lib.analyzing || fl.running || AiRunner.running) "离开页面也会继续" else if (ScanPolicy.paused) "已暂停，点「立即继续」恢复" else ScanPolicy.waitingText() }
                    val ai = if (AiRunner.on && AiRunner.cardNote.isNotEmpty() && (aiN < imgs || AiPool.switched.isNotEmpty())) "AI 分类：" + AiRunner.cardNote else ""
                    if (ai.isEmpty()) base else if (lib.analyzing || fl.running) "$base\n$ai" else ai
                },
                onToggle = {
                    if (lib.analyzing || fl.running || AiRunner.running) ScanPolicy.pauseAll()
                    else ScanPolicy.resumeAll(ctx, startAiDav = AiRunner.on)
                },
                // S19: the 整理 card's settings go to the tab-4 Settings (one Settings, not two):
                // pushing Route.Settings opened a second, stacked copy.
                onSettings = { HomeState.tab = 4 },
            )
        }

        // ---------------- 人物
        item {
            RowHeader("人物", if (fl.persons.isNotEmpty()) "${fl.persons.size} 位" else "", "识别设置", onAction = { HomeState.showFaceCfg = true },
                extra = if (fl.hiddenPersons.isNotEmpty()) ("已隐藏 ${fl.hiddenPersons.size}" to { Nav.push(Route.X(ExtScreen.HiddenPersons(lib.accountId))) }) else null)
        }
        item {
            if (fl.persons.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(fl.persons, key = { it.key }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).combinedClickable(onLongClick = { menuPerson = p }) {
                        openDavPerson(lib.accountId, p)
                    }) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(C.Surface2)) { NetImage(fl.cropFile(p.cover).absolutePath, Modifier.fillMaxSize()) }
                        Text(p.name.ifEmpty { "未命名" }, fontSize = 13.sp, color = if (p.name.isEmpty()) C.Sub else C.Text, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                        Text("${p.photos.size} 张", fontSize = 11.sp, color = C.Faint)
                    }
                }
            }
            FaceStatusLine(lib, fl)   // 用户 2026-10-23 反馈 #7：人脸识别状态 / 原因 / 重试
            Text("人脸只在本机识别，不会上传。长按人物可命名或建相册。", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
        }

        // ---------------- 地点
        if (places.isNotEmpty()) {
            item { RowHeader("地点", "${places.size} 处", "地图") { Nav.push(Route.X(ExtScreen.Map(true))) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(places, key = { it.first }) { pl ->
                        CoverCard(pl.first, "${n(pl.second.size)} 张", pl.second.firstOrNull()?.thumbM, onLong = { menuPlace = pl }) { openGroup(pl.first, "地点", pl.second) }
                    }
                }
            }
        }

        // ---------------- 回忆（1.1.0：那年今天、回忆短片）
        item { RowHeader("回忆") }
        item {
            val memories = remember(v, lib.photos.size) { OnThisDay.from(Hidden.visible(lib.photos.filter { !it.isVideo })) }
            MemoriesSection(memories, recent = { lib.photos.filter { !it.isVideo } })
        }
        // 1.1.0 回忆 → 旅行（自动旅行相册，设置里可关）
        item { TravelAlbumsRow(lib, v) }

        // ---------------- AI 分类
        item { RowHeader("AI 分类", if (cats.isNotEmpty()) "${cats.size} 类" else "", if (AiConfig.configured) "AI 设置" else null) { Nav.push(Route.AiSettings) } }
        item {
            if (cats.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cats, key = { it.first }) { (c, ps) -> CoverCard(c, "${n(ps.size)} 张", ps.firstOrNull()?.thumbM, small = true) { openGroup(c, "AI 分类", ps) } }
            }
            else InfoRow(LI.sparkle(), if (AiConfig.configured) "等待 AI 整理" else "接入大模型后自动分类", if (AiConfig.configured) (AiRunner.note.ifEmpty { AiRunner.status }.ifEmpty { "按设置的网络和充电条件开始" }) else "风景、美食、人像、文档…",
                null, if (AiRunner.on) "立即整理" else "去配置") { if (!AiRunner.on) Nav.push(Route.AiSettings) else AiRunner.startDav(ctx, lib, manual = true) }
        }

        // ---------------- 清理
        item { RowHeader("清理空间") }
        item {
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface)) {
                var dupN by remember { mutableIntStateOf(0) }
                var simN by remember { mutableIntStateOf(0) }
                LaunchedEffect(v, lib.photos.size) {
                    kotlinx.coroutines.delay(1500) // debounce: metaVersion ticks every few seconds while analysing
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { lib.duplicates().size to lib.similar().size }.getOrNull() }
                    if (r != null) { dupN = r.first; simN = r.second }
                }
                val pend = if (analyzedN < imgs) " · 已分析 ${n(analyzedN)}/${n(imgs)}" else ""
                ToolRow(LI.copy(), "重复照片", (if (dupN > 0) "$dupN 组" else "完全相同的文件，保留一张") + pend) { Nav.push(Route.DavGroups("重复照片", lib.accountId, "dup")) }
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(start = 58.dp))
                ToolRow(LI.layers(), "相似照片", (if (simN > 0) "$simN 组" else "连拍、相近的几张，挑最好的留下") + pend) { Nav.push(Route.DavGroups("相似照片", lib.accountId, "sim")) }
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(start = 58.dp))
                ToolRow(LI.photos(), "截图", "手机截屏，看完可以批量删掉") {
                    openGroup("截图", "清理空间", Hidden.visible(lib.photos.filter { !it.isVideo && AdvFilter.isScreenshot(it) }))
                }
                HorizontalDivider(color = C.Line, modifier = Modifier.padding(start = 58.dp))
                ToolRow(LI.drive(), "大文件", "按大小排列，先清理最占空间的") { Nav.push(Route.DavBigFiles(lib.accountId)) }
            }
        }
    }
    menuPerson?.let { p ->
        ActionListDialog(p.name.ifEmpty { "未命名人物" }, "${p.photos.size} 张照片", onDismiss = { menuPerson = null }, actions = listOf(
            "命名" to { menuPerson = null; HomeState.renameFace = p },
            "合并到其他人物…" to { menuPerson = null; mergeFrom = p },
            "隐藏（路人）" to { menuPerson = null; FacePersonActions.hide(fl, lib, ctx, p) },
            "创建相册" to { menuPerson = null; makeDavAlbum(ctx, lib, p.name.ifEmpty { "人物" }, p.photos) },
        ))
    }
    mergeFrom?.let { p ->
        PersonPicker(fl, exclude = p.key, title = "把「${p.name.ifEmpty { "该人物" }}」合并到…", onDismiss = { mergeFrom = null }) { t ->
            mergeFrom = null
            FacePersonActions.merge(fl, lib, from = p, into = t); toast(ctx, "已合并")
        }
    }
    menuPlace?.let { pl ->
        ActionListDialog(pl.first, "${pl.second.size} 张照片", onDismiss = { menuPlace = null }, actions = listOf(
            "创建相册" to { menuPlace = null; makeDavAlbum(ctx, lib, pl.first, pl.second) },
        ))
    }
}

private fun makeDavAlbum(ctx: android.content.Context, lib: DavLib, name: String, photos: List<Photo>) {
    val existing = lib.albums.orEmpty().firstOrNull { it.name == name }
    Ops.launch {
        runCatching {
            if (existing != null) lib.addTo(existing.id, photos.map { it.cloudPath }) else lib.createAlbum(name, photos.map { it.cloudPath })
        }.onSuccess { toast(ctx, if (existing != null) "已把 ${photos.size} 张加入「$name」" else "已创建相册「$name」，${photos.size} 张") }
            .onFailure { toast(ctx, it.message ?: "创建失败") }
    }
}

/** 飞牛: page through a person / place and put every photo into a new NAS album. */
private fun makeNasAlbum(ctx: android.content.Context, name: String, load: suspend (Int, Int) -> Pair<List<Photo>, Boolean>) {
    toast(ctx, "正在创建相册「$name」…")
    Ops.launch {
        runCatching {
            val ids = ArrayList<Int>(); var off = 0; var guard = 0
            while (guard++ < 100) { val (l, more) = load(off, 200); ids.addAll(l.map { it.id }); off += l.size; if (!more || l.isEmpty()) break }
            val id = NasX.createAlbum(name)
            ids.chunked(200).forEach { NasX.addToAlbum(id, it, name) }
            HomeState.albums = null
            ids.size
        }.onSuccess { toast(ctx, "已创建相册「$name」，$it 张") }.onFailure { toast(ctx, it.message ?: "创建失败") }
    }
}

@Composable
private fun ScanStatusCard(rows: List<Triple<String, Pair<Int, Int>, Boolean>>, running: Boolean, note: String, onToggle: () -> Unit, onSettings: () -> Unit) {
    val allDone = rows.all { it.second.second > 0 && it.second.first >= it.second.second }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (allDone) "全部整理完毕" else if (running) "正在整理" else "整理暂停", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (allDone) "有新照片加入时自动处理" else note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (!allDone) Text(if (running) "暂停" else "立即继续", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 7.dp))
            else Text("扫描设置", color = C.Sub, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSettings).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        if (!allDone) rows.forEach { (label, pr, on) ->
            val (d, t) = pr
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (on) C.Text else C.Sub, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f).background(if (on) C.Accent else C.Faint))
                }
                Text("${n(d)}/${n(t)}", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

private fun openGroup(name: String, sub: String, ps: List<Photo>) =
    Nav.push(Route.Collection(name, sub, PhotoSource(pageSize = 100000) { o, _ -> if (o > 0) emptyList<Photo>() to false else ps to false }))

@Composable
private fun RowHeader(title: String, trailing: String = "", action: String? = null, extra: Pair<String, () -> Unit>? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (trailing.isNotEmpty()) Text(trailing, color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        if (extra != null) Text(extra.first, color = C.Sub, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = extra.second).padding(horizontal = 8.dp, vertical = 4.dp))
        if (action != null) Text(action, color = C.Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CoverCard(title: String, sub: String, cover: String?, small: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val w = if (small) 120.dp else 150.dp; val h = if (small) 120.dp else 180.dp
    Box(Modifier.size(w, h).pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface).combinedClickable(onLongClick = onLong, onClick = onClick)) {
        NetImage(cover, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(C.Scrim))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String, progress: Float?, action: String?, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (action != null) Text(action, color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = C.Accent, trackColor = C.Surface2,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Text("扫描", color = C.Accent, fontSize = 13.sp)
        Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

/** 飞牛官方的三个人脸参数。改完只重新归类，不用重新识别。 */
@Composable
fun FaceSettingsDialog(onDismiss: () -> Unit) {
    var conf by remember { mutableFloatStateOf(FaceConfig.conf) }
    var minP by remember { mutableFloatStateOf(FaceConfig.minPhotos.toFloat()) }
    var diff by remember { mutableFloatStateOf(FaceConfig.diff) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("人脸识别设置", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("人脸置信度阈值  %.2f".format(conf), color = C.Text, fontSize = 14.sp)
            Text("越高越只认清晰的正脸；侧脸、模糊、太小的会被忽略", style = MaterialTheme.typography.bodySmall)
            Slider(conf, { conf = (it * 100).toInt() / 100f }, valueRange = 0.3f..0.95f)
            Text("生成人物的最少照片数  ${minP.toInt()} 张", color = C.Text, fontSize = 14.sp)
            Text("一个人出现在这么多张照片里才生成人物", style = MaterialTheme.typography.bodySmall)
            Slider(minP, { minP = it }, valueRange = 1f..20f, steps = 18)
            Text("人物相似度差异阈值  %.2f".format(diff), color = C.Text, fontSize = 14.sp)
            Text("越小越严格：同一人容易被拆成几个；越大越宽松：不同人容易被并在一起", style = MaterialTheme.typography.bodySmall)
            Slider(diff, { diff = (it * 100).toInt() / 100f }, valueRange = 0.15f..0.6f)
            Text("恢复默认（0.65 · 5 张 · 0.36）", color = C.Accent, fontSize = 13.sp, modifier = Modifier.clickable { conf = FaceConfig.DEF_CONF; minP = FaceConfig.DEF_MIN.toFloat(); diff = FaceConfig.DEF_DIFF }.padding(vertical = 8.dp))
            Text("清除识别结果并重新识别", color = C.Danger, fontSize = 13.sp, modifier = Modifier.clickable { confirmClear = true }.padding(vertical = 8.dp))
        }
    }, confirmButton = {
        TextButton(onClick = {
            FaceConfig.conf = conf; FaceConfig.minPhotos = minP.toInt(); FaceConfig.diff = diff; FaceConfig.save()
            Dav.lib()?.let { Faces.lib(it.accountId).cluster(it) }
            onDismiss()
        }) { Text("保存并重新归类", color = C.Accent) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
    if (confirmClear) ConfirmDialog("清除人脸识别结果？", "人物和名字都会清空，之后重新识别。", "清除", onDismiss = { confirmClear = false }) {
        confirmClear = false; Faces.stop(); Dav.lib()?.let { Faces.lib(it.accountId).clear() }; onDismiss()
    }
}



/**
 * 1.1.0 发现 → 回忆：那年今天（有则显示）+ 用最近 30 天的照片生成回忆短片。
 * [recent] 只在点击时计算，避免每次重组都过滤整库。
 */
@Composable
private fun MemoriesSection(memories: List<MemoryGroup>, recent: () -> List<Photo>) {
    MemoriesRow(memories)
    if (memories.isEmpty()) Text("那年今天有照片时会出现在这里。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(C.Card)).background(C.Surface)) {
        ToolRow(LI.film(), "回忆短片", "用最近 30 天的照片生成一段带音乐的短片") {
            val cut = java.time.LocalDate.now().minusDays(30).let { "%04d:%02d:%02d".format(it.year, it.monthValue, it.dayOfMonth) }
            val ps = Hidden.visible(recent().filter { it.day >= cut }.sortedBy { it.time })
            openMemoryClip("最近的回忆", "近 30 天 · ${ps.size} 张", ps)
        }
    }
}
