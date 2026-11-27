package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerZoomTest {
    private val eps = 0.01f

    @Test fun fitPortraitOnPortraitScreenFillsWidth() {
        val (w, h) = ViewerZoom.fit(3f / 4f, 1080f, 2400f)
        assertEquals(1080f, w, eps); assertEquals(1440f, h, eps)
    }

    @Test fun fitPanoramaFillsWidthLandscapeScreenFillsHeight() {
        val (w, h) = ViewerZoom.fit(4f, 2400f, 1080f)
        assertEquals(2400f, w, eps); assertEquals(600f, h, eps)
        val (w2, h2) = ViewerZoom.fit(1f, 2400f, 1080f)
        assertEquals(1080f, w2, eps); assertEquals(1080f, h2, eps)
    }

    @Test fun unknownRatioFillsBox() {
        assertEquals(1080f to 2400f, ViewerZoom.fit(0f, 1080f, 2400f))
    }

    @Test fun maxScaleFollowsPixels() {
        // 12 MP 4000×3000 竖着显示在 1080 宽的屏幕上：长边 4000 / 1440 ≈ 2.78 → ×2 ≈ 5.56
        assertEquals(5.56f, ViewerZoom.maxScale(3000, 4000, 1080f, 1440f), 0.02f)
        // 50 MP 大图封顶 8
        assertEquals(8f, ViewerZoom.maxScale(8160, 6120, 1080f, 1440f), eps)
        // 小图（微信缩略图 640×480）至少 2.5
        assertEquals(2.5f, ViewerZoom.maxScale(640, 480, 1080f, 810f), eps)
    }

    @Test fun maxScaleIgnoresExifRotation() {
        assertEquals(ViewerZoom.maxScale(3000, 4000, 1080f, 1440f), ViewerZoom.maxScale(4000, 3000, 1080f, 1440f), eps)
    }

    @Test fun unknownPixelsKeepLegacyMax() {
        assertEquals(6f, ViewerZoom.maxScale(0, 0, 1080f, 1440f), eps)
    }

    @Test fun doubleTapNeverExceedsMax() {
        assertEquals(2.6f, ViewerZoom.doubleTap(8f), eps)
        assertEquals(2.5f, ViewerZoom.doubleTap(2.5f), eps)
    }

    @Test fun panStopsAtPhotoEdgeNotBlackBars() {
        // 横图 4:3 在竖屏 1080×2400 上显示为 1080×810；放大 2 倍后 2160×1620：左右各能拖 540，上下 1620 < 2400 不能拖
        val (mx, my) = ViewerZoom.panLimit(2f, 1080f, 2400f, 1080f, 810f)
        assertEquals(540f, mx, eps); assertEquals(0f, my, eps)
        // 放大 4 倍：3240 高 → 上下各 420
        assertEquals(420f, ViewerZoom.panLimit(4f, 1080f, 2400f, 1080f, 810f).second, eps)
    }

    @Test fun noPanAtScaleOne() {
        assertEquals(0f to 0f, ViewerZoom.panLimit(1f, 1080f, 2400f, 1080f, 2400f))
    }
}
