package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Lrc
import com.hark.shiguang.MusicPrefs
import com.hark.shiguang.MusicState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一行歌词或一段间奏（间奏只在唱到它时撑开）。 */
private sealed interface LyricItem {
    data class L(val index: Int) : LyricItem
    data class G(val gap: Lrc.Gap) : LyricItem
}

/**
 * 1.7.35 歌词 v2（MEDIA-RESEARCH 批 2，参照 Apple Music / AMLL）：
 * - 大号粗体左对齐；当前句满色，离得越远越淡；Android 12+ 近处几行轻模糊（设置里「歌词柔焦」可关，只模糊 1–4 行，手动滑动时全部清晰）；
 * - 换句时列表一下子滚到位，各行带着错开的延迟从旧位置滑回来（上面的先动），当前句停在视口约 30% 处；
 *   拖进度 / 点歌词跳得远时不错开，直接定位并轻淡入；
 * - 歌词里有逐字时间（增强 LRC / YRC）时，当前句逐字点亮（只在绘制阶段读播放位置，不重组）；
 * - 两句之间空了 6 秒以上显示三颗呼吸点，随进度依次亮起；
 * - 手指一滑就暂停跟随，右下角出现「回到当前」，3 秒不碰自动回去；
 * - 移除动画：不错开、不模糊、不逐字（整句点亮），间奏点静态。
 */
@Composable
fun LyricsView(lines: List<Lrc.Line>?, pos: Long, playing: Boolean, onSeek: (Long) -> Unit, onManual: (() -> Unit)? = null) {
    when {
        lines == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
        lines.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (MusicPrefs.online) "没有找到歌词" else "没有歌词（联网补全已关闭）", color = C.Sub, style = MaterialTheme.typography.bodyLarge)
            if (onManual != null) QuietButton("手动搜索歌词", C.Accent, Modifier.padding(top = Space.m)) { onManual() }
        }
        lines.first().ms < 0 -> PlainLyrics(lines)
        else -> TimedLyrics(lines, pos, playing, onSeek)
    }
}

@Composable
private fun lyricStyle(): TextStyle {
    val fs = LocalDensity.current.fontScale
    val sp = LyricMath.fontSp(fs)
    return TextStyle(fontSize = sp.sp, lineHeight = (sp * 1.28f).sp, fontWeight = FontWeight.Bold, color = C.Text)
}

/** 没有时间轴的歌词：整篇静态显示，不跟随。 */
@Composable
private fun PlainLyrics(lines: List<Lrc.Line>) {
    val style = lyricStyle().let { it.copy(fontSize = it.fontSize * 0.78f, lineHeight = it.lineHeight * 0.78f) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 48.dp)) {
        itemsIndexed(lines) { _, l ->
            Text(l.text, style = style, color = C.Text.copy(alpha = 0.85f), modifier = Modifier.padding(vertical = Space.xs))
        }
    }
}

@Composable
private fun TimedLyrics(lines: List<Lrc.Line>, pos: Long, playing: Boolean, onSeek: (Long) -> Unit) {
    val items = remember(lines) {
        val gaps = Lrc.gaps(lines).groupBy { it.afterIndex }
        buildList {
            gaps[-1]?.forEach { add(LyricItem.G(it)) }
            lines.indices.forEach { i -> add(LyricItem.L(i)); gaps[i]?.forEach { add(LyricItem.G(it)) } }
        }
    }
    val lineItem = remember(items) { IntArray(lines.size).also { a -> items.forEachIndexed { k, it -> if (it is LyricItem.L) a[it.index] = k } } }
    // 当前项：在间奏里 → 那段间奏；否则 → 当前句
    val focus = remember(items, pos) {
        val g = items.indexOfFirst { it is LyricItem.G && pos >= it.gap.startMs && pos < it.gap.endMs - 200 }
        if (g >= 0) g else Lrc.current(lines, pos)?.let { lineItem[it] } ?: 0
    }
    val st = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    val dragged by st.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    // 手指离开、惯性停下 3 秒后回到跟随
    LaunchedEffect(following, dragged, st.isScrollInProgress) {
        if (!following && !dragged && !st.isScrollInProgress) { delay(LyricMath.RESUME_MS); following = true }
    }
    val blurOn = Glass.blurSupported && MusicPrefs.lyricBlur && following && !Motion.reduced

    // 错开落位：shift = 这次列表一下子滚过的距离；lagT = 换句后过去的毫秒数
    var shift by remember { mutableFloatStateOf(0f) }
    val lagT = remember { Animatable(10_000f) }
    val fade = remember { Animatable(1f) }
    var lastFocus by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focus, following) {
        if (!following) return@LaunchedEffect
        val prev = lastFocus
        lastFocus = focus
        val info = st.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focus }
        if (Motion.reduced || info == null || LyricMath.isJump(prev, focus)) {
            lagT.snapTo(10_000f)
            st.scrollToItem(focus, 0)
            if (!Motion.reduced && prev >= 0) { fade.snapTo(0.35f); fade.animateTo(1f, tween(120)) }
            return@LaunchedEffect
        }
        val d = info.offset.toFloat()
        if (kotlin.math.abs(d) < 1f) return@LaunchedEffect
        st.scrollBy(d)
        shift = d
        lagT.snapTo(0f)
        val total = (LyricMath.LAG_MS + Motion.STAGGER * Motion.STAGGER_MAX).toFloat()
        lagT.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }
    // 只在当前句有逐字时间、且在播、没开移除动画时逐帧读播放位置
    val cur = (items.getOrNull(focus) as? LyricItem.L)?.index
    val wordsLive = cur != null && lines[cur].words.isNotEmpty() && playing && !Motion.reduced
    val livePos = remember { mutableLongStateOf(pos) }
    LaunchedEffect(wordsLive) {
        if (!wordsLive) return@LaunchedEffect
        while (isActive) withFrameMillis { livePos.longValue = MusicState.position }
    }
    SideEffect { if (!wordsLive) livePos.longValue = pos }

    val style = lyricStyle()
    val subStyle = TextStyle(fontSize = (style.fontSize.value * 0.55f).sp, lineHeight = (style.fontSize.value * 0.75f).sp, fontWeight = FontWeight.Medium, color = C.Text)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = maxHeight * 0.3f
        LazyColumn(state = st, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = top, bottom = maxHeight * 0.6f)) {
            itemsIndexed(items, key = { k, it -> if (it is LyricItem.L) "l${it.index}" else "g$k" }) { k, item ->
                val distance = kotlin.math.abs(k - focus)
                val lagMod = Modifier.graphicsLayer {
                    val t = lagT.value
                    if (t < 10_000f) translationY = LyricMath.lag(shift, t, LyricMath.delayMs(k, focus))
                }
                when (item) {
                    is LyricItem.L -> LyricLine(lines[item.index], distance, following, blurOn, style, subStyle,
                        live = if (k == focus && wordsLive) livePos else null, modifier = lagMod) { if (lines[item.index].ms >= 0) onSeek(lines[item.index].ms) }
                    is LyricItem.G -> GapDots(item.gap, k == focus, pos, lagMod)
                }
            }
        }
        // 手动滑动后：回到当前
        AnimatedVisibility(!following, Modifier.align(Alignment.BottomEnd).padding(end = Space.l, bottom = Space.m),
            enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Row(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(C.Surface2.copy(alpha = 0.92f))
                .clickable(role = Role.Button, onClickLabel = "回到当前歌词") { following = true; scope.launch { st.scrollToItem(focus, 0) } }
                .padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(KI.down(), null, tint = C.Text, modifier = Modifier.size(16.dp))
                Text("回到当前", color = C.Text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Space.xs))
            }
        }
    }
}

@Composable
private fun LyricLine(l: Lrc.Line, distance: Int, following: Boolean, blurOn: Boolean, style: TextStyle, subStyle: TextStyle,
                      live: State<Long>?, modifier: Modifier, onClick: () -> Unit) {
    val a by animateFloatAsState(LyricMath.alpha(distance, following, light = !C.P.dark), Motion.state(), label = "lrcA")
    val blur by animateFloatAsState(if (blurOn) LyricMath.blurDp(distance) else 0f, Motion.state(), label = "lrcBlur")
    val sc by animateFloatAsState(if (distance == 0 || !following) 1f else 0.97f, Motion.move(), label = "lrcScale")
    val density = LocalDensity.current.density
    Column(modifier.fillMaxWidth()
        .graphicsLayer {
            alpha = unitAlpha(a)
            scaleX = sc; scaleY = sc
            transformOrigin = TransformOrigin(0f, 0.5f)
            val r = blur * density
            renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
        }
        .clip(RoundedCornerShape(Radius.s))
        .clickable(enabled = l.ms >= 0, onClickLabel = "从这句开始播放", onClick = onClick)
        .semantics { if (distance == 0) stateDescription = "当前" }
        .padding(vertical = Space.s)) {
        if (live != null) WordLit(l, style, live) else Text(l.text, style = style)
        if (l.sub.isNotEmpty()) Text(l.sub, style = subStyle, color = C.Text.copy(alpha = 0.7f), modifier = Modifier.padding(top = Space.xxs))
    }
}

/**
 * 逐字点亮：底下一层 40% 的整句，上面一层满色的整句，用每个视觉行一条「左实右空」的渐变（羽化半个字宽）只留下已唱的部分。
 * 播放位置只在 drawWithContent 里读，换字不重组、不重新排版。
 */
@Composable
private fun WordLit(l: Lrc.Line, style: TextStyle, live: State<Long>) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        Text(l.text, style = style, color = C.Text.copy(alpha = 0.4f), onTextLayout = { layout = it })
        Text(l.text, style = style, modifier = Modifier
            .clearAndSetSemantics {}
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val lay = layout ?: return@drawWithContent
                val sung = Lrc.sungChars(l, live.value) ?: return@drawWithContent
                val n = l.text.length
                for (line in 0 until lay.lineCount) {
                    val s = lay.getLineStart(line); val e = lay.getLineEnd(line, visibleEnd = true)
                    val top = lay.getLineTop(line); val bottom = lay.getLineBottom(line)
                    val left = lay.getLineLeft(line); val right = lay.getLineRight(line)
                    val feather = (style.fontSize.toPx() * 0.5f).coerceAtLeast(1f)
                    val x: Float = when {
                        sung >= e -> continue                       // 整行已唱：保留
                        sung <= s -> left - feather                 // 整行没唱：全擦掉
                        else -> {
                            val ci = sung.toInt().coerceIn(s, (n - 1).coerceAtLeast(0))
                            val box = lay.getBoundingBox(ci)
                            box.left + box.width * (sung - ci)
                        }
                    }
                    val brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - feather / 2, endX = x + feather / 2)
                    drawRect(brush, topLeft = Offset(left - 2f, top), size = Size(right - left + 4f, bottom - top), blendMode = BlendMode.DstIn)
                }
            })
    }
}

/** 间奏三颗点：唱到这段间奏时撑开，随进度依次亮起；结束前 0.3 秒一起淡出。 */
@Composable
private fun GapDots(g: Lrc.Gap, on: Boolean, pos: Long, modifier: Modifier) {
    val p = if (on) ((pos - g.startMs).toFloat() / (g.endMs - g.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
    val ending = on && g.endMs - pos < 300
    AnimatedVisibility(on, modifier, enter = if (Motion.reduced) fadeIn(Motion.state()) else expandVertically(Motion.move()) + fadeIn(Motion.enter()),
        exit = if (Motion.reduced) fadeOut(Motion.exit()) else shrinkVertically(Motion.move()) + fadeOut(Motion.exit())) {
        Row(Modifier.padding(vertical = Space.m).height(28.dp).semantics { contentDescription = "间奏" }, verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                val lit = if (Motion.reduced) 1f else LyricMath.dot(i, p)
                val f by animateFloatAsState(if (ending) 0f else lit, Motion.state(), label = "dot$i")
                Box(Modifier.padding(end = 10.dp).size(12.dp).graphicsLayer {
                    val s = 0.6f + 0.4f * f; scaleX = s; scaleY = s
                    alpha = unitAlpha(if (ending) f else 0.35f + 0.65f * f)
                }.clip(CircleShape).background(C.Text))
            }
        }
    }
}
