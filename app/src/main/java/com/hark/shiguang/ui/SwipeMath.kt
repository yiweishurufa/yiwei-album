package com.hark.shiguang.ui

import kotlin.math.abs

/**
 * 1.7.34 正在播放 / 迷你播放条的滑动手势判定（纯计算，便于单测）。
 * - 左右滑切歌：位移超过宽度的 [SKIP_FRACTION] 或甩动速度超过 [SKIP_VELOCITY_DP] 就切；方向上没有歌（列表首 / 尾且不循环）时加阻尼、不切。
 * - 下拉收起：位移超过高度的 [DISMISS_FRACTION] 或速度超过 [DISMISS_VELOCITY_DP] 就收起。
 * 速度单位 dp/s，位移单位 px（调用方传 density）。
 */
internal object SwipeMath {
    const val SKIP_FRACTION = 0.30f
    const val SKIP_VELOCITY_DP = 800f
    const val DISMISS_FRACTION = 0.25f
    const val DISMISS_VELOCITY_DP = 1200f
    /** 往上滑多远（dp）算「上滑」（封面上 = 打开歌词，迷你条上 = 展开）。 */
    const val UP_DP = 56f
    /** 方向上没有歌时，跟手位移只给手指位移的这个比例。 */
    const val RESIST = 0.35f

    /** -1 = 上一首（手指往右），+1 = 下一首（手指往左），0 = 回弹。[dx] 手指位移（右正），[vx] 速度 dp/s。 */
    fun skipDecision(dx: Float, vxDp: Float, widthPx: Float, canPrev: Boolean, canNext: Boolean): Int {
        if (widthPx <= 0f) return 0
        val far = abs(dx) > widthPx * SKIP_FRACTION
        // 甩动：速度够且方向与位移一致（往回甩不算）
        val fling = abs(vxDp) > SKIP_VELOCITY_DP && (dx == 0f || vxDp * dx > 0f)
        if (!far && !fling) return 0
        val dir = if (dx != 0f) (if (dx < 0f) 1 else -1) else (if (vxDp < 0f) 1 else -1)
        return when {
            dir == 1 && canNext -> 1
            dir == -1 && canPrev -> -1
            else -> 0
        }
    }

    /** 跟手位移：方向上没有歌时加阻尼。 */
    fun skipOffset(dx: Float, canPrev: Boolean, canNext: Boolean): Float = when {
        dx < 0f && !canNext -> dx * RESIST
        dx > 0f && !canPrev -> dx * RESIST
        else -> dx
    }

    fun shouldDismiss(dy: Float, vyDp: Float, heightPx: Float): Boolean {
        if (dy <= 0f || heightPx <= 0f) return false
        return dy > heightPx * DISMISS_FRACTION || (vyDp > DISMISS_VELOCITY_DP && dy > 0f)
    }

    /** 下拉进度 0..1（用于缩放 / 圆角），以收起阈值为 1。 */
    fun dismissProgress(dy: Float, heightPx: Float): Float =
        if (heightPx <= 0f) 0f else (dy / (heightPx * DISMISS_FRACTION)).coerceIn(0f, 1f)

    /**
     * 这个方向上有没有歌。与 media3 seekToNext/PreviousMediaItem 的规则一致：列表循环或随机时总有（队列 ≥ 2 首）；
     * 单曲循环在导航时按「不循环」算；顺序播放到头 / 到尾就没有。
     */
    fun canGo(dir: Int, index: Int, size: Int, repeatAll: Boolean, shuffle: Boolean): Boolean {
        if (size < 2 || index !in 0 until size) return false
        if (repeatAll || shuffle) return true
        return if (dir > 0) index < size - 1 else index > 0
    }
}
