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
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicNowPlayingScreen() {
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(Store.getStr("music.showLyrics") == "1") }
    fun setLyrics(on: Boolean) { showLyrics = on; Store.putStr("music.showLyrics", if (on) "1" else "") }
    var queueOpen by remember { mutableStateOf(false) }
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
    BackHandler { if (queueOpen) queueOpen = false else Nav.pop() }

    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    val skip = rememberSkipSwipe()
    // 下拉收起：拖动中跟手，松手不够远就弹回
    val pullAnim = remember { Animatable(0f) }
    var pullRaw by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }
    var pageH by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }
    fun pull(): Float = if (pulling) pullRaw.coerceAtLeast(0f) else pullAnim.value
    fun pullDrag(d: Float) { if (dismissed) return; if (!pulling) { pulling = true; pullRaw = pullAnim.value }; pullRaw += d }
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

    Box(Modifier.fillMaxSize().onSizeChanged { pageH = it.height.toFloat() }
        // 拉下来时后面压一层渐淡的暗色（直接压在首页上时能看到首页）
        .drawBehind { val p = SwipeMath.dismissProgress(pull(), pageH); if (p > 0f) drawRect(Color.Black.copy(alpha = unitAlpha(0.45f * (1f - p)))) }
        .mediaDrag(Unit, horizontal = false, vertical = true, onVertical = { pullDrag(it) }, onVerticalEnd = { _, v -> pullEnd(v) })) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        val y = pull()
        translationY = y
        val p = SwipeMath.dismissProgress(y, pageH)
        if (p > 0f) {
            if (!Motion.reduced) { val s = 1f - 0.08f * p; scaleX = s; scaleY = s }
            shape = RoundedCornerShape((32 * p).dp); clip = true
        }
    }.appBackground()) {
        // 1.7.0 v2：封面放大并重度模糊铺满全屏（API 31+ 真模糊；以下仍是压暗的封面），环境光晕取封面颜色
        AmbientFrom(t?.artPath?.let { "file://$it" })
        if (t?.artPath != null) Box(Modifier.fillMaxSize()) {
            val real = Glass.blurSupported
            NetImage("file://${t.artPath}", Modifier.fillMaxSize().graphicsLayer { if (real) { scaleX = 1.3f; scaleY = 1.3f } }.then(if (real) Modifier.blur(80.dp) else Modifier), crossfade = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(if (real) listOf(C.Bg.copy(alpha = 0.45f), C.Bg.copy(alpha = 0.7f), C.Bg.copy(alpha = 0.92f))
                else listOf(C.Bg.copy(alpha = 0.86f), C.Bg.copy(alpha = 0.95f), C.Bg))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            NowPlayingTopBar(t, sourceKey, lib)
            // 中区：封面（左右滑切歌、上滑打开歌词、下拉收起）⇄ 歌词；点一下切换
            Box(Modifier.weight(1f).fillMaxWidth()
                .mediaDrag(showLyrics, horizontal = !showLyrics, vertical = true,
                    onHorizontal = { skip.drag(it) },
                    onHorizontalEnd = { _, v -> scope.launch { skip.release(v / density) { Haptics.tick(view) } } },
                    onVertical = { pullDrag(it) },
                    onVerticalEnd = { total, v ->
                        if (!showLyrics && total < -upPx) { pulling = false; pullRaw = 0f; scope.launch { pullAnim.snapTo(0f) }; setLyrics(true) }
                        else pullEnd(v)
                    })
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClickLabel = if (showLyrics) "显示封面" else "显示歌词") { setLyrics(!showLyrics) }
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction("上一首") { MusicState.prevTrack(); true }.takeIf { MusicState.canSkip(-1) },
                        CustomAccessibilityAction("下一首") { MusicState.next(); true }.takeIf { MusicState.canSkip(1) },
                    )
                }, contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(showLyrics, animationSpec = Motion.enter(), label = "lyrics") { ly ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (ly) LyricsView(lyrics, pos) { MusicState.seek(it) }
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val scale by animateFloatAsState(if (MusicState.isPlaying) 1f else 0.9f, Motion.breathe(), label = "art")
                            MusicArt(t?.artPath, Modifier.padding(horizontal = 28.dp).fillMaxWidth().aspectRatio(1f)
                                .onSizeChanged { skip.width = it.width.toFloat() }
                                .graphicsLayer {
                                    val x = skip.offset
                                    translationX = x
                                    val f = if (skip.width > 0f) (abs(x) / skip.width).coerceIn(0f, 1f) else 0f
                                    alpha = 1f - f * 0.6f
                                    val s = scale * (1f - 0.06f * f); scaleX = s; scaleY = s
                                }, corner = Radius.l, lifted = true)
                            // one current lyric line under the cover（没有时间轴的歌词 / 没有歌词时这一行不占位）
                            val cur = lyrics?.let { l -> currentLine(l, pos) }?.let { lyrics?.getOrNull(it)?.text }
                            if (lyrics?.firstOrNull()?.let { it.ms >= 0 } == true)
                            // 1.7.32：旧的一句同时往上退出（以前原地淡出、新的一句从下面压上来，两行叠在一起有重影）；裁在这一行的高度里
                            androidx.compose.animation.AnimatedContent(cur ?: "", Modifier.clipToBounds(), transitionSpec = {
                                if (Motion.reduced) fadeIn(Motion.state()) togetherWith fadeOut(Motion.exit())
                                else (fadeIn(Motion.enter()) + androidx.compose.animation.slideInVertically(Motion.move()) { it / 2 }) togetherWith
                                    (fadeOut(Motion.exit()) + androidx.compose.animation.slideOutVertically(Motion.move()) { -it / 2 })
                            }, label = "curLine") { line ->
                                Text(line, color = C.Sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 22.dp, start = Space.xl, end = Space.xl).fillMaxWidth())
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 歌名过长时横向跑马灯（播两遍停下）；移除动画时用省略号
                    Text(t?.displayTitle ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = if (Motion.reduced) TextOverflow.Ellipsis else TextOverflow.Clip,
                        modifier = Modifier.weight(1f).then(if (Motion.reduced) Modifier else Modifier.basicMarquee(iterations = 2, initialDelayMillis = 1500)))
                    // A2：喜欢当前这首（1.7.32：按曲目分开记，切歌时心形不会自己弹一下）
                    if (t != null && lib != null) key(t.id) {
                        val fav = t.id in lib.favs
                        IconButton(onClick = { lib.toggleFav(t.id) }) {
                            Icon(if (fav) KI.heartSolid() else KI.heart(), if (fav) "取消喜欢" else "喜欢", tint = if (fav) C.Accent else C.Text, modifier = Modifier.size(26.dp).popOnChange(fav))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val artist = t?.artists?.firstOrNull()?.takeIf { it.isNotBlank() && t.displayArtist != "未知歌手" }
                    Text(t?.displayArtist ?: "", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(Radius.xs))
                            .then(if (artist != null) Modifier.clickable(onClickLabel = "查看歌手", role = Role.Button) { Nav.push(Route.MusicList("artist", artist, artist)) } else Modifier))
                    t?.let { qualityOf(it) }?.let { b -> Text(b, color = C.Sub, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, modifier = Modifier.padding(start = Space.s).clip(RoundedCornerShape(4.dp)).background(C.Chip).padding(horizontal = 5.dp, vertical = 1.dp)) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = Space.m), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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
            Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, bottom = Space.m), horizontalArrangement = Arrangement.SpaceEvenly) {
                val modeOn = MusicState.shuffle || MusicState.repeat == Player.REPEAT_MODE_ONE
                ToolButton(label = modeText(), active = modeOn, desc = "播放模式", state = modeText(), onClick = { cycleMode() }) { tint ->
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (MusicState.shuffle) KI.shuffle() else KI.repeat(), null, tint = if (MusicState.repeat == Player.REPEAT_MODE_OFF && !MusicState.shuffle) C.Faint else tint, modifier = Modifier.size(22.dp))
                        // 单曲循环：循环图标中间加一个小「1」（Kubai 没有 repeat-one）
                        if (!MusicState.shuffle && MusicState.repeat == Player.REPEAT_MODE_ONE) Text("1", color = tint, style = Type.badge, fontSize = 9.sp)
                    }
                }
                ToolButton(label = "歌词", active = showLyrics, desc = "歌词", state = if (showLyrics) "已显示" else "已隐藏", switch = true, onClick = { setLyrics(!showLyrics) }) { tint ->
                    Text("词", color = tint, style = MaterialTheme.typography.titleMedium)
                }
                SleepTool()
                ToolButton(label = "列表", active = false, desc = "播放列表", onClick = { queueOpen = true }) { tint -> Icon(KI.playlist(), null, tint = tint, modifier = Modifier.size(22.dp)) }
            }
        }
    }
    }
    if (queueOpen) AppBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = C.Surface) { QueueSheet() }
}

/** 顶栏：收起 /「来自 专辑」/ ⋯。 */
@Composable
private fun NowPlayingTopBar(t: Track?, sourceKey: String?, lib: MusicLib?) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    if (manual && t != null && sourceKey != null) MusicManualSearch(t, sourceKey) { manual = false }
    Row(Modifier.padding(horizontal = Space.xs, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Nav.pop() }) { Icon(KI.down(), "收起", tint = C.Text, modifier = Modifier.size(28.dp)) }
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
                if (sourceKey != null) DropdownMenuItem(text = { Text("手动搜索封面和歌词", color = C.Text) }, onClick = { menu = false; manual = true })
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

private fun currentLine(l: List<Lrc.Line>, pos: Long): Int? {
    if (l.isEmpty() || l.first().ms < 0) return null
    var i = l.indexOfLast { it.ms <= pos + 300 }
    if (i < 0) i = 0
    return i
}

@Composable
private fun LyricsView(lines: List<Lrc.Line>?, pos: Long, onSeek: (Long) -> Unit) {
    when {
        lines == null -> CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        lines.isEmpty() -> Text(if (MusicPrefs.online) "没有找到歌词" else "没有歌词（联网补全已关闭）", color = C.Sub, style = MaterialTheme.typography.bodyLarge)
        else -> {
            val st = rememberLazyListState()
            val cur = currentLine(lines, pos)
            // 1.6.6：当前行滚到视口约 40% 处（弹簧滚动，用户手动滑动时不抢）；字号固定，当前行靠颜色 + 缩放 1.0 / 0.94 区分，
            // 换行时不重新排版（以前字号 16↔19 来回变会让整列跳一下）
            val density = androidx.compose.ui.platform.LocalDensity.current
            LaunchedEffect(cur) {
                if (cur != null && !st.isScrollInProgress) {
                    val off = -(st.layoutInfo.viewportSize.height * 0.4f).toInt() + with(density) { 120.dp.roundToPx() }
                    if (Motion.reduced) st.scrollToItem(cur, off) else st.animateScrollToItem(cur, off)
                }
            }
            LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 120.dp, horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(lines) { i, l ->
                    val on = i == cur
                    val fg by androidx.compose.animation.animateColorAsState(if (on) C.Text else C.Sub.copy(alpha = 0.7f), Motion.state(), label = "lrcFg")
                    val sc by animateFloatAsState(if (on) 1f else 0.94f, Motion.move(), label = "lrcScale")
                    Column(Modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(Radius.s)).clickable(enabled = l.ms >= 0, onClickLabel = "从这句开始播放") { onSeek(l.ms) }.padding(vertical = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l.text.ifEmpty { "♪" }, color = fg, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
                        if (l.sub.isNotEmpty()) Text(l.sub, color = C.Sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSheet() {
    val q = MusicState.queue
    val st = rememberLazyListState(initialFirstVisibleItemIndex = (MusicState.index - 2).coerceAtLeast(0))
    var confirmClear by remember { mutableStateOf(false) }
    // 全面审查 B9：右侧拖动手柄排序；拖动时这一行跟着手指走，松手后按移动的行数换位置
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
        Row(Modifier.padding(start = Space.xl, end = Space.s, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
                Text("播放列表", style = MaterialTheme.typography.titleLarge)
                Text("${q.size} 首 · ${modeText()}", color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(start = Space.s, bottom = 2.dp))
            }
            HeaderTextButton("清空", C.Danger) { confirmClear = true }
        }
        LazyColumn(state = st, contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(q, key = { i, m -> "$i|${m.mediaId}" }) { i, m ->
                val on = i == MusicState.index
                val dragging = dragFrom == i
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { if (dragging) { translationY = dragDy; shadowElevation = 8f } }
                    .background(if (dragging) C.Surface else Color.Transparent)
                    .clickable(onClickLabel = "播放这首") { MusicState.playAt(i) }.padding(start = Space.xl, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (on) NowPlayingBars(MusicState.isPlaying, C.Accent, Modifier.padding(end = Space.m))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { if (on) stateDescription = "正在播放" }) {
                        Text(m.mediaMetadata.title?.toString() ?: "", color = if (on) C.Accent else C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.mediaMetadata.artist?.toString() ?: "", color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // B9：删除按钮 48dp 触控区（原来 18dp 图标直接点，容易点歪）
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
        }
    }
    if (confirmClear) AppAlertDialog(onDismissRequest = { confirmClear = false }, containerColor = C.Surface,
        title = { Text("清空播放列表？", color = C.Text) }, text = { Text("会停止播放并移除列表里的 ${q.size} 首歌。", color = C.Sub) },
        confirmButton = { TextButton(onClick = { confirmClear = false; MusicState.stop() }) { Text("清空", color = C.Danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = C.Sub) } })
}
