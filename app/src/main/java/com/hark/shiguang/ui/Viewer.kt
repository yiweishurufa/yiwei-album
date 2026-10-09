package com.hark.shiguang.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
    // hero: grow from the tapped thumbnail
    val heroFrom = remember { Hero.from }
    val heroThumb = remember { Hero.fromThumb }
    val heroAnim = remember { Animatable(if (heroFrom != null) 0f else 1f) }
    LaunchedEffect(Unit) { Hero.from = null; heroAnim.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
    LaunchedEffect(pager.currentPage) { photos.getOrNull(pager.currentPage)?.let { ViewerReturn.lastKey = it.key } }
    DisposableEffect(Unit) { onDispose { ViewerReturn.tick++ } }
    if (photos.isEmpty()) { LaunchedEffect(Unit) { Nav.pop() }; return }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var albumFor by remember { mutableStateOf<Photo?>(null) }
    var favTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(dragY.value) / 900f).coerceIn(0.3f, 1f)))
    ) {
        HorizontalPager(
            state = pager, userScrollEnabled = !zoomed, beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (heroFrom != null) ((heroAnim.value - 0.75f) * 4f).coerceIn(0f, 1f) else 1f
                translationY = dragY.value
                val s = 1f - (abs(dragY.value) / 3000f).coerceAtMost(0.15f); scaleX = s; scaleY = s
            },
            key = { photos[it].id },
        ) { page ->
            val p = photos[page]
            val current = pager.currentPage == page
            if (p.isVideo) VideoPage(p, current) { chrome = !chrome }
            else ZoomImage(
                p, onTap = { chrome = !chrome }, onZoom = { if (current) zoomed = it },
                onDismissDrag = { dy, end ->
                    scope.launch {
                        if (!end) dragY.snapTo(dragY.value + dy)
                        else if (abs(dragY.value) > 220f) Nav.pop() else dragY.animateTo(0f, spring())
                    }
                },
            )
        }

        if (heroFrom != null && heroAnim.value < 1f) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val dens = androidx.compose.ui.platform.LocalDensity.current
                val W = with(dens) { maxWidth.toPx() }; val H = with(dens) { maxHeight.toPx() }
                val sp = photos.getOrNull(start)
                val ratio = sp?.ratio?.takeIf { it > 0f } ?: 1f
                val fw = if (W / H > ratio) H * ratio else W; val fh = fw / ratio
                val t = heroAnim.value
                val w = heroFrom.width + (fw - heroFrom.width) * t; val h = heroFrom.height + (fh - heroFrom.height) * t
                val x = heroFrom.left + ((W - fw) / 2f - heroFrom.left) * t; val y = heroFrom.top + ((H - fh) / 2f - heroFrom.top) * t
                AsyncImage(
                    ImageRequest.Builder(ctx).data(sp?.thumbM ?: heroThumb).placeholderMemoryCacheKey(heroThumb).build(), null,
                    Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }.size(with(dens) { w.toDp() }, with(dens) { h.toDp() }).clip(RoundedCornerShape(((1 - t) * 8).dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        val p = photos.getOrNull(pager.currentPage)
        AnimatedVisibility(chrome && p != null, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Rounded.ArrowBackIosNew, bg = Color(0x40000000), tint = Color.White) { Nav.pop() }
                    Spacer(Modifier.width(12.dp))
                    if (p != null) Column(Modifier.weight(1f)) {
                        val (t, w) = dayTitle(p.day)
                        Text("$t $w", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(p.time.drop(11).take(5) + (if (p.isLive) "  ·  实况" else ""), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x40000000)).padding(horizontal = 9.dp, vertical = 4.dp))
                    Spacer(Modifier.width(10.dp))
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Rounded.MoreHoriz, bg = Color(0x40000000), tint = Color.White) { more = true }
                }
            }
        }
        AnimatedVisibility(chrome && p != null, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
                Row(
                    Modifier.navigationBarsPadding().padding(bottom = 14.dp, top = 30.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (p != null) {
                        if (!p.isCloud) key(favTick, p.id) {
                            BarAction(if (p.collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (p.collected) "已收藏" else "收藏", if (p.collected) C.Rose else Color.White) {
                                val target = !p.collected
                                p.collected = target; favTick++
                                scope.launch { runCatching { Repo.setCollect(p.id, target) }.onFailure { p.collected = !target; favTick++ } }
                            }
                        }
                        BarAction(Icons.Rounded.Share, "分享", Color.White) { Ops.share(ctx, listOf(p)) }
                        BarAction(Icons.Rounded.Download, "下载", Color.White) { Ops.download(ctx, listOf(p)) }
                        BarAction(Icons.Rounded.PhotoAlbum, "加入相册", Color.White) { albumFor = p }
                        BarAction(Icons.Rounded.DeleteOutline, "删除", Color.White) { confirmDelete = true }
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
            if (p.isCloud) "从网盘删除这张照片？" else "移到回收站？", if (p.isCloud) "网盘里的文件会被删除。" else "30 天内可以在回收站恢复。", "删除",
            onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch {
                runCatching { Ops.delete(listOf(p)) }.onSuccess {
                    val i = pager.currentPage; photos.removeAt(i)
                    if (photos.isEmpty()) Nav.pop()
                    HomeState.dirty = true
                }.onFailure { toast(ctx, it.message ?: "删除失败") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(p: Photo, onInfo: () -> Unit, onDismiss: () -> Unit, onHidden: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var albumPick by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Column(Modifier.padding(bottom = 30.dp)) {
            NavRow(Icons.Rounded.Info, "详情", "拍摄参数、位置、文件") { onInfo() }
            NavRow(Icons.Rounded.PhotoAlbum, "加入相册", "加入已有相册，或新建一个") { albumPick = true }
            if (!p.isCloud) NavRow(Icons.Rounded.ImageSearch, "找相似照片", "") { onDismiss(); Ops.openSimilar(p) }
            if (p.isLive) NavRow(Icons.Rounded.MotionPhotosOn, "保存实况视频部分", "") { onDismiss(); Ops.downloadLiveVideo(ctx, p) }
            if (!p.isVideo) NavRow(Icons.Rounded.Tune, "编辑", "裁剪、旋转、亮度，另存为新照片") { onDismiss(); Nav.push(com.hark.shiguang.Route.X(ExtScreen.Edit(p))) }
            val hid = remember(p.key) { com.hark.shiguang.Hidden.has(p) }
            NavRow(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "取消隐藏" else "隐藏", if (hid) "回到时间线和相册" else "移到隐藏相册，需验证指纹才能查看") {
                com.hark.shiguang.Hidden.hide(listOf(p), !hid); toast(ctx, if (hid) "已取消隐藏" else "已隐藏"); onHidden()
            }
        }
    }
    if (albumPick) AddToAlbumFlow(p) { albumPick = false; onDismiss() }
}

/** 加入相册 for one photo: NAS albums for 飞牛 photos, virtual albums for WebDAV photos. */
@Composable
fun AddToAlbumFlow(p: Photo, onDone: () -> Unit) {
    val ctx = LocalContext.current
    if (p.isCloud) {
        val lib = com.hark.shiguang.Dav.lib(p.source)
        DavAlbumPicker(lib, onDismiss = onDone) { a ->
            onDone()
            Ops.launch { runCatching { lib.addTo(a.id, listOf(p.cloudPath)) }.onSuccess { toast(ctx, "已加入「${a.name}」") }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    } else AlbumPicker(onDismiss = onDone) { a ->
        onDone()
        Ops.launch { runCatching { Ops.addToAlbum(a, listOf(p)) }.onSuccess { toast(ctx, "已加入「${a.name}」") }.onFailure { toast(ctx, it.message ?: "失败") } }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ZoomImage(p: Photo, onTap: () -> Unit, onZoom: (Boolean) -> Unit, onDismissDrag: (Float, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var hiRes by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(1f) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = (size.width * (s - 1)) / 2f; val my = (size.height * (s - 1)) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(p.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pt ->
                        scope.launch {
                            val from = scale; val to = if (scale > 1.05f) 1f else 2.6f
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
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    do {
                        val ev = awaitPointerEvent()
                        val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                        val multi = ev.changes.size > 1
                        if (multi || scale > 1.01f) {
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = clamp(offset + pan, scale)
                            onZoom(scale > 1.01f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (dismissing || (abs(pan.y) > abs(pan.x) * 1.6f && abs(pan.y) > 2f)) {
                            dismissing = true
                            onDismissDrag(pan.y, false)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (dismissing) onDismissDrag(0f, true)
                    if (scale < 1.02f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        AsyncImage(ImageRequest.Builder(ctx).data(p.thumbM).placeholderMemoryCacheKey(p.thumbS).build(), null, layer, contentScale = ContentScale.Fit)
        // Fetch the original once the user lingers or zooms in.
        LaunchedEffect(p.id) { kotlinx.coroutines.delay(600); hiRes = true }
        if (hiRes && !p.fileName.endsWith(".dng", true) && !p.fileName.endsWith(".arw", true) && !p.fileName.endsWith(".cr2", true) && !p.fileName.endsWith(".nef", true)) {
            AsyncImage(ImageRequest.Builder(ctx).data(p.original).crossfade(250).size(4096).build(), null, layer, contentScale = ContentScale.Fit)
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
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
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
        // DefaultDataSource: file:// (extracted Motion Photo MP4) locally, everything else through OkHttp
        ExoPlayer.Builder(ctx).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, ds))).build().apply {
            setMediaItem(MediaItem.fromUri(target)); repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare(); playWhenReady = play
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp).verticalScroll(rememberScrollState())) {
            val (t, w) = dayTitle(p.day)
            Text("$t $w  ${p.time.drop(11).take(5)}", style = MaterialTheme.typography.headlineSmall)
            Text(p.fileName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            val camera = listOf(p.make, p.model).filter { it.isNotBlank() }.joinToString(" ").trim()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface2).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = C.Gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(camera.ifEmpty { "未知设备" }, style = MaterialTheme.typography.titleSmall)
                }
                val specs = listOfNotNull(
                    p.focal.takeIf { it.isNotBlank() }?.let { if (it.endsWith("mm")) it else "${it}mm" },
                    p.fNumber.takeIf { it.isNotBlank() }?.let { if (it.startsWith("f", true)) it else "ƒ/$it" },
                    p.exposure.takeIf { it.isNotBlank() }?.let { if (it.endsWith("s")) it else "${it}s" },
                    p.iso.takeIf { it.isNotBlank() }?.let { "ISO $it" },
                )
                if (specs.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        specs.forEach { s -> Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 6.dp)) { Text(s, fontSize = 12.sp, color = C.Text) } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val mp = if (p.width > 0) "%.1f MP".format(p.width * p.height / 1_000_000f) else ""
                Text(listOf(if (p.width > 0) "${p.width} × ${p.height}" else "", mp, fmtSize(p.size)).filter { it.isNotEmpty() }.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            }
            if (p.geo.isNotBlank()) InfoRow(Icons.Rounded.Place, p.geo)
            if (p.path.isNotBlank()) InfoRow(Icons.Rounded.Folder, p.path)
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = C.Sub)
    }
}

private fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1 shl 20 -> "%.0f KB".format(b / 1024f)
    b < 1 shl 30 -> "%.1f MB".format(b / 1048576f)
    else -> "%.2f GB".format(b / 1073741824f)
}

