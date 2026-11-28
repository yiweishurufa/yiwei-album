package com.hark.shiguang.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.hark.shiguang.MusicState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 1.7.34 正在播放页 / 迷你播放条的手势：
 * - [SkipSwipe]：左右滑切歌的跟手位移（只动 graphicsLayer）；松手按 [SwipeMath.skipDecision] 切或回弹，切歌时旧封面飞出、新封面从对侧落位。
 * - [Modifier.mediaDrag]：轴锁定的拖动识别（先过触摸阈值再定横 / 竖），横向交给切歌，竖向交给调用方（下拉收起 / 上滑打开歌词或展开）。
 *   子组件（歌词列表滚动、进度条拖动）先消费了事件时整段手势让给子组件。
 * 「移除动画」时：不做飞出 / 落位，直接切歌；跟手位移仍保留（手指在动，不算动画）。
 */
@Stable
class SkipSwipe {
    private val anim = Animatable(0f)
    private var raw by mutableFloatStateOf(0f)
    private var dragging by mutableStateOf(false)
    var width = 0f

    /** 现在该画的横向位移（px）：拖动中跟手（带阻尼），松手后跟动画。只在绘制阶段读。 */
    val offset: Float get() = if (dragging) SwipeMath.skipOffset(raw, canPrev(), canNext()) else anim.value

    fun canPrev() = MusicState.canSkip(-1)
    fun canNext() = MusicState.canSkip(1)

    fun drag(delta: Float) { if (!dragging) { dragging = true; raw = anim.value }; raw += delta }

    /** 松手。[vxDp] 速度 dp/s。返回 -1 / 0 / 1（切到上一首 / 回弹 / 下一首）。 */
    suspend fun release(vxDp: Float, onSkip: (Int) -> Unit = {}): Int {
        val total = raw
        anim.snapTo(SwipeMath.skipOffset(total, canPrev(), canNext()))
        dragging = false; raw = 0f
        val dir = SwipeMath.skipDecision(total, vxDp, width, canPrev(), canNext())
        if (dir == 0) { anim.animateTo(0f, Motion.settle()); return 0 }
        onSkip(dir)
        val before = MusicState.mediaId
        if (Motion.reduced || width <= 0f) {
            if (dir > 0) MusicState.next() else MusicState.prevTrack()
            anim.snapTo(0f); return dir
        }
        anim.animateTo(-dir * width, tween(Motion.EXIT, easing = Motion.EaseOut))
        if (dir > 0) MusicState.next() else MusicState.prevTrack()
        // 等新歌真的换上（封面 / 歌名跟着变）再从对侧落位，最多等 0.5 秒
        withTimeoutOrNull(500) { snapshotFlow { MusicState.mediaId }.first { it != before } }
        anim.snapTo(dir * width * 0.6f)
        anim.animateTo(0f, Motion.settle())
        return dir
    }
}

@Composable
fun rememberSkipSwipe(): SkipSwipe = remember { SkipSwipe() }

/**
 * 轴锁定拖动。[horizontal] 为 false 时横向手势不接（让给别人）；[vertical] 同理。
 * 回调里的位移单位 px，速度单位 px/s。
 */
fun Modifier.mediaDrag(
    key: Any?,
    horizontal: Boolean,
    vertical: Boolean,
    onHorizontal: (delta: Float) -> Unit = {},
    onHorizontalEnd: (total: Float, velocity: Float) -> Unit = { _, _ -> },
    onVertical: (delta: Float) -> Unit = {},
    onVerticalEnd: (total: Float, velocity: Float) -> Unit = { _, _ -> },
): Modifier = this.pointerInput(key, horizontal, vertical) {
    axisDrag(horizontal, vertical, onHorizontal, onHorizontalEnd, onVertical, onVerticalEnd)
}

private suspend fun PointerInputScope.axisDrag(
    horizontal: Boolean, vertical: Boolean,
    onH: (Float) -> Unit, onHEnd: (Float, Float) -> Unit,
    onV: (Float) -> Unit, onVEnd: (Float, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var axis = 0          // 0 未定 / 1 横 / 2 竖
        var acc = Offset.Zero
        var total = 0f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (axis == 0 && ch.isConsumed) return@awaitEachGesture   // 子组件（列表滚动 / 进度条）先接了
            val d = ch.positionChange()
            tracker.addPosition(ch.uptimeMillis, ch.position)
            if (axis == 0) {
                acc += d
                if (acc.getDistance() < slop) continue
                axis = if (abs(acc.x) > abs(acc.y)) 1 else 2
                if ((axis == 1 && !horizontal) || (axis == 2 && !vertical)) return@awaitEachGesture
                ch.consume()
                // 过阈值那一下的位移也算进去，跟手不丢一截
                if (axis == 1) { total = acc.x; onH(acc.x) } else { total = acc.y; onV(acc.y) }
                continue
            }
            ch.consume()
            if (axis == 1) { total += d.x; onH(d.x) } else { total += d.y; onV(d.y) }
        }
        if (axis != 0) {
            val v = runCatching { tracker.calculateVelocity() }.getOrNull()
            if (axis == 1) onHEnd(total, v?.x ?: 0f) else onVEnd(total, v?.y ?: 0f)
        }
    }
}
