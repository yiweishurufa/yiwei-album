package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 1.6.6 阶段 5：音乐与视频播放器共用的小部件（DESIGN §6 §7）。
 * - [PlayPauseIcon]：播放 ↔ 暂停换图标时缩放淡入淡出（不是硬切），「移除动画」时只淡入淡出。
 * - [ScrubBar]：进度条。平时 4dp 细轨，按住 / 拖动时加粗到 8dp 并出现圆点；点哪跳哪；读屏可当滑块调。
 * - [PopOnChange]：数值变化时轻轻弹一下（收藏心形）。
 * - [NowPlayingBars]：「正在播放」的三根跳动小柱，只在真的在播时动（循环只给真实状态，DESIGN §7）。
 */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    AnimatedContent(playing, modifier, transitionSpec = {
        if (Motion.reduced) fadeIn(Motion.state()) togetherWith fadeOut(Motion.exit())
        else (scaleIn(Motion.move(), 0.6f) + fadeIn(Motion.state())) togetherWith (scaleOut(Motion.exit(), 0.6f) + fadeOut(Motion.exit()))
    }, label = "playPause") { p ->
        // 播放三角的视觉重心偏左，往右挪一点点才居中
        Icon(if (p) KI.pauseSolid() else KI.playSolid(), null, tint = tint, modifier = Modifier.size(size).offset(x = if (p) 0.dp else size * 0.04f))
    }
}

/**
 * 进度条。[value] 0–1；拖动时 [onChange] 跟手，松手 [onCommit]。[label] 给读屏（如「播放进度」），[valueText] 读出当前时间。
 */
@Composable
fun ScrubBar(value: Float, enabled: Boolean, onChange: (Float) -> Unit, onCommit: (Float) -> Unit, modifier: Modifier = Modifier,
             active: Color = C.Text, track: Color = C.Surface2, label: String = "播放进度", valueText: String = "") {
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val held = pressed || dragging
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val h by animateDpAsState(if (held) 8.dp else 4.dp, Motion.move(), label = "scrubH")
    val dot by animateDpAsState(if (held) 14.dp else 0.dp, Motion.move(), label = "scrubDot")
    var widthPx by remember { mutableFloatStateOf(1f) }
    var cur by remember { mutableFloatStateOf(value) }
    Box(modifier.fillMaxWidth().height(32.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        .semantics {
            contentDescription = label
            if (valueText.isNotEmpty()) stateDescription = valueText
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
            if (enabled) setProgress { v -> onCommit(v.coerceIn(0f, 1f)); true }
        }
        .then(if (!enabled) Modifier else Modifier
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { o -> commit((o.x / widthPx).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; cur = (o.x / widthPx).coerceIn(0f, 1f); change(cur) },
                    onDragEnd = { dragging = false; commit(cur) },
                    onDragCancel = { dragging = false; commit(cur) },
                ) { ch, dx -> ch.consume(); cur = (cur + dx / widthPx).coerceIn(0f, 1f); change(cur) }
            }),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(if (enabled) active else C.Faint))
        }
        if (dot > 0.dp) Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).wrapContentWidth(Alignment.End)) {
            Box(Modifier.offset(x = dot / 2).size(dot).clip(RoundedCornerShape(50)).background(active))
        }
    }
}

/** [key] 变化时（不含第一次）缩放弹一下：1 → 1.22 → 1，带一点回弹。 */
fun Modifier.popOnChange(key: Any?): Modifier = this.composed {
    val s = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        if (Motion.reduced) return@LaunchedEffect
        s.snapTo(0.82f)
        s.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f))
    }
    graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** 正在播放的三根小柱（[playing] 为 false 或「移除动画」时静止在不同高度）。 */
@Composable
fun NowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val anim = playing && !Motion.reduced
    val t = rememberInfiniteTransition(label = "bars")
    val a = if (anim) t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1").value else 0.55f
    val b = if (anim) t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "b2").value else 0.9f
    val c = if (anim) t.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "b3").value else 0.4f
    Row(modifier.size(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(a, b, c).forEach { f -> Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(1.dp)).background(color)) }
    }
}
