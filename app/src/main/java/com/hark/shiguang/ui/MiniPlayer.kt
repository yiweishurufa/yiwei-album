package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hark.shiguang.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ============================================================ mini bar

/** Extra bottom space for Home tab lists while the mini bar is showing, so it no longer covers the last row. */
val miniPlayerPad: Dp get() = if (MusicState.active && !Selection.active) 64.dp else 0.dp

/**
 * 1.7.36 迷你播放条 → 正在播放的「容器展开」（没有 SharedTransitionLayout，沿用相册大图 [Hero] 的办法）：
 * 只存迷你条胶囊和小封面的 LayoutCoordinates 引用，点开 / 上滑那一刻才算屏幕位置交给正在播放页；
 * 正在播放页从胶囊大小「揭开」到全屏、封面从小封面长到大封面，收起时反过来缩回迷你条，缩完 [closed] = true，这次出栈不再做页面转场。
 */
object MiniHero {
    class Rects(val capsule: Rect, val art: Rect)
    var capsule: LayoutCoordinates? = null
    var art: LayoutCoordinates? = null
    /** 这次打开要做容器展开（正在播放页读出后在首帧之后清掉；MainActivity 据此不叠页面转场）。 */
    var pending: Rects? = null
    var closed = false

    /** 迷你条现在在屏幕上的位置（不在组合里 / 被多选隐藏时为 null）。 */
    fun target(): Rects? {
        val c = capsule?.takeIf { it.isAttached }?.boundsInRoot() ?: return null
        val a = art?.takeIf { it.isAttached }?.boundsInRoot() ?: return null
        if (c.width <= 0f || a.width <= 0f) return null
        return Rects(c, a)
    }

    fun open() {
        if (Nav.stack.lastOrNull() == Route.MusicNowPlaying) return
        pending = if (Motion.reduced || Selection.active) null else target()
        closed = false
        Nav.push(Route.MusicNowPlaying)
    }
}

/**
 * Above the bottom navigation (or at the bottom on wide screens) while something is queued.
 * 1.6.6 阶段 5：浮起的胶囊（圆角 20、质感卡片影）、底部 2dp 进度细线；整条按压 0.985，点开正在播放页（从下往上展开）；
 * 播放 / 暂停换图标有缩放过渡；出现 / 消失由 Home 里的 AnimatedVisibility 从底部滑入滑出。
 * 1.7.34：左右滑切歌（封面和歌名一起跟手，过三成宽度或甩一下就切，新歌从另一侧滑进来）；往上滑展开正在播放页。
 * 读屏另有「上一首 / 下一首」自定义操作。
 */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null) {
    val t = remember(MusicState.mediaId, MusicState.tick) { MusicState.current() }
    var pos by remember { mutableLongStateOf(0L) }
    // 全面审查 B10：暂停时只读一次位置，不再每 0.5 秒刷新
    LaunchedEffect(MusicState.isPlaying, MusicState.mediaId) { pos = MusicState.position; while (MusicState.isPlaying) { delay(500); pos = MusicState.position } }
    val dur = MusicState.duration
    val title = t?.displayTitle ?: "正在准备…"
    // 1.7.0 v2：玻璃胶囊（和底栏同宽、首页上有真模糊），按下液体形变；环境光晕取当前封面
    AmbientFrom(t?.artPath?.let { "file://$it" })
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.l)
    val skip = rememberSkipSwipe()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density
    val upPx = SwipeMath.UP_DP * density
    Column(modifier.padding(horizontal = 14.dp).fillMaxWidth().onGloballyPositioned { MiniHero.capsule = it }.glassPress(press, 0.5f).glass(shape, backdrop, shadow = 14.dp, solid = true).clip(shape)
        .mediaDrag(Unit, horizontal = true, vertical = true,
            onHorizontal = { skip.drag(it) },
            onHorizontalEnd = { _, v -> scope.launch { skip.release(v / density) { Haptics.tick(view) } } },
            onVerticalEnd = { total, v -> if (total < -upPx || (v / density < -SwipeMath.SKIP_VELOCITY_DP && total < 0f)) MiniHero.open() })
        .clickable(press, null, onClickLabel = "打开正在播放") { MiniHero.open() }
        .semantics(mergeDescendants = false) {
            contentDescription = "正在播放：$title" + (t?.displayArtist?.takeIf { it.isNotBlank() }?.let { "，$it" } ?: "")
            customActions = listOfNotNull(
                CustomAccessibilityAction("上一首") { MusicState.prevTrack(); true }.takeIf { MusicState.canSkip(-1) },
                CustomAccessibilityAction("下一首") { MusicState.next(); true }.takeIf { MusicState.canSkip(1) },
            )
        }) {
        Row(Modifier.padding(start = Space.s, end = Space.xs, top = Space.s, bottom = Space.s - 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // 封面 + 歌名一起跟手；越远越淡
            Row(Modifier.weight(1f).onSizeChanged { skip.width = it.width.toFloat() }.graphicsLayer {
                val x = skip.offset
                translationX = x
                alpha = if (skip.width > 0f) (1f - abs(x) / skip.width * 0.7f).coerceIn(0f, 1f) else 1f
            }, verticalAlignment = Alignment.CenterVertically) {
                MusicArt(t?.artPath, Modifier.size(42.dp).onGloballyPositioned { MiniHero.art = it }, corner = Radius.l - Space.s)
                Column(Modifier.weight(1f).padding(start = Space.m)) {
                    Text(title, color = C.OnGlass, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t?.displayArtist ?: "", color = C.OnGlassSub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { MusicState.toggle() }, modifier = Modifier.semantics { contentDescription = if (MusicState.isPlaying) "暂停" else "播放" }) {
                if (MusicState.buffering && MusicState.isPlaying) CircularProgressIndicator(color = C.Sub, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else PlayPauseIcon(MusicState.isPlaying, C.OnGlass, 24.dp)
            }
            IconButton(onClick = { MusicState.next() }) { Icon(KI.skipFwdSolid(), "下一首", tint = C.OnGlass, modifier = Modifier.size(22.dp)) }
        }
        Box(Modifier.padding(horizontal = Space.l).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.Line)) {
            if (dur > 0) Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(C.Accent))
        }
        Spacer(Modifier.height(Space.xs + 2.dp))
    }
}
