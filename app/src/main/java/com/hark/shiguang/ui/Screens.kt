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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
        } catch (t: Throwable) {
            // 1.7.30：页面离开（协程取消）不是加载失败：复位 loading 后照常取消，不留「Job was cancelled」错误页、也不停止翻页
            if (t is kotlinx.coroutines.CancellationException) { loading = false; throw t }
            error = t.message ?: "加载失败"; more = false
        }
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
    /**
     * 1.7.24：现在显示的是上次保存的内容（还没拉到新的，或者拉失败了）。[offline] = 这次联网失败，首页显示离线提示。
     * 缓存状态下不翻页（done = true），恢复联网后 [refresh] 整体换成新的。
     */
    var cached by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set

    /**
     * 1.7.24 缓存优先：首次打开先显示上次的时间线（[TimelineCache]），后台照常拉新的；新的拉到了再整体换上，
     * 拉不到就保留现在显示的（缓存或上次加载的）并标记离线。以前每次刷新先清空，断网就只剩「加载失败」，
     * 下拉刷新时网格也会先闪成骨架。
     */
    suspend fun refresh() {
        if (loading) return
        loading = true
        try { refreshInner() } finally { loading = false }
    }

    private suspend fun refreshInner() {
        val acct = com.hark.shiguang.NasAccounts.currentId
        if (photos.isEmpty() && acct.isNotEmpty()) {
            val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.hark.shiguang.TimelineCache.load(acct) }
            if (snap != null && snap.photos.isNotEmpty() && photos.isEmpty()) {
                photos.addAll(snap.photos); days = snap.days; stats = snap.stats
                months = snap.days.groupBy { "%04d:%02d".format(it.year, it.month) }.map { (k, v) -> k to v.sumOf { it.count } }.sortedByDescending { it.first }
                total = snap.days.sumOf { it.count }
                cached = true; done = true
            }
        }
        val fresh = TimelineSource()
        fresh.next()
        if (fresh.error == null || photos.isEmpty()) {
            adopt(fresh)
            if (fresh.error == null && acct.isNotEmpty() && acct == com.hark.shiguang.NasAccounts.currentId) {
                val snap = com.hark.shiguang.TimelineCache.Snapshot(days, stats, photos.take(com.hark.shiguang.TimelineCache.MAX), System.currentTimeMillis())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.hark.shiguang.TimelineCache.save(acct, snap) }
            }
        } else {
            // 拉新失败、手上有内容（缓存或上次加载的）：留着，标记离线，不翻页（翻页会和缓存重复）
            offline = true; done = true; error = null
            Diag.log("HOME", "timeline refresh failed, showing ${if (cached) "cached" else "previous"} ${photos.size} photos: ${fresh.error?.take(120)}")
        }
    }

    /** 把另一份（刚联网加载的）状态整体换上来。 */
    private fun adopt(o: TimelineSource) {
        photos.clear(); photos.addAll(o.photos)
        days = o.days; months = o.months; total = o.total; stats = o.stats
        mi = o.mi; offsetInMonth = o.offsetInMonth; indexLoaded = o.indexLoaded; done = o.done; error = o.error
        cached = false; offline = false
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
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) { loading = false; throw t }   // 1.7.30：同 PhotoSource.next
            error = t.message ?: "加载失败"
        }
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
    /** 1.0.2: jump back to the 发现 tab (重复/相似 pages point there for progress). */
    fun openAiTab() { tab = 3; Nav.reset(Route.Home) }

    /**
     * 1.1.0: 照片页筛选 —— 0 全部 / 1 照片 / 2 视频 / 3 实况（「视频」tab 并入照片页）。
     * 照片 = 不是视频（含实况，与旧照片页一致）。记在 Store "photo.filter"。
     */
    var photoFilter by mutableIntStateOf(Store.getStr("photo.filter").toIntOrNull()?.coerceIn(0, 3) ?: 0)
        private set
    fun setFilter(f: Int) { photoFilter = f.coerceIn(0, 3); Store.putStr("photo.filter", photoFilter.toString()) }
    /** 第 6 批 沉浸视图第一步：图库网格 ↔ 单列大图流，记在 Store "photo.immersive"（默认网格）。 */
    var immersive by mutableStateOf(Store.getStr("photo.immersive") == "1")
        private set
    fun useImmersive(on: Boolean) { immersive = on; Store.putStr("photo.immersive", if (on) "1" else "") }
    fun accepts(p: com.hark.shiguang.data.Photo, f: Int = photoFilter): Boolean = when (f) { 1 -> !p.isVideo; 2 -> p.isVideo; 3 -> p.isLive && !p.isVideo; else -> true }

    /**
     * 1.1.0: 旧 tab 索引兼容（保存的 nav.tab、通知/深链）：
     * 0 照片 · 1 视频（旧）→ 照片页 + 视频筛选 · 2 相册（照片页里的「相册」）· 3 发现（原 AI）· 4 设置 · 5 影视 · 6 音乐（1.4.0 从影音拆出）。
     */
    fun openTab(i: Int) { if (i == 1) { setFilter(2); tab = 0 } else tab = if (i in 0..6) i else 0 }
    /** Deep link names (intent extra "tab"): photos / videos / albums / discover (ai) / settings / media (movies, music). */
    fun openTab(name: String) = openTab(when (name.lowercase()) { "videos", "video" -> 1; "albums", "album" -> 2; "discover", "ai" -> 3; "settings" -> 4; "media", "movies" -> 5; "music" -> 6; else -> 0 })
    var columns by mutableIntStateOf(Store.columns)
    var dirty by mutableStateOf(false)
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
    var found by remember { mutableStateOf<List<com.hark.shiguang.data.Endpoint.Found>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val addrFocus = remember { FocusRequester() }
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

    Box(Modifier.fillMaxSize().appBackground()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.padding(top = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Nav.stack.size > 1) IconButton(onClick = { Nav.pop() }, modifier = Modifier.offset(x = (-12).dp)) { Icon(LI.back(), "返回", tint = C.Text, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            // 1.7.9 v2：标志放在一块带强调色光晕的玻璃方块里
            Box(Modifier.size(64.dp).drawBehind {
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(C.Accent.copy(alpha = 0.35f), Color.Transparent),
                    radius = size.minDimension * 1.1f), radius = size.minDimension * 1.1f)
            }.glass(RoundedCornerShape(22.dp), null, tint = C.Surface, shadow = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(2.dp).background(C.Text))
                Box(Modifier.offset(x = 5.dp).size(9.dp).clip(CircleShape).background(C.Accent))
            }
            Spacer(Modifier.height(22.dp))
            Text("连接飞牛", style = MaterialTheme.typography.displaySmall)
            Text("一维相册 · 你的照片，都在身边", style = MaterialTheme.typography.bodyMedium, color = C.Sub, modifier = Modifier.padding(top = 6.dp))
            val hist = com.hark.shiguang.NasAccounts.list
            if (hist.isNotEmpty()) {
                Text("最近登录", style = MaterialTheme.typography.titleSmall, color = C.Sub, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hist.forEach { a ->
                        // 1.7.9：最近登录 = 玻璃胶囊（48dp 高的触摸区）
                        Row(Modifier.heightIn(min = 44.dp).glass(CircleShape, null, tint = C.Surface, shadow = 0.dp, highlight = 0.7f).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            if (a.token.isNotEmpty()) { com.hark.shiguang.NasAccounts.switchTo(a.id); HomeState.clear(); Nav.reset(Route.Home) }
                            else { url = a.url.substringAfter("://").substringBeforeLast(":"); port = a.url.substringAfterLast(":", "").takeIf { it.all(Char::isDigit) }.orEmpty(); user = a.user }
                        }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(LI.nas(), null, tint = C.Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                            Text("${a.title} · ${a.user}", style = MaterialTheme.typography.labelLarge, color = C.Text)
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { Field("地址", url, { url = it }, KI.nas(), "IP、域名或 FN ID", KeyboardType.Uri, focusRequester = addrFocus) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(96.dp)) { Field("端口", port, { v -> port = v.filter { it.isDigit() }.take(5) }, null, "自动", KeyboardType.Number) }
            }
            Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("域名会自动补全 https/http 和端口（443、5667、5666、8000），端口可手动改。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text(if (scanning) "正在搜索局域网…" else "搜索局域网里的飞牛", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                // H17: 48dp tall touch target for the 13sp text link
                modifier = Modifier.padding(top = 2.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp)).clickable(enabled = !scanning) {
                    scanning = true; found = null
                    scope.launch { found = runCatching { Endpoint.discover() }.getOrDefault(emptyList()); scanning = false }
                }.padding(horizontal = 4.dp))
            // 用户 2026-10-23 反馈 #1：扫描期间显示进度
            if (scanning) Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp)) {
                val t = LanScan.total
                if (t > 0) LinearProgressIndicator(progress = { (LanScan.done.toFloat() / t).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Surface2)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Surface2)
                Text(LanScan.phase.ifEmpty { "正在搜索…" } + if (t > 0) "（${LanScan.done}/$t）" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            found?.let { l ->
                if (l.isEmpty() && !scanning) Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
                    Text("没找到飞牛。" + LanScan.lastReason, style = MaterialTheme.typography.bodySmall)
                    Text("手动输入地址", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp)).clickable {
                            found = null; runCatching { addrFocus.requestFocus() }
                        }.padding(horizontal = 4.dp))
                }
                l.forEach { f ->
                    // 1.7.9：搜到的飞牛 = 玻璃卡片（强调色圆片 + 名字 + 地址）
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth().glass(RoundedCornerShape(Radius.l), null, tint = C.Surface, shadow = 6.dp).clip(RoundedCornerShape(Radius.l))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        url = f.host; port = f.port
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(LI.nas(), null, tint = C.Accent, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.name.ifEmpty { "飞牛 NAS" }, color = C.Text, style = MaterialTheme.typography.titleSmall)
                            Text(f.url.removePrefix("http://"), color = C.Sub, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Field("账号", user, { user = it }, KI.person(), "飞牛 OS 用户名")
            Spacer(Modifier.height(14.dp))
            Field("密码", pass, { pass = it }, KI.lock(), "", KeyboardType.Password, password = true, ime = if (need2fa) ImeAction.Next else ImeAction.Go, onGo = ::go)
            if (need2fa) {
                Spacer(Modifier.height(14.dp))
                Field("二步验证码", otp, { v -> otp = v.filter { it.isDigit() }.take(8) }, KI.shield(), "验证器 App 里的 6 位数字", KeyboardType.Number, ime = ImeAction.Go, onGo = ::go)
            }
            AnimatedVisibility(err != null) {
                Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(C.Danger.copy(alpha = 0.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(KI.alert(), null, tint = C.Danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err.orEmpty(), color = C.Text, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            GradientButton("登录", Modifier.fillMaxWidth(), loading = loading, onClick = ::go)
            // 1.7.9：次要入口改成玻璃次要按钮（和主按钮同宽同高）
            SecondaryButton("只用 WebDAV，不连飞牛", Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Store.source = "dav"; HomeState.source = "dav"; Nav.reset(Route.Home); Nav.push(Route.DavAccounts)
            }
            Spacer(Modifier.height(10.dp))
            Text("账号密码只保存在本机，并经过系统加密。一维相册为第三方应用，与飞牛官方无关。", style = MaterialTheme.typography.bodySmall, color = C.Faint, lineHeight = 18.sp)
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 1.7.9 v2 玻璃输入框（DESIGN §9）：高 56、圆角 18 的磨砂玻璃（降级外观，不实时模糊）；聚焦时 1.5dp 强调色描边 + 外圈同色柔光，
 * 图标变正文色。[onGlass] = 放在对话框 / 面板这类本身已是玻璃的浮层里：改用一层淡底，不叠玻璃。
 * 密码框右侧的眼睛是 48dp 的按钮，读屏「显示密码 / 隐藏密码」。[label] 为 null 时不显示标题（读屏用 [hint]）。
 */
@Composable
fun Field(
    label: String?, value: String, onChange: (String) -> Unit, icon: ImageVector?, hint: String,
    type: KeyboardType = KeyboardType.Text, password: Boolean = false, ime: ImeAction = ImeAction.Next, onGo: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null, onGlass: Boolean = false, modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val ring by animateColorAsState(if (focused) C.Accent else C.Line, Motion.state(), label = "fieldRing")
    val glow by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, Motion.state(), label = "fieldGlow")
    val accent = C.Accent
    Column(modifier) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = C.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .drawBehind {
                    // 聚焦时外圈一圈强调色柔光（画在形状外面一点）
                    if (glow > 0f) drawRoundRect(accent.copy(alpha = 0.22f * glow), topLeft = androidx.compose.ui.geometry.Offset(-3.dp.toPx(), -3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(21.dp.toPx()))
                }
                .then(if (onGlass) Modifier.clip(shape).background(C.Text.copy(alpha = 0.06f))
                      else Modifier.glass(shape, null, tint = C.Surface, shadow = 0.dp, highlight = 0.8f).clip(shape))
                .border(if (focused) 1.5.dp else 1.dp, if (focused) ring else if (onGlass) C.Line else Color.Transparent, shape)
                .padding(start = 16.dp, end = if (password) 4.dp else 16.dp),
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
                    modifier = Modifier.fillMaxWidth().let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }.onFocusChanged { focused = it.isFocused }
                        .semantics { if (label != null) contentDescription = label else if (hint.isNotEmpty()) contentDescription = hint },
                )
            }
            if (password) Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { show = !show }
                .semantics { contentDescription = if (show) "隐藏密码" else "显示密码" }, contentAlignment = Alignment.Center) {
                Icon(if (show) KI.eye(duo = true) else KI.eye(), null, tint = if (show) C.Accent else C.Sub, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        // 1.7.0：分区标题大号粗体（titleLarge）
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
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
    // 1.7.14：照片区录进 backdrop，返回 / 更多换成玻璃圆钮（API 31+ 真模糊下面的照片）；相册、相似照片、人物等合集页共用
    val backdrop = remember { GlassBackdrop() }
    Box(Modifier.fillMaxSize().appBackground()) {
        val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
        Box(Modifier.fillMaxSize().glassSource(backdrop)) {
        when {
            src.photos.isEmpty() && src.loading -> SkeletonGrid(cols, topPad)
            src.photos.isEmpty() && src.error != null -> ErrorState(src.error, onRetry = { scope.launch { src.refresh() } })
            src.photos.isEmpty() -> StateMessage(KI.photos(), "这里还是空的", "")
            else -> PhotoGrid(entries, cols, src.loading, onEnd = { scope.launch { src.next() } }, onOpen = { Nav.push(Route.Viewer(shown, it)) }, state = grid, top = topPad, bottom = 40.dp)
        }
        }
        Box(Modifier.fillMaxWidth().background(C.TopScrim)) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(KI.back(), "返回", backdrop = backdrop) { Nav.pop() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val s = listOf(r.subtitle, if (src.photos.isNotEmpty()) "${num(src.photos.size)}${if (src.more) "+" else ""} 项" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                    if (s.isNotEmpty()) Text(s, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.album != null) Box {
                    GlassIconButton(KI.moreH(), "更多", backdrop = backdrop) { menu = true }
                    // 1.7.14：去掉写死的不透明底，走玻璃菜单（与 1.7.4 其余菜单一致）
                    AppDropdownMenu(menu, { menu = false }) {
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
        Ops.launch { runCatching { AlbumOps.rename(al, name) }.onSuccess { title = name; toast(ctx, "已重命名") }.onFailure { failToast(ctx, it, "失败") } }
    }
    if (deleting && al != null) ConfirmDialog("删除相册「$title」？", "只删除相册，照片本身不会被删除。", "删除", onDismiss = { deleting = false }) {
        deleting = false
        Ops.launch { runCatching { AlbumOps.delete(al) }.onSuccess { toast(ctx, "相册已删除"); Nav.pop() }.onFailure { failToast(ctx, it, "失败") } }
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
    /** 1.1.0：结果里靠「图中文字」找到的照片（排在最前面，单独一组标注）。 */
    var ocrPaths by mutableStateOf<Set<String>>(emptySet())
    fun reset() { results = null; error = null; note = ""; ocrPaths = emptySet() }
}

/** 搜索结果分组：靠图中文字找到的在前（标「图中文字」），其余标「其他结果」；没有图中文字命中时和以前一样不分组。 */
fun searchEntries(res: List<Photo>, ocr: Set<String>): List<Entry> {
    val k = if (ocr.isEmpty()) 0 else res.takeWhile { it.cloudPath in ocr }.size
    if (k == 0) return buildEntries(res, false)
    val out = ArrayList<Entry>(res.size + 2)
    out += Entry.Header("ocr", "图中文字", "照片里有这些字 · ${num(k)} 张")
    for (i in 0 until k) out += Entry.Cell(res[i], i)
    if (k < res.size) {
        out += Entry.Header("other", "其他结果", "${num(res.size - k)} 张")
        for (i in k until res.size) out += Entry.Cell(res[i], i)
    }
    return out
}

@Composable
fun SearchScreen() {
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var history by remember { mutableStateOf(com.hark.shiguang.SearchHistory.load()) }
    fun run(q: String) {
        if (q.isBlank() && !AdvFilter.active) { SearchState.reset(); return }
        SearchState.query = q; focus.clearFocus()
        if (q.isNotBlank()) { com.hark.shiguang.SearchHistory.add(q); history = com.hark.shiguang.SearchHistory.load() }
        scope.launch {
            SearchState.loading = true; SearchState.error = null; SearchState.ocrPaths = emptySet()
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
                    // 1.1.0 图中文字：按原句匹配照片里识别出的字，命中的排在最前面
                    val ocr = com.hark.shiguang.OcrRunner.search(lib, q.trim())
                    val ocrSet = ocr.mapTo(HashSet()) { it.cloudPath }
                    val done = lib.aiDone(); val total = lib.imagesCount()
                    val ocrOn = com.hark.shiguang.OcrRunner.enabled
                    val ocrLeft = with(com.hark.shiguang.OcrRunner) { if (ocrOn && countsFor == lib.accountId && this.total > 0 && this.done + noImage < this.total) "图中文字已识别 ${this.done} / ${this.total} 张" else null }
                    SearchState.note = listOfNotNull(
                        if (!exact && res.any { it.cloudPath !in ocrSet }) "没有完全符合的，下面是部分符合的" else null,
                        if (!aiOn) (if (ocrOn) "未配置 AI，只能按地点、文件名和图中文字找" else "未配置 AI，只能按地点和文件名找") else if (done < total) "AI 已整理 $done / $total 张，结果可能不全" else null,
                        ocrLeft,
                    ).joinToString(" · ")
                    SearchState.ocrPaths = ocrSet
                    if (ocr.isEmpty()) res else ocr + res.filter { it.cloudPath !in ocrSet }
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
    // 1.7.24：多选只属于这一页，离开时清掉（不把搜索结果里的选择带回首页）
    DisposableEffect(Unit) { onDispose { if (Selection.active) Selection.clear() } }
    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        // 1.7.24 v2：与影视搜索 / 全局搜索同款——44dp 返回圆钮 + 内凹搜索槽（高 48）+ 有字时出现的清除圆钮（以前是灰底条 + 右侧「取消」文字）
        Row(Modifier.fillMaxWidth().padding(start = Space.s + 2.dp, end = Space.page, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(KI.back(), size = 44.dp, desc = "返回") { Nav.pop() }
            Row(Modifier.padding(start = Space.m).weight(1f).height(48.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(KI.search(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = Space.s), contentAlignment = Alignment.CenterStart) {
                    if (SearchState.query.isEmpty()) Text("用一句话描述你要找的照片", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        SearchState.query, { SearchState.query = it; if (it.isBlank() && !AdvFilter.active) SearchState.reset() }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(SearchState.query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fr).semantics { contentDescription = "用一句话描述你要找的照片" }
                    )
                }
                AnimatedVisibility(SearchState.query.isNotEmpty(), enter = fadeIn(Motion.state()) + androidx.compose.animation.scaleIn(Motion.move(), 0.6f),
                    exit = fadeOut(Motion.exit()) + androidx.compose.animation.scaleOut(Motion.exit(), 0.6f)) {
                    IconButton(onClick = { SearchState.query = ""; SearchState.reset(); fr.requestFocus() }) { Icon(KI.close(), "清除搜索", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        var filterSheet by remember { mutableStateOf(false) }
        FilterChipsRow(onOpen = { filterSheet = true }, onClear = { AdvFilter.reset(); run(SearchState.query) })
        if (filterSheet) FilterSheet(HomeState.source == "dav", onDismiss = { filterSheet = false }) { filterSheet = false; run(SearchState.query) }
        val res = SearchState.results
        Box(Modifier.weight(1f)) {
            when {
                SearchState.loading -> SkeletonGrid(4, 0.dp)
                // 1.7.17：搜索失败（多半是网络 / AI 接口）给「重试」，按原词再搜一次
                SearchState.error != null -> ErrorState(SearchState.error, onRetry = { run(SearchState.query) })
                // 1.7.24 v2：最近搜索是一排胶囊 +「清空」（与影视搜索 / 全局搜索一致），分区标题走 ContentHeader
                res == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                    if (history.isNotEmpty()) {
                        ContentHeader("最近搜索", extra = "清空" to { com.hark.shiguang.SearchHistory.clear(); history = emptyList() })
                        LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(history) { h -> ChoiceChip(h, false, role = androidx.compose.ui.semantics.Role.Button) { run(h) } }
                        }
                    }
                    ContentHeader("试试这样搜")
                    listOf("海边的日落", "在吃火锅", "穿红衣服的小孩", "下雪的街道", "猫在睡觉", "生日蛋糕").forEach { s ->
                        Row(Modifier.fillMaxWidth().heightIn(min = Space.row).clickable(role = androidx.compose.ui.semantics.Role.Button) { run(s) }.padding(horizontal = Space.page),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(KI.discover(), null, tint = C.Gold, modifier = Modifier.size(18.dp))
                            Text(s, color = C.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Space.m))
                        }
                    }
                    Text(if (HomeState.source == "dav") "WebDAV 照片按 AI 整理出的标签和描述来搜，整理越多越准。" + (if (com.hark.shiguang.OcrRunner.enabled) "也能直接搜照片里的文字，比如店名、票据上的字。" else "") else "需要在飞牛相册中开启 AI 智能识别，才能用自然语言搜索。",
                        color = C.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.m))
                }
                res.isEmpty() -> StateMessage(KI.search(), "没有找到相关照片", SearchState.note.ifEmpty { "换个说法试试，比如「蓝天」「孩子在笑」。" })
                // 1.7.24：搜索结果可以多选（长按一张或点「选择」），底部多选栏和首页一样：加入相册、下载、分享、删除…
                else -> PhotoGrid(remember(res, SearchState.ocrPaths) { searchEntries(res, SearchState.ocrPaths) }, adaptiveCols(4), false, onEnd = {}, onOpen = { Nav.push(Route.Viewer(res, it)) }, top = 4.dp,
                    bottom = if (Selection.active) 160.dp else 40.dp, pageSelectAll = false,   // 搜索页标题行自己有「全选」
                    header = { Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("找到 ${num(res.size)} 项", color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.weight(1f))
                            val allOn = Selection.active && res.all { Selection.has(it) }
                            HeaderTextButton(when { !Selection.active -> "选择"; allOn -> "全不选"; else -> "全选" }, C.Accent) {
                                when {
                                    !Selection.active -> Selection.enter()
                                    allOn -> Selection.clear()
                                    else -> res.forEach { Selection.set(it, true) }
                                }
                            }
                        }
                        if (SearchState.note.isNotEmpty()) Text(SearchState.note, color = C.Gold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                    } })
            }
            SelectionBar(Modifier.align(Alignment.BottomCenter), onChanged = { run(SearchState.query) })
        }
    }
}
