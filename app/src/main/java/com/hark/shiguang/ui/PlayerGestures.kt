package com.hark.shiguang.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 用户 2026-10-23 反馈：播放器手势对标腾讯视频 / 爱奇艺。包在 PlayerView 外面的一层，统一分配触摸：
 *
 * - 按下点落在控制栏的按钮 / 进度条上 → 整个手势交给 PlayerView（按钮照常点、进度条照常拖）。
 * - 否则这层接管：单击 = 显示 / 隐藏控件（等双击超时才算单击），双击 = 暂停 / 播放，
 *   长按 = 临时 2 倍速，横滑 = 快进快退（松手才跳），左半屏竖滑 = 亮度，右半屏竖滑 = 音量。
 * - 起点在系统手势区（左右边缘返回手势、上下边缘的状态栏 / 导航条）时不做滑动和长按，只认点击。
 * - [gesturesOn] = false（画中画）时什么都不接管。
 *
 * 动作本身（改音量、亮度、跳转）由 [Callbacks] 在 Compose 那边做，这里只认手势。
 */
@SuppressLint("ViewConstructor")
class PlayerGestureLayer(ctx: Context, val playerView: PlayerView) : FrameLayout(ctx) {

    interface Callbacks {
        fun onTap()
        fun onDoubleTap()
        /** true = 长按生效（正在播放时才变速）。 */
        fun onLongPressStart(): Boolean
        fun onLongPressEnd()
        /** false = 这次不能拖（时长未知）。 */
        fun onSeekStart(): Boolean
        fun onSeekMove(dxPx: Float, widthPx: Int)
        fun onSeekEnd(commit: Boolean)
        fun onVerticalStart(brightness: Boolean)
        fun onVerticalMove(upPx: Float, heightPx: Int)
        fun onVerticalEnd()
    }

    var callbacks: Callbacks? = null
    var gesturesOn = true

    private enum class Mode { NONE, IDLE, SEEK, VERTICAL, LONG, IGNORED }

    private val vc = ViewConfiguration.get(ctx)
    private val slop = vc.scaledTouchSlop.toFloat()
    private val doubleTapSlop = vc.scaledDoubleTapSlop.toFloat()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val dp = resources.displayMetrics.density

    private var mode = Mode.NONE
    private var downX = 0f; private var downY = 0f
    /** Origin of the drag once it is recognised (so the value does not jump by the touch slop). */
    private var originX = 0f; private var originY = 0f
    private var nearEdge = false
    private var secondTap = false
    private var lastTapUpAt = 0L; private var lastTapX = 0f; private var lastTapY = 0f

    private val singleTap = Runnable { callbacks?.onTap() }
    private val longPress = Runnable {
        if (mode == Mode.IDLE && !secondTap && callbacks?.onLongPressStart() == true) {
            mode = Mode.LONG
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    init {
        addView(playerView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            mode = if (!gesturesOn || hitsControl(ev.rawX, ev.rawY)) Mode.NONE else Mode.IDLE
        }
        // claim the whole gesture at DOWN: PlayerView never sees it, so its own tap-to-toggle does not fire twice
        return mode != Mode.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        val cb = callbacks
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                nearEdge = inGestureEdge(ev.x, ev.y)
                val now = SystemClock.uptimeMillis()
                secondTap = now - lastTapUpAt <= doubleTapMs && hypot(ev.x - lastTapX, ev.y - lastTapY) <= doubleTapSlop
                if (secondTap) removeCallbacks(singleTap)
                if (!nearEdge && !secondTap) postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> if (mode == Mode.IDLE) {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (hypot(dx, dy) > slop) {
                    removeCallbacks(longPress)
                    if (nearEdge || secondTap || cb == null) mode = Mode.IGNORED
                    else {
                        originX = ev.x; originY = ev.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        if (abs(dx) > abs(dy)) {
                            mode = if (cb.onSeekStart()) Mode.SEEK else Mode.IGNORED
                        } else {
                            mode = Mode.VERTICAL
                            cb.onVerticalStart(brightness = downX < width / 2f)
                        }
                    }
                }
            } else when (mode) {
                Mode.SEEK -> cb?.onSeekMove(ev.x - originX, width)
                Mode.VERTICAL -> cb?.onVerticalMove(originY - ev.y, height)
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (mode) {
                    Mode.LONG -> cb?.onLongPressEnd()
                    Mode.SEEK -> cb?.onSeekEnd(commit = true)
                    Mode.VERTICAL -> cb?.onVerticalEnd()
                    Mode.IDLE -> if (secondTap) {
                        lastTapUpAt = 0L
                        cb?.onDoubleTap()
                    } else {
                        lastTapUpAt = SystemClock.uptimeMillis(); lastTapX = ev.x; lastTapY = ev.y
                        postDelayed(singleTap, doubleTapMs)
                    }
                    else -> Unit
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                when (mode) {
                    Mode.LONG -> cb?.onLongPressEnd()
                    Mode.SEEK -> cb?.onSeekEnd(commit = false)
                    Mode.VERTICAL -> cb?.onVerticalEnd()
                    else -> Unit
                }
                mode = Mode.NONE
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(singleTap); removeCallbacks(longPress)
        if (mode == Mode.LONG) callbacks?.onLongPressEnd()
        if (mode == Mode.SEEK) callbacks?.onSeekEnd(commit = false)
        mode = Mode.NONE
        super.onDetachedFromWindow()
    }

    /** Start point inside the system gesture areas (back swipe on the sides, status bar / home bar at top and bottom). */
    private fun inGestureEdge(x: Float, y: Float): Boolean {
        val ins = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val side = 24 * dp; val vert = 28 * dp
        val l = maxOf(ins?.left?.toFloat() ?: 0f, side); val r = maxOf(ins?.right?.toFloat() ?: 0f, side)
        val t = maxOf(ins?.top?.toFloat() ?: 0f, vert); val b = maxOf(ins?.bottom?.toFloat() ?: 0f, vert)
        return x < l || x > width - r || y < t || y > height - b
    }

    private val tmpLoc = IntArray(2)
    private val tmpRect = Rect()

    /** The touch lands on a visible button / time bar of the media3 controller. */
    private fun hitsControl(rawX: Float, rawY: Float): Boolean {
        val controller = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller) ?: return false
        if (!controller.isShown) return false
        return hit(controller, rawX, rawY, root = true)
    }

    @androidx.annotation.OptIn(UnstableApi::class)   // TimeBar
    private fun hit(v: View, x: Float, y: Float, root: Boolean): Boolean {
        if (!v.isShown || v.alpha < 0.05f) return false
        if (!root && (v.isClickable || v is TimeBar)) {
            v.getLocationOnScreen(tmpLoc)
            val pad = if (v is TimeBar) (12 * dp).toInt() else 0
            tmpRect.set(tmpLoc[0] - pad, tmpLoc[1] - pad, tmpLoc[0] + v.width + pad, tmpLoc[1] + v.height + pad)
            if (tmpRect.contains(x.toInt(), y.toInt())) return true
        }
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) if (hit(v.getChildAt(i), x, y, false)) return true
        return false
    }
}
