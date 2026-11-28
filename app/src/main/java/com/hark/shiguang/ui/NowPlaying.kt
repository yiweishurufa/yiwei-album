@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hark.shiguang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.isActive
import androidx.media3.common.Player
import com.hark.shiguang.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 正在播放页（1.7.34 起从 MusicTab.kt 拆出）。
 * 1.7.34 重排（MEDIA-RESEARCH 批 1）：
 * - 顶栏只留 收起 /「来自 专辑」（点开专辑）/ ⋯（查看专辑、查看歌手、重新匹配、手动搜索、文件信息）；
 * - 歌名一行（过长时跑马灯，移除动画时省略号）+ 右侧 ♡；歌手可点进歌手页；无损 / DSD 小标；右侧显示剩余时间；
 * - 控制行只有 上一首 / 播放 / 下一首；下面一排工具：播放模式 · 歌词 · 定时 · 列表（图标 + 小字）；
 * - 封面左右滑切歌，封面上往上滑打开歌词；整页往下拉跟手收起（过四分之一屏高或甩一下）。
 * 1.7.36（MEDIA-RESEARCH 批 3）：
 * - 封面 / 歌词 / 列表三态在同一页里切换：同一个封面在大封面框与左上 56dp 小框之间形变（Modifier.layout 插值，Motion.zoom），
 *   歌名移到小封面右边；列表态代替原来的底部面板（随机 / 循环 / 单曲胶囊、左滑移除、拖动排序）；返回键先回封面态。
 * - 背景换成封面取色的「舞台」（[NowPlayingStage]）：3 个色块，播放中且页面在前台时极慢漂移，暂停即停；不再全屏模糊封面。
 * - 从迷你播放条打开时是容器展开（[MiniHero]）：页面从胶囊大小揭开到全屏、封面从小封面长到大封面，控件过半后淡入；收起时反过来缩回迷你条。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicNowPlayingScreen() {
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    // 三态：0 封面 / 1 歌词 / 2 列表。歌词态沿用 music.showLyrics 记住，列表态不记
    var mode by remember { mutableIntStateOf(if (Store.getStr("music.showLyrics") == "1") 1 else 0) }
    fun setMode(m: Int) { mode = m; Store.putStr("music.showLyrics", if (m == 1) "1" else "") }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { pos = MusicState.position; delay(200) } }
    LaunchedEffect(MusicState.active) { if (!MusicState.active) { delay(400); if (!MusicState.active) Nav.pop() } }
    val dur = MusicState.duration.takeIf { it > 0 } ?: (t?.durationMs ?: 0L)
    val sourceKey = MusicState.mediaId?.substringBefore('|')
    val lib = remember(sourceKey) { sourceKey?.let { Music.lib(it) } }
    val lyrics by produceState<List<Lrc.Line>?>(null, t?.id) {
        value = null
        val tr = t ?: return@produceState
        val s = lib?.lyrics(tr)
        value = s?.let { val p = Lrc.parse(it); if (p.isNotEmpty()) p else Lrc.plain(it) } ?: emptyList()
    }

    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    val skip = rememberSkipSwipe()

    // ---- 容器展开 / 收起（迷你条 ⇄ 全屏）
    var hero by remember { mutableStateOf(MiniHero.pending) }
    // 读出后清掉；closed 也复位（别的入口打开时，上次缩回留下的标记不能影响这次出栈的转场）
    LaunchedEffect(Unit) { MiniHero.pending = null; MiniHero.closed = false }
    val expand = remember { Animatable(if (hero != null) 0f else 1f) }
    LaunchedEffect(Unit) { if (expand.value < 1f) expand.animateTo(1f, Motion.expand()) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        // 只有直接压在首页上（迷你条就在下面）才缩回迷你条；否则走普通的下滑退出
        val to = if (Motion.reduced || Nav.stack.size != 2 || Nav.stack.firstOrNull() != Route.Home) null else MiniHero.target()
        if (to == null) { Nav.pop(); return }
        closing = true
        hero = to
        scope.launch { expand.animateTo(0f, Motion.collapse()); MiniHero.closed = true; Nav.pop() }
    }
    BackHandler { when { closing -> {}; mode != 0 -> setMode(0); else -> close() } }
    fun contentAlpha(): Float = ExpandMath.contentAlpha(expand.value)
    val artCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }

    // ---- 下拉收起：拖动中跟手，松手不够远就弹回
    val pullAnim = remember { Animatable(0f) }
    var pullRaw by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }
    var pageH by remember { mutableFloatStateOf(0f) }
    var pageW by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }
    fun pull(): Float = if (pulling) pullRaw.coerceAtLeast(0f) else pullAnim.value
    fun pullDrag(d: Float) { if (dismissed || closing || expand.isRunning) return; if (!pulling) { pulling = true; pullRaw = pullAnim.value }; pullRaw += d }
    fun pullEnd(vy: Float) {
        if (!pulling) return
        val now = pullRaw.coerceAtLeast(0f)
        pulling = false; pullRaw = 0f
        scope.launch {
            pullAnim.snapTo(now)
            if (SwipeMath.shouldDismiss(now, vy / density, pageH)) { dismissed = true; Nav.pop() }
            else pullAnim.animateTo(0f, Motion.settle())
        }
    }
    val upPx = SwipeMath.UP_DP * density

    // ---- 封面态 ⇄ 歌词 / 列表态的形变进度（0 大封面，1 左上小封面）
    val morph = remember { Animatable(if (mode == 0) 0f else 1f) }
    LaunchedEffect(mode) { val target = if (mode == 0) 0f else 1f; if (Motion.reduced) morph.snapTo(target) else morph.animateTo(target, Motion.zoom()) }

    Box(Modifier.fillMaxSize().onSizeChanged { pageH = it.height.toFloat(); pageW = it.width.toFloat() }
        // 拉下来时后面压一层渐淡的暗色（直接压在首页上时能看到首页）
        .drawBehind { val p = SwipeMath.dismissProgress(pull(), pageH); if (p > 0f) drawRect(Color.Black.copy(alpha = unitAlpha(0.45f * (1f - p)))) }
        .mediaDrag(Unit, horizontal = false, vertical = true, onVertical = { pullDrag(it) }, onVerticalEnd = { _, v -> pullEnd(v) })) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        val e = expand.value
        val h = hero
        if (e < 1f && h != null) {
            // 从迷你条胶囊揭开到全屏：裁切矩形与圆角插值
            val c = h.capsule
            val k = e.coerceIn(0f, 1f)
            val l = ExpandMath.lerp(c.left, 0f, k); val tp = ExpandMath.lerp(c.top, 0f, k)
            val r = ExpandMath.lerp(c.right, size.width, k); val b = ExpandMath.lerp(c.bottom, size.height, k)
            shape = RectShape(androidx.compose.ui.geometry.Rect(l, tp, r, b), ExpandMath.lerp(Radius.l.toPx(), 0f, k))
            clip = true
        } else {
            val y = pull()
            translationY = y
            val p = SwipeMath.dismissProgress(y, pageH)
            if (p > 0f) {
                if (!Motion.reduced) { val s = 1f - 0.08f * p; scaleX = s; scaleY = s }
                shape = RoundedCornerShape((32 * p).dp); clip = true
            }
        }
    }.background(C.Bg)) {
        NowPlayingStage(t?.artPath, MusicState.isPlaying)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.graphicsLayer { alpha = contentAlpha() }) { NowPlayingTopBar(t, sourceKey, lib, onClose = { close() }) { manual = true } }
            // 中区：封面 / 歌词 / 列表
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
                .mediaDrag(mode, horizontal = mode == 0, vertical = true,
                    onHorizontal = { skip.drag(it) },
                    onHorizontalEnd = { _, v -> scope.launch { skip.release(v / density) { Haptics.tick(view) } } },
                    onVertical = { pullDrag(it) },
                    onVerticalEnd = { total, v ->
                        if (mode == 0 && total < -upPx) { pulling = false; pullRaw = 0f; scope.launch { pullAnim.snapTo(0f) }; setMode(1) }
                        else pullEnd(v)
                    })
                .then(if (mode == 2) Modifier else Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null,
                    onClickLabel = if (mode == 1) "显示封面" else "显示歌词") { setMode(if (mode == 1) 0 else 1) })
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction("上一首") { MusicState.prevTrack(); true }.takeIf { MusicState.canSkip(-1) },
                        CustomAccessibilityAction("下一首") { MusicState.next(); true }.takeIf { MusicState.canSkip(1) },
                    )
                }) {
                val d = LocalDensity.current
                val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
                val lineSpace = with(d) { 56.dp.toPx() }
                val bigSide = (minOf(wPx - with(d) { 56.dp.toPx() }, hPx - lineSpace)).coerceAtLeast(0f)
                val bigL = (wPx - bigSide) / 2f
                val bigT = ((hPx - bigSide - lineSpace) / 2f).coerceAtLeast(0f)
                val smallSide = with(d) { 56.dp.toPx() }; val smallL = with(d) { 26.dp.toPx() }; val smallT = with(d) { 8.dp.toPx() }

                // 歌词 / 列表（在小封面那一行下面）
                if (mode != 0) Box(Modifier.fillMaxSize().padding(top = 72.dp).graphicsLayer { alpha = contentAlpha() * morph.value }) {
                    androidx.compose.animation.Crossfade(mode, animationSpec = Motion.enter(), label = "npMode") { m ->
                        if (m == 2) QueuePane()
                        else LyricsView(lyrics, pos, MusicState.isPlaying, onSeek = { MusicState.seek(it) }, onManual = if (t != null && sourceKey != null) ({ manual = true }) else null)
                    }
                }
                // 小封面右边的歌名 / 歌手 / ♡（歌词、列表态）
                if (mode != 0 || morph.isRunning) Row(Modifier.fillMaxWidth().padding(start = 26.dp + 56.dp + 14.dp, end = Space.s, top = 8.dp).height(56.dp)
                    .graphicsLayer { alpha = contentAlpha() * morph.value }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.displayTitle ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t?.displayArtist ?: "", color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FavButton(t, lib, 24.dp)
                }
                // 封面态：大封面下面的一句歌词（没有时间轴 / 没有歌词时不显示）
                if (mode == 0) {
                    val cur = lyrics?.let { l -> Lrc.current(l, pos) }?.let { lyrics?.getOrNull(it)?.text }
                    if (lyrics?.firstOrNull()?.let { it.ms >= 0 } == true)
                    // 1.7.32：旧的一句同时往上退出；裁在这一行的高度里
                    androidx.compose.animation.AnimatedContent(cur ?: "", Modifier.fillMaxWidth().offset { IntOffset(0, (bigT + bigSide).toInt()) }
                        .graphicsLayer { alpha = contentAlpha() * (1f - morph.value) }.clipToBounds(), transitionSpec = {
                        if (Motion.reduced) fadeIn(Motion.state()) togetherWith fadeOut(Motion.exit())
                        else (fadeIn(Motion.enter()) + androidx.compose.animation.slideInVertically(Motion.move()) { it / 2 }) togetherWith
                            (fadeOut(Motion.exit()) + androidx.compose.animation.slideOutVertically(Motion.move()) { -it / 2 })
                    }, label = "curLine") { line ->
                        Text(line, color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 18.dp, start = Space.xl, end = Space.xl).fillMaxWidth())
                    }
                }
                // 封面：同一个元素在大框与小框之间插值（不跨组合树，不需要共享元素 API）
                val scale by animateFloatAsState(if (MusicState.isPlaying || mode != 0) 1f else 0.9f, Motion.breathe(), label = "art")
                Box(Modifier.layout { m, c ->
                    val k = morph.value
                    val side = ExpandMath.lerp(bigSide, smallSide, k).toInt().coerceAtLeast(1)
                    val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
                    layout(side, side) { p.place(0, 0) }
                }.offset { val k = morph.value; IntOffset(ExpandMath.lerp(bigL, smallL, k).toInt(), ExpandMath.lerp(bigT, smallT, k).toInt()) }
                    .onGloballyPositioned { artCoords[0] = it }
                    .graphicsLayer {
                        // 容器展开：从迷你条小封面的位置长出来
                        val e = expand.value; val h = hero; val b = artCoords[0]?.takeIf { it.isAttached }?.boundsInRoot()
                        if (e < 1f && h != null && b != null) {
                            val (tx, ty, s) = ExpandMath.art(h.art.left, h.art.top, h.art.width, b.left, b.top, b.width, e)
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = tx; translationY = ty; scaleX = s; scaleY = s
                        }
                    }
                    .then(if (mode != 0) Modifier.clickable(onClickLabel = "显示大封面", role = Role.Button) { setMode(0) } else Modifier)
                    .onSizeChanged { if (mode == 0) skip.width = it.width.toFloat() }
                    .graphicsLayer {
                        val x = if (mode == 0) skip.offset else 0f
                        translationX = x
                        val f = if (skip.width > 0f) (abs(x) / skip.width).coerceIn(0f, 1f) else 0f
                        alpha = 1f - f * 0.6f
                        val s = scale * (1f - 0.06f * f); scaleX = s; scaleY = s
                    }) {
                    MusicArt(t?.artPath, Modifier.fillMaxSize(), corner = if (mode == 0) Radius.l else Radius.m, lifted = true)
                }
            }
            Column(Modifier.padding(horizontal = 26.dp).graphicsLayer { alpha = contentAlpha() }) {
                androidx.compose.animation.AnimatedVisibility(mode == 0, enter = if (Motion.reduced) fadeIn(Motion.state()) else androidx.compose.animation.expandVertically(Motion.move()) + fadeIn(Motion.enter()),
                    exit = if (Motion.reduced) fadeOut(Motion.exit()) else androidx.compose.animation.shrinkVertically(Motion.move()) + fadeOut(Motion.exit())) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 歌名过长时横向跑马灯（播两遍停下）；移除动画时用省略号
                            Text(t?.displayTitle ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = if (Motion.reduced) TextOverflow.Ellipsis else TextOverflow.Clip,
                                modifier = Modifier.weight(1f).then(if (Motion.reduced) Modifier else Modifier.basicMarquee(iterations = 2, initialDelayMillis = 1500)))
                            FavButton(t, lib, 26.dp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val artist = t?.artists?.firstOrNull()?.takeIf { it.isNotBlank() && t.displayArtist != "未知歌手" }
                            Text(t?.displayArtist ?: "", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(Radius.xs))
                                    .then(if (artist != null) Modifier.clickable(onClickLabel = "查看歌手", role = Role.Button) { Nav.push(Route.MusicList("artist", artist, artist)) } else Modifier))
                            t?.let { qualityOf(it) }?.let { b -> Text(b, color = C.Sub, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, modifier = Modifier.padding(start = Space.s).clip(RoundedCornerShape(4.dp)).background(C.Chip).padding(horizontal = 5.dp, vertical = 1.dp)) }
                        }
                    }
                }
                val shownPos = drag?.let { (it * dur).toLong() } ?: pos
                ScrubBar(drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, enabled = dur > 0,
                    onChange = { drag = it }, onCommit = { f -> if (dur > 0) MusicState.seek((f * dur).toLong()); drag = null },
                    modifier = Modifier.padding(top = Space.s), valueText = "${Music.fmt(shownPos)} / ${if (dur > 0) Music.fmt(dur) else "--:--"}")
                Row {
                    Text(Music.fmt(shownPos), color = if (drag != null) C.Text else C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), modifier = Modifier.weight(1f))
                    // 右侧显示剩余时间（Apple Music / Spotify 的习惯）
                    Text(if (dur > 0) "-" + Music.fmt((dur - shownPos).coerceAtLeast(0L)) else "--:--", color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num),
                        modifier = Modifier.semantics { contentDescription = if (dur > 0) "剩余 ${Music.fmt((dur - shownPos).coerceAtLeast(0L))}" else "" })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = Space.m).graphicsLayer { alpha = contentAlpha() }, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { MusicState.prev() }, modifier = Modifier.size(64.dp)) { Icon(KI.skipBackSolid(), "上一首", tint = C.Text, modifier = Modifier.size(34.dp)) }
                val playIx = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(Modifier.size(76.dp).skeuoPressable(playIx, CircleShape, C.Text, scale = 0.92f, flat = true).clickable(playIx, null, role = Role.Button) { MusicState.toggle() }
                    .semantics { contentDescription = if (MusicState.isPlaying) "暂停" else "播放" }, contentAlignment = Alignment.Center) {
                    if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    else PlayPauseIcon(MusicState.isPlaying, C.Bg, 34.dp)
                }
                IconButton(onClick = { MusicState.next() }, modifier = Modifier.size(64.dp)) { Icon(KI.skipFwdSolid(), "下一首", tint = C.Text, modifier = Modifier.size(34.dp)) }
            }
            // 工具行：播放模式 · 歌词 · 定时 · 列表
            Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, bottom = Space.m).graphicsLayer { alpha = contentAlpha() }, horizontalArrangement = Arrangement.SpaceEvenly) {
                val modeOn = MusicState.shuffle || MusicState.repeat == Player.REPEAT_MODE_ONE
                ToolButton(label = modeText(), active = modeOn, desc = "播放模式", state = modeText(), onClick = { cycleMode() }) { tint ->
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (MusicState.shuffle) KI.shuffle() else KI.repeat(), null, tint = if (MusicState.repeat == Player.REPEAT_MODE_OFF && !MusicState.shuffle) C.Faint else tint, modifier = Modifier.size(22.dp))
                        // 单曲循环：循环图标中间加一个小「1」（Kubai 没有 repeat-one）
                        if (!MusicState.shuffle && MusicState.repeat == Player.REPEAT_MODE_ONE) Text("1", color = tint, style = Type.badge, fontSize = 9.sp)
                    }
                }
                ToolButton(label = "歌词", active = mode == 1, desc = "歌词", state = if (mode == 1) "已显示" else "已隐藏", switch = true, onClick = { setMode(if (mode == 1) 0 else 1) }) { tint ->
                    Text("词", color = tint, style = MaterialTheme.typography.titleMedium)
                }
                SleepTool()
                ToolButton(label = "列表", active = mode == 2, desc = "播放列表", state = if (mode == 2) "已显示" else "已隐藏", switch = true, onClick = { setMode(if (mode == 2) 0 else 2) }) { tint ->
                    Icon(KI.playlist(), null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    }
    if (manual && t != null && sourceKey != null) MusicManualSearch(t, sourceKey) { manual = false }
}

/** 正在播放页的 ♡（A2；1.7.32 按曲目分开记，切歌时心形不会自己弹一下）。 */
@Composable
private fun FavButton(t: Track?, lib: MusicLib?, size: androidx.compose.ui.unit.Dp) {
    if (t == null || lib == null) return
    key(t.id) {
        val fav = t.id in lib.favs
        IconButton(onClick = { lib.toggleFav(t.id) }) {
            Icon(if (fav) KI.heartSolid() else KI.heart(), if (fav) "取消喜欢" else "喜欢", tint = if (fav) C.Accent else C.Text, modifier = Modifier.size(size).popOnChange(fav))
        }
    }
}

/** 裁切成页面里的一个圆角矩形（容器展开用；RoundedCornerShape 只能铺满整个尺寸）。 */
private class RectShape(val r: androidx.compose.ui.geometry.Rect, val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(radius)))
}

/**
 * 1.7.36 正在播放页的「舞台」背景：封面取色衍生 3 个色（[StageMath.palette]，深浅主题各自保证文字对比度），画成 3 个大径向渐变。
 * 只在 播放中 + 页面在前台 + 没开移除动画 + 设置「播放页背景流动」开 时按帧推进漂移时间，暂停 / 切后台 / 被别的页盖住就停在原地；
 * 换歌时颜色 600 ms 过渡。只改 drawBehind 的参数，不重组、不模糊。
 */
@Composable
private fun NowPlayingStage(artPath: String?, playing: Boolean) {
    val ctx = LocalContext.current
    val dark = C.P.dark
    val seed by produceState<Int?>(null, artPath) { value = artPath?.let { Ambient.colorOf(ctx, "file://$it")?.toArgb() } }
    val accent = C.Accent.toArgb()
    val pal = remember(seed, dark, accent) { StageMath.palette(seed ?: accent, dark) }
    val c0 by androidx.compose.animation.animateColorAsState(Color(pal[0]), tween(if (Motion.reduced) 0 else 600), label = "st0")
    val c1 by androidx.compose.animation.animateColorAsState(Color(pal[1]), tween(if (Motion.reduced) 0 else 600), label = "st1")
    val c2 by androidx.compose.animation.animateColorAsState(Color(pal[2]), tween(if (Motion.reduced) 0 else 600), label = "st2")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val flow = playing && resumed && !Motion.reduced && MusicPrefs.bgFlow
    // 漂移时间（秒）：暂停时停在原处
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(flow) {
        if (!flow) return@LaunchedEffect
        var last = 0L
        while (isActive) withFrameNanos { n -> if (last != 0L) phase += ((n - last) / 1e9f).coerceAtMost(0.1f); last = n }
    }
    val base = C.Bg
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(base)
        val r = maxOf(size.width, size.height) * 0.72f
        val cs = arrayOf(c0, c1, c2)
        for (i in 0..2) {
            val (x, y) = StageMath.center(i, phase)
            val o = Offset(x * size.width, y * size.height)
            drawCircle(Brush.radialGradient(listOf(cs[i], cs[i].copy(alpha = 0f)), center = o, radius = r), radius = r, center = o)
        }
    })
}

/** 顶栏：收起 /「来自 专辑」/ ⋯。 */
@Composable
private fun NowPlayingTopBar(t: Track?, sourceKey: String?, lib: MusicLib?, onClose: () -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = Space.xs, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(KI.down(), "收起", tint = C.Text, modifier = Modifier.size(28.dp)) }
        val album = t?.takeIf { it.cleanAlbum.isNotBlank() }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(Radius.s))
            .then(if (album != null) Modifier.clickable(onClickLabel = "查看专辑", role = Role.Button) { Nav.push(Route.MusicList("album", album.albumKey, album.displayAlbum)) } else Modifier)
            .semantics(mergeDescendants = true) {}.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (album != null) "来自专辑" else "正在播放", color = C.Sub, style = MaterialTheme.typography.labelMedium)
            if (album != null) Text(album.displayAlbum, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = t != null) { Icon(KI.more(), "更多", tint = C.Text, modifier = Modifier.size(24.dp)) }
            if (t != null) AppDropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (t.cleanAlbum.isNotBlank()) DropdownMenuItem(text = { Text("查看专辑", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("album", t.albumKey, t.displayAlbum)) })
                t.artists.firstOrNull()?.takeIf { it.isNotBlank() }?.let { a ->
                    DropdownMenuItem(text = { Text("查看歌手", color = C.Text) }, onClick = { menu = false; Nav.push(Route.MusicList("artist", a, a)) })
                }
                if (lib != null) DropdownMenuItem(text = { Text("重新匹配封面和歌词", color = C.Text) }, onClick = {
                    menu = false
                    toast(ctx, "正在重新匹配…")
                    Ops.launch {
                        runCatching { lib.rematch(t) }
                            .onSuccess { n -> toast(ctx, if (n.artPath != null) "已重新匹配" else "已重新匹配，没有找到可信的封面") }
                            .onFailure { failToast(ctx, it, "重新匹配失败") }
                    }
                })
                if (sourceKey != null) DropdownMenuItem(text = { Text("手动搜索封面和歌词", color = C.Text) }, onClick = { menu = false; onManual() })
                DropdownMenuItem(text = { Column { Text("文件信息", color = C.Text); Text("${t.ext.uppercase()} · ${t.codec} · ${"%.1f".format(t.size / 1048576.0)} MB", color = C.Sub, fontSize = 11.sp)
                    Text(t.path.removePrefix("fm:"), color = C.Sub, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }, onClick = { menu = false })
            }
        }
    }
}

/** 1.7.34 正在播放页底部工具行的一个按钮：48dp 图标区 + 小字；[active] 时强调色。 */
@Composable
private fun ToolButton(label: String, active: Boolean, desc: String, state: String? = null, switch: Boolean = false, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val tint = if (active) C.Accent else C.Text
    Column(Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(Radius.m)).clickable(role = if (switch) Role.Switch else Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = desc; if (state != null) stateDescription = state }
        .padding(vertical = Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(32.dp).pressScale(0.9f), contentAlignment = Alignment.Center) { icon(tint) }
        Text(label, color = if (active) C.Accent else C.Sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** 1.7.34 无损 / DSD 标：FLAC、APE、WV、WAV、AIFF、ALAC 记「无损」，DSD 单独写；有损格式不标（WMA 仍标格式名）。 */
internal fun qualityOf(t: Track): String? = when (val b = badge(t)) {
    null -> null
    "DSD", "WMA" -> b
    else -> "无损 $b"
}

/** 全面审查 A3：定时关闭（和影视播放器同样的选项）；1.7.34 移到底部工具行，开着时强调色并显示剩余分钟。 */
@Composable
private fun SleepTool() {
    var open by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(MusicState.sleepAt) { while (MusicState.sleepAt > 0) { left = (((MusicState.sleepAt - System.currentTimeMillis()) + 59_999) / 60_000).toInt().coerceAtLeast(0); delay(15_000) } }
    val on = MusicState.sleepAt > 0 || MusicState.sleepAfterTrack != null
    val label = when { MusicState.sleepAt > 0 -> "$left 分"; MusicState.sleepAfterTrack != null -> "本首"; else -> "定时" }
    Box {
        ToolButton(label = label, active = on, desc = "定时关闭", state = when { MusicState.sleepAt > 0 -> "剩 $left 分钟"; MusicState.sleepAfterTrack != null -> "本首播完停止"; else -> "未开启" },
            onClick = { open = true }) { tint -> Icon(KI.moon(on), null, tint = tint, modifier = Modifier.size(22.dp)) }
        AppDropdownMenu(open, onDismissRequest = { open = false }) {
            PlayerHints.sleepChoices.forEach { m -> DropdownMenuItem(text = { Text("$m 分钟后暂停", color = C.Text) }, onClick = { open = false; MusicState.setSleep(m) }) }
            DropdownMenuItem(text = { Text("本首播完后停止", color = C.Text) }, onClick = { open = false; MusicState.sleepAfterThisTrack() })
            if (on) DropdownMenuItem(text = { Text("关闭定时", color = C.Danger) }, onClick = { open = false; MusicState.clearSleep() })
        }
    }
}


/** 列表循环 → 单曲循环 → 随机播放 → (back to 列表循环). */
private fun cycleMode() {
    when {
        MusicState.shuffle -> { MusicState.shuffleTo(false); MusicState.repeatTo(Player.REPEAT_MODE_ALL) }
        MusicState.repeat == Player.REPEAT_MODE_ONE -> { MusicState.repeatTo(Player.REPEAT_MODE_ALL); MusicState.shuffleTo(true) }
        else -> MusicState.repeatTo(Player.REPEAT_MODE_ONE)
    }
}
private fun modeText(): String = when {
    MusicState.shuffle -> "随机播放"
    MusicState.repeat == Player.REPEAT_MODE_ONE -> "单曲循环"
    MusicState.repeat == Player.REPEAT_MODE_ALL -> "列表循环"
    else -> "顺序播放"
}

/**
 * 1.7.36 列表态（代替原来的底部面板）：顶部 随机 / 列表循环 / 单曲循环 三个胶囊开关 + 清空；
 * 「正在播放」一行，下面「接下来」；左滑移除（红底 + 垃圾桶，松手前可以拉回来），右侧手柄拖动排序（全面审查 B9）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePane() {
    val q = MusicState.queue
    val st = rememberLazyListState(initialFirstVisibleItemIndex = (MusicState.index - 1).coerceAtLeast(0))
    var confirmClear by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = Space.s, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ModeChip("随机", MusicState.shuffle) { MusicState.shuffleTo(!MusicState.shuffle) }
                ModeChip("列表循环", MusicState.repeat == Player.REPEAT_MODE_ALL) {
                    MusicState.repeatTo(if (MusicState.repeat == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL)
                }
                ModeChip("单曲循环", MusicState.repeat == Player.REPEAT_MODE_ONE) {
                    MusicState.repeatTo(if (MusicState.repeat == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE)
                }
            }
            HeaderTextButton("清空", C.Danger) { confirmClear = true }
        }
        LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
            itemsIndexed(q, key = { i, m -> "$i|${m.mediaId}" }) { i, m ->
                val on = i == MusicState.index
                if (i == MusicState.index + 1 || (i == 0 && !on)) Text(if (i == 0 && !on) "已播放 · ${MusicState.index} 首" else "接下来 · ${q.size - i} 首", color = C.Sub, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 22.dp, top = Space.m, bottom = Space.xs).semantics { heading() })
                if (on) Text("正在播放", color = C.Sub, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 22.dp, top = Space.xs, bottom = Space.xs).semantics { heading() })
                val dragging = dragFrom == i
                val row = @Composable {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { if (dragging) { translationY = dragDy; shadowElevation = 8f } }
                        .background(if (dragging) C.Surface else Color.Transparent)
                        .clickable(onClickLabel = "播放这首") { MusicState.playAt(i) }.padding(start = 22.dp, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (on) NowPlayingBars(MusicState.isPlaying, C.Accent, Modifier.padding(end = Space.m))
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { if (on) stateDescription = "正在播放" }) {
                            Text(m.mediaMetadata.title?.toString() ?: "", color = if (on) C.Accent else C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.mediaMetadata.artist?.toString() ?: "", color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // 读屏 / 不方便滑动时仍可点 ✕ 移除
                        if (!on) IconButton(onClick = { MusicState.remove(i) }) { Icon(KI.close(), "从播放列表移除", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                        Box(Modifier.size(48.dp).pointerInput(i, q.size) {
                            detectDragGestures(
                                onDragStart = { dragFrom = i; dragDy = 0f },
                                onDragEnd = {
                                    val to = (i + kotlin.math.round(dragDy / rowPx).toInt()).coerceIn(0, q.size - 1)
                                    dragFrom = -1; dragDy = 0f; MusicState.move(i, to)
                                },
                                onDragCancel = { dragFrom = -1; dragDy = 0f },
                            ) { ch, d -> ch.consume(); dragDy += d.y }
                        }, contentAlignment = Alignment.Center) {
                            Icon(KI.menu(), "拖动排序", tint = C.Faint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (on) row()
                else {
                    // 左滑移除：滑过一半松手就移除，不够就弹回
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) { MusicState.remove(i); true } else false })
                    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
                        if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                            Box(Modifier.fillMaxSize().background(C.Danger).padding(end = Space.xl), contentAlignment = Alignment.CenterEnd) {
                                Icon(KI.trash(), null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                    }) { row() }
                }
            }
        }
    }
    if (confirmClear) AppAlertDialog(onDismissRequest = { confirmClear = false }, containerColor = C.Surface,
        title = { Text("清空播放列表？", color = C.Text) }, text = { Text("会停止播放并移除列表里的 ${q.size} 首歌。", color = C.Sub) },
        confirmButton = { TextButton(onClick = { confirmClear = false; MusicState.stop() }) { Text("清空", color = C.Danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = C.Sub) } })
}

/** 列表态顶部的播放模式胶囊（读屏是开关）。 */
@Composable
private fun ModeChip(text: String, on: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (on) C.AccentSoft else C.Chip, Motion.state(), label = "chip")
    Box(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(bg).clickable(role = Role.Switch, onClick = onClick)
        .semantics { stateDescription = if (on) "已开启" else "已关闭" }.padding(horizontal = Space.m), contentAlignment = Alignment.Center) {
        Text(text, color = if (on) C.Accent else C.Text, style = MaterialTheme.typography.labelLarge)
    }
}
