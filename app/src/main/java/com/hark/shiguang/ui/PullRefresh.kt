package com.hark.shiguang.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 全面审查 B7：下拉刷新（原来只有 WebDAV 照片页有）。影视、音乐、飞牛照片页用它包住列表。
 * [onRefresh] 只负责发起后台增量扫描（扫描进度仍由页内的「正在更新」一行显示），圆圈转一下就收起，不挡列表。
 * [enabled] 为 false（没有文件夹 / 正在扫描）时下拉不触发。
 * 1.7.12：指示器换成 [GlassRefreshIndicator]（玻璃圆片 + 强调色进度弧）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(enabled: Boolean, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val canRefresh by rememberUpdatedState(enabled)
    val refresh by rememberUpdatedState(onRefresh)
    val ptr = rememberPullToRefreshState(enabled = { canRefresh })
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) { refresh(); delay(700); ptr.endRefresh() }
    }
    // 1.7.26 F8：点当前底栏 Tab 时页面已经在顶部 → 当作下拉刷新（等一帧，让同一页的 ScrollToTopOnTab 先报告要不要滚动）
    val tick = ScrollTop.tick
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        if (ScrollTop.moved != tick && canRefresh && !ptr.isRefreshing) ptr.startRefresh()
    }
    Box(Modifier.fillMaxSize().nestedScroll(ptr.nestedScrollConnection)) {
        content()
        GlassRefreshIndicator(ptr, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * 1.7.12 玻璃下拉刷新指示器（DESIGN §7 / §9）：44dp 磨砂玻璃圆片，跟着手指从状态栏下方落下、由小长大；
 * 里面一道强调色弧随下拉长到 300°（同时转动，提示「再拉就刷新」），过了阈值弧变粗；刷新中弧持续旋转。
 * 不在 glassSource 外面，所以总是降级外观（着色 + 噪点 + 高光边）；简洁风格纯色圆片。
 * 移除动画时：不旋转、不缩放，刷新中显示一道静止的 270° 弧。读屏：出现时读「下拉刷新」，刷新中礼貌播报「正在刷新」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassRefreshIndicator(state: PullToRefreshState, modifier: Modifier = Modifier) {
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    val refreshing = state.isRefreshing
    val spin = if (refreshing && !Motion.reduced) {
        val inf = rememberInfiniteTransition(label = "ptrSpin")
        inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "ptrSpinA").value
    } else 0f
    // 1.7.26：拉过阈值的一瞬间轻震一下（像 iOS「再松手就刷新」）；刷新结束时圆片轻弹放大再缩小淡出，而不是直接被收走
    val view = androidx.compose.ui.platform.LocalView.current
    val armedNow = !refreshing && state.progress >= 1f
    LaunchedEffect(armedNow) { if (armedNow) Haptics.tick(view) }
    val done = remember { androidx.compose.animation.core.Animatable(0f) }
    var wasRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) { done.snapTo(0f); wasRefreshing = true; return@LaunchedEffect }
        if (!wasRefreshing) return@LaunchedEffect
        wasRefreshing = false
        if (Motion.reduced) return@LaunchedEffect
        done.snapTo(0.001f)
        done.animateTo(1f, tween(Motion.BURST - 140, easing = Motion.EaseOut))
        done.snapTo(0f)
    }
    val finishing by remember { androidx.compose.runtime.derivedStateOf { done.value > 0f } }
    val visible = refreshing || (!finishing && state.verticalOffset > 0.5f)
    val accent = C.Accent
    val track = C.Text.copy(alpha = 0.12f)
    Box(
        modifier
            .graphicsLayer {
                val p = state.progress.coerceIn(0f, 1.5f)
                // 落点：状态栏下方 8dp + 下拉距离；收起时向上退出屏幕
                val d = done.value
                if (d > 0f) {
                    // 收尾：停在刷新时的位置，先弹到 1.12 再缩到 0.6、同时淡出（约 380 ms）
                    translationY = statusTop + gapPx
                    val s = RefreshFinish.scale(d)
                    scaleX = s; scaleY = s
                    alpha = RefreshFinish.alpha(d)
                    return@graphicsLayer
                }
                translationY = statusTop + gapPx + state.verticalOffset - size.height
                alpha = if (refreshing) 1f else (p * 1.8f).coerceIn(0f, 1f)
                if (!Motion.reduced && !refreshing) { val s = 0.7f + 0.3f * p.coerceAtMost(1f); scaleX = s; scaleY = s }
            }
            .then(if (visible) Modifier.semantics {
                contentDescription = if (refreshing) "正在刷新" else "下拉刷新"
                if (refreshing) liveRegion = LiveRegionMode.Polite
            } else Modifier.clearAndSetSemantics { }),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.size(44.dp), shape = CircleShape, shadow = 8.dp) {
            Canvas(Modifier.size(44.dp)) {
                val p = state.progress.coerceIn(0f, 1f)
                val armed = refreshing || finishing || state.progress >= 1f
                val w = (if (armed) 2.6.dp else 2.dp).toPx()
                val r = 11.dp.toPx()
                val tl = Offset(center.x - r, center.y - r)
                val sz = Size(r * 2, r * 2)
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                val sweep = when {
                    // 刷新中：弧长在 100°–220° 之间呼吸，同时整体旋转
                    finishing -> 360f   // 收尾时弧合成一整圈：「好了」
                    refreshing -> if (Motion.reduced) 270f else 160f + 60f * kotlin.math.sin(Math.toRadians(spin * 2.0)).toFloat()
                    else -> 300f * p
                }
                val start = if (refreshing) spin - 90f else -90f + (if (Motion.reduced) 0f else 160f * p)
                drawArc(accent, start, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

/** 1.7.26 下拉刷新收尾曲线（[d] 0→1）：前 35% 从 1 弹到 1.12，之后缩到 0.6 并淡出。纯函数，有单测。 */
internal object RefreshFinish {
    private const val PEAK = 0.35f
    fun scale(d: Float): Float { val x = d.coerceIn(0f, 1f); return if (x < PEAK) 1f + 0.12f * (x / PEAK) else 1.12f - 0.52f * ((x - PEAK) / (1f - PEAK)) }
    fun alpha(d: Float): Float { val x = d.coerceIn(0f, 1f); return if (x < PEAK) 1f else 1f - (x - PEAK) / (1f - PEAK) }
}
