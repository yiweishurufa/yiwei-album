package com.hark.shiguang

import kotlin.math.max
import kotlin.math.min

/**
 * 1.7.18 大图缩放的几何（纯函数，有单测）。
 *
 * - 最大倍数按照片像素算（参考 Immich 3.4「按图片像素限制最大缩放」）：放到「照片 1 像素 = 屏幕 2 像素」为止，
 *   至少 [MIN_MAX]（小图也能看清一点），最多 [MAX_MAX]。像素未知时保持以前的 [LEGACY_MAX]。
 *   用长边比，照片宽高是否已按 EXIF 旋转都不影响结果。
 * - 拖动范围按照片在屏幕上实际显示的矩形算：只能拖到照片边缘，不再拖进上下 / 左右的黑边。
 */
object ViewerZoom {
    const val MIN_MAX = 2.5f
    const val MAX_MAX = 8f
    const val LEGACY_MAX = 6f
    const val DOUBLE_TAP = 2.6f

    /** 照片按 Fit 显示在 boxW × boxH 里的尺寸；ratio = 宽 / 高，≤ 0 表示未知（按铺满整个框）。 */
    fun fit(ratio: Float, boxW: Float, boxH: Float): Pair<Float, Float> {
        if (ratio <= 0f || boxW <= 0f || boxH <= 0f) return boxW to boxH
        return if (boxW / boxH > ratio) (boxH * ratio) to boxH else boxW to (boxW / ratio)
    }

    /** 最大倍数。photoW / photoH 是原图像素（任一 ≤ 0 视为未知），fitW / fitH 是 [fit] 的结果（屏幕像素）。 */
    fun maxScale(photoW: Int, photoH: Int, fitW: Float, fitH: Float): Float {
        if (photoW <= 0 || photoH <= 0 || fitW <= 0f || fitH <= 0f) return LEGACY_MAX
        val native = max(photoW, photoH) / max(fitW, fitH)
        return (native * 2f).coerceIn(MIN_MAX, MAX_MAX)
    }

    /** 双击放大到的倍数：以前固定 2.6，不超过这张照片的上限。 */
    fun doubleTap(maxScale: Float): Float = min(DOUBLE_TAP, maxScale)

    /** 放大 scale 倍后允许的平移（以框中心为原点，正负对称）：照片放大后比框大多少，一半就是能拖的距离；不比框大就不能拖。 */
    fun panLimit(scale: Float, boxW: Float, boxH: Float, fitW: Float, fitH: Float): Pair<Float, Float> =
        max(0f, (fitW * scale - boxW) / 2f) to max(0f, (fitH * scale - boxH) / 2f)
}
