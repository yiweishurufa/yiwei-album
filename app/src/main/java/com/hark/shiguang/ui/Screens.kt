package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.Store
import com.hark.shiguang.Diag
import com.hark.shiguang.data.*
import com.hark.shiguang.data.Endpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.text.NumberFormat

// ============================================================ data sources

class PhotoSource(val pageSize: Int = 120, val grouped: Boolean = true, private val loader: suspend (offset: Int, limit: Int) -> Pair<List<Photo>, Boolean>) {
    val photos = mutableStateListOf<Photo>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var more by mutableStateOf(true)
    var started = false
    suspend fun next() {
        if (loading || !more) return
        loading = true; error = null
        try {
            val (l, m) = loader(photos.size, pageSize)
            val seen = photos.mapTo(HashSet()) { it.id }
            photos.addAll(l.filter { seen.add(it.id) })
            more = m && l.isNotEmpty()
        } catch (t: Throwable) { error = t.message ?: "加载失败"; more = false }
        loading = false
    }
    suspend fun refresh() { photos.clear(); more = true; next() }
}

/** Timeline: walks the day index month by month, like the official web client. */
class TimelineSource {
    private companion object { const val PAGE = 500 }
    val photos = mutableStateListOf<Photo>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableIntStateOf(0)
    var stats by mutableStateOf(0 to 0)
    var months by mutableStateOf<List<Pair<String, Int>>>(emptyList()) // "YYYY:MM" to count
    /** Per-day counts from the timeline index (used by 那年今天). */
    var days by mutableStateOf<List<DayCount>>(emptyList())
    private var mi = 0
    private var offsetInMonth = 0
    private var indexLoaded = false
    var done by mutableStateOf(false)

    suspend fun refresh() {
        photos.clear(); mi = 0; offsetInMonth = 0; indexLoaded = false; done = false; error = null; next()
    }

    /** Keeps paging until a photo whose day starts with [prefix] ("YYYY" or "YYYY:MM") is loaded. */
    suspend fun ensureLoaded(prefix: String) {
        var guard = 0
        while (!done && photos.none { it.day.startsWith(prefix) } && guard++ < 200) {
            if (loading) { kotlinx.coroutines.delay(50); continue }
            next()
        }
    }

    suspend fun next() {
        if (loading || done) return
        loading = true; error = null
        try {
            if (!indexLoaded) {
                val days = Repo.timeline()
                this.days = days
                months = days.groupBy { "%04d:%02d".format(it.year, it.month) }.map { (k, v) -> k to v.sumOf { it.count } }.sortedByDescending { it.first }
                total = days.sumOf { it.count }
                indexLoaded = true
                stats = Repo.stat()
                if (months.isEmpty()) { // fall back to a flat walk
                    val (l, _) = Repo.photos(Repo.FAR_START, Repo.FAR_END, 0, 300)
                    photos.addAll(l); done = true
                }
            }
            var added = 0
            while (!done && added < 90) {
                if (mi >= months.size) { done = true; break }
                // 1.0.1: when we are at the start of a month and the next months are small, fetch up to 4 whole months
                // in parallel (one request each, ≤500 items) and append them in order; big months still page 500 at a time.
                if (offsetInMonth == 0) {
                    val batch = ArrayList<Pair<String, Int>>()
                    var sum = 0
                    var k = mi
                    while (k < months.size && batch.size < 4 && months[k].second <= PAGE && (batch.isEmpty() || sum + months[k].second <= PAGE * 2)) {
                        batch.add(months[k]); sum += months[k].second; k++
                    }
                    if (batch.size > 1) {
                        val got = kotlinx.coroutines.coroutineScope {
                            batch.map { (m, count) -> async { Repo.photos("$m:01 00:00:00", Repo.monthEnd(m), 0, count.coerceAtLeast(1)).first } }.awaitAll()
                        }
                        got.forEach { photos.addAll(it); added += it.size }
                        mi += batch.size
                        continue
                    }
                }
                val (m, count) = months[mi]
                val limit = minOf(PAGE, count - offsetInMonth).coerceAtLeast(1)
                val (l, _) = Repo.photos("$m:01 00:00:00", Repo.monthEnd(m), offsetInMonth, limit)
                photos.addAll(l); added += l.size
                offsetInMonth += l.size
                if (l.isEmpty() || offsetInMonth >= count) { mi++; offsetInMonth = 0 }
            }
        } catch (t: Throwable) { error = t.message ?: "加载失败" }
        loading = false
    }
}

object HomeState {
    var timeline by mutableStateOf(TimelineSource())
    var source by mutableStateOf(Store.source)
    var albums by mutableStateOf<List<Album>?>(null)
    var persons by mutableStateOf<List<Person>?>(null)
    var places by mutableStateOf<List<Place>?>(null)
    var tab by mutableIntStateOf(0)
    /** 1.0.2: jump back to the AI tab (重复/相似 pages point there for progress). */
    fun openAiTab() { tab = 3; Nav.reset(Route.Home) }
    var columns by mutableIntStateOf(Store.columns)
    var dirty by mutableStateOf(false)
    var showConn by mutableStateOf(false)
    var showDav by mutableStateOf(false)
    var showDate by mutableStateOf(false)
    var renamePerson by mutableStateOf<Person?>(null)
    var switching by mutableStateOf(false)
    var showFaceCfg by mutableStateOf(false)
    var renameFace by mutableStateOf<com.hark.shiguang.FacePerson?>(null)
    private class Snap(val timeline: TimelineSource, val albums: List<Album>?, val persons: List<Person>?, val places: List<Place>?)
    private val snaps = HashMap<String, Snap>()
    /** Keeps the current NAS account's loaded state so switching back restores it instead of reloading. */
    fun stash(accountId: String) { if (accountId.isNotEmpty()) snaps[accountId] = Snap(timeline, albums, persons, places) }
    fun restore(accountId: String) {
        val s = snaps[accountId]
        if (s == null) clear() else { timeline = s.timeline; albums = s.albums; persons = s.persons; places = s.places }
    }
    fun forget(accountId: String) { snaps.remove(accountId) }
    fun clear() { timeline = TimelineSource(); albums = null; persons = null; places = null; ScrollMem.reset() }
    fun useSource(s: String) { source = s; Store.source = s }
}

private fun num(n: Int) = NumberFormat.getIntegerInstance().format(n)

// ============================================================ login

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoginScreen() {
    var url by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var need2fa by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun go() {
        if (url.isBlank() || user.isBlank() || pass.isBlank()) { err = "请填写完整"; return }
        focus.clearFocus(); loading = true; err = null
        scope.launch {
            runCatching { Endpoint.loginAuto(url, port, user.trim(), pass, otp.trim()) }
                .onSuccess { (base, tk) ->
                    Diag.log("LOGIN", "ok $base")
                    com.hark.shiguang.NasAccounts.saveLogin(base, user.trim(), pass, tk)
                    Store.source = "fn"; HomeState.source = "fn"
                    HomeState.clear(); Nav.reset(Route.Home)
                }
                .onFailure { e ->
                    if (e is ApiException && e.code == FnClient.NEED_2FA) need2fa = true
                    err = e.message ?: "登录失败"; Diag.log("LOGIN", "fail ${e.message}")
                }
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.padding(top = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Nav.stack.size > 1) Icon(LI.back(), null, tint = C.Text, modifier = Modifier.size(24.dp).clickable { Nav.pop() })
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(2.dp).background(C.Text))
                Box(Modifier.offset(x = 5.dp).size(9.dp).clip(CircleShape).background(C.Accent))
            }
            Spacer(Modifier.height(22.dp))
            Text("连接飞牛", style = MaterialTheme.typography.displaySmall)
            Text("一维相册 · 你的照片，都在身边", style = MaterialTheme.typography.bodyMedium, color = C.Sub, modifier = Modifier.padding(top = 6.dp))
            val hist = com.hark.shiguang.NasAccounts.list
            if (hist.isNotEmpty()) {
                Text("最近登录", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hist.forEach { a ->
                        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.Surface2).clickable {
                            if (a.token.isNotEmpty()) { com.hark.shiguang.NasAccounts.switchTo(a.id); HomeState.clear(); Nav.reset(Route.Home) }
                            else { url = a.url.substringAfter("://").substringBeforeLast(":"); port = a.url.substringAfterLast(":", "").takeIf { it.all(Char::isDigit) }.orEmpty(); user = a.user }
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(LI.nas(), null, tint = C.Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text("${a.title} · ${a.user}", fontSize = 13.sp, color = C.Text)
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { Field("地址", url, { url = it }, Icons.Rounded.Dns, "IP、域名或 FN ID", KeyboardType.Uri) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(96.dp)) { Field("端口", port, { v -> port = v.filter { it.isDigit() }.take(5) }, null, "自动", KeyboardType.Number) }
            }
            Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("域名会自动补全 https/http 和端口（443、5667、5666、8000），端口可手动改。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text(if (scanning) "正在搜索局域网…" else "搜索局域网里的飞牛", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp).clickable(enabled = !scanning) {
                    scanning = true; scope.launch { found = Endpoint.discover(); scanning = false }
                })
            found?.let { l ->
                if (l.isEmpty()) Text("没找到。确认手机和 NAS 在同一个 Wi-Fi。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                l.forEach { f ->
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).clickable {
                        url = f.substringAfter("://").substringBefore(":"); port = f.substringAfterLast(":")
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LI.nas(), null, tint = C.Accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text(f.removePrefix("http://"), color = C.Text, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Field("账号", user, { user = it }, Icons.Rounded.PersonOutline, "飞牛 OS 用户名")
            Spacer(Modifier.height(14.dp))
            Field("密码", pass, { pass = it }, Icons.Rounded.Lock, "", KeyboardType.Password, password = true, ime = if (need2fa) ImeAction.Next else ImeAction.Go, onGo = ::go)
            if (need2fa) {
                Spacer(Modifier.height(14.dp))
                Field("二步验证码", otp, { v -> otp = v.filter { it.isDigit() }.take(8) }, Icons.Rounded.Security, "验证器 App 里的 6 位数字", KeyboardType.Number, ime = ImeAction.Go, onGo = ::go)
            }
            AnimatedVisibility(err != null) {
                Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(C.Danger.copy(alpha = 0.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = C.Danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err.orEmpty(), color = C.Text, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            GradientButton("登录", Modifier.fillMaxWidth(), loading = loading, onClick = ::go)
            Text("只用 WebDAV，不连飞牛", color = C.Sub, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).clickable {
                Store.source = "dav"; HomeState.source = "dav"; Nav.reset(Route.Home); Nav.push(Route.DavAccounts)
            }.padding(8.dp))
            Spacer(Modifier.height(10.dp))
            Text("账号密码只保存在本机，并经过系统加密。一维相册为第三方应用，与飞牛官方无关。", style = MaterialTheme.typography.bodySmall, color = C.Faint, lineHeight = 18.sp)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, icon: ImageVector?, hint: String,
    type: KeyboardType = KeyboardType.Text, password: Boolean = false, ime: ImeAction = ImeAction.Next, onGo: (() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) C.Accent.copy(alpha = 0.8f) else C.Line, label = "b")
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface)
                .border(1.dp, border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) { Icon(icon, null, tint = if (focused) C.Text else C.Sub, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = C.Faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value, onChange, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = C.Text),
                    cursorBrush = SolidColor(C.Accent),
                    visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime),
                    keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (password) Icon(
                if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = C.Sub,
                modifier = Modifier.size(20.dp).clickable { show = !show }
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.bodySmall)
    }
}

// ============================================================ collection

@Composable
fun CollectionScreen(r: Route.Collection) {
    val src = r.source
    val scope = rememberCoroutineScope()
    LaunchedEffect(src) { if (!src.started) { src.started = true; src.next() } }
    val shown by remember(src) { derivedStateOf { com.hark.shiguang.Hidden.visible(src.photos.toList()) } }
    val entries by remember(src) { derivedStateOf { buildEntries(shown, src.grouped) } }
    val grid = rememberMemGrid("c" + System.identityHashCode(r))
    val cols = adaptiveCols(HomeState.columns.coerceAtMost(4))
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(r.title) }
    DisposableEffect(r) { AlbumCtx.current = r.album; onDispose { if (AlbumCtx.current == r.album) AlbumCtx.current = null } }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
        when {
            src.photos.isEmpty() && src.loading -> SkeletonGrid(cols, topPad)
            src.photos.isEmpty() && src.error != null -> StateMessage(Icons.Rounded.CloudOff, "加载失败", src.error!!, "重试") { scope.launch { src.refresh() } }
            src.photos.isEmpty() -> StateMessage(Icons.Rounded.PhotoLibrary, "这里还是空的", "")
            else -> PhotoGrid(entries, cols, src.loading, onEnd = { scope.launch { src.next() } }, onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = topPad, bottom = 40.dp)
        }
        Box(Modifier.fillMaxWidth().background(C.TopScrim)) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.ArrowBackIosNew, size = 40.dp) { Nav.pop() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val s = listOf(r.subtitle, if (src.photos.isNotEmpty()) "${num(src.photos.size)}${if (src.more) "+" else ""} 项" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                    if (s.isNotEmpty()) Text(s, style = MaterialTheme.typography.bodySmall)
                }
                if (shown.any { !it.isVideo }) {
                    // 1.0.1 #13: 人物 / 地点 / 相册 → 回忆短片 (uses what is loaded so far)
                    RoundIcon(LI.film(), size = 40.dp) { openMemoryClip(title, r.subtitle, shown) }
                    Spacer(Modifier.width(8.dp))
                }
                if (r.album != null) Box {
                    RoundIcon(Icons.Rounded.MoreHoriz, size = 40.dp) { menu = true }
                    DropdownMenu(menu, { menu = false }, Modifier.background(C.Surface2)) {
                        DropdownMenuItem({ Text("重命名", color = C.Text) }, { menu = false; renaming = true })
                        DropdownMenuItem({ Text("删除相册", color = C.Danger) }, { menu = false; deleting = true })
                    }
                }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { scope.launch { src.refresh() } })
    }
    val al = r.album
    if (renaming && al != null) InputDialog("重命名相册", title, "相册名称", onDismiss = { renaming = false }) { name ->
        renaming = false
        Ops.launch { runCatching { AlbumOps.rename(al, name) }.onSuccess { title = name; toast(ctx, "已重命名") }.onFailure { toast(ctx, it.message ?: "失败") } }
    }
    if (deleting && al != null) ConfirmDialog("删除相册「$title」？", "只删除相册，照片本身不会被删除。", "删除", onDismiss = { deleting = false }) {
        deleting = false
        Ops.launch { runCatching { AlbumOps.delete(al) }.onSuccess { toast(ctx, "相册已删除"); Nav.pop() }.onFailure { toast(ctx, it.message ?: "失败") } }
    }
}

// ============================================================ search

object SearchState {
    var pending by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Photo>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    fun reset() { results = null; error = null; note = "" }
}

@Composable
fun SearchScreen() {
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    fun run(q: String) {
        if (q.isBlank() && !AdvFilter.active) { SearchState.reset(); return }
        SearchState.query = q; focus.clearFocus()
        scope.launch {
            SearchState.loading = true; SearchState.error = null
            runCatching {
                if (q.isBlank()) {
                    val (pool, note) = AdvFilter.pool(HomeState.source == "dav")
                    SearchState.note = note
                    return@runCatching pool
                }
                val words = q.trim().split(Regex("[\\s,，、。]+")).filter { it.isNotBlank() }
                val aiOn = com.hark.shiguang.AiConfig.configured
                val concepts = (if (aiOn) com.hark.shiguang.AiClient.concepts(q.trim()) else emptyList()).ifEmpty { words.map { listOf(it) } }
                if (HomeState.source == "dav") {
                    val lib = com.hark.shiguang.Dav.lib() ?: error("没有 WebDAV 账户")
                    lib.ensure()
                    val (res, exact) = lib.aiSearch(concepts)
                    val done = lib.aiDone(); val total = lib.imagesCount()
                    SearchState.note = listOfNotNull(
                        if (!exact && res.isNotEmpty()) "没有完全符合的，下面是部分符合的" else null,
                        if (!aiOn) "未配置 AI，只能按地点和文件名找" else if (done < total) "AI 已整理 $done / $total 张，结果可能不全" else null,
                    ).joinToString(" · ")
                    res
                } else {
                    val base = Repo.search(q.trim())
                    val extra = com.hark.shiguang.AiRunner.nasSearchIds(concepts.flatten())
                    SearchState.note = ""
                    if (extra.isEmpty()) base else base + HomeState.timeline.photos.filter { it.id in extra && base.none { b -> b.id == it.id } }
                }
            }.map { AdvFilter.apply(it) }.onSuccess { SearchState.results = it }.onFailure { SearchState.error = it.message; SearchState.results = null }
            SearchState.loading = false
        }
    }
    LaunchedEffect(Unit) {
        val p = SearchState.pending
        if (p != null) { SearchState.pending = null; run(p) } else if (SearchState.results == null) fr.requestFocus()
    }
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = C.Sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (SearchState.query.isEmpty()) Text("用一句话描述你要找的照片", color = C.Faint, fontSize = 15.sp)
                    BasicTextField(
                        SearchState.query, { SearchState.query = it; if (it.isBlank() && !AdvFilter.active) SearchState.reset() }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = C.Text), cursorBrush = SolidColor(C.Rose),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(SearchState.query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fr)
                    )
                }
                if (SearchState.query.isNotEmpty()) Icon(Icons.Rounded.Cancel, null, tint = C.Faint, modifier = Modifier.size(18.dp).clickable { SearchState.query = ""; SearchState.reset(); fr.requestFocus() })
            }
            Spacer(Modifier.width(12.dp))
            Text("取消", color = C.Text, fontSize = 15.sp, modifier = Modifier.clickable { Nav.pop() })
        }
        var filterSheet by remember { mutableStateOf(false) }
        FilterChipsRow(onOpen = { filterSheet = true }, onClear = { AdvFilter.reset(); run(SearchState.query) })
        if (filterSheet) FilterSheet(HomeState.source == "dav", onDismiss = { filterSheet = false }) { filterSheet = false; run(SearchState.query) }
        val res = SearchState.results
        Box(Modifier.weight(1f)) {
            when {
                SearchState.loading -> SkeletonGrid(4, 0.dp)
                SearchState.error != null -> StateMessage(Icons.Rounded.SearchOff, "搜索失败", SearchState.error!!)
                res == null -> Column(Modifier.padding(18.dp)) {
                    Text("试试这样搜", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    listOf("海边的日落", "在吃火锅", "穿红衣服的小孩", "下雪的街道", "猫在睡觉", "生日蛋糕").forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { run(s) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = C.Gold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp)); Text(s, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (HomeState.source == "dav") "WebDAV 照片按 AI 整理出的标签和描述来搜，整理越多越准。" else "需要在飞牛相册中开启 AI 智能识别，才能用自然语言搜索。", style = MaterialTheme.typography.bodySmall)
                }
                res.isEmpty() -> StateMessage(Icons.Rounded.ImageSearch, "没有找到相关照片", SearchState.note.ifEmpty { "换个说法试试，比如「蓝天」「孩子在笑」。" })
                else -> PhotoGrid(buildEntries(res, false), adaptiveCols(4), false, onEnd = {}, onOpen = { Nav.push(Route.Viewer(res, it)) }, top = 4.dp, bottom = 40.dp,
                    header = { Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                        Text("找到 ${num(res.size)} 项", style = MaterialTheme.typography.bodySmall)
                        if (SearchState.note.isNotEmpty()) Text(SearchState.note, color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    } })
            }
        }
    }
}
