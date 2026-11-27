package com.hark.shiguang.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.hark.shiguang.ViewerZoom
import com.hark.shiguang.CameraName
import coil.request.ImageRequest
import com.hark.shiguang.Nav
import androidx.compose.runtime.toMutableStateList
import com.hark.shiguang.data.AuthX
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.Repo
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(photosIn: List<Photo>, start: Int, slideshowStart: Boolean = false) {
    val photos = remember(photosIn) { photosIn.toMutableStateList() }
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 1.6.2 共享元素式转场（DESIGN §7「大图 / 播放器打开」）：从点到的缩略图位置长出来，下拉 / 返回时缩回它在网格里的位置
    val heroFrom = remember { Hero.from }
    val heroThumb = remember { Hero.fromThumb }
    val heroAnim = remember { Animatable(if (heroFrom != null && !Motion.reduced) 0f else 1f) }
    LaunchedEffect(Unit) { Hero.from = null; Hero.closed = false; heroAnim.animateTo(1f, Motion.zoom()) }
    // 1.7.18 预加载：翻页组件已经带着左右各 1 张，这里再把左右第 2 张的中图提前拉进缓存，快速连翻时不先看到灰块
    LaunchedEffect(pager.currentPage) {
        kotlinx.coroutines.delay(300)   // 停稳再预取，快速划过的中间页不白下
        val loader = coil.Coil.imageLoader(ctx)
        for (d in intArrayOf(2, -2)) photos.getOrNull(pager.currentPage + d)?.takeIf { it.thumbM.isNotEmpty() }?.let {
            loader.enqueue(ImageRequest.Builder(ctx).data(it.thumbM).build())
        }
    }
    LaunchedEffect(pager.currentPage) {
        photos.getOrNull(pager.currentPage)?.let { ViewerReturn.lastKey = it.key }
        // 翻页后让下面的网格把这一张滚进屏幕：下拉缩回时才有地方可落（网格只在不可见时才滚）
        if (pager.currentPage != start) ViewerReturn.tick++
    }
    DisposableEffect(Unit) { onDispose { ViewerReturn.tick++ } }
    if (photos.isEmpty()) { LaunchedEffect(Unit) { Nav.pop() }; return }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    // 1.7.18：放大看细节时顶部 / 底部按钮自动收起；缩回原大时恢复成放大前的样子（放大前是收起的就保持收起，参考 Immich 3.4）
    var chromeBeforeZoom by remember { mutableStateOf(true) }
    LaunchedEffect(zoomed) {
        if (zoomed) { chromeBeforeZoom = chrome; chrome = false } else chrome = chromeBeforeZoom
    }
    var info by remember { mutableStateOf(false) }
    var albumFor by remember { mutableStateOf<Photo?>(null) }
    var favTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 下拉关闭：照片跟手（x / y 都跟），越往下越小、背景越透明；松手按距离或速度决定关闭还是弹回
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val closeAnim = remember { Animatable(0f) }
    var closeFrom by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var closeTo by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var closePhoto by remember { mutableStateOf<Photo?>(null) }
    var closing by remember { mutableStateOf(false) }
    val dens = androidx.compose.ui.platform.LocalDensity.current
    var screen by remember { mutableStateOf(IntSize(1, 1)) }
    val H = screen.height.toFloat().coerceAtLeast(1f); val W = screen.width.toFloat().coerceAtLeast(1f)
    val dragProgress = (drag.value.y / H).coerceIn(0f, 1f)
    // 1.7.0：照片录进 backdrop，顶部 / 底部的玻璃控件模糊它（API 31+；以下降级为磨砂深色玻璃）
    val backdrop = remember { GlassBackdrop() }
    val dragScale = 1f - dragProgress * 0.45f

    /** 照片在屏幕上 Fit 显示时的矩形。 */
    fun fitRect(ph: Photo): androidx.compose.ui.geometry.Rect {
        val ratio = ph.ratio.takeIf { it > 0f } ?: 1f
        val fw = if (W / H > ratio) H * ratio else W; val fh = fw / ratio
        return androidx.compose.ui.geometry.Rect((W - fw) / 2f, (H - fh) / 2f, (W + fw) / 2f, (H + fh) / 2f)
    }
    /** 关闭：有可落的缩略图就缩回去（共享元素），否则按原来的淡出关闭。可以从拖动中途接上。 */
    fun requestClose() {
        if (closing) return
        val ph = photos.getOrNull(pager.currentPage)
        val target = ph?.let { Hero.bounds(it.key) }?.takeIf { r -> r.width > 0f && r.bottom > 0f && r.top < H && r.right > 0f && r.left < W }
        if (ph == null || target == null || Motion.reduced || ph.isVideo || zoomed) { Nav.pop(); return }
        val fit = fitRect(ph)
        val c = fit.center + drag.value
        val w = fit.width * dragScale; val h = fit.height * dragScale
        closeFrom = androidx.compose.ui.geometry.Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
        closeTo = target; closePhoto = ph; closing = true
        scope.launch {
            closeAnim.snapTo(0f)
            closeAnim.animateTo(1f, Motion.zoom())
            Hero.closed = true
            Nav.pop()
        }
    }
    androidx.activity.compose.BackHandler(enabled = !closing) { requestClose() }

    // 背景：打开时随放大淡入，下拉时变透明（下面是首页网格时能看见它），缩回时完全透明
    val openAlpha = if (heroFrom != null) heroAnim.value.coerceIn(0f, 1f) else 1f
    val bgAlpha = (openAlpha * (1f - dragProgress * 1.8f) * (1f - closeAnim.value)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().onSizeChanged { screen = it }.background(Color.Black.copy(alpha = bgAlpha))) {
        HorizontalPager(
            state = pager, userScrollEnabled = !zoomed && drag.value == Offset.Zero, beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (closing) 0f else if (heroFrom != null) ((heroAnim.value - 0.75f) * 4f).coerceIn(0f, 1f) else 1f
                translationX = drag.value.x; translationY = drag.value.y
                scaleX = dragScale; scaleY = dragScale
            }.glassSource(backdrop),
            key = { photos[it].id },
        ) { page ->
            val p = photos[page]
            val current = pager.currentPage == page
            if (p.isVideo) VideoPage(p, current) { chrome = !chrome }
            else ZoomImage(
                p, onTap = { chrome = !chrome }, onZoom = { if (current) zoomed = it },
                onDismissDrag = { delta, end, vy ->
                    scope.launch {
                        if (!end) drag.snapTo(drag.value + delta)
                        else {
                            val far = drag.value.y > with(dens) { 96.dp.toPx() }
                            val flung = vy > with(dens) { 900.dp.toPx() } && drag.value.y > 0f
                            if ((far || flung) && vy > -with(dens) { 300.dp.toPx() }) requestClose()
                            else drag.animateTo(Offset.Zero, Motion.zoom())
                        }
                    }
                },
            )
        }

        // 打开：缩略图位置 → 全屏 Fit；关闭：当前位置（含下拉位移 / 缩放）→ 网格里的缩略图
        val flying: Triple<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect, Float>? = when {
            closing && closeFrom != null && closeTo != null -> Triple(closeFrom!!, closeTo!!, closeAnim.value)
            heroFrom != null && heroAnim.value < 1f -> photos.getOrNull(start)?.let { Triple(heroFrom, fitRect(it), heroAnim.value) }
            else -> null
        }
        if (flying != null) {
            val (from, to, t) = flying
            val l = from.left + (to.left - from.left) * t; val tp = from.top + (to.top - from.top) * t
            val w = from.width + (to.width - from.width) * t; val h = from.height + (to.height - from.height) * t
            val sp = if (closing) closePhoto else photos.getOrNull(start)
            // 网格那一端是方形裁切 + 小圆角；全屏那一端是 Fit（Crop 在照片本身比例的框里等于 Fit）
            val gridCorner = C.Corner.value
            val corner = if (closing) gridCorner * t else gridCorner * (1f - t)
            AsyncImage(
                ImageRequest.Builder(ctx).data(sp?.thumbM ?: heroThumb).placeholderMemoryCacheKey(if (closing) sp?.thumbS else heroThumb).build(), null,
                Modifier.offset { androidx.compose.ui.unit.IntOffset(l.toInt(), tp.toInt()) }
                    .size(with(dens) { w.coerceAtLeast(1f).toDp() }, with(dens) { h.coerceAtLeast(1f).toDp() })
                    .clip(RoundedCornerShape(corner.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        val p = photos.getOrNull(pager.currentPage)
        // 1.7.0：环境光晕跟着正在看的照片变色（回到网格时也是这张照片的颜色）
        AmbientFrom(p?.thumbS)
        val chromeOn = chrome && p != null && !closing && dragProgress < 0.02f && (heroFrom == null || heroAnim.value > 0.6f)
        // 1.7.0 v2：控件是浮在照片上的玻璃——返回 / 更多两颗玻璃圆钮、中间一枚日期胶囊、底部一条玻璃操作胶囊；
        // 出现时「材质到来」（缩放 + 淡入，Motion.materialize），不再有整条黑色渐变
        AnimatedVisibility(chromeOn, enter = fadeIn(Motion.enter()) + scaleIn(Motion.materialize(), initialScale = 0.88f) + slideInVertically(Motion.materialize()) { -it / 4 },
            exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.92f)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.s, end = Space.s, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(KI.back(), "返回", overPhoto = true, backdrop = backdrop) { requestClose() }
                Spacer(Modifier.width(Space.xs))
                if (p != null) GlassSurface(Modifier.weight(1f, fill = false).heightIn(min = 44.dp), shape = CircleShape, backdrop = backdrop, shadow = 0.dp, onDark = true) {
                    Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
                            val (t, w) = dayTitle(p.day)
                            Text("$t $w", color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            // 第 4 批：飞牛实况 type-1 的独立视频流映射未验证（FnExtra.motionVideo），这里标注「未验证」，不要宣称已验证。
                            // WebDAV 的 MOV 配对也用 liveType=1，但 isCloud=true，不受影响。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.time.drop(11).take(5), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall.merge(Type.num))
                                if (p.isLive) {
                                    Icon(KI.live(), null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = Space.s, end = Space.xxs).size(14.dp))
                                    Text("实况" + (if (!p.isCloud && p.liveType == 1) "（未验证）" else ""), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = C.P.display, fontFeatureSettings = Type.TNUM),
                            modifier = Modifier.padding(start = Space.m).semantics { contentDescription = "第 ${pager.currentPage + 1} 张，共 ${photos.size} 张" }
                                .clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                Spacer(Modifier.weight(0.001f))
                GlassIconButton(KI.moreH(), "更多", overPhoto = true, backdrop = backdrop) { more = true }
            }
        }
        AnimatedVisibility(chromeOn, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(Motion.enter()) + scaleIn(Motion.materialize(), initialScale = 0.88f) + slideInVertically(Motion.materialize()) { it / 3 },
            exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.92f)) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Space.l, end = Space.l, bottom = Space.m), contentAlignment = Alignment.Center) {
                // 底部操作 = 一条玻璃胶囊，里面一排图标（文字只给读屏）；收藏后变成实心心形 + 强调色
                GlassSurface(Modifier.widthIn(max = 420.dp).fillMaxWidth(), shape = CircleShape, backdrop = backdrop, shadow = 0.dp, onDark = true) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        if (p != null) {
                            if (!p.isCloud) key(favTick, p.id) {
                                BarAction(if (p.collected) KI.heartSolid() else KI.heart(), if (p.collected) "取消收藏" else "收藏", if (p.collected) C.Accent else Color.White) {
                                    val target = !p.collected
                                    p.collected = target; favTick++
                                    scope.launch { runCatching { Repo.setCollect(p.id, target) }.onFailure { p.collected = !target; favTick++ } }
                                }
                            }
                            BarAction(KI.share(), "分享", Color.White) { Ops.share(ctx, listOf(p)) }
                            BarAction(KI.download(), "下载", Color.White) { Ops.download(ctx, listOf(p)) }
                            BarAction(KI.album(), "加入相册", Color.White) { albumFor = p }
                            BarAction(KI.trash(), "删除", Color.White) { confirmDelete = true }
                        }
                    }
                }
            }
        }
        if (info && p != null) InfoSheet(p) { info = false }
        albumFor?.let { ap -> AddToAlbumFlow(ap) { albumFor = null } }
        if (more && p != null) MoreSheet(p, onInfo = { more = false; info = true }, onDismiss = { more = false }, onHidden = {
            more = false
            val i = pager.currentPage; if (i in photos.indices) photos.removeAt(i)
            if (photos.isEmpty()) Nav.pop()
        })
        if (confirmDelete && p != null) ConfirmDialog(
            if (p.isCloud && !com.hark.shiguang.DavRecycle.enabled) "从网盘删除这张照片？" else "移到回收站？", if (p.isCloud && !com.hark.shiguang.DavRecycle.enabled) "网盘里的文件会被删除。" else "30 天内可以在「最近删除」里恢复。", "删除",
            onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch {
                runCatching { Ops.delete(listOf(p)) }.onSuccess {
                    val i = pager.currentPage; photos.removeAt(i)
                    if (photos.isEmpty()) Nav.pop()
                    HomeState.dirty = true
                }.onFailure { failToast(ctx, it, "删除失败") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(p: Photo, onInfo: () -> Unit, onDismiss: () -> Unit, onHidden: () -> Unit) {
    val ctx = LocalContext.current
    // 1.7.7 v2「⋯」面板：上面一行大的操作块（看 / 做），下面单独一行「隐藏」；标题是这张照片的日期（v2 字阶）。
    // 面板本身是玻璃，块不再叠玻璃，只用一层淡底（DESIGN §1 玻璃不叠玻璃）。功能与 1.6.2 相同。
    AppBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface) {
        Column(Modifier.padding(horizontal = Space.page).padding(bottom = Space.xl).navigationBarsPadding()) {
            val (t, w) = dayTitle(p.day)
            Text(t, style = MaterialTheme.typography.headlineMedium, color = C.Text, maxLines = 1,
                modifier = Modifier.semantics { heading() })
            Text(listOf(w, p.time.drop(11).take(5), p.fileName).filter { it.isNotBlank() }.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall,
                color = C.Sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = Space.l))
            val edit = !p.isVideo; val live = p.isLive
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SheetTile(KI.info(), "详情", Modifier.weight(1f)) { onInfo() }
                // 全面审查 C1：「加入相册」只留底栏那一个
                if (!p.isCloud) SheetTile(KI.similar(), "找相似", Modifier.weight(1f)) { onDismiss(); Ops.openSimilar(p) }
                if (edit) SheetTile(KI.sliders(), "编辑", Modifier.weight(1f)) { onDismiss(); Nav.push(com.hark.shiguang.Route.X(ExtScreen.Edit(p))) }
                if (live) SheetTile(KI.live(), "存实况视频", Modifier.weight(1f)) { onDismiss(); Ops.downloadLiveVideo(ctx, p) }
            }
            val hid = remember(p.key) { com.hark.shiguang.Hidden.has(p) }
            SheetLine(KI.eye(), if (hid) "取消隐藏" else "隐藏", Modifier.padding(top = Space.s)) {
                com.hark.shiguang.Hidden.hide(listOf(p), !hid); toast(ctx, if (hid) "已取消隐藏" else "已隐藏"); onHidden()
            }
        }
    }
}

/** 面板里的淡底（玻璃面板里不再叠玻璃）：玻璃风格是正文色 6%，简洁风格是凹槽色。 */
private val sheetFill: Color @Composable get() = if (Glass.on) C.Text.copy(alpha = 0.06f) else C.Surface2

/** 「⋯」面板的大操作块：图标在上、文字在下，高 84，圆角 24，按下液体形变。 */
@Composable
private fun SheetTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.height(84.dp).glassPress(ix, 0.8f).clip(RoundedCornerShape(Radius.l)).background(sheetFill)
        .clickable(ix, androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = C.Text, maxLines = 1, modifier = Modifier.padding(top = Space.s))
    }
}

/** 「⋯」面板里的整行操作（隐藏）：高 56，圆角 24。 */
@Composable
private fun SheetLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = Space.row).glassPress(ix, 0.4f).clip(RoundedCornerShape(Radius.l)).background(sheetFill)
        .clickable(ix, androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.Text, modifier = Modifier.padding(start = Space.m))
    }
}

/** 加入相册 for one photo: NAS albums for 飞牛 photos, virtual albums for WebDAV photos. */
@Composable
fun AddToAlbumFlow(p: Photo, onDone: () -> Unit) {
    val ctx = LocalContext.current
    if (p.isCloud) {
        val lib = com.hark.shiguang.Dav.lib(p.source)
        DavAlbumPicker(lib, onDismiss = onDone) { a ->
            onDone()
            Ops.launch { runCatching { lib.addTo(a.id, listOf(p.cloudPath)) }.onSuccess { toast(ctx, "已加入「${a.name}」") }.onFailure { failToast(ctx, it, "失败") } }
        }
    } else AlbumPicker(onDismiss = onDone) { a ->
        onDone()
        Ops.launch { runCatching { Ops.addToAlbum(a, listOf(p)) }.onSuccess { toast(ctx, "已加入「${a.name}」") }.onFailure { failToast(ctx, it, "失败") } }
    }
}

/** 大图上的按钮规格（照片上的半透明黑圆钮）。 */

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val c by androidx.compose.animation.animateColorAsState(tint, Motion.state(), label = "barTint")
    // 1.7.0：玻璃胶囊里的图标按钮（48dp 触摸区），按下时一小团亮光 + 液体形变
    val inter = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    val glow by animateFloatAsState(if (pressed) 1f else 0f, Motion.state(), label = "barGlow")
    Box(Modifier.size(Space.touch).glassPress(inter, 1.6f).clip(CircleShape)
        .background(Color.White.copy(alpha = 0.16f * glow))
        .clickable(inter, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun ZoomImage(p: Photo, onTap: () -> Unit, onZoom: (Boolean) -> Unit, onDismissDrag: (delta: Offset, end: Boolean, velocityY: Float) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var hiRes by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(1f) }

    // 1.7.18（ViewerZoom）：照片实际显示的宽高比取自已解码的中图（已按 EXIF 旋转），没加载出来前按整个框算（以前的行为）
    // 手势协程只在换照片时重启，所以这里用函数现读状态，不用组合时算好的值（否则手势里拿到的是第一次组合时的旧值）
    var shownRatio by remember(p.id) { mutableFloatStateOf(0f) }
    fun fitSize() = ViewerZoom.fit(shownRatio, size.width.toFloat(), size.height.toFloat())
    fun maxScale(): Float = if (shownRatio > 0f) fitSize().let { ViewerZoom.maxScale(p.width, p.height, it.first, it.second) } else ViewerZoom.LEGACY_MAX

    fun clamp(o: Offset, s: Float): Offset {
        // 只能拖到照片边缘，不再拖进黑边
        val f = fitSize()
        val (mx, my) = ViewerZoom.panLimit(s, size.width.toFloat(), size.height.toFloat(), f.first, f.second)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(p.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pt ->
                        scope.launch {
                            val from = scale; val to = if (scale > 1.05f) 1f else ViewerZoom.doubleTap(maxScale())
                            val focus = Offset(pt.x - size.width / 2f, pt.y - size.height / 2f)
                            anim.snapTo(0f)
                            anim.animateTo(1f, spring(stiffness = 500f)) {
                                scale = from + (to - from) * value
                                offset = if (to == 1f) offset * (1 - value) else clamp(-focus * (scale - 1), scale)
                            }
                            onZoom(scale > 1.05f)
                        }
                    },
                    onLongPress = { if (p.isLive) showLive = true },
                    onPress = { tryAwaitRelease(); showLive = false },
                )
            }
            .pointerInput(p.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    // 1.6.2：下拉关闭带速度（快速一甩也能关），拖动时照片跟手走 x / y 两个方向
                    val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    do {
                        val ev = awaitPointerEvent()
                        val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                        val multi = ev.changes.size > 1
                        ev.changes.firstOrNull()?.let { vt.addPosition(it.uptimeMillis, it.position) }
                        if (multi || scale > 1.01f) {
                            if (dismissing) { dismissing = false; onDismissDrag(Offset.Zero, true, 0f) }
                            scale = (scale * zoom).coerceIn(1f, maxScale())
                            offset = clamp(offset + pan, scale)
                            onZoom(scale > 1.01f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (dismissing || (pan.y > 0f && abs(pan.y) > abs(pan.x) * 1.6f && abs(pan.y) > 2f)) {
                            dismissing = true
                            onDismissDrag(pan, false, 0f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (dismissing) onDismissDrag(Offset.Zero, true, vt.calculateVelocity().y)
                    if (scale < 1.02f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        AsyncImage(ImageRequest.Builder(ctx).data(p.thumbM).placeholderMemoryCacheKey(p.thumbS).build(), null, layer, contentScale = ContentScale.Fit,
            onSuccess = { st -> val d = st.result.drawable; if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) shownRatio = d.intrinsicWidth.toFloat() / d.intrinsicHeight })
        // Fetch the original once the user lingers or zooms in.
        LaunchedEffect(p.id) { kotlinx.coroutines.delay(600); hiRes = true }
        // RAW / EXR / DPX / TIFF …: the phone cannot decode the original, stay on the NAS preview (PreviewOnly).
        if (hiRes && !com.hark.shiguang.PreviewOnly.of(p.fileName)) {
            // H12: decode for the screen (longer side), not 4096 px — the pager keeps up to 3 pages alive. Zooming in
            // past 1.5× switches to 2× the screen (≤ 4096) and the screen-sized copy stays as the placeholder.
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(cfg.screenWidthDp, cfg.screenHeightDp).dp.roundToPx() }.coerceIn(720, 4096)
            val zoomedIn = scale > 1.5f
            val target = if (zoomedIn) (screenPx * 2).coerceAtMost(4096) else screenPx
            val screenKey = remember(p.id) { mutableStateOf<coil.memory.MemoryCache.Key?>(null) }
            AsyncImage(ImageRequest.Builder(ctx).data(p.original).crossfade(250).size(target)
                .apply { if (zoomedIn) placeholderMemoryCacheKey(screenKey.value) }.build(), null, layer, contentScale = ContentScale.Fit,
                onSuccess = { if (!zoomedIn) screenKey.value = it.result.memoryCacheKey })
        }
        if (showLive) LiveLayer(p)
    }
}

/** 1.0.1: long-press playback of any live photo; the motion url is resolved (and an embedded MP4 extracted) on demand. */
@Composable
private fun LiveLayer(p: Photo) {
    val url by produceState<String?>(null, p.key) { value = com.hark.shiguang.LiveMotion.playable(p) ?: "" }
    when (val u = url) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp)) }
        "" -> {}
        else -> VideoSurface(u, play = true, loop = true, controls = false, Modifier.fillMaxSize())
    }
}

@Composable
private fun VideoPage(p: Photo, current: Boolean, onTap: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(current) { if (!current) started = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (started && current && p.video != null) VideoSurface(p.video, play = true, loop = false, controls = true, Modifier.fillMaxSize())
        else {
            AsyncImage(p.thumbM, null, Modifier.fillMaxSize().clickable(onClick = onTap), contentScale = ContentScale.Fit)
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(38.dp)).background(Color(0x66000000)).clickable { started = true }, contentAlignment = Alignment.Center) {
                Icon(KI.playSolid(), null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoSurface(url: String, play: Boolean, loop: Boolean, controls: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val isNas = FnClient.baseUrl.isNotEmpty() && url.startsWith(FnClient.baseUrl)
    val isLocal = url.startsWith("file:")
    // WebDAV (123pan etc.) answers every request with a 302 to a signed CDN url: resolve it once,
    // so ExoPlayer's range requests go straight to the CDN.
    val real by produceState<String?>(if (isNas || isLocal) url else null, url) {
        if (!isNas && !isLocal) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.hark.shiguang.DavThumb.finalUrl(url) }
    }
    val target = real
    if (target == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp)) }
        return
    }
    val player = remember(target) {
        val ds = OkHttpDataSource.Factory(FnClient.http).setDefaultRequestProperties(
            if (isNas) {
                val path = url.removePrefix(FnClient.baseUrl).substringBefore("?")
                mapOf("accesstoken" to FnClient.token, "authx" to AuthX.header(path, ""))
            } else if (target != url) emptyMap() else com.hark.shiguang.ImageAuth.headersFor(url) ?: emptyMap()
        )
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 800, 1_500).build()
        // B34 / 全面审查 C5：与影视播放器共用 FFmpeg 优先的解码器配置（DTS / TrueHD / AC3 在每台手机都能放）
        val renderers = com.hark.shiguang.ffmpegRenderersFactory(ctx)
        // DefaultDataSource: file:// (extracted Motion Photo MP4) locally, everything else through OkHttp
        // 全面审查 B1：普通视频申请音频焦点（来电 / 别的 App 放声音时自动暂停），拔耳机暂停
        ExoPlayer.Builder(ctx, renderers).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, ds)))
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* 实况照片循环不抢焦点 */ controls)
            .setHandleAudioBecomingNoisy(true).build().apply {
            setMediaItem(MediaItem.fromUri(target)); repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare(); playWhenReady = play
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // 全面审查 B1：照片里的视频开始播时先停音乐；切到后台（ON_STOP）暂停，不再和音乐叠在一起响
    LaunchedEffect(player) { if (play && controls) com.hark.shiguang.MusicState.pause() }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = {
        PlayerView(it).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = controls; this.player = player; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(p: Photo, onDismiss: () -> Unit) {
    // 1.7.7 v2 信息面板：26sp 粗体日期标题；相机卡（机型 + 参数胶囊）；尺寸 / 像素 / 大小三格数字（Allo 等宽）；地点、路径各一行带标签。
    AppBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface) {
        Column(Modifier.padding(horizontal = Space.page + 4.dp).padding(bottom = 36.dp).verticalScroll(rememberScrollState())) {
            val (t, w) = dayTitle(p.day)
            Text(t, style = MaterialTheme.typography.headlineMedium, color = C.Text, modifier = Modifier.semantics { heading() })
            Text(listOf(w, p.time.drop(11).take(5)).filter { it.isNotBlank() }.joinToString("  "), style = MaterialTheme.typography.bodyMedium, color = C.Sub,
                modifier = Modifier.padding(top = 2.dp))
            Text(p.fileName, style = MaterialTheme.typography.bodySmall, color = C.Sub, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(Space.l))
            val camera = CameraName.of(p.make, p.model)   // 1.7.23：「Xiaomi Xiaomi 13」→「Xiaomi 13」
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.l)).background(sheetFill).padding(Space.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(KI.camera(), null, tint = C.Accent, modifier = Modifier.size(20.dp))
                    }
                    Text(camera.ifEmpty { "未知设备" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = C.Text,
                        modifier = Modifier.padding(start = Space.m))
                }
                val specs = listOfNotNull(
                    p.focal.takeIf { it.isNotBlank() }?.let { if (it.endsWith("mm")) it else "${it}mm" },
                    p.fNumber.takeIf { it.isNotBlank() }?.let { if (it.startsWith("f", true)) it else "ƒ/$it" },
                    p.exposure.takeIf { it.isNotBlank() }?.let { if (it.endsWith("s")) it else "${it}s" },
                    p.iso.takeIf { it.isNotBlank() }?.let { "ISO $it" },
                )
                if (specs.isNotEmpty()) {
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        specs.forEach { s -> Box(Modifier.clip(CircleShape).background(C.Text.copy(alpha = 0.07f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(s, style = Type.badge.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = C.Text) } }
                    }
                }
                Spacer(Modifier.height(Space.l))
                Row(Modifier.fillMaxWidth()) {
                    InfoStat("尺寸", if (p.width > 0) "${p.width}×${p.height}" else "—", Modifier.weight(1.3f))
                    InfoStat("像素", if (p.width > 0) "%.1f MP".format(p.width * p.height / 1_000_000f) else "—", Modifier.weight(1f))
                    InfoStat("大小", fmtBytes(p.size), Modifier.weight(1f))
                }
            }
            if (p.geo.isNotBlank()) InfoRow(KI.pin(), "地点", p.geo)
            if (p.path.isNotBlank()) InfoRow(KI.folder(), "位置", p.path)
        }
    }
}

/** 信息面板里的一格数字：小标签在上，Allo 等宽数字在下。读屏合并成「尺寸 4032×3024」。 */
@Composable
private fun InfoStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = C.Sub)
        Text(value, style = Type.badge.copy(fontSize = 15.sp, lineHeight = 20.sp), color = C.Text, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = Space.l).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(sheetFill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Text, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(start = Space.m)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = C.Sub)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = C.Text, modifier = Modifier.padding(top = 1.dp))
        }
    }
}



